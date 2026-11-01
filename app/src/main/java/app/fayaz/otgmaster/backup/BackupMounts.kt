package app.fayaz.otgmaster.backup

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import app.fayaz.otgmaster.MountedDrive
import app.fayaz.otgmaster.OtgMasterState
import app.fayaz.otgmaster.backup.restic.FileTreeBackend
import app.fayaz.otgmaster.backup.restic.ResticFileSystem
import app.fayaz.otgmaster.backup.restic.ResticRepository
import app.fayaz.otgmaster.extension.ExtensionContract
import java.util.concurrent.ConcurrentHashMap

/**
 * "Mount" on an incremental backup: its restic repository shown as a read-only drive, one
 * folder per snapshot, so the Files app and other apps open backed-up files without a
 * restore. It appears in the list of mounted drives like a partition, with Unmount.
 *
 * The repository lives on another drive (or the phone), read through that drive's
 * document tree. When that drive goes, the backup mount goes with it ([dependents]).
 */
object BackupMounts {
    /** Mounted backup's drive id → the drive id its repository lives on (null: the phone). */
    private val on = ConcurrentHashMap<String, String>()

    fun isMounted(jobId: String): Boolean = OtgMasterState.mountedDrives.any { it.id == driveIdFor(jobId) }

    fun driveIdFor(jobId: String): String = "bk" + jobId.filter { it.isLetterOrDigit() }.take(8)

    /** Opens the job's repository with its saved password and mounts it. Blocking: call off the main thread. */
    fun mount(context: Context, job: BackupJob): MountedDrive {
        require(job.type == JobType.INCREMENTAL) { "only incremental backups can be mounted" }
        val id = driveIdFor(job.id)
        OtgMasterState.mountedDrives.firstOrNull { it.id == id }?.let { return it }
        return add(context, id, job.name, BackupRunner(context).repository(job), Uri.parse(job.destination.uri))
    }

    /**
     * Whether the folder the user browsed to holds a restic repository. A repository has no
     * main file: it is a folder with a `config` file and `keys`, `snapshots`, `index` and
     * `data` folders. `config` and at least one key are what opening it needs.
     */
    fun holdsBackup(context: Context, folder: Uri): Boolean = runCatching {
        val backend = FileTreeBackend(SafFileTree(context.contentResolver, folder))
        backend.exists("config") && backend.list("keys").isNotEmpty()
    }.onFailure { OtgMasterState.logFailure("backup-browse", "Could not read ${VolumeInfo.displayPath(context, folder)}", it) }
        .getOrDefault(false)

    /**
     * If [folder] is the repository of one of this phone's jobs, mounts it as that job, so the
     * job's row shows it mounted and it cannot be mounted twice. Null if it is no job's, or its
     * saved password is missing or no longer opens it. Blocking: call off the main thread.
     */
    fun mountAsJob(context: Context, folder: Uri): MountedDrive? {
        val chosen = runCatching { DocumentsContract.getTreeDocumentId(folder) }.getOrNull() ?: return null
        val job = JobStore.get(context).jobs.value.firstOrNull { job ->
            val target = Uri.parse(job.destination.uri)
            job.type == JobType.INCREMENTAL && target.authority == folder.authority &&
                runCatching { DocumentsContract.getTreeDocumentId(target) }.getOrNull()?.let { root ->
                    root.trimEnd('/') + "/" + job.destinationFolder == chosen.trimEnd('/')
                } == true
        } ?: return null
        return runCatching { mount(context, job) }
            .onFailure { OtgMasterState.log("Backup \"${job.name}\": ${it.message}; asking for the password") }
            .getOrNull()
    }

    /**
     * Mounts the repository in a folder the user browsed to. Returns null if [password] does
     * not open it. Blocking: call off the main thread.
     */
    fun mountFolder(context: Context, folder: Uri, password: String): MountedDrive? {
        val id = "bk" + Integer.toHexString(folder.toString().hashCode())
        OtgMasterState.mountedDrives.firstOrNull { it.id == id }?.let { return it }
        val path = VolumeInfo.displayPath(context, folder)
        val repo = ResticRepository.open(FileTreeBackend(SafFileTree(context.contentResolver, folder)), password.toByteArray())
        if (repo == null) {
            OtgMasterState.log("Backup at $path: the password does not open it")
            return null
        }
        return add(context, id, path.trimEnd('/').substringAfterLast('/').ifEmpty { "Backup" }, repo, folder)
    }

    private fun add(context: Context, id: String, label: String, repo: ResticRepository, location: Uri): MountedDrive {
        val drive = MountedDrive(
            id = id,
            name = "$label (backup)",
            fileSystem = ResticFileSystem(repo, label),
            blockDevice = null,
            isPlain = false,
            partitionLabel = "",
            filesystemName = "Backup",
            mountedReadOnly = true,
        )
        VolumeInfo.otgDriveId(location)?.let { on[id] = it }
        OtgMasterState.addDrive(drive)
        context.contentResolver.notifyChange(DocumentsContract.buildRootsUri(ExtensionContract.AUTHORITY), null)
        OtgMasterState.log("Mounted backup \"$label\" read-only: ${repo.snapshots().size} snapshot(s)")
        return drive
    }

    /** Backups mounted from a repository on [driveId], to unmount when it goes. */
    fun dependents(driveId: String): List<MountedDrive> =
        OtgMasterState.mountedDrives.filter { on[it.id] == driveId }

    fun forget(id: String) { on.remove(id) }
}
