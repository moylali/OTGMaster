package app.fayaz.otgmaster.veracrypt

import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean

/** Which of VeraCrypt's volume types a mount is: its "Type" column reads Normal, Hidden or Outer. */
enum class VeraCryptVolumeType { NORMAL, HIDDEN, OUTER }

/** The hidden volume's credentials, typed to protect it while its outer volume is mounted. */
class HiddenVolumeCredentials(
    val password: CharArray,
    val pim: Int?,
    val keyfiles: List<android.net.Uri>,
    val cipher: VeraCryptCipher,
    val hash: VeraCryptHash,
)

/**
 * The hidden volume's credentials did not open a hidden volume, so the outer one is not mounted
 * either. [getMessage] is VeraCrypt's own wording; [logMessage] goes to the log, which users
 * paste into public issues and which must not say that a hidden volume was involved.
 */
class HiddenVolumeCredentialsException(withKeyfiles: Boolean) : IllegalArgumentException(
    if (withKeyfiles) "Incorrect keyfile(s) and/or password to the protected hidden volume or the hidden volume does not exist."
    else "Incorrect password to the protected hidden volume or the hidden volume does not exist."
) {
    val logMessage: String get() = "the second set of credentials was not accepted"
}

/** A write VeraCrypt's hidden volume protection refused. The text reaches the log; it stays neutral. */
class VolumeWriteProtectedException : IOException("this volume is write-protected until it is unmounted")

/**
 * Hidden volume protection, as VeraCrypt's Volume::CheckProtectedRange and WriteSectors do it.
 *
 * The range is the hidden volume's data area, in bytes from the start of the host volume. The
 * first write that touches it is refused and from then on every write is refused, anywhere on
 * the volume, until it is unmounted: the outer filesystem has just lost a write, so nothing more
 * should be built on top of it. The state lives in memory only. VeraCrypt writes nothing about
 * the event to the volume, and neither does this.
 */
class HiddenVolumeProtection(val rangeStart: Long, val rangeEnd: Long) {
    private val fired = AtomicBoolean(false)

    /** Set once writes have been stopped; shown as Outer(!) on the card. */
    val triggered: Boolean get() = fired.get()

    /** Runs on the writing thread, once, when the protection first refuses a write. */
    @Volatile var onTriggered: (() -> Unit)? = null

    /** Throws if a write of [length] bytes at [hostOffset] must not reach the device. */
    fun checkWrite(hostOffset: Long, length: Long) {
        if (length <= 0) return
        if (fired.get()) throw VolumeWriteProtectedException()
        val last = hostOffset + length - 1
        val hits = if (hostOffset < rangeStart) last >= rangeStart else hostOffset <= rangeEnd - 1
        if (hits) {
            if (fired.compareAndSet(false, true)) onTriggered?.invoke()
            throw VolumeWriteProtectedException()
        }
    }
}
