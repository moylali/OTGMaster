package app.fayaz.otgmaster.backup

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import app.fayaz.otgmaster.MountedDrive
import app.fayaz.otgmaster.OtgMasterState
import app.fayaz.otgmaster.backup.restic.ResticFileSystem
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
        val repo = BackupRunner(context).repository(job)
        val drive = MountedDrive(
            id = id,
            name = "${job.name} (backup)",
            fileSystem = ResticFileSystem(repo, job.name),
            blockDevice = null,
            isPlain = false,
            partitionLabel = job.name,
            filesystemName = "Backup",
            mountedReadOnly = true,
        )
        VolumeInfo.otgDriveId(Uri.parse(job.destination.uri))?.let { on[id] = it }
        OtgMasterState.addDrive(drive)
        context.contentResolver.notifyChange(DocumentsContract.buildRootsUri(ExtensionContract.AUTHORITY), null)
        OtgMasterState.log("Mounted backup \"${job.name}\" read-only: ${repo.snapshots().size} snapshot(s)")
        return drive
    }

    /** Backups mounted from a repository on [driveId], to unmount when it goes. */
    fun dependents(driveId: String): List<MountedDrive> =
        OtgMasterState.mountedDrives.filter { on[it.id] == driveId }

    fun forget(id: String) { on.remove(id) }
}
