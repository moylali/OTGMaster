package app.fayaz.otgmaster.backup

import java.io.File
import java.io.FileInputStream
import java.io.FileNotFoundException
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream

/**
 * A directory tree the engine can read from and write to.
 *
 * Every endpoint is one of these — a folder in internal storage, a folder on a
 * drive mounted by OTG Master, or a plain directory in tests — which is what
 * makes all four backup directions the same code. Paths are "/"-separated and
 * relative to the tree's root; "" is the root itself.
 */
interface FileTree {
    data class Entry(
        val name: String,
        val isDirectory: Boolean,
        val size: Long,
        val lastModified: Long,
    )

    /** The entry at [path], or null if nothing is there. */
    fun stat(path: String): Entry?

    /** Children of the directory at [path]. Throws [FileNotFoundException] if absent. */
    fun list(path: String): List<Entry>

    fun openRead(path: String): InputStream

    /** Creates or truncates the file at [path], creating parent directories. */
    fun openWrite(path: String): OutputStream

    fun mkdirs(path: String)

    /** Deletes [path] and everything under it; no-op if absent. */
    fun delete(path: String)

    /** Renames the file at [path] to [newName] in the same folder; fails if [newName] exists. */
    fun rename(path: String, newName: String)

    /** A short human-readable description of where this tree is, for logs and errors. */
    val description: String
}

object Paths {
    fun join(a: String, b: String): String = when {
        a.isEmpty() -> b
        b.isEmpty() -> a
        else -> "$a/$b"
    }

    fun parent(path: String): String = path.substringBeforeLast('/', "")

    fun name(path: String): String = path.substringAfterLast('/')

    /** Rejects names that could escape a tree when used as a path component. */
    fun requireSafeName(name: String) {
        require(name.isNotEmpty() && name != "." && name != ".." && '/' !in name && '\u0000' !in name) {
            "unsafe file name: '$name'"
        }
    }
}

/** A subdirectory of another tree, presented as a tree of its own. */
class SubTree(private val base: FileTree, private val prefix: String) : FileTree {
    private fun p(path: String) = Paths.join(prefix, path)
    override fun stat(path: String) = base.stat(p(path))
    override fun list(path: String) = base.list(p(path))
    override fun openRead(path: String) = base.openRead(p(path))
    override fun openWrite(path: String) = base.openWrite(p(path))
    override fun mkdirs(path: String) = base.mkdirs(p(path))
    override fun delete(path: String) = base.delete(p(path))
    override fun rename(path: String, newName: String) = base.rename(p(path), newName)
    override val description: String get() = "${base.description}/$prefix"
}

/** A tree on the local filesystem; used by tests and for app-private scratch space. */
class LocalFileTree(private val root: File) : FileTree {
    private fun f(path: String) = if (path.isEmpty()) root else File(root, path)

    override fun stat(path: String): FileTree.Entry? {
        val file = f(path)
        if (!file.exists()) return null
        return FileTree.Entry(file.name, file.isDirectory, if (file.isFile) file.length() else 0, file.lastModified())
    }

    override fun list(path: String): List<FileTree.Entry> {
        val dir = f(path)
        val children = dir.listFiles() ?: throw FileNotFoundException("not a directory: $dir")
        return children.map { FileTree.Entry(it.name, it.isDirectory, if (it.isFile) it.length() else 0, it.lastModified()) }
    }

    override fun openRead(path: String): InputStream = FileInputStream(f(path))

    override fun openWrite(path: String): OutputStream {
        val file = f(path)
        file.parentFile?.mkdirs()
        if (file.isDirectory) file.deleteRecursively()
        return FileOutputStream(file)
    }

    override fun mkdirs(path: String) {
        val dir = f(path)
        if (dir.isFile) dir.delete()
        dir.mkdirs()
    }

    override fun delete(path: String) {
        f(path).deleteRecursively()
    }

    override fun rename(path: String, newName: String) {
        Paths.requireSafeName(newName)
        val from = f(path)
        val to = File(from.parentFile, newName)
        if (to.exists() || !from.renameTo(to)) throw java.io.IOException("could not rename $path to $newName")
    }

    override val description: String get() = root.path
}
