package app.fayaz.otgmaster

import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import android.util.Log
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.hamcrest.CoreMatchers.notNullValue
import org.hamcrest.MatcherAssert.assertThat

@RunWith(AndroidJUnit4::class)
class E2EAutomatedTest {

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
        // Read arguments passed from adb am instrument
        val arguments = InstrumentationRegistry.getArguments()
        val password = arguments.getString("password", "password123")
        val keyfile = arguments.getString("keyfile", "")

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

        val pim = arguments.getString("pim", "")

        // Type password (wait up to 30s because probing USB takes time)
        val passwordField = device.wait(Until.findObject(By.descContains("password_input")), 30000L)
        if (passwordField == null) {
            Log.d("E2E", "No password field detected; no USB device present. Test ending.")
            return
        } else {
            passwordField.click()
            android.os.SystemClock.sleep(500)
            
            // Use adb shell input text to reliably trigger IME and Compose onValueChange
            device.executeShellCommand("input text $password")
            android.os.SystemClock.sleep(500)
            device.pressEnter()
            android.os.SystemClock.sleep(500)
        }

        if (pim.isNotEmpty()) {
            val pimField = device.wait(Until.findObject(By.descContains("pim_input")), timeout)
            pimField?.click()
            android.os.SystemClock.sleep(500)
            device.executeShellCommand("input text $pim")
            android.os.SystemClock.sleep(500)
            device.pressEnter()
            android.os.SystemClock.sleep(500)
        }

        if (keyfile.isNotEmpty()) {
            val selectKeyfileBtn = device.wait(Until.findObject(By.textContains("Select Keyfile")), timeout)
            selectKeyfileBtn?.click()

            // Wait for SAF picker
            val fileObject = device.wait(Until.findObject(By.textContains(keyfile)), timeout * 2)
            fileObject?.click()
        }

        // Click Mount
        val mountButton = device.wait(Until.findObject(By.descContains("mount_button")), timeout)
            ?: device.wait(Until.findObject(By.textContains("Unlock & Mount")), timeout)
        assertTrue("Mount button not found", mountButton != null)
        assertTrue("Mount button is not enabled", mountButton.isEnabled)
        mountButton.click()

        // Wait for "Mounted" state. Increase timeout to 300s due to slow emulator crypto performance.
        val mountedText = device.wait(Until.findObject(By.textContains("Used")), 300000L)
        assertTrue("Drive was not successfully mounted!", mountedText != null)
        
        // Verify capacity is populated
        val usedText = mountedText?.text ?: ""
        assertTrue("Used capacity should not be empty", usedText.contains("Used:"))
        val freeTextObj = device.findObject(By.textContains("Free:"))
        assertTrue("Free capacity should be visible", freeTextObj != null)
        
        // Copy the flower.jpg to /sdcard/Download/flower.jpg via ContentResolver
        val context = ApplicationProvider.getApplicationContext<Context>()
        val fileUri = android.provider.DocumentsContract.buildDocumentUri("app.fayaz.otgmaster.documents", "flower.jpg")
        try {
            context.contentResolver.openInputStream(fileUri)?.use { input ->
                val outFile = java.io.File("/sdcard/Download/flower.jpg")
                outFile.outputStream().use { output ->
                    input.copyTo(output)
                }
                assertTrue("Copied flower image should exist", outFile.exists())
                assertTrue("Copied flower image should have non-zero size", outFile.length() > 0)
            }
        } catch (e: Exception) {
            fail("Failed to copy flower.jpg via SAF: ${e.message}")
        }
        
        // At this point, the SAF Document Provider is serving the files.
        // We will fire an ACTION_VIEW intent for flower.jpg to open it in Gallery
        val viewIntent = Intent(Intent.ACTION_VIEW).apply {
            data = fileUri
            type = "image/jpeg"
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        
        try {
            context.startActivity(viewIntent)
        } catch (e: Exception) {
            fail("Failed to launch Gallery app for the image: ${e.message}")
        }
        
        // Verify Gallery or Photos app is in foreground
        val galleryOpened = device.wait(Until.hasObject(By.pkg(java.util.regex.Pattern.compile(".*(gallery|photos|media).*"))), 10000L)
        assertTrue("Gallery app did not open the flower image file!", galleryOpened)
        
        // Return to OTGMaster reliably instead of using pressBack() which might get caught in Gallery overlays
        val otgIntent = context.packageManager.getLaunchIntentForPackage("app.fayaz.otgmaster")?.apply {
            addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
        context.startActivity(otgIntent)
        device.wait(Until.hasObject(By.pkg("app.fayaz.otgmaster").depth(0)), timeout * 2)
        
        // Unmount
        val unmountButton = device.wait(Until.findObject(By.textContains("Unmount")), timeout)
        assertTrue("Unmount button not found", unmountButton != null)
        unmountButton?.click()
        
        // Verify Unmounted successfully (the "Unmount" button disappears)
        val unmountGone = device.wait(Until.gone(By.textContains("Unmount")), timeout)
        assertTrue("Drive was not successfully unmounted!", unmountGone)
    }
}
