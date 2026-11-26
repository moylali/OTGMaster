package app.fayaz.otgmaster.backup.restic

import android.os.Binder
import me.jahnen.libaums.core.fs.AbstractUsbFile
import me.jahnen.libaums.core.fs.FileSystem
import me.jahnen.libaums.core.fs.UsbFile
import java.io.IOException
import java.nio.ByteBuffer
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * A restic repository shown as a read-only drive ("Mount a backup"): its top level has
 * one folder per snapshot, named by date and short id, and each holds that snapshot's
 * files as they were backed up. The Files app browses it and other apps open files from
 * it like any mounted partition; nothing is restored to the phone first.
 *
 * Files are read blob by blob: the offset is found from the index's blob sizes, so a
 * video can be played from its middle without reading what comes before. The last few
 * blobs read are kept, since apps read a file in small sequential pieces and a blob is
 * up to 8 MiB. Every blob read is checked against its hash ([ResticRepository.blob]).
 *
 * All repository access goes through [access]. It holds [lock], because the repository's
 * index and its backend (a document tree on another drive) are not thread-safe and the
 * Files app reads from several threads. It also clears the calling identity: a Files app
 * request arrives here on a binder thread as the Files app, and the backend's document
 * calls would be checked against that app, which has no grant on the repository's folder.
 * The grant is this app's, so they must run as this app.
 */
class ResticFileSystem(val repo: ResticRepository, private val label: String) : FileSystem {
    internal val lock = Any()
    private val cache = object : LinkedHashMap<String, ByteArray>(8, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ByteArray>) = size > 4
    }

    private inline fun <T> access(block: () -> T): T {
        val caller = Binder.clearCallingIdentity()
        try {
            return synchronized(lock) { block() }
        } finally {
            Binder.restoreCallingIdentity(caller)
        }
    }

    internal fun blob(id: String): ByteArray = access { cache[id] ?: repo.blob(id).also { cache[id] = it } }
    internal fun tree(id: String): List<ResticRepository.Node> = access { repo.tree(id) }
    internal fun blobSize(id: String): Int = access { repo.blobSize(id) }

    private val snapshots: List<ResticRepository.Snapshot> by lazy { access { repo.snapshots() } }

    override val rootDirectory: UsbFile = SnapshotsRoot()
    override val volumeLabel: String get() = label
    override val capacity: Long get() = 0L
    override val freeSpace: Long get() = 0L
    override val occupiedSpace: Long get() = 0L
    override val chunkSize: Int get() = 1 shl 20
    override val type: Int get() = -1

    private inner class SnapshotsRoot : ReadOnlyFile(null) {
        override val isDirectory get() = true
        override val isRoot get() = true
        override var name: String
            get() = ""
            set(_) = readOnly()
        override fun listFiles(): Array<UsbFile> = snapshots.map { SnapshotDir(it, this) }.toTypedArray()
        override fun lastModified() = snapshots.lastOrNull()?.let { millis(it.time) } ?: 0L
    }

    private inner class SnapshotDir(val snapshot: ResticRepository.Snapshot, parent: UsbFile) : ReadOnlyFile(parent) {
        override val isDirectory get() = true
        override var name: String
            get() = snapshotName(snapshot)
            set(_) = readOnly()
        override fun listFiles(): Array<UsbFile> = tree(snapshot.tree).map { node(it, this) }.toTypedArray()
        override fun lastModified() = millis(snapshot.time)
    }

    private fun node(n: ResticRepository.Node, parent: UsbFile): UsbFile =
        if (n.type == "dir") NodeDir(n, parent) else NodeFile(n, parent)

    private inner class NodeDir(val node: ResticRepository.Node, parent: UsbFile) : ReadOnlyFile(parent) {
        override val isDirectory get() = true
        override var name: String
            get() = node.name
            set(_) = readOnly()
        override fun listFiles(): Array<UsbFile> =
            (node.subtree?.let { tree(it) } ?: emptyList()).map { node(it, this) }.toTypedArray()
        override fun lastModified() = node.mtime?.let { millis(it) } ?: 0L
    }

    private inner class NodeFile(val node: ResticRepository.Node, parent: UsbFile) : ReadOnlyFile(parent) {
        override val isDirectory get() = false
        override var name: String
            get() = node.name
            set(_) = readOnly()
        override var length: Long
            get() = if (node.type == "file") node.size else 0L
            set(_) = readOnly()
        override fun listFiles(): Array<UsbFile> = throw IOException("$name is not a folder")
        override fun lastModified() = node.mtime?.let { millis(it) } ?: 0L

        /** Start offset of each blob, worked out once from the index. */
        private val starts: LongArray by lazy {
            val ids = node.content
            val out = LongArray(ids.size + 1)
            for (i in ids.indices) out[i + 1] = out[i] + blobSize(ids[i])
            out
        }

        override fun read(offset: Long, destination: ByteBuffer) {
            if (node.type != "file") throw IOException("$name is a ${node.type}, not a file")
            val ids = node.content
            var at = offset
            while (destination.hasRemaining()) {
                if (at >= starts[ids.size]) throw IOException("read past the end of $name")
                // The blob holding [at]: binary search over the start offsets.
                var lo = 0
                var hi = ids.size - 1
                while (lo < hi) {
                    val mid = (lo + hi + 1) ushr 1
                    if (starts[mid] <= at) lo = mid else hi = mid - 1
                }
                val data = blob(ids[lo])
                val inBlob = (at - starts[lo]).toInt()
                val n = minOf(destination.remaining(), data.size - inBlob)
                destination.put(data, inBlob, n)
                at += n
            }
        }
    }

    private abstract class ReadOnlyFile(override val parent: UsbFile?) : AbstractUsbFile() {
        override val isRoot: Boolean get() = false
        override var length: Long
            get() = 0L
            set(_) = readOnly()
        override fun createdAt(): Long = lastModified()
        override fun lastAccessed(): Long = lastModified()
        override fun list(): Array<String> = listFiles().map { it.name }.toTypedArray()
        override fun read(offset: Long, destination: ByteBuffer): Unit = throw IOException("$name is a folder")
        override fun write(offset: Long, source: ByteBuffer) = readOnly()
        override fun flush() {}
        override fun close() {}
        override fun createDirectory(name: String): UsbFile = readOnly()
        override fun createFile(name: String): UsbFile = readOnly()
        override fun moveTo(destination: UsbFile) = readOnly()
        override fun delete() = readOnly()
    }

    companion object {
        private val NAME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH.mm.ss")

        private fun readOnly(): Nothing = throw IOException("a mounted backup is read-only; restore files to change them")

        /** restic writes RFC 3339 with nanoseconds and an offset. */
        internal fun millis(time: String): Long = runCatching { OffsetDateTime.parse(time).toInstant().toEpochMilli() }.getOrDefault(0L)

        /** "2026-10-31 14.05.12 (1a2b3c4d)" in local time: sorts by date, and names the snapshot restic shows. */
        internal fun snapshotName(s: ResticRepository.Snapshot): String {
            val t = runCatching { OffsetDateTime.parse(s.time).atZoneSameInstant(ZoneId.systemDefault()).format(NAME) }.getOrDefault(s.time)
            return "$t (${s.id.take(8)})"
        }
    }
}
