package app.fayaz.otgmaster.bench

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import app.fayaz.otgmaster.OtgMasterState

/**
 * Runs the I/O benchmark against whatever is currently mounted.
 *
 * Declared in the debug manifest only, so release builds have neither this class
 * nor Benchmark nor the native counters they read (see OTG_IO_STATS in
 * src/main/cpp/CMakeLists.txt).
 *
 * Reads mount state from OtgMasterState, which is a process-wide singleton, so
 * this needs no reference to MainActivity.
 *
 * Passing a "password" extra (with optional "pim", "cipher", "hash") makes the
 * run mount first, so nothing is needed beyond the drive being attached:
 *
 *   ... RUN_BENCHMARK -n app.fayaz.otgmaster/.bench.BenchmarkReceiver
 *       --es password password123 --es pim 1
 *
 * The component must be named explicitly. Manifest-declared receivers have not
 * received implicit broadcasts since Android 8, and an action-only broadcast is
 * reported as "Broadcast completed: result=0" while being silently dropped:
 *
 *   adb shell am broadcast -a app.fayaz.otgmaster.RUN_BENCHMARK \
 *       -n app.fayaz.otgmaster/.bench.BenchmarkReceiver
 *
 *   adb shell am broadcast -a app.fayaz.otgmaster.RUN_BENCHMARK \
 *       -n app.fayaz.otgmaster/.bench.BenchmarkReceiver --es tests "free,block,seq"
 */
class BenchmarkReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val only = intent.getStringExtra("tests")
            ?.split(",")?.map { it.trim() }?.filter { it.isNotEmpty() }?.toSet()
            ?: emptySet()

        // Optional: mount before measuring, so a run needs nothing beyond the drive
        // being physically attached.
        val mount = intent.getStringExtra("password")?.let { pw ->
            Benchmark.MountCredentials(
                password = pw,
                pim = intent.getStringExtra("pim")?.toIntOrNull()
                    ?: intent.getIntExtra("pim", -1).takeIf { it >= 0 },
                cipher = intent.getStringExtra("cipher") ?: "AES",
                hash = intent.getStringExtra("hash") ?: "SHA-512",
                // A run that writes mounts read-write whatever the phone's "read-only
                // by default" says; --es readonly true|false overrides either way.
                readOnly = intent.getStringExtra("readonly")?.toBooleanStrictOrNull()
                    ?: if (only.any { it in setOf("write", "unaligned", "correct", "saf") }) false else null,
            )
        }

        // Cache configuration, applied at mount time. "off" disables it; a
        // readahead value in bytes overrides the per-filesystem default.
        val cacheArg = intent.getStringExtra("cache")
        val readAheadArg = intent.getStringExtra("readahead")?.toIntOrNull()
        if (cacheArg != null || readAheadArg != null) {
            OtgMasterState.cacheConfig = OtgMasterState.CacheConfig(
                enabled = !cacheArg.equals("off", ignoreCase = true),
                readAheadBytes = readAheadArg,
            )
        }
        val remount = intent.getStringExtra("remount")?.equals("true", true) == true ||
            cacheArg != null || readAheadArg != null

        // Deliberately NOT goAsync(): that keeps the broadcast open until finish()
        // and the receiver timeout still applies (~10s in the foreground), so a
        // multi-minute run ANRs the app. Return immediately instead and let the
        // thread outlive the broadcast — the process stays alive because the
        // activity holding the mount is in the foreground.
        Thread {
            try {
                Log.i("OTGBench", "benchmark starting (tests=${if (only.isEmpty()) "all" else only})")
                Benchmark.runAll(
                    context.applicationContext, only, mount, remount,
                    // "all" (default), indices, or label substrings: "0", "VCFAT",
                    // "0,1". Every mounted drive runs in sequence when unset.
                    intent.getStringExtra("drive"),
                )
            } catch (e: Throwable) {
                Log.e("OTGBench", "benchmark failed", e)
            }
        }.apply { name = "OTGBench"; isDaemon = false }.start()
    }
}
