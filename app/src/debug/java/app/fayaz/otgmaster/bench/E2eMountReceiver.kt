package app.fayaz.otgmaster.bench

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import app.fayaz.otgmaster.OtgMasterState

/**
 * Debug-only: mounts or unmounts with no UI, for companion-app E2E suites
 * (Backup Master's drives the extension contract through this).
 *
 * Same mount path as BenchmarkReceiver's "password" extra — every probed
 * candidate is unlocked with the given credentials — without running a
 * benchmark afterwards. MainActivity must be running; it installs the hook.
 *
 *   adb shell am broadcast -a app.fayaz.otgmaster.E2E_MOUNT \
 *       -n app.fayaz.otgmaster/.bench.E2eMountReceiver --es password password123 --es pim 1
 *   adb shell am broadcast -a app.fayaz.otgmaster.E2E_UNMOUNT_ALL \
 *       -n app.fayaz.otgmaster/.bench.E2eMountReceiver
 *
 * Progress is logged under the tag OTGE2E: "mounted N: <names>" once the count
 * of mounted drives has held steady for three seconds.
 */
class E2eMountReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            ACTION_UNMOUNT_ALL -> {
                val unmount = OtgMasterState.unmountAllRequest
                if (unmount == null) Log.w(TAG, "no unmount handler — is MainActivity running?") else unmount()
                Log.i(TAG, "unmount requested")
            }
            ACTION_MOUNT -> {
                val password = intent.getStringExtra("password") ?: return
                val pim = intent.getStringExtra("pim")?.toIntOrNull()
                val handler = OtgMasterState.mountRequest
                if (handler == null) { Log.w(TAG, "no mount handler — is MainActivity running?"); return }
                handler.mount(password, pim, intent.getStringExtra("cipher") ?: "AES", intent.getStringExtra("hash") ?: "SHA-512",
                    intent.getStringExtra("readonly")?.toBooleanStrictOrNull())
                Thread {
                    val deadline = System.currentTimeMillis() + 180_000
                    var last = -1
                    var since = System.currentTimeMillis()
                    while (System.currentTimeMillis() < deadline) {
                        val n = OtgMasterState.mountedDrives.size
                        if (n != last) { last = n; since = System.currentTimeMillis() }
                        else if (n > 0 && System.currentTimeMillis() - since > 3_000) break
                        Thread.sleep(300)
                    }
                    Log.i(TAG, "mounted ${OtgMasterState.mountedDrives.size}: " +
                        OtgMasterState.mountedDrives.joinToString { "${it.id}=${it.name}" })
                }.start()
            }
        }
    }

    companion object {
        const val TAG = "OTGE2E"
        const val ACTION_MOUNT = "app.fayaz.otgmaster.E2E_MOUNT"
        const val ACTION_UNMOUNT_ALL = "app.fayaz.otgmaster.E2E_UNMOUNT_ALL"
    }
}
