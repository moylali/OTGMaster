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
    fun `counts read as one file and one day`() {
        assertEquals("1 file", Format.files(1))
        assertEquals("0 files", Format.files(0))
        assertEquals("5 files", Format.files(5))
        val now = 10 * 86_400_000L
        assertEquals("1 day ago", Format.ago(now - 86_400_000L, now))
        assertEquals("1 day ago", Format.ago(now - 2 * 86_400_000L + 1, now))
        assertEquals("2 days ago", Format.ago(now - 2 * 86_400_000L, now))
    }

    @Test
    fun `a due drive is named by its device alone`() {
        fun end(partition: String, device: String = "SanDisk Ultra") = Endpoint("content://x/tree/$partition", partition, "otg", mapOf(
            VolumeInfo.KIND to VolumeInfo.KIND_OTG, VolumeInfo.OTG_DEVICE to device, VolumeInfo.OTG_PARTITION to partition,
            VolumeInfo.OTG_CONTAINER to "VERACRYPT", VolumeInfo.OTG_FILESYSTEM to "exFAT"))
        val siblings = job().copy(source = end("Partition 1"), destination = end("Partition 2"))
        assertEquals("SanDisk Ultra", BackupScheduler.driveNames(siblings))
        val two = job().copy(source = end("Partition 1"), destination = end("Whole device", "PNY FD"))
        assertEquals("SanDisk Ultra and PNY FD", BackupScheduler.driveNames(two))
    }

    @Test
    fun `a folder is named by its path inside the volume`() {
        assertEquals("/DCIM/Camera", VolumeInfo.folderInside("primary:DCIM/Camera"))
        assertEquals("/DCIM/Camera", VolumeInfo.folderInside("abc:/DCIM/Camera"))
        assertEquals("/", VolumeInfo.folderInside("primary:"))
        assertEquals("/", VolumeInfo.folderInside("abc:/"))
        assertEquals("/Camera", VolumeInfo.joinFolder("/", "Camera"))
        assertEquals("/Backups/Camera", VolumeInfo.joinFolder("/Backups", "Camera"))
        assertEquals("/Backups", VolumeInfo.joinFolder("/Backups", ""))
    }

    @Test
    fun `next run is the delay, the snooze or the interval`() {
        val h = BackupJob.HOUR
        val j = job().copy(createdAt = 0, lastSuccessAt = 10 * h)
        assertEquals(34 * h, j.nextRunAt())
        assertEquals(40 * h, j.copy(snoozedUntil = 40 * h).nextRunAt())
        assertEquals(11 * h, j.copy(delayedStartAt = 11 * h).nextRunAt())
        assertEquals(null, j.copy(schedule = Schedule(intervalHours = 0)).nextRunAt())
    }

    @Test
    fun `times read as today, tomorrow or a date`() {
        val zone = java.util.TimeZone.getTimeZone("UTC")
        val at = { s: String -> java.time.Instant.parse(s).toEpochMilli() }
        val now = at("2026-11-05T12:00:00Z") // a Thursday
        assertEquals("Today at 10:00 PM", Format.whenAt(at("2026-11-05T22:00:00Z"), now, zone, java.util.Locale.US))
        assertEquals("Tomorrow at 9:00 AM", Format.whenAt(at("2026-11-06T09:00:00Z"), now, zone, java.util.Locale.US))
        assertEquals("Sat, Nov 7 at 9:00 AM", Format.whenAt(at("2026-11-07T09:00:00Z"), now, zone, java.util.Locale.US))
    }

    @Test
    fun `an existing backup must sit directly in the target`() {
        val key = { doc: String -> BackupTargets.folderKey("auth", doc) }
        assertEquals("repo", BackupTargets.childOf(key("d1:/Backups"), key("d1:/Backups/repo")))
        assertEquals("repo", BackupTargets.childOf(key("d1:/"), key("d1:/repo")))
        assertEquals("deeper, in a folder of its own", null, BackupTargets.childOf(key("d1:/Backups"), key("d1:/Backups/a/repo")))
        assertEquals("the target itself", null, BackupTargets.childOf(key("d1:/Backups"), key("d1:/Backups")))
        assertEquals("beside the target", null, BackupTargets.childOf(key("d1:/Backups"), key("d1:/Backups2/repo")))
        assertEquals("another drive", null, BackupTargets.childOf(key("d1:/Backups"), key("d2:/Backups/repo")))
        assertEquals("another provider", null, BackupTargets.childOf(key("d1:/Backups"), BackupTargets.folderKey("other", "d1:/Backups/repo")))
    }

    @Test
    fun `the bell lists due backups waiting on the user`() {
        val now = 1_000 * BackupJob.HOUR
        val usb = job().destination
        val phone = job().source
        val due = job().copy(id = "due", pendingConfirmation = null, schedule = Schedule(intervalHours = 24, autoRun = true, remindAfterHours = 0), source = usb, destination = usb, lastSuccessAt = now - 25 * BackupJob.HOUR)
        fun listed(j: BackupJob, busy: Set<String> = emptySet()) = listOf(j).waitingOnUser(busy, now).isNotEmpty()
        assertTrue("due, on a drive", listed(due))
        assertTrue("never run", listed(due.copy(lastSuccessAt = null)))
        assertFalse("not due yet", listed(due.copy(lastSuccessAt = now - BackupJob.HOUR)))
        assertFalse("waiting on a low-space answer", listed(due.copy(pendingConfirmation = job().pendingConfirmation)))
        assertFalse("running", listed(due, busy = setOf("due")))
        assertFalse("snoozed", listed(due.copy(snoozedUntil = now + BackupJob.HOUR)))
        assertFalse("a delay chosen", listed(due.copy(delayedStartAt = now + BackupJob.HOUR)))
        assertFalse("manual", listed(due.copy(schedule = Schedule(intervalHours = 0, autoRun = false, remindAfterHours = 0))))
        val phoneOnly = due.copy(source = phone, destination = phone)
        assertFalse("starts by itself", listed(phoneOnly))
        assertTrue("could not start, and said so", listed(phoneOnly.copy(lastReminderAt = now - BackupJob.HOUR)))
    }

    @Test
    fun `a target must keep clear of other backups`() {
        val a = "auth"
        val other = listOf("Camera" to BackupTargets.folderKey(a, "d1:/Backups", "Camera"))
        val key = { doc: String, sub: String -> BackupTargets.folderKey(a, doc, sub) }
        // The same parent is fine: each backup has a folder of its own name in it.
        assertEquals(null, BackupTargets.conflict(key("d1:/Backups", ""), key("d1:/Backups", "Music"), other))
        assertEquals(BackupTargets.Conflict.SameAsJob("Camera"), BackupTargets.conflict(key("d1:/Backups", ""), key("d1:/Backups", "Camera"), other))
        assertEquals(BackupTargets.Conflict.InsideJob("Camera"), BackupTargets.conflict(key("d1:/Backups/Camera", ""), key("d1:/Backups/Camera", "New"), other))
        assertEquals(BackupTargets.Conflict.InsideJob("Camera"), BackupTargets.conflict(key("d1:/Backups/Camera/data", ""), key("d1:/Backups/Camera/data", "New"), other))
        // A name that only starts the same is another folder, and another drive is another place.
        assertEquals(null, BackupTargets.conflict(key("d1:/Backups", ""), key("d1:/Backups", "Camera2"), other))
        assertEquals(null, BackupTargets.conflict(key("d2:/Backups", ""), key("d2:/Backups", "Camera"), other))
        // The two spellings of a drive's top level are one folder.
        assertEquals(key("d1:/", "x"), key("d1:", "x"))
    }

    @Test
    fun `a sibling partition is matched by serial, never without one`() {
        val key = app.fayaz.otgmaster.extension.ExtensionContract.DeviceKey(1, 2, "S1")
        val info = mapOf("vendor_id" to "1", "product_id" to "2", "serial" to "S1")
        assertTrue(BackupScheduler.onSameDevice("p2", info, "p1", key))
        assertTrue("the same partition needs no serial", BackupScheduler.onSameDevice("p1", emptyMap(), "p1", key))
        assertFalse(BackupScheduler.onSameDevice("p2", info + ("serial" to "S2"), "p1", key))
        assertFalse(BackupScheduler.onSameDevice("p2", info - "serial", "p1", key.copy(serial = null)))
    }

    @Test
    fun `a damaged backup is told apart from a read that failed`() {
        assertTrue(BackupRunner.isDamage(app.fayaz.otgmaster.backup.restic.DamagedException("x")))
        assertTrue(BackupRunner.isDamage(RuntimeException(app.fayaz.otgmaster.backup.restic.DamagedException("x"))))
        assertFalse(BackupRunner.isDamage(java.io.IOException("USB transfer failed")))
        assertTrue(CopyEngine.isDamage("a/b.txt: ${CopyEngine.DIFFERS}"))
        assertTrue(CopyEngine.isDamage("a/b.txt: ${CopyEngine.MISSING}"))
        assertFalse(CopyEngine.isDamage("a/b.txt: I/O error"))
    }

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
        assertFalse("a chosen delay is not due before its time", j.copy(delayedStartAt = 30 * h).isDue(25 * h))
        assertFalse("a corrupted backup does not ask to run", j.copy(corruptedAt = 1).isDue(25 * h))
        assertFalse("manual jobs are never due", j.copy(schedule = Schedule(intervalHours = 0)).isDue(1000 * h))
        assertFalse("snoozed", j.copy(snoozedUntil = 30 * h).isDue(25 * h))
    }
}
