package app.fayaz.otgmaster.spike

import android.app.Service
import android.content.Intent
import android.os.Handler
import android.os.HandlerThread
import android.os.ParcelFileDescriptor
import android.os.ProxyFileDescriptorCallback
import android.os.storage.StorageManager
import android.util.Log
import java.security.MessageDigest

/**
 * Debug-only worker running in its own process, standing in for a per-drive mount
 * process.
 *
 * It serves a generated in-memory payload rather than a USB drive on purpose: the
 * question under test is whether a FUSE-backed ParcelFileDescriptor created here
 * survives being relayed through another process, and mixing USB into that would
 * confuse a negative result with a storage problem.
 */
class FdRelayService : Service() {

    private val payload = ByteArray(PAYLOAD_BYTES) { (it * 31 % 251).toByte() }
    private val log = java.util.Collections.synchronizedList(mutableListOf<String>())
    private var retained: ParcelFileDescriptor? = null

    private val handler: Handler by lazy {
        HandlerThread("FdRelayCallbacks").apply { start() }.let { Handler(it.looper) }
    }

    private val binder = object : IFdRelay.Stub() {

        override fun servicePid(): Int = android.os.Process.myPid()

        override fun callbackLog(): List<String> = ArrayList(log)

        override fun expectedSha256(): String =
            MessageDigest.getInstance("SHA-256").digest(payload)
                .joinToString("") { "%02x".format(it) }

        override fun releaseRetained() {
            runCatching { retained?.close() }
            retained = null
            log += "worker closed its retained PFD"
        }

        override fun openProxy(mode: String, keepOpen: Boolean): ParcelFileDescriptor {
            log.clear()
            val sm = getSystemService(StorageManager::class.java)
            val callback = object : ProxyFileDescriptorCallback() {
                override fun onGetSize(): Long {
                    log += "onGetSize pid=${android.os.Process.myPid()} " +
                           "thread=${Thread.currentThread().name}"
                    return payload.size.toLong()
                }

                override fun onRead(offset: Long, size: Int, data: ByteArray): Int {
                    if (log.size < LOG_CAP) {
                        log += "onRead off=$offset size=$size " +
                               "pid=${android.os.Process.myPid()} " +
                               "thread=${Thread.currentThread().name}"
                    }
                    if (offset >= payload.size) return 0
                    val n = minOf(size.toLong(), payload.size - offset).toInt()
                    System.arraycopy(payload, offset.toInt(), data, 0, n)
                    return n
                }

                override fun onWrite(offset: Long, size: Int, data: ByteArray): Int {
                    if (log.size < LOG_CAP) {
                        log += "onWrite off=$offset size=$size pid=${android.os.Process.myPid()}"
                    }
                    val n = minOf(size.toLong(), payload.size - offset).toInt().coerceAtLeast(0)
                    if (n > 0) System.arraycopy(data, 0, payload, offset.toInt(), n)
                    return size
                }

                override fun onFsync() { log += "onFsync pid=${android.os.Process.myPid()}" }

                override fun onRelease() {
                    log += "onRelease pid=${android.os.Process.myPid()}"
                    Log.i(TAG, "onRelease in pid ${android.os.Process.myPid()}")
                }
            }

            val pfdMode = if (mode == "rw") {
                ParcelFileDescriptor.MODE_READ_WRITE
            } else {
                ParcelFileDescriptor.MODE_READ_ONLY
            }
            val pfd = sm.openProxyFileDescriptor(pfdMode, callback, handler)
            log += "created pfd fd=${pfd.fd} pid=${android.os.Process.myPid()}"
            // The lifetime question: hold our copy, or drop it the moment we return.
            if (keepOpen) retained = pfd
            return pfd
        }
    }

    override fun onBind(intent: Intent?) = binder

    companion object {
        private const val TAG = "FdRelaySpike"
        /** Small enough to read quickly, large enough to force many callbacks. */
        const val PAYLOAD_BYTES = 512 * 1024
        private const val LOG_CAP = 6
    }
}
