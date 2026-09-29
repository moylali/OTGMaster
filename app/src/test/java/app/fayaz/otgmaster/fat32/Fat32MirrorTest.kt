package app.fayaz.otgmaster.fat32

import app.fayaz.otgmaster.block.RawBlockDevice
import app.fayaz.otgmaster.block.RawBlockDeviceAdapter
import me.jahnen.libaums.core.driver.ByteBlockDevice
import me.jahnen.libaums.core.fs.fat32.Fat32FileSystem
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer

/**
 * Every FAT copy must receive every write.
 *
 * libaums wrote fatOffset[0] only — its own comment read "TODO we should write in
 * in all FATs when they are mirrored!" — so FAT[1] stayed as mkfs left it and
 * fsck.fat reported "FATs differ" on every FAT32 volume this app had written to.
 *
 * The second test covers the edge the fix has to get right. Writes go in
 * two-block buffers, and a FAT with an odd sector count ends half-way through its
 * last buffer, which therefore overhangs into FAT[1]. Replaying that whole buffer
 * at FAT[1]'s offset lands its second half past FAT[1]'s end — on the root
 * directory, which is the first thing in the data area. The benchmark drive's FAT
 * is 118,073 sectors, so this is not hypothetical.
 */
class Fat32MirrorTest {

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

    private fun run(vararg cmd: String): Pair<Int, String> {
        val p = ProcessBuilder(*cmd).redirectErrorStream(true).start()
        val out = p.inputStream.bufferedReader().readText()
        return p.waitFor() to out
    }

    private fun makeImage(sizeBytes: Long, vararg mkfsArgs: String): File {
        assumeTrue("mkfs.vfat not available",
            File("/usr/sbin/mkfs.vfat").exists() || File("/sbin/mkfs.vfat").exists())
        val f = File.createTempFile("fat32mirror", ".img")
        RandomAccessFile(f, "rw").use { it.setLength(sizeBytes) }
        val (rc, out) = run("mkfs.vfat", *mkfsArgs, "-F", "32", "-n", "MIRROR", f.absolutePath)
        assumeTrue("mkfs.vfat failed: $out", rc == 0)
        img = f
        return f
    }

    private fun readAt(f: File, off: Long, len: Int) = RandomAccessFile(f, "r").use {
        it.seek(off); ByteArray(len).also { b -> it.readFully(b) }
    }
    private fun writeAt(f: File, off: Long, b: ByteArray) =
        RandomAccessFile(f, "rw").use { it.seek(off); it.write(b) }
    private fun u16(f: File, o: Long) = readAt(f, o, 2).let {
        (it[0].toInt() and 0xFF) or ((it[1].toInt() and 0xFF) shl 8) }
    private fun u32(f: File, o: Long) = readAt(f, o, 4).let {
        (it[0].toLong() and 0xFF) or ((it[1].toLong() and 0xFF) shl 8) or
        ((it[2].toLong() and 0xFF) shl 16) or ((it[3].toLong() and 0xFF) shl 24) }

    private class Geometry(val fat0: Long, val fatBytes: Long, val clusters: Long,
                           val fsInfo: Long, val dataArea: Long)

    private fun geometry(f: File): Geometry {
        val bps = u16(f, 11).toLong()
        val spc = readAt(f, 13, 1)[0].toLong() and 0xFF
        val resv = u16(f, 14).toLong()
        val nfat = readAt(f, 16, 1)[0].toLong() and 0xFF
        val total = u32(f, 32)
        val spf = u32(f, 36)
        val fsInfoSector = u16(f, 48).toLong()
        return Geometry(
            fat0 = resv * bps,
            fatBytes = spf * bps,
            clusters = (total - resv - nfat * spf) / spc,
            fsInfo = fsInfoSector * bps,
            dataArea = (resv + nfat * spf) * bps,
        )
    }

    private fun <T> withFs(f: File, body: (Fat32FileSystem) -> T): T {
        val dev = FileBlockDevice(f)
        try {
            val fs = Fat32FileSystem.read(ByteBlockDevice(RawBlockDeviceAdapter(dev)))
                ?: throw IllegalStateException("not recognised as FAT32")
            return body(fs)
        } finally { dev.close() }
    }

