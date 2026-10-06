package app.fayaz.otgmaster.fat32

import app.fayaz.otgmaster.block.RawBlockDevice
import app.fayaz.otgmaster.block.RawBlockDeviceAdapter
import me.jahnen.libaums.core.driver.ByteBlockDevice
import me.jahnen.libaums.core.fs.fat32.Fat32FileSystem
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.io.RandomAccessFile

/**
 * `listFiles` must not fail when a garbage collection lands mid-listing.
 *
 * libaums keeps a `WeakHashMap<String, UsbFile>` of handles keyed by path, and
 * `listFiles` read it twice: `cache[path] != null -> cache[path]!!`. Nothing else
 * holds the key string, so a GC between the two reads clears the entry and the
 * second read is null. On the OnePlus 7 (device-matrix run 8, D2BLFAT32) that was
 * "dense opens: FAILED java.lang.NullPointerException" right after 2.1 GB of
 * sequential reads; the benchmark harness had worked around a "null name" since
 * it was written without the cause being known.
 *
 * Here a second thread churns the heap while a 1000-entry directory is listed again and
 * again; the old code throws within seconds.
 */
class Fat32ListGcRaceTest {

    private class FileBlockDevice(private val file: File) : RawBlockDevice {
        private val raf = RandomAccessFile(file, "rw")
        override val blockSize = 512
        override val blockCount get() = file.length() / blockSize
        override fun readBlocks(startBlock: Long, blockCount: Int): ByteArray {
            val out = ByteArray(blockCount * blockSize)
            raf.seek(startBlock * blockSize); raf.readFully(out); return out
        }
        override fun writeBlocks(startBlock: Long, data: ByteArray) {
            raf.seek(startBlock * blockSize); raf.write(data)
        }
        override fun close() = raf.close()
    }

    private var img: File? = null

    @After fun tearDown() { img?.delete() }

    @Test
    fun listingSurvivesAGcBetweenCacheReads() {
        assumeTrue("mkfs.vfat not available",
            File("/usr/sbin/mkfs.vfat").exists() || File("/sbin/mkfs.vfat").exists())
        val f = File.createTempFile("fat32gc", ".img").also { img = it }
        RandomAccessFile(f, "rw").use { it.setLength(64L shl 20) }
        val p = ProcessBuilder("mkfs.vfat", "-F", "32", "-s", "1", f.absolutePath)
            .redirectErrorStream(true).start()
        p.inputStream.readBytes()
        assumeTrue("mkfs.vfat failed", p.waitFor() == 0)

        val dev = FileBlockDevice(f)
        val stop = java.util.concurrent.atomic.AtomicBoolean(false)
        // Allocation churn rather than System.gc(): frequent young collections, which
        // clear the young path strings the cache is keyed by, without stopping the world.
        val gc = Thread {
            var sink = 0
            while (!stop.get()) sink += ByteArray(256 * 1024).size
            if (sink == 42) println()
        }.apply { isDaemon = true }
        try {
            val dir = Fat32FileSystem.read(ByteBlockDevice(RawBlockDeviceAdapter(dev)))!!
                .rootDirectory.createDirectory("dense")
            repeat(1000) { dir.createFile("f%05d.dat".format(it)).close() }
            gc.start()
            val until = System.nanoTime() + 15_000_000_000L
            var passes = 0
            while (System.nanoTime() < until) {
                // Not holding the result: the handles must be collectable, as on the
                // phone, where the benchmark keeps only the names.
                assertEquals(1000, dir.listFiles().size)
                passes++
            }
            println("listings: $passes")
        } finally {
            stop.set(true); gc.join(); dev.close()
        }
    }
}
