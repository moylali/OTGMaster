package app.fayaz.otgmaster.format

import app.fayaz.otgmaster.bitlocker.BitLockerHeader
import app.fayaz.otgmaster.bitlocker.BitLockerUnlocker
import app.fayaz.otgmaster.block.CachedBlockDevice
import app.fayaz.otgmaster.block.RawBlockDevice
import app.fayaz.otgmaster.block.RawBlockDeviceAdapter
import app.fayaz.otgmaster.exfat.ExFatFileSystem
import app.fayaz.otgmaster.exfat.ExFatNative
import app.fayaz.otgmaster.ntfs.NtfsFileSystem
import me.jahnen.libaums.core.driver.ByteBlockDevice
import me.jahnen.libaums.core.fs.FileSystem
import me.jahnen.libaums.core.fs.fat32.Fat32FileSystem
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Assume.assumeTrue
import org.junit.BeforeClass
import org.junit.Test
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import kotlin.random.Random

/**
 * Format inside BitLocker. BitLocker keeps its three metadata areas and the moved first
 * sectors inside the volume it encrypts (Windows puts them wherever there was room), and
 * BitLockerBlockDevice refuses writes there, since they hold the keys. So a new filesystem
 * must keep every structure off them and never hand them out to a file: FAT32 and exFAT
 * mark them bad, NTFS lists them in $BadClus (mkntfs, docs/VENDOR_FIXES.md N3).
 *
 * Judged without the app: each filesystem's own checker, the reserved bytes compared
 * before and after, and then the volume filled through the app's own driver on a device
 * that fails the test at the first write into a reserved sector. The last tests do it all
 * through real BitLocker volumes, decrypted independently by scripts/bitlk_decrypt.py.
 */
class BitLockerFormatTest {

    /** A loopback image that, like BitLockerBlockDevice, reads zeros in [reservedBlocks] and refuses writes there. */
    private class HoleyDevice(file: File, override val reservedBlocks: List<LongRange>) : RawBlockDevice {
        private val raf = RandomAccessFile(file, "rw")
        override val blockSize = 512
        override val blockCount = file.length() / 512
        var strayWrites = 0
        override fun readBlocks(startBlock: Long, blockCount: Int): ByteArray {
            val out = ByteArray(blockCount * 512)
            raf.seek(startBlock * 512); raf.readFully(out)
            for (r in reservedBlocks) for (b in maxOf(r.first, startBlock)..minOf(r.last, startBlock + blockCount - 1)) {
                out.fill(0, ((b - startBlock) * 512).toInt(), ((b - startBlock + 1) * 512).toInt())
            }
            return out
        }
        override fun writeBlocks(startBlock: Long, data: ByteArray) {
            val last = startBlock + data.size / 512 - 1
            reservedBlocks.firstOrNull { it.first <= last && it.last >= startBlock }?.let {
                strayWrites++
                throw IOException("write of sectors $startBlock..$last reaches reserved sectors $it")
            }
            raf.seek(startBlock * 512); raf.write(data)
        }
        override fun close() = raf.close()
    }

    private val tmp = mutableListOf<File>()
    @After fun cleanUp() { tmp.forEach { it.delete() } }

    /** A sparse image of [bytes] whose [holes] hold 0xA5, so a write there would show. */
    private fun image(bytes: Long, holes: List<LongRange>): File {
        val f = File.createTempFile("blformat", ".img").also { tmp += it }
        RandomAccessFile(f, "rw").use { raf ->
            raf.setLength(bytes)
            for (h in holes) { raf.seek(h.first * 512); raf.write(ByteArray(((h.last - h.first + 1) * 512).toInt()) { 0xA5.toByte() }) }
        }
        return f
    }

    private fun holeBytes(f: File, holes: List<LongRange>): List<ByteArray> = RandomAccessFile(f, "r").use { raf ->
        holes.map { h -> ByteArray(((h.last - h.first + 1) * 512).toInt()).also { raf.seek(h.first * 512); raf.readFully(it) } }
    }

    private fun assertHolesUntouched(f: File, holes: List<LongRange>) {
        holeBytes(f, holes).forEachIndexed { i, b ->
            assertTrue("reserved sectors ${holes[i]} were written", b.all { it == 0xA5.toByte() })
        }
    }

