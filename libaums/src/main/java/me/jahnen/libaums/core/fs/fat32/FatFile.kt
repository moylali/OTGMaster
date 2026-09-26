/*
 * (C) Copyright 2014-2016 mjahnen <github@mgns.tech>
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 * 
 */

package me.jahnen.libaums.core.fs.fat32

import me.jahnen.libaums.core.driver.BlockDeviceDriver
import me.jahnen.libaums.core.fs.AbstractUsbFile
import me.jahnen.libaums.core.fs.UsbFile
import java.io.IOException
import java.nio.ByteBuffer

class FatFile
/**
 * Constructs a new file with the given information.
 *
 * @param blockDevice
 * The device where the file system is located.
 * @param fat
 * The FAT used to follow cluster chains.
 * @param bootSector
 * The boot sector of the file system.
 * @param entry
 * The corresponding entry in a FAT directory.
 * @param parent
 * The parent directory of the newly constructed file.
 */
internal constructor(private val blockDevice: BlockDeviceDriver, private val fat: FAT, private val bootSector: Fat32BootSector,
                    private val entry: FatLfnDirectoryEntry, override var parent: FatDirectory?) : AbstractUsbFile() {
    private lateinit var chain: ClusterChain

    /** Whether this handle has changed file contents or metadata. See close(). */
    private var dirty = false

    override val isDirectory: Boolean
        get() = false

    override var name: String
        get() = entry.name
        @Throws(IOException::class)
        set(newName) = parent!!.renameEntry(entry, newName)

    override var length: Long
        get() = entry.fileSize
        @Throws(IOException::class)
        set(newLength) {
            initChain()
            dirty = true
            chain.length = newLength
            entry.fileSize = newLength
            // LOCAL PATCH (docs/VENDOR_FIXES.md V2): keep the directory entry's start
            // cluster in step with the chain.
            //
            // ClusterChain replaces its whole chain array when allocating or freeing,
            // so the first cluster can change -- and truncating to 0 frees every
            // cluster, after which a rewrite allocates a brand new one. Without this,
            // flush() wrote the entry back still naming the original, now-freed
            // cluster: the file read as garbage after remount, or cross-linked into
            // whatever had since been given that cluster. SAF truncates on every
            // mode "t" open, so this was a routine path, not an edge case.
            entry.startCluster = chain.firstCluster
        }

    override val isRoot: Boolean
        get() = false

    /**
     * Initializes the cluster chain to access the contents of the file.
     *
     * @throws IOException
     * If reading from FAT fails.
     */
    @Throws(IOException::class)
    private fun initChain() {
        if (!::chain.isInitialized) {
            chain = ClusterChain(entry.startCluster, blockDevice, fat, bootSector)
        }
    }

    override fun createdAt(): Long {
        return entry.actualEntry.createdDateTime
    }

    override fun lastModified(): Long {
        return entry.actualEntry.lastModifiedDateTime
    }

    override fun lastAccessed(): Long {
        return entry.actualEntry.lastAccessedDateTime
    }

    override fun list(): Array<String> {
        throw UnsupportedOperationException("This is a file!")
    }

    @Throws(IOException::class)
    override fun listFiles(): Array<UsbFile> {
        throw UnsupportedOperationException("This is a file!")
    }

    @Throws(IOException::class)
    override fun read(offset: Long, destination: ByteBuffer) {
        initChain()
        // LOCAL PATCH (docs/VENDOR_FIXES.md V3): bound the read to the file.
        //
        // ClusterChain.read fills whatever buffer it is handed, cluster by cluster,
        // with no reference to fileSize. A 4 KiB read of a 100-byte file therefore
        // returned 3,996 bytes of cluster slack — on an encrypted volume that is the
        // decrypted plaintext of whatever previously occupied the cluster, handed to
        // the caller as file content. Reading at or past EOF also indexed past the
        // end of the chain array.
        val length = entry.fileSize
        if (offset >= length) return
        // Long math throughout: (length - offset).toInt() overflows to negative for a
        // file of 2 GiB or more, which made every read of one return zero bytes. The
        // min is taken before narrowing, and remaining() is already an Int, so the
        // result always fits.
        val available = minOf(length - offset, destination.remaining().toLong()).toInt()
        if (available <= 0) return
        val limitBefore = destination.limit()
        destination.limit(destination.position() + available)
        try {
            chain.read(offset, destination)
        } finally {
            destination.limit(limitBefore)
        }
        // LOCAL PATCH (V4): only mark the access time when it will be written anyway.
        // Touching it here made every read dirty the entry, which close() then
        // flushed — see below.
    }

    @Throws(IOException::class)
    override fun write(offset: Long, source: ByteBuffer) {
        initChain()
        dirty = true
        val length = offset + source.remaining()
        if (length > this.length)
            this.length = length
        entry.setLastModifiedTimeToNow()
        chain.write(offset, source)
        // The length setter above syncs startCluster when it grows the chain, but a
        // write that fits the existing allocation skips it. Re-assert, cheaply, so a
        // first write into a freshly truncated file cannot leave a stale entry.
        entry.startCluster = chain.firstCluster
    }

    @Throws(IOException::class)
    override fun flush() {
        // we only have to update the parent because we are always writing
        // everything
        // immediately to the device
        // the parent directory is responsible for updating the
        // FatDirectoryEntry which
        // contains things like the file size and the date time fields
        parent!!.write()
    }

    @Throws(IOException::class)
    override fun close() {
        // LOCAL PATCH (docs/VENDOR_FIXES.md V4): only flush a handle that changed
        // something.
        //
        // close() flushed unconditionally, and flush() calls parent!!.write(), which
        // serialises and rewrites the *entire* parent directory table. Combined with
        // read() touching the access time, purely reading a file rewrote the whole
        // directory: opening 100 files in a 1,000-entry directory rewrote it 100
        // times. That is write amplification on a bus already measured at ~5 MB/s,
        // and needless flash wear. ExFatFile has tracked a dirty flag for this since
        // a492960.
        if (!dirty) return
        flush()
        dirty = false
    }

    @Throws(IOException::class)
    override fun createDirectory(name: String): UsbFile {
        throw UnsupportedOperationException("This is a file!")
    }

    @Throws(IOException::class)
    override fun createFile(name: String): UsbFile {
        throw UnsupportedOperationException("This is a file!")
    }

    @Throws(IOException::class)
    override fun moveTo(destination: UsbFile) {
        parent!!.move(entry, destination)
        parent = destination as FatDirectory
    }

    @Throws(IOException::class)
    override fun delete() {
        initChain()
        parent!!.removeEntry(entry)
        parent!!.write()
        chain.length = 0
    }

}
