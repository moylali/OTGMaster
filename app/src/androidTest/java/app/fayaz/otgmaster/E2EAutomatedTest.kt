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

        /**
         * The large write-case file: block i is SHA-256("otg-e2e-big-<i>"), 32 bytes,
         * for 3 MiB. scripts/verify_e2e_volume.py generates the same bytes.
         */
        const val BIG_FILE_NAME = "big_write.bin"
        const val BIG_FILE_BLOCKS = 3 * 1024 * 1024 / 32

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
        val password = arguments.getString("password", "password123")
        val testCase = arguments.getString("testCase", "exfat")

        // The slot device (/dev/block/sda) is already overwritten by the bash script before this test runs.
        android.os.SystemClock.sleep(1000)

        val keyfile = arguments.getString("keyfile", "")
        val pim = arguments.getString("pim", "")
        val expectMount = arguments.getString("expect_mount", "true") == "true"
        val expectedFs = arguments.getString("expected_fs", "")
        val cipher = arguments.getString("cipher", "")

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
            device.executeShellCommand("input text $password")
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

            if (keyfile.isNotEmpty()) {
                val selectKeyfileBtn = device.wait(Until.findObject(By.textContains("Select Keyfile")), timeout)
                selectKeyfileBtn?.click()

                // Wait for DocumentsUI to open
                device.wait(Until.hasObject(By.pkg("com.google.android.documentsui")), timeout)
                android.os.SystemClock.sleep(1000)

                // Try to find the file in current view (Recent); if not found, navigate to Downloads
                var fileObject = device.findObject(By.textContains(keyfile))
                if (fileObject == null) {
                    val hamburger = device.findObject(By.descContains("Show roots"))
                        ?: device.findObject(By.descContains("Navigate up"))
                        ?: device.findObject(By.descContains("Open navigation drawer"))
                    hamburger?.click()
                    android.os.SystemClock.sleep(500)
                    val downloadsItem = device.wait(Until.findObject(By.textContains("Download")), timeout)
                    downloadsItem?.click()
                    android.os.SystemClock.sleep(500)
                    fileObject = device.wait(Until.findObject(By.textContains(keyfile)), timeout * 2)
                }
                fileObject?.click()
                // Wait for picker to close and return to our app
                device.wait(Until.hasObject(By.pkg("app.fayaz.otgmaster")), timeout)
                android.os.SystemClock.sleep(500)
            }

            if (cipher.isNotEmpty() && cipher != "AES") {
                val cipherPicker = device.wait(Until.findObject(By.descContains("cipher_picker")), timeout)
                assertTrue("Cipher picker not found", cipherPicker != null)
                cipherPicker?.click()
                android.os.SystemClock.sleep(500)
                val cipherOption = device.wait(Until.findObject(By.textContains(cipher)), timeout)
                assertTrue("Cipher option '$cipher' not found in picker", cipherOption != null)
                cipherOption?.click()
                android.os.SystemClock.sleep(500)
            }

            // Click Mount
            val clicked = clickMountButton()
            if (clicked != MountClick.CLICKED) captureScreen("mount_button_$clicked")
            assertTrue("Mount button not found", clicked != MountClick.NOT_FOUND)
            assertTrue("Mount button is not enabled", clicked != MountClick.DISABLED)

            if (!expectMount) {
                assertCannotMountError(expectedFs)
                return
            }

            // Wait for "Mounted" state. Increase timeout to 300s due to slow emulator crypto performance.
            val mountedText = waitForMounted(300000L)
            assertTrue("Drive was not successfully mounted!", mountedText != null)

            // Verify capacity is populated
            val usedText = mountedText?.text ?: ""
            assertTrue("Used capacity should not be empty", usedText.contains("Used:"))
            val freeTextObj = device.findObject(By.textContains("Free:"))
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

    // ── helpers ──────────────────────────────────────────────────────────────

    private fun doMount(password: String, pim: String, testCase: String, clearFields: Boolean): Boolean {
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

        if (testCase == "partitioned_mbr") {
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
        device.executeShellCommand("input text $password")
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

        val clicked = clickMountButton()
        if (clicked != MountClick.CLICKED) return false.also { captureScreen("mount_button_$clicked") }

        return waitForMounted(300000L) != null
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
        val unmountButton = device.wait(Until.findObject(By.descContains("unmount_button")), timeout)
        assertTrue("Unmount button not found", unmountButton != null)
        unmountButton?.click()
        val gone = device.wait(Until.gone(By.descContains("unmount_button")), UNMOUNT_WAIT_MS)
        if (!gone) captureScreen("unmount_not_gone")
        assertTrue("Drive was not successfully unmounted!", gone)
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
        // Not found: the loop has left the page at the top again.
        captureScreen("not_found_after_scrolling")
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
    private fun findScanButton(): androidx.test.uiautomator.UiObject2? {
        scrollToTop()
        return device.wait(Until.findObject(By.textContains("Scan").clickable(true)), timeout)
            ?: device.wait(Until.findObject(By.descContains("scan_button")), timeout)
    }

    private enum class MountClick { CLICKED, NOT_FOUND, DISABLED }

    /**
     * Finds and clicks Unlock & Mount, finding it again if it goes stale. Compose
     * can redraw the form between the find and the click — partitioned_mbr once
     * failed with StaleObjectException on `isEnabled` while the form was being
     * re-offered — and a stale handle says nothing about the app.
     */
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
        findScrolling(By.descContains("mount_button"), desc = "mount_button")
            ?: findScrolling(By.textContains("Unlock & Mount"), text = "Unlock & Mount", firstWaitMs = 0L)

    /** Waits up to [timeoutMs] for a mounted card ("Used:"), scrolling up to it. */
    private fun waitForMounted(timeoutMs: Long): androidx.test.uiautomator.UiObject2? {
        val deadline = android.os.SystemClock.uptimeMillis() + timeoutMs
        while (android.os.SystemClock.uptimeMillis() < deadline) {
            device.wait(Until.findObject(By.textContains("Used")), 5000L)?.let { return it }
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

    private fun assertCannotMountError(expectedFs: String) {
        // The page may need scrolling for the Logs section to be visible/composed
        // (e.g. with a tall expanded mount form above it). Scroll the screen itself
        // rather than relying on the app to auto-scroll, which is both simpler and
        // closer to real user behavior.
        device.swipe(device.displayWidth / 2, device.displayHeight - 200, device.displayWidth / 2, 200, 20)
        android.os.SystemClock.sleep(500)

        // Crypto is slow on emulator; allow up to 120s for decryption + detection
        // Unsupported filesystems log "Cannot mount: <reason>" (log_cannot_mount_reason).
        val errorLog = device.wait(Until.findObject(By.textContains("Cannot mount:")), 120000L)
        assertTrue("Expected 'Cannot mount:' in app log but it did not appear", errorLog != null)

        if (expectedFs.isNotEmpty()) {
            val fsLog = device.findObject(By.textContains("Detected filesystem: $expectedFs"))
            assertTrue("Expected 'Detected filesystem: $expectedFs' in app log", fsLog != null)
        }

        assertNull("Drive should not have been mounted for unsupported filesystem",
            device.findObject(By.textContains("Used:")))
    }
}