    private fun assertClean(f: File, fs: DriveFormatter.Filesystem, what: String) {
        when (fs) {
            DriveFormatter.Filesystem.FAT32 -> {
                val (rc, out) = run("fsck.vfat", "-n", "-v", f.path)
                assertEquals("$what: fsck.vfat\n$out", 0, rc)
            }
            DriveFormatter.Filesystem.EXFAT -> {
                val (rc, out) = run("fsck.exfat", "-n", "-v", f.path)
                assertEquals("$what: fsck.exfat\n$out", 0, rc)
            }
            DriveFormatter.Filesystem.NTFS -> {
                val (crc, cout) = run("python3", File(repo, "scripts/ntfs_check.py").path, f.path)
                assertEquals("$what: ntfs_check.py\n$cout", 0, crc)
                val (rc, out) = run("ntfsfix", "-n", f.path)
                assertEquals("$what: ntfsfix -n\n$out", 0, rc)
            }
            DriveFormatter.Filesystem.EXT4 -> error("not formatted around reserved sectors")
        }
    }

    private fun existing(fs: DriveFormatter.Filesystem, sectors: Long) =
        DriveFormatter.Existing(fs, sectors, when (fs) {
            DriveFormatter.Filesystem.FAT32 -> Fat32Formatter.defaultClusterBytes(sectors * 512)
            DriveFormatter.Filesystem.EXFAT -> ExFatFormatter.defaultClusterBytes(sectors * 512)
            else -> 4096
        }, "OTG BL", 0)

    private fun <R> mounted(dev: RawBlockDevice, fs: DriveFormatter.Filesystem, body: (FileSystem) -> R): R = when (fs) {
        DriveFormatter.Filesystem.FAT32 -> body(Fat32FileSystem.read(ByteBlockDevice(RawBlockDeviceAdapter(dev)))!!)
        DriveFormatter.Filesystem.EXFAT -> {
            val ptr = ExFatNative.mount(dev, false)
            assertNotEquals("libexfat refused the volume", 0L, ptr)
            val x = ExFatFileSystem(dev, ptr)
            try { body(x) } finally { x.unmount() }
        }
        DriveFormatter.Filesystem.NTFS -> {
            val cached = CachedBlockDevice(dev)
            val n = NtfsFileSystem.mount(cached, readOnly = false)
            try { body(n) } finally { n.unmount(); cached.close() }
        }
        DriveFormatter.Filesystem.EXT4 -> error("not formatted around reserved sectors")
    }

    /**
     * Fills [fs] through its driver to within 4 MiB of full, 4 MiB a file: an allocator
     * that took a reserved cluster for free would write there, and the device fails it.
     */
    private fun fill(fs: FileSystem, seed: Int): Map<String, String> {
        val rnd = Random(seed)
        val written = mutableMapOf<String, String>()
        val dir = fs.rootDirectory.createDirectory("fill")
        var n = 0
        while (fs.freeSpace > 8L shl 20) {
            val data = rnd.nextBytes(4 shl 20)
            dir.createFile("f$n.bin").apply { write(0, ByteBuffer.wrap(data)); close() }
            written["fill/f$n.bin"] = sha(data)
            n++
        }
        assertTrue("only $n files fit", n > 10)
        return written
    }

    private fun readBack(fs: FileSystem, written: Map<String, String>) {
        for ((path, want) in written) {
            val f = fs.rootDirectory.search(path) ?: throw AssertionError("$path is gone")
            val out = ByteArray(f.length.toInt())
            f.read(0, ByteBuffer.wrap(out)); f.close()
            assertEquals("$path read back", want, sha(out))
        }
    }

    /** Formats, checks, fills and checks again: the whole contract on one device. */
    private fun formatAround(fs: DriveFormatter.Filesystem, holes: List<LongRange>, full: Boolean = false,
                             bytes: Long = 256L shl 20) {
        assumeTrue("no host C toolchain", hostLib != null || fs == DriveFormatter.Filesystem.FAT32)
        val f = image(bytes, holes)
        HoleyDevice(f, holes).use { dev ->
            DriveFormatter.reformat(dev, existing(fs, dev.blockCount), full, target = fs)
            assertEquals(0, dev.strayWrites)
        }
        assertHolesUntouched(f, holes)
        assertClean(f, fs, "fresh ${fs.displayName}")
        assertBadClustersRecorded(f, fs, holes)
        val written = HoleyDevice(f, holes).use { dev -> mounted(dev, fs) { fill(it, holes.hashCode()) } }
        HoleyDevice(f, holes).use { dev -> mounted(dev, fs) { readBack(it, written) } }
        assertHolesUntouched(f, holes)
        assertClean(f, fs, "${fs.displayName} filled to the brim")
    }

