package app.fayaz.otgmaster.backup

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import app.fayaz.otgmaster.R
import app.fayaz.otgmaster.backup.restic.FileTreeBackend
import app.fayaz.otgmaster.backup.restic.ResticRepository

/**
 * What a new backup may use as its target. Nothing here protects against overwriting, since
 * no backup overwrites another: a backup goes in a folder of its own name, Plain copies are
 * dated, and restic only ever adds. It keeps each backup in a folder of its own, because Verify,
 * Recreate and Remove all act on a folder and would otherwise reach another backup's data.
 *
 * Android grants only go downward, so a folder inside someone else's repository cannot be
 * recognised from here unless a job on this phone names it.
 */
object BackupTargets {
    /** A folder, comparable across jobs: provider, then volume and path with no stray slashes. */
    fun folderKey(authority: String?, treeDocumentId: String, subfolder: String = ""): String {
        val volume = treeDocumentId.substringBefore(':')
        val path = listOf(treeDocumentId.substringAfter(':', ""), subfolder)
            .flatMap { it.split('/') }.filter { it.isNotEmpty() }.joinToString("/")
        return "$authority|$volume:$path"
    }

    /** [a] is [b], or a folder somewhere inside it. */
    fun sameOrInside(a: String, b: String): Boolean =
        a == b || a.startsWith(if (b.endsWith(":")) b else "$b/")

    sealed class Conflict {
        data class InsideJob(val name: String) : Conflict()
        data class SameAsJob(val name: String) : Conflict()
    }

    /** [chosen] is the folder picked, [final] the one the backup would use inside it. Others are (name, final folder). */
    fun conflict(chosen: String, final: String, others: List<Pair<String, String>>): Conflict? {
        others.firstOrNull { (_, other) -> sameOrInside(chosen, other) }?.let { return Conflict.InsideJob(it.first) }
        others.firstOrNull { (_, other) -> sameOrInside(final, other) || sameOrInside(other, final) }?.let { return Conflict.SameAsJob(it.first) }
        return null
    }

    /** The name of [chosen] if it sits directly in [target], both from [folderKey]; otherwise null. */
    fun childOf(target: String, chosen: String): String? {
        val prefix = if (target.endsWith(":")) target else "$target/"
        if (!chosen.startsWith(prefix)) return null
        return chosen.removePrefix(prefix).takeIf { it.isNotEmpty() && '/' !in it }
    }

    /** What Select existing found: the backup's name, or why the chosen folder will not do. */
    sealed class Existing {
        data class Found(val name: String) : Existing()
        object NotInTarget : Existing()
        object NoBackup : Existing()
    }

    /** Reads the drive: call off the main thread. [chosen] came from the picker, which cannot be held to [target]. */
    fun findExisting(context: Context, target: Uri, chosen: Uri): Existing {
        val name = childOf(
            folderKey(target.authority, DocumentsContract.getTreeDocumentId(target)),
            folderKey(chosen.authority, DocumentsContract.getTreeDocumentId(chosen)),
        ) ?: return Existing.NotInTarget
        return if (holdsRepository(SafFileTree(context.contentResolver, target), name)) Existing.Found(name) else Existing.NoBackup
    }

    /** Whether [password] opens the backup in [target]/[name]. Reads the drive: call off the main thread. */
    fun opensExisting(context: Context, target: Uri, name: String, password: String): Boolean =
        ResticRepository.open(FileTreeBackend(SubTree(SafFileTree(context.contentResolver, target), name)), password.toByteArray()) != null

    private fun holdsRepository(tree: FileTree, folder: String): Boolean =
        tree.stat(Paths.join(folder, "config")) != null && tree.stat(Paths.join(folder, "keys")) != null

    private fun finalOf(job: BackupJob): String? = runCatching {
        val uri = Uri.parse(job.destination.uri)
        folderKey(uri.authority, DocumentsContract.getTreeDocumentId(uri), job.destinationFolder)
    }.getOrNull()

    /**
     * Why [target]/[name] cannot be this backup's folder, or null if it can. Reads the drive,
     * and opens an existing repository with [password]: call off the main thread.
     */
    fun refusal(context: Context, jobs: List<BackupJob>, target: Uri, name: String, incremental: Boolean, password: String): String? {
        val id = DocumentsContract.getTreeDocumentId(target)
        val chosen = folderKey(target.authority, id)
        val final = folderKey(target.authority, id, name)
        when (val c = conflict(chosen, final, jobs.mapNotNull { j -> finalOf(j)?.let { j.name to it } })) {
            is Conflict.InsideJob -> return context.getString(R.string.backup_target_inside_job, c.name)
            is Conflict.SameAsJob -> return context.getString(R.string.backup_target_same_job, c.name)
            null -> Unit
        }
        val tree = SafFileTree(context.contentResolver, target)
        if (holdsRepository(tree, "")) return context.getString(R.string.backup_target_is_repository)
        if (holdsRepository(tree, name)) {
            if (!incremental) return context.getString(R.string.backup_target_plain_on_repository, name)
            val opened = ResticRepository.open(FileTreeBackend(SubTree(tree, name)), password.toByteArray())
            if (opened == null) return context.getString(R.string.backup_target_wrong_password, name)
        }
        return null
    }
}
