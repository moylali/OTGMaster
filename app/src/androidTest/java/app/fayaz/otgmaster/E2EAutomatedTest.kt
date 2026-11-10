package app.fayaz.otgmaster

import android.content.Context
import android.content.Intent
import android.provider.DocumentsContract
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import android.util.Log
import app.fayaz.otgmaster.backup.BackupExecutor
import app.fayaz.otgmaster.backup.BackupDelays
import app.fayaz.otgmaster.backup.BackupNotifications
import app.fayaz.otgmaster.backup.BackupPrompts
import app.fayaz.otgmaster.backup.BackupRunner
import app.fayaz.otgmaster.backup.BackupScheduler
import app.fayaz.otgmaster.backup.BackupJob
import app.fayaz.otgmaster.backup.BackupMounts
import app.fayaz.otgmaster.backup.BackupSecrets
import app.fayaz.otgmaster.backup.Endpoint
import app.fayaz.otgmaster.backup.JobStore
import app.fayaz.otgmaster.backup.JobType
import app.fayaz.otgmaster.backup.Retention
import app.fayaz.otgmaster.backup.RunRecord
import app.fayaz.otgmaster.backup.RunStatus
import app.fayaz.otgmaster.backup.Schedule
import app.fayaz.otgmaster.backup.VolumeInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.hamcrest.CoreMatchers.notNullValue
import org.hamcrest.MatcherAssert.assertThat

@RunWith(AndroidJUnit4::class)
class E2EAutomatedTest {

    companion object {
        private const val AUTHORITY = "app.fayaz.otgmaster.documents"
        private const val UNMOUNT_WAIT_MS = 15_000L
        private const val UNMOUNT_SETTLE_MS = 1_000L
        private const val EMULATOR_LAUNCHER = "com.google.android.apps.nexuslauncher"
        private const val BACKUP_REPO = "otg_backup_repo"
        /** As a case's password: it has keyfiles and no password. */
        private const val NO_PASSWORD = "none"
        private const val BK_PASSWORD = "otg-backup-run-2026"
        /** A backup's actions that live in its card's More actions, not on the card. */
        private val BK_IN_MORE = listOf("verify", "restore", "show_password", "remove")
        private const val BK_DESKTOP_PASSWORD = "otg-backup-e2e-2026"
        private const val PHONE = "/sdcard/Download/otg_e2e"
        private const val BACKUP_DIALOG_FIELD = "backup_name"

        /**
         * The large write-case file: block i is SHA-256("otg-e2e-big-<i>"), 32 bytes,
         * for 3 MiB. scripts/verify_e2e_volume.py generates the same bytes.
         */
        const val BIG_FILE_NAME = "big_write.bin"
        const val BIG_FILE_BLOCKS = 3 * 1024 * 1024 / 32
        const val LEAK_FILE_NAME = "delete_while_open.bin"
        const val LEAK_FILE_BYTES = 2 * 1024 * 1024

        fun bigFileBlock(i: Int): ByteArray =
            java.security.MessageDigest.getInstance("SHA-256").digest("otg-e2e-big-$i".toByteArray())

        fun expectedBigFileSha(): String {
            val md = java.security.MessageDigest.getInstance("SHA-256")
            for (i in 0 until BIG_FILE_BLOCKS) md.update(bigFileBlock(i))
            return md.digest().joinToString("") { "%02x".format(it) }
        }
    }

    private lateinit var device: UiDevice
    private val timeout = 5000L

    @Before
    fun startMainActivityFromHomeScreen() {
        device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())        
        // Start from the home screen
        device.pressHome()

        // Wait for launcher
        val launcherPackage = device.launcherPackageName
        assertThat(launcherPackage, notNullValue())
        device.wait(Until.hasObject(By.pkg(launcherPackage).depth(0)), timeout)