    /** The checkers accept bad clusters as such; this checks they are actually listed. */
    private fun assertBadClustersRecorded(f: File, fs: DriveFormatter.Filesystem, holes: List<LongRange>) {
        val boot = RandomAccessFile(f, "r").use { raf -> ByteArray(512).also { raf.readFully(it) } }
        val b = ByteBuffer.wrap(boot).order(ByteOrder.LITTLE_ENDIAN)
        when (fs) {
            DriveFormatter.Filesystem.FAT32 -> {
                val spc = boot[13].toInt() and 0xFF
                val fatStart = (b.getShort(14).toInt() and 0xFFFF).toLong()
                val fatSectors = b.getInt(36).toLong()
                val dataStart = fatStart + 2 * fatSectors
                RandomAccessFile(f, "r").use { raf ->
                    for (h in holes) for (c in listOf((h.first - dataStart) / spc + 2, (h.last - dataStart) / spc + 2)) {
                        for (copy in 0..1) {
                            raf.seek((fatStart + copy * fatSectors) * 512 + c * 4)
                            val e = Integer.reverseBytes(raf.readInt())
                            assertEquals("FAT ${copy + 1} entry of cluster $c (in $h)", 0x0FFF_FFF7, e)
                        }
                    }
                }
            }
            DriveFormatter.Filesystem.EXFAT -> {
                val fatOffset = b.getInt(80).toLong()
                val heap = b.getInt(88).toLong()
                val spc = 1 shl (boot[109].toInt() and 0xFF)
                RandomAccessFile(f, "r").use { raf ->
                    for (h in holes) for (c in listOf((h.first - heap) / spc + 2, (h.last - heap) / spc + 2)) {
                        raf.seek(fatOffset * 512 + c * 4)
                        assertEquals("FAT entry of cluster $c (in $h)", 0xFFFF_FFF7.toInt(), Integer.reverseBytes(raf.readInt()))
                        // The bitmap is the first heap cluster, cluster 2.
                        raf.seek(heap * 512 + (c - 2) / 8)
                        val bit = (raf.read() shr ((c - 2) % 8).toInt()) and 1
                        assertEquals("bitmap bit of cluster $c (in $h)", 1, bit)
                    }
                }
            }
            DriveFormatter.Filesystem.NTFS -> {
                val spc = boot[13].toInt() and 0xFF
                for (h in holes) for (c in listOf(h.first / spc, h.last / spc)) {
                    val (rc, out) = run("ntfscluster", "-f", "-c", "$c", f.path)
                    assertTrue("ntfscluster -c $c (in $h):\n$out", rc == 0 && out.contains("\$BadClus"))
                }
            }
            DriveFormatter.Filesystem.EXT4 -> {}
        }
    }

    // ── Format around reserved sectors ───────────────────────────────────────────

    /**
     * Where Windows might have put them: in the data area, one at the exact middle (where
     * mkntfs puts $MFTMirr and $LogFile), the moved first sectors right after it, one near
     * the end. Sectors of 512 bytes, 64 KiB areas and 8 KiB of moved sectors.
     */
    private val windowsLike = listOf(
        (64L shl 11) until (64L shl 11) + 128,
        (128L shl 11) until (128L shl 11) + 128,
        (128L shl 11) + 128 until (128L shl 11) + 144,
        (200L shl 11) + 8 until (200L shl 11) + 136,   // not cluster aligned
    ).map { it.first..it.last }

    @Test fun fat32AroundBitLockerMetadata() = formatAround(DriveFormatter.Filesystem.FAT32, windowsLike)
    @Test fun exFatAroundBitLockerMetadata() = formatAround(DriveFormatter.Filesystem.EXFAT, windowsLike)
    @Test fun ntfsAroundBitLockerMetadata() = formatAround(DriveFormatter.Filesystem.NTFS, windowsLike)
    @Test fun fullFormatSkipsTheReservedSectors() = formatAround(DriveFormatter.Filesystem.EXFAT, windowsLike, full = true)

