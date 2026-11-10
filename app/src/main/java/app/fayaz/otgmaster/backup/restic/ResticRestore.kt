package app.fayaz.otgmaster.backup.restic

import app.fayaz.otgmaster.backup.FileTree
import app.fayaz.otgmaster.backup.Paths
import java.io.IOException

/**
 * Restores from a restic snapshot into a [FileTree], as `restic restore` does: a whole
 * snapshot, or one folder or file of it. Every blob is checked against its id as it is
 * read (ResticRepository.blob), so damaged data stops the restore with an error naming
 * the file instead of being written out as if it were the file.
 */
class ResticRestore(private val repo: ResticRepository) {

    var filesRestored = 0L; private set
    var bytesRestored = 0L; private set

    /** Polled before each file and each blob; a restore asked to stop throws [CancelledException]. */
    var isCancelled: () -> Boolean = { false }

    /** Told after each file is in place. */
    var onFileDone: () -> Unit = {}

    /** The node at [path] inside the snapshot ("" is its top), or null. */
    fun find(snapshot: ResticRepository.Snapshot, path: String): ResticRepository.Node? {
        var tree = snapshot.tree
        var node: ResticRepository.Node? = null
        for (part in path.trim('/').split('/').filter { it.isNotEmpty() }) {
            node = repo.tree(tree).firstOrNull { it.name == part } ?: return null
            tree = node.subtree ?: if (node.type == "dir") return null else ""
        }
        return node
    }

    /** The children of the folder at [path] in [snapshot], for browsing the timeline. */
    fun list(snapshot: ResticRepository.Snapshot, path: String): List<ResticRepository.Node> {
        if (path.trim('/').isEmpty()) return repo.tree(snapshot.tree)
        val n = find(snapshot, path) ?: throw IOException("$path is not in snapshot ${snapshot.id.take(8)}")
        return repo.tree(n.subtree ?: throw IOException("$path is a file, not a folder"))
    }

    /**
     * Writes [node] (a file or a whole folder) to [targetPath] in [target].
     *
     * A file is written to a hidden sibling first and moved over the destination only
     * once every blob has been read and checked, so a damaged or missing blob, a backup
     * drive that goes away, or Cancel leaves what was there. It used to truncate the
     * destination before reading the first blob. Something of the other kind in the way
     * (a folder where a file goes, or a file where a folder goes) is an error, not a
     * deletion. Review finding 10, 2026-11-09.
     */
    fun restore(node: ResticRepository.Node, target: FileTree, targetPath: String) {
        if (isCancelled()) throw CancelledException()
        val existing = target.stat(targetPath)
        when (node.type) {
            "dir" -> {
                if (existing != null && !existing.isDirectory)
                    throw IOException("$targetPath: a file is in the way of the folder being restored, so it was left as it is")
                target.mkdirs(targetPath)
                for (child in repo.tree(node.subtree ?: return)) {
                    Paths.requireSafeName(child.name)
                    restore(child, target, Paths.join(targetPath, child.name))
                }
            }
            "file" -> {
                if (existing?.isDirectory == true)
                    throw IOException("$targetPath: a folder is in the way of the file being restored, so it was left as it is")
                val name = Paths.name(targetPath)
                val partial = Paths.join(Paths.parent(targetPath), ".$name$PARTIAL_SUFFIX")
                target.delete(partial)   // from an earlier run that did not finish
                var written = 0L
                try {
                    target.openWrite(partial).use { out ->
                        for (id in node.content) {
                            if (isCancelled()) throw CancelledException()
                            val b = repo.blob(id)
                            out.write(b)
                            written += b.size
                        }
                    }
                    if (written != node.size) throw DamagedException("$targetPath: restored $written bytes, the snapshot says ${node.size}")
                } catch (t: Throwable) {
                    runCatching { target.delete(partial) }
                    throw t
                }
                // Only now is the old file let go. Should the rename fail, the restored copy
                // is still there under its temporary name.
                if (existing != null) target.delete(targetPath)
                target.rename(partial, name)
                bytesRestored += written
                filesRestored++
                onFileDone()
            }
            // Symlinks, devices and the like have no meaning on a phone folder or a USB drive.
            else -> Unit
        }
    }

    /** A whole snapshot's top folder into [targetPath]. */
    fun restoreAll(snapshot: ResticRepository.Snapshot, target: FileTree, targetPath: String) {
        target.mkdirs(targetPath)
        for (child in repo.tree(snapshot.tree)) {
            Paths.requireSafeName(child.name)
            restore(child, target, Paths.join(targetPath, child.name))
        }
    }

    companion object {
        /** Suffix of the hidden file a restore writes before moving it into place. */
        const val PARTIAL_SUFFIX = ".otgrestore"
    }
}
