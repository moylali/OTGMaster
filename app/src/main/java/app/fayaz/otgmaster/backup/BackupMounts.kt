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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
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

    /** Mounted backup's drive id → the folder its repository is in, as [BackupTargets.folderKey]. */
    private val folders = ConcurrentHashMap<String, String>()

    private val _justMounted = MutableStateFlow<MountedDrive?>(null)
    /** A backup just mounted, for the main screen to highlight the next time it is on screen. */
    val justMounted: StateFlow<MountedDrive?> = _justMounted
    fun announced() { _justMounted.value = null }

    /** Debug builds only: holds a mount open long enough for a test to see its progress sign. */
    @Volatile var testDelayMs = 0L
    private fun testDelay() { if (app.fayaz.otgmaster.BuildConfig.DEBUG && testDelayMs > 0) Thread.sleep(testDelayMs) }

    fun driveIdFor(jobId: String): String = "bk" + jobId.filter { it.isLetterOrDigit() }.take(8)

    private fun folderOf(job: BackupJob): String? = runCatching {
        val target = Uri.parse(job.destination.uri)
        BackupTargets.folderKey(target.authority, DocumentsContract.getTreeDocumentId(target), job.destinationFolder)
    }.getOrNull()

    private fun folderOf(folder: Uri): String? = runCatching {
        BackupTargets.folderKey(folder.authority, DocumentsContract.getTreeDocumentId(folder))
    }.getOrNull()

    /**
     * The mount already showing the repository in [folder]. Rows of more than one job, or the picker,
     * can lead to the same folder, and it only needs mounting once. Announced, so the screen goes to its card.
     */
    private fun alreadyMounted(folder: String?): MountedDrive? {
        if (folder == null) return null
        val drive = OtgMasterState.mountedDrives.firstOrNull { folders[it.id] == folder } ?: return null
        OtgMasterState.log("${drive.name} is already mounted")
        _justMounted.value = drive
        return drive
    }

    /** Opens the job's repository with its saved password and mounts it, unless it is mounted already. Blocking: call off the main thread. */
    fun mount(context: Context, job: BackupJob): MountedDrive {
        require(job.type == JobType.INCREMENTAL) { "only incremental backups can be mounted" }
        val folder = folderOf(job)
        alreadyMounted(folder)?.let { return it }
        testDelay()
        return add(context, driveIdFor(job.id), job.name, BackupRunner(context).repository(job), Uri.parse(job.destination.uri), folder)
    }

    /**
     * Whether the folder the user browsed to holds a restic repository. A repository has no
     * main file: it is a folder with a `config` file and `keys`, `snapshots`, `index` and
     * `data` folders. `config` and at least one key are what opening it needs.
     */
    fun holdsBackup(context: Context, folder: Uri): Boolean = runCatching {
        testDelay()
        val backend = FileTreeBackend(SafFileTree(context.contentResolver, folder))
        backend.exists("config") && backend.list("keys").isNotEmpty()
    }.onFailure { OtgMasterState.logFailure("backup-browse", "Could not read ${VolumeInfo.displayPath(context, folder)}", it) }
        .getOrDefault(false)

    /**
     * The mount already showing [folder], whoever mounted it. Otherwise, if it is the repository of one
     * of this phone's jobs, mounts it as that job, with the saved password. Null if it is no job's, or its saved password
     * is missing or no longer opens it. A damaged repository marks the job Corrupted, raises the
     * offer to recreate it and throws. Blocking: call off the main thread.
     */
    fun mountAsJob(context: Context, folder: Uri): MountedDrive? {
        alreadyMounted(folderOf(folder))?.let { return it }
        val chosen = runCatching { DocumentsContract.getTreeDocumentId(folder) }.getOrNull() ?: return null
        val job = JobStore.get(context).jobs.value.firstOrNull { job ->
            val target = Uri.parse(job.destination.uri)
            job.type == JobType.INCREMENTAL && target.authority == folder.authority &&
                runCatching { DocumentsContract.getTreeDocumentId(target) }.getOrNull()?.let { root ->
                    root.trimEnd('/') + "/" + job.destinationFolder == chosen.trimEnd('/')
                } == true
        } ?: return null
        return runCatching { mount(context, job) }
            .onFailure {
                if (BackupRunner.isDamage(it)) {
                    BackupRunner(context).markCorrupted(job, it.message ?: "the backup is damaged")
                    JobStore.get(context).job(job.id)?.let { j -> BackupPrompts.corruptionFound(context, j) }
                    throw it
                }
                OtgMasterState.log("Backup \"${job.name}\": ${it.message}; asking for the password")
            }
            .getOrNull()
    }

    /**
     * Mounts the repository in a folder the user browsed to. Returns null if [password] does
     * not open it. Blocking: call off the main thread.
     */
    fun mountFolder(context: Context, folder: Uri, password: String): MountedDrive? {
        val key = folderOf(folder)
        alreadyMounted(key)?.let { return it }
        testDelay()
        val id = "bk" + Integer.toHexString(folder.toString().hashCode())
        val path = VolumeInfo.displayPath(context, folder)
        val repo = ResticRepository.open(FileTreeBackend(SafFileTree(context.contentResolver, folder)), password.toByteArray())
        if (repo == null) {
            OtgMasterState.log("Backup at $path: the password does not open it")
            return null
        }
        return add(context, id, path.trimEnd('/').substringAfterLast('/').ifEmpty { "Backup" }, repo, folder, key)
    }

    @Synchronized
    private fun add(context: Context, id: String, label: String, repo: ResticRepository, location: Uri, folder: String?): MountedDrive {
        // Opening a repository takes seconds, so another row may have mounted it meanwhile.
        alreadyMounted(folder)?.let { return it }
        OtgMasterState.mountedDrives.firstOrNull { it.id == id }?.let { _justMounted.value = it; return it }
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
        folder?.let { folders[id] = it }
        OtgMasterState.addDrive(drive)
        context.contentResolver.notifyChange(DocumentsContract.buildRootsUri(ExtensionContract.AUTHORITY), null)
        OtgMasterState.log("Mounted backup \"$label\" read-only: ${repo.snapshots().size} snapshot(s)")
        _justMounted.value = drive
        return drive
    }

    /** Backups mounted from a repository on [driveId], to unmount when it goes. */
    fun dependents(driveId: String): List<MountedDrive> =
        OtgMasterState.mountedDrives.filter { on[it.id] == driveId }

    fun forget(id: String) {
        on.remove(id)
        folders.remove(id)
    }
}
