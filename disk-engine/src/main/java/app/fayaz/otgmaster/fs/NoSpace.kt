package app.fayaz.otgmaster.fs

import java.io.IOException

// NoSpaceException is in :mount-core (same package), so the provider can map it to ENOSPC.

object NoSpace {
    private const val ENOSPC = 28

    /** True if [t], or anything that caused it, means the volume is full. */
    fun isNoSpace(t: Throwable?): Boolean {
        var e = t
        var depth = 0
        while (e != null && depth++ < 8) {
            if (e is NoSpaceException) return true
            if (e is android.system.ErrnoException && e.errno == ENOSPC) return true
            val m = e.message.orEmpty()
            // libaums' FAT32 allocator (FAT.kt), which is vendored and keeps its message.
            if (m.contains("FAT32 volume is full") || m.contains("No space left on device")) return true
            e = e.cause
        }
        return false
    }

    /** For a negative errno from native code: a [NoSpaceException] saying [message] if it is ENOSPC, else null. */
    fun fromErrno(rc: Int, message: String): NoSpaceException? =
        if (rc == -ENOSPC) NoSpaceException(message) else null

    /**
     * What [fileSizes] and [folders] take on a volume that allocates in [cluster]-byte
     * units: every file rounded up to whole clusters (an empty file takes none), and a
     * cluster for each folder. Directory entries beyond that are not counted, so this is
     * a lower bound on a filesystem with large directories, but it is the part that
     * makes 20,000 small files on 32 KiB clusters need far more than their byte count.
     */
    fun neededOnVolume(fileSizes: Iterable<Long>, folders: Long, cluster: Int): Long {
        val c = cluster.coerceAtLeast(512).toLong()
        var total = folders * c
        for (s in fileSizes) if (s > 0) total += (s + c - 1) / c * c
        return total
    }
}
