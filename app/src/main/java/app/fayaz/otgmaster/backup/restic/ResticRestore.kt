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

    /** Writes [node] (a file or a whole folder) to [targetPath] in [target]. */
    fun restore(node: ResticRepository.Node, target: FileTree, targetPath: String) {
        when (node.type) {
            "dir" -> {
                target.mkdirs(targetPath)
                for (child in repo.tree(node.subtree ?: return)) {
                    Paths.requireSafeName(child.name)
                    restore(child, target, Paths.join(targetPath, child.name))
                }
            }
            "file" -> {
                target.openWrite(targetPath).use { out ->
                    for (id in node.content) {
                        val b = repo.blob(id)
                        out.write(b)
                        bytesRestored += b.size
                    }
                }
                filesRestored++
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
}
