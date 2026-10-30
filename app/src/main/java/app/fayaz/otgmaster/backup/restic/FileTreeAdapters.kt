package app.fayaz.otgmaster.backup.restic

import app.fayaz.otgmaster.backup.FileTree
import app.fayaz.otgmaster.backup.Paths
import java.io.IOException
import java.io.InputStream

/**
 * A restic repository in a [FileTree]: a folder on a drive OTG Master has mounted, or a
 * phone folder, through the same document provider every other app uses.
 *
 * Every file is written under a temporary name and renamed into place once complete,
 * so a pack, index or snapshot cut short by an unplug never exists under its real name
 * (restic would report it as damage). Then it is read back and its SHA-256 checked
 * against its name before anything refers to it.
 */
class FileTreeBackend(private val tree: FileTree) : ResticBackend {
    override fun list(dir: String): List<String> =
        if (tree.stat(dir) == null) emptyList() else tree.list(dir).filter { !it.isDirectory && !it.name.endsWith(TMP) }.map { it.name }

    override fun read(path: String): ByteArray = tree.openRead(path).use { it.readBytes() }

    override fun readRange(path: String, offset: Long, length: Int): ByteArray = tree.openRead(path).use { input ->
        var skip = offset
        while (skip > 0) {
            val n = input.skip(skip)
            if (n <= 0) throw IOException("$path ends before offset $offset")
            skip -= n
        }
        val out = ByteArray(length)
        var got = 0
        while (got < length) {
            val n = input.read(out, got, length - got)
            if (n < 0) throw IOException("$path ends before ${offset + length}")
            got += n
        }
        out
    }

    override fun write(path: String, data: ByteArray) {
        val tmp = path + TMP
        tree.openWrite(tmp).use { it.write(data) }
        if (tree.stat(path) != null) tree.delete(path) // a retried write of the same content-addressed file
        tree.rename(tmp, Paths.name(path))
        // Content-addressed files must read back as what was meant to be written.
        val name = Paths.name(path)
        if (name.length == 64 && name.all { it in '0'..'9' || it in 'a'..'f' }) {
            val back = read(path)
            if (ResticRepository.sha256Hex(back) != name) throw IOException("$path does not read back as written")
        }
    }

    override fun exists(path: String) = tree.stat(path) != null
    override fun delete(path: String) = tree.delete(path)
    override fun mkdirs(dir: String) = tree.mkdirs(dir)

    companion object { const val TMP = ".otgtmp" }
}

/** A folder in a [FileTree] as something to back up. */
class FileTreeSource(private val tree: FileTree, private val path: String, private val entry: FileTree.Entry) : BackupSource {
    override val name: String get() = entry.name
    override val isDirectory: Boolean get() = entry.isDirectory
    override val size: Long get() = entry.size
    override val modifiedMillis: Long get() = entry.lastModified
    override fun children(): List<BackupSource> =
        tree.list(path).map { FileTreeSource(tree, Paths.join(path, it.name), it) }
    override fun open(): InputStream = tree.openRead(path)

    companion object {
        fun root(tree: FileTree): FileTreeSource =
            FileTreeSource(tree, "", tree.stat("") ?: FileTree.Entry("", true, 0, 0))
    }
}
