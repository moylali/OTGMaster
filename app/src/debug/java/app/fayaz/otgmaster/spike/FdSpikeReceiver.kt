package app.fayaz.otgmaster.spike

import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.os.ParcelFileDescriptor
import android.util.Log
import java.io.FileInputStream
import java.security.MessageDigest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Debug-only. Answers one question: does a FUSE-backed ParcelFileDescriptor created
 * by [FdRelayService] in the :mount0 process still work once relayed here?
 *
 * That decides whether per-mount processes can keep a single DocumentsProvider. A
 * provider authority lives in exactly one process, so if the relay does not work,
 * each drive needs its own authority declared in the manifest — a fixed slot count,
 * and a drive appearing in SAF as a separate provider rather than a separate root.
 *
 *   adb shell am broadcast -a app.fayaz.otgmaster.RUN_FD_SPIKE \
 *       -n app.fayaz.otgmaster/.spike.FdSpikeReceiver
 */
class FdSpikeReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        Thread {
            runCatching { runSpike(context.applicationContext) }
                .onFailure { Log.e(TAG, "spike failed", it); emit("SPIKE FAILED: $it") }
        }.apply { name = "FdSpike" }.start()
    }

    private fun emit(line: String) { Log.i(TAG, line) }

    private fun runSpike(context: Context) {
        emit("=== FD relay spike ===")
        emit("caller pid=${android.os.Process.myPid()}")

        val latch = CountDownLatch(1)
        var service: IFdRelay? = null
        val conn = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
                service = IFdRelay.Stub.asInterface(binder); latch.countDown()
            }
            override fun onServiceDisconnected(name: ComponentName?) { service = null }
        }
        val bound = context.bindService(
            Intent(context, FdRelayService::class.java), conn, Context.BIND_AUTO_CREATE)
        if (!bound || !latch.await(15, TimeUnit.SECONDS)) {
            return emit("RESULT: could not bind the worker service")
        }
        val svc = service ?: return emit("RESULT: bound but no interface")

        val workerPid = svc.servicePid()
        emit("worker pid=$workerPid  (must differ from caller for this to mean anything)")
        if (workerPid == android.os.Process.myPid()) {
            emit("RESULT: INCONCLUSIVE — android:process did not take effect")
            context.unbindService(conn); return
        }
        val expected = svc.expectedSha256()

        // --- 1 & 2: does the relayed FD read, and where do the callbacks run?
        var pass1 = false
        runCatching {
            val pfd = svc.openProxy("r", true)
            val got = readAll(pfd)
            pfd.close()
            val sha = MessageDigest.getInstance("SHA-256").digest(got)
                .joinToString("") { "%02x".format(it) }
            emit("read ${got.size} bytes through the relayed fd")
            emit("sha256 ${if (sha == expected) "matches" else "DIFFERS"} " +
                 "(expected ${expected.take(16)}…)")
            pass1 = got.size == FdRelayService.PAYLOAD_BYTES && sha == expected
            svc.callbackLog().take(4).forEach { emit("  worker: $it") }
            val ranInWorker = svc.callbackLog().any { it.contains("pid=$workerPid") }
            emit("callbacks ran in the worker process: $ranInWorker")
            pass1 = pass1 && ranInWorker
        }.onFailure { emit("relayed read threw: $it") }
        svc.releaseRetained()

        // --- 4: lifetime. Worker drops its copy immediately; does ours still read?
        var pass4 = false
        runCatching {
            val pfd = svc.openProxy("r", false)   // worker retains nothing
            System.gc()                            // invite the worker's copy to vanish
            Thread.sleep(300)
            val got = readAll(pfd)
            pfd.close()
            pass4 = got.size == FdRelayService.PAYLOAD_BYTES
            emit("after the worker dropped its own copy: read ${got.size} bytes")
        }.onFailure { emit("lifetime case threw: $it") }

        // --- 5: write path
        var pass5 = false
        runCatching {
            val pfd = svc.openProxy("rw", true)
            java.io.FileOutputStream(pfd.fileDescriptor).use { it.write(ByteArray(4096) { 0x5A }) }
            pfd.close()
            val sawWrite = svc.callbackLog().any { it.startsWith("onWrite") }
            emit("write path reached the worker: $sawWrite")
            pass5 = sawWrite
        }.onFailure { emit("write case threw: $it") }
        svc.releaseRetained()

        // --- 3: onRelease timing — must fire when the reader closes, not before.
        runCatching {
            val pfd = svc.openProxy("r", true)
            val beforeClose = svc.callbackLog().any { it.startsWith("onRelease") }
            pfd.close()
            Thread.sleep(500)
            val afterClose = svc.callbackLog().any { it.startsWith("onRelease") }
            emit("onRelease before our close=$beforeClose, after=$afterClose")
        }.onFailure { emit("release case threw: $it") }
        svc.releaseRetained()

        // --- 6: worker death mid-read. A hung fd in a file manager is worse than a
        // clean failure, so this must error rather than block forever.
        var pass6 = false
        runCatching {
            val pfd = svc.openProxy("r", true)
            val ins = FileInputStream(pfd.fileDescriptor)
            val first = ins.read(ByteArray(4096))
            emit("read $first bytes, now killing worker pid=$workerPid")
            android.os.Process.killProcess(workerPid)

            val done = CountDownLatch(1)
            val outcome = java.util.concurrent.atomic.AtomicReference("still blocked after 10s")
            Thread {
                outcome.set(try {
                    var total = first
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        val n = ins.read(buf)
                        if (n <= 0) break
                        total += n
                    }
                    "returned EOF after $total bytes"
                } catch (e: Throwable) {
                    "threw ${e.javaClass.simpleName}"
                })
                done.countDown()
            }.apply { isDaemon = true }.start()

            pass6 = done.await(10, TimeUnit.SECONDS)
            emit("after worker death: ${outcome.get()}")
            runCatching { pfd.close() }
        }.onFailure { emit("worker-death case threw on setup: $it") }

        emit("VERDICT relay-reads=$pass1 lifetime=$pass4 write=$pass5 workerDeath=$pass6")
        emit("=== spike finished ===")
        context.unbindService(conn)
    }

    private fun readAll(pfd: ParcelFileDescriptor): ByteArray {
        FileInputStream(pfd.fileDescriptor).use { ins ->
            val out = java.io.ByteArrayOutputStream()
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = ins.read(buf)
                if (n <= 0) break
                out.write(buf, 0, n)
            }
            return out.toByteArray()
        }
    }

    private companion object { const val TAG = "FdRelaySpike" }
}