        // Launch the app
        val context = ApplicationProvider.getApplicationContext<Context>()
        // Cases share an emulator and every disk there is the same device, so a read-only
        // choice left by one case made the next case's writes fail, and backups left hidden
        // would take the next case's Manage backups away. commit(): the activity reads these
        // in onCreate.
        val prefs = context.getSharedPreferences("otgmaster_prefs", Context.MODE_PRIVATE)
        prefs.edit().remove("read_only_partitions").remove("backups_hidden").remove("auto_mount")
            .remove("usb_access_mode").remove("test_android_holds_usb").commit()
        // The one-time hint at the Drives icon sits over the top of the screen, where Scan is.
        // Every case but the one that tests it starts with it already seen.
        prefs.edit().remove(MainActivity.PREF_SLIDER_HINT_OFF).putBoolean(MainActivity.PREF_SLIDER_HINT_SEEN,
            InstrumentationRegistry.getArguments().getString("eject_flow", "") != "slider_hint").commit()
        // Before the launch: under Always the launch itself would open the drive.
        when (InstrumentationRegistry.getArguments().getString("eject_flow", "")) {
            "usb_mode_manual", "usb_mode_manual_auto_mount" -> prefs.edit().putString("usb_access_mode", "manual").commit()
            "usb_mode_wait_free" -> prefs.edit().putString("usb_access_mode", "wait").putBoolean("test_android_holds_usb", false).commit()
            "usb_mode_wait_held", "usb_mode_scan_warning" ->
                prefs.edit().putString("usb_access_mode", "wait").putBoolean("test_android_holds_usb", true).commit()
        }
        // Named explicitly: the debug build has a second launcher entry (OTG Bench),
        // and getLaunchIntentForPackage returned that one, so every case ran against
        // the bench screen and failed with "password input not shown".
        val intent = mainActivityIntent().apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        }
        context.startActivity(intent)

        // Wait for the app to appear
        device.wait(Until.hasObject(By.pkg("app.fayaz.otgmaster").depth(0)), timeout)
        
        // Wait for USB Permission dialog and click Allow if it appears
        val allowButton = device.wait(Until.findObject(By.text(java.util.regex.Pattern.compile("(?i)Allow|OK"))), 5000L)
        if (allowButton != null) {
            Log.d("E2E", "Found USB Permission Dialog. Clicking Allow...")
            allowButton.click()
        }
    }

    @Test
    fun testMountUsbDrive() {
        val arguments = InstrumentationRegistry.getArguments()
        if (arguments.getString("write_test", "false") == "true") return
        if (arguments.getString("erase_action", "").isNotEmpty()) return
        // Run from here rather than as its own @Test: every @Test relaunches the app in
        // @Before, and a fourth method cost every case about 13 s doing nothing.
        if (arguments.getString("disconnect_test", "false") == "true") return runDisconnectCase()
        if (arguments.getString("journal_repair", "false") == "true") return runJournalRepairCase()
        arguments.getString("hidden_flow")?.let { return runHiddenFlow(it) }
        arguments.getString("eject_flow")?.let { return runEjectFlow(it) }
        if (arguments.getString("backup_flow") == "device") return bkFlowDevice()
        if (arguments.getString("backup_flow") == "delete_tree") return deviceDeleteTree()
        if (arguments.getString("backup_flow") == "main_screen") return runMainScreenCase()
        arguments.getString("backup_flow")?.let { return runBackupFlow(it) }
        if (arguments.getString("backup_test", "false") == "true") return runBackupCase()
        val password = arguments.getString("password", "password123")
        val testCase = arguments.getString("testCase", "exfat")

        // The slot device (/dev/block/sda) is already overwritten by the bash script before this test runs.
        android.os.SystemClock.sleep(1000)

        val keyfile = arguments.getString("keyfile", "")
        val pim = arguments.getString("pim", "")
        val expectMount = arguments.getString("expect_mount", "true") == "true"
        val expectedFs = arguments.getString("expected_fs", "")
        val cipher = arguments.getString("cipher", "")
        val hash = arguments.getString("hash", "")
        // A plain volume (no container) is auto-mounted on scan: there is no form.
        val plainMount = arguments.getString("plain_mount", "false") == "true"

        // Click "Scan USB Devices"
        val scanButton = device.wait(Until.findObject(By.desc("scan_button").clickable(true)), timeout)
            ?: findScanButton()
        scanButton?.click()

        // Wait for USB permission dialog (optional – may not appear if no device)
        val allowButton = device.wait(Until.findObject(By.text(java.util.regex.Pattern.compile("(?i)OK|ALLOW"))), timeout)
        if (allowButton != null) {
            Log.d("E2E", "USB permission dialog found, clicking Allow")
            allowButton.click()
        } else {
            Log.d("E2E", "USB permission dialog not present, proceeding")
        }

        val isRemountTest = arguments.getString("remount_test", "false") == "true"
        
        // Loop at least once, or twice if remount test
        val iterations = if (isRemountTest) 2 else 1
        
        for (i in 1..iterations) {
            if (i > 1) {
                // Click Scan again for remount
                val scanButton = findScanButton()
                    ?: device.wait(Until.findObject(By.descContains("Scan")), timeout)
                assertNotNull("Scan button not found for the remount", scanButton)
                scanButton!!.click()
                android.os.SystemClock.sleep(2000)
            }

            if (!plainMount) {
            closeSlider()
            // The app might default to the dummy drive which has no candidates.
            // Wait up to 15s to see if password input appears. If not, and there's a device picker, pick the other one.
            var passwordField = device.wait(Until.findObject(By.descContains("password_input")), 15000L)
            if (passwordField == null) {
                val devicePicker = device.findObject(By.descContains("device_picker"))
                if (devicePicker != null) {
                    devicePicker.click()
                    android.os.SystemClock.sleep(500)
                    val options = device.findObjects(By.textContains("QEMU"))
                    if (options.size > 1) {
                        options.last().click()
                        android.os.SystemClock.sleep(1000)
                    }
                }
                passwordField = device.wait(Until.findObject(By.descContains("password_input")), 15000L)
            }

            if (passwordField == null) captureScreen("no_password_field")
            assertTrue("USB device not detected — password input not shown within 30s", passwordField != null)
            assertContainerTag(arguments.getString("expected_container", ""))

            // For partitioned disks, the VeraCrypt volume is on a specific partition.
            // Select it from the candidate picker before unlocking.
            if (testCase == "partitioned_mbr") {
                val candidatePicker = device.findObject(By.descContains("candidate_picker"))
                if (candidatePicker != null) {
                    candidatePicker.click()
                    android.os.SystemClock.sleep(500)
                    val partitionOptions = device.findObjects(By.textContains("MBR partition"))
                    partitionOptions.lastOrNull()?.click()
                    android.os.SystemClock.sleep(500)
                }
            }

            passwordField!!.click()
            android.os.SystemClock.sleep(500)
            if (i > 1) {
                // Ctrl-A doesn't reliably select-all on Compose TextFields; send enough
                // backspaces to wipe whatever was left from the previous iteration instead.
                repeat(50) { device.executeShellCommand("input keyevent KEYCODE_DEL") }
                android.os.SystemClock.sleep(200)
            }
            if (password != NO_PASSWORD) device.executeShellCommand("input text $password")
            android.os.SystemClock.sleep(500)

            if (pim.isNotEmpty()) {
                val pimField = findScrolling(By.descContains("pim_input"), desc = "pim_input")
                if (pimField == null) captureScreen("no_pim_field")
                assertNotNull("PIM field not found", pimField)
                pimField?.click()
                android.os.SystemClock.sleep(500)
                if (i > 1) {
                    repeat(20) { device.executeShellCommand("input keyevent KEYCODE_DEL") }
                    android.os.SystemClock.sleep(200)
                }
                device.executeShellCommand("input text $pim")
                android.os.SystemClock.sleep(500)
            }
            
            // Press enter after all text fields are filled to clear focus if needed
            device.pressEnter()
            android.os.SystemClock.sleep(500)

            keyfile.split(',').filter { it.isNotEmpty() }.forEach { name ->
                val selectKeyfileBtn = device.wait(Until.findObject(By.textContains("Select keyfile")), timeout)
                selectKeyfileBtn?.click()
                pickKeyfileInFiles(name)
            }
            if (arguments.getString("read_only_switch", "false") == "true") clickScrolling("read_only_switch", "no read-only switch on the form")

            if (cipher.isNotEmpty() && cipher != "AES") pickOption("cipher_picker", cipher)
            if (hash.isNotEmpty() && hash != "SHA-512") pickOption("hash_picker", hash)

            // Click Mount
            val clicked = clickMountButton()
            if (clicked != MountClick.CLICKED) captureScreen("mount_button_$clicked")
            assertTrue("Mount button not found", clicked != MountClick.NOT_FOUND)
            assertTrue("Mount button is not enabled", clicked != MountClick.DISABLED)

            if (!expectMount) {
                assertCannotMountError(expectedFs)
                return
            }
            }

            // Wait for "Mounted" state. Increase timeout to 300s due to slow emulator crypto performance.
            val mountedText = waitForMounted(300000L)
            assertTrue("Drive was not successfully mounted!", mountedText != null)

            // Verify capacity is populated
            val usedText = mountedText?.text ?: ""
            assertTrue("Used capacity should not be empty", usedText.contains("Used:"))
            InstrumentationRegistry.getInstrumentation().uiAutomation.clearCache()
            val freeTextObj = device.wait(Until.findObject(By.textContains("Free:")), timeout)
            if (freeTextObj == null) captureScreen("no_free")
            assertTrue("Free capacity should be visible", freeTextObj != null)

            val context = ApplicationProvider.getApplicationContext<Context>()
            
            // Every mounted drive is a root, and with a plain partition auto-mounted
            // beside the encrypted one (partitioned_mbr) the first root is the plain
            // one. Look for the fixture files in each root, not just the first.
            val rootIds = allRootDocIds(context)
            assertTrue("No roots found from DocumentsProvider", rootIds.isNotEmpty())
            var flowerDocId: String? = null
            var spaceFileDocId: String? = null
            for (rootDocId in rootIds) {
                context.contentResolver.query(
                    android.provider.DocumentsContract.buildChildDocumentsUri(AUTHORITY, rootDocId), null, null, null, null
                )?.use { cursor ->
                    while (cursor.moveToNext()) {
                        val docId = cursor.getString(cursor.getColumnIndex(
                            android.provider.DocumentsContract.Document.COLUMN_DOCUMENT_ID)) ?: continue
                        if (docId.endsWith("flower.jpg")) flowerDocId = docId
                        if (docId.endsWith("file with spaces.txt")) spaceFileDocId = docId
                    }
                }
                if (flowerDocId != null) break
            }
            assertTrue("flower.jpg not found in the root directory", flowerDocId != null)
            assertTrue("file with spaces.txt not found in the root directory", spaceFileDocId != null)

        val providerUri = android.provider.DocumentsContract.buildDocumentUri("app.fayaz.otgmaster.documents", flowerDocId)
        val outFile = java.io.File("/sdcard/Download/flower.jpg")
        try {
            val inputStream = context.contentResolver.openInputStream(providerUri)
            assertNotNull("flower.jpg not accessible via DocumentsProvider — drive may not be mounted or file missing from volume", inputStream)
            inputStream!!.use { input ->
                outFile.outputStream().use { output -> input.copyTo(output) }
            }
        } catch (e: Exception) {
            fail("Failed to copy flower.jpg via SAF: ${e.message}")
        }
        assertTrue("Copied flower image should exist at /sdcard/Download/flower.jpg", outFile.exists())
        // Byte for byte, where the case asks (support-matrix and APFS cases).
        val flowerSha = arguments.getString("flower_sha256", "")
        if (flowerSha.isNotEmpty()) {
            val got = java.security.MessageDigest.getInstance("SHA-256").digest(outFile.readBytes())
                .joinToString("") { "%02x".format(it) }
            assertEquals("flower.jpg read through the provider differs from testdata/flower.jpg", flowerSha, got)
        }
        // case.txt and CASE.txt were both written on the Mac: two files on a
        // case-sensitive volume, one on an insensitive one.
        arguments.getString("expect_case_sensitive", "").takeIf { it.isNotEmpty() }?.let { cs ->
            val names = mutableSetOf<String>()
            for (rootDocId in allRootDocIds(context)) {
                context.contentResolver.query(
                    DocumentsContract.buildChildDocumentsUri(AUTHORITY, rootDocId), null, null, null, null
                )?.use { cur ->
                    while (cur.moveToNext()) names += cur.getString(cur.getColumnIndex(
                        DocumentsContract.Document.COLUMN_DISPLAY_NAME))
                }
            }
            assertTrue("case.txt missing from the root: ${names.sorted()}", "case.txt" in names)
            assertEquals("case sensitivity of the volume (names: ${names.sorted()})",
                cs == "true", "CASE.txt" in names)
        }

        // VeraCrypt's volume type on the card, and a file only this volume has.
        arguments.getString("expected_volume_tag", "").takeIf { it.isNotEmpty() }?.let { tag ->
            if (tag == "NONE") {
                openSlider()
                assertNull("HIDDEN on a normal unlock", device.findObject(By.text("HIDDEN")))
                assertNull("OUTER on a normal unlock", device.findObject(By.text("OUTER")))
            } else {
                assertNotNull("$tag not on the drive card", findInSlider(By.text(tag)))
            }
        }
        arguments.getString("expect_file", "").takeIf { it.isNotEmpty() }?.let { readRootFile(context, it) }

        // Volumes the app must not write (ext2/ext3: no extents) are mounted
        // read-only: the card says so, and a create through the provider fails.
        if (arguments.getString("expect_read_only", "false") == "true") {
            assertNotNull("READ-ONLY tag not shown on the drive card",
                device.wait(Until.findObject(By.text("READ-ONLY")), timeout))
            val created = runCatching {
                DocumentsContract.createDocument(context.contentResolver,
                    DocumentsContract.buildDocumentUri(AUTHORITY, allRootDocIds(context).first()),
                    "text/plain", "must_not_exist.txt")
            }.getOrNull()
            assertNull("a file was created on a volume that must be read-only", created)
        }
        assertTrue("Copied flower image should have non-zero size", outFile.length() > 0)

        // Open the image via the DocumentsProvider content:// URI (drive is still mounted here).
        // A file:// URI would throw FileUriExposedException when handed to another app on API 24+.
        val viewIntent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(providerUri, "image/jpeg")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        try {
            context.startActivity(viewIntent)
        } catch (e: Exception) {
            fail("Failed to launch Gallery app for the image: ${e.message}")
        }

        val galleryOpened = device.wait(Until.hasObject(By.pkg(java.util.regex.Pattern.compile(".*(gallery|photos|media|files|documentsui).*"))), 10000L)
        assertTrue("Gallery/viewer app did not open the flower image file!", galleryOpened)

        // Pause here so a human watching the emulator can actually see the flower image in the viewer
        android.os.SystemClock.sleep(2500)

        // Return to OTGMaster reliably instead of using pressBack() which might get caught in Gallery overlays
        val otgIntent = mainActivityIntent().apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or
                Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
        context.startActivity(otgIntent)
        device.wait(Until.hasObject(By.pkg("app.fayaz.otgmaster").depth(0)), timeout * 2)

        // Pause here so a human watching the emulator can see OTGMaster back in front before the unmount click
        android.os.SystemClock.sleep(2000)

        // A plain drive has no Unmount button by design: it was auto-mounted, and is
        // released by unplugging. Its card must still be there after the viewer.
        openSlider()
        if (plainMount) {
            assertNotNull("plain drive card gone after returning from the viewer",
                device.wait(Until.findObject(By.textContains("Used:")), timeout))
            assertNull("a plain drive must not offer Unmount",
                device.findObject(By.descContains("unmount_button")))
            return
        }
        val unmountButton = device.wait(Until.findObject(By.descContains("unmount_button")), timeout)
        assertTrue("Unmount button not found", unmountButton != null)
        unmountButton?.click()

        // 15 s, not 5: on a loaded emulator the card can outlive the unmount by more
        // than 5 s (the fat32 case failed once with the unmount already logged as done).
        val unmountGone = device.wait(Until.gone(By.descContains("unmount_button")), UNMOUNT_WAIT_MS)
        assertTrue("Drive was not successfully unmounted!", unmountGone)
        }
    }

    @Test
    fun testWriteOperations() {
        val arguments = InstrumentationRegistry.getArguments()
        if (arguments.getString("write_test", "false") != "true") return
        if (arguments.getString("erase_action", "").isNotEmpty()) return

        val password = arguments.getString("password", "password123")
        val pim = arguments.getString("pim", "")
        val testCase = arguments.getString("testCase", "fat32_write")
        val context = ApplicationProvider.getApplicationContext<Context>()

        android.os.SystemClock.sleep(1000)

        // ── MOUNT #1 ──────────────────────────────────────────────────────────
        val scanBtn1 = findScanButton()
            ?: device.wait(Until.findObject(By.descContains("Scan").clickable(true)), timeout)
        assertNotNull("Scan button not found", scanBtn1)
        scanBtn1!!.click()
        device.wait(Until.findObject(By.text(java.util.regex.Pattern.compile("(?i)Allow|OK"))), 5000L)?.click()

        assertContainerTag(arguments.getString("expected_container", ""))
        assertTrue("Mount #1 failed", doMount(password, pim, testCase, clearFields = false))

        val rootDocId1 = getRootDocId(context)
        assertNotNull("No root after mount #1", rootDocId1)

        // ── READ: flower.jpg must arrive byte for byte (support-matrix cases) ──
        val flowerSha = arguments.getString("flower_sha256", "")
        if (flowerSha.isNotEmpty()) {
            var flowerId: String? = null
            context.contentResolver.query(
                DocumentsContract.buildChildDocumentsUri(AUTHORITY, rootDocId1!!), null, null, null, null
            )?.use { cur ->
                while (cur.moveToNext()) {
                    val id = cur.getString(cur.getColumnIndex(DocumentsContract.Document.COLUMN_DOCUMENT_ID)) ?: continue
                    if (id.endsWith("flower.jpg")) flowerId = id
                }
            }
            assertNotNull("flower.jpg not listed in the root", flowerId)
            val md = java.security.MessageDigest.getInstance("SHA-256")
            context.contentResolver.openInputStream(DocumentsContract.buildDocumentUri(AUTHORITY, flowerId!!))
                ?.use { input ->
                    val buf = ByteArray(65536)
                    while (true) { val n = input.read(buf); if (n < 0) break; md.update(buf, 0, n) }
                } ?: fail("flower.jpg could not be opened")
            assertEquals("flower.jpg read through the provider differs from the original",
                flowerSha, md.digest().joinToString("") { "%02x".format(it) })
        }

        // ── WRITE: create file + directory + nested file ───────────────────────
        val rootUri1 = DocumentsContract.buildDocumentUri(AUTHORITY, rootDocId1!!)

        val fileContent = "OTGMaster write-test content"
        val newFileUri = DocumentsContract.createDocument(
            context.contentResolver, rootUri1, "text/plain", "write_test.txt"
        )
        assertNotNull("createDocument(write_test.txt) returned null", newFileUri)
        context.contentResolver.openOutputStream(newFileUri!!)?.use { it.write(fileContent.toByteArray()) }
            ?: fail("openOutputStream for write_test.txt returned null")

        val newDirUri = DocumentsContract.createDocument(
            context.contentResolver, rootUri1, DocumentsContract.Document.MIME_TYPE_DIR, "write_dir"
        )
        assertNotNull("createDocument(write_dir) returned null", newDirUri)

        val nestedContent = "nested content"
        val nestedUri = DocumentsContract.createDocument(
            context.contentResolver, newDirUri!!, "text/plain", "nested.txt"
        )
        assertNotNull("createDocument(nested.txt) returned null", nestedUri)
        context.contentResolver.openOutputStream(nestedUri!!)?.use { it.write(nestedContent.toByteArray()) }
            ?: fail("openOutputStream for nested.txt returned null")

        // A multi-megabyte file as well: 28 bytes fit inside an NTFS file record
        // (or one cluster anywhere), so the small files never exercised cluster
        // allocation, growth across clusters or a fragmented write. Its content is
        // deterministic so scripts/verify_e2e_volume.py can regenerate it and check
        // it on the host without trusting the app.
        val bigUri = DocumentsContract.createDocument(
            context.contentResolver, newDirUri, "application/octet-stream", BIG_FILE_NAME
        )
        assertNotNull("createDocument($BIG_FILE_NAME) returned null", bigUri)
        context.contentResolver.openOutputStream(bigUri!!)?.use { out ->
            var block = 0
            while (block < BIG_FILE_BLOCKS) {
                val chunk = java.io.ByteArrayOutputStream()
                repeat(minOf(2048, BIG_FILE_BLOCKS - block)) { chunk.write(bigFileBlock(block++)) }
                out.write(chunk.toByteArray())
            }
        } ?: fail("openOutputStream for $BIG_FILE_NAME returned null")

        doUnmount()

        // ── REMOUNT #2 — verify write persistence ─────────────────────────────
        val scanBtn2 = findScanButton()
            ?: device.wait(Until.findObject(By.descContains("Scan").clickable(true)), timeout)
        assertNotNull("Scan button not found", scanBtn2)
        scanBtn2!!.click()
        android.os.SystemClock.sleep(2000)

        // Where the volume has a second secret (a BitLocker recovery key), remount
        // with it: both protectors must open the same volume and see the same files.
        val secondSecret = arguments.getString("recovery", "").ifEmpty { password }
        assertTrue("Mount #2 failed", doMount(secondSecret, pim, testCase, clearFields = true))

        val rootDocId2 = getRootDocId(context)
        assertNotNull("No root after mount #2", rootDocId2)
        val childrenUri2 = DocumentsContract.buildChildDocumentsUri(AUTHORITY, rootDocId2!!)

        var writeTxtId: String? = null
        var writeDirId: String? = null
        context.contentResolver.query(childrenUri2, null, null, null, null)?.use { cur ->
            while (cur.moveToNext()) {
                val id = cur.getString(cur.getColumnIndex(DocumentsContract.Document.COLUMN_DOCUMENT_ID)) ?: continue
                if (id.endsWith("write_test.txt")) writeTxtId = id
                if (id.endsWith("write_dir")) writeDirId = id
            }
        }
        assertNotNull("write_test.txt missing after remount #2", writeTxtId)
        assertNotNull("write_dir missing after remount #2", writeDirId)

        // verify nested.txt inside write_dir
        var nestedId: String? = null
        context.contentResolver.query(
            DocumentsContract.buildChildDocumentsUri(AUTHORITY, writeDirId!!), null, null, null, null
        )?.use { cur ->
            while (cur.moveToNext()) {
                val id = cur.getString(cur.getColumnIndex(DocumentsContract.Document.COLUMN_DOCUMENT_ID)) ?: continue
                if (id.endsWith("nested.txt")) nestedId = id
            }
        }
        assertNotNull("nested.txt missing inside write_dir after remount #2", nestedId)

        // verify file content survived remount
        val writeTxtUri2 = DocumentsContract.buildDocumentUri(AUTHORITY, writeTxtId!!)
        val readBack = context.contentResolver.openInputStream(writeTxtUri2)
            ?.use { it.readBytes().toString(Charsets.UTF_8) }
        assertEquals("write_test.txt content changed after remount #2", fileContent, readBack)
        val nestedBack = context.contentResolver.openInputStream(
            DocumentsContract.buildDocumentUri(AUTHORITY, nestedId!!))?.use { it.readBytes().toString(Charsets.UTF_8) }
        assertEquals("nested.txt content changed after remount #2", nestedContent, nestedBack)

        var bigId: String? = null
        context.contentResolver.query(
            DocumentsContract.buildChildDocumentsUri(AUTHORITY, writeDirId!!), null, null, null, null
        )?.use { cur ->
            while (cur.moveToNext()) {
                val id = cur.getString(cur.getColumnIndex(DocumentsContract.Document.COLUMN_DOCUMENT_ID)) ?: continue
                if (id.endsWith(BIG_FILE_NAME)) bigId = id
            }
        }
        assertNotNull("$BIG_FILE_NAME missing inside write_dir after remount #2", bigId)
        val bigDigest = java.security.MessageDigest.getInstance("SHA-256")
        var bigLen = 0L
        context.contentResolver.openInputStream(DocumentsContract.buildDocumentUri(AUTHORITY, bigId!!))?.use { input ->
            val buf = ByteArray(65536)
            while (true) { val n = input.read(buf); if (n < 0) break; bigDigest.update(buf, 0, n); bigLen += n }
        } ?: fail("$BIG_FILE_NAME could not be opened after remount #2")
        assertEquals("$BIG_FILE_NAME length after remount #2", BIG_FILE_BLOCKS * 32L, bigLen)
        assertEquals("$BIG_FILE_NAME content changed after remount #2", expectedBigFileSha(),
            bigDigest.digest().joinToString("") { "%02x".format(it) })

        // ── DELETE write_test.txt ──────────────────────────────────────────────
        DocumentsContract.deleteDocument(context.contentResolver, writeTxtUri2)

        var goneImmediately = true
        context.contentResolver.query(childrenUri2, null, null, null, null)?.use { cur ->
            while (cur.moveToNext()) {
                val id = cur.getString(cur.getColumnIndex(DocumentsContract.Document.COLUMN_DOCUMENT_ID))
                if (id?.endsWith("write_test.txt") == true) goneImmediately = false
            }
        }
        assertTrue("write_test.txt still visible immediately after deletion", goneImmediately)

        // ── DELETE a folder with files in it ───────────────────────────────────
        // SafFileTree empties a folder first, but the Files app and other apps do not.
        val treeUri = DocumentsContract.createDocument(
            context.contentResolver, DocumentsContract.buildDocumentUri(AUTHORITY, rootDocId2),
            DocumentsContract.Document.MIME_TYPE_DIR, "delete_tree"
        )
        assertNotNull("createDocument(delete_tree) returned null", treeUri)
        val subUri = DocumentsContract.createDocument(
            context.contentResolver, treeUri!!, DocumentsContract.Document.MIME_TYPE_DIR, "sub"
        )
        assertNotNull("createDocument(delete_tree/sub) returned null", subUri)
        for ((parent, name) in listOf(treeUri to "top.txt", subUri!! to "deep.txt")) {
            val uri = DocumentsContract.createDocument(context.contentResolver, parent, "text/plain", name)
            assertNotNull("createDocument($name) returned null", uri)
            context.contentResolver.openOutputStream(uri!!)?.use { it.write(name.toByteArray()) }
                ?: fail("openOutputStream for $name returned null")
        }
        assertTrue("deleting a folder with files in it failed: see the log",
            DocumentsContract.deleteDocument(context.contentResolver, treeUri))
        context.contentResolver.query(childrenUri2, null, null, null, null)?.use { cur ->
            while (cur.moveToNext()) {
                val id = cur.getString(cur.getColumnIndex(DocumentsContract.Document.COLUMN_DOCUMENT_ID))
                assertFalse("delete_tree still listed after deleting it", id?.endsWith("delete_tree") == true)
            }
        }

        // ── DELETE a folder with a file in it still open for writing ───────────
        // The file's close writes to the volume, so it has to come before the delete. Held open it
        // is refused, and once closed it goes: the case that left FAT32 an orphan cluster when the
        // close was only late. The host's checker sees that.
        val heldDir = DocumentsContract.createDocument(
            context.contentResolver, DocumentsContract.buildDocumentUri(AUTHORITY, rootDocId2),
            DocumentsContract.Document.MIME_TYPE_DIR, "delete_held"
        ) ?: throw AssertionError("createDocument(delete_held) returned null")
        val heldFile = DocumentsContract.createDocument(context.contentResolver, heldDir, "text/plain", "held.txt")
            ?: throw AssertionError("createDocument(held.txt) returned null")
        val heldOut = context.contentResolver.openOutputStream(heldFile)
            ?: throw AssertionError("openOutputStream for held.txt returned null")
        var refused: Boolean
        try {
            heldOut.write("still open".toByteArray())
            heldOut.flush()
            refused = !DocumentsContract.deleteDocument(context.contentResolver, heldDir)
        } finally {
            heldOut.close()
        }
        assertTrue("a folder was deleted with a file in it open for writing", refused)
        assertTrue("delete_held could not be deleted once its file was closed",
            DocumentsContract.deleteDocument(context.contentResolver, heldDir))

        // ── DELETE an open file: its space must come back at once ──────────────
        // exFAT freed a deleted file's clusters only when the last handle on it was
        // released — and a listing's handles are released by the GC, or never if the
        // drive is unmounted first. A full drive then refused the next write with
        // ENOSPC straight after a delete that had succeeded. Holding the file open
        // across the delete makes that deterministic rather than GC-dependent.
        val leakUri = DocumentsContract.createDocument(
            context.contentResolver, DocumentsContract.buildDocumentUri(AUTHORITY, rootDocId2),
            "application/octet-stream", LEAK_FILE_NAME
        )
        assertNotNull("createDocument($LEAK_FILE_NAME) returned null", leakUri)
        context.contentResolver.openOutputStream(leakUri!!)?.use { out ->
            val chunk = ByteArray(65536) { 0x5a }
            repeat(LEAK_FILE_BYTES / chunk.size) { out.write(chunk) }
        } ?: fail("openOutputStream for $LEAK_FILE_NAME returned null")
        val holder = context.contentResolver.openInputStream(leakUri)
            ?: throw AssertionError("$LEAK_FILE_NAME could not be opened")
        try {
            assertEquals("$LEAK_FILE_NAME first byte", 0x5a, holder.read())
            val freeBefore = availableBytes(context, rootDocId2)
            DocumentsContract.deleteDocument(context.contentResolver, leakUri)
            val freeAfter = availableBytes(context, rootDocId2)
            assertTrue(
                "Deleting $LEAK_FILE_NAME ($LEAK_FILE_BYTES bytes) while it was open freed only " +
                    "${freeAfter - freeBefore} bytes (free $freeBefore -> $freeAfter)",
                freeAfter - freeBefore >= LEAK_FILE_BYTES,
            )
        } finally {
            holder.close()
        }

        doUnmount()

        // ── REMOUNT #3 — verify deletion persisted ────────────────────────────
        val scanBtn3 = findScanButton()
            ?: device.wait(Until.findObject(By.descContains("Scan").clickable(true)), timeout)
        assertNotNull("Scan button not found", scanBtn3)
        scanBtn3!!.click()
        android.os.SystemClock.sleep(2000)

        assertTrue("Mount #3 failed", doMount(password, pim, testCase, clearFields = true))

        val rootDocId3 = getRootDocId(context)
        assertNotNull("No root after mount #3", rootDocId3)
        var reappeared = false
        context.contentResolver.query(
            DocumentsContract.buildChildDocumentsUri(AUTHORITY, rootDocId3!!), null, null, null, null
        )?.use { cur ->
            while (cur.moveToNext()) {
                val id = cur.getString(cur.getColumnIndex(DocumentsContract.Document.COLUMN_DOCUMENT_ID))
                if (id?.endsWith("write_test.txt") == true) reappeared = true
            }
        }
        assertFalse("write_test.txt reappeared after remount #3 — deletion not persisted!", reappeared)

        doUnmount()
    }

    /**
     * Format and Encrypt from a mounted card, through the UI a user drives: the More
     * menu, the confirmation screen (typing ERASE), the progress dialog. Then, through
     * the provider: the fixture's flower.jpg is gone, a new file can be written and read
     * back, and — where the result is encrypted — the volume unmounts and unlocks again
     * with the password it now has and still holds that file.
     * scripts/verify_e2e_volume.py --erase then checks the drive on the host.
     *
     * Case files: erase.txt (action, quick, fs, and for encrypt type (luks2, else VeraCrypt),
     * cipher, hash, pim, password, label), plus plain.txt for a volume that auto-mounts
     * without a password.
     */
    @Test
    fun testEraseOperations() {
        val arguments = InstrumentationRegistry.getArguments()
        val action = arguments.getString("erase_action", "")
        if (action.isEmpty()) return
        val password = arguments.getString("password", "password123")
        val pim = arguments.getString("pim", "")
        val testCase = arguments.getString("testCase", "")
        val plain = arguments.getString("plain_mount", "false") == "true"
        val quick = arguments.getString("erase_quick", "false") == "true"
        val context = ApplicationProvider.getApplicationContext<Context>()
        android.os.SystemClock.sleep(1000)

        val scan = findScanButton()
        assertNotNull("Scan button not found", scan)
        scan!!.click()
        device.wait(Until.findObject(By.text(java.util.regex.Pattern.compile("(?i)Allow|OK"))), 5000L)?.click()
        if (plain) {
            assertNotNull("plain volume was not auto-mounted", waitForMounted(120000L))
        } else {
            assertTrue("Mount before $action failed", doMount(password, pim, testCase, clearFields = false))
        }
        assertTrue("flower.jpg not on the volume before $action", listRoot(context).any { it.endsWith("flower.jpg") })

        // ── The card's action, as a button if it fits on the row, else in More actions ──
        openSlider()
        val item = device.findObject(By.desc("${action}_action")) ?: run {
            val more = findScrolling(By.desc("more_actions_button"), desc = "more_actions_button")
            assertNotNull("neither $action nor More actions is on the mounted card", more)
            more!!.click()
            device.wait(Until.findObject(By.desc("${action}_action")), timeout)
        }
        if (item == null) captureScreen("no_${action}_action")
        assertNotNull("$action not on the card or in More actions", item)
        assertTrue("$action is disabled for this volume", item!!.isEnabled)
        item.click()
        android.os.SystemClock.sleep(1000)

        var newPassword = password
        var newPim = pim
        var newCipher = arguments.getString("cipher", "")
        var newHash = arguments.getString("hash", "")
        // erase_fs is the filesystem the picker is set to: exfat (its default), fat32, ext4, ntfs.
        val eraseFs = arguments.getString("erase_fs", "")
        val fsOption = mapOf("fat32" to "FAT32", "exfat" to "exFAT", "ext4" to "ext4", "ntfs" to "NTFS")[eraseFs]
        if (action == "format" && fsOption != null) pickOption("format_fs", fsOption, inDialog = true)
        if (action == "encrypt") {
            val luks2 = arguments.getString("erase_type", "") == "luks2"
            newPassword = arguments.getString("erase_password", "")
            if (luks2) {
                // LUKS2 has no cipher, KDF, PIM or keyfile choices; its unlock form neither.
                pickOption("encrypt_type", "LUKS2", inDialog = true)
                newPim = ""; newCipher = ""; newHash = ""
            } else {
                newPim = arguments.getString("erase_pim", "")
                newCipher = arguments.getString("erase_cipher", "AES")
                newHash = arguments.getString("erase_hash", "SHA-512")
            }
            if (newPassword != NO_PASSWORD) {
                typeInto("encrypt_password", newPassword)
                typeInto("encrypt_password_confirm", newPassword)
                hideKeyboard()
            }
            if (!luks2) {
                unlockKeyfiles = arguments.getString("erase_keyfiles", "").split(',').filter { it.isNotEmpty() }
                unlockKeyfiles.forEach { name ->
                    clickInDialog("encrypt_keyfiles")
                    pickKeyfileInFiles(name)
                }
                if (newCipher != "AES") pickOption("encrypt_cipher", newCipher, inDialog = true)
                if (newHash != "SHA-512") pickOption("encrypt_kdf", newHash, inDialog = true)
                if (newPim.isNotEmpty()) {
                    typeInto("encrypt_pim", newPim); hideKeyboard()
                    typeInto("encrypt_pim_confirm", newPim); hideKeyboard()
                }
            }
            if (fsOption != null && eraseFs != "exfat") pickOption("encrypt_fs", fsOption, inDialog = true)
            arguments.getString("erase_label", "").takeIf { it.isNotEmpty() }?.let {
                typeInto("encrypt_label", it); hideKeyboard()
            }
        }
        if (quick) clickInDialog("quick_checkbox")
        val confirm = findInDialog("${action}_confirm_button")
        assertNotNull("$action confirm button not found", confirm)
        if (!confirm!!.isEnabled) captureScreen("${action}_confirm_disabled")
        assertTrue("$action button disabled with the form filled in", confirm.isEnabled)
        confirm.click()
        // The erase itself is confirmed in a dialog: nothing starts until ERASE is typed.
        val eraseNow = device.wait(Until.findObject(By.desc("erase_dialog_confirm")), timeout)
        if (eraseNow == null) captureScreen("erase_dialog_missing")
        assertNotNull("the ERASE confirmation dialog did not open", eraseNow)
        // Tapped before ERASE is typed, it must do nothing. (Its isEnabled cannot say: the
        // accessibility node uiautomator gets for the Compose button reads enabled either way.)
        eraseNow!!.click()
        android.os.SystemClock.sleep(1500)
        assertNotNull("the confirmation dialog closed on Erase without ERASE typed",
            device.findObject(By.desc("erase_confirm_field")))
        assertNull("an erase started without ERASE typed", device.findObject(By.desc("operation_done_button")))
        assertNull("an erase started without ERASE typed", device.findObject(By.text(context.getString(R.string.erase_keep_attached))))
        typeInto("erase_confirm_field", "ERASE")
        hideKeyboard()
        val eraseEnabled = device.wait(Until.findObject(By.desc("erase_dialog_confirm")), timeout)
        assertNotNull("the dialog's Erase button went away", eraseEnabled)
        eraseEnabled!!.click()
        // The confirmation screen closes as the operation starts; if it is still up,
        // the click did not take, and waiting for the result would only time out.
        if (!device.wait(Until.gone(By.desc("${action}_confirm_button")), 15000L)) {
            captureScreen("${action}_confirm_did_not_start")
            fail("$action did not start: the confirmation screen is still showing")
        }

        // ── Progress, then the result ─────────────────────────────────────────
        val done = device.wait(Until.findObject(By.desc("operation_done_button")), 900_000L)
        if (done == null) captureScreen("${action}_never_finished")
        assertNotNull("$action did not finish within 15 minutes", done)
        val failed = device.findObject(By.textStartsWith("Failed:"))
        if (failed != null) captureScreen("${action}_failed")
        assertNull("$action failed: ${failed?.text}", failed)
        done!!.click()
        if (action == "encrypt") {
            // Not mounted after Encrypt: the new volume is offered for unlock, so its
            // password is proved before anything is written to it (#94).
            android.os.SystemClock.sleep(800)
            assertFalse("the slider covers the form that offers the new volume", sliderOpen())
            assertNull("the new volume was mounted without its password", waitForMounted(5000L))
            assertTrue("password field not offered for the new volume",
                device.wait(Until.findObject(By.descContains("password_input")), 30000L) != null)
            if (newCipher.isNotEmpty() && newCipher != "AES") pickOption("cipher_picker", newCipher)
            if (newHash.isNotEmpty() && newHash != "SHA-512") pickOption("hash_picker", newHash)
            assertTrue("the new volume did not unlock with its password after encrypt",
                doMount(newPassword, newPim, testCase, clearFields = true))
        }
        assertNotNull("no mounted card after $action", waitForMounted(60000L))

        var root = listRoot(context)
        assertFalse("flower.jpg survived the $action: $root", root.any { it.endsWith("flower.jpg") })
        val content = "OTGMaster after $action ($testCase)"
        val rootId = getRootDocId(context)!!
        val uri = DocumentsContract.createDocument(context.contentResolver,
            DocumentsContract.buildDocumentUri(AUTHORITY, rootId), "text/plain", "after_erase.txt")
        assertNotNull("createDocument(after_erase.txt) failed on the new volume", uri)
        context.contentResolver.openOutputStream(uri!!)?.use { it.write(content.toByteArray()) }
            ?: fail("openOutputStream(after_erase.txt) returned null")
        assertEquals(content, context.contentResolver.openInputStream(uri)?.use { it.readBytes().toString(Charsets.UTF_8) })

        // An encrypted result must open again with the password it now has.
        if (action == "encrypt" || !plain) {
            doUnmount()
            val scan2 = findScanButton()
            assertNotNull("Scan button not found for the remount", scan2)
            scan2!!.click()
            android.os.SystemClock.sleep(2000)
            assertTrue("password field not offered for the new volume",
                device.wait(Until.findObject(By.descContains("password_input")), 30000L) != null)
            if (newCipher.isNotEmpty() && newCipher != "AES") pickOption("cipher_picker", newCipher)
            if (newHash.isNotEmpty() && newHash != "SHA-512") pickOption("hash_picker", newHash)
            assertTrue("the volume did not unlock with its new password after $action",
                doMount(newPassword, newPim, testCase, clearFields = true))
            root = listRoot(context)
            val id = root.firstOrNull { it.endsWith("after_erase.txt") }
            assertNotNull("after_erase.txt missing after the remount: $root", id)
            assertEquals("after_erase.txt changed across the remount", content,
                context.contentResolver.openInputStream(DocumentsContract.buildDocumentUri(AUTHORITY, id!!))
                    ?.use { it.readBytes().toString(Charsets.UTF_8) })
            doUnmount()
        }
    }

    /**
     * A drive that goes away while mounted (issue #50). The runner removes the emulator's
     * USB drive with QEMU's device_del when asked, so the volume's I/O really fails; the
     * app is then told through [OtgMasterState.detachRequest], because the emulator's
     * drive is read through /dev/block/sda and Android broadcasts no detach for it.
     *
     * Two drops: the first on battery (42%), checked in full — card, banner, the advice
     * that leads with power, Dismiss, the no-drive help — and the second on external
     * power, cleared by mounting the same partition again rather than by Dismiss.
     * Called from [testMountUsbDrive] when the case has disconnect.txt.
     */
    private fun runDisconnectCase() {
        val arguments = InstrumentationRegistry.getArguments()
        val password = arguments.getString("password", "password123")
        val pim = arguments.getString("pim", "")
        val testCase = arguments.getString("testCase", "disconnect")
        android.os.SystemClock.sleep(1000)
        try {
            scanAndMount(password, pim, testCase, "before the first drop")

            // ── Drop 1, on battery ───────────────────────────────────────────────
            device.executeShellCommand("dumpsys battery unplug")
            device.executeShellCommand("dumpsys battery set level 42")
            dropMountedDrive(banner = "may need more power")
            assertDisconnectedCard("on battery, 42%")

            clickScrolling("see_recommendations", "See recommendations link missing on the disconnected card")
            assertDialog("Why drives disconnect", "The phone was on battery")

            clickScrolling("dismiss_disconnected_button", "Dismiss missing on the disconnected card")
            assertTrue("Dismiss did not remove the disconnected card",
                device.wait(Until.gone(By.desc("disconnected_card")), timeout))

            // A Scan with nothing attached offers the no-drive help.
            findScanButton()!!.click()
            assertNotNull("'No USB drives available' not shown after a scan with the drive out",
                device.wait(Until.findObject(By.textContains("No USB drives available")), 15000L))
            clickScrolling("see_recommendations", "See recommendations link missing under 'No USB drives available'")
            assertDialog("Drive not showing up?", "OTG adapter")

            requestUsb("replug")
            scanAndMount(password, pim, testCase, "after the first drop")
            assertHistory(1, "on battery, 42%")

            // ── Drop 2, on external power ───────────────────────────────────────
            device.executeShellCommand("dumpsys battery set ac 1")
            dropMountedDrive(banner = "Check the cable")
            assertDisconnectedCard("on external power")
            clickScrolling("see_recommendations", "See recommendations link missing on the second disconnected card")
            assertDialog("Why drives disconnect", "The phone was on external power")

            requestUsb("replug")
            scanAndMount(password, pim, testCase, "after the second drop")
            if (!device.wait(Until.gone(By.desc("disconnected_card")), 10000L)) captureScreen("card_not_cleared")
            assertNull("mounting the partition again did not clear its disconnected card",
                device.findObject(By.desc("disconnected_card")))
            assertHistory(2, "on external power")

            // ── Drop 3, as the "Mounted" note ends ──────────────────────────────
            // A new note cancels the old one's timer only at the next recomposition. Keeping
            // the main thread busy across the end of that timer fires it first, every time,
            // which is what lost this warning now and then when a drive dropped by chance.
            doUnmount()
            scanAndMount(password, pim, testCase, "before the third drop")
            assertNotNull("no 'Mounted' note to overlap",
                device.wait(Until.findObject(By.textStartsWith("Mounted:")), 3000L))
            dropMountedDrive(banner = "Check the cable",
                noteSeenAt = android.os.SystemClock.uptimeMillis())
            requestUsb("replug")
            scanAndMount(password, pim, testCase, "after the third drop")

            doUnmount()
        } finally {
            device.executeShellCommand("dumpsys battery reset")
        }
    }

    /**
     * A repository made by desktop restic, on a plain FAT32 drive, that no job of the app
     * knows (testdata/backup_mount, made by scripts/make_backup_fixture.py; docs/TEST_DATA.md
     * §6f), reached the way a user reaches one: Browse for backup, the Files picker, a typed
     * password.
     *
     * The order matters. The Files app comes before this process reads the backup, because
     * the snapshot list is cached once read and this process needs no grant: read from here
     * first, a listing that fails for another app would pass. The host then checks that the
     * drive did not change (scripts/verify_e2e_volume.py, which sees backup.txt).
     */
    private fun runBackupCase() {
        val arguments = InstrumentationRegistry.getArguments()
        val password = arguments.getString("password", "")
        val testCase = arguments.getString("testCase", "backup_mount")
        val context = ApplicationProvider.getApplicationContext<Context>()
        val expected = device.executeShellCommand("cat /data/local/tmp/testdata/$testCase/backup_expected.txt")
            .lines().filter { it.isNotBlank() }.map { it.split('\t') }
        val snapshotIds = expected.filter { it[0] == "snapshot" }.associate { it[1] to it[2] }
        assertEquals("backup_expected.txt names two snapshots", 2, snapshotIds.size)
        android.os.SystemClock.sleep(1000)

        findScanButton()!!.click()
        device.wait(Until.findObject(By.text(java.util.regex.Pattern.compile("(?i)OK|ALLOW"))), 5000L)?.click()
        if (waitForMounted(60000L) == null) captureScreen("backup_no_source_card")
        val source = OtgMasterState.mountedDrives.firstOrNull()
        assertNotNull("the plain drive holding the backup did not mount", source)

        // ── A drive is mounted, so a schedule can be created ─────────────────
        openBackupsTab("backups_open_schedules")
        // By tapping: isEnabled on a Compose button's description node cannot say.
        val create = device.wait(Until.findObject(By.desc("backups_create_schedule")), timeout)
        assertNotNull("Create reminder missing", create)
        assertNull("the no-devices message shows with a drive mounted",
            device.findObject(By.text(context.getString(R.string.backups_create_no_devices))))
        create!!.click()
        if (device.wait(Until.findObject(By.desc(BACKUP_DIALOG_FIELD)), timeout) == null) captureScreen("backup_create_no_dialog")
        assertNotNull("Create reminder did not open its form with a drive mounted", device.findObject(By.desc(BACKUP_DIALOG_FIELD)))
        device.pressBack()
        assertTrue("the Create reminder form did not close", device.wait(Until.gone(By.desc(BACKUP_DIALOG_FIELD)), timeout))

        // ── A folder with no repository in it ────────────────────────────────
        browseToFolder(source!!.name, "not_a_backup")
        val none = device.wait(Until.findObject(By.text(context.getString(R.string.backups_browse_none))), 30000L)
        if (none == null) captureScreen("backup_none_not_shown")
        assertNotNull("'No backups found' not shown for a folder without a repository", none)
        assertTrue("'No backups found' stayed on the screen", device.wait(Until.gone(By.text(context.getString(R.string.backups_browse_none))), 15000L))

        // ── The repository: a wrong password, then the right one ─────────────
        browseToFolder(source.name, BACKUP_REPO)
        typeInto("backups_browse_password", "not-the-password")
        clickInDialog("backups_browse_mount")
        val wrong = device.wait(Until.findObject(By.text(context.getString(R.string.backups_browse_wrong))), 60000L)
        if (wrong == null) captureScreen("backup_wrong_not_shown")
        assertNotNull("a wrong password was not refused", wrong)
        val backup = mountBrowsedBackup(password)

        // ── Another app lists it: the Files app, from the card's own button ──
        returnToMainScreen()
        val title = "$BACKUP_REPO (backup)"
        assertEquals("the mounted backup's name", title, backup.name)
        val card = findScrolling(By.text(title), text = title)
        if (card == null) captureScreen("backup_no_card")
        assertNotNull("no card for the mounted backup", card)
        assertNotNull("the backup's card is not tagged ENCRYPTED", device.wait(Until.findObject(By.text("ENCRYPTED")), timeout))
        openBackupInFiles(context, title, snapshotIds.getValue("2"))
        returnToMainScreen()

        // ── Every file of both snapshots ─────────────────────────────────────
        val snapshots = documentChildren(context, "${backup.id}:/")
        assertEquals("one folder per snapshot: ${snapshots.keys}", 2, snapshots.size)
        fun snapshotDoc(number: String): String {
            val id = snapshotIds.getValue(number)
            val name = snapshots.keys.firstOrNull { it.contains("($id)") }
            assertNotNull("snapshot $id not listed: ${snapshots.keys}", name)
            return snapshots.getValue(name!!)
        }
        expected.filter { it[0] == "file" }.forEach { (_, number, sha, path) ->
            assertEquals("$path in snapshot $number", sha, sha256(context, documentAt(context, snapshotDoc(number), path)))
        }
        // Opened at its middle, as a video player does.
        expected.filter { it[0] == "range" }.forEach { line ->
            val (number, sha, path) = line.drop(1)
            val doc = documentAt(context, snapshotDoc(number), path)
            assertEquals("$path from offset ${line[4]} in snapshot $number", sha,
                sha256Range(context, doc, line[4].toLong(), line[5].toInt()))
        }
        assertFalse("added_later.txt is in the snapshot taken before it existed",
            "added_later.txt" in documentChildren(context, snapshotDoc("1")))

        // ── Read-only ────────────────────────────────────────────────────────
        val created = runCatching {
            DocumentsContract.createDocument(context.contentResolver,
                DocumentsContract.buildDocumentUri(AUTHORITY, snapshotDoc("2")), "text/plain", "refused.txt")
        }.getOrNull()
        assertNull("a file was created in a mounted backup", created)

        // ── Unmount, and mount again ─────────────────────────────────────────
        doUnmount()
        assertFalse("the backup is still mounted after Unmount", OtgMasterState.mountedDrives.any { it.id == backup.id })
        browseToFolder(source.name, BACKUP_REPO)
        mountBrowsedBackup(password)
        returnToMainScreen()

        // ── A job on this drive: each end's folder shown, and its saved password mounts it ─
        val tree = { folder: String -> DocumentsContract.buildTreeDocumentUri(AUTHORITY, "${source.id}:/$folder") }
        val jobs = JobStore.get(context)
        val endpoint = { uri: android.net.Uri ->
            Endpoint(uri.toString(), source.name, VolumeInfo.kindOf(uri), VolumeInfo.capture(context, uri, withFreeSpace = false))
        }
        val job = BackupJob(
            name = "E2E row",
            type = JobType.INCREMENTAL,
            source = endpoint(tree("not_a_backup")),
            destination = endpoint(tree("")),
            destinationFolder = BACKUP_REPO,
            schedule = Schedule(intervalHours = 168, autoRun = true, remindAfterHours = 0),
            retention = Retention(),
            lastSuccessAt = System.currentTimeMillis(),
        )
        BackupSecrets(context).setPassword(job.id, password)
        jobs.upsert(job)
        openBackupsTab("backups_open_schedules")
        assertNotNull("the job's row does not say From", device.wait(Until.findObject(By.text(context.getString(R.string.backup_from))), timeout))
        assertNotNull("the job's row does not say To", device.findObject(By.text(context.getString(R.string.backup_to))))
        assertNotNull("the job's row does not name its source folder",
            device.findObject(By.text(context.getString(R.string.backup_folder_at, "/not_a_backup"))))
        assertNotNull("the job's row does not name its target folder",
            device.findObject(By.text(context.getString(R.string.backup_folder_at, "/$BACKUP_REPO"))))
        // History: a backup shows both ends. A restore runs from the backup, and its target
        // is not recorded unless it went into the job's own source.
        val sourceFolder = context.getString(R.string.backup_folder_at, "/not_a_backup")
        val targetFolder = context.getString(R.string.backup_folder_at, "/$BACKUP_REPO")
        val now = System.currentTimeMillis()
        jobs.addRun(RunRecord(jobId = job.id, kind = "restore", reason = "restore", startedAt = now, finishedAt = now, status = RunStatus.SUCCESS))
        assertTrue("History tab missing", clickByDesc("backups_tab_history"))
        assertNotNull("a restore's row does not name the backup it came from", device.wait(Until.findObject(By.text(targetFolder)), timeout))
        assertNull("a restore's row names the source as its target", device.findObject(By.text(sourceFolder)))
        jobs.addRun(RunRecord(jobId = job.id, kind = "backup", reason = "manual", startedAt = now + 1, finishedAt = now + 1, status = RunStatus.SUCCESS))
        assertNotNull("a backup's row does not name its source folder", device.wait(Until.findObject(By.text(sourceFolder)), timeout))
        // Mounted already, by the password asked for above: choosing it again does not mount it twice.
        browseToFolder(source.name, BACKUP_REPO)
        bkExpectMountAnnounced("$BACKUP_REPO (backup)")
        assertEquals("the folder was mounted a second time", 1, OtgMasterState.mountedDrives.count { it.filesystemName == "Backup" })
        assertTrue("it took the job's name while mounted", OtgMasterState.mountedDrives.none { it.id == BackupMounts.driveIdFor(job.id) })
        returnToMainScreen()
        openSlider("mounted_tab_backups")
        doUnmount()
        browseToFolder(source.name, BACKUP_REPO)
        val jobDrive = BackupMounts.driveIdFor(job.id)
        val mountDeadline = android.os.SystemClock.uptimeMillis() + 60_000L
        while (OtgMasterState.mountedDrives.none { it.id == jobDrive } && android.os.SystemClock.uptimeMillis() < mountDeadline) {
            android.os.SystemClock.sleep(200)
        }
        if (OtgMasterState.mountedDrives.none { it.id == jobDrive }) captureScreen("backup_job_mount_failed")
        assertTrue("choosing the job's folder did not mount it with its saved password", OtgMasterState.mountedDrives.any { it.id == jobDrive })
        assertNull("it asked for the password a job has saved", device.findObject(By.desc("backups_browse_password")))
        returnToMainScreen()

        // ── Pulling the drive takes the backup with it ───────────────────────
        val key = source.sourceDeviceName
        assertNotNull("the plain drive has no USB device name", key)
        requestUsb("unplug")
        val detach = OtgMasterState.detachRequest
        assertNotNull("no detach handler installed — is MainActivity running?", detach)
        detach!!(key!!)
        val deadline = android.os.SystemClock.uptimeMillis() + UNMOUNT_WAIT_MS
        while (OtgMasterState.mountedDrives.isNotEmpty() && android.os.SystemClock.uptimeMillis() < deadline) {
            android.os.SystemClock.sleep(200)
        }
        if (OtgMasterState.mountedDrives.isNotEmpty()) captureScreen("backup_outlived_drive")
        assertTrue("still mounted after the drive was pulled: ${OtgMasterState.mountedDrives.map { it.name }}",
            OtgMasterState.mountedDrives.isEmpty())

        openBackupsTab("backups_open_schedules")
        val message = device.wait(Until.findObject(By.text(context.getString(R.string.backups_create_no_devices))), timeout)
        if (message == null) captureScreen("backup_no_devices_not_shown")
        assertNotNull("the no-devices message is not shown with nothing mounted", message)
        val disabled = device.wait(Until.findObject(By.desc("backups_create_schedule")), timeout)
        assertNotNull("Create reminder missing with nothing mounted", disabled)
        disabled!!.click()
        android.os.SystemClock.sleep(1500)
        assertNull("Create reminder opened its form with nothing mounted", device.findObject(By.desc(BACKUP_DIALOG_FIELD)))

        jobs.delete(job.id)
        BackupSecrets(context).forget(job.id)
        requestUsb("replug")
    }

    // ═══ Backup flows ═══════════════════════════════════════════════════════════════════
    // testdata/backup_<flow>, made by scripts/make_backup_run_fixtures.py; docs/TEST_DATA.md §6g.
    // Time is moved by changing a job's lastSuccessAt and passing `now`, so nothing in the app
    // needs a test clock. backup_delayed_start also waits out a real 5 minutes.

    private val bkContext: Context get() = ApplicationProvider.getApplicationContext()
    private val bkStore: JobStore get() = JobStore.get(bkContext)
    /** Through a real shell: executeShellCommand has no quoting or redirects, and fails silently. */
    private fun bkShell(cmd: String): String {
        val pipes = InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommandRw("sh")
        android.os.ParcelFileDescriptor.AutoCloseOutputStream(pipes[1]).use { it.write((cmd + "\n").toByteArray()) }
        return android.os.ParcelFileDescriptor.AutoCloseInputStream(pipes[0]).use { it.readBytes().toString(Charsets.UTF_8) }
    }

    private fun bkPhoneSha(path: String): String {
        val sha = bkShell("sha256sum '$path'").substringBefore(' ').trim()
        assertEquals("no hash for $path", 64, sha.length)
        return sha
    }

    private fun bkHold(): java.util.concurrent.CountDownLatch =
        java.util.concurrent.CountDownLatch(1).also { BackupExecutor.testHold = it }

    private fun bkRelease() {
        BackupExecutor.testHold?.countDown()
        BackupExecutor.testHold = null
    }

    private fun bkReset() {
        bkStore.jobs.value.forEach { j ->
            BackupScheduler.forget(bkContext, j.id)
            BackupSecrets(bkContext).forget(j.id)
            bkStore.delete(j.id)
        }
        BackupPrompts.answered(); BackupPrompts.showProgress(null); BackupPrompts.askRecreate(null)
        BackupMounts.testDelayMs = 0
        BackupExecutor.testProgressDelayMs = 0
        bkRelease()
    }

    private fun runBackupFlow(flow: String) {
        val args = InstrumentationRegistry.getArguments()
        val password = args.getString("password", "")
        val pim = args.getString("pim", "")
        val testCase = args.getString("testCase", "")
        device.executeShellCommand("pm grant app.fayaz.otgmaster android.permission.POST_NOTIFICATIONS")
        bkReset()
        bkShell("rm -rf $PHONE")
        bkShell("mkdir -p $PHONE/targets $PHONE/restored")
        bkShell("cp -r /data/local/tmp/testdata/$testCase/phone_src $PHONE/src")
        android.os.SystemClock.sleep(1000)
        val drive = bkMount(password, pim, testCase)
        try {
            when (flow) {
                "run_full" -> bkFlowRun(drive, testCase, full = true)
                "run_core" -> bkFlowRun(drive, testCase, full = false)
                "due_prompt" -> bkFlowDuePrompt(drive)
                "delayed_start" -> bkFlowDelayedStart(drive)
                "due_mounted" -> bkFlowDueMounted(drive)
                "reminder" -> bkFlowReminder(drive)
                "target_checks" -> bkFlowTargetChecks(drive)
                "progress_dialog" -> bkFlowProgressDialog(drive)
                "mount_progress" -> bkFlowMountProgress(drive)
                "schedule_status" -> bkFlowScheduleStatus(drive)
                "remove_schedule" -> bkFlowRemoveSchedule(drive)
                "manual_history" -> bkFlowManualHistory(drive)
                "show_password" -> bkFlowShowPassword(drive)
                "recover_access" -> bkFlowRecoverAccess(drive)
                "verify_damaged" -> bkFlowVerifyDamaged(drive)
                "interrupted" -> bkFlowInterrupted(drive)
                "encrypt_form" -> bkFlowEncryptForm()
                "backups_disabled" -> bkFlowBackupsDisabled(drive)
                "eject_not_offered" -> bkFlowEjectNotOffered(drive)
                "eject_backup_running" -> bkFlowEjectBackupRunning(drive)
                else -> fail("unknown backup flow $flow")
            }
        } catch (t: Throwable) {
            captureScreen("backup_${flow}_failed")
            throw t
        } finally {
            device.executeShellCommand("cmd statusbar collapse")
        }
        returnToMainScreen()
        bkReset()
        bkShell("rm -rf $PHONE")
        // The host reads the drive next: everything has to be on it.
        OtgMasterState.unmountAllRequest?.invoke()
        val end = android.os.SystemClock.uptimeMillis() + 60_000L
        while (OtgMasterState.mountedDrives.isNotEmpty() && android.os.SystemClock.uptimeMillis() < end) android.os.SystemClock.sleep(200)
        requestUsb("replug")
    }

    /** Mounts the case's drive: by itself if plain, with its password if not. */
    private fun bkMount(password: String, pim: String, testCase: String): MountedDrive {
        android.os.SystemClock.sleep(1000)
        findScanButton()!!.click()
        device.wait(Until.findObject(By.text(java.util.regex.Pattern.compile("(?i)OK|ALLOW"))), 5000L)?.click()
        val plain = InstrumentationRegistry.getArguments().getString("plain_mount", "false") == "true"
        if (plain) { if (waitForMounted(60000L) == null) captureScreen("backup_no_drive") }
        else assertTrue("the encrypted drive did not mount", doMount(password, pim, testCase, clearFields = false))
        val d = OtgMasterState.mountedDrives.firstOrNull()
        assertNotNull("the drive did not mount", d)
        android.os.SystemClock.sleep(1000)
        closeSlider()
        return d!!
    }

    private fun bkTree(drive: MountedDrive, folder: String): android.net.Uri =
        DocumentsContract.buildTreeDocumentUri(AUTHORITY, "${drive.id}:/$folder")

    /** A job on [drive], made directly: its own provider needs no grant, so no picker. */
    private fun bkJob(drive: MountedDrive, name: String, source: String = "drive_src", target: String = "",
                      type: JobType = JobType.INCREMENTAL, interval: Int = 24, agoHours: Long? = 25): BackupJob {
        val end = { uri: android.net.Uri -> Endpoint(uri.toString(), drive.name, VolumeInfo.kindOf(uri), VolumeInfo.capture(bkContext, uri, withFreeSpace = false)) }
        val job = BackupJob(name = name, type = type, source = end(bkTree(drive, source)), destination = end(bkTree(drive, target)),
            destinationFolder = name, schedule = Schedule(intervalHours = interval, autoRun = interval > 0, remindAfterHours = 0),
            retention = if (type == JobType.INCREMENTAL) Retention(keepLast = 3) else Retention(),
            lastSuccessAt = agoHours?.let { System.currentTimeMillis() - it * BackupJob.HOUR })
        if (type == JobType.INCREMENTAL) BackupSecrets(bkContext).setPassword(job.id, BK_PASSWORD)
        bkStore.upsert(job)
        return job
    }

    private fun bkAwait(jobId: String, what: String, ms: Long = 300_000L): RunRecord {
        val end = android.os.SystemClock.uptimeMillis() + ms
        android.os.SystemClock.sleep(300)
        while (BackupExecutor.isBusy(jobId) && android.os.SystemClock.uptimeMillis() < end) android.os.SystemClock.sleep(250)
        assertFalse("$what still running after ${ms / 1000} s", BackupExecutor.isBusy(jobId))
        val run = bkStore.history(jobId).lastOrNull()
        assertNotNull("$what left no history", run)
        return run!!
    }

    private fun bkRunNow(job: BackupJob, what: String = "a backup", op: BackupExecutor.Op = BackupExecutor.Op.Backup("manual")): RunRecord {
        assertTrue("$what did not start", BackupExecutor.start(bkContext, job.id, op))
        return bkAwait(job.id, what)
    }

    private fun bkExpectSuccess(run: RunRecord, what: String) {
        assertEquals("$what: ${run.message}; ${run.errors}", RunStatus.SUCCESS, run.status)
    }

    private fun bkTitles(): List<String> = bkContext.getSystemService(android.app.NotificationManager::class.java)!!
        .activeNotifications.map { it.notification.extras.getCharSequence(android.app.Notification.EXTRA_TITLE)?.toString().orEmpty() }

    private fun bkText(id: Int, vararg args: Any): String = bkContext.getString(id, *args)

    private fun bkAssertDesc(desc: String, msg: String, ms: Long = 20000L) {
        val o = device.wait(Until.findObject(By.desc(desc)), ms)
        if (o == null) captureScreen("missing_$desc")
        assertNotNull(msg, o)
    }

    private fun bkAssertText(text: String, msg: String, ms: Long = 20000L) {
        val o = device.wait(Until.findObject(By.text(text)), ms)
        if (o == null) captureScreen("missing_text")
        assertNotNull("$msg (looking for: $text)", o)
    }

    private fun bkAssertGone(desc: String, msg: String, ms: Long = 20000L) {
        if (!device.wait(Until.gone(By.desc(desc)), ms)) captureScreen("not_gone_$desc")
        assertNull(msg, device.findObject(By.desc(desc)))
    }

    /** Opens Schedules or another tab fresh, from the main screen. */
    private fun bkOpenTab(tab: String) {
        returnToMainScreen()
        openBackupsTab("backups_open_schedules")
        assertTrue("$tab missing", clickByDesc(tab))
    }

    /** Finds [selector] on the current tab, scrolling the list down, then back up. */
    private fun bkScrollTo(selector: androidx.test.uiautomator.BySelector): androidx.test.uiautomator.UiObject2? {
        device.wait(Until.findObject(selector), 1500L)?.let { return it }
        for (direction in listOf(androidx.test.uiautomator.Direction.DOWN, androidx.test.uiautomator.Direction.UP)) {
            for (step in 1..12) {
                val list = device.findObject(By.scrollable(true).pkg("app.fayaz.otgmaster")) ?: break
                // The rows redraw each second, which can leave the list's node stale.
                val more = try { list.scroll(direction, 0.6f, 1500) } catch (_: androidx.test.uiautomator.StaleObjectException) { true }
                device.waitForIdle(500)
                // The accessibility tree this reads can lag the screen: seen with a reason that had
                // gone from the screenshot and stayed in the tree.
                InstrumentationRegistry.getInstrumentation().uiAutomation.clearCache()
                device.wait(Until.findObject(selector), 1000L)?.let { return it }
                if (!more) break
            }
            captureScreen("scrolled_${direction.name.lowercase()}")
        }
        return null
    }

    /** Taps a job's row action, scrolling to it first. */
    private fun bkRowClick(desc: String) {
        BK_IN_MORE.firstOrNull { desc.startsWith("backup_${it}_") }?.let { action ->
            bkMoreClick(desc.removePrefix("backup_${action}_"), desc)
            return
        }
        for (attempt in 1..3) {
            val o = bkScrollTo(By.desc(desc))
            if (o == null) captureScreen("missing_$desc")
            assertNotNull("$desc missing", o)
            try {
                o!!.click()
                return
            } catch (e: androidx.test.uiautomator.StaleObjectException) {
                if (attempt == 3) throw e
            }
        }
    }

    /** In Create's form: picks how often the backup comes due. */
    private fun bkPickFrequency(hours: Int) {
        clickInDialog("backup_interval")
        val option = device.wait(Until.findObject(By.desc("backup_interval_$hours")), timeout)
        if (option == null) captureScreen("no_frequency_$hours")
        assertNotNull("no frequency of $hours hours", option)
        option!!.click()
        assertTrue("the frequency's menu stayed", device.wait(Until.gone(By.desc("backup_interval_$hours")), timeout))
    }

    /** Opens the More actions menu of the backup [name]'s card. */
    private fun bkOpenMore(name: String): Boolean {
        val more = bkScrollTo(By.desc("backup_more_$name")) ?: return false
        return try { more.click(); true } catch (e: androidx.test.uiautomator.StaleObjectException) { false }
    }

    /**
     * Taps [desc] in the backup [name]'s More actions. The menu lists only what can be done now,
     * so a run just ending can leave the item, or the whole menu, a moment behind: look again.
     */
    private fun bkMoreClick(name: String, desc: String) {
        for (attempt in 1..5) {
            if (bkOpenMore(name)) {
                val item = device.wait(Until.findObject(By.desc(desc)), 5000L)
                try {
                    if (item != null) { item.click(); return }
                } catch (e: androidx.test.uiautomator.StaleObjectException) {
                    // Redrawn under the tap: the menu closes or not, and the check below sorts that out.
                }
                // Back only if the menu is up: otherwise it would leave the screen.
                if (BK_IN_MORE.any { device.hasObject(By.desc("backup_${it}_$name")) }) device.pressBack()
            }
            android.os.SystemClock.sleep(2000)
        }
        captureScreen("missing_$desc")
        fail("$desc not in the More actions of $name")
    }

    /** Unmounts the mounted backup [id], titled [name]: an encrypted drive's card has Unmount too. */
    private fun bkUnmountBackup(id: String, name: String) {
        returnToMainScreen()
        openSlider("mounted_tab_backups")
        android.os.SystemClock.sleep(UNMOUNT_SETTLE_MS)
        val title = bkScrollTo(By.textContains(name))
        assertNotNull("no card for the backup $name", title)
        var button: androidx.test.uiautomator.UiObject2? = null
        for (attempt in 1..4) {
            // The screen can show the button while the accessibility tree still lacks it.
            InstrumentationRegistry.getInstrumentation().uiAutomation.clearCache()
            val top = device.findObject(By.textContains(name))?.visibleBounds?.bottom ?: 0
            button = device.findObjects(By.descContains("unmount_button")).firstOrNull { it.visibleBounds.top > top }
            if (button != null) break
            device.swipe(device.displayWidth / 2, device.displayHeight * 3 / 4, device.displayWidth / 2, device.displayHeight / 2, 25)
            android.os.SystemClock.sleep(500)
        }
        if (button == null) captureScreen("no_backup_unmount")
        assertNotNull("no Unmount on the backup's card", button)
        button!!.click()
        val end = android.os.SystemClock.uptimeMillis() + UNMOUNT_WAIT_MS
        while (OtgMasterState.mountedDrives.any { it.id == id } && android.os.SystemClock.uptimeMillis() < end) android.os.SystemClock.sleep(200)
        assertFalse("the backup is still mounted after Unmount", OtgMasterState.mountedDrives.any { it.id == id })
    }

    /** With the Files picker up: goes to [root] if given, walks [path], and chooses that folder. */
    private fun bkPick(root: String?, vararg path: String) {
        val documentsUi = "com.google.android.documentsui"
        assertTrue("the Files picker did not open", device.wait(Until.hasObject(By.pkg(documentsUi)), 30000L))
        android.os.SystemClock.sleep(1000)
        val first = path.firstOrNull()
        var row: androidx.test.uiautomator.UiObject2? = null
        for (attempt in 1..3) {
            if (root != null) {
                (device.findObject(By.descContains("Show roots")) ?: device.findObject(By.descContains("Open navigation drawer")))?.click()
                if (device.wait(Until.hasObject(By.text(root)), 20000L) != true) captureScreen("picker_no_root")
                android.os.SystemClock.sleep(1000)
                device.findObject(By.text(root))?.click()
            }
            if (first == null) {
                // The drive's top level itself. A tap on the drawer while it slides in only closes
                // it, leaving the picker on the phone's storage, which has Alarms and the drive not.
                android.os.SystemClock.sleep(2000)
                if (root == null || device.findObject(By.text("Alarms")) == null) break
                if (attempt == 3) { captureScreen("picker_no_drive_top"); fail("the picker did not go to $root") }
                continue
            }
            row = device.wait(Until.findObject(By.text(first)), 8000L)
            if (row != null) break
        }
        for ((i, name) in path.withIndex()) {
            val o = if (i == 0) row else device.wait(Until.findObject(By.text(name)), 15000L)
            if (o == null) captureScreen("picker_no_$name")
            assertNotNull("$name not in the Files picker", o)
            o!!.click()
            android.os.SystemClock.sleep(700)
        }
        // Tapped again if the confirmation does not come: the first tap can land on a moving drawer.
        var allow: androidx.test.uiautomator.UiObject2? = null
        for (attempt in 1..3) {
            val use = device.wait(Until.findObject(By.text(java.util.regex.Pattern.compile("(?i)use this folder"))), 20000L)
            if (use == null) captureScreen("picker_no_use")
            assertNotNull("USE THIS FOLDER missing", use)
            use!!.click()
            allow = device.wait(Until.findObject(By.text(java.util.regex.Pattern.compile("(?i)^allow$"))), 8000L)
            if (allow != null) break
        }
        if (allow == null) captureScreen("picker_no_allow")
        assertNotNull("ALLOW missing", allow)
        allow!!.click()
        assertTrue("the Files picker did not return to the app", device.wait(Until.hasObject(By.pkg("app.fayaz.otgmaster")), timeout))
        android.os.SystemClock.sleep(800)
    }

    private val bkPhoneRoot: String get() = android.os.Build.MODEL

    data class BkEnd(val root: String?, val path: List<String>)

    /** Fills the create form, which is already open, up to the Create button. */
    private fun bkFillForm(source: BkEnd, target: BkEnd, name: String, incremental: Boolean, password: String = BK_PASSWORD, interval: Int? = null) {
        clickInDialog("backup_source_location")
        bkPick(source.root, *source.path.toTypedArray())
        clickInDialog("backup_target_location")
        bkPick(target.root, *target.path.toTypedArray())
        typeInto("backup_name", name)
        clickInDialog(if (incremental) "backup_type_incremental" else "backup_type_plain")
        interval?.let { bkPickFrequency(it) }
        if (incremental) {
            bkTypePassword("backup_password", password)
            bkTypePassword("backup_password_confirm", password)
            clickInDialog("backup_password_saved")
            android.os.SystemClock.sleep(800)
        }
    }

    /** Finds [desc] in the form, which waits for it to stop moving, and taps it. */
    private fun bkTapSettled(desc: String) {
        val found = findInDialog(desc)
        assertNotNull("$desc not in the form", found)
        found!!.click()
    }

    /** The form scrolls, and [findInDialog] only looks downward. */
    private fun bkFormToTop() {
        repeat(5) {
            val h = device.displayHeight
            device.swipe(device.displayWidth / 2, h / 3, device.displayWidth / 2, h * 3 / 4, 25)
            device.waitForIdle()
        }
        android.os.SystemClock.sleep(500)
    }

    private fun bkTypePassword(desc: String, text: String) {
        val field = findInDialog(desc)
        assertNotNull("$desc not found in the dialog", field)
        editTextOf(field!!).text = text
        android.os.SystemClock.sleep(300)
    }

    /** Create, tapped again if nothing comes: a tap right after the tick can land before the button enables. */
    private fun bkTapCreate(expect: String) {
        for (attempt in 1..3) {
            clickInDialog("backup_create")
            if (device.wait(Until.findObject(By.desc(expect)), 15000L) != null) return
            if (device.findObject(By.desc("backup_name")) == null) return
        }
    }

    private fun bkOpenCreate() {
        bkOpenTab("backups_tab_schedules")
        assertTrue("Create reminder missing", clickByDesc("backups_create_schedule"))
        assertNotNull("the form did not open", device.wait(Until.findObject(By.desc("backup_name")), timeout))
    }

    private fun bkCreate(source: BkEnd, target: BkEnd, name: String, incremental: Boolean, password: String = BK_PASSWORD): BackupJob {
        bkOpenCreate()
        bkFillForm(source, target, name, incremental, password)
        bkTapCreate("backup_progress_title")
        bkAssertDesc("backup_progress_title", "Create did not open the progress dialog", 60000L)
        val job = bkStore.jobs.value.firstOrNull { it.name == name }
        assertNotNull("Create made no job named $name", job)
        return job!!
    }

    private fun bkCloseProgress() {
        bkAssertDesc("backup_progress_close", "the run did not finish in its dialog", 300_000L)
        assertTrue("Close missing", clickByDesc("backup_progress_close"))
        bkAssertGone("backup_progress_title", "the progress dialog did not close")
    }

    // ── Back up and restore through the real screens, in every direction ─────────────────

    private fun bkFlowRun(drive: MountedDrive, testCase: String, full: Boolean) {
        val expected = bkShell("cat /data/local/tmp/testdata/$testCase/phone_expected.txt").lines().filter { it.isNotBlank() }.map { it.split('\t') }
        assertTrue("phone_expected.txt is empty", expected.isNotEmpty())
        val phoneSrc = BkEnd(bkPhoneRoot, listOf("Download", "otg_e2e", "src"))
        val driveTop = BkEnd(drive.name, emptyList())

        // Incremental, phone to drive: the typed password, the first run, its dialog.
        val inc = bkCreate(phoneSrc, driveTop, "E2E_inc", incremental = true)
        bkCloseProgress()
        var run = bkStore.history(inc.id).last()
        bkExpectSuccess(run, "the first incremental run")
        assertEquals("files in the first run", expected.size.toLong(), run.stats?.files)
        assertEquals("the backup's folder holds a repository", true, documentChildren(bkContext, "${drive.id}:/E2E_inc").containsKey("config"))

        // Back up now from the row: nothing changed, so nothing new is stored.
        bkOpenTab("backups_tab_schedules")
        bkRowClick("backup_run_now_E2E_inc")
        bkCloseProgress()
        run = bkStore.history(inc.id).last()
        bkExpectSuccess(run, "the second run")
        assertEquals("unchanged files stored again", 0L, run.stats?.filesNew)

        // One file changes: only that is new.
        bkShell("echo changed >> '$PHONE/src/${expected.first()[1]}'")
        android.os.SystemClock.sleep(1100)
        bkRowClick("backup_run_now_E2E_inc")
        bkCloseProgress()
        run = bkStore.history(inc.id).last()
        bkExpectSuccess(run, "the run after a change")
        // One other file may be read again: the phone's storage can report a modified time a few
        // ms off from the one it gave before (seen: .178 then .182). That costs a read, and what
        // proves nothing was stored twice is how little was added.
        val stats = run.stats!!
        assertEquals("a changed file counted as new", 0L, stats.filesNew)
        assertTrue("files read again: ${stats.filesUnchanged} of ${expected.size} unchanged", stats.filesUnchanged >= expected.size - 2L)
        assertTrue("stored again: ${stats.bytesAdded} bytes added for one small change", stats.bytesAdded < 64 * 1024)
        assertEquals("snapshots", 3, bkStore.snapshots(inc.id).size)

        // Verify reads every blob back.
        bkRowClick("backup_verify_E2E_inc")
        bkAssertDesc("backup_progress_title", "Verify did not open the progress dialog")
        bkAssertDesc("backup_progress_result", "Verify showed no result", 300_000L)
        bkCloseProgress()
        val verified = bkStore.history(inc.id).last()
        bkExpectSuccess(verified, "Verify")
        assertTrue("Verify did not read the data: ${verified.message}", verified.message.contains("read and verified"))

        // Restore newest to a folder on the phone, and compare by hash outside the app.
        bkRowClick("backup_restore_E2E_inc")
        bkPick(bkPhoneRoot, "Download", "otg_e2e", "restored")
        bkCloseProgress()
        bkExpectSuccess(bkStore.history(inc.id).last(), "the restore")
        val restoredDir = bkShell("ls $PHONE/restored").lines().first { it.isNotBlank() }.trim()
        for (row in expected) {
            val path = row[1]
            assertEquals("restored $path", bkPhoneSha("$PHONE/src/$path"), bkPhoneSha("$PHONE/restored/$restoredDir/$path"))
        }

        // Mount it: the repository this app wrote, read through the provider, with its saved password.
        bkMountFromPicker(drive.name, "E2E_inc")
        val mountId = BackupMounts.driveIdFor(inc.id)
        val mountDeadline = android.os.SystemClock.uptimeMillis() + 60_000L
        while (OtgMasterState.mountedDrives.none { it.id == mountId } && android.os.SystemClock.uptimeMillis() < mountDeadline) android.os.SystemClock.sleep(200)
        assertTrue("Mount did not mount the backup", OtgMasterState.mountedDrives.any { it.id == mountId })
        val snaps = documentChildren(bkContext, "$mountId:/")
        assertEquals("one folder per snapshot: ${snaps.keys}", 3, snaps.size)
        bkUnmountBackup(mountId, "E2E_inc")

        // Plain, phone to drive.
        val plain = bkCreate(phoneSrc, driveTop, "E2E_plain", incremental = false)
        bkCloseProgress()
        bkExpectSuccess(bkStore.history(plain.id).last(), "the plain run")
        val copies = documentChildren(bkContext, "${drive.id}:/E2E_plain")
        assertEquals("dated copies: ${copies.keys}", 1, copies.size)
        bkRowClick("backup_verify_E2E_plain")
        bkCloseProgress()
        bkExpectSuccess(bkStore.history(plain.id).last(), "Verify of a plain copy")
        if (!full) return

        // Run again: a second dated folder, the first untouched.
        android.os.SystemClock.sleep(1100)
        bkRowClick("backup_run_now_E2E_plain")
        bkCloseProgress()
        assertEquals("dated copies after a second run", 2, documentChildren(bkContext, "${drive.id}:/E2E_plain").size)

        // Plain, drive to phone; compare what landed on the phone outside the app.
        val toPhone = bkCreate(BkEnd(drive.name, listOf("drive_src")), BkEnd(bkPhoneRoot, listOf("Download", "otg_e2e", "targets")), "E2E_to_phone", incremental = false)
        bkCloseProgress()
        bkExpectSuccess(bkStore.history(toPhone.id).last(), "the drive-to-phone copy")
        val dated = bkShell("ls $PHONE/targets/E2E_to_phone").lines().first { it.isNotBlank() }.trim()
        val driveExpected = bkShell("cat /data/local/tmp/testdata/$testCase/drive_expected.txt").lines().filter { it.isNotBlank() }.map { it.split('\t') }
        for (row in driveExpected) assertEquals("copied ${row[1]}", row[0], bkPhoneSha("$PHONE/targets/E2E_to_phone/$dated/${row[1]}"))

        // Incremental, drive to phone.
        val incPhone = bkCreate(BkEnd(drive.name, listOf("drive_src")), BkEnd(bkPhoneRoot, listOf("Download", "otg_e2e", "targets")), "E2E_inc_phone", incremental = true)
        bkCloseProgress()
        bkExpectSuccess(bkStore.history(incPhone.id).last(), "the drive-to-phone backup")
        assertTrue("no repository on the phone", bkShell("ls $PHONE/targets/E2E_inc_phone").contains("config"))

        // Keep rules: only this job's old snapshots go, and what they alone used.
        // With something new to store as well: that run's own new tree once read as damage to its prune.
        bkStore.update(inc.id) { it.copy(retention = Retention(keepLast = 2)) }
        bkShell("echo new-for-this-run > $PHONE/src/new_with_keep_rules.txt")
        android.os.SystemClock.sleep(1100)
        bkExpectSuccess(bkRunNow(inc, "the run with keep rules"), "the run with keep rules")
        assertEquals("snapshots after the keep rules", 2, bkStore.snapshots(inc.id).size)
        bkShell("rm $PHONE/src/new_with_keep_rules.txt")
        bkExpectSuccess(bkRunNow(inc, "Verify after pruning", BackupExecutor.Op.Check(true)), "Verify after pruning")

        // A folder inside the source holds the target: the backup does not copy itself.
        val selfRun = bkRunNow(bkJob(drive, "E2E_self", source = "self_src", target = "self_src"), "a target inside its source")
        bkExpectSuccess(selfRun, "a target inside its source")
        assertEquals("the repository was backed up into itself", 1L, selfRun.stats?.files)

        // An empty source writes nothing, and says so.
        val empty = bkJob(drive, "E2E_empty", source = "empty")
        val emptyRun = bkRunNow(empty, "a backup of an empty folder")
        assertEquals("an empty source: ${emptyRun.message}", RunStatus.FAILED, emptyRun.status)
        assertTrue(emptyRun.message, emptyRun.message.contains("listed no files"))
        assertNull("an empty source made a folder", documentChildren(bkContext, "${drive.id}:/")["E2E_empty"])

        // Cancel leaves no snapshot and a repository that still verifies.
        val before = bkStore.snapshots(inc.id).size
        bkShell("dd if=/dev/urandom of=$PHONE/src/cancel.bin bs=1M count=8 2>/dev/null")
        assertTrue(BackupExecutor.start(bkContext, inc.id, BackupExecutor.Op.Backup("manual")))
        BackupExecutor.cancel(inc.id)
        val cancelled = bkAwait(inc.id, "a cancelled run")
        assertEquals("the cancelled run: ${cancelled.message}", RunStatus.CANCELLED, cancelled.status)
        assertEquals("a cancelled run made a snapshot", before, bkStore.snapshots(inc.id).size)
        bkExpectSuccess(bkRunNow(inc, "Verify after a cancel", BackupExecutor.Op.Check(true)), "Verify after a cancel")
        bkShell("rm -f $PHONE/src/cancel.bin")

        // Low space: too big is refused; nearly full asks, and both answers work.
        val free = VolumeInfo.capture(bkContext, bkTree(drive, ""))[VolumeInfo.FREE]!!.toLong()
        bkShell("mkdir -p $PHONE/big")
        bkShell("dd if=/dev/zero of=$PHONE/big/big.bin bs=1M count=${(free shr 20) + 8} 2>/dev/null")
        val big = bkCreate(BkEnd(bkPhoneRoot, listOf("Download", "otg_e2e", "big")), driveTop, "E2E_big", incremental = true)
        bkCloseProgress()
        val refused = bkStore.history(big.id).last()
        assertEquals("too big for the drive: ${refused.message}", RunStatus.FAILED, refused.status)
        assertTrue(refused.message, refused.message.contains("Not enough space"))
        bkShell("rm -f $PHONE/big/big.bin")
        bkShell("dd if=/dev/zero of=$PHONE/big/big.bin bs=1M count=${(free shr 20) - 2} 2>/dev/null")
        assertTrue(BackupExecutor.start(bkContext, big.id, BackupExecutor.Op.Backup("manual")))
        val asked = bkAwait(big.id, "the low-space run")
        assertEquals("nearly full: ${asked.message}", RunStatus.NEEDS_CONFIRMATION, asked.status)
        val lowTitle = bkText(R.string.backup_low_space_title, "E2E_big")
        assertTrue("no low-space notification: ${bkTitles()}", bkTitles().contains(lowTitle))
        bkContext.sendBroadcast(Intent(bkContext, app.fayaz.otgmaster.backup.BackupActionReceiver::class.java)
            .setAction(app.fayaz.otgmaster.backup.BackupActionReceiver.ACTION_SKIP).putExtra(BackupNotifications.EXTRA_JOB_ID, big.id))
        android.os.SystemClock.sleep(1500)
        assertNotNull("Not now did not snooze", bkStore.job(big.id)!!.snoozedUntil)
        assertFalse("Not now left the question up", bkTitles().contains(lowTitle))
        bkContext.sendBroadcast(Intent(bkContext, app.fayaz.otgmaster.backup.BackupActionReceiver::class.java)
            .setAction(app.fayaz.otgmaster.backup.BackupActionReceiver.ACTION_PROCEED).putExtra(BackupNotifications.EXTRA_JOB_ID, big.id))
        android.os.SystemClock.sleep(1000)
        bkExpectSuccess(bkAwait(big.id, "the run after Proceed", 600_000L), "the run after Proceed")

        // Remove from the row: the backups stay on the drive.
        bkOpenTab("backups_tab_schedules")
        bkRowClick("backup_remove_E2E_plain")
        bkAssertDesc("backup_remove_confirm", "no confirmation for Remove")
        assertTrue(clickByDesc("backup_remove_confirm"))
        android.os.SystemClock.sleep(800)
        assertNull("the job survived Remove", bkStore.jobs.value.firstOrNull { it.name == "E2E_plain" })
        assertEquals("Remove touched the copies", 2, documentChildren(bkContext, "${drive.id}:/E2E_plain").size)
    }

    // ── A due backup when the drive mounts ──────────────────────────────────────────────

    /** Nothing popped up: the bell shows [count], and the dialog waits to be asked for. */
    private fun bkAssertAsksQuietly(count: Int, why: String) {
        closeSlider()
        var bell: androidx.test.uiautomator.UiObject2? = null
        for (attempt in 1..20) {
            // The tree can lag the screen: seen drawing 1 while it said 0 for 20 s.
            InstrumentationRegistry.getInstrumentation().uiAutomation.clearCache()
            bell = device.wait(Until.findObject(By.desc(bkText(R.string.cd_backups_due, count))), 1000L)
            if (bell != null) break
        }
        if (bell == null) captureScreen("bell_not_$count")
        assertNotNull("$why: the bell does not show $count", bell)
        assertNull("$why: a dialog opened by itself", device.findObject(By.desc("backup_due_title")))
    }

    /** From the bell's list: the dialog for this one backup. */
    private fun bkOpenDueFromBell(name: String) {
        closeSlider()
        InstrumentationRegistry.getInstrumentation().uiAutomation.clearCache()
        val bell = device.wait(Until.findObject(By.descStartsWith("Backups due:")), timeout)
        assertNotNull("no bell on the top bar", bell)
        bell!!.click()
        bkAssertDesc("backup_due_item_$name", "$name is not in the bell's list")
        assertTrue(clickByDesc("backup_due_item_$name"))
        bkAssertDesc("backup_due_title", "choosing $name opened no dialog")
        bkAssertText(name, "the dialog does not name $name")
    }

    private fun bkFlowDuePrompt(drive: MountedDrive) {
        val job = bkJob(drive, "E2E_due")
        val title = bkText(R.string.backup_due_title, BackupScheduler.driveNames(job))
        returnToMainScreen()

        // Asks, starts nothing, and interrupts nothing: the notification and the bell say so.
        assertTrue("attach started a backup unasked", BackupScheduler.evaluate(bkContext, "attach").isEmpty())
        android.os.SystemClock.sleep(1500)
        bkAssertAsksQuietly(1, "a mount with a backup due")
        assertTrue("no notification: ${bkTitles()}", bkTitles().contains(title))
        bkOpenDueFromBell("E2E_due")
        bkAssertText(title, "the dialog's title")
        bkAssertText(bkText(R.string.backup_due_question), "the dialog's question")
        assertFalse("a backup started while asking", BackupExecutor.isBusy(job.id))

        // Not now snoozes a day; a second partition mounting does not ask again.
        assertTrue(clickByDesc("backup_due_not_now"))
        bkAssertGone("backup_due_title", "Not now left the dialog up")
        val snoozed = bkStore.job(job.id)!!.snoozedUntil
        assertNotNull("Not now did not snooze", snoozed)
        assertTrue("snoozed for about a day", kotlin.math.abs(snoozed!! - System.currentTimeMillis() - 24 * BackupJob.HOUR) < 60_000L)
        bkAssertAsksQuietly(0, "after Not now")
        device.findObject(By.descStartsWith("Backups due:")).click()
        bkAssertDesc("backup_due_none", "the empty list says nothing")
        device.pressBack()
        android.os.SystemClock.sleep(500)
        assertTrue(BackupScheduler.evaluate(bkContext, "attach").isEmpty())
        android.os.SystemClock.sleep(1500)
        assertNull("a snoozed backup asked again", device.findObject(By.desc("backup_due_title")))
        bkStore.update(job.id) { it.copy(snoozedUntil = null) }
        BackupScheduler.evaluate(bkContext, "attach")
        android.os.SystemClock.sleep(1500)
        assertNull("the same mount asked twice", device.findObject(By.desc("backup_due_title")))
        assertFalse("the same mount notified twice: ${bkTitles()}", bkTitles().contains(title))

        // Each delay: the time is set, the row says it, and nothing runs yet.
        for (minutes in BackupDelays.CHOICES_MINUTES) {
            BackupPrompts.ask(BackupPrompts.Due(BackupScheduler.driveNames(job), listOf(job.id)))
            bkAssertDesc("backup_due_later", "Later missing")
            android.os.SystemClock.sleep(2500) // past the reachability check that enables it
            assertTrue(clickByDesc("backup_due_later"))
            BackupDelays.CHOICES_MINUTES.forEach { bkAssertDesc("backup_later_$it", "no $it minute choice") }
            assertTrue(clickByDesc("backup_later_$minutes"))
            bkAssertGone("backup_due_title", "choosing a delay left the dialog up")
            val at = bkStore.job(job.id)!!.delayedStartAt
            assertNotNull("no delay was set for $minutes", at)
            assertTrue("$minutes minutes", kotlin.math.abs(at!! - System.currentTimeMillis() - minutes * 60_000L) < 15_000L)
            assertFalse("a delay started it at once", BackupExecutor.isBusy(job.id))
            bkOpenTab("backups_tab_schedules")
            val next = device.wait(Until.findObject(By.desc("backup_status_E2E_due")), timeout)
            assertNotNull("no status on the row", next)
            BackupDelays.clearFor(bkContext, job.id)
            returnToMainScreen()
        }

        // Start now: the real run, in its dialog.
        BackupPrompts.ask(BackupPrompts.Due(BackupScheduler.driveNames(job), listOf(job.id)))
        bkAssertDesc("backup_due_start", "Start now missing")
        android.os.SystemClock.sleep(2500)
        assertTrue(clickByDesc("backup_due_start"))
        bkAssertDesc("backup_progress_title", "Start now opened no progress dialog")
        bkCloseProgress()
        bkExpectSuccess(bkStore.history(job.id).last(), "Start now")
        assertFalse("just backed up, still due", bkStore.job(job.id)!!.isDue(System.currentTimeMillis()))

        // One end here and the other away: it asks, and says what it needs. The emulator's drive
        // has no serial, so matching a sibling partition by serial is a unit test and a device check.
        val sibling = job.copy(id = java.util.UUID.randomUUID().toString(), name = "E2E_sibling", destinationFolder = "E2E_sibling", lastSuccessAt = null,
            destination = job.destination.copy(uri = DocumentsContract.buildTreeDocumentUri(AUTHORITY, "feedface00:/").toString()))
        bkStore.upsert(sibling)
        BackupSecrets(bkContext).setPassword(sibling.id, BK_PASSWORD)
        assertTrue(BackupScheduler.evaluate(bkContext, "attach").isEmpty())
        android.os.SystemClock.sleep(1500)
        bkAssertAsksQuietly(1, "a backup with an end away")
        bkOpenDueFromBell("E2E_sibling")
        bkAssertDesc("backup_due_missing_E2E_sibling", "the missing other end is not named")
        android.os.SystemClock.sleep(2500)
        device.findObject(By.desc("backup_due_start"))?.click()
        android.os.SystemClock.sleep(1000)
        assertFalse("Start now ran with an end missing", BackupExecutor.isBusy(sibling.id))
        assertTrue(clickByDesc("backup_due_not_now"))
        bkAssertGone("backup_due_title", "Not now left the dialog up")
        bkStore.delete(sibling.id)

        // Not due, and manual: never asks. The whole path, with the real mount: it does.
        bkStore.update(job.id) { it.copy(lastSuccessAt = System.currentTimeMillis() - 25 * BackupJob.HOUR) }
        val manual = bkJob(drive, "E2E_manual", interval = 0)
        val fresh = bkJob(drive, "E2E_fresh", agoHours = 1)
        bkPullDrive(drive)
        bkReplugDrive()
        bkAwaitDueNotification(title, "the real mount did not ask")
        bkAssertAsksQuietly(1, "the real mount")
        device.findObject(By.descStartsWith("Backups due:")).click()
        bkAssertDesc("backup_due_item_E2E_due", "the due backup is not in the list")
        assertNull("a manual backup asked", device.findObject(By.desc("backup_due_item_E2E_manual")))
        assertNull("a backup not yet due asked", device.findObject(By.desc("backup_due_item_E2E_fresh")))
        assertTrue(clickByDesc("backup_due_item_E2E_due"))
        bkAssertDesc("backup_due_title", "choosing it opened no dialog")
        assertTrue(clickByDesc("backup_due_not_now"))
        assertNotNull(manual); assertNotNull(fresh)
    }

    private fun bkAwaitDueNotification(title: String, why: String) {
        val end = android.os.SystemClock.uptimeMillis() + 60_000L
        while (android.os.SystemClock.uptimeMillis() < end && !bkTitles().contains(title)) android.os.SystemClock.sleep(500)
        assertTrue("$why: ${bkTitles()}", bkTitles().contains(title))
    }

    private fun bkPullDrive(drive: MountedDrive) {
        val key = drive.sourceDeviceName
        assertNotNull("the drive has no USB device name", key)
        returnToMainScreen()
        requestUsb("unplug")
        val detach = OtgMasterState.detachRequest
        assertNotNull("no detach handler installed", detach)
        detach!!(key!!)
        val end = android.os.SystemClock.uptimeMillis() + UNMOUNT_WAIT_MS
        while (OtgMasterState.mountedDrives.isNotEmpty() && android.os.SystemClock.uptimeMillis() < end) android.os.SystemClock.sleep(200)
        assertTrue("still mounted after the drive was pulled", OtgMasterState.mountedDrives.isEmpty())
        android.os.SystemClock.sleep(1500) // the detach evaluation runs on its own thread
    }

    private fun bkReplugDrive(): MountedDrive {
        requestUsb("replug")
        android.os.SystemClock.sleep(3000)
        returnToMainScreen()
        if (OtgMasterState.mountedDrives.isEmpty()) {
            findScanButton()!!.click()
            device.wait(Until.findObject(By.text(java.util.regex.Pattern.compile("(?i)OK|ALLOW"))), 5000L)?.click()
        }
        val end = android.os.SystemClock.uptimeMillis() + 60_000L
        while (OtgMasterState.mountedDrives.isEmpty() && android.os.SystemClock.uptimeMillis() < end) android.os.SystemClock.sleep(200)
        val d = OtgMasterState.mountedDrives.firstOrNull()
        assertNotNull("the drive did not mount again", d)
        return d!!
    }

    // ── Later: the delay runs out, or the drive goes first ──────────────────────────────

    private fun bkFlowDelayedStart(drive: MountedDrive) {
        val job = bkJob(drive, "E2E_later")

        // A real five minutes, on the real alarm.
        val started = System.currentTimeMillis()
        BackupDelays.schedule(bkContext, job.id, 5)
        android.os.SystemClock.sleep(60_000L)
        assertFalse("it started before its time", BackupExecutor.isBusy(job.id))
        assertTrue("nothing has run yet", bkStore.history(job.id).isEmpty())
        val deadline = started + 8 * 60_000L
        while (bkStore.history(job.id).isEmpty() && System.currentTimeMillis() < deadline) android.os.SystemClock.sleep(1000)
        val run = bkAwait(job.id, "the delayed run")
        bkExpectSuccess(run, "the delayed run")
        assertEquals("what started it", "delayed", run.reason)
        assertTrue("it started early: ${run.startedAt - started} ms", run.startedAt - started >= 5 * 60_000L - 2000L)
        assertNull("the delay stayed set", bkStore.job(job.id)!!.delayedStartAt)

        // 15 minutes, with the time moved rather than waited for.
        bkStore.update(job.id) { it.copy(lastSuccessAt = System.currentTimeMillis() - 25 * BackupJob.HOUR) }
        BackupDelays.schedule(bkContext, job.id, 15)
        assertTrue("before its time", BackupScheduler.evaluate(bkContext, "periodic", System.currentTimeMillis() + 14 * 60_000L).isEmpty())
        assertEquals(listOf(job.id), BackupScheduler.evaluate(bkContext, "periodic", System.currentTimeMillis() + 16 * 60_000L))
        bkExpectSuccess(bkAwait(job.id, "the 15 minute run"), "the 15 minute run")

        // 30 minutes, and the drive goes while it waits: dropped, with the reason in the log.
        bkStore.update(job.id) { it.copy(lastSuccessAt = System.currentTimeMillis() - 25 * BackupJob.HOUR) }
        val runsBefore = bkStore.history(job.id).size
        BackupDelays.schedule(bkContext, job.id, 30)
        bkPullDrive(drive)
        assertNull("the delay outlived the drive", bkStore.job(job.id)!!.delayedStartAt)
        assertTrue("no log line says why", OtgMasterState.recentLogs().any { it.contains("E2E_later") && it.contains("did not start") })
        assertTrue("it ran with the drive gone", bkStore.history(job.id).size == runsBefore)

        // Plugged back: the old timer does not come back; it asks again.
        val again = bkReplugDrive()
        bkAwaitDueNotification(bkText(R.string.backup_due_title, BackupScheduler.driveNames(job)), "the replug did not ask")
        assertNull("the replug brought the timer back", bkStore.job(job.id)!!.delayedStartAt)
        bkOpenDueFromBell("E2E_later")
        assertTrue(clickByDesc("backup_due_not_now"))
        assertNotNull(again)

        // The time comes with the drive gone and the timer not yet dropped: it checks when it fires.
        bkStore.update(job.id) { it.copy(lastSuccessAt = System.currentTimeMillis() - 25 * BackupJob.HOUR, snoozedUntil = null,
            delayedStartAt = System.currentTimeMillis() - 1000L, destination = it.destination.copy(uri = DocumentsContract.buildTreeDocumentUri(AUTHORITY, "feedface00:/").toString())) }
        assertFalse("it fired with an end missing", BackupDelays.fire(bkContext, job.id))
        assertNull(bkStore.job(job.id)!!.delayedStartAt)
    }

    // ── Due with the drive already mounted ──────────────────────────────────────────────

    /**
     * The notification titled [title]: [label] is one of its actions, or the title for its body. The
     * body is tapped in the real shade. An action is fired directly, because grouped with the app's
     * mounted-drive notification this one stays collapsed and its arrow did not open it reliably.
     */
    private fun bkNotificationTap(title: String, label: String) {
        if (label == title) {
            // Settled first: a heads-up can show the title while the shade is still moving, and a tap
            // then falls through to whatever is behind it (seen: a debug button on the main screen).
            bkShell("cmd statusbar expand-notifications")
            android.os.SystemClock.sleep(2500)
            val t = device.wait(Until.findObject(By.text(title).pkg("com.android.systemui")), 20000L)
            if (t == null) captureScreen("shade_no_notification")
            assertNotNull("no notification titled $title in the shade", t)
            t!!.click()
            android.os.SystemClock.sleep(1500)
            return
        }
        val notification = bkContext.getSystemService(android.app.NotificationManager::class.java)!!.activeNotifications
            .firstOrNull { it.notification.extras.getCharSequence(android.app.Notification.EXTRA_TITLE)?.toString() == title }?.notification
        assertNotNull("no notification titled $title: ${bkTitles()}", notification)
        val action = notification!!.actions?.firstOrNull { it.title.toString().equals(label, ignoreCase = true) }
        assertNotNull("no $label on the notification: ${notification.actions?.map { it.title }}", action)
        action!!.actionIntent.send()
        android.os.SystemClock.sleep(1500)
    }

    private fun bkFlowDueMounted(drive: MountedDrive) {
        val job = bkJob(drive, "E2E_mounted")
        val title = bkText(R.string.backup_due_title, BackupScheduler.driveNames(job))
        returnToMainScreen()
        val now = System.currentTimeMillis()

        // Comes due overnight with the drive in: a notification, and no run.
        assertTrue("a USB backup started by itself", BackupScheduler.evaluate(bkContext, "periodic", now).isEmpty())
        android.os.SystemClock.sleep(800)
        assertTrue("no due notification: ${bkTitles()}", bkTitles().contains(title))
        assertFalse(BackupExecutor.isBusy(job.id))
        val text = bkContext.getSystemService(android.app.NotificationManager::class.java)!!.activeNotifications
            .first { it.id == BackupNotifications.dueId(job.id) }.notification.extras.getCharSequence(android.app.Notification.EXTRA_TEXT).toString()
        assertEquals(bkText(R.string.backup_due_text, "E2E_mounted"), text)

        // Dismissed: not back within 20 hours, back after.
        BackupNotifications.cancelDue(bkContext, job.id)
        BackupScheduler.evaluate(bkContext, "periodic", now + 19 * BackupJob.HOUR)
        android.os.SystemClock.sleep(500)
        assertFalse("back too soon", bkTitles().contains(title))
        BackupScheduler.evaluate(bkContext, "periodic", now + 21 * BackupJob.HOUR)
        android.os.SystemClock.sleep(500)
        assertTrue("not back after 20 hours", bkTitles().contains(title))

        // Not now, from the shade.
        bkNotificationTap(title, bkText(R.string.backup_due_not_now))
        device.executeShellCommand("cmd statusbar collapse")
        assertNotNull("Not now did not snooze", bkStore.job(job.id)!!.snoozedUntil)
        assertFalse("Not now left it up", bkTitles().contains(title))

        // Later, from the shade: the main screen offers the minutes.
        fun repost() {
            bkStore.update(job.id) { it.copy(snoozedUntil = null, delayedStartAt = null, lastReminderAt = null, lastSuccessAt = System.currentTimeMillis() - 25 * BackupJob.HOUR) }
            BackupScheduler.evaluate(bkContext, "periodic")
            android.os.SystemClock.sleep(800)
            assertTrue("no due notification: ${bkTitles()}", bkTitles().contains(title))
        }
        repost()
        bkNotificationTap(title, bkText(R.string.backup_due_later))
        bkAssertDesc("backup_later_15", "Later opened no choice of minutes", 20000L)
        assertTrue(clickByDesc("backup_later_15"))
        android.os.SystemClock.sleep(500)
        assertNotNull("no delay set", bkStore.job(job.id)!!.delayedStartAt)
        assertFalse("the notification stayed", bkTitles().contains(title))

        // Its body opens the dialog. Fired rather than tapped: beside the mounted-drive notification
        // it sits in a group, and a tap on the group's header opens the app's launcher instead.
        // The reminder and progress flows tap the real thing.
        repost()
        bkContext.getSystemService(android.app.NotificationManager::class.java)!!.activeNotifications
            .first { it.id == BackupNotifications.dueId(job.id) }.notification.contentIntent.send()
        bkAssertDesc("backup_due_title", "its body opened no dialog", 20000L)
        assertTrue(clickByDesc("backup_due_not_now"))
        bkAssertGone("backup_due_title", "Not now left the dialog up")

        // Start now, from the shade: runs, with no dialog in the way.
        repost()
        bkNotificationTap(title, bkText(R.string.backup_due_start))
        device.executeShellCommand("cmd statusbar collapse")
        bkExpectSuccess(bkAwait(job.id, "Start now from the shade"), "Start now from the shade")

        // The drive goes while it is up: the connect reminder takes its place.
        repost()
        bkPullDrive(drive)
        assertFalse("the due notification outlived the drive", bkTitles().contains(title))
        val reminder = bkText(R.string.backup_reminder_title, "E2E_mounted")
        assertTrue("no connect reminder: ${bkTitles()}", bkTitles().contains(reminder))
        val back = bkReplugDrive()
        bkAwaitDueNotification(title, "the replug did not ask")
        bkOpenDueFromBell("E2E_mounted")
        assertTrue(clickByDesc("backup_due_not_now"))
        BackupNotifications.cancelAll(bkContext, job.id)

        // A backup on the phone alone stays automatic, with no notification.
        val phone = bkCreate(BkEnd(bkPhoneRoot, listOf("Download", "otg_e2e", "src")), BkEnd(bkPhoneRoot, listOf("Download", "otg_e2e", "targets")), "E2E_phone_only", incremental = false)
        bkCloseProgress()
        bkExpectSuccess(bkStore.history(phone.id).last(), "the phone-only backup")
        val runs = bkStore.history(phone.id).size
        bkStore.update(phone.id) { it.copy(lastSuccessAt = System.currentTimeMillis() - 25 * BackupJob.HOUR) }
        assertTrue("a phone-only backup did not start by itself", BackupScheduler.evaluate(bkContext, "periodic").contains(phone.id))
        bkExpectSuccess(bkAwait(phone.id, "the automatic run"), "the automatic run")
        assertEquals(runs + 1, bkStore.history(phone.id).size)
        assertFalse("a phone-only backup asked", bkTitles().contains(bkText(R.string.backup_due_title, "")))
        assertNotNull(back)
    }

    // ── Due with no drive ───────────────────────────────────────────────────────────────

    private fun bkFlowReminder(drive: MountedDrive) {
        val job = bkJob(drive, "E2E_reminder")
        val never = bkJob(drive, "E2E_never", agoHours = null)
        val notDue = bkJob(drive, "E2E_not_due", agoHours = 1)
        val manual = bkJob(drive, "E2E_manual", interval = 0)
        BackupScheduler.evaluate(bkContext, "attach")
        bkAssertAsksQuietly(2, "mounting with two due")
        bkPullDrive(drive)
        BackupNotifications.cancelAll(bkContext, job.id); BackupNotifications.cancelAll(bkContext, never.id)
        listOf(job, never).forEach { j -> bkStore.update(j.id) { it.copy(lastReminderAt = null) } }
        val now = System.currentTimeMillis()

        assertTrue("started with the drive gone", BackupScheduler.evaluate(bkContext, "periodic", now).isEmpty())
        android.os.SystemClock.sleep(800)
        val title = bkText(R.string.backup_reminder_title, "E2E_reminder")
        val neverTitle = bkText(R.string.backup_reminder_never, "E2E_never")
        val titles = bkTitles()
        assertTrue("no reminder at the due time: $titles", titles.contains(title))
        assertTrue("no first-run reminder: $titles", titles.contains(neverTitle))
        assertFalse("a backup not due reminded: $titles", titles.any { it.contains("E2E_not_due") })
        assertFalse("a manual backup reminded: $titles", titles.any { it.contains("E2E_manual") })
        val text = bkContext.getSystemService(android.app.NotificationManager::class.java)!!.activeNotifications
            .first { it.id == BackupNotifications.reminderId(job.id) }.notification.extras.getCharSequence(android.app.Notification.EXTRA_TEXT).toString()
        assertEquals(bkText(R.string.backup_reminder_connect, VolumeInfo.driveName(job.destination.info)), text)

        // Not again for 20 hours, then again.
        BackupNotifications.cancel(bkContext, BackupNotifications.reminderId(job.id))
        BackupScheduler.evaluate(bkContext, "periodic", now + 19 * BackupJob.HOUR)
        android.os.SystemClock.sleep(500)
        assertFalse("reminded again too soon", bkTitles().contains(title))
        BackupScheduler.evaluate(bkContext, "periodic", now + 21 * BackupJob.HOUR)
        android.os.SystemClock.sleep(500)
        assertTrue("not reminded after 20 hours", bkTitles().contains(title))

        // A tap opens the app; the drive back and a good run clear it.
        bkNotificationTap(title, title)
        assertTrue("the tap did not open the app", device.wait(Until.hasObject(By.pkg("app.fayaz.otgmaster")), timeout))
        bkReplugDrive()
        bkOpenDueFromBell("E2E_reminder")
        android.os.SystemClock.sleep(2500)
        assertTrue(clickByDesc("backup_due_start"))
        bkAssertDesc("backup_progress_title", "Start now opened no dialog")
        bkCloseProgress()
        assertFalse("the reminder outlived a good backup: ${bkTitles()}", bkTitles().contains(title))
        assertNotNull(notDue); assertNotNull(manual)
    }

    // ── What Create refuses, and every part of its form ─────────────────────────────────

    private fun bkExpectRefused(message: String) {
        bkTapCreate("backup_create_error")
        val e = device.findObject(By.desc("backup_create_error"))
        if (e == null) captureScreen("no_refusal")
        assertNotNull("Create did not refuse: $message", e)
        bkAssertText(message, "the refusal's wording")
    }

    private fun bkFlowTargetChecks(drive: MountedDrive) {
        val src = BkEnd(drive.name, listOf("drive_src"))
        val top = BkEnd(drive.name, emptyList())
        val existing = bkJob(drive, "E2E_taken", agoHours = 1)
        bkExpectSuccess(bkRunNow(existing, "the first job's backup"), "the first job's backup")

        // The form itself.
        bkOpenCreate()
        clickInDialog("backup_learn_more")
        bkAssertText(bkText(R.string.backup_learn_more_title), "Learn more opened nothing")
        assertTrue(clickByDesc("backup_learn_more_close"))
        android.os.SystemClock.sleep(500)
        assertNotNull("no Backup reminders heading", findInDialog("backup_reminders_heading"))
        clickInDialog("backup_interval")
        assertNotNull("no Daily", device.wait(Until.findObject(By.desc("backup_interval_24")), timeout))
        assertNotNull("no Weekly", device.findObject(By.desc("backup_interval_168")))
        assertNotNull("no Monthly", device.findObject(By.desc("backup_interval_720")))
        assertNull("Manual on Create reminder", device.findObject(By.desc("backup_interval_0")))
        device.pressBack()
        assertTrue("Back left the frequency's menu up", device.wait(Until.gone(By.desc("backup_interval_24")), timeout))
        assertTrue("the heading is not above the frequency",
            device.findObject(By.desc("backup_reminders_heading")).visibleBounds.bottom <= device.findObject(By.desc("backup_interval")).visibleBounds.top + 40)
        bkTapSettled(bkText(R.string.cd_backup_reminders_info))
        bkAssertDesc("backup_reminders_info", "the info icon opened no dialog")
        assertNull("Create reminder's info mentions Manual, which it does not offer",
            device.findObject(By.text(bkText(R.string.backup_reminders_info) + " " + bkText(R.string.backup_reminders_info_manual))))
        assertTrue(clickByDesc("backup_reminders_info_close"))
        bkAssertGone("backup_reminders_info", "Close left the info up")
        assertNull("the password's details are still on the form", device.findObject(By.text(bkText(R.string.backup_password_help))))
        bkTapSettled(bkText(R.string.cd_backup_password_info))
        bkAssertDesc("backup_password_info", "the password's info icon opened no dialog")
        bkAssertText(bkText(R.string.backup_password_help), "the dialog does not hold the password's details")
        assertTrue(clickByDesc("backup_password_info_close"))
        bkAssertGone("backup_password_info", "Close left the password's info up")
        bkFormToTop()
        // Create does nothing without its folders.
        device.findObject(By.desc("backup_create"))?.click()
        android.os.SystemClock.sleep(800)
        assertNotNull("Create closed the form with no folders", device.findObject(By.desc("backup_name")))
        // The swap exchanges the ends.
        clickInDialog("backup_source_location")
        bkPick(src.root, *src.path.toTypedArray())
        clickInDialog("backup_swap")
        android.os.SystemClock.sleep(500)
        clickInDialog("backup_swap")
        // Same folder at both ends.
        clickInDialog("backup_target_location")
        bkPick(src.root, *src.path.toTypedArray())
        typeInto("backup_name", "E2E_same")
        clickInDialog("backup_type_plain")
        bkExpectRefused(bkText(R.string.backup_same_folder))
        // A password that differs, or is short, or is not marked saved: nothing happens.
        clickInDialog("backup_target_location")
        bkPick(top.root)
        clickInDialog("backup_type_incremental")
        bkTypePassword("backup_password", BK_PASSWORD)
        bkTypePassword("backup_password_confirm", "$BK_PASSWORD-x")
        clickInDialog("backup_password_saved")
        bkAssertText(bkText(R.string.encrypt_passwords_differ), "no word about passwords that differ")
        device.findObject(By.desc("backup_create"))?.click()
        android.os.SystemClock.sleep(800)
        assertNotNull("Create went ahead with passwords that differ", device.findObject(By.desc("backup_name")))
        bkTypePassword("backup_password_confirm", BK_PASSWORD)
        // Those edits unticked "saved": it does not go ahead until ticked again.
        device.findObject(By.desc("backup_create"))?.click()
        android.os.SystemClock.sleep(800)
        assertNotNull("Create went ahead without the saved tick", device.findObject(By.desc("backup_name")))
        device.pressBack(); android.os.SystemClock.sleep(500)
        if (device.findObject(By.desc("backup_name")) != null) device.pressBack()

        // Inside another job's folder.
        bkOpenCreate()
        bkFillForm(src, BkEnd(drive.name, listOf("E2E_taken")), "E2E_inside", incremental = false)
        bkExpectRefused(bkText(R.string.backup_target_inside_job, "E2E_taken"))
        device.pressBack(); android.os.SystemClock.sleep(500)
        if (device.findObject(By.desc("backup_name")) != null) device.pressBack()

        // The same folder as another job.
        bkOpenCreate()
        bkFillForm(src, top, "E2E_taken", incremental = true)
        bkExpectRefused(bkText(R.string.backup_target_same_job, "E2E_taken"))
        device.pressBack(); android.os.SystemClock.sleep(500)
        if (device.findObject(By.desc("backup_name")) != null) device.pressBack()

        // A folder that is itself a repository: desktop restic's, which no job knows.
        bkOpenCreate()
        bkFillForm(src, BkEnd(drive.name, listOf("desktop_repo")), "E2E_in_repo", incremental = false)
        bkExpectRefused(bkText(R.string.backup_target_is_repository))
        device.pressBack(); android.os.SystemClock.sleep(500)
        if (device.findObject(By.desc("backup_name")) != null) device.pressBack()

        // The name lands on that repository. Plain is refused, a wrong password is refused.
        bkOpenCreate()
        bkFillForm(src, top, "desktop_repo", incremental = false)
        bkExpectRefused(bkText(R.string.backup_target_plain_on_repository, "desktop_repo"))
        device.pressBack(); android.os.SystemClock.sleep(500)
        if (device.findObject(By.desc("backup_name")) != null) device.pressBack()
        bkOpenCreate()
        bkFillForm(src, top, "desktop_repo", incremental = true, password = "not-the-password")
        bkExpectRefused(bkText(R.string.backup_target_wrong_password, "desktop_repo"))
        device.pressBack(); android.os.SystemClock.sleep(500)
        if (device.findObject(By.desc("backup_name")) != null) device.pressBack()

        // Select existing, in the form: a folder outside the target and one with no backup are
        // refused; the password is asked once, a wrong one refused, and the field then locks.
        bkOpenCreate()
        clickInDialog("backup_source_location")
        bkPick(src.root, *src.path.toTypedArray())
        clickInDialog("backup_target_location")
        bkPick(top.root, "shared")
        clickInDialog("backup_select_existing")
        bkPick(top.root, "desktop_repo")
        bkAssertDesc("backup_create_error", "a backup outside the target was accepted", 20000L)
        assertNotNull("no reason for a folder outside the target", device.wait(Until.findObject(By.textContains("directly inside")), timeout))
        clickInDialog("backup_target_location")
        bkPick(top.root)
        clickInDialog("backup_select_existing")
        bkPick(top.root, "not_a_backup")
        bkAssertText(bkText(R.string.backups_browse_none), "a folder with no backup was accepted")
        clickInDialog("backup_select_existing")
        bkPick(top.root, "desktop_repo")
        bkAssertDesc("backup_start_new", "selecting a backup changed nothing", 20000L)
        assertNull("the confirm field is still asked for", device.findObject(By.desc("backup_password_confirm")))
        assertNull("the saved tick is still asked for", device.findObject(By.desc("backup_password_saved")))
        assertNotNull("the name was not filled in", device.findObject(By.text("desktop_repo")))
        bkTypePassword("backup_password", "not-the-password")
        clickInDialog("backup_existing_check")
        bkAssertText(bkText(R.string.backup_target_wrong_password, "desktop_repo"), "a wrong password was accepted")
        assertNull("a wrong password passed", device.findObject(By.desc("backup_existing_checked")))
        bkTypePassword("backup_password", BK_DESKTOP_PASSWORD)
        clickInDialog("backup_existing_check")
        bkAssertDesc("backup_existing_checked", "the right password was refused", 30000L)
        assertNull("Check password stayed", device.findObject(By.desc("backup_existing_check")))
        // Start new puts the normal form back.
        clickInDialog("backup_start_new")
        bkAssertDesc("backup_password_confirm", "Start new did not bring the form back")
        assertNull("Start new left the name", device.findObject(By.text("desktop_repo")))

        // Selected again and created: it continues desktop's repository, whose snapshots stay. The host counts them.
        clickInDialog("backup_select_existing")
        bkPick(top.root, "desktop_repo")
        bkAssertDesc("backup_start_new", "selecting the backup again changed nothing", 20000L)
        bkTypePassword("backup_password", BK_DESKTOP_PASSWORD)
        clickInDialog("backup_existing_check")
        bkAssertDesc("backup_existing_checked", "the right password was refused", 30000L)
        bkTapCreate("backup_progress_title")
        bkAssertDesc("backup_progress_title", "Create did not open the progress dialog", 60000L)
        val cont = bkStore.jobs.value.firstOrNull { it.name == "desktop_repo" }!!
        bkCloseProgress()
        bkExpectSuccess(bkStore.history(cont.id).last(), "continuing a desktop repository")
        assertEquals("only this job's snapshots are its own", 1, bkStore.snapshots(cont.id).size)

        // Two backups in one parent: each gets its own folder, and neither loses anything.
        val a = bkCreate(src, BkEnd(drive.name, listOf("shared")), "E2E_A", incremental = true)
        bkCloseProgress()
        bkExpectSuccess(bkStore.history(a.id).last(), "A into the shared folder")
        val c = bkCreate(BkEnd(drive.name, listOf("other_src")), BkEnd(drive.name, listOf("shared")), "E2E_C", incremental = false)
        bkCloseProgress()
        bkExpectSuccess(bkStore.history(c.id).last(), "C into the shared folder")
        val shared = documentChildren(bkContext, "${drive.id}:/shared").keys - ".keep"
        assertEquals("one folder each", setOf("E2E_A", "E2E_C"), shared)
        bkExpectSuccess(bkRunNow(a, "Verify of A after C", BackupExecutor.Op.Check(true)), "Verify of A after C")
    }

    // ── The progress dialog, from every door ────────────────────────────────────────────

    /** A backup held at its start, so its dialog can be looked at for as long as the test needs. */
    private fun bkHeldJob(name: String): BackupJob {
        bkHold()
        return bkCreate(BkEnd(bkPhoneRoot, listOf("Download", "otg_e2e", "src")), BkEnd(bkPhoneRoot, listOf("Download", "otg_e2e", "targets")), name, incremental = true)
    }

    private fun bkFlowProgressDialog(drive: MountedDrive) {
        // From Create: Hide goes to the main screen, the run and its notification carry on.
        val job = bkHeldJob("E2E_slow")
        bkAssertDesc("backup_progress_hide", "no Hide while running")
        bkAssertDesc("backup_progress_cancel", "no Cancel while running")
        assertTrue(clickByDesc("backup_progress_hide"))
        assertNotNull("Hide did not go to the main screen", device.wait(Until.findObject(By.desc("scan_button")), timeout))
        assertTrue("Hide stopped the run", BackupExecutor.isBusy(job.id))
        val title = "Backing up E2E_slow"
        assertTrue("the notification went with the dialog: ${bkTitles()}", bkTitles().contains(title))

        // From the notification: the same dialog, on the main screen, still with Hide.
        bkNotificationTap(title, title)
        bkAssertDesc("backup_progress_title", "the notification opened no dialog", 20000L)
        assertTrue(clickByDesc("backup_progress_hide"))
        bkAssertGone("backup_progress_title", "Hide left the dialog up")
        assertTrue("the notification went with the dialog: ${bkTitles()}", bkTitles().contains(title))

        // The result waits: it finishes unseen, and a tap on its notification shows it.
        bkRelease()
        bkAwait(job.id, "the slow run", 600_000L)
        bkExpectSuccess(bkStore.history(job.id).last(), "the slow run")
        val done = bkText(R.string.notification_operation_done, title)
        assertTrue("no result notification: ${bkTitles()}", bkTitles().contains(done))
        assertTrue("the notification stayed after the run", bkTitles().none { it == title })
        bkNotificationTap(done, done)
        bkAssertDesc("backup_progress_result", "the result did not wait", 20000L)
        assertTrue(clickByDesc("backup_progress_close"))
        bkAssertGone("backup_progress_title", "Close left the dialog up")

        // From Back up now, and its Cancel.
        bkHold()
        bkOpenTab("backups_tab_schedules")
        bkRowClick("backup_run_now_E2E_slow")
        bkAssertDesc("backup_progress_cancel", "Back up now opened no dialog")
        val snaps = bkStore.snapshots(job.id).size
        assertTrue(clickByDesc("backup_progress_cancel"))
        bkRelease()
        bkAssertDesc("backup_progress_result", "Cancel showed no result", 120_000L)
        assertEquals("the cancelled run", RunStatus.CANCELLED, bkStore.history(job.id).last().status)
        assertEquals("a cancelled run made a snapshot", snaps, bkStore.snapshots(job.id).size)
        bkCloseProgress()

        // The row's link, back into the dialog, and the row's own Cancel showing by itself.
        bkHold()
        bkRowClick("backup_run_now_E2E_slow")
        bkAssertDesc("backup_progress_hide", "no dialog")
        // Hide leaves Manage backups for the main screen; come back for the link.
        assertTrue(clickByDesc("backup_progress_hide"))
        bkOpenTab("backups_tab_schedules")
        bkAssertDesc("backup_cancel_E2E_slow", "the row's Cancel did not show by itself", 15000L)
        bkAssertDesc("backup_status_E2E_slow", "no status on the row")
        device.findObject(By.desc("backup_status_E2E_slow"))!!.click()
        bkAssertDesc("backup_progress_title", "the link opened no dialog")
        assertTrue(clickByDesc("backup_progress_cancel"))
        bkRelease()
        bkCloseProgress()

        // Restore, Verify: the dialog too, and a failure shows in it.
        bkRowClick("backup_verify_E2E_slow")
        bkAssertDesc("backup_progress_title", "Verify opened no dialog")
        bkCloseProgress()
        assertNotNull(drive)
    }

    // ── Mount backup ────────────────────────────────────────────────────────────────────

    /** Manage backups' Mount backup: straight to the Files picker, for [path] on [root]. */
    private fun bkMountFromPicker(root: String?, vararg path: String) {
        clickMountBackup()
        bkPick(root, *path)
    }

    /**
     * Settings > Disable backups: the bell, Manage backups and the Backups tab go, stay gone over
     * a relaunch and come back when it is turned off. A backup already mounted moves to the one
     * list left, and can still be unmounted from there.
     */
    private fun bkFlowBackupsDisabled(drive: MountedDrive) {
        val title = "E2E_off (backup)"
        val job = bkJob(drive, "E2E_off", agoHours = 1)
        bkExpectSuccess(bkRunNow(job, "the backup to mount"), "the backup to mount")
        bkMountFromPicker(drive.name, "E2E_off")
        assertNotNull("the mount did not open the slider", device.wait(Until.findObject(By.desc("mounted_drawer")), 60000L))
        assertNotNull("the backup is not on Backups", device.wait(Until.findObject(By.textContains(title)), 60000L))
        closeSlider()
        assertBackupUiShown(true)

        setBackupsDisabled(true)
        assertBackupUiShown(false)

        openSlider()
        assertNull("the Drives tab is still there", device.findObject(By.desc("mounted_tab_drives")))
        assertNull("the Backups tab is still there", device.findObject(By.desc("mounted_tab_backups")))
        assertNotNull("the mounted backup left the list", findInSlider(By.textContains(title)))
        bkUnmountBackup(BackupMounts.driveIdFor(job.id), title)
        assertNotNull("the drive is not in the list", findInSlider(By.text(driveTitle(drive))))
        closeSlider()

        relaunchApp()
        assertBackupUiShown(false)

        // Under More actions: Backups is gone, Back up stays and asks to enable backups first.
        val prefs = bkContext.getSharedPreferences("otgmaster_prefs", Context.MODE_PRIVATE)
        openSlider()
        val backUp = bkDriveAction("backup_action")
        assertNotNull("Back up left More actions", backUp)
        assertNull("Backups is still under More actions", device.findObject(By.desc("backups_action")))
        backUp!!.click()
        assertNotNull("no dialog offering to enable backups", device.wait(Until.findObject(By.desc("backups_enable_confirm")), timeout))
        assertTrue(clickByDesc("backups_enable_cancel"))
        assertTrue("Cancel left the dialog up", device.wait(Until.gone(By.desc("backups_enable_confirm")), timeout))
        assertTrue("Cancel enabled backups", prefs.getBoolean("backups_hidden", false))
        assertNull("Cancel went on to the backup form", device.findObject(By.desc("backup_name")))

        bkDriveAction("backup_action")!!.click()
        assertTrue(clickByDesc("backups_enable_confirm"))
        val form = device.wait(Until.findObject(By.desc("backup_name")), timeout)
        if (form == null) captureScreen("no_backup_form_after_enable")
        assertNotNull("Enable did not go on to the backup form", form)
        assertFalse("Enable did not change the setting", prefs.getBoolean("backups_hidden", false))
        device.pressBack()
        assertTrue("Back left the backup form up", device.wait(Until.gone(By.desc("backup_name")), timeout))

        closeSlider()
        assertBackupUiShown(true)
        openSlider("mounted_tab_backups")
        assertNotNull("the Drives tab did not come back", device.findObject(By.desc("mounted_tab_drives")))
        assertTrue(clickByDesc("mounted_tab_drives"))
        assertNotNull("Backups did not come back under More actions", bkDriveAction("backups_action"))
        device.pressBack()
        closeSlider()

        // The switch in Settings turns it off as well as on.
        setBackupsDisabled(true)
        setBackupsDisabled(false)
        assertBackupUiShown(true)
    }

    /** A mounted backup is unmounted, not ejected. The drive under it still offers Eject. */
    private fun bkFlowEjectNotOffered(drive: MountedDrive) {
        val title = "E2E_noeject (backup)"
        val job = bkJob(drive, "E2E_noeject", agoHours = 1)
        bkExpectSuccess(bkRunNow(job, "the backup to mount"), "the backup to mount")
        bkMountFromPicker(drive.name, "E2E_noeject")
        assertNotNull("the mount did not open the slider", device.wait(Until.findObject(By.desc("mounted_drawer")), 60000L))
        assertNotNull("the backup is not on Backups", device.wait(Until.findObject(By.textContains(title)), 60000L))
        android.os.SystemClock.sleep(500)
        assertNull("Eject offered on a mounted backup", device.findObject(By.desc("eject_button")))
        bkUnmountBackup(BackupMounts.driveIdFor(job.id), title)
        openSlider()
        assertNotNull("no Eject on the drive itself", findScrolling(By.desc("eject_button"), desc = "eject_button"))
    }

    /** A running backup holds the Eject, and the notification comes when the backup ends. */
    private fun bkFlowEjectBackupRunning(drive: MountedDrive) {
        val job = bkJob(drive, "E2E_ejectrun", agoHours = 1)
        BackupExecutor.testProgressDelayMs = 3000
        try {
            assertTrue("the backup did not start", BackupExecutor.start(bkContext, job.id, BackupExecutor.Op.Backup("manual")))
            val end = android.os.SystemClock.uptimeMillis() + 30_000L
            while (!BackupExecutor.isBusy(job.id) && android.os.SystemClock.uptimeMillis() < end) android.os.SystemClock.sleep(100)
            assertTrue("the backup is not running", BackupExecutor.isBusy(job.id))
            ejTapEject()
            assertNotNull("no transfer dialog with a backup running", device.wait(Until.findObject(By.desc("eject_busy_dismiss")), timeout))
            assertEquals("it ejected under a running backup", 1, OtgMasterState.mountedDrives.count { it.id == drive.id })
            assertTrue(clickByDesc("eject_busy_dismiss"))
            assertNotNull("the card does not say it is waiting", findInSlider(By.desc("eject_pending")))
        } finally {
            BackupExecutor.testProgressDelayMs = 0
        }
        bkExpectSuccess(bkAwait(job.id, "the backup"), "the backup")
        ejAwaitGone(120_000L)
        ejAwaitNotification()
    }

    /** A drive card's action: a button if it fit, otherwise in its More actions. */
    private fun bkDriveAction(id: String): androidx.test.uiautomator.UiObject2? {
        device.findObject(By.desc(id))?.let { return it }
        val more = findScrolling(By.desc("more_actions_button"), desc = "more_actions_button")
        assertNotNull("no More actions on the drive", more)
        more!!.click()
        return device.wait(Until.findObject(By.desc(id)), timeout)
    }

    /** Starts the activity afresh: what it keeps in memory is gone and it rereads its settings. */
    private fun relaunchApp() {
        bkContext.startActivity(mainActivityIntent().apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        })
        assertNotNull("the app did not come back", device.wait(Until.findObject(By.desc(bkContext.getString(R.string.cd_settings))), 30000L))
    }

    private fun setBackupsDisabled(disabled: Boolean) {
        val prefs = bkContext.getSharedPreferences("otgmaster_prefs", Context.MODE_PRIVATE)
        device.wait(Until.findObject(By.desc(bkContext.getString(R.string.cd_settings))), timeout)!!.click()
        val row = device.wait(Until.findObject(By.res("settings_row_backups")), timeout)
        if (row == null) captureScreen("no_settings_row_backups")
        assertNotNull("no Disable backups row in Settings", row)
        row!!.click()
        assertNotNull("Disable backups opened no dialog", device.wait(Until.findObject(By.desc("settings_dialog_backups")), timeout))
        assertTrue(clickByDesc("settings_backups_switch"))
        android.os.SystemClock.sleep(500)
        assertEquals("the switch did not change the setting", disabled, prefs.getBoolean("backups_hidden", false))
        assertTrue(clickByDesc("settings_dialog_close"))
        assertTrue("Close left the dialog up", device.wait(Until.gone(By.desc("settings_dialog_backups")), timeout))
        device.findObject(By.desc(bkContext.getString(R.string.cd_close_settings))).click()
        assertTrue("Settings stayed open", device.wait(Until.gone(By.res("settings_row_theme")), timeout))
    }

    private fun assertBackupUiShown(shown: Boolean) {
        val bell = By.descStartsWith(bkContext.getString(R.string.cd_backups_due, 0).substringBeforeLast(' '))
        if (shown) {
            assertNotNull("no bell", device.wait(Until.findObject(bell), timeout))
            assertNotNull("no Manage backups", findScrolling(By.desc("manage_backups_button"), desc = "manage_backups_button"))
        } else {
            assertTrue("the bell is still there", device.wait(Until.gone(bell), timeout))
            assertNull("Manage backups is still there", findScrolling(By.desc("manage_backups_button"), desc = "manage_backups_button"))
            // A search that finds nothing ends in the slider.
            closeSlider()
        }
        scrollToTop()
    }

    private fun bkFlowMountProgress(drive: MountedDrive) {
        val job = bkJob(drive, "E2E_mp", agoHours = 1)
        bkExpectSuccess(bkRunNow(job, "the backup to mount"), "the backup to mount")
        BackupMounts.testDelayMs = 4000

        // There is no tab for it any more, in Manage backups or anywhere.
        openBackupsTab("backups_open_schedules")
        assertNull("Manage backups still has a Mount tab", device.findObject(By.textContains("Mount backup")))
        returnToMainScreen()

        // A saved password that no longer opens it: it asks, rather than failing.
        val secrets = BackupSecrets(bkContext)
        val realPassword = secrets.password(job.id)!!
        secrets.setPassword(job.id, "not-the-password")
        bkMountFromPicker(drive.name, "E2E_mp")
        bkAssertDesc("backups_browse_password", "a wrong saved password did not ask", 60000L)
        device.pressBack()
        if (device.findObject(By.desc("backups_browse_password")) != null) device.pressBack()
        bkAssertGone("backups_browse_password", "Cancel left the password dialog up")
        secrets.setPassword(job.id, realPassword)

        // One of this phone's backups: a sign while it opens, then the slider, with no password asked.
        bkMountFromPicker(drive.name, "E2E_mp")
        bkAssertDesc("backups_browse_opening", "no sign after choosing a folder", 3000L)
        bkExpectMountAnnounced("E2E_mp (backup)")
        assertNull("it asked for the password it has saved", device.findObject(By.desc("backups_browse_password")))
        assertEquals("mounted once", 1, OtgMasterState.mountedDrives.count { it.id == BackupMounts.driveIdFor(job.id) })
        doUnmount()

        // Someone else's: a sign after choosing the folder and in the password dialog.
        bkMountFromPicker(drive.name, "desktop_repo")
        bkAssertDesc("backups_browse_opening", "no sign after choosing a folder", 3000L)
        bkAssertDesc("backups_browse_password", "no password dialog", 30000L)
        bkTypePassword("backups_browse_password", BK_DESKTOP_PASSWORD)
        clickInDialog("backups_browse_mount")
        bkAssertDesc("backups_browse_opening", "no sign in the password dialog", 3000L)
        bkAssertGone("backups_browse_mount", "the password dialog stayed", 60000L)
        bkExpectMountAnnounced("desktop_repo (backup)")
        doUnmount()

        // A reminder's card mounts it with the saved password, with a sign while it opens.
        returnToMainScreen()
        openBackupsTab("backups_open_schedules")
        bkRowClick("backup_mount_E2E_mp")
        bkAssertDesc("backup_mount_progress_E2E_mp", "no sign on the card's Mount", 3000L)
        BackupMounts.testDelayMs = 0
        bkExpectMountAnnounced("E2E_mp (backup)")
        doUnmount()
        // So does its History entry.
        returnToMainScreen()
        openBackupsTab("backups_open_history")
        bkRowClick("backup_mount_E2E_mp")
        bkExpectMountAnnounced("E2E_mp (backup)")
        doUnmount()

        // Two rows on one folder, and the same folder chosen in the picker: one mount.
        val twin = job.copy(id = java.util.UUID.randomUUID().toString(), name = "E2E_mp2")
        secrets.setPassword(twin.id, realPassword)
        bkStore.upsert(twin)
        val backupMounts = { OtgMasterState.mountedDrives.count { it.filesystemName == "Backup" } }
        returnToMainScreen()
        openBackupsTab("backups_open_schedules")
        bkRowClick("backup_mount_E2E_mp")
        bkExpectMountAnnounced("E2E_mp (backup)")
        assertEquals("the first row did not mount it", 1, backupMounts())
        returnToMainScreen()
        openBackupsTab("backups_open_schedules")
        bkRowClick("backup_mount_E2E_mp2")
        bkExpectMountAnnounced("E2E_mp (backup)")
        assertEquals("a second row mounted the same folder again", 1, backupMounts())
        returnToMainScreen()
        openBackupsTab("backups_open_history")
        bkRowClick("backup_mount_E2E_mp")
        bkExpectMountAnnounced("E2E_mp (backup)")
        assertEquals("its History entry mounted it again", 1, backupMounts())

        // The slider closed in the middle of a flash, then Mount again: it still opens and flashes.
        returnToMainScreen()
        openBackupsTab("backups_open_schedules")
        bkRowClick("backup_mount_E2E_mp")
        assertCardFlashes(By.text("E2E_mp (backup)"), "E2E_mp (backup)", 60000L)
        closeSlider()
        returnToMainScreen()
        openBackupsTab("backups_open_schedules")
        bkRowClick("backup_mount_E2E_mp")
        bkExpectMountAnnounced("E2E_mp (backup)")

        // The picker's check takes no time at all, and the flash would be over before the picker
        // has closed, so this holds it back until the test can watch.
        BackupMounts.testDelayMs = 4000
        bkMountFromPicker(drive.name, "E2E_mp")
        bkExpectMountAnnounced("E2E_mp (backup)")
        BackupMounts.testDelayMs = 0
        assertEquals("the picker mounted it again", 1, backupMounts())
        // Repeats of a line collapse into one in the log, so this asks only that it says so.
        assertTrue("the skipped mounts are not in the log", OtgMasterState.recentLogs().any { it.contains("E2E_mp (backup) is already mounted") })
        doUnmount()
        secrets.forget(twin.id)
        bkStore.delete(twin.id)

        // With the target's drive gone, neither does anything. The button follows within two seconds.
        bkPullDrive(drive)
        openBackupsTab("backups_open_history")
        android.os.SystemClock.sleep(3000)
        bkAssertMountRefused("History")
        bkOpenTab("backups_tab_schedules")
        android.os.SystemClock.sleep(3000)
        bkAssertMountRefused("the card")

        // Back again, it mounts.
        bkReplugDrive()
        openBackupsTab("backups_open_schedules")
        android.os.SystemClock.sleep(3000)
        bkRowClick("backup_mount_E2E_mp")
        bkExpectMountAnnounced("E2E_mp (backup)")
        doUnmount()
    }

    /** A tap on Mount does nothing with the target away: no mount, no error, and Manage backups stays up. */
    private fun bkAssertMountRefused(where: String) {
        bkRowClick("backup_mount_E2E_mp")
        android.os.SystemClock.sleep(1500)
        assertNull("Mount on $where opened something with the drive gone", device.findObject(By.desc("backup_mount_progress_E2E_mp")))
        assertNull("Mount on $where tried with the drive gone", device.findObject(By.desc("backup_mount_problem")))
        assertTrue("Mount on $where mounted with the drive gone", OtgMasterState.mountedDrives.none { it.id == BackupMounts.driveIdFor(bkStore.jobs.value.first { j -> j.name == "E2E_mp" }.id) })
        assertNotNull("Mount on $where left Manage backups", device.findObject(By.desc("backups_tab_history")))
    }

    // ── What each schedule row says ─────────────────────────────────────────────────────

    /** The row of [name] has a status, and [expected] is on the screen. */
    private fun bkExpectStatus(name: String, expected: String) {
        val row = bkScrollTo(By.desc("backup_status_$name"))
        if (row == null) captureScreen("no_status_$name")
        assertNotNull("no status on the row of $name", row)
        // The tree can lag the screen when a row's text changes in place, so read it afresh.
        var text: androidx.test.uiautomator.UiObject2? = null
        for (attempt in 1..15) {
            InstrumentationRegistry.getInstrumentation().uiAutomation.clearCache()
            text = device.wait(Until.findObject(By.text(expected)), 1000L)
            if (text != null) break
        }
        if (text == null) captureScreen("status_$name")
        assertNotNull("the row of $name does not say: $expected", text)
    }

    private fun bkFlowScheduleStatus(drive: MountedDrive) {
        val now = System.currentTimeMillis()
        val hour = BackupJob.HOUR
        val next = bkJob(drive, "E2E_next", agoHours = 3)
        val job = bkStore.job(next.id)!!
        bkOpenTab("backups_tab_schedules")
        bkExpectStatus("E2E_next", bkText(R.string.backup_state_next, app.fayaz.otgmaster.backup.Format.whenAt(job.nextRunAt()!!)))

        // A delay and a snooze set the time shown.
        BackupDelays.schedule(bkContext, job.id, 30)
        bkExpectStatus("E2E_next", bkText(R.string.backup_state_next, app.fayaz.otgmaster.backup.Format.whenAt(bkStore.job(job.id)!!.delayedStartAt!!)))
        BackupDelays.clearFor(bkContext, job.id)
        BackupScheduler.snooze(bkContext, job.id, now)
        bkExpectStatus("E2E_next", bkText(R.string.backup_state_next, app.fayaz.otgmaster.backup.Format.whenAt(now + 24 * hour)))
        bkStore.update(job.id) { it.copy(snoozedUntil = null, lastSuccessAt = now - 25 * hour) }

        // Due with the drive here.
        bkExpectStatus("E2E_next", bkText(R.string.backup_state_due))

        // In progress, and waiting behind it: the one thread takes them in turn.
        val slow = bkHeldJob("E2E_busy")
        bkAssertDesc("backup_progress_hide", "no dialog")
        assertTrue(clickByDesc("backup_progress_hide"))
        assertTrue("the held run has finished", BackupExecutor.isBusy(slow.id))
        assertTrue(BackupExecutor.start(bkContext, job.id, BackupExecutor.Op.Backup("manual")))
        bkOpenTab("backups_tab_schedules")
        bkExpectStatus("E2E_next", bkText(R.string.backup_state_waiting))
        bkExpectStatus("E2E_busy", bkText(R.string.backup_state_in_progress))
        bkRelease()
        bkAwait(slow.id, "the slow run", 600_000L)
        bkExpectSuccess(bkAwait(job.id, "the waiting backup", 120_000L), "the waiting backup")

        // The frequency is a dropdown on the card, and what it picks is kept.
        bkRowClick("backup_interval_E2E_next")
        assertNotNull("no Daily on the card", device.wait(Until.findObject(By.desc("backup_interval_E2E_next_24")), timeout))
        assertNotNull("no Monthly on the card", device.findObject(By.desc("backup_interval_E2E_next_720")))
        assertNull("Manual on a reminder's card", device.findObject(By.desc("backup_interval_E2E_next_0")))
        device.findObject(By.desc("backup_interval_E2E_next_168")).click()
        assertTrue("the card's menu stayed", device.wait(Until.gone(By.desc("backup_interval_E2E_next_168")), timeout))
        assertEquals("the card's dropdown did not set Weekly", 168, bkStore.job(job.id)!!.schedule.intervalHours)
        // Back to Daily: the rest of this flow expects it to be due after a day.
        bkRowClick("backup_interval_E2E_next")
        device.wait(Until.findObject(By.desc("backup_interval_E2E_next_24")), timeout)!!.click()
        assertTrue("the card's menu stayed", device.wait(Until.gone(By.desc("backup_interval_E2E_next_24")), timeout))
        assertEquals("the card's dropdown did not set Daily", 24, bkStore.job(job.id)!!.schedule.intervalHours)

        bkExpectScheduleColumns("E2E_next", "E2E_busy")

        // Due with the drive gone.
        bkStore.update(job.id) { it.copy(lastSuccessAt = System.currentTimeMillis() - 25 * hour) }
        bkPullDrive(drive)
        openBackupsTab("backups_open_schedules")
        bkExpectStatus("E2E_next", bkText(R.string.backup_state_due_connect, BackupScheduler.driveNames(job)))
        // Corrupted says so, whatever else is true.
        bkStore.update(job.id) { it.copy(corruptedAt = System.currentTimeMillis(), corruptedReason = null) }
        bkExpectStatus("E2E_next", bkText(R.string.backup_state_corrupted))
    }

    /**
     * The mounted card flashes green. The flash lasts 1.5 s and leaves no state behind, so this looks
     * at the screen: the card's padding, just left of [anchor], until green shows in it. A resting card
     * is never greener than it is red, in either theme.
     */
    private fun assertCardFlashes(anchor: androidx.test.uiautomator.BySelector, what: String, waitMs: Long) {
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        val inset = (8 * ApplicationProvider.getApplicationContext<Context>().resources.displayMetrics.density).toInt()
        val end = android.os.SystemClock.uptimeMillis() + waitMs
        var firstSeen = 0L
        while (android.os.SystemClock.uptimeMillis() < end) {
            val bounds = try { device.findObject(anchor)?.visibleBounds } catch (e: androidx.test.uiautomator.StaleObjectException) { null }
            if (bounds == null) {
                android.os.SystemClock.sleep(50)
                continue
            }
            if (firstSeen == 0L) firstSeen = android.os.SystemClock.uptimeMillis()
            automation.takeScreenshot()?.let { shot ->
                val pixel = shot.getPixel((bounds.left - inset).coerceAtLeast(0), bounds.centerY())
                shot.recycle()
                if (android.graphics.Color.green(pixel) > android.graphics.Color.red(pixel)) return
            }
            if (android.os.SystemClock.uptimeMillis() - firstSeen > 5000L) break
        }
        captureScreen("no_flash")
        fail("the card of $what did not flash")
    }

    /** A mount from Manage backups leaves it for the slider, open on the Backups tab, which highlights it and says so. */
    private fun bkExpectMountAnnounced(title: String) {
        assertCardFlashes(By.text(title), title, 60000L)
        val slider = device.wait(Until.findObject(By.desc("mounted_drawer")), 60000L)
        if (slider == null) captureScreen("mount_stayed_on_backups")
        assertNotNull("the mount did not open the slider", slider)
        assertNull("Manage backups is open", device.findObject(By.desc("backups_tab_history")))
        val toast = device.wait(Until.findObject(By.text("Mounted: $title")), 10000L)
        if (toast == null) captureScreen("mount_no_toast")
        assertNotNull("no toast for the mount of $title", toast)
        // The tab's own node reads selected=false either way, so what is showing says which tab is up.
        if (device.wait(Until.findObject(By.textContains(title)), timeout) == null) captureScreen("mount_not_on_backups")
        assertNotNull("the slider did not open on Backups, with the backup in it", device.findObject(By.textContains(title)))
        assertNull("the main screen did not take the highlight", BackupMounts.justMounted.value)
        assertTrue("the toast stayed on the screen", device.wait(Until.gone(By.text("Mounted: $title")), 15000L))
        assertTrue(clickByDesc("mounted_tab_drives"))
        assertTrue("the backup is listed under Drives too", device.wait(Until.gone(By.textContains(title)), timeout))
        assertTrue(clickByDesc("mounted_tab_backups"))
        assertNotNull("the backup left Backups", device.wait(Until.findObject(By.textContains(title)), timeout))
        closeSlider()
    }

    /** Two schedules stack on a phone and sit side by side on an unfolded phone's size of screen. */
    private fun bkExpectScheduleColumns(first: String, second: String) {
        fun lefts(): Pair<Int, Int> {
            for (attempt in 1..15) {
                InstrumentationRegistry.getInstrumentation().uiAutomation.clearCache()
                val a = device.wait(Until.findObject(By.desc("backup_status_$first")), 1000L)
                val b = device.findObject(By.desc("backup_status_$second"))
                val pair = runCatching { if (a != null && b != null) a.visibleBounds.left to b.visibleBounds.left else null }.getOrNull()
                if (pair != null) return pair
            }
            captureScreen("schedule_columns")
            throw AssertionError("$first and $second are not both on the screen")
        }
        val (narrowA, narrowB) = lefts()
        assertEquals("the phone layout is not one column", narrowA, narrowB)
        onWideScreen {
            val (wideA, wideB) = lefts()
            if (kotlin.math.abs(wideA - wideB) < device.displayWidth / 3) captureScreen("schedule_columns")
            assertTrue("no second column at ${device.displayWidth} px: $wideA and $wideB", kotlin.math.abs(wideA - wideB) >= device.displayWidth / 3)
        }
    }

    /** Runs [check] at an unfolded phone's size of screen, and leaves the emulator as it was. */
    private fun onWideScreen(check: () -> Unit) {
        try {
            device.executeShellCommand("wm size 2208x1840")
            device.executeShellCommand("wm density 420")
            device.waitForIdle()
            android.os.SystemClock.sleep(1500)
            check()
        } finally {
            device.executeShellCommand("wm density reset")
            device.executeShellCommand("wm size reset")
            // A display this wide makes the launcher show its taskbar, and it keeps showing it
            // after the reset. On the phone screen that bar covers the bottom of every later
            // case on this emulator: swipes starting there do nothing, so forms stop scrolling.
            // Restarting the launcher is what clears it.
            device.executeShellCommand("am force-stop $EMULATOR_LAUNCHER")
            device.waitForIdle()
            android.os.SystemClock.sleep(1500)
        }
        if (!device.wait(Until.gone(By.pkg(EMULATOR_LAUNCHER)), 15000L)) {
            captureScreen("taskbar_left_behind")
            throw AssertionError("the launcher's taskbar is still on screen after the size reset")
        }
    }

    // ── Remove reminder ─────────────────────────────────────────────────────────────────

    private fun bkFlowRemoveSchedule(drive: MountedDrive) {
        val job = bkJob(drive, "E2E_rm", agoHours = 25)
        bkExpectSuccess(bkRunNow(job, "the backup to keep"), "the backup to keep")
        bkStore.update(job.id) { it.copy(lastSuccessAt = System.currentTimeMillis() - 25 * BackupJob.HOUR) }
        BackupDelays.schedule(bkContext, job.id, 30)
        BackupNotifications.reminder(bkContext, bkStore.job(job.id)!!)
        android.os.SystemClock.sleep(500)
        assertTrue("no reminder to clear: ${bkTitles()}", bkTitles().contains(bkText(R.string.backup_reminder_title, "E2E_rm")))

        // The dialog warns; Cancel keeps everything.
        bkOpenTab("backups_tab_schedules")
        bkRowClick("backup_remove_E2E_rm")
        bkAssertText(bkText(R.string.backup_delete_job_title, "E2E_rm"), "the dialog's title")
        bkAssertText(bkText(R.string.backup_delete_job_text), "the password warning")
        bkAssertDesc("backup_remove_show_password", "no way to see the password first")
        assertTrue(clickByDesc("backup_remove_cancel"))
        bkAssertGone("backup_remove_confirm", "Cancel left the dialog up")
        assertNotNull("Cancel removed it", bkStore.job(job.id))

        // Remove: job, password, delay and notifications go; the backup stays.
        bkRowClick("backup_remove_E2E_rm")
        assertTrue(clickByDesc("backup_remove_confirm"))
        bkAssertGone("backup_remove_confirm", "the dialog stayed")
        android.os.SystemClock.sleep(800)
        assertNull("the job survived", bkStore.job(job.id))
        assertNull("the password survived", BackupSecrets(bkContext).password(job.id))
        assertFalse("the reminder survived: ${bkTitles()}", bkTitles().contains(bkText(R.string.backup_reminder_title, "E2E_rm")))
        assertTrue("the backup went with the schedule", documentChildren(bkContext, "${drive.id}:/E2E_rm").containsKey("config"))
        assertTrue("it is gone from the row", device.wait(Until.gone(By.text("E2E_rm")), timeout))
        // The delay cannot fire for it.
        assertFalse(BackupDelays.fire(bkContext, job.id))

        // A phone folder's grant goes with its only job, and stays while another uses it.
        val phoneSrc = BkEnd(bkPhoneRoot, listOf("Download", "otg_e2e", "src"))
        val p1 = bkCreate(phoneSrc, BkEnd(drive.name, emptyList()), "E2E_g1", incremental = false)
        bkCloseProgress()
        val p2 = bkCreate(phoneSrc, BkEnd(drive.name, emptyList()), "E2E_g2", incremental = false)
        bkCloseProgress()
        val uri = p1.source.uri
        fun granted() = bkContext.contentResolver.persistedUriPermissions.any { it.uri.toString() == uri }
        assertTrue("no grant to begin with", granted())
        bkRowClick("backup_remove_E2E_g1")
        assertTrue(clickByDesc("backup_remove_confirm"))
        android.os.SystemClock.sleep(800)
        assertTrue("the grant went while another job uses it", granted())
        bkRowClick("backup_remove_E2E_g2")
        assertTrue(clickByDesc("backup_remove_confirm"))
        android.os.SystemClock.sleep(800)
        assertFalse("the grant outlived the last job using it", granted())
        assertNotNull(p2)
    }

    // ── Manual backups live in History ──────────────────────────────────────────────────

    private fun bkFlowManualHistory(drive: MountedDrive) {
        // Made from the card's Back up, where Manual is offered.
        returnToMainScreen()
        val action = findScrolling(By.text(bkText(R.string.backup_action)), text = bkText(R.string.backup_action))
        if (action == null) {
            clickScrolling("more_actions_button", "More actions missing")
            device.wait(Until.findObject(By.text(bkText(R.string.backup_action))), timeout)!!.click()
        } else action.click()
        clickInDialog("backup_interval")
        assertNotNull("no Manual on the card's Back up", device.wait(Until.findObject(By.desc("backup_interval_0")), timeout))
        device.pressBack()
        assertTrue("Back left the frequency's menu up", device.wait(Until.gone(By.desc("backup_interval_0")), timeout))
        bkTapSettled(bkText(R.string.cd_backup_reminders_info))
        bkAssertText(bkText(R.string.backup_reminders_info) + " " + bkText(R.string.backup_reminders_info_manual), "the info does not cover Manual")
        assertTrue(clickByDesc("backup_reminders_info_close"))
        bkAssertGone("backup_reminders_info", "Close left the info up")
        bkFormToTop()
        bkFillForm(BkEnd(drive.name, listOf("drive_src")), BkEnd(drive.name, emptyList()), "E2E_manual", incremental = true, interval = 0)
        bkTapCreate("backup_progress_title")
        bkAssertDesc("backup_progress_title", "Create opened no dialog", 60000L)
        bkCloseProgress()
        val job = bkStore.jobs.value.first { it.name == "E2E_manual" }
        assertTrue("not manual", job.schedule.isManual)

        // Not under Schedules.
        bkOpenTab("backups_tab_schedules")
        android.os.SystemClock.sleep(1500)
        assertNull("a manual backup under Schedules", device.findObject(By.text("E2E_manual")))
        assertNull("no row, no status", device.findObject(By.desc("backup_status_E2E_manual")))

        // Under History, and only its newest entry has the actions.
        assertTrue(clickByDesc("backups_tab_history"))
        bkAssertDesc("backup_run_now_E2E_manual", "no Run again on its History entry")
        bkAssertText(bkText(R.string.backup_run_again), "the button says Run again")
        assertTrue("no More actions on its History entry", bkOpenMore("E2E_manual"))
        for (a in BK_IN_MORE) bkAssertDesc("backup_${a}_E2E_manual", "no $a in the More actions of its History entry")
        device.pressBack()
        android.os.SystemClock.sleep(500)
        bkRowClick("backup_run_now_E2E_manual")
        bkCloseProgress()
        assertEquals("two runs", 2, bkStore.history(job.id).size)
        assertEquals("only the newest entry has the actions", 1, device.findObjects(By.desc("backup_run_now_E2E_manual")).size)
        bkRowClick("backup_verify_E2E_manual")
        bkCloseProgress()
        bkExpectSuccess(bkStore.history(job.id).last(), "Verify from History")

        // Never due, never reminds.
        assertFalse(bkStore.job(job.id)!!.isDue(System.currentTimeMillis() + 1000 * BackupJob.HOUR))
        assertTrue(BackupScheduler.evaluate(bkContext, "attach").isEmpty())
        android.os.SystemClock.sleep(1000)
        assertNull("a manual backup asked", device.findObject(By.desc("backup_due_title")))

        // Remove, from History.
        assertTrue(clickByDesc("backups_tab_history"))
        bkRowClick("backup_remove_E2E_manual")
        assertTrue(clickByDesc("backup_remove_confirm"))
        android.os.SystemClock.sleep(800)
        assertNull("the job survived", bkStore.job(job.id))
    }

    // ── Show password needs the phone's lock ────────────────────────────────────────────

    private fun bkFlowShowPassword(drive: MountedDrive) {
        val job = bkJob(drive, "E2E_pw", agoHours = 1)
        bkExpectSuccess(bkRunNow(job, "the backup"), "the backup")
        bkShell("locksettings clear --old 1234")

        // No screen lock: refuses, and shows nothing.
        bkOpenTab("backups_tab_schedules")
        bkRowClick("backup_show_password_E2E_pw")
        bkAssertText(bkText(R.string.backup_password_needs_lock), "no refusal without a screen lock")
        assertNull("the password showed without a lock", device.findObject(By.desc("backup_password_shown")))
        assertNull("the password is on the screen", device.findObject(By.text(BK_PASSWORD)))
        device.pressBack()
        android.os.SystemClock.sleep(500)

        bkShell("locksettings set-pin 1234")
        try {
            // Cancelled: nothing shows.
            // The PIN screen is a secure window: backing out takes one press for the keyboard
            // and one for the screen, and nothing behind it can be seen until then.
            fun leavePinScreen() {
                repeat(4) {
                    if (device.findObject(By.desc("backups_tab_history")) != null) return
                    device.pressBack()
                    android.os.SystemClock.sleep(1500)
                }
            }
            bkRowClick("backup_show_password_E2E_pw")
            android.os.SystemClock.sleep(2000)
            assertNull("the password showed before the check", device.findObject(By.desc("backup_password_shown")))
            leavePinScreen()
            assertNull("the password showed after a cancelled check", device.findObject(By.desc("backup_password_shown")))

            // The PIN: it shows.
            bkRowClick("backup_show_password_E2E_pw")
            android.os.SystemClock.sleep(2000)
            bkShell("input text 1234")
            bkShell("input keyevent 66")
            bkAssertDesc("backup_password_shown", "the PIN did not show the password", 20000L)
            bkAssertText(BK_PASSWORD, "the wrong password showed")
            assertTrue(clickByDesc("backup_password_close"))

            // From the Remove dialog too.
            bkRowClick("backup_remove_E2E_pw")
            assertTrue(clickByDesc("backup_remove_show_password"))
            android.os.SystemClock.sleep(2000)
            assertNull("the Remove dialog showed it unchecked", device.findObject(By.desc("backup_password_shown")))
            leavePinScreen()
            if (device.findObject(By.desc("backup_remove_cancel")) != null) assertTrue(clickByDesc("backup_remove_cancel"))
        } finally {
            bkShell("locksettings clear --old 1234")
        }
    }

    // ── What a new phone lacks ──────────────────────────────────────────────────────────

    private fun bkFlowRecoverAccess(drive: MountedDrive) {
        // The password did not come with the app.
        val job = bkJob(drive, "E2E_new", agoHours = 1)
        bkExpectSuccess(bkRunNow(job, "the backup"), "the backup")
        BackupSecrets(bkContext).forget(job.id)
        bkOpenTab("backups_tab_schedules")
        bkAssertDesc("backup_password_missing_E2E_new", "the row does not say the password is missing")
        bkAssertText(bkText(R.string.backup_enter_password_hint), "the row's wording")
        val failed = bkRunNow(job, "a backup with no password")
        assertEquals("with no password: ${failed.message}", RunStatus.FAILED, failed.status)
        assertTrue(failed.message, failed.message.contains("Enter password"))

        // Checked against the repository before it is saved.
        bkOpenTab("backups_tab_schedules")
        bkRowClick("backup_enter_password_E2E_new")
        bkAssertDesc("backup_enter_password_field", "no password dialog")
        bkTypePassword("backup_enter_password_field", "not-the-password")
        assertTrue(clickByDesc("backup_enter_password_save"))
        bkAssertDesc("backup_enter_password_error", "a wrong password was not refused", 60000L)
        assertNull("a wrong password was saved", BackupSecrets(bkContext).password(job.id))
        bkTypePassword("backup_enter_password_field", BK_PASSWORD)
        assertTrue(clickByDesc("backup_enter_password_save"))
        bkAssertGone("backup_enter_password_field", "the right password was not accepted", 60000L)
        assertEquals(BK_PASSWORD, BackupSecrets(bkContext).password(job.id))
        bkAssertGone("backup_password_missing_E2E_new", "the row still says it is missing", 10000L)
        bkExpectSuccess(bkRunNow(job, "a backup with the password back"), "a backup with the password back")

        // The phone folder's grant did not come either.
        val phone = bkCreate(BkEnd(bkPhoneRoot, listOf("Download", "otg_e2e", "src")), BkEnd(drive.name, emptyList()), "E2E_grant", incremental = false)
        bkCloseProgress()
        bkContext.contentResolver.releasePersistableUriPermission(android.net.Uri.parse(phone.source.uri),
            Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        bkOpenTab("backups_tab_schedules")
        assertNotNull("the row does not say the folder is lost", bkScrollTo(By.desc("backup_folder_lost_E2E_grant")))
        val lost = bkRunNow(phone, "a backup with no grant")
        assertEquals("with no grant: ${lost.message}", RunStatus.UNAVAILABLE, lost.status)
        bkOpenTab("backups_tab_schedules")
        bkRowClick("backup_choose_folder_E2E_grant")
        bkPick(bkPhoneRoot, "Download", "otg_e2e", "src")
        bkAssertGone("backup_folder_lost_E2E_grant", "the row still says the folder is lost", 10000L)
        bkExpectSuccess(bkRunNow(phone, "a backup with the folder back"), "a backup with the folder back")
    }

    // ── Corrupted, and Recreate ─────────────────────────────────────────────────────────

    /** A folder and what is in it. Some filesystems here refuse to delete a folder that is not empty. */
    private fun bkDeleteTree(docId: String) {
        documentChildren(bkContext, docId).values.forEach(::bkDeleteTree)
        DocumentsContract.deleteDocument(bkContext.contentResolver, DocumentsContract.buildDocumentUri(AUTHORITY, docId))
    }

    /** Flips bytes in the middle of a file on the drive, keeping its size. */
    private fun bkDamage(docId: String) {
        val uri = DocumentsContract.buildDocumentUri(AUTHORITY, docId)
        val bytes = bkContext.contentResolver.openInputStream(uri)!!.use { it.readBytes() }
        assertTrue("nothing to damage in $docId", bytes.size > 64)
        for (i in bytes.size / 2 until bytes.size / 2 + 16) bytes[i] = (bytes[i].toInt() xor 0x5A).toByte()
        bkContext.contentResolver.openOutputStream(uri, "wt")!!.use { it.write(bytes) }
    }

    private fun bkFirstPack(drive: MountedDrive, name: String): String {
        val dirs = documentChildren(bkContext, "${drive.id}:/$name/data")
        val packs = dirs.values.flatMap { documentChildren(bkContext, it).values }
        assertTrue("no packs in $name", packs.isNotEmpty())
        return packs.first()
    }

    private fun bkFlowVerifyDamaged(drive: MountedDrive) {
        // Verify finds it: the dialog says Corrupted and offers Recreate.
        val job = bkJob(drive, "E2E_bad", agoHours = 1)
        bkExpectSuccess(bkRunNow(job, "the backup"), "the backup")
        bkExpectSuccess(bkRunNow(job, "Verify of a good backup", BackupExecutor.Op.Check(true)), "Verify of a good backup")
        assertNull("a good backup is marked corrupted", bkStore.job(job.id)!!.corruptedAt)
        bkDamage(bkFirstPack(drive, "E2E_bad"))
        bkOpenTab("backups_tab_schedules")
        bkRowClick("backup_verify_E2E_bad")
        bkAssertDesc("backup_progress_corrupted", "Verify did not say Corrupted", 300_000L)
        assertNotNull("not marked corrupted", bkStore.job(job.id)!!.corruptedAt)
        assertEquals("the record", RunStatus.CORRUPTED, bkStore.history(job.id).last().status)
        assertTrue(clickByDesc("backup_progress_close"))
        bkAssertGone("backup_progress_title", "Close left the dialog up")

        // The row and History say so; it stops asking to run; Back up now is off.
        bkExpectStatus("E2E_bad", bkText(R.string.backup_state_corrupted))
        assertNotNull("no reason on the row", device.findObject(By.text(bkStore.job(job.id)!!.corruptedReason!!)))
        bkStore.update(job.id) { it.copy(lastSuccessAt = System.currentTimeMillis() - 25 * BackupJob.HOUR) }
        assertFalse("a corrupted backup is due", bkStore.job(job.id)!!.isDue(System.currentTimeMillis()))
        device.findObject(By.desc("backup_run_now_E2E_bad"))?.click()
        android.os.SystemClock.sleep(1000)
        assertFalse("Back up now ran on a corrupted backup", BackupExecutor.isBusy(job.id))
        assertTrue(clickByDesc("backups_tab_history"))
        assertNotNull("History does not say corrupted", bkScrollTo(By.textStartsWith("corrupted · ")))
        assertTrue(clickByDesc("backups_tab_schedules"))

        // The offer, with the source away: it says what it needs, and cannot start.
        val realSource = bkStore.job(job.id)!!.source
        bkStore.update(job.id) { it.copy(source = realSource.copy(uri = DocumentsContract.buildTreeDocumentUri(AUTHORITY, "feedface00:/").toString())) }
        bkRowClick("backup_recreate_E2E_bad")
        bkAssertDesc("backup_corrupted_title", "Recreate opened no dialog")
        bkAssertDesc("backup_corrupted_connect", "it does not say the source is missing", 15000L)
        device.findObject(By.desc("backup_recreate_confirm"))?.click()
        android.os.SystemClock.sleep(1000)
        assertFalse("Recreate started with the source away", BackupExecutor.isBusy(job.id))
        assertTrue(clickByDesc("backup_recreate_not_now"))
        bkAssertGone("backup_corrupted_title", "Not now left the dialog up")
        assertNotNull("Not now cleared the state", bkStore.job(job.id)!!.corruptedAt)
        bkStore.update(job.id) { it.copy(source = realSource) }

        // Recreate: a new good backup, the damaged one kept beside it.
        bkRowClick("backup_recreate_E2E_bad")
        bkAssertDesc("backup_recreate_confirm", "no Recreate")
        android.os.SystemClock.sleep(2500)
        assertTrue(clickByDesc("backup_recreate_confirm"))
        bkCloseProgress()
        bkExpectSuccess(bkStore.history(job.id).last(), "Recreate")
        assertNull("still corrupted", bkStore.job(job.id)!!.corruptedAt)
        val top = documentChildren(bkContext, "${drive.id}:/").keys
        assertTrue("the damaged backup was not kept: $top", top.any { it.startsWith("E2E_bad damaged ") })
        assertTrue("no new backup: $top", top.contains("E2E_bad"))
        bkExpectSuccess(bkRunNow(job, "Verify of the new backup", BackupExecutor.Op.Check(true)), "Verify of the new backup")
        assertEquals("one new snapshot", 1, bkStore.snapshots(job.id).size)

        // A plain copy: a changed file, then a missing one.
        val plain = bkJob(drive, "E2E_plain_bad", type = JobType.ONE_TIME_COPY, agoHours = 1)
        bkExpectSuccess(bkRunNow(plain, "the copy"), "the copy")
        val folder = bkStore.history(plain.id).last().copyFolder!!.substringAfterLast('/')
        val copied = documentChildren(bkContext, "${drive.id}:/E2E_plain_bad/$folder")
        val file = copied.entries.first { it.key.endsWith(".bin") }
        bkDamage(file.value)
        val checked = bkRunNow(plain, "Verify of a changed copy", BackupExecutor.Op.Check(true))
        assertEquals("a changed copy: ${checked.message}", RunStatus.CORRUPTED, checked.status)
        assertNotNull(bkStore.job(plain.id)!!.corruptedAt)
        BackupPrompts.askRecreate(null)
        // Recreate a plain one: a new dated copy, nothing renamed.
        android.os.SystemClock.sleep(1100)
        bkExpectSuccess(bkRunNow(plain, "recreating the copy", BackupExecutor.Op.Recreate), "recreating the copy")
        assertNull(bkStore.job(plain.id)!!.corruptedAt)
        assertEquals("two dated copies", 2, documentChildren(bkContext, "${drive.id}:/E2E_plain_bad").size)

        // A Verify that passes clears the state, when it was wrong to begin with.
        bkStore.update(job.id) { it.copy(corruptedAt = 1L, corruptedReason = "x") }
        bkExpectSuccess(bkRunNow(job, "Verify of a good backup", BackupExecutor.Op.Check(true)), "Verify of a good backup")
        assertNull("a passing Verify left it Corrupted", bkStore.job(job.id)!!.corruptedAt)

        // A repository that is gone altogether.
        val gone = bkJob(drive, "E2E_gone", agoHours = 1)
        bkExpectSuccess(bkRunNow(gone, "the backup"), "the backup")
        val folderDoc = documentChildren(bkContext, "${drive.id}:/")["E2E_gone"]!!
        DocumentsContract.renameDocument(bkContext.contentResolver, DocumentsContract.buildDocumentUri(AUTHORITY, folderDoc), "E2E_elsewhere")
        val goneRun = bkRunNow(gone, "Verify with the repository gone", BackupExecutor.Op.Check(true))
        assertEquals("gone: ${goneRun.message}", RunStatus.CORRUPTED, goneRun.status)

        // Found by a restore, nobody looking: the offer comes up by itself.
        val found = bkJob(drive, "E2E_found", agoHours = 1)
        bkExpectSuccess(bkRunNow(found, "the backup"), "the backup")
        bkDamage(bkFirstPack(drive, "E2E_found"))
        BackupPrompts.showProgress(null); BackupPrompts.askRecreate(null)
        returnToMainScreen()
        val snap = bkStore.snapshots(found.id).last().id
        bkShell("mkdir -p $PHONE/x")
        assertTrue(BackupExecutor.start(bkContext, found.id, BackupExecutor.Op.Restore(snap, "", bkTree(drive, "other_src"), "restored_here", false)))
        bkAwait(found.id, "the restore of a damaged backup")
        bkAssertDesc("backup_corrupted_title", "finding damage raised no dialog", 20000L)
        bkAssertText(bkText(R.string.backup_corrupted_title, "E2E_found"), "the title")
        assertTrue(clickByDesc("backup_recreate_not_now"))
    }

    // ── On a real phone, over real USB: what the emulator cannot show ───────────────────

    /**
     * `am instrument -e backup_flow device` on a physical device with a scratch drive. It finds a
     * writable plain partition, works in a folder of its own, and leaves other jobs
     * alone: unlike the emulator flows it does not reset the app, whose jobs may be the owner's.
     */
    /**
     * Deleting a folder through the provider on a real drive, on ext4 and on exFAT. The laptop
     * checks the partitions afterwards (docs/BENCHMARK_RUNS.md, 2026-11-04). Needs a physical
     * device, so it is not an E2E case: `am instrument -e backup_flow delete_tree`, with the
     * VeraCrypt credentials of the exFAT partition as -e password, pim, cipher and hash.
     *
     * Each partition gets a tree made through the app, whose space must come back. An ext4
     * partition may also hold delete_linux, made on the laptop by Linux with hard links, symlinks,
     * a FIFO, an indexed folder and an xattr block, plus a hard link to one of its files in
     * delete_keep, which must survive. -e keep_sha256 is that file's hash from the laptop.
     */
    private fun deviceDeleteTree() {
        val tag = "OTGDevice"
        fun note(text: String) = Log.i(tag, text)
        val args = InstrumentationRegistry.getArguments()
        val cr = bkContext.contentResolver
        val keepSha = args.getString("keep_sha256", "")

        android.os.SystemClock.sleep(1000)
        findScanButton()?.click()
        device.wait(Until.findObject(By.text(java.util.regex.Pattern.compile("(?i)OK|ALLOW"))), 5000L)?.click()
        val request = OtgMasterState.mountRequest
        assertNotNull("no mount handler installed: is MainActivity running?", request)
        request!!.mount(
            args.getString("password", ""), args.getString("pim", "1").toInt(),
            args.getString("cipher", ""), args.getString("hash", ""), false,
        )
        fun writable(fs: String) = OtgMasterState.mountedDrives
            .firstOrNull { it.filesystemName == fs && !it.isReadOnly }
        // The unlock tries every partition still locked, and Argon2 is slow on all of them.
        val end = android.os.SystemClock.uptimeMillis() + 15 * 60_000L
        while ((writable("ext4") == null || writable("exFAT") == null) &&
            android.os.SystemClock.uptimeMillis() < end) android.os.SystemClock.sleep(1000)
        val drives = listOf(writable("ext4"), writable("exFAT"))
        assertTrue("no writable ext4 and exFAT: ${OtgMasterState.mountedDrives.map { "${it.name} ${it.filesystemName}" }}",
            drives.all { it != null })

        fun uri(id: String) = DocumentsContract.buildDocumentUri(AUTHORITY, id)
        fun listed(id: String): List<String> = cr.query(
            DocumentsContract.buildChildDocumentsUri(AUTHORITY, id), null, null, null, null,
        )?.use { cur ->
            val col = cur.getColumnIndex(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
            generateSequence { if (cur.moveToNext()) cur.getString(col) else null }.toList()
        } ?: fail("listing $id returned null").let { emptyList() }
        fun sha(id: String): String {
            val digest = java.security.MessageDigest.getInstance("SHA-256")
            cr.openInputStream(uri(id))!!.use { input ->
                val buf = ByteArray(1 shl 16)
                while (true) { val n = input.read(buf); if (n < 0) break; digest.update(buf, 0, n) }
            }
            return digest.digest().joinToString("") { "%02x".format(it) }
        }
        fun made(parent: android.net.Uri, mime: String, name: String): android.net.Uri =
            DocumentsContract.createDocument(cr, parent, mime, name) ?: throw AssertionError("could not create $name")

        for (d in drives.map { it!! }) {
            val root = "${d.id}:/"
            note("${d.filesystemName} ${d.name}: free ${availableBytes(bkContext, root)}")
            assertTrue("otg_delete_check is left from an earlier run", listed(root).none { it.endsWith("/otg_delete_check") })
            val freeBefore = availableBytes(bkContext, root)

            val dir = DocumentsContract.Document.MIME_TYPE_DIR
            val tree = made(uri(root), dir, "otg_delete_check")
            val many = made(tree, dir, "many")
            repeat(300) { i ->
                cr.openOutputStream(made(many, "text/plain", "file_$i.txt"))!!.use { it.write("file $i".toByteArray()) }
            }
            val deep = made(made(made(tree, dir, "sub"), dir, "deep"), dir, "deeper")
            cr.openOutputStream(made(deep, "application/octet-stream", "big.bin"))!!.use { out ->
                val chunk = ByteArray(1 shl 20)
                val random = java.util.Random(4)
                repeat(32) { random.nextBytes(chunk); out.write(chunk) }
            }
            val freeFull = availableBytes(bkContext, root)
            note("${d.filesystemName}: tree made, 300 small files and 32 MiB, free $freeFull")
            assertTrue("the tree took no space", freeFull < freeBefore - (32L shl 20))

            val t0 = android.os.SystemClock.uptimeMillis()
            assertTrue("deleting the tree failed: see the app log", DocumentsContract.deleteDocument(cr, tree))
            note("${d.filesystemName}: tree deleted in ${android.os.SystemClock.uptimeMillis() - t0} ms")
            assertTrue("otg_delete_check still listed", listed(root).none { it.endsWith("/otg_delete_check") })
            assertEquals("free space after deleting the tree, ${d.filesystemName}", freeBefore, availableBytes(bkContext, root))

            if (d.filesystemName == "ext4" && keepSha.isNotEmpty()) {
                val keep = root + "delete_keep/linked.bin"
                assertEquals("delete_keep/linked.bin before", keepSha, sha(keep))
                val freeLinux = availableBytes(bkContext, root)
                val t1 = android.os.SystemClock.uptimeMillis()
                assertTrue("deleting delete_linux failed: see the app log", DocumentsContract.deleteDocument(cr, uri(root + "delete_linux")))
                note("ext4: delete_linux deleted in ${android.os.SystemClock.uptimeMillis() - t1} ms")
                assertTrue("delete_linux still listed", listed(root).none { it.endsWith("/delete_linux") })
                assertEquals("delete_keep/linked.bin after", keepSha, sha(keep))
                val back = availableBytes(bkContext, root) - freeLinux
                note("ext4: delete_linux gave back $back bytes")
                assertTrue("delete_linux gave back only $back bytes", back >= (32L shl 20))
            }
        }

        val unmount = OtgMasterState.unmountAllRequest
        assertNotNull("no unmount handler installed", unmount)
        unmount!!.invoke()
        val gone = android.os.SystemClock.uptimeMillis() + 120_000L
        while (OtgMasterState.mountedDrives.isNotEmpty() && android.os.SystemClock.uptimeMillis() < gone) android.os.SystemClock.sleep(500)
        assertTrue("still mounted: ${OtgMasterState.mountedDrives.map { it.name }}", OtgMasterState.mountedDrives.isEmpty())
        note("all unmounted: safe to pull the drive")
    }

    private fun bkFlowDevice() {
        val tag = "OTGDevice"
        fun note(text: String) = Log.i(tag, text)
        val ctx = bkContext
        val before = bkStore.jobs.value.map { it.id }.toSet()
        val top = "otg_e2e_device"
        var drive: MountedDrive? = null
        try {
            android.os.SystemClock.sleep(1000)
            findScanButton()?.click()
            device.wait(Until.findObject(By.text(java.util.regex.Pattern.compile("(?i)OK|ALLOW"))), 5000L)?.click()
            val end = android.os.SystemClock.uptimeMillis() + 90_000L
            fun candidate() = OtgMasterState.mountedDrives.firstOrNull { it.isPlain && !it.isReadOnly && it.filesystemName in listOf("FAT32", "exFAT", "ext4", "NTFS") }
            while (candidate() == null && android.os.SystemClock.uptimeMillis() < end) android.os.SystemClock.sleep(500)
            drive = candidate()
            assertNotNull("no writable plain partition: ${OtgMasterState.mountedDrives.map { it.name }}", drive)
            val d = drive!!
            note("drive ${d.name}, key ${d.sourceDeviceName}")
            val key = app.fayaz.otgmaster.extension.ExtensionContract.parseDeviceKey(d.sourceDeviceName)
            assertNotNull("a real drive with no serial: ${d.sourceDeviceName}", key.serial)

            // A folder of its own, and a source with real data.
            val resolver = ctx.contentResolver
            fun mkdir(parent: String, name: String): String = DocumentsContract.getDocumentId(DocumentsContract.createDocument(resolver,
                DocumentsContract.buildDocumentUri(AUTHORITY, parent), DocumentsContract.Document.MIME_TYPE_DIR, name)!!)
            documentChildren(ctx, "${d.id}:/")[top]?.let(::bkDeleteTree)
            val folder = mkdir("${d.id}:/", top)
            val src = mkdir(folder, "src")
            val restored = mkdir(folder, "restored")
            assertNotNull(restored)
            val data = java.util.Random(2026).let { r -> ByteArray(32 shl 20).also(r::nextBytes) }
            val file = DocumentsContract.createDocument(resolver, DocumentsContract.buildDocumentUri(AUTHORITY, src), "application/octet-stream", "data.bin")!!
            resolver.openOutputStream(file)!!.use { it.write(data) }
            val want = java.security.MessageDigest.getInstance("SHA-256").digest(data).joinToString("") { "%02x".format(it) }
            note("source written, sha256 $want")

            // Backup, a run with nothing changed, Verify, restore by hash.
            val job = bkJob(d, "E2E_device", source = "$top/src", target = top, agoHours = 25)
            var t0 = android.os.SystemClock.uptimeMillis()
            bkExpectSuccess(bkRunNow(job, "the first backup over USB"), "the first backup over USB")
            note("backup: ${android.os.SystemClock.uptimeMillis() - t0} ms: ${bkStore.history(job.id).last().message}")
            val second = bkRunNow(job, "a second backup over USB")
            bkExpectSuccess(second, "a second backup over USB")
            assertEquals("the unchanged file read again", 1L, second.stats?.filesUnchanged)
            t0 = android.os.SystemClock.uptimeMillis()
            val verified = bkRunNow(job, "Verify over USB", BackupExecutor.Op.Check(true))
            bkExpectSuccess(verified, "Verify over USB")
            note("verify: ${android.os.SystemClock.uptimeMillis() - t0} ms: ${verified.message}")
            val snap = bkStore.snapshots(job.id).last().id
            bkExpectSuccess(bkRunNow(job, "a restore over USB", BackupExecutor.Op.Restore(snap, "", bkTree(d, "$top/restored"), "out", false)), "a restore over USB")
            assertEquals("the restored file", want, sha256(ctx, documentAt(ctx, restored, "out/data.bin")))
            note("restored file matches by hash")

            // Mount the backup through the provider, and read it back.
            returnToMainScreen()
            bkMountFromPicker(d.name, top, "E2E_device")
            val mountedId = BackupMounts.driveIdFor(job.id)
            val mountEnd = android.os.SystemClock.uptimeMillis() + 90_000L
            while (OtgMasterState.mountedDrives.none { it.id == mountedId } && android.os.SystemClock.uptimeMillis() < mountEnd) android.os.SystemClock.sleep(200)
            assertTrue("Mount did not mount the backup", OtgMasterState.mountedDrives.any { it.id == mountedId })
            val snaps = documentChildren(ctx, "$mountedId:/")
            assertEquals("snapshots on the mounted backup: ${snaps.keys}", 2, snaps.size)
            // A snapshot holds the source under its label, as `restic snapshots` shows it.
            val inSnapshot = documentChildren(ctx, snaps.values.last()).values.single()
            assertEquals("the file in the mounted backup", want, sha256(ctx, documentAt(ctx, inSnapshot, "data.bin")))
            note("mounted backup reads back by hash")
            bkUnmountBackup(mountedId, "E2E_device")

            // The dialog, with the real foreground notification.
            returnToMainScreen()
            bkHold()
            assertTrue(BackupExecutor.start(ctx, job.id, BackupExecutor.Op.Backup("manual")))
            BackupPrompts.showProgress(job.id)
            bkAssertDesc("backup_progress_hide", "no dialog on the device")
            val running = "Backing up E2E_device"
            val titleEnd = android.os.SystemClock.uptimeMillis() + 15_000L
            while (running !in bkTitles() && android.os.SystemClock.uptimeMillis() < titleEnd) android.os.SystemClock.sleep(300)
            assertTrue("no progress notification: ${bkTitles()}", running in bkTitles())
            assertTrue(clickByDesc("backup_progress_hide"))
            assertTrue("Hide stopped the run", BackupExecutor.isBusy(job.id))
            // Fired directly: the shade holds the owner's other notifications, so it stays closed here,
            // and One UI's layout differs. The emulator flows tap the real thing.
            fun notified(title: String) = ctx.getSystemService(android.app.NotificationManager::class.java)!!.activeNotifications
                .first { it.notification.extras.getCharSequence(android.app.Notification.EXTRA_TITLE)?.toString() == title }.notification
            notified(running).contentIntent.send()
            bkAssertDesc("backup_progress_title", "the notification opened no dialog", 20000L)
            assertTrue(clickByDesc("backup_progress_hide"))
            bkRelease()
            bkExpectSuccess(bkAwait(job.id, "the held backup"), "the held backup")
            BackupExecutor.clearResult(job.id)
            note("dialog and foreground notification work")

            // A sibling partition, by real serial: asks, and says the end is missing.
            val sibling = job.copy(id = java.util.UUID.randomUUID().toString(), name = "E2E_device_sibling", destinationFolder = "E2E_device_sibling", lastSuccessAt = null,
                source = job.source.copy(uri = DocumentsContract.buildTreeDocumentUri(AUTHORITY, "feedface00:/").toString()),
                destination = job.destination.copy(uri = DocumentsContract.buildTreeDocumentUri(AUTHORITY, "feedface01:/").toString()))
            bkStore.upsert(sibling)
            returnToMainScreen()
            assertTrue(BackupScheduler.evaluate(ctx, "attach").isEmpty())
            bkOpenDueFromBell("E2E_device_sibling")
            bkAssertDesc("backup_due_missing_E2E_device_sibling", "the sibling partition did not ask", 20000L)
            assertTrue(clickByDesc("backup_due_not_now"))
            bkAssertGone("backup_due_title", "Not now left the dialog up")
            BackupScheduler.forget(ctx, sibling.id)
            bkStore.delete(sibling.id)
            note("sibling partition matched by serial")

            // Due while mounted: a notification with its actions, nothing started.
            bkStore.update(job.id) { it.copy(lastSuccessAt = System.currentTimeMillis() - 25 * BackupJob.HOUR, lastReminderAt = null, snoozedUntil = null) }
            assertTrue("a USB backup started by itself", BackupScheduler.evaluate(ctx, "periodic").isEmpty())
            android.os.SystemClock.sleep(1000)
            val dueTitle = bkText(R.string.backup_due_title, BackupScheduler.driveNames(job))
            assertTrue("no due notification: ${bkTitles()}", dueTitle in bkTitles())
            val dueActions = notified(dueTitle).actions.map { it.title.toString() }
            assertEquals("the due notification's actions", listOf(bkText(R.string.backup_due_start), bkText(R.string.backup_due_later), bkText(R.string.backup_due_not_now)), dueActions)
            notified(dueTitle).actions.first { it.title.toString() == bkText(R.string.backup_due_not_now) }.actionIntent.send()
            android.os.SystemClock.sleep(1500)
            assertNotNull("Not now did not snooze", bkStore.job(job.id)!!.snoozedUntil)
            note("due notification and Not now work")

            // Later, 5 minutes, with the screen off: the case a timer alone cannot be trusted with.
            bkStore.update(job.id) { it.copy(snoozedUntil = null, lastSuccessAt = System.currentTimeMillis() - 25 * BackupJob.HOUR) }
            val runs = bkStore.history(job.id).size
            val laterAt = System.currentTimeMillis()
            BackupDelays.schedule(ctx, job.id, 5)
            device.sleep()
            val laterEnd = laterAt + 14 * 60_000L
            while (bkStore.history(job.id).size == runs && System.currentTimeMillis() < laterEnd) android.os.SystemClock.sleep(2000)
            device.wakeUp()
            android.os.SystemClock.sleep(1000)
            val later = bkAwait(job.id, "the delayed run")
            assertTrue("no delayed run with the screen off", bkStore.history(job.id).size > runs)
            bkExpectSuccess(later, "the delayed run with the screen off")
            assertEquals("delayed", later.reason)
            note("delayed start with the screen off began ${(later.startedAt - laterAt) / 1000} s after it was chosen")

            // Corrupted on real hardware, and Recreate.
            val packs = documentChildren(ctx, documentAt(ctx, folder, "E2E_device/data")).values.flatMap { documentChildren(ctx, it).values }
            assertTrue("no packs", packs.isNotEmpty())
            bkDamage(packs.first())
            val bad = bkRunNow(job, "Verify of a damaged backup", BackupExecutor.Op.Check(true))
            assertEquals("after damage: ${bad.message}", RunStatus.CORRUPTED, bad.status)
            BackupPrompts.askRecreate(null)
            BackupExecutor.clearResult(job.id)
            note("damage reads as Corrupted: ${bad.message.take(80)}")
            bkExpectSuccess(bkRunNow(job, "Recreate over USB", BackupExecutor.Op.Recreate), "Recreate over USB")
            assertNull(bkStore.job(job.id)!!.corruptedAt)
            bkExpectSuccess(bkRunNow(job, "Verify of the new backup", BackupExecutor.Op.Check(true)), "Verify of the new backup")
            note("recreate made a good backup")
            note("DEVICE FLOW PASSED")
        } catch (t: Throwable) {
            note("DEVICE FLOW FAILED: $t")
            throw t
        } finally {
            bkRelease()
            device.wakeUp()
            BackupPrompts.answered(); BackupPrompts.showProgress(null); BackupPrompts.askRecreate(null)
            bkStore.jobs.value.filter { it.id !in before }.forEach { j ->
                BackupScheduler.forget(ctx, j.id)
                BackupSecrets(ctx).forget(j.id)
                bkStore.delete(j.id)
            }
            drive?.let { d ->
                runCatching { documentChildren(ctx, "${d.id}:/")[top]?.let(::bkDeleteTree) }
                    .onFailure { note("could not remove the test folder: $it") }
            }
            note("cleaned up; jobs left: ${bkStore.jobs.value.size}, before: ${before.size}")
        }
    }

    // ── The Encrypt form says what is wrong, beside the field ───────────────────────────

    private fun bkFlowEncryptForm() {
        returnToMainScreen()
        openSlider()
        val item = device.findObject(By.desc("encrypt_action")) ?: run {
            val more = findScrolling(By.desc("more_actions_button"), desc = "more_actions_button")
            assertNotNull("neither Encrypt nor More actions is on the card", more)
            more!!.click()
            device.wait(Until.findObject(By.desc("encrypt_action")), timeout)
        }
        assertNotNull("Encrypt not on the card", item)
        item!!.click()
        assertNotNull("the form did not open", device.wait(Until.findObject(By.desc("encrypt_password")), timeout))
        fun set(desc: String, text: String) {
            // findInDialog only scrolls down, and the password fields are at the top of a long form.
            if (device.findObject(By.desc(desc)) == null) repeat(4) {
                val h = device.displayHeight
                device.swipe(device.displayWidth / 2, h / 3, device.displayWidth / 2, h * 3 / 4, 25)
            }
            bkTypePassword(desc, text)
        }
        fun confirmDoesNothing(why: String) {
            findScrolling(By.desc("encrypt_confirm_button"), desc = "encrypt_confirm_button")?.click()
            android.os.SystemClock.sleep(800)
            assertNull("Encrypt went ahead with $why", device.findObject(By.text(bkText(R.string.erase_confirm_title))))
        }
        // Going ahead is the proof a problem has cleared: a reason that went off the screen can
        // linger in the accessibility tree for a while (seen here, with the screenshot clear).
        fun confirmGoesAhead(what: String) {
            hideKeyboard()
            findScrolling(By.desc("encrypt_confirm_button"), desc = "encrypt_confirm_button")?.click()
            val dialog = device.wait(Until.findObject(By.text(bkText(R.string.erase_confirm_title))), timeout)
            if (dialog == null) captureScreen("encrypt_blocked")
            assertNotNull("Encrypt refused $what", dialog)
            device.pressBack()
            assertTrue("the erase dialog did not close", device.wait(Until.gone(By.text(bkText(R.string.erase_confirm_title))), timeout))
        }
        val tooLowFor = { min: Int -> bkText(R.string.encrypt_pim_too_low, min, 20) }
        val shortForPim = bkText(R.string.encrypt_password_short_for_pim, 20)

        // A short password with a low PIM: both fields say so.
        set("encrypt_password", "short1")
        set("encrypt_password_confirm", "short1")
        set("encrypt_pim", "484")
        set("encrypt_pim_confirm", "484")
        assertNotNull("no reason under the PIM", findScrolling(By.text(tooLowFor(485)), text = tooLowFor(485)))
        assertNotNull("no reason under the password", findScrolling(By.text(shortForPim), text = shortForPim))
        confirmDoesNothing("a PIM too low")

        // The limit itself: 485 passes, and both reasons go.
        set("encrypt_pim", "485")
        set("encrypt_pim_confirm", "485")
        confirmGoesAhead("PIM 485 with a short password")

        // Argon2's limit is its own: 11 is refused, 12 is not, where SHA-512 needs 485.
        pickOption("encrypt_kdf", "Argon2", inDialog = true)
        set("encrypt_pim", "11")
        set("encrypt_pim_confirm", "11")
        assertNotNull("no reason for Argon2 PIM 11", findScrolling(By.text(tooLowFor(12)), text = tooLowFor(12)))
        confirmDoesNothing("Argon2 PIM 11")
        set("encrypt_pim", "12")
        set("encrypt_pim_confirm", "12")
        confirmGoesAhead("Argon2 PIM 12 with a short password")

        // A long password needs no such PIM.
        set("encrypt_password", "a-password-of-twenty-chars")
        set("encrypt_password_confirm", "a-password-of-twenty-chars")
        set("encrypt_pim", "1")
        set("encrypt_pim_confirm", "1")
        confirmGoesAhead("PIM 1 with a long password")

        // What differs says so under the field that differs.
        set("encrypt_password_confirm", "something-else")
        val differ = bkText(R.string.encrypt_passwords_differ)
        assertNotNull("no reason under the confirm field", findScrolling(By.text(differ), text = differ))
        confirmDoesNothing("passwords that differ")
        set("encrypt_password_confirm", "a-password-of-twenty-chars")
        set("encrypt_pim_confirm", "2")
        val pimsDiffer = bkText(R.string.encrypt_pims_differ)
        assertNotNull("no reason under the PIM confirm field", findScrolling(By.text(pimsDiffer), text = pimsDiffer))
        confirmDoesNothing("PIMs that differ")
        device.pressBack()
    }

    // ── The drive is pulled mid-run ─────────────────────────────────────────────────────

    private fun bkFlowInterrupted(drive: MountedDrive) {
        bkShell("dd if=/dev/urandom of=$PHONE/src/interrupted.bin bs=1M count=24 2>/dev/null")
        // Slowed at each file, so the drive goes while files are still going onto it.
        BackupExecutor.testProgressDelayMs = 4000
        val job = bkCreate(BkEnd(bkPhoneRoot, listOf("Download", "otg_e2e", "src")), BkEnd(drive.name, emptyList()), "E2E_cut", incremental = true)
        assertTrue(clickByDesc("backup_progress_hide"))
        val end = android.os.SystemClock.uptimeMillis() + 120_000L
        while (BackupExecutor.runs.value[job.id]?.phase.orEmpty().let { !it.contains(" of ") } && android.os.SystemClock.uptimeMillis() < end) android.os.SystemClock.sleep(100)
        assertTrue("the run ended before the drive was pulled", BackupExecutor.isBusy(job.id))
        bkPullDrive(drive)
        BackupExecutor.testProgressDelayMs = 0
        val run = bkAwait(job.id, "the interrupted run")
        assertTrue("a pulled drive did not read as a failure: ${run.status} ${run.message}", run.status == RunStatus.FAILED || run.status == RunStatus.UNAVAILABLE)
        assertNull("a pulled drive was called Corrupted", bkStore.job(job.id)!!.corruptedAt)
        BackupExecutor.clearResult(job.id)

        // Back in: it asks, the run completes on what is there, and every blob reads.
        val back = bkReplugDrive()
        bkOpenDueFromBell(job.name)
        android.os.SystemClock.sleep(2500)
        assertTrue(clickByDesc("backup_due_start"))
        bkCloseProgress()
        val second = bkStore.history(job.id).last()
        bkExpectSuccess(second, "the run after the drive came back")
        bkExpectSuccess(bkRunNow(job, "Verify after the interruption", BackupExecutor.Op.Check(true)), "Verify after the interruption")
        assertNull("called Corrupted afterwards", bkStore.job(job.id)!!.corruptedAt)
        assertNotNull(back)
    }

    /** Mount backup, on the main screen under Manage backups. Goes to the Files picker. */
    private fun clickMountBackup() {
        returnToMainScreen()
        if (device.findObject(By.desc("backups_open_mount")) == null) clickScrolling("manage_backups_button", "Manage backups missing")
        clickScrolling("backups_open_mount", "Mount backup missing under Manage backups")
    }

    /** Opens a part of Manage backups from the main screen. */
    private fun openBackupsTab(desc: String) {
        if (device.findObject(By.desc(desc)) == null) clickScrolling("manage_backups_button", "Manage backups missing")
        clickScrolling(desc, "$desc missing under Manage backups")
        assertNotNull("Manage backups did not open", device.wait(Until.findObject(By.desc("backups_tab_history")), 30000L))
    }

    /** Mount backup: picks [folder] at the top of the drive titled [rootTitle] in the Files picker. */
    private fun browseToFolder(rootTitle: String, folder: String) {
        val documentsUi = "com.google.android.documentsui"
        clickMountBackup()
        assertTrue("the Files picker did not open", device.wait(Until.hasObject(By.pkg(documentsUi)), timeout))
        android.os.SystemClock.sleep(1000)
        fun step(what: String, selector: androidx.test.uiautomator.BySelector, waitMs: Long = 20000L) {
            val obj = device.wait(Until.findObject(selector), waitMs)
            if (obj == null) captureScreen("picker_no_$what")
            assertNotNull("$what not in the Files picker", obj)
            obj!!.click()
            android.os.SystemClock.sleep(500)
        }
        // The picker reopens where it was last, so go through the drawer every time. A tap
        // while the drawer slides in only closes it, leaving the picker on the phone's own
        // storage, so wait for it to settle and open it again if the folder does not show.
        var row: androidx.test.uiautomator.UiObject2? = null
        for (attempt in 1..3) {
            (device.findObject(By.descContains("Show roots")) ?: device.findObject(By.descContains("Open navigation drawer")))?.click()
            if (device.wait(Until.hasObject(By.text(rootTitle)), 20000L) != true) captureScreen("picker_no_drive")
            android.os.SystemClock.sleep(1000)
            device.findObject(By.text(rootTitle))?.click()
            row = device.wait(Until.findObject(By.text(folder)), 8000L)
            if (row != null) break
        }
        if (row == null) captureScreen("picker_no_folder")
        assertNotNull("$folder not in the Files picker under $rootTitle", row)
        row!!.click()
        android.os.SystemClock.sleep(500)
        step("use_this_folder", By.text(java.util.regex.Pattern.compile("(?i)use this folder")))
        step("allow", By.text(java.util.regex.Pattern.compile("(?i)allow")))
        assertTrue("the Files picker did not return to the app",
            device.wait(Until.hasObject(By.pkg("app.fayaz.otgmaster")), timeout))
    }

    /** With the password dialog up: types [password] and returns the backup it mounts. */
    private fun mountBrowsedBackup(password: String): MountedDrive {
        typeInto("backups_browse_password", password)
        clickInDialog("backups_browse_mount")
        if (!device.wait(Until.gone(By.desc("backups_browse_mount")), 60000L)) captureScreen("backup_password_dialog_stayed")
        val drive = OtgMasterState.mountedDrives.firstOrNull { it.id.startsWith("bk") }
        if (drive == null) captureScreen("backup_not_mounted")
        assertNotNull("the backup did not mount with its password", drive)
        return drive!!
    }

    /** Backs out of Manage backups or the Files app, to the main screen. */
    private fun returnToMainScreen() {
        repeat(4) {
            if (device.wait(Until.findObject(By.desc("scan_button")), 1500L) != null) return
            device.pressBack()
        }
        val scan = device.wait(Until.findObject(By.desc("scan_button")), timeout)
        if (scan == null) captureScreen("backup_not_back_on_main")
        assertNotNull("did not get back to the main screen", scan)
    }

    /**
     * The Files app, another app with its own identity, opens the card titled [title] with the
     * card's own button, lists the backup and goes into a snapshot. Its calls used to be
     * checked against an app with no grant on the repository: "Can't load content".
     */
    private fun openBackupInFiles(context: Context, title: String, snapshotId: String) {
        openSlider("mounted_tab_backups")
        val label = context.getString(R.string.open_files_app)
        fun button(): androidx.test.uiautomator.UiObject2? {
            val top = device.findObject(By.text(title))?.visibleBounds?.bottom ?: return null
            return device.findObjects(By.text(label)).firstOrNull { it.visibleBounds.top > top }
        }
        val open = button() ?: run {
            device.findObject(By.scrollable(true).pkg("app.fayaz.otgmaster"))
                ?.scroll(androidx.test.uiautomator.Direction.DOWN, 0.4f)
            button()
        }
        if (open == null) captureScreen("backup_no_open_files")
        assertNotNull("Open Files App not found on the backup's card", open)
        open!!.click()
        val row = device.wait(Until.findObject(By.textContains("($snapshotId)")), 30000L)
        if (row == null) captureScreen("backup_files_no_listing")
        assertNotNull("the Files app did not list snapshot $snapshotId", row)
        assertNull("the Files app could not load the backup", device.findObject(By.textContains("load content")))
        row!!.click()
        val file = device.wait(Until.findObject(By.text("flower.jpg")), 30000L)
        if (file == null) captureScreen("backup_files_no_snapshot")
        assertNotNull("the Files app did not open the snapshot", file)
    }

    /** Name → document id of a folder's children. */
    private fun documentChildren(context: Context, docId: String): Map<String, String> {
        val found = linkedMapOf<String, String>()
        context.contentResolver.query(DocumentsContract.buildChildDocumentsUri(AUTHORITY, docId), null, null, null, null)
            ?.use { cur ->
                val idCol = cur.getColumnIndex(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
                val nameCol = cur.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
                while (cur.moveToNext()) found[cur.getString(nameCol)] = cur.getString(idCol)
            }
        return found
    }

    /** The document at [path] under folder [docId], one folder listing at a time, as an app walks it. */
    private fun documentAt(context: Context, docId: String, path: String): String =
        path.split('/').fold(docId) { folder, name ->
            documentChildren(context, folder)[name] ?: throw AssertionError("$name not found on the way to $path")
        }

    private fun sha256(context: Context, docId: String): String {
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        val stream = context.contentResolver.openInputStream(DocumentsContract.buildDocumentUri(AUTHORITY, docId))
        assertNotNull("could not open $docId", stream)
        stream!!.use { input ->
            val buffer = ByteArray(1 shl 16)
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                digest.update(buffer, 0, n)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun sha256Range(context: Context, docId: String, offset: Long, length: Int): String {
        val pfd = context.contentResolver.openFileDescriptor(DocumentsContract.buildDocumentUri(AUTHORITY, docId), "r")
        assertNotNull("could not open $docId", pfd)
        val bytes = ByteArray(length)
        pfd!!.use { fd ->
            java.io.FileInputStream(fd.fileDescriptor).use { input ->
                input.channel.position(offset)
                var read = 0
                while (read < length) {
                    val n = input.read(bytes, read, length - read)
                    if (n < 0) break
                    read += n
                }
                assertEquals("bytes read from offset $offset of $docId", length, read)
            }
        }
        return java.security.MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    }

    /**
     * An ext4 drive whose journal needs recovery (testdata/ext4_journal, made by
     * scripts/make_journal_fixture.py; docs/TEST_DATA.md §6e). It mounts read-only and the
     * repair dialog opens on its own. Before the repair, journal_replayed.txt reads "no"
     * and nothing can be created; "Keep read-only" leaves a Repair button on the card;
     * after the repair the same file reads "yes" — through the block cache that already
     * held the old block — and a new file goes in and reads back. The host then checks
     * the drive (scripts/verify_e2e_volume.py, which sees journal.txt).
     */
    private fun runJournalRepairCase() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val title = "Repair this drive?"
        // A plain drive mounts when the app starts, and the dialog with it. Scan only if
        // it has not: a tap on Scan behind the dialog lands outside it and dismisses it.
        if (device.wait(Until.findObject(By.text(title)), 20000L) == null) {
            (device.wait(Until.findObject(By.desc("scan_button").clickable(true)), timeout) ?: findScanButton())?.click()
            device.wait(Until.findObject(By.text(java.util.regex.Pattern.compile("(?i)OK|ALLOW"))), 5000L)?.click()
        }
        if (device.wait(Until.findObject(By.text(title)), 60000L) == null) captureScreen("no_repair_dialog")
        assertNotNull("the repair dialog did not open for a drive whose journal needs recovery",
            device.findObject(By.text(title)))

        assertEquals("journal_replayed.txt before the repair", "REPLAYED: no \n",
            readRootFile(context, "journal_replayed.txt"))
        val rootUri = DocumentsContract.buildDocumentUri(AUTHORITY, getRootDocId(context)!!)
        assertNull("a file was created on the volume before its journal was replayed",
            runCatching { DocumentsContract.createDocument(context.contentResolver, rootUri, "text/plain", "must_not_exist.txt") }
                .getOrNull())

        // Declining keeps it read-only, with the repair still on the card.
        device.findObject(By.text("Keep read-only"))!!.click()
        assertTrue("the repair dialog did not close", device.wait(Until.gone(By.text(title)), timeout))
        openSlider()
        assertNotNull("READ-ONLY tag missing before the repair", device.wait(Until.findObject(By.text("READ-ONLY")), timeout))
        // A read-only drive can be a backup's source, not its target: the form says so.
        openBackupsTab("backups_open_schedules")
        assertTrue("Create reminder missing", clickByDesc("backups_create_schedule"))
        assertNotNull("the form did not open", device.wait(Until.findObject(By.desc("backup_name")), timeout))
        val readOnlyNote = findScrolling(By.text(context.getString(R.string.backup_target_read_only)), text = context.getString(R.string.backup_target_read_only))
        if (readOnlyNote == null) captureScreen("no_read_only_note")
        assertNotNull("the form does not say a read-only drive cannot be the target", readOnlyNote)
        device.pressBack()
        returnToMainScreen()
        clickScrolling("journal_repair_button", "no Repair button on the card of a drive that needs one")
        assertNotNull("the Repair button did not open the dialog", device.wait(Until.findObject(By.text(title)), timeout))
        assertTrue("Repair could not be confirmed", clickByDesc("journal_repair_confirm"))

        if (!device.wait(Until.gone(By.text("READ-ONLY")), 60000L)) captureScreen("still_read_only")
        assertNull("READ-ONLY tag still shown after the repair", device.findObject(By.text("READ-ONLY")))
        assertNull("Repair still offered after the repair", device.findObject(By.desc("journal_repair_button")))
        assertEquals("journal_replayed.txt after the repair", "REPLAYED: yes\n",
            readRootFile(context, "journal_replayed.txt"))

        val content = "OTGMaster wrote this after the journal repair"
        val uri = DocumentsContract.createDocument(context.contentResolver, rootUri, "text/plain", "after_repair.txt")
        assertNotNull("createDocument(after_repair.txt) failed after the repair", uri)
        context.contentResolver.openOutputStream(uri!!)?.use { it.write(content.toByteArray()) }
            ?: fail("openOutputStream(after_repair.txt) returned null")
        assertEquals(content, context.contentResolver.openInputStream(uri)?.use { it.readBytes().toString(Charsets.UTF_8) })
    }

    /** The text of [name] in the first root, read through the provider. */
    private fun readRootFile(context: Context, name: String): String {
        val id = listRoot(context).firstOrNull { it.endsWith("/$name") || it.endsWith(name) }
        assertNotNull("$name missing from the root: ${listRoot(context)}", id)
        return context.contentResolver.openInputStream(DocumentsContract.buildDocumentUri(AUTHORITY, id!!))
            ?.use { it.readBytes().toString(Charsets.UTF_8) } ?: ""
    }

    private fun clickScrolling(desc: String, missing: String) {
        val obj = findScrolling(By.desc(desc), desc = desc)
        assertNotNull(missing, obj)
        obj!!.click()
    }

    /** Taps Scan and unlocks and mounts the volume. */
    private fun scanAndMount(password: String, pim: String, testCase: String, context: String, beforeMount: () -> Unit = {}) {
        scan(context)
        assertTrue("the volume did not mount $context",
            doMount(password, pim, testCase, clearFields = true, beforeMount = beforeMount))
    }

    private fun scan(context: String) {
        val scan = findScanButton()
        assertNotNull("Scan button not found ($context)", scan)
        scan!!.click()
        device.wait(Until.findObject(By.text(java.util.regex.Pattern.compile("(?i)Allow|OK"))), 3000L)?.click()
    }

    // ── Eject and USB access (testdata/eject_*, testdata/usb_mode_*, scripts/make_eject_fixtures.py) ──

    private fun ejMount(context: String = "to eject"): MountedDrive {
        val a = InstrumentationRegistry.getArguments()
        if (a.getString("plain_mount", "false") == "true") {
            scan(context)
            if (waitForMounted(60000L) == null) captureScreen("no_plain_mount")
        } else {
            scanAndMount(a.getString("password", ""), a.getString("pim", ""), a.getString("testCase", ""), context)
        }
        val d = OtgMasterState.mountedDrives.firstOrNull()
        assertNotNull("the drive did not mount ($context)", d)
        return d!!
    }

    private fun ejTapEject() {
        android.os.SystemClock.sleep(UNMOUNT_SETTLE_MS)
        val eject = findInSlider(By.desc("eject_button"))
        if (eject == null) captureScreen("no_eject_button")
        assertNotNull("no Eject on the drive card", eject)
        eject!!.click()
    }

    private fun ejAwaitGone(ms: Long = 30_000L) {
        val end = android.os.SystemClock.uptimeMillis() + ms
        while (OtgMasterState.mountedDrives.isNotEmpty() && android.os.SystemClock.uptimeMillis() < end) android.os.SystemClock.sleep(200)
        if (OtgMasterState.mountedDrives.isNotEmpty()) captureScreen("not_ejected")
        assertTrue("a partition is still mounted after Eject", OtgMasterState.mountedDrives.isEmpty())
        assertTrue("the Eject is still pending", OtgMasterState.pendingEjects.value.isEmpty())
    }

    private fun ejAwaitMessage(resId: Int) {
        val text = bkContext.getString(resId).replace("\\'", "'")
        val banner = device.wait(Until.findObject(By.textContains(text.take(30))), 30_000L)
        if (banner == null) captureScreen("no_eject_message")
        assertNotNull("no message after Eject: $text", banner)
    }

    private fun ejAwaitNotification() {
        val posted = (0 until 40).any {
            android.os.SystemClock.sleep(250)
            bkTitles().any { t -> t.endsWith(" ejected") }
        }
        assertTrue("no notification after a dismissed Eject: ${bkTitles()}", posted)
    }

    private fun ejAssertNoNotification() {
        android.os.SystemClock.sleep(1000)
        assertFalse("a notification though the dialog stayed up: ${bkTitles()}", bkTitles().any { it.endsWith(" ejected") })
    }

    /** Over a relaunch, which runs the same refresh a resume does: nothing opens the drive again. */
    private fun ejAssertLeftAlone() {
        closeSlider()
        relaunchApp()
        android.os.SystemClock.sleep(5000)
        assertNull("an ejected drive was offered for unlocking", device.findObject(By.desc("password_input")))
        assertTrue("an ejected drive was mounted", OtgMasterState.mountedDrives.isEmpty())
        assertTrue("the app forgot the drive was ejected", OtgMasterState.ejectedDevices.isNotEmpty())
    }

    /** Opens a file on [drive] through the provider and holds it, as a copy in Files would. */
    private fun ejHoldFile(drive: MountedDrive, name: String = "flower.jpg"): android.os.ParcelFileDescriptor {
        val uri = DocumentsContract.buildDocumentUri(AUTHORITY, "${drive.id}:/$name")
        return bkContext.contentResolver.openFileDescriptor(uri, "r")!!
    }

    private fun ejAssertBusyDialog() {
        val d = device.wait(Until.findObject(By.desc("eject_busy_dismiss")), timeout)
        if (d == null) captureScreen("no_busy_dialog")
        assertNotNull("no dialog with a file open", d)
        assertNotNull("the dialog's title", device.findObject(By.text("Data transfer in progress")))
        assertNotNull("the dialog's text", device.findObject(By.text("Will eject once the transfer is complete.")))
        assertEquals("it did not stay mounted", 1, OtgMasterState.mountedDrives.size)
    }

    private fun ejAssertStaysMounted(ms: Long = 5000L) {
        android.os.SystemClock.sleep(ms)
        assertEquals("it ejected", 1, OtgMasterState.mountedDrives.size)
    }

    // ── Report issue (testdata/report_issue, scripts/check_github_issue.py) ──

    /**
     * Report issue, the review screen, the web form in Chrome, then Submit. The runner makes
     * [marker] up for each attempt. After this test it looks for that string in the issue
     * GitHub received, and deletes the issue.
     */
    private fun runReportIssue(marker: String) {
        assertTrue("the runner gave no issue_marker", marker.isNotEmpty())
        ejMount("to report")
        val report = findScrolling(By.text(usbText(R.string.feedback_report_issue)))
        assertNotNull("no Report issue button", report)
        report!!.click()
        val open = findScrolling(By.text(usbText(R.string.feedback_open)), firstWaitMs = 10_000L)
        assertNotNull("no button to open the form", open)
        open!!.click()

        val description = webOnScreen("feedback-text")
        description.text = "E2E test of Report issue, please ignore. $marker"
        assertTrue("the description did not take the text", webField("feedback-text").text.orEmpty().contains(marker))
        webOnScreen("submit-btn").click()
        val done = device.wait(Until.findObject(By.textContains("Feedback submitted successfully")), 60_000L)
        if (done == null) captureScreen("report_issue_submit")
        assertNotNull("the form did not say the report was submitted", done)
        device.executeShellCommand("am force-stop com.android.chrome")
    }

    /** An element of the web form by its HTML id, past any Chrome first-run screen. */
    private fun webField(id: String, ms: Long = 90_000L): androidx.test.uiautomator.UiObject2 {
        val firstRun = java.util.regex.Pattern.compile("(?i)Use without an account|No thanks|Accept & continue|Got it")
        val end = android.os.SystemClock.uptimeMillis() + ms
        while (android.os.SystemClock.uptimeMillis() < end) {
            device.findObject(By.res(id))?.let { return it }
            device.findObject(By.pkg("com.android.chrome").text(firstRun))?.click()
            android.os.SystemClock.sleep(1000)
        }
        captureScreen("report_issue_$id")
        throw AssertionError("the web form has no $id")
    }

    /**
     * Scrolls the page until the element can be tapped. Chrome only exposes elements near the
     * screen, so one further down is not in the tree until the page has moved. The swipe is in
     * the left margin, because on a text box it would scroll the box, and slow, because a fling
     * can carry the page past the element. It goes down the page and then back up, since the
     * tree can lag a fresh page and the first pass can pass the element by.
     */
    private fun webOnScreen(id: String): androidx.test.uiautomator.UiObject2 {
        webField("feedback-form")
        val x = device.displayWidth / 12
        val low = device.displayHeight * 3 / 4
        val high = device.displayHeight * 2 / 5
        fun tappable(): androidx.test.uiautomator.UiObject2? {
            android.os.SystemClock.sleep(800)
            return device.findObject(By.res(id))?.takeIf {
                it.visibleBounds.height() >= 40 && it.visibleBounds.bottom < device.displayHeight - 150
            }
        }
        device.wait(Until.findObject(By.res(id)), 5_000L)
        tappable()?.let { return it }
        repeat(14) {
            device.swipe(x, low, x, high, 120)
            tappable()?.let { return it }
        }
        repeat(14) {
            device.swipe(x, high, x, low, 120)
            tappable()?.let { return it }
        }
        captureScreen("report_issue_scroll_$id")
        throw AssertionError("could not scroll $id onto the screen")
    }

    private fun usbText(id: Int): java.util.regex.Pattern =
        java.util.regex.Pattern.compile(java.util.regex.Pattern.quote(bkContext.getString(id)))

    private fun usbAwaitHint(id: Int, ms: Long = 15_000L) {
        val hint = device.wait(Until.findObject(By.text(usbText(id))), ms)
        if (hint == null) captureScreen("no_usb_hint")
        assertNotNull("no hint: ${bkContext.getString(id)}", hint)
    }

    private fun usbAssertNotOpened(what: String) {
        assertNull("$what: the unlock form is up", device.findObject(By.desc("password_input")))
        assertTrue("$what: something was mounted", OtgMasterState.mountedDrives.isEmpty())
    }

    private fun usbPickInSettings(value: String) {
        val prefs = bkContext.getSharedPreferences("otgmaster_prefs", Context.MODE_PRIVATE)
        device.wait(Until.findObject(By.desc(bkContext.getString(R.string.cd_settings))), timeout)!!.click()
        val row = device.wait(Until.findObject(By.res("settings_row_usb_access")), timeout)
        if (row == null) captureScreen("no_settings_row_usb_access")
        assertNotNull("no USB access row in Settings", row)
        row!!.click()
        assertNotNull("USB access opened no dialog", device.wait(Until.findObject(By.desc("settings_dialog_usb_access")), timeout))
        assertTrue(clickByDesc("usb_access_$value"))
        android.os.SystemClock.sleep(500)
        assertEquals("the option did not change the setting", value, prefs.getString("usb_access_mode", null))
        assertTrue(clickByDesc("settings_dialog_close"))
        assertTrue("Close left the dialog up", device.wait(Until.gone(By.desc("settings_dialog_usb_access")), timeout))
        device.findObject(By.desc(bkContext.getString(R.string.cd_close_settings))).click()
        assertTrue("Settings stayed open", device.wait(Until.gone(By.res("settings_row_theme")), timeout))
    }

    private fun umTapUnmount() {
        android.os.SystemClock.sleep(UNMOUNT_SETTLE_MS)
        val unmount = findInSlider(By.desc("unmount_button"))
        if (unmount == null) captureScreen("no_unmount_button")
        assertNotNull("no Unmount on the drive card", unmount)
        unmount!!.click()
    }

    private fun umAssertBusyDialog() {
        val d = device.wait(Until.findObject(By.desc("unmount_busy_dismiss")), timeout)
        if (d == null) captureScreen("no_unmount_busy_dialog")
        assertNotNull("no dialog with a file open", d)
        assertNotNull("the dialog's title", device.findObject(By.text("Data transfer in progress")))
        assertNotNull("the dialog's text", device.findObject(By.text("Will unmount once the transfer is complete.")))
        assertEquals("it did not stay mounted", 1, OtgMasterState.mountedDrives.size)
    }

    private fun umAwaitUnmounted(ms: Long = 30_000L) {
        val end = android.os.SystemClock.uptimeMillis() + ms
        while (OtgMasterState.mountedDrives.isNotEmpty() && android.os.SystemClock.uptimeMillis() < end) android.os.SystemClock.sleep(200)
        if (OtgMasterState.mountedDrives.isNotEmpty()) captureScreen("not_unmounted")
        assertTrue("still mounted after the file closed", OtgMasterState.mountedDrives.isEmpty())
        assertTrue("the Unmount is still pending", OtgMasterState.pendingUnmounts.value.isEmpty())
        assertTrue("Unmount ejected the drive", OtgMasterState.ejectedDevices.isEmpty())
    }

    private fun umNotified(): Boolean = bkTitles().any { it.endsWith(" unmounted") }

    private fun runUnmountFlow(flow: String) {
        device.executeShellCommand("pm grant app.fayaz.otgmaster android.permission.POST_NOTIFICATIONS")
        android.os.SystemClock.sleep(1000)
        when (flow) {
            // Nothing open: it unmounts straight away, with no dialog.
            "unmount_idle" -> {
                ejMount("to unmount")
                umTapUnmount()
                assertNull("a transfer dialog for an idle partition", device.findObject(By.desc("unmount_busy_dismiss")))
                umAwaitUnmounted()
            }
            // The dialog is left up: it closes by itself and there is no notification.
            "unmount_busy_wait" -> {
                val drive = ejMount("to unmount")
                val file = ejHoldFile(drive)
                try {
                    umTapUnmount()
                    umAssertBusyDialog()
                    ejAssertStaysMounted()
                    assertNotNull("the dialog went away by itself", device.findObject(By.desc("unmount_busy_dismiss")))
                } finally { file.close() }
                assertTrue("the dialog did not close after the Unmount", device.wait(Until.gone(By.desc("unmount_busy_dismiss")), 30_000L))
                umAwaitUnmounted()
                android.os.SystemClock.sleep(1000)
                assertFalse("a notification though the dialog stayed up: ${bkTitles()}", umNotified())
            }
            "unmount_busy_dismiss" -> {
                val drive = ejMount("to unmount")
                val file = ejHoldFile(drive)
                try {
                    umTapUnmount()
                    umAssertBusyDialog()
                    assertTrue(clickByDesc("unmount_busy_dismiss"))
                    assertTrue("Dismiss left the dialog up", device.wait(Until.gone(By.desc("unmount_busy_dismiss")), timeout))
                    assertNotNull("the card does not say it is waiting", findInSlider(By.desc("unmount_pending")))
                    assertNull("Unmount offered while waiting", device.findObject(By.desc("unmount_button")))
                    assertNull("Eject offered while waiting", device.findObject(By.desc("eject_button")))
                    assertNull("More actions offered while waiting", device.findObject(By.desc("more_actions_button")))
                    ejAssertStaysMounted()
                    assertTrue("it forgot the Unmount", OtgMasterState.pendingUnmounts.value.isNotEmpty())
                } finally { file.close() }
                umAwaitUnmounted()
                val posted = (0 until 40).any {
                    android.os.SystemClock.sleep(250)
                    umNotified()
                }
                assertTrue("no notification after a dismissed Unmount: ${bkTitles()}", posted)
            }
            "unmount_busy_cancel" -> {
                val drive = ejMount("to unmount")
                val file = ejHoldFile(drive)
                try {
                    umTapUnmount()
                    umAssertBusyDialog()
                    assertTrue(clickByDesc("unmount_busy_cancel"))
                    assertTrue("Cancel unmount left the dialog up", device.wait(Until.gone(By.desc("unmount_busy_cancel")), timeout))
                    assertTrue("still pending", OtgMasterState.pendingUnmounts.value.isEmpty())
                } finally { file.close() }
                ejAssertStaysMounted(6000L)
                assertNotNull("Unmount is not back", findInSlider(By.desc("unmount_button")))
                doUnmount()
            }
            "unmount_card_cancel" -> {
                val drive = ejMount("to unmount")
                val file = ejHoldFile(drive)
                try {
                    umTapUnmount()
                    umAssertBusyDialog()
                    assertTrue(clickByDesc("unmount_busy_dismiss"))
                    assertNotNull("no Cancel unmount on the card", findInSlider(By.desc("unmount_card_cancel")))
                    assertTrue(clickByDesc("unmount_card_cancel"))
                    assertTrue("the card still says it is waiting", device.wait(Until.gone(By.desc("unmount_pending")), timeout))
                    assertTrue("still pending", OtgMasterState.pendingUnmounts.value.isEmpty())
                } finally { file.close() }
                ejAssertStaysMounted(6000L)
                doUnmount()
            }
            // A copy writing to the partition when Unmount is tapped, as Files would make one:
            // Unmount waits for it, the copy completes, and every byte of it is there after a remount.
            "unmount_during_copy" -> {
                val cr = bkContext.contentResolver
                val drive = ejMount("to copy to")
                val name = "copy_during_unmount.bin"
                val uri = DocumentsContract.createDocument(cr,
                    DocumentsContract.buildDocumentUri(AUTHORITY, "${drive.id}:/"), "application/octet-stream", name)
                assertNotNull("createDocument($name) returned null", uri)
                val md = java.security.MessageDigest.getInstance("SHA-256")
                var copyError: Throwable? = null
                val chunks = 64   // 4 MiB in 64 KiB writes, paced to last about 10 s
                val copy = Thread {
                    try {
                        cr.openOutputStream(uri!!)!!.use { out ->
                            val rnd = java.util.Random(52)
                            val buf = ByteArray(64 * 1024)
                            repeat(chunks) {
                                rnd.nextBytes(buf)
                                out.write(buf)
                                md.update(buf)
                                android.os.SystemClock.sleep(150)
                            }
                        }
                    } catch (t: Throwable) { copyError = t }
                }
                copy.start()
                android.os.SystemClock.sleep(1500)
                assertTrue("the copy ended before Unmount was tapped: $copyError", copy.isAlive)
                umTapUnmount()
                umAssertBusyDialog()
                assertTrue("the copy was still going when the dialog came up", copy.isAlive)
                copy.join(120_000L)
                assertFalse("the copy did not finish", copy.isAlive)
                assertNull("the copy failed: $copyError", copyError)
                val expected = md.digest().joinToString("") { "%02x".format(it) }
                assertTrue("the dialog did not close after the Unmount", device.wait(Until.gone(By.desc("unmount_busy_dismiss")), 30_000L))
                umAwaitUnmounted()

                val again = ejMount("after the copy")
                val got = java.security.MessageDigest.getInstance("SHA-256").let { d ->
                    cr.openInputStream(DocumentsContract.buildDocumentUri(AUTHORITY, "${again.id}:/$name"))!!.use { input ->
                        val buf = ByteArray(64 * 1024)
                        var total = 0L
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            d.update(buf, 0, n)
                            total += n
                        }
                        assertEquals("the copied file's size after the remount", chunks * 64L * 1024L, total)
                    }
                    d.digest().joinToString("") { "%02x".format(it) }
                }
                assertEquals("the copied file changed across the Unmount", expected, got)
                doUnmount()
            }
            else -> fail("unknown unmount flow $flow")
        }
    }

    /**
     * slider_hint: the first time the slider closes with a drive mounted, a hint points at the
     * Drives icon; a tap dismisses it, and it does not come back.
     * notification_slider: a tap on the mounted-drives notification opens the app on the slider.
     */
    private fun runSliderFlow(flow: String) {
        device.executeShellCommand("pm grant app.fayaz.otgmaster android.permission.POST_NOTIFICATIONS")
        android.os.SystemClock.sleep(1000)
        val prefs = InstrumentationRegistry.getInstrumentation().targetContext
            .getSharedPreferences("otgmaster_prefs", Context.MODE_PRIVATE)
        val drive = ejMount("for the slider")
        // A new mount opens the slider by itself.
        assertTrue("the slider did not open on the mount", device.wait(Until.hasObject(By.desc("mounted_drawer")), timeout))
        when (flow) {
            "slider_hint" -> {
                assertNull("the hint showed while the slider was open", device.findObject(By.desc("slider_hint")))
                device.pressBack()
                assertTrue("Back did not close the slider", device.wait(Until.gone(By.desc("mounted_drawer")), timeout))
                val hint = device.wait(Until.findObject(By.desc("slider_hint")), 5000L)
                if (hint == null) captureScreen("no_slider_hint")
                assertNotNull("no hint at the Drives icon after the slider closed", hint)
                assertNotNull("the hint's text", device.findObject(By.text("Tap here or swipe left for mounted devices")))
                // It points at the Drives icon: its arrow sits under the icon, the bubble below the bar.
                val icon = device.findObject(By.descStartsWith("Mounted:"))
                assertNotNull("no Drives icon", icon)
                assertTrue("the hint is not below the Drives icon", hint!!.visibleBounds.top >= icon!!.visibleBounds.bottom)
                assertTrue("it was not remembered as shown", prefs.getBoolean(MainActivity.PREF_SLIDER_HINT_SEEN, false))
                hint.click()
                assertTrue("a tap did not dismiss the hint", device.wait(Until.gone(By.desc("slider_hint")), timeout))
                // The first-close hint is once only: open and close the slider again.
                openSlider()
                closeSlider()
                android.os.SystemClock.sleep(1500)
                assertNull("the hint came back", device.findObject(By.desc("slider_hint")))
                // Opening the app with a drive mounted shows it again, every time.
                repeat(2) { round ->
                    InstrumentationRegistry.getInstrumentation().targetContext.startActivity(mainActivityIntent()
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
                    assertTrue("the app did not open", device.wait(Until.hasObject(By.pkg("app.fayaz.otgmaster").depth(0)), timeout))
                    val opened = android.os.SystemClock.uptimeMillis()
                    // Not straight away: it waits SLIDER_HINT_OPEN_DELAY_MS.
                    android.os.SystemClock.sleep(SLIDER_HINT_OPEN_DELAY_MS / 2)
                    assertNull("the hint came up before its delay", device.findObject(By.desc("slider_hint")))
                    val again = device.wait(Until.findObject(By.desc("slider_hint")), SLIDER_HINT_OPEN_DELAY_MS + 10_000L)
                    val after = android.os.SystemClock.uptimeMillis() - opened
                    assertTrue("the hint came up ${after} ms after the app opened, before its delay",
                        again == null || after >= SLIDER_HINT_OPEN_DELAY_MS - 1000L)
                    if (again == null) captureScreen("no_slider_hint_on_open_$round")
                    assertNotNull("no hint when the app was opened with a drive mounted (open ${round + 1})", again)
                    assertNull("the slider opened by itself", device.findObject(By.desc("mounted_drawer")))
                    again!!.click()
                    assertTrue("a tap did not dismiss the hint", device.wait(Until.gone(By.desc("slider_hint")), timeout))
                }
                // "Don't show again" turns it off for good.
                InstrumentationRegistry.getInstrumentation().targetContext.startActivity(mainActivityIntent()
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
                assertNotNull("no hint before Don't show again",
                    device.wait(Until.findObject(By.desc("slider_hint")), SLIDER_HINT_OPEN_DELAY_MS + 10_000L))
                assertNotNull("no Don't show again on the hint", device.findObject(By.text("Don't show again")))
                assertTrue(clickByDesc("slider_hint_never"))
                // The tick shows before the hint goes (TICK_FEEDBACK_MS). One look partway through,
                // without UiAutomator's wait for idle: findObject first waits until the screen has
                // been quiet for a while, and the tick's own animation kept it busy past the 0.7 s,
                // so the look always came after the hint had gone (#141, 3 runs).
                val cfg = androidx.test.uiautomator.Configurator.getInstance()
                val idleBefore = cfg.waitForIdleTimeout
                cfg.waitForIdleTimeout = 0
                val stillUp: Boolean
                val ticked: androidx.test.uiautomator.UiObject2?
                try {
                    android.os.SystemClock.sleep(app.fayaz.otgmaster.ui.TICK_FEEDBACK_MS / 3)
                    InstrumentationRegistry.getInstrumentation().uiAutomation.clearCache()
                    stillUp = device.findObject(By.desc("slider_hint")) != null
                    ticked = device.findObject(By.checkable(true).checked(true))
                } finally {
                    cfg.waitForIdleTimeout = idleBefore
                }
                if (!stillUp || ticked == null) captureScreen("no_tick")
                assertTrue("the hint went at once, before showing the tick", stillUp)
                assertNotNull("no tick in the box after Don't show again", ticked)
                assertTrue("Don't show again left the hint up", device.wait(Until.gone(By.desc("slider_hint")), timeout))
                assertTrue("Don't show again was not saved", prefs.getBoolean(MainActivity.PREF_SLIDER_HINT_OFF, false))
                InstrumentationRegistry.getInstrumentation().targetContext.startActivity(mainActivityIntent()
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
                assertTrue(device.wait(Until.hasObject(By.pkg("app.fayaz.otgmaster").depth(0)), timeout))
                android.os.SystemClock.sleep(SLIDER_HINT_OPEN_DELAY_MS + 3000L)
                assertNull("the hint came back after Don't show again", device.findObject(By.desc("slider_hint")))
                openSlider()
                closeSlider()
                android.os.SystemClock.sleep(1500)
                assertNull("the hint came back on close after Don't show again", device.findObject(By.desc("slider_hint")))
            }
            "notification_slider" -> {
                closeSlider()
                dismissSliderHint()
                device.pressHome()
                assertTrue("the app is still in front", device.wait(Until.gone(By.pkg("app.fayaz.otgmaster").depth(0)), timeout))
                device.openNotification()
                val n = device.wait(Until.findObject(By.text("Files stay available in other apps until you unmount.")), timeout)
                if (n == null) captureScreen("no_mounted_notification")
                assertNotNull("no mounted-drives notification for ${drive.name}", n)
                n!!.click()
                assertTrue("the app did not open from the notification",
                    device.wait(Until.hasObject(By.pkg("app.fayaz.otgmaster").depth(0)), timeout))
                val slider = device.wait(Until.hasObject(By.desc("mounted_drawer")), timeout)
                if (!slider) captureScreen("notification_no_slider")
                assertTrue("the notification opened the app without the slider", slider)
                assertNotNull("the drive's card is not on the slider", findInSlider(By.desc("unmount_button")))
                assertNull("the hint showed although the notification opened the slider", device.findObject(By.desc("slider_hint")))
            }
        }
        doUnmount()
    }

    private fun runEjectFlow(flow: String) {
        if (flow.startsWith("unmount_")) return runUnmountFlow(flow)
        if (flow == "slider_hint" || flow == "notification_slider") return runSliderFlow(flow)
        val args = InstrumentationRegistry.getArguments()
        val password = args.getString("password", "")
        val pim = args.getString("pim", "")
        val testCase = args.getString("testCase", "")
        device.executeShellCommand("pm grant app.fayaz.otgmaster android.permission.POST_NOTIFICATIONS")
        android.os.SystemClock.sleep(1000)
        when (flow) {
            // One partition, nothing in use: no dialog of any kind.
            "single" -> {
                ejMount()
                ejTapEject()
                ejAwaitMessage(R.string.eject_done_other)
                assertNull("a dialog for an idle drive with one partition", device.findObject(By.desc("eject_confirm")))
                assertNull("a transfer dialog for an idle drive", device.findObject(By.desc("eject_busy_dismiss")))
                ejAwaitGone()
                ejAssertNoNotification()
                ejAssertLeftAlone()
            }
            "rescan" -> {
                ejMount()
                ejTapEject()
                ejAwaitGone()
                ejAssertLeftAlone()
                // Scan is the way back, as a re-plug is.
                val back = ejMount("after Eject")
                assertTrue("Scan left the drive marked ejected", OtgMasterState.ejectedDevices.isEmpty())
                readRootFile(bkContext, "flower.jpg")
                assertNotNull(back)
                doUnmount()
            }
            // A plain partition mounted by itself and an encrypted one beside it.
            "multi_confirm" -> {
                ejMount("the VeraCrypt partition")
                assertEquals("two partitions should be mounted", 2, OtgMasterState.mountedDrives.size)
                ejTapEject()
                assertNotNull("no confirmation with two partitions", device.wait(Until.findObject(By.desc("eject_confirm")), timeout))
                assertNotNull("the confirmation does not name the other partition",
                    device.findObject(By.textContains("the other partition on this drive")))
                assertTrue(clickByDesc("eject_keep_mounted"))
                assertTrue("Cancel left the dialog up", device.wait(Until.gone(By.desc("eject_confirm")), timeout))
                android.os.SystemClock.sleep(1000)
                assertEquals("Cancel ejected", 2, OtgMasterState.mountedDrives.size)

                ejTapEject()
                assertTrue(clickByDesc("eject_confirm"))
                ejAwaitMessage(R.string.eject_done_other)
                ejAwaitGone()
                ejAssertLeftAlone()

                // Only the plain one mounted, the encrypted one waiting on the form: it goes as well.
                findScanButton()!!.click()
                if (waitForMounted(60000L) == null) captureScreen("no_plain_after_scan")
                assertEquals("the plain partition did not come back", 1, OtgMasterState.mountedDrives.size)
                closeSlider()
                assertNotNull("the encrypted partition is not offered", device.wait(Until.findObject(By.desc("password_input")), 15000L))
                ejTapEject()
                assertNull("a confirmation for one mounted partition", device.findObject(By.desc("eject_confirm")))
                ejAwaitGone()
                ejAssertLeftAlone()
            }
            "message_encrypted" -> {
                ejMount()
                ejTapEject()
                ejAwaitMessage(R.string.eject_done_other)
                ejAwaitGone()
                ejAssertLeftAlone()
            }
            "message_plain" -> {
                ejMount()
                assertNull("a plain drive has no Unmount of its own", device.findObject(By.desc("unmount_button")))
                ejTapEject()
                ejAwaitMessage(R.string.eject_done_android)
                ejAwaitGone()
                ejAssertLeftAlone()
            }
            // The dialog is left up: it closes by itself and there is no notification.
            "busy_wait" -> {
                val drive = ejMount()
                val file = ejHoldFile(drive)
                try {
                    ejTapEject()
                    ejAssertBusyDialog()
                    ejAssertStaysMounted()
                    assertNotNull("the dialog went away by itself", device.findObject(By.desc("eject_busy_dismiss")))
                } finally { file.close() }
                assertTrue("the dialog did not close after the Eject", device.wait(Until.gone(By.desc("eject_busy_dismiss")), 30_000L))
                ejAwaitGone()
                ejAwaitMessage(R.string.eject_done_other)
                ejAssertNoNotification()
            }
            "busy_dismiss" -> {
                val drive = ejMount()
                val file = ejHoldFile(drive)
                try {
                    ejTapEject()
                    ejAssertBusyDialog()
                    assertTrue(clickByDesc("eject_busy_dismiss"))
                    assertTrue("Dismiss left the dialog up", device.wait(Until.gone(By.desc("eject_busy_dismiss")), timeout))
                    assertNotNull("the card does not say it is waiting", findInSlider(By.desc("eject_pending")))
                    assertNotNull("Eject still offered while waiting", device.findObject(By.desc("eject_card_cancel")))
                    assertNull("Eject offered while waiting", device.findObject(By.desc("eject_button")))
                    assertNull("Unmount offered while waiting", device.findObject(By.desc("unmount_button")))
                    assertNull("More actions offered while waiting", device.findObject(By.desc("more_actions_button")))
                    ejAssertStaysMounted()
                    assertTrue("it forgot the Eject", OtgMasterState.pendingEjects.value.isNotEmpty())
                } finally { file.close() }
                ejAwaitGone()
                ejAwaitNotification()
            }
            "busy_cancel" -> {
                val drive = ejMount()
                val file = ejHoldFile(drive)
                try {
                    ejTapEject()
                    ejAssertBusyDialog()
                    assertTrue(clickByDesc("eject_busy_cancel"))
                    assertTrue("Cancel eject left the dialog up", device.wait(Until.gone(By.desc("eject_busy_cancel")), timeout))
                    assertTrue("still pending", OtgMasterState.pendingEjects.value.isEmpty())
                } finally { file.close() }
                ejAssertStaysMounted(6000L)
                assertNotNull("Eject is not back", findInSlider(By.desc("eject_button")))
                doUnmount()
            }
            "card_cancel" -> {
                val drive = ejMount()
                val file = ejHoldFile(drive)
                try {
                    ejTapEject()
                    ejAssertBusyDialog()
                    assertTrue(clickByDesc("eject_busy_dismiss"))
                    assertNotNull("no Cancel eject on the card", findInSlider(By.desc("eject_card_cancel")))
                    assertTrue(clickByDesc("eject_card_cancel"))
                    assertTrue("the card still says it is waiting", device.wait(Until.gone(By.desc("eject_pending")), timeout))
                    assertTrue("still pending", OtgMasterState.pendingEjects.value.isEmpty())
                } finally { file.close() }
                ejAssertStaysMounted(6000L)
                doUnmount()
            }
            // A second copy starts before the first has settled: Eject waits for that one too.
            "new_transfer" -> {
                val drive = ejMount()
                val first = ejHoldFile(drive)
                var second: android.os.ParcelFileDescriptor? = null
                try {
                    ejTapEject()
                    ejAssertBusyDialog()
                    assertTrue(clickByDesc("eject_busy_dismiss"))
                    first.close()
                    android.os.SystemClock.sleep(500)
                    second = ejHoldFile(drive)
                    ejAssertStaysMounted(6000L)
                } finally {
                    first.close()
                    second?.close()
                }
                ejAwaitGone()
                ejAwaitNotification()
            }
            "unplug_pending" -> {
                val drive = ejMount()
                val key = drive.sourceDeviceName!!
                val file = ejHoldFile(drive)
                try {
                    ejTapEject()
                    ejAssertBusyDialog()
                    val detach = OtgMasterState.detachRequest
                    assertNotNull("no detach handler", detach)
                    detach!!(key)
                    assertTrue("the dialog stayed after the drive went", device.wait(Until.gone(By.desc("eject_busy_dismiss")), 15_000L))
                    val end = android.os.SystemClock.uptimeMillis() + 15_000L
                    while (OtgMasterState.mountedDrives.isNotEmpty() && android.os.SystemClock.uptimeMillis() < end) android.os.SystemClock.sleep(200)
                    assertTrue("the Eject outlived the drive", OtgMasterState.pendingEjects.value.isEmpty())
                    assertTrue("a drive that went away is marked ejected", OtgMasterState.ejectedDevices.isEmpty())
                } finally { runCatching { file.close() } }
                ejAssertNoNotification()
            }
            "usb_mode_always" -> {
                val prefs = bkContext.getSharedPreferences("otgmaster_prefs", Context.MODE_PRIVATE)
                assertNull("the default is saved before anyone chose", prefs.getString("usb_access_mode", null))
                assertNotNull("the default did not open the drive by itself", device.wait(Until.findObject(By.desc("password_input")), 30_000L))
                usbPickInSettings("manual")
                usbPickInSettings("wait")
                usbPickInSettings("always")
                relaunchApp()
                assertNotNull("Always did not open the drive by itself", device.wait(Until.findObject(By.desc("password_input")), 30_000L))
                assertTrue("the volume did not mount", doMount(password, pim, testCase, clearFields = true))
                doUnmount()
            }
            "usb_mode_manual" -> {
                usbAwaitHint(R.string.usb_hint_manual)
                android.os.SystemClock.sleep(3000)
                usbAssertNotOpened("Manual, before a Scan")
                ejMount("after a Scan")
                doUnmount()
                // Released at the last Unmount, and not taken again. The open slider hides the form.
                closeSlider()
                usbAwaitHint(R.string.usb_hint_manual)
                usbAssertNotOpened("Manual, after the Unmount")
            }
            "usb_mode_manual_auto_mount" -> {
                usbAwaitHint(R.string.usb_hint_manual)
                android.os.SystemClock.sleep(3000)
                usbAssertNotOpened("Manual, a plain drive before a Scan")
                ejMount("after a Scan")
                assertTrue("a plain drive did not mount by itself after Scan", OtgMasterState.mountedDrives.isNotEmpty())
            }
            "usb_mode_wait_free" -> {
                relaunchApp()
                usbAwaitHint(R.string.usb_hint_waiting, 6000L)
                usbAssertNotOpened("Wait, inside the wait")
                assertNotNull("Wait did not open a drive Android left alone",
                    device.wait(Until.findObject(By.desc("password_input")), 30_000L))
                assertTrue("the volume did not mount", doMount(password, pim, testCase, clearFields = true))
                doUnmount()
            }
            "usb_mode_wait_held" -> {
                usbAwaitHint(R.string.usb_hint_android)
                android.os.SystemClock.sleep(12_000)
                usbAssertNotOpened("Wait, a drive Android took")
                assertNotNull("the hint went away", device.findObject(By.text(usbText(R.string.usb_hint_android))))
            }
            "report_issue" -> runReportIssue(args.getString("issue_marker", ""))
            "usb_mode_scan_warning" -> {
                usbAwaitHint(R.string.usb_hint_android)
                findScanButton()!!.click()
                assertNotNull("no warning for a drive Android took", device.wait(Until.findObject(By.desc("usb_scan_continue")), timeout))
                assertTrue(clickByDesc("usb_scan_cancel"))
                assertTrue("Cancel left the warning up", device.wait(Until.gone(By.desc("usb_scan_continue")), timeout))
                android.os.SystemClock.sleep(2000)
                usbAssertNotOpened("Wait, after Cancel on the warning")

                findScanButton()!!.click()
                assertTrue(clickByDesc("usb_scan_continue", waitMs = timeout))
                assertNotNull("Continue did not open the drive", device.wait(Until.findObject(By.desc("password_input")), 30_000L))
                assertTrue("the volume did not mount", doMount(password, pim, testCase, clearFields = true))
                doUnmount()
            }
            else -> fail("unknown eject_flow $flow")
        }
    }

    // ── VeraCrypt hidden volumes (testdata/hidden_*, scripts/make_hidden_volume_fixtures.py) ──

    private fun hiddenArg(name: String): String = InstrumentationRegistry.getArguments().getString(name, "")

    /** Fills the Hidden Volume Protection group of the unlock form, as the case says. */
    private fun fillHiddenProtection() {
        clickScrolling("hidden_protect_switch", "no hidden volume protection switch on the form")
        typeInField("hidden_protect_password", hiddenArg("hidden_password"))
        hiddenArg("hidden_pim").takeIf { it.isNotEmpty() }?.let { typeInField("hidden_protect_pim", it) }
        hiddenArg("hidden_keyfile").takeIf { it.isNotEmpty() }?.let {
            clickScrolling("hidden_protect_keyfiles_button", "no keyfiles button in the protection group")
            pickKeyfileInFiles(it)
        }
        // Below the fold with the group open, and pickOption does not scroll.
        fun pick(picker: String, option: String) {
            assertNotNull("$picker missing", findScrolling(By.desc(picker), desc = picker))
            pickOption(picker, option)
        }
        hiddenArg("hidden_cipher").takeIf { it.isNotEmpty() }?.let { pick("hidden_protect_cipher", it) }
        hiddenArg("hidden_hash").takeIf { it.isNotEmpty() }?.let { pick("hidden_protect_hash", it) }
        hideKeyboard()
    }

    private fun typeInField(desc: String, text: String) {
        clickScrolling(desc, "$desc missing")
        android.os.SystemClock.sleep(500)
        device.executeShellCommand("input text $text")
        android.os.SystemClock.sleep(500)
    }

    private fun protectionSwitch(): androidx.test.uiautomator.UiObject2 {
        val found = findScrolling(By.desc("hidden_protect_switch"), desc = "hidden_protect_switch")
        assertNotNull("no hidden volume protection switch on the form", found)
        return found!!
    }

    /** A failed unlock's reason, in the log below the form. */
    private fun assertLogLine(text: String) {
        val deadline = android.os.SystemClock.uptimeMillis() + 120_000L
        var line: androidx.test.uiautomator.UiObject2? = null
        while (line == null && android.os.SystemClock.uptimeMillis() < deadline) {
            InstrumentationRegistry.getInstrumentation().uiAutomation.clearCache()
            line = findScrolling(By.textContains(text), text = text, firstWaitMs = 3000L)
        }
        if (line == null) captureScreen("no_log_line")
        assertNotNull("'$text' not in the app's log", line)
    }

    private fun assertRefusedThenOptionOff(logLine: String) {
        assertLogLine(logLine)
        assertTrue("a refused unlock mounted something", OtgMasterState.mountedDrives.isEmpty())
        assertFalse("hidden volume protection stayed on after the attempt", protectionSwitch().isChecked)
        assertNull("the protection fields stayed open after the attempt", device.findObject(By.desc("hidden_protect_password")))
    }

    /**
     * Mounts with the protection fields filled. VeraCrypt's message about the protection covers
     * the screen when the mount ends, so it is read and closed before looking for the card.
     */
    private fun mountProtected(password: String, pim: String, testCase: String, justBeforeMount: () -> Unit = {}) {
        scan("with hidden volume protection")
        assertTrue("the mount button was not tapped",
            doMount(password, pim, testCase, clearFields = true, waitForMount = false, beforeMount = { fillHiddenProtection(); justBeforeMount() }))
        if (device.wait(Until.findObject(By.text("Hidden volume protected")), 300000L) == null) captureScreen("no_protection_message")
        assertDialog("Hidden volume protected", "until the outer volume is unmounted")
        assertNotNull("the outer volume did not mount", waitForMounted(30000L))
    }

    private fun assertOuterTag(tag: String) {
        assertNotNull("$tag not on the drive card", findInSlider(By.text(tag)))
    }

    /** Writes a new file in the volume's root through the provider; false if that was refused. */
    private fun writeRootFile(context: Context, name: String, bytes: ByteArray): Boolean = runCatching {
        val root = DocumentsContract.buildDocumentUri(AUTHORITY, getRootDocId(context)!!)
        val uri = DocumentsContract.createDocument(context.contentResolver, root, "application/octet-stream", name)!!
        context.contentResolver.openOutputStream(uri)!!.use { it.write(bytes) }
    }.isSuccess

    /** The hidden volume, read-only so that nothing can touch it: its own file and a byte-exact flower. */
    private fun assertHiddenVolumeIntact(context: Context, testCase: String) {
        scanAndMount(hiddenArg("hidden_password"), hiddenArg("hidden_pim"), testCase, "with the hidden volume's password") {
            clickScrolling("read_only_switch", "no read-only switch on the form")
        }
        assertOuterTag("HIDDEN")
        assertEquals("the hidden volume's own file changed", "Only the hidden volume has this file\n",
            readRootFile(context, "hidden_marker.txt"))
        doUnmount()
    }

    private fun runHiddenFlow(flow: String) {
        val arguments = InstrumentationRegistry.getArguments()
        val password = arguments.getString("password", "")
        val pim = arguments.getString("pim", "")
        val testCase = arguments.getString("testCase", "")
        val context = ApplicationProvider.getApplicationContext<Context>()
        android.os.SystemClock.sleep(1000)
        when (flow) {
            "wrong_password" -> {
                scan("before a wrong password")
                doMount(password, pim, testCase, clearFields = true, waitForMount = false)
                assertLogLine("Neither the standard nor the hidden volume header")
                assertTrue("a wrong password mounted something", OtgMasterState.mountedDrives.isEmpty())
            }
            "protect_wrong_password" -> {
                scan("before a wrong hidden password")
                doMount(password, pim, testCase, clearFields = true, waitForMount = false, beforeMount = { fillHiddenProtection() })
                // Named only in the toast: a log gets pasted into public issues.
                assertRefusedThenOptionOff("second set of credentials")
                assertTrue("the log names the hidden volume",
                    device.wait(Until.gone(By.textContains("protected hidden")), 15000L))
            }
            "protect_main_is_hidden" -> {
                scan("with the hidden password as the main one")
                doMount(password, pim, testCase, clearFields = true, waitForMount = false, beforeMount = { fillHiddenProtection() })
                assertRefusedThenOptionOff("Neither the standard nor the hidden volume header")
            }
            "protect_read_only" -> {
                scan("before the read-only switch")
                assertNotNull("no unlock form", device.wait(Until.findObject(By.desc("password_input")), 30000L))
                protectionSwitch().click()
                assertNotNull("the protection fields did not open", findScrolling(By.desc("hidden_protect_password"), desc = "hidden_protect_password"))
                clickScrolling("read_only_switch", "no read-only switch on the form")
                android.os.SystemClock.sleep(500)
                assertFalse("read-only left hidden volume protection on", protectionSwitch().isChecked)
                assertFalse("hidden volume protection can be turned on for a read-only mount", protectionSwitch().isEnabled)
                assertNull("the protection fields stayed open", device.findObject(By.desc("hidden_protect_password")))
                clickScrolling("read_only_switch", "no read-only switch on the form")
                android.os.SystemClock.sleep(500)
                assertTrue("hidden volume protection stayed greyed out", protectionSwitch().isEnabled)
            }
            "protect_option" -> {
                mountProtected(password, pim, testCase)
                assertOuterTag("OUTER")
                doUnmount()
            }
            "protect_safe" -> {
                mountProtected(password, pim, testCase)
                assertOuterTag("OUTER")
                clickScrolling("more_actions_button", "More actions missing")
                android.os.SystemClock.sleep(500)
                assertNull("Format offered with the hidden volume protected", device.findObject(By.text("Format")))
                assertNull("Encrypt offered with the hidden volume protected", device.findObject(By.text("Encrypt")))
                device.pressBack()
                val protection = OtgMasterState.mountedDrives.first().hiddenVolumeProtection
                assertNotNull("the mount has no protection", protection)
                assertTrue("a write clear of the hidden volume was refused",
                    writeRootFile(context, "protected_write_ok.txt", ByteArray(64 * 1024) { (it % 251).toByte() }))
                assertFalse("a write clear of the hidden volume set off the protection", protection!!.triggered)
                readRootFile(context, "decoy.txt")
                doUnmount()
                // The option does not survive the mount: the same drive now mounts as a normal volume.
                scanAndMount(password, pim, testCase, "without protection, the second time")
                assertNull("the mount after a protected one is protected too", OtgMasterState.mountedDrives.first().hiddenVolumeProtection)
                assertNull("OUTER on a mount without protection", device.findObject(By.text("OUTER")))
                doUnmount()
                assertHiddenVolumeIntact(context, testCase)
            }
            "protect_trigger" -> {
                mountProtected(password, pim, testCase)
                assertOuterTag("OUTER")
                val protection = OtgMasterState.mountedDrives.first().hiddenVolumeProtection!!
                assertFalse("an 8 MiB write that must reach the hidden volume succeeded",
                    writeRootFile(context, "reaches_hidden.bin", ByteArray(8 * 1024 * 1024) { (it % 253).toByte() }))
                assertTrue("the write did not set off the protection", protection.triggered)
                assertDialog("Damage to hidden volume prevented", "write-protected until it is unmounted")
                val posted = (0 until 40).any {
                    android.os.SystemClock.sleep(250)
                    "Damage to hidden volume prevented" in bkTitles()
                }
                assertTrue("no notification that a write was refused: ${bkTitles()}", posted)
                assertOuterTag("OUTER(!)")
                assertNotNull("READ-ONLY not on the card", device.wait(Until.findObject(By.text("READ-ONLY")), timeout))
                assertFalse("a small write after the protection fired was not refused",
                    writeRootFile(context, "after_trigger.txt", ByteArray(4096)))
                doUnmount()
                assertHiddenVolumeIntact(context, testCase)
            }
            "no_auto_mount" -> {
                val prefs = context.getSharedPreferences("otgmaster_prefs", Context.MODE_PRIVATE)
                val store = app.fayaz.otgmaster.security.CredentialStore(context)
                try {
                    prefs.edit().putBoolean("auto_mount", true).commit()
                    relaunchApp()
                    // A normal mount is remembered, which is what the two below must not be.
                    scanAndMount(password, pim, testCase, "as a normal volume")
                    val key = OtgMasterState.mountedDrives.first().sourceDeviceName.orEmpty()
                    assertTrue("no device key", key.isNotEmpty())
                    assertNotNull("a normal mount was not remembered", store.load(key, 0L))
                    doUnmount()
                    store.deletePartition(key, 0L)
                    relaunchApp()

                    // Saved at the last moment: with a saved password on it the form offers Quick unlock instead.
                    mountProtected(password, pim, testCase) {
                        store.save(key, 0L, password, pim, emptyList(),
                            app.fayaz.otgmaster.veracrypt.VeraCryptCipher.DEFAULT.name, app.fayaz.otgmaster.veracrypt.VeraCryptHash.DEFAULT.name)
                    }
                    assertOuterTag("OUTER")
                    assertNull("a protected mount left a password saved", store.load(key, 0L))
                    doUnmount()

                    scanAndMount(hiddenArg("hidden_password"), hiddenArg("hidden_pim"), testCase, "with the hidden volume's password")
                    assertOuterTag("HIDDEN")
                    assertNull("the hidden volume's password was saved", store.load(key, 0L))
                    doUnmount()
                } finally {
                    prefs.edit().remove("auto_mount").commit()
                    store.clearAll()
                }
            }
            else -> fail("unknown hidden_flow $flow")
        }
    }

    /**
     * Pulls the drive out from under its mount, then tells the app it has gone, and
     * watches the banner while the card goes: the warning containing [banner] must show,
     * and the unmount that follows must not replace it with a green "Unmounted", which
     * reads as success. The banner lasts 2.5 s, so it is watched throughout rather than
     * looked for afterwards.
     *
     * With [noteSeenAt], the time a 2.5 s note was seen, the app is told while its main thread
     * is held across the end of that note.
     */
    private fun dropMountedDrive(banner: String, noteSeenAt: Long? = null) {
        val key = OtgMasterState.mountedDrives.firstOrNull()?.sourceDeviceName
        assertNotNull("no mounted drive to drop", key)
        // On screen, or its going below would be seen at once.
        val unmount = findScrolling(By.descContains("unmount_button"), desc = "unmount_button")
        if (unmount == null) captureScreen("no_unmount_before_drop")
        assertNotNull("Unmount not on screen before the drop", unmount)
        requestUsb("unplug")
        val detach = OtgMasterState.detachRequest
        assertNotNull("no detach handler installed — is MainActivity running?", detach)
        if (noteSeenAt == null) {
            detach!!(key!!)
        } else {
            android.os.SystemClock.sleep((noteSeenAt + 1700 - android.os.SystemClock.uptimeMillis()).coerceAtLeast(0))
            InstrumentationRegistry.getInstrumentation().runOnMainSync {
                detach!!(key!!)
                android.os.SystemClock.sleep(1500)
            }
        }
        var sawWarning = false
        var sawUnmounted = false
        var goneAt = 0L
        val deadline = android.os.SystemClock.uptimeMillis() + UNMOUNT_WAIT_MS
        while (android.os.SystemClock.uptimeMillis() < deadline) {
            InstrumentationRegistry.getInstrumentation().uiAutomation.clearCache()
            if (device.findObject(By.textContains(banner)) != null) sawWarning = true
            if (device.findObject(By.textStartsWith("Unmounted:")) != null) sawUnmounted = true
            if (goneAt == 0L && device.findObject(By.descContains("unmount_button")) == null) {
                goneAt = android.os.SystemClock.uptimeMillis()
            }
            // Past the card going, long enough for a late "Unmounted" to have shown.
            if (goneAt != 0L && android.os.SystemClock.uptimeMillis() - goneAt > 2000) break
            android.os.SystemClock.sleep(200)
        }
        if (goneAt == 0L || !sawWarning || sawUnmounted) captureScreen("drop")
        assertTrue("the mounted card did not go when its drive was pulled", goneAt != 0L)
        assertTrue("disconnect warning '$banner' not shown", sawWarning)
        assertFalse("a green 'Unmounted' banner replaced the disconnect warning", sawUnmounted)
    }

    /**
     * Each Settings row opens a dialog holding what it has, nothing of it shows before the tap, and
     * Close comes back to the list. A switch in a dialog still changes the setting.
     */
    private fun assertSettingsOpenDialogs(context: Context) {
        device.findObject(By.desc(context.getString(R.string.cd_settings))).click()
        assertNotNull("Settings did not open", device.wait(Until.findObject(By.res("settings_row_theme")), timeout))
        val rows = listOf(
            "theme" to By.text(context.getString(R.string.theme_dark)),
            "auto_mount" to By.text(context.getString(R.string.auto_mount_description)),
            "read_only" to By.text(context.getString(R.string.read_only_mode_description)),
            "backups" to By.text(context.getString(R.string.backups_disable_description)),
            "version" to By.descContains(context.getString(R.string.cd_copy_version)),
            "donate" to By.text(context.getString(R.string.donate_thank_you)),
        )
        for ((id, inside) in rows) {
            assertNull("$id shows its content before the tap", device.findObject(inside))
            val row = device.findObject(By.res("settings_row_$id"))
            if (row == null) captureScreen("no_settings_row_$id")
            assertNotNull("no Settings row for $id", row)
            row!!.click()
            assertNotNull("$id opened no dialog", device.wait(Until.findObject(By.desc("settings_dialog_$id")), timeout))
            assertNotNull("the $id dialog does not hold its content", device.wait(Until.findObject(inside), timeout))
            if (id == "donate") {
                assertNotNull("no Ko-fi button in the Donate dialog",
                    device.findObject(By.desc(context.getString(R.string.donate_kofi))))
                assertNotNull("no link to the site's Support section in the Donate dialog",
                    device.findObject(By.res("donate_support_link")))
            }
            if (id == "read_only") {
                val prefs = context.getSharedPreferences("otgmaster_prefs", Context.MODE_PRIVATE)
                val before = prefs.getBoolean("read_only_default", false)
                assertTrue(clickByDesc("settings_read_only_switch"))
                android.os.SystemClock.sleep(500)
                assertEquals("the switch in the dialog did not change the setting", !before, prefs.getBoolean("read_only_default", false))
                assertTrue(clickByDesc("settings_read_only_switch"))
                android.os.SystemClock.sleep(500)
                assertEquals("the switch did not change back", before, prefs.getBoolean("read_only_default", false))
            }
            assertTrue(clickByDesc("settings_dialog_close"))
            assertTrue("Close left the $id dialog up", device.wait(Until.gone(By.desc("settings_dialog_$id")), timeout))
            assertNotNull("Close left Settings", device.wait(Until.findObject(By.res("settings_row_$id")), timeout))
        }
        device.findObject(By.desc(context.getString(R.string.cd_close_settings))).click()
        assertTrue("Settings stayed open", device.wait(Until.gone(By.res("settings_row_theme")), timeout))
    }

    /**
     * The main screen around a mount: one unlock per partition whichever path starts it, Manage
     * backups below the form, the bell beside Settings, and the slider that a mount opens.
     */
    private fun runMainScreenCase() {
        val arguments = InstrumentationRegistry.getArguments()
        val context = ApplicationProvider.getApplicationContext<Context>()
        val password = arguments.getString("password", "password123")
        val pim = arguments.getString("pim", "")
        android.os.SystemClock.sleep(1000)
        findScanButton()!!.click()
        device.wait(Until.findObject(By.text(java.util.regex.Pattern.compile("(?i)OK|ALLOW"))), 5000L)?.click()
        assertNotNull("the form did not offer the drive", device.wait(Until.findObject(By.desc("password_input")), 30000L))

        // The bell is left of Settings, and nothing is due.
        val bell = device.wait(Until.findObject(By.desc(context.getString(R.string.cd_backups_due, 0))), timeout)
        assertNotNull("no bell showing 0", bell)
        val settings = device.findObject(By.desc(context.getString(R.string.cd_settings)))
        assertTrue("the bell is not left of Settings", bell!!.visibleBounds.right <= settings.visibleBounds.left)

        // Manage backups is below the form: scrolled to, Unlock & mount is above it or gone upward.
        val manage = findScrolling(By.desc("manage_backups_button"), desc = "manage_backups_button")
        assertNotNull("no Manage backups", manage)
        device.findObject(By.desc("mount_button"))?.let {
            assertTrue("Manage backups is not below the form", it.visibleBounds.bottom <= manage!!.visibleBounds.top)
        }
        scrollToTop()

        assertSettingsOpenDialogs(context)

        // An unlock the form did not start, as auto mount's is: the form says so for this partition.
        val attempts = { OtgMasterState.recentLogs().count { it.contains("is already being unlocked") } }
        val hold = java.util.concurrent.CountDownLatch(1)
        OtgMasterState.testUnlockHold = hold
        try {
            val request = OtgMasterState.mountRequest
            assertNotNull("no mount request handler", request)
            val cipher = app.fayaz.otgmaster.veracrypt.VeraCryptCipher.DEFAULT.name
            val hash = app.fayaz.otgmaster.veracrypt.VeraCryptHash.DEFAULT.name
            request!!.mount(password, pim.toIntOrNull(), cipher, hash, null)
            val busyText = context.getString(R.string.unlocking_in_progress)
            val busy = findScrolling(By.text(busyText), text = busyText)
            if (busy == null) captureScreen("form_not_busy")
            assertNotNull("the form does not say an unlock is in progress", busy)
            // Its isEnabled cannot say: the accessibility node reads enabled either way. A tap can.
            device.findObject(By.desc("mount_button"))?.click()
            android.os.SystemClock.sleep(1000)
            assertEquals("the tap on the busy button started an unlock", 0, attempts())
            // A second path asking for the same partition is refused, and says so.
            request.mount(password, pim.toIntOrNull(), cipher, hash, null)
            val end = android.os.SystemClock.uptimeMillis() + 20_000L
            while (attempts() == 0 && android.os.SystemClock.uptimeMillis() < end) android.os.SystemClock.sleep(200)
            assertEquals("a second unlock of the same partition was not refused", 1, attempts())
        } finally {
            hold.countDown()
            OtgMasterState.testUnlockHold = null
        }

        // One mount, and it opens the slider on Drives over everything else.
        assertCardFlashes(By.descContains("unmount_button"), "the drive", 120_000L)
        val slider = device.wait(Until.findObject(By.desc("mounted_drawer")), 120_000L)
        if (slider == null) captureScreen("no_slider")
        assertNotNull("the mount did not open the slider", slider)
        android.os.SystemClock.sleep(3000)
        assertEquals("the partition mounted twice", 1, OtgMasterState.mountedDrives.size)
        assertNotNull("the slider did not open on Drives, with the card in it", device.wait(Until.findObject(By.textContains("Used:")), timeout))
        assertNull("the main screen is still offered behind the slider", device.findObject(By.desc("scan_button")))
        assertTrue("the slider does not come from the right", slider!!.visibleBounds.right == device.displayWidth && slider.visibleBounds.left > 0)

        // The tabs count what is mounted. Backups has nothing, and says so.
        val tabCount = { id: Int, n: Int -> context.getString(id, n) }
        assertNotNull("Drives does not count 1", device.findObject(By.text(tabCount(R.string.mounted_tab_drives, 1))))
        assertNotNull("Backups does not count 0", device.findObject(By.text(tabCount(R.string.mounted_tab_backups, 0))))
        assertTrue(clickByDesc("mounted_tab_backups"))
        bkAssertTextIn(context.getString(R.string.no_backups_mounted))
        assertNull("the drive is listed under Backups", device.findObject(By.textContains("Used:")))

        // Back closes it; the top bar's icon brings it back, counting what is mounted.
        closeSlider()
        assertNotNull("Back did not return to the main screen", device.wait(Until.findObject(By.desc("scan_button")), timeout))
        assertNull("the mounted card stayed on the main screen", device.findObject(By.textContains("Used:")))
        assertNotNull("the icon does not count 1", device.findObject(By.desc(context.getString(R.string.cd_open_mounted, 1))))
        openSlider()
        assertNotNull("the icon opened no card", device.wait(Until.findObject(By.textContains("Used:")), timeout))
        // The close button, and a drag towards the edge.
        device.findObject(By.desc(context.getString(R.string.cd_close_mounted))).click()
        assertTrue("Close left the slider open", device.wait(Until.gone(By.desc("mounted_drawer")), timeout))
        android.os.SystemClock.sleep(500)
        openSlider()
        val h = device.displayHeight / 2
        device.swipe(device.displayWidth / 4, h, device.displayWidth - 20, h, 20)
        assertTrue("a drag did not close the slider", device.wait(Until.gone(By.desc("mounted_drawer")), timeout))
        android.os.SystemClock.sleep(500)
        // A swipe to the left opens it: across the screen, and in from the right edge.
        device.swipe(device.displayWidth * 3 / 4, h, device.displayWidth / 4, h, 20)
        assertNotNull("a swipe across the screen did not open the slider", device.wait(Until.findObject(By.desc("mounted_drawer")), timeout))
        closeSlider()
        device.swipe(device.displayWidth - 5, h, device.displayWidth / 2, h, 20)
        assertNotNull("a swipe in from the edge did not open the slider", device.wait(Until.findObject(By.desc("mounted_drawer")), timeout))
        assertTrue("the edge swipe also went Back", device.hasObject(By.pkg("app.fayaz.otgmaster")))
        closeSlider()

        // Portrait: the form takes the width of the screen.
        scrollToTop()
        val portraitScan = device.wait(Until.findObject(By.desc("scan_button")), timeout)
        assertNotNull("no Scan button", portraitScan)
        assertTrue("the form is narrow in portrait", portraitScan!!.visibleBounds.width() >= device.displayWidth * 85 / 100)

        // Landscape: the phone's slider, not a pane beside the form, and the form is a centred column.
        onWideScreen {
            val density = 420f / 160f
            assertNull("a pane beside the form", device.findObject(By.desc("mounted_pane")))
            scrollToTop()
            val scan = device.wait(Until.findObject(By.desc("scan_button")), timeout)
            if (scan == null) captureScreen("no_scan_on_wide")
            assertNotNull("no Scan button on a wide screen", scan)
            val form = scan!!.visibleBounds
            assertTrue("the form is ${form.width()} px wide", form.width() <= 640 * density + 2)
            assertTrue("the form is at ${form.left}..${form.right}, off centre", kotlin.math.abs(form.centerX() - 2208 / 2) <= 4)

            openSlider()
            val drawer = device.findObject(By.desc("mounted_drawer"))
            assertTrue("the slider is ${drawer.visibleBounds.width()} px wide", drawer.visibleBounds.width() <= 480 * density + 2)
            assertNotNull("no card in the slider", device.wait(Until.findObject(By.textContains("Used:")), timeout))
            assertTrue(clickByDesc("mounted_tab_backups"))
            bkAssertTextIn(context.getString(R.string.no_backups_mounted))
            assertTrue(clickByDesc("mounted_tab_drives"))
            assertNotNull("the slider lost its card", device.wait(Until.findObject(By.textContains("Used:")), timeout))
            closeSlider()
        }
        doUnmount()
    }

    private fun bkAssertTextIn(text: String) {
        if (device.wait(Until.findObject(By.text(text)), timeout) == null) captureScreen("missing_text")
        assertNotNull("'$text' not on the screen", device.findObject(By.text(text)))
    }

    /** Asks the runner to unplug or replug the USB drive, and waits until it has. */
    private fun requestUsb(action: String) {
        val name = "otg_e2e_$action"
        device.executeShellCommand("touch /data/local/tmp/$name")
        val deadline = android.os.SystemClock.uptimeMillis() + 60_000
        while (android.os.SystemClock.uptimeMillis() < deadline) {
            // executeShellCommand returns stdout only, so a missing file reads as an
            // empty listing rather than an error message.
            if (name !in device.executeShellCommand("ls /data/local/tmp")) return
            android.os.SystemClock.sleep(500)
        }
        fail("the runner did not $action the USB drive within 60 s (is run_e2e_tests.sh watching?)")
    }

    private fun assertDisconnectedCard(powerLine: String) {
        val card = findScrolling(By.desc("disconnected_card"), desc = "disconnected_card")
        if (card == null) captureScreen("no_disconnected_card")
        assertNotNull("no disconnected card after the drive was pulled", card)
        assertNotNull("DISCONNECTED tag missing", device.findObject(By.text("DISCONNECTED")))
        assertNotNull("power line '$powerLine' missing on the card",
            findScrolling(By.textContains(powerLine), text = powerLine))
        // Nothing is behind the card any more: no Open, Unmount or More.
        assertNull("Unmount offered on a disconnected card", device.findObject(By.descContains("unmount_button")))
        assertNull("More actions offered on a disconnected card", device.findObject(By.desc("more_actions_button")))
        assertNull("Open Files App offered on a disconnected card", device.findObject(By.text("Open Files app")))
        assertNotNull("Dismiss missing on the disconnected card",
            findScrolling(By.desc("dismiss_disconnected_button"), desc = "dismiss_disconnected_button"))
    }

    /** The dialog titled [title] is up and says [expected]; closes it with OK. */
    private fun assertDialog(title: String, expected: String) {
        val heading = device.wait(Until.findObject(By.text(title)), timeout)
        if (heading == null) captureScreen("no_dialog")
        assertNotNull("'$title' dialog did not open", heading)
        assertNotNull("'$title' dialog does not say '$expected'", device.findObject(By.textContains(expected)))
        device.findObject(By.text("OK"))!!.click()
        assertTrue("'$title' dialog did not close", device.wait(Until.gone(By.text(title)), timeout))
    }

    /** The mounted card counts [count] drops this session; the newest reads [latest]. */
    private fun assertHistory(count: Int, latest: String) {
        val summary = findScrolling(By.desc("disconnect_history"), desc = "disconnect_history")
        if (summary == null) captureScreen("no_disconnect_history")
        assertNotNull("no disconnect history on the mounted card", summary)
        assertNotNull("history does not say ${count}×",
            device.findObject(By.text("Disconnected unexpectedly ${count}× this session")))
        // Found again at the click: the card can recompose just after a mount (free space
        // filled in), and the minified run once expanded nothing, its click on a stale
        // handle lost. The expanded lines can also sit below the fold, so they are
        // scrolled to.
        // A click can land while the card recomposes and expand nothing: the card is then
        // still collapsed (chevron down; attempt-1 screenshot on 4eae31e and bea4a45, both
        // FLAKY). Click again, up to three times, until the expanded line shows.
        var line: androidx.test.uiautomator.UiObject2? = null
        for (attempt in 1..3) {
            assertTrue("disconnect history could not be clicked", clickByDesc("disconnect_history"))
            line = findScrolling(By.textContains(latest), text = latest)
            if (line != null) break
            android.util.Log.w("E2E", "disconnect history did not expand on click $attempt; clicking again")
        }
        if (line == null) captureScreen("history_not_expanded")
        assertNotNull("expanded history does not list '$latest'", line)
        assertEquals("expanded history lists the wrong number of drops", count,
            device.findObjects(By.textContains(" · on ")).size)
        clickByDesc("disconnect_history")
    }

    /** Every document id in the first root's top directory. */
    private fun listRoot(context: Context): List<String> {
        val ids = mutableListOf<String>()
        val rootId = getRootDocId(context) ?: return ids
        context.contentResolver.query(DocumentsContract.buildChildDocumentsUri(AUTHORITY, rootId), null, null, null, null)
            ?.use { cur ->
                val col = cur.getColumnIndex(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
                while (cur.moveToNext()) cur.getString(col)?.let { ids += it }
            }
        return ids
    }

    /**
     * Finds [desc] in the full-screen Format/Encrypt dialog, swiping its form up until
     * it shows. The dialog is its own window, so the page scroller behind it is no help.
     */
    private fun findInDialog(desc: String): androidx.test.uiautomator.UiObject2? {
        var swiped = false
        repeat(8) {
            device.wait(Until.findObject(By.desc(desc)), 1500L)?.let { found ->
                if (!swiped) return found
                // A tap on a form still moving from the swipe only stops it, and nothing else happens:
                // a chip stayed unselected, and encrypt_luks2_ext4's Erase opened no dialog.
                android.os.SystemClock.sleep(800)
                return device.findObject(By.desc(desc)) ?: found
            }
            val h = device.displayHeight
            device.swipe(device.displayWidth / 2, h * 3 / 4, device.displayWidth / 2, h / 3, 25)
            swiped = true
            device.waitForIdle()
        }
        captureScreen("dialog_missing_$desc")
        return null
    }

    private fun clickInDialog(desc: String) {
        val obj = findInDialog(desc)
        assertNotNull("$desc not found in the dialog", obj)
        obj!!.click()
        android.os.SystemClock.sleep(300)
    }

    /**
     * Sets a field's text through the accessibility set-text action. Typing with
     * `input text` goes to whichever field has focus, and a tap on the next field did not
     * always move it there: ERASE once landed in the label field above the confirmation
     * field, and the case then waited out its whole timeout on a disabled button.
     */
    private fun typeInto(desc: String, text: String) {
        val field = findInDialog(desc)
        assertNotNull("$desc not found in the dialog", field)
        editTextOf(field!!).text = text
        android.os.SystemClock.sleep(300)
        // Read back through the field itself. Password fields are hidden from
        // accessibility; for those, the confirm button enabling (it needs both
        // passwords to match) is the check.
        if (!desc.startsWith("encrypt_password") && desc != "backups_browse_password") {
            val shown = device.findObject(By.desc(desc))?.let { editTextOf(it).text } ?: ""
            if (shown != text) captureScreen("typed_$desc")
            assertEquals("$desc after setting its text", text, shown)
        }
    }

    /**
     * The editable node of a Compose text field found by its description. The
     * description lands on a View *inside* the EditText (seen in the hierarchy dump of
     * the ERASE field), so the EditText is its parent; set-text on the inner View is
     * silently ignored.
     */
    private fun editTextOf(obj: androidx.test.uiautomator.UiObject2): androidx.test.uiautomator.UiObject2 {
        val edit = "android.widget.EditText"
        if (obj.className == edit) return obj
        obj.parent?.takeIf { it.className == edit }?.let { return it }
        return obj.findObject(By.clazz(edit)) ?: obj
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private fun doMount(password: String, pim: String, testCase: String, clearFields: Boolean,
                        waitForMount: Boolean = true, beforeMount: () -> Unit = {}): Boolean {
        closeSlider()
        // First pass: check if password field is visible; if not, try the device picker.
        if (device.wait(Until.findObject(By.descContains("password_input")), 15000L) == null) {
            val devicePicker = device.findObject(By.descContains("device_picker"))
            if (devicePicker != null) {
                devicePicker.click()
                android.os.SystemClock.sleep(500)
                val options = device.findObjects(By.textContains("QEMU"))
                if (options.size > 1) {
                    options.last().click()
                    android.os.SystemClock.sleep(1000)
                }
            }
        }

        if (testCase == "partitioned_mbr" || testCase == "eject_multi_confirm") {
            val candidatePicker = device.findObject(By.descContains("candidate_picker"))
            if (candidatePicker != null) {
                candidatePicker.click()
                android.os.SystemClock.sleep(500)
                device.findObjects(By.textContains("MBR partition")).lastOrNull()?.click()
                android.os.SystemClock.sleep(500)
            }
        }

        // Re-find and click the password field with retry: a UiObject2 can go stale
        // between findObject() and click() if Compose recomposes in that window.
        if (!clickByDesc("password_input", waitMs = 10000L)) return false.also { captureScreen("no_password_field") }
        android.os.SystemClock.sleep(500)
        if (clearFields) {
            repeat(50) { device.executeShellCommand("input keyevent KEYCODE_DEL") }
            android.os.SystemClock.sleep(200)
        }
        if (password != NO_PASSWORD) device.executeShellCommand("input text $password")
        android.os.SystemClock.sleep(500)

        if (pim.isNotEmpty()) {
            val pimField = findScrolling(By.descContains("pim_input"), desc = "pim_input")
                ?: return false.also { captureScreen("no_pim_field") }
            pimField.click()
            android.os.SystemClock.sleep(500)
            if (clearFields) {
                repeat(20) { device.executeShellCommand("input keyevent KEYCODE_DEL") }
                android.os.SystemClock.sleep(200)
            }
            device.executeShellCommand("input text $pim")
            android.os.SystemClock.sleep(500)
        }

        device.pressEnter()
        android.os.SystemClock.sleep(500)
        // The form keeps what was picked; picking the same file again changes nothing.
        unlockKeyfiles.forEach { name ->
            val button = findScrolling(By.textContains("Select keyfile"), text = "Select keyfile")
            assertNotNull("Select keyfiles not on the unlock form", button)
            button!!.click()
            pickKeyfileInFiles(name)
        }

        beforeMount()
        val clicked = clickMountButton()
        if (clicked != MountClick.CLICKED) return false.also { captureScreen("mount_button_$clicked") }
        if (!waitForMount) return true

        return waitForMounted(300000L) != null
    }

    /** Keyfiles doMount picks on the unlock form: what the new volume needs. */
    private var unlockKeyfiles: List<String> = emptyList()

    /** With the Files picker opening: taps the file named [name], from Recent or Downloads. */
    private fun pickKeyfileInFiles(name: String) {
        device.wait(Until.hasObject(By.pkg("com.google.android.documentsui")), timeout)
        android.os.SystemClock.sleep(1000)
        var fileObject = device.findObject(By.textContains(name))
        if (fileObject == null) {
            val hamburger = device.findObject(By.descContains("Show roots"))
                ?: device.findObject(By.descContains("Navigate up"))
                ?: device.findObject(By.descContains("Open navigation drawer"))
            hamburger?.click()
            android.os.SystemClock.sleep(500)
            device.wait(Until.findObject(By.textContains("Download")), timeout)?.click()
            android.os.SystemClock.sleep(500)
            fileObject = device.wait(Until.findObject(By.textContains(name)), timeout * 2)
        }
        if (fileObject == null) captureScreen("no_keyfile_$name")
        assertNotNull("keyfile $name not in the Files picker", fileObject)
        fileObject!!.click()
        device.wait(Until.hasObject(By.pkg("app.fayaz.otgmaster")), timeout)
        android.os.SystemClock.sleep(500)
    }

    private fun clickByDesc(desc: String, waitMs: Long = timeout, retries: Int = 3): Boolean {
        repeat(retries) { attempt ->
            try {
                val obj = device.wait(Until.findObject(By.descContains(desc)), waitMs) ?: return false
                obj.click()
                return true
            } catch (e: androidx.test.uiautomator.StaleObjectException) {
                if (attempt == retries - 1) return false
                android.os.SystemClock.sleep(200)
            }
        }
        return false
    }

    private fun doUnmount() {
        // A tap about 300 ms after the card appeared was lost: encrypt_plain_exfat failed both
        // attempts of 4 runs with no unmountDrive in the log, while a second tap 15 s later
        // unmounted at once, and a 1 s wait passed. The unmount itself is still held to its
        // 15 s below, and one tap only, so a button that stays dead still fails.
        android.os.SystemClock.sleep(UNMOUNT_SETTLE_MS)
        val unmountButton = findInSlider(By.descContains("unmount_button"))
        assertTrue("Unmount button not found", unmountButton != null)
        unmountButton?.click()
        val gone = device.wait(Until.gone(By.descContains("unmount_button")), UNMOUNT_WAIT_MS)
        if (!gone) captureScreen("unmount_not_gone")
        assertTrue("Drive was not successfully unmounted!", gone)
        // A tap within the 3 s settle after a write waits as for a transfer, behind a
        // dialog that closes when the drive unmounts. Let it go before the next step.
        device.wait(Until.gone(By.desc("unmount_busy_dismiss")), 5000L)
    }

    /**
     * Finds [selector], scrolling the screen to it. With a mounted drive's card
     * above the unlock form (a plain partition beside the encrypted one) the PIM
     * field and Unlock & Mount sit below the fold, and UI Automator only sees what
     * is on screen; the PIM field being missed silently made partitioned_mbr unlock
     * with no PIM — 500,000 iterations instead of 16,000, a wrong key, and a
     * "not mounted" failure that looked like a mount bug. Scrolls down first, then
     * back up, so it finds things on either side.
     */
    private fun findScrolling(selector: androidx.test.uiautomator.BySelector, desc: String? = null,
                              text: String? = null, firstWaitMs: Long = 2000L): androidx.test.uiautomator.UiObject2? {
        device.wait(Until.findObject(selector), firstWaitMs)?.let { return it }
        // The keyboard opened by the password field covers the lower half, and a swipe
        // starting on it types rather than scrolls. Close it first — with Back, and
        // only while it is up, since Back with no keyboard would leave the app.
        hideKeyboard()
        device.findObject(selector)?.let { return it }
        // Cards are in the slider, and everything else behind it, out of the tree while it is open.
        if (sliderOpen()) {
            findInSlider(selector)?.let { return it }
            closeSlider()
            device.wait(Until.findObject(selector), 1000L)?.let { return it }
        }
        // Scroll the app's own container (a ScrollView covering the screen) rather
        // than swiping — a swipe near the top pulled down the notification shade.
        // Then *wait* for the element: after a programmatic scroll the accessibility
        // tree lags the screen, and a dump taken right after scrolling showed the
        // form only down to "Select Keyfiles" while the screenshot showed Unlock &
        // Mount on screen.
        val scrollable = androidx.test.uiautomator.UiScrollable(
            androidx.test.uiautomator.UiSelector().scrollable(true).packageName("app.fayaz.otgmaster"))
        for (toEnd in listOf(true, false)) {
            runCatching { if (toEnd) scrollable.scrollToEnd(10) else scrollable.scrollToBeginning(10) }
            device.waitForIdle()
            device.wait(Until.findObject(selector), 5000L)?.let { return it }
        }
        if (OtgMasterState.mountedDrives.isNotEmpty() || OtgMasterState.disconnectedDrives.value.isNotEmpty()) {
            openSlider()
            findInSlider(selector)?.let { return it }
        }
        // Not found: the loop has left the page at the top again.
        captureScreen("not_found_after_scrolling")
        return null
    }

    private fun sliderOpen() = device.findObject(By.desc("mounted_drawer")) != null

    /**
     * Closes the mounted slider if it is up. Looked for with a short wait rather than
     * once: an Unmount tapped within 3 s of a file write shows "Will unmount once the
     * transfer is complete" for a moment, and while that dialog's window was going away
     * a single look found no slider. The slider stayed open over the Scan button, and
     * every Encrypt and Format case that remounts failed "Scan button not found for the
     * remount" (#141's E2E, 7 cases).
     */
    private fun closeSlider() {
        InstrumentationRegistry.getInstrumentation().uiAutomation.clearCache()
        if (device.wait(Until.hasObject(By.desc("mounted_drawer")), 2000L) != true) return
        device.pressBack()
        assertTrue("Back did not close the slider", device.wait(Until.gone(By.desc("mounted_drawer")), timeout))
        android.os.SystemClock.sleep(500)
    }

    /** Opens the slider from the top bar's drive icon, on [tab]. */
    private fun openSlider(tab: String = "mounted_tab_drives") {
        if (!sliderOpen()) {
            InstrumentationRegistry.getInstrumentation().uiAutomation.clearCache()
            val icon = device.wait(Until.findObject(By.descStartsWith("Mounted:")), timeout)
            if (icon == null) captureScreen("no_mounted_icon")
            assertNotNull("no drive icon on the top bar", icon)
            icon!!.click()
            assertNotNull("the icon did not open the slider", device.wait(Until.findObject(By.desc("mounted_drawer")), timeout))
        }
        // With backups disabled the slider is one list, without tabs.
        if (bkContext.getSharedPreferences("otgmaster_prefs", Context.MODE_PRIVATE).getBoolean("backups_hidden", false)) return
        val chosen = device.wait(Until.findObject(By.desc(tab)), timeout)
        assertNotNull("no $tab", chosen)
        chosen!!.click()
        android.os.SystemClock.sleep(400)
    }

    private fun findInSlider(selector: androidx.test.uiautomator.BySelector): androidx.test.uiautomator.UiObject2? {
        for (tab in listOf("mounted_tab_drives", "mounted_tab_backups")) {
            openSlider(tab)
            device.wait(Until.findObject(selector), 1000L)?.let { return it }
            val list = androidx.test.uiautomator.UiScrollable(
                androidx.test.uiautomator.UiSelector().scrollable(true).packageName("app.fayaz.otgmaster"))
            for (toEnd in listOf(true, false)) {
                runCatching { if (toEnd) list.scrollToEnd(5) else list.scrollToBeginning(5) }
                device.waitForIdle()
                device.wait(Until.findObject(selector), 1000L)?.let { return it }
            }
        }
        return null
    }

    private fun hideKeyboard() {
        // The accessibility window list has an input-method window only while the
        // keyboard is on screen. The dumpsys flag and Gboard's view tree both
        // outlive it, and a Back pressed on either false positive left the app.
        val imeUp = InstrumentationRegistry.getInstrumentation().uiAutomation.windows
            .any { it.type == android.view.accessibility.AccessibilityWindowInfo.TYPE_INPUT_METHOD }
        if (imeUp) {
            device.pressBack()
            android.os.SystemClock.sleep(500)
        }
    }

    /** Scrolls the app's page back to the top. */
    private fun scrollToTop() {
        runCatching {
            androidx.test.uiautomator.UiScrollable(androidx.test.uiautomator.UiSelector()
                .scrollable(true).packageName("app.fayaz.otgmaster")).scrollToBeginning(10)
        }
        device.waitForIdle()
    }

    /**
     * The Scan USB Devices button, which sits at the top of the page. An earlier
     * lookup lower down (PIM, Unlock & Mount) can leave the page scrolled, and the
     * click on a missing button was skipped silently — the remount then found no
     * candidate and failed as "Mount #2 failed" (m_luks1_exfat).
     */
    /**
     * Taps away the hint at the Drives icon if it is up. It shows whenever the app is opened
     * with a drive mounted, and sits over the top of the screen, where Scan is.
     */
    private fun dismissSliderHint() {
        device.findObject(By.desc("slider_hint"))?.let {
            it.click()
            device.wait(Until.gone(By.desc("slider_hint")), timeout)
        }
    }

    private fun findScanButton(): androidx.test.uiautomator.UiObject2? {
        closeSlider()
        dismissSliderHint()
        scrollToTop()
        return device.wait(Until.findObject(By.textContains("Scan").clickable(true)), timeout)
            ?: device.wait(Until.findObject(By.descContains("scan_button")), timeout)
            ?: null.also { captureScreen("scan_button_missing") }
    }

    private enum class MountClick { CLICKED, NOT_FOUND, DISABLED }

    /**
     * Finds and clicks Unlock & Mount, finding it again if it goes stale. Compose
     * can redraw the form between the find and the click — partitioned_mbr once
     * failed with StaleObjectException on `isEnabled` while the form was being
     * re-offered — and a stale handle says nothing about the app.
     */
    /**
     * Opens the dropdown [pickerDesc] and picks [label]. Exact text first: "Twofish" is a
     * substring of four cascade names. An unsupported option reads "<name> (not yet supported)",
     * so a prefix match is the fallback that still finds those.
     */
    private fun pickOption(pickerDesc: String, label: String, inDialog: Boolean = false) {
        // In the Encrypt dialog the picker can sit below the fold: the PIM confirmation field
        // (9a5e7f5) pushed encrypt_fs off an emulator's screen, and a wait cannot scroll.
        // A tap right after the keyboard closes can land where the picker was before the form
        // re-laid out (encrypt_fs, after the PIM confirmation field, three runs in a row): the
        // menu never opened. Wait for the UI to settle, and find and tap the picker again if no
        // menu appeared.
        var option: androidx.test.uiautomator.UiObject2? = null
        for (attempt in 1..3) {
            device.waitForIdle()
            val picker = if (inDialog) findInDialog(pickerDesc)
                else device.wait(Until.findObject(By.descContains(pickerDesc)), timeout)
            assertTrue("$pickerDesc not found", picker != null)
            picker?.click()
            android.os.SystemClock.sleep(500)
            option = device.wait(Until.findObject(By.text(label)), 3000L)
                ?: device.wait(Until.findObject(By.textStartsWith("$label ")), 2000L)
            if (option != null) break
        }
        if (option == null) captureScreen("option_missing_${pickerDesc}")
        assertTrue("Option '$label' not found in $pickerDesc", option != null)
        option?.click()
        android.os.SystemClock.sleep(500)
    }

    private fun clickMountButton(): MountClick {
        repeat(3) {
            val button = findMountButton() ?: return MountClick.NOT_FOUND
            try {
                if (!button.isEnabled) return MountClick.DISABLED
                button.click()
                return MountClick.CLICKED
            } catch (e: androidx.test.uiautomator.StaleObjectException) {
                android.os.SystemClock.sleep(500)
            }
        }
        return MountClick.NOT_FOUND
    }

    private fun findMountButton(): androidx.test.uiautomator.UiObject2? =
        findScrolling(By.desc("mount_button"), desc = "mount_button")
            ?: findScrolling(By.textContains("Unlock & mount"), text = "Unlock & mount", firstWaitMs = 0L)

    /** Waits up to [timeoutMs] for a mounted card ("Used:"), scrolling up to it. */
    private fun waitForMounted(timeoutMs: Long): androidx.test.uiautomator.UiObject2? {
        val deadline = android.os.SystemClock.uptimeMillis() + timeoutMs
        while (android.os.SystemClock.uptimeMillis() < deadline) {
            device.wait(Until.findObject(By.textContains("Used")), 5000L)?.let { return it }
            if (!sliderOpen() && OtgMasterState.mountedDrives.isNotEmpty()) { openSlider(); continue }
            device.findObject(By.scrollable(true).pkg("app.fayaz.otgmaster"))
                ?.scroll(androidx.test.uiautomator.Direction.UP, 1.0f)
        }
        return null
    }

    /** Screenshot and UI hierarchy to /sdcard/Download, for diagnosing a failed wait. */
    private fun captureScreen(tag: String) {
        runCatching { device.takeScreenshot(java.io.File("/sdcard/Download/e2e_$tag.png")) }
        runCatching { device.dumpWindowHierarchy(java.io.File("/sdcard/Download/e2e_$tag.xml")) }
    }

    private fun mainActivityIntent() =
        Intent().setClassName("app.fayaz.otgmaster", "app.fayaz.otgmaster.MainActivity")

    /** The volume picker must tag the candidate with its container type, e.g. BITLOCKER. */
    private fun assertContainerTag(expected: String) {
        if (expected.isEmpty()) return
        assertTrue("Volume picker does not tag the volume as $expected",
            device.wait(Until.findObject(By.text(expected)), timeout) != null)
    }

    private fun allRootDocIds(context: Context): List<String> {
        val ids = mutableListOf<String>()
        context.contentResolver.query(DocumentsContract.buildRootsUri(AUTHORITY), null, null, null, null)?.use { c ->
            val i = c.getColumnIndex(DocumentsContract.Root.COLUMN_DOCUMENT_ID)
            while (i != -1 && c.moveToNext()) c.getString(i)?.let { ids += it }
        }
        return ids
    }

    private fun getRootDocId(context: Context): String? {
        val rootsUri = DocumentsContract.buildRootsUri(AUTHORITY)
        var rootDocId: String? = null
        context.contentResolver.query(rootsUri, null, null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val index = cursor.getColumnIndex(DocumentsContract.Root.COLUMN_DOCUMENT_ID)
                if (index != -1) rootDocId = cursor.getString(index)
            }
        }
        return rootDocId
    }

    /** The root's COLUMN_AVAILABLE_BYTES, which the provider reads live from the filesystem. */
    private fun availableBytes(context: Context, rootDocId: String): Long {
        context.contentResolver.query(DocumentsContract.buildRootsUri(AUTHORITY), null, null, null, null)?.use { cur ->
            val idCol = cur.getColumnIndex(DocumentsContract.Root.COLUMN_DOCUMENT_ID)
            val freeCol = cur.getColumnIndex(DocumentsContract.Root.COLUMN_AVAILABLE_BYTES)
            while (cur.moveToNext()) {
                if (cur.getString(idCol) == rootDocId) return cur.getLong(freeCol)
            }
        }
        throw AssertionError("root $rootDocId not listed")
    }

    private fun assertCannotMountError(expectedFs: String) {
        // The page may need scrolling for the Logs section to be visible/composed
        // (e.g. with a tall expanded mount form above it). Scroll the screen itself
        // rather than relying on the app to auto-scroll, which is both simpler and
        // closer to real user behavior.
        // Crypto is slow on emulator; allow up to 120s for decryption + detection
        // Unsupported filesystems log "Cannot mount: <reason>" (log_cannot_mount_reason).
        var errorLog: androidx.test.uiautomator.UiObject2? = null
        val logDeadline = android.os.SystemClock.uptimeMillis() + 120_000L
        device.swipe(device.displayWidth / 2, device.displayHeight * 3 / 4, device.displayWidth / 2, device.displayHeight / 4, 20)
        while (errorLog == null && android.os.SystemClock.uptimeMillis() < logDeadline) {
            // The tree can lag the screen: seen with this line on the screen and not in the tree for 2 minutes.
            InstrumentationRegistry.getInstrumentation().uiAutomation.clearCache()
            errorLog = device.wait(Until.findObject(By.textContains("Cannot mount:")), 3000L)
        }
        if (errorLog == null) captureScreen("cannot_mount_missing")
        assertTrue("Expected 'Cannot mount:' in app log but it did not appear", errorLog != null)

        if (expectedFs.isNotEmpty()) {
            val fsLog = device.findObject(By.textContains("Detected filesystem: $expectedFs"))
            assertTrue("Expected 'Detected filesystem: $expectedFs' in app log", fsLog != null)
        }

        assertTrue("Drive should not have been mounted for unsupported filesystem",
            OtgMasterState.mountedDrives.isEmpty())
    }
}