    /** Right where mkntfs puts $MFT (16 KiB in), its bitmap (8 KiB in) and $LogFile: they move. */
    @Test fun ntfsMovesItsFixedStructuresOffReservedSectors() =
        formatAround(DriveFormatter.Filesystem.NTFS, listOf(16L..17L, 32L..159L, (128L shl 11) + 300..(128L shl 11) + 427))

    /** In FAT32's FATs: refused, and nothing written at all. */
    @Test fun fat32RefusesReservedSectorsInItsFats() = refused(DriveFormatter.Filesystem.FAT32, listOf(200L..327L), "FAT32")

    /** In exFAT's allocation bitmap, up-case table or root directory (the first heap clusters). */
    @Test fun exFatRefusesReservedSectorsInItsFirstClusters() {
        val l = ExFatFormatter.layout(512, 256L shl 11)
        refused(DriveFormatter.Filesystem.EXFAT, listOf(l.clusterHeapOffset..l.clusterHeapOffset + 7), "exFAT")
    }

    /** On the backup boot sector, which NTFS keeps in the volume's last sector. */
    @Test fun ntfsRefusesTheLastSector() =
        refused(DriveFormatter.Filesystem.NTFS, listOf((256L shl 11) - 128..(256L shl 11) - 1), "last sector")

    @Test fun ext4IsRefusedAroundReservedSectors() = refused(DriveFormatter.Filesystem.EXT4, windowsLike, "ext4")

    private fun refused(fs: DriveFormatter.Filesystem, holes: List<LongRange>, why: String) {
        val f = image(256L shl 20, holes)
        val before = sha(f.readBytes())
        HoleyDevice(f, holes).use { dev ->
            try {
                DriveFormatter.reformat(dev, existing(fs, dev.blockCount), full = true, target = fs)
                fail("${fs.displayName} was formatted over reserved sectors $holes")
            } catch (e: IllegalArgumentException) {
                assertTrue("the reason names $why: ${e.message}", e.message!!.contains(why))
            }
        }
        assertEquals("a refused format wrote to the device", before, sha(f.readBytes()))
    }

    // ── Through real BitLocker ───────────────────────────────────────────────────

