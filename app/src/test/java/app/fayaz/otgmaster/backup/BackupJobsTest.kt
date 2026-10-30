package app.fayaz.otgmaster.backup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

class BackupJobsTest {
    private fun job() = BackupJob(
        name = "Camera",
        source = Endpoint("content://com.android.externalstorage.documents/tree/primary%3ADCIM", "DCIM", "internal",
            mapOf("kind" to "internal", "path" to "Internal storage/DCIM")),
        destination = Endpoint("content://app.fayaz.otgmaster.documents/tree/abc%3A", "PNY · Partition 2", "otg",
            mapOf("kind" to "otg", "device_name" to "PNY USB 3.2.1 FD", "serial" to "071C")),
        destinationFolder = "Backups/Camera",
        schedule = Schedule(intervalHours = 24, autoRun = true, remindAfterHours = 72),
        retention = Retention(keepLast = 3, keepDaily = 7),
        pendingConfirmation = PendingConfirmation(10, 20, null, 30, "scheduled", 40),
    )

    @Test
    fun `jobs, history and snapshots survive a reload`() {
        val dir = Files.createTempDirectory("jobs").toFile()
        val a = JobStore(dir)
        val j = job()
        a.upsert(j)
        a.addRun(RunRecord(jobId = j.id, kind = "backup", reason = "manual", startedAt = 1, finishedAt = 2,
            status = RunStatus.WARNING, message = "m", snapshotId = "s1", stats = RunStats(files = 5, bytesAdded = 9),
            errors = listOf("/a: denied")))
        a.setSnapshots(j.id, listOf(SnapshotSummary("s1", 100, null, "phone", listOf("otg-job:${j.id}"), RunStats(files = 5))))
        val b = JobStore(dir)
        assertEquals(j, b.job(j.id))
        assertEquals(RunStatus.WARNING, b.history(j.id).single().status)
        assertEquals(listOf("/a: denied"), b.history(j.id).single().errors)
        assertEquals(5L, b.snapshots(j.id).single().stats!!.files)
        b.delete(j.id)
        assertTrue(JobStore(dir).jobs.value.isEmpty())
        dir.deleteRecursively()
    }

    @Test
    fun `due and overdue follow the schedule`() {
        val h = BackupJob.HOUR
        val j = job().copy(createdAt = 0, lastSuccessAt = 0)
        assertFalse(j.isDue(23 * h)); assertTrue(j.isDue(24 * h))
        assertFalse(j.isOverdue(71 * h)); assertTrue(j.isOverdue(72 * h))
        assertFalse("manual jobs are never due", j.copy(schedule = Schedule(intervalHours = 0)).isDue(1000 * h))
        assertFalse("snoozed", j.copy(snoozedUntil = 30 * h).isDue(25 * h))
    }
}
