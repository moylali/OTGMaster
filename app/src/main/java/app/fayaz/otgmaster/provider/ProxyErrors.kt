package app.fayaz.otgmaster.provider

import app.fayaz.otgmaster.fs.NoSpaceException
import java.io.FileNotFoundException

/**
 * The errno a proxy file descriptor reports to the app reading or writing it.
 *
 * Android's proxy descriptors (StorageManager.openProxyFileDescriptor) pass a callback's
 * ErrnoException to the client as its errno, and report anything else as EBADF. The
 * callbacks threw plain IOExceptions, so a client writing to a full drive got "Bad file
 * descriptor" instead of "No space left on device", and one writing to a read-only volume
 * the same. Mapping them lets apps such as Files say what happened (architecture
 * proposal, F6).
 *
 * Linux values, which are what android.system.OsConstants holds; written out so the
 * mapping can be tested on the JVM, where OsConstants is a stub.
 */
object ProxyErrors {
    const val ENOENT = 2
    const val EIO = 5
    const val ENOSPC = 28
    const val EROFS = 30

    fun errnoFor(e: Throwable): Int {
        var t: Throwable? = e
        while (t != null) {
            when {
                t is NoSpaceException -> return ENOSPC
                t is FileNotFoundException -> return ENOENT
                // Every driver refuses a write on a read-only volume with an IOException
                // whose message says so ("ext4 volume is read-only: …", "exFAT volume is
                // mounted read-only", the NTFS, APFS and restic equivalents).
                t.message?.contains("read-only", ignoreCase = true) == true -> return EROFS
            }
            t = t.cause.takeIf { it !== t }
        }
        return EIO
    }
}
