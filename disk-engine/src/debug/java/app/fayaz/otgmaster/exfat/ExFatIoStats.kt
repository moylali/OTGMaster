package app.fayaz.otgmaster.exfat

/**
 * libexfat pread/pwrite counters, for attributing where an operation's cost goes.
 *
 * Throughput alone could not distinguish "the bytes are slow" from "there are far
 * more round trips than the bytes need" — that distinction is what identified the
 * missing block cache (docs/IO_PERFORMANCE.md §5.5): listing 10,000 entries issued
 * 100,844 preads averaging 24 bytes at 1.00x amplification.
 *
 * The native side is compiled only when OTG_IO_STATS is defined, which
 * src/main/cpp/CMakeLists.txt sets for debug builds alone. This class lives in the
 * debug source set for the same reason, so a release build cannot reference a
 * symbol that is not there.
 */
object ExFatIoStats {

    init {
        System.loadLibrary("veracrypt-native")
    }

    @JvmStatic
    external fun reset()

    /**
     * [0] pread calls, [1] pread bytes, [2] pwrite calls, [3] pwrite bytes,
     * [4..9] size histogram: <=512, <=4K, <=16K, <=64K, <=256K, >256K.
     */
    @JvmStatic
    external fun snapshot(): LongArray
}
