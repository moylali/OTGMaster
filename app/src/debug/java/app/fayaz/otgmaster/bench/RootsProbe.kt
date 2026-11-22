package app.fayaz.otgmaster.bench

import android.content.Context
import android.provider.DocumentsContract

/**
 * Times the app's own drive list (DocumentsProvider.queryRoots, through the ContentResolver as
 * the Files app reaches it) once a second while bigwrite runs, and reports the slowest answer.
 *
 * Two ANRs on the Galaxy Tab A9+ (11-21 and 11-22, build b65821c) came from that query waiting
 * on a volume's lock while bigwrite deleted the previous 2.2 GB big.bin. adb cannot run the query
 * itself (the provider only answers the app and the Files app), so the benchmark does.
 */
class RootsProbe private constructor(private val context: Context) {
    @Volatile private var running = true
    @Volatile private var count = 0
    @Volatile private var maxMs = 0L
    @Volatile private var over1s = 0
    private val thread = Thread({ loop() }, "OTGBenchRootsProbe").apply { isDaemon = true }

    private fun loop() {
        val uri = DocumentsContract.buildRootsUri(context.packageName + ".documents")
        while (running) {
            val t0 = System.nanoTime()
            runCatching { context.contentResolver.query(uri, null, null, null, null)?.use { it.count } }
            val ms = (System.nanoTime() - t0) / 1_000_000
            count++
            if (ms > maxMs) maxMs = ms
            if (ms > 1000) over1s++
            runCatching { Thread.sleep(1000) }
        }
    }

    /** Stops the probe and returns its report line. */
    fun stop(): String {
        running = false
        thread.join(30_000)
        return "roots query   : $count during bigwrite, slowest $maxMs ms, $over1s over 1 s"
    }

    companion object {
        fun start(context: Context) = RootsProbe(context.applicationContext).also { it.thread.start() }
    }
}