    /**
     * A BitLocker volume with its metadata inside, as Windows makes them, holding an
     * NTFS (made by the host's mkntfs and wrapped by scripts/make_bitlocker_image.py,
     * which cryptsetup must accept first). The app unlocks it, formats it to [target],
     * fills it, and then: the metadata areas are byte-identical, cryptsetup still
     * releases the key, and the independently decrypted plaintext is checker-clean with
     * the files the app wrote.
     */
    private fun throughBitLocker(target: DriveFormatter.Filesystem, cipher: String) {
        assumeTrue("no host C toolchain", hostLib != null)
        assumeTrue("cryptsetup / python cryptography / ntfs-3g tools missing",
            listOf("cryptsetup", "python3", "mkntfs", "ntfsfix").all { run("sh", "-c", "command -v $it").first == 0 } &&
                run("python3", "-c", "import cryptography").first == 0)
        val size = 256L shl 20
        val plain = File.createTempFile("blplain", ".img").also { tmp += it }
        RandomAccessFile(plain, "rw").use { it.setLength(size) }
        val (mrc, mout) = run("mkntfs", "-F", "-q", "-f", "-L", "BEFORE", plain.path)
        assertEquals("mkntfs: $mout", 0, mrc)
        val img = File.createTempFile("bl-$cipher", ".img").also { tmp += it }
        val at = listOf(64L shl 20, 128L shl 20, 200L shl 20, (128L shl 20) + 65536)
        val (brc, bout) = run("python3", File(repo, "scripts/make_bitlocker_image.py").path, plain.path, img.path,
            "--password", PASSWORD, "--cipher", cipher, "--metadata-at", at.joinToString(","), "--verify")
        assertEquals("make_bitlocker_image.py:\n$bout", 0, brc)
        val header = RandomAccessFile(img, "r").use { f ->
            BitLockerHeader.parse { off, len -> ByteArray(len).also { f.seek(off); f.readFully(it) } }
        }
        // The keys and BitLocker's own boot sector must not change. The moved first sectors
        // must: they are the volume's real first sectors, where the new boot sector goes.
        val areas = header.metadataOffsets.map { it to BitLockerHeader.METADATA_AREA_SIZE.toLong() } + listOf(0L to 512L)
        fun areaBytes() = RandomAccessFile(img, "r").use { f ->
            areas.map { (o, n) -> ByteArray(n.toInt()).also { f.seek(o); f.readFully(it) } }
        }
        val before = areaBytes()

        val written = RwDevice(img).use { raw ->
            val bl = BitLockerUnlocker().unlock(raw, 0, raw.blockCount, PASSWORD.toCharArray())
            assertEquals("the device reports BitLocker's areas", 4, bl.reservedBlocks.size)
            val existing = DriveFormatter.readExisting(bl, "AFTER")
            DriveFormatter.reformat(bl, existing, full = false, target = target)
            val w = mounted(bl, target) { fill(it, cipher.hashCode()) }
            bl.close()
            w
        }
        val after = areaBytes()
        for (i in before.indices) assertArrayEquals("BitLocker area ${areas[i]} changed", before[i], after[i])

        val dec = File.createTempFile("bl-dec", ".img").also { tmp += it }
        val (drc, dout) = run("python3", File(repo, "scripts/bitlk_decrypt.py").path, img.path, PASSWORD, dec.path)
        assertEquals("cryptsetup no longer opens the volume, or the decryption failed:\n$dout", 0, drc)
        assertClean(dec, target, "${target.displayName} inside BitLocker, decrypted independently")
        val holes = (header.metadataOffsets.map { it to BitLockerHeader.METADATA_AREA_SIZE.toLong() } +
            listOf(header.volumeHeaderOffset to header.volumeHeaderSize)).map { (o, n) -> o / 512..(o + n) / 512 - 1 }
        assertBadClustersRecorded(dec, target, holes)

        // And the app finds the files after a fresh unlock.
        RwDevice(img).use { raw ->
            val bl = BitLockerUnlocker().unlock(raw, 0, raw.blockCount, PASSWORD.toCharArray())
            mounted(bl, target) { readBack(it, written) }
            bl.close()
        }
    }

    @Test fun ntfsToExFatInsideBitLocker() = throughBitLocker(DriveFormatter.Filesystem.EXFAT, "xts128")
    @Test fun ntfsToFat32InsideBitLocker() = throughBitLocker(DriveFormatter.Filesystem.FAT32, "cbc256")
    @Test fun ntfsToNtfsInsideBitLocker() = throughBitLocker(DriveFormatter.Filesystem.NTFS, "xts256")

    private class RwDevice(file: File) : RawBlockDevice {
        private val raf = RandomAccessFile(file, "rw")
        override val blockSize = 512
        override val blockCount = file.length() / 512
        override fun readBlocks(startBlock: Long, blockCount: Int): ByteArray =
            ByteArray(blockCount * 512).also { raf.seek(startBlock * 512); raf.readFully(it) }
        override fun writeBlocks(startBlock: Long, data: ByteArray) { raf.seek(startBlock * 512); raf.write(data) }
        override fun close() = raf.close()
    }

    companion object {
        private const val PASSWORD = "password123"
        private val repo: File = generateSequence(File("").absoluteFile) { it.parentFile }
            .first { File(it, "scripts/build_host_native.sh").exists() }
        private var hostLib: File? = null

        private fun run(vararg cmd: String): Pair<Int, String> {
            val p = ProcessBuilder(*cmd).redirectErrorStream(true).start()
            val out = p.inputStream.bufferedReader().readText()
            return p.waitFor() to out
        }

        private fun sha(b: ByteArray) = MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }

        @BeforeClass @JvmStatic
        fun buildHostLibrary() {
            if (run("sh", "-c", "command -v gcc && command -v g++").first != 0) return
            val lib = File(repo, "disk-engine/build/host-native/libotg-host.so")
            val (rc, out) = run(File(repo, "scripts/build_host_native.sh").path, lib.path)
            if (rc != 0) throw AssertionError("host build of the native code failed:\n$out")
            System.setProperty("otg.native.hostlib", lib.absolutePath)
            hostLib = lib
        }
    }
}
