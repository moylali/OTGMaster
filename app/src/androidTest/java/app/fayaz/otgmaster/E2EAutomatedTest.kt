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
        val intent = context.packageManager.getLaunchIntentForPackage("app.fayaz.otgmaster")?.apply {
            addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK)
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
        val scanButton = device.wait(Until.findObject(By.textContains("Scan")), timeout)
            ?: device.wait(Until.findObject(By.descContains("Scan")), timeout)
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
                val scanButton = device.wait(Until.findObject(By.textContains("Scan")), timeout)
                    ?: device.wait(Until.findObject(By.descContains("Scan")), timeout)
                scanButton?.click()
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

            assertTrue("USB device not detected — password input not shown within 30s", passwordField != null)

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
                val pimField = device.wait(Until.findObject(By.descContains("pim_input")), timeout)
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

                // Wait for SAF picker
                val fileObject = device.wait(Until.findObject(By.textContains(keyfile)), timeout * 2)
                fileObject?.click()
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
            val mountButton = device.wait(Until.findObject(By.descContains("mount_button")), timeout)
                ?: device.wait(Until.findObject(By.textContains("Unlock & Mount")), timeout)
            assertTrue("Mount button not found", mountButton != null)
            assertTrue("Mount button is not enabled", mountButton!!.isEnabled)
            mountButton.click()

            if (!expectMount) {
                assertCannotMountError(expectedFs)
                return
            }

            // Wait for "Mounted" state. Increase timeout to 300s due to slow emulator crypto performance.
            val mountedText = device.wait(Until.findObject(By.textContains("Used")), 300000L)
            assertTrue("Drive was not successfully mounted!", mountedText != null)

            // Verify capacity is populated
            val usedText = mountedText?.text ?: ""
            assertTrue("Used capacity should not be empty", usedText.contains("Used:"))
            val freeTextObj = device.findObject(By.textContains("Free:"))
            assertTrue("Free capacity should be visible", freeTextObj != null)

            val context = ApplicationProvider.getApplicationContext<Context>()
            
            // Dynamically find the docId for flower.jpg since it is now prepended with a dynamic drive ID
            val rootsUri = android.provider.DocumentsContract.buildRootsUri("app.fayaz.otgmaster.documents")
            var rootDocId: String? = null
            context.contentResolver.query(rootsUri, null, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val index = cursor.getColumnIndex(android.provider.DocumentsContract.Root.COLUMN_DOCUMENT_ID)
                    if (index != -1) rootDocId = cursor.getString(index)
                }
            }
            assertTrue("No roots found from DocumentsProvider", rootDocId != null)

            val childrenUri = android.provider.DocumentsContract.buildChildDocumentsUri("app.fayaz.otgmaster.documents", rootDocId)
            var flowerDocId: String? = null
            var spaceFileDocId: String? = null
            context.contentResolver.query(childrenUri, null, null, null, null)?.use { cursor ->
                while (cursor.moveToNext()) {
                    val docIdIndex = cursor.getColumnIndex(android.provider.DocumentsContract.Document.COLUMN_DOCUMENT_ID)
                    val docId = cursor.getString(docIdIndex)
                    if (docId != null) {
                        if (docId.endsWith("flower.jpg")) flowerDocId = docId
                        if (docId.endsWith("file with spaces.txt")) spaceFileDocId = docId
                    }
                }
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
        val otgIntent = context.packageManager.getLaunchIntentForPackage("app.fayaz.otgmaster")?.apply {
            addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
        context.startActivity(otgIntent)
        device.wait(Until.hasObject(By.pkg("app.fayaz.otgmaster").depth(0)), timeout * 2)

        // Pause here so a human watching the emulator can see OTGMaster back in front before the unmount click
        android.os.SystemClock.sleep(2000)

        val unmountButton = device.wait(Until.findObject(By.descContains("unmount_button")), timeout)
        assertTrue("Unmount button not found", unmountButton != null)
        unmountButton?.click()

        val unmountGone = device.wait(Until.gone(By.descContains("unmount_button")), timeout)
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
        val scanBtn1 = device.wait(Until.findObject(By.textContains("Scan")), timeout)
            ?: device.wait(Until.findObject(By.descContains("Scan")), timeout)
        scanBtn1?.click()
        device.wait(Until.findObject(By.text(java.util.regex.Pattern.compile("(?i)Allow|OK"))), 5000L)?.click()

        assertTrue("Mount #1 failed", doMount(password, pim, testCase, clearFields = false))

        // ── WRITE: create file + directory + nested file ───────────────────────
        val rootDocId1 = getRootDocId(context)
        assertNotNull("No root after mount #1", rootDocId1)
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

        doUnmount()

        // ── REMOUNT #2 — verify write persistence ─────────────────────────────
        val scanBtn2 = device.wait(Until.findObject(By.textContains("Scan")), timeout)
            ?: device.wait(Until.findObject(By.descContains("Scan")), timeout)
        scanBtn2?.click()
        android.os.SystemClock.sleep(2000)

        assertTrue("Mount #2 failed", doMount(password, pim, testCase, clearFields = true))

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
        val scanBtn3 = device.wait(Until.findObject(By.textContains("Scan")), timeout)
            ?: device.wait(Until.findObject(By.descContains("Scan")), timeout)
        scanBtn3?.click()
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
        if (!clickByDesc("password_input", waitMs = 10000L)) return false
        android.os.SystemClock.sleep(500)
        if (clearFields) {
            repeat(50) { device.executeShellCommand("input keyevent KEYCODE_DEL") }
            android.os.SystemClock.sleep(200)
        }
        device.executeShellCommand("input text $password")
        android.os.SystemClock.sleep(500)

        if (pim.isNotEmpty()) {
            val pimField = device.wait(Until.findObject(By.descContains("pim_input")), timeout)
            pimField?.click()
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

        val mountButton = device.wait(Until.findObject(By.descContains("mount_button")), timeout)
            ?: device.wait(Until.findObject(By.textContains("Unlock & Mount")), timeout)
        if (mountButton == null || !mountButton.isEnabled) return false
        mountButton.click()

        return device.wait(Until.findObject(By.textContains("Used")), 300000L) != null
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
        val gone = device.wait(Until.gone(By.descContains("unmount_button")), timeout)
        assertTrue("Drive was not successfully unmounted!", gone)
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