    private fun writeFile(f: File, name: String, content: ByteArray) = withFs(f) { fs ->
        val file = fs.rootDirectory.createFile(name)
        file.write(0, ByteBuffer.wrap(content))
        file.close()
    }

    private fun readFile(f: File, name: String): ByteArray? = withFs(f) { fs ->
        val file = fs.rootDirectory.listFiles().firstOrNull { it.name == name }
            ?: return@withFs null
        val buf = ByteBuffer.allocate(file.length.toInt())
        file.read(0, buf)
        buf.array()
    }

    private fun assertMirroredAndClean(f: File) {
        val g = geometry(f)
        assertArrayEquals("FAT[1] must be byte-identical to FAT[0] after a write",
            readAt(f, g.fat0, g.fatBytes.toInt()),
            readAt(f, g.fat0 + g.fatBytes, g.fatBytes.toInt()))
        val (rc, out) = run("fsck.fat", "-n", f.absolutePath)
        assertEquals("fsck.fat -n must be clean, got:\n$out", 0, rc)
    }

    @Test
    fun aFileWriteLeavesBothFatsIdentical() {
        val f = makeImage(64L * 1024 * 1024)
        val content = ByteArray(256 * 1024) { (it * 31).toByte() }
        writeFile(f, "plain.bin", content)

        assertMirroredAndClean(f)
        assertArrayEquals(content, readFile(f, "plain.bin"))
    }

    @Test
    fun mirroringTheLastBufferOfAnOddSizedFatStaysInsideTheFat() {
        // -a disables mkfs's alignment, which otherwise rounds the FAT to an even
        // sector count and hides the overhang entirely.
        val f = makeImage(614912L * 1024, "-a")
        val g = geometry(f)
        assumeTrue("need an odd-sized FAT, got ${g.fatBytes / 512} sectors",
            (g.fatBytes / 512) % 2 == 1L)

        // The file goes in a subdirectory. Written into the root, the spill would
        // be silently repaired: closing the file rewrites the root directory from
        // libaums' in-memory copy, straight over the damage. An unclamped mirror
        // passed an earlier version of this test for exactly that reason. On a
        // real drive the writes land in BENCH/reports and the like, nothing
        // rewrites the root, and the damage stays.
        withFs(f) { fs -> fs.rootDirectory.createDirectory("sub") }

        // Now allocate inside the FAT's last sector — the half-buffer that
        // overhangs FAT[1]. libaums searches from the FSInfo next-free hint, so
        // the hint is set only after "sub" has its cluster, keeping that out of
        // the way.
        val firstEntryInLastSector = (g.fatBytes - 512) / 4
        val lastRealCluster = g.clusters + 1
        assumeTrue("no real clusters in the FAT's last sector",
            lastRealCluster - firstEntryInLastSector >= 8)
        val hint = firstEntryInLastSector - 1
        writeAt(f, g.fsInfo + 492, byteArrayOf(
            hint.toByte(), (hint shr 8).toByte(), (hint shr 16).toByte(), (hint shr 24).toByte()))

        // The root directory is cluster 2, the first thing past FAT[1] — where an
        // unclamped mirror write lands.
        val rootBefore = readAt(f, g.dataArea, 512)

        val content = ByteArray(4 * 4096) { (it * 7 + 3).toByte() }
        withFs(f) { fs ->
            val sub = fs.rootDirectory.search("sub")!!
            val file = sub.createFile("tail.bin")
            file.write(0, ByteBuffer.wrap(content))
            file.close()
        }

        assertArrayEquals("the root directory sits just past FAT[1]; mirroring the FAT's " +
            "last buffer must not have written into it", rootBefore, readAt(f, g.dataArea, 512))
        assertMirroredAndClean(f)
        val back = withFs(f) { fs ->
            val file = fs.rootDirectory.search("sub/tail.bin")!!
            ByteBuffer.allocate(file.length.toInt()).also { file.read(0, it) }.array()
        }
        assertArrayEquals(content, back)
    }
}
