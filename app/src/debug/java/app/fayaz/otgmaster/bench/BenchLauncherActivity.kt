package app.fayaz.otgmaster.bench

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import app.fayaz.otgmaster.OtgMasterState

/**
 * Debug-only, on-device benchmark trigger for phones where adb over wifi is not
 * available — Android 9 and earlier have no Wireless Debugging, and legacy
 * `adb tcpip` does not survive losing the USB transport, which is exactly the
 * transport the drive occupies.
 *
 * Its own launcher entry, because it cannot be reached any other way on such a
 * device. Debug source set only, so release builds have neither the activity nor
 * the manifest entry.
 *
 * Holds FLAG_KEEP_SCREEN_ON for the whole run. That is not a convenience: with the
 * screen off the CPU throttles and decryption is CPU-bound, measured at 7.27 MB/s
 * against 17.26 MB/s awake on the block-layer control. A dozed run reads as a
 * regression, which is how one was first misread (docs/IO_PERFORMANCE.md §5.7).
 *
 * Results go to BENCH/reports/ on the drive itself, so they travel with it — see
 * Benchmark.save.
 */
class BenchLauncherActivity : Activity() {

    private lateinit var status: TextView
    private val password = "password123"

    override fun onCreate(saved: Bundle?) {
        super.onCreate(saved)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 48, 32, 32)
        }
        root.addView(TextView(this).apply {
            text = "OTG Master — on-device benchmark"
            textSize = 20f
        })
        root.addView(TextView(this).apply {
            text = "Screen stays on while this screen is open. Reports are written to " +
                   "BENCH/reports/ on the drive, and to Documents/."
            textSize = 12f
            setPadding(0, 12, 0, 24)
        })

        val cache = CheckBox(this).apply {
            text = "Block cache enabled"
            isChecked = true
        }
        root.addView(cache)

        status = TextView(this).apply {
            textSize = 11f
            setPadding(0, 24, 0, 0)
            gravity = Gravity.START
        }

        // Correctness first in the list, deliberately: it is the run that matters when
        // nobody is watching, and the one whose result is meaningful without a
        // baseline to compare against.
        fun run(label: String, tests: String) {
            root.addView(Button(this).apply {
                text = label
                setOnClickListener { launch(tests, cache.isChecked) }
            })
        }
        run("Correctness + unaligned (data integrity)", "unaligned,correct")
        run("Correctness + write verification", "unaligned,correct,write")
        run("Reads (dir, seq, random, opens)", "free,block,dir,path,seq,random,opens")
        run("SAF path", "saf")
        run("Everything", "free,block,dir,path,seq,random,opens,unaligned,correct,write,saf")

        root.addView(status)
        setContentView(ScrollView(this).apply { addView(root) })

        OtgMasterState.logSink = { line -> runOnUiThread { appendStatus(line) } }
    }

    private fun appendStatus(line: String) {
        status.text = (status.text.toString() + "\n" + line).takeLast(4000)
    }

    /**
     * Starts MainActivity first: it owns the mount handler the benchmark calls
     * through, and without it a run ends immediately with "no mount handler
     * installed". Then broadcasts to BenchmarkReceiver, reusing the same path the
     * adb-driven runs take rather than a second code path that could drift.
     */
    private fun launch(tests: String, cacheEnabled: Boolean) {
        // Mount here rather than leaving it to the benchmark's own request.
        //
        // That request probes USB and needs permission, and a permission dialog needs
        // a resumed activity — but this screen holds the front so the screen stays on,
        // which leaves MainActivity in the background unable to show one. On a cold
        // start the run then sat for its full 90-second mount timeout and reported
        // "no probed candidates", which says nothing about why.
        //
        // Mounting in OTG Master first is also the natural order for a human: unlock
        // the drive there, then come here to measure it.
        if (OtgMasterState.mountedDrives.isEmpty()) {
            appendStatus("No drive is mounted.")
            appendStatus("Open OTG Master, unlock the drive there, then come back and " +
                         "tap again. Opening it now…")
            startActivity(
                Intent(this, Class.forName("app.fayaz.otgmaster.MainActivity")).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
            )
            return
        }

        appendStatus("starting: $tests (cache ${if (cacheEnabled) "on" else "off"})")
        appendStatus("mounted: " + OtgMasterState.mountedDrives.joinToString { it.name })
        Thread {
            Thread.sleep(500)
            runOnUiThread {
                sendBroadcast(
                    Intent("app.fayaz.otgmaster.RUN_BENCHMARK").apply {
                        setClass(this@BenchLauncherActivity, BenchmarkReceiver::class.java)
                        putExtra("password", password)
                        putExtra("pim", "1")
                        putExtra("cipher", "AES")
                        putExtra("hash", "SHA-512")
                        putExtra("tests", tests)
                        putExtra("cache", if (cacheEnabled) "default" else "off")
                        putExtra("remount", "true")
                    }
                )
            }
        }.apply { isDaemon = true }.start()
    }

    override fun onDestroy() {
        OtgMasterState.logSink = null
        super.onDestroy()
    }
}
