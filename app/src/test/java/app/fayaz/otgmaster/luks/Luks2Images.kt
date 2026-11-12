package app.fayaz.otgmaster.luks

import app.fayaz.otgmaster.block.RawBlockDevice
import org.json.JSONObject
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer

/**
 * LUKS2 images for host tests: made by cryptsetup, then edited the way a damaged or
 * unusual header would look, with both header copies' checksums recomputed so that only
 * the edit under test differs (cryptsetup itself accepts the edited image).
 */
object Luks2Images {
    const val PASSWORD = "password123"
    val repo: File = generateSequence(File("").absoluteFile) { it.parentFile }
        .first { File(it, "scripts/build_host_native.sh").exists() }

    /** True once the host native library is built and cryptsetup is present. */
    val ready: Boolean by lazy {
        if (run("sh", "-c", "command -v gcc && command -v cryptsetup").first != 0) return@lazy false
        val lib = File(repo, "app/build/host-native/libotg-host.so")
        val (rc, out) = run(File(repo, "scripts/build_host_native.sh").path, lib.path)
        if (rc != 0) throw AssertionError("host native build failed:\n$out")
        System.setProperty("otg.native.hostlib", lib.absolutePath)
        true
    }

    fun run(vararg cmd: String, input: String? = null): Pair<Int, String> {
        val p = ProcessBuilder(*cmd).redirectErrorStream(true).start()
        input?.let { p.outputStream.use { o -> o.write(it.toByteArray()) } }
        val out = p.inputStream.bufferedReader().readText()
        return p.waitFor() to out
    }

    /** A cryptsetup LUKS2 image of [megabytes] MiB with a PBKDF2 keyslot and 16 KiB headers. */
    fun format(file: File, vararg extra: String, megabytes: Int = 24): File {
        RandomAccessFile(file, "rw").use { it.setLength(megabytes.toLong() shl 20) }
        // A quick PBKDF2 slot unless the caller picks its own KDF.
        val kdf = if ("--pbkdf" in extra) emptyList() else listOf("--pbkdf", "pbkdf2", "--iter-time", "50")
        val args = listOf("cryptsetup", "luksFormat", "--type", "luks2", "--batch-mode", "--disable-locks",
            "--cipher", "aes-xts-plain64", "--key-size", "512",
            "--luks2-metadata-size", "16k", "--luks2-keyslots-size", "1m", "--sector-size", "512") +
            kdf + extra.toList() + listOf(file.path, "-")
        val (rc, out) = run(*args.toTypedArray(), input = PASSWORD)
        if (rc != 0) throw AssertionError("luksFormat failed: $out")
        return file
    }

    private const val HDR = 16384

    /** Rewrites the JSON of both header copies with [edit], recomputing their checksums. */
    fun editJson(file: File, edit: (JSONObject) -> Unit) {
        RandomAccessFile(file, "rw").use { raf ->
            for (offset in listOf(0L, HDR.toLong())) {
                val copy = ByteArray(HDR).also { raf.seek(offset); raf.readFully(it) }
                val json = JSONObject(String(copy, 4096, HDR - 4096, Charsets.UTF_8).trimEnd('\u0000'))
                edit(json)
                val bytes = json.toString().toByteArray(Charsets.UTF_8)
                require(bytes.size < HDR - 4096) { "edited JSON does not fit" }
                copy.fill(0, 4096, HDR)
                bytes.copyInto(copy, 4096)
                raf.seek(offset); raf.write(Luks2Metadata.withChecksum(copy))
            }
        }
    }

    /** Sets the sequence id of the copy at [offset] and recomputes its checksum. */
    fun setSeqId(file: File, offset: Long, seqId: Long) = rewriteCopy(file, offset) { ByteBuffer.wrap(it).putLong(16, seqId) }

    /** Rewrites the JSON of only the copy at [offset], recomputing its checksum. */
    fun editJsonOf(file: File, offset: Long, edit: (JSONObject) -> Unit) = rewriteCopy(file, offset) { copy ->
        val json = JSONObject(String(copy, 4096, HDR - 4096, Charsets.UTF_8).trimEnd('\u0000'))
        edit(json)
        copy.fill(0, 4096, HDR)
        json.toString().toByteArray(Charsets.UTF_8).copyInto(copy, 4096)
    }

    private fun rewriteCopy(file: File, offset: Long, change: (ByteArray) -> Unit) {
        RandomAccessFile(file, "rw").use { raf ->
            val copy = ByteArray(HDR).also { raf.seek(offset); raf.readFully(it) }
            change(copy)
            raf.seek(offset); raf.write(Luks2Metadata.withChecksum(copy))
        }
    }

    /** Flips one byte at [offset] without touching any checksum. */
    fun corrupt(file: File, offset: Long) {
        RandomAccessFile(file, "rw").use { raf ->
            raf.seek(offset); val b = raf.read(); raf.seek(offset); raf.write(b xor 0xFF)
        }
    }

    /** The image as a 512-byte-block device. */
    class FileDevice(file: File) : RawBlockDevice {
        private val raf = RandomAccessFile(file, "rw")
        override val blockSize = 512
        override val blockCount = file.length() / 512
        override fun readBlocks(startBlock: Long, blockCount: Int): ByteArray {
            require(startBlock >= 0 && startBlock + blockCount <= this.blockCount) { "read past the image" }
            val out = ByteArray(blockCount * 512); raf.seek(startBlock * 512); raf.readFully(out); return out
        }
        override fun writeBlocks(startBlock: Long, data: ByteArray) { raf.seek(startBlock * 512); raf.write(data) }
        override fun close() = raf.close()
    }

    /** Unlocks [file] with the app's LuksUnlocker. */
    fun unlock(file: File, password: String = PASSWORD, availableMemoryBytes: Long? = null): RawBlockDevice {
        val dev = FileDevice(file)
        return LuksUnlocker().unlock(dev, 0, dev.blockCount, password.toByteArray(), availableMemoryBytes)
    }
}
