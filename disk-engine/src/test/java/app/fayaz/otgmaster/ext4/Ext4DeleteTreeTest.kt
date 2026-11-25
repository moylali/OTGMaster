package app.fayaz.otgmaster.ext4

import app.fayaz.otgmaster.block.CachedBlockDevice
import app.fayaz.otgmaster.block.RawBlockDevice
import me.jahnen.libaums.core.fs.UsbFile
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.file.Files
import kotlin.random.Random

/**
 * Deleting a folder deletes what it holds, as it does on FAT32 and NTFS.
 *
 * Going down a tree reaches kinds of file the app never makes, so the volumes here are built
 * by `mkfs.ext4 -d` from a real directory: hard links, symlinks, a FIFO, an external xattr
 * block. Each of these was handled as a plain file with an extent tree, which is not safe:
 * a hard link's other name lost its inode, and a symlink's target text was read as extents.
 * e2fsck judges every result, and a refusal must leave every byte of the volume as it was.
 */
class Ext4DeleteTreeTest {

    private class Device(file: File, private val allow: Int = Int.MAX_VALUE) : RawBlockDevice {
        private val raf = RandomAccessFile(file, "rw")
        private val length = file.length()
        var writes = 0
            private set
        override val blockSize = 512
        override val blockCount get() = length / blockSize
        override fun readBlocks(startBlock: Long, blockCount: Int): ByteArray {
            val out = ByteArray(blockCount * blockSize)
            raf.seek(startBlock * blockSize); raf.readFully(out); return out
        }
        override fun writeBlocks(startBlock: Long, data: ByteArray) {
            if (writes >= allow) throw IOException("device gone after $allow writes")
            writes++
            raf.seek(startBlock * blockSize); raf.write(data)
        }
        override fun close() = raf.close()
    }

    private lateinit var work: File
    private lateinit var src: File
    private lateinit var img: File

    private val keep = Random(1).nextBytes(300_000)
    private val big = Random(2).nextBytes(3_000_000)

    private fun run(vararg cmd: String): Pair<Int, String> {
        val p = ProcessBuilder(*cmd).redirectErrorStream(true).start()
        val out = p.inputStream.bufferedReader().readText()
        return p.waitFor() to out
    }

    @Before
    fun setUp() {
        for (tool in listOf("mkfs.ext4", "e2fsck", "debugfs", "dumpe2fs", "tune2fs")) {
            assumeTrue("$tool not available", run("sh", "-c", "command -v $tool").first == 0)
        }
        work = Files.createTempDirectory("ext4tree").toFile()
        src = File(work, "src").apply { mkdirs() }
        img = File(work, "fs.img")
        File(src, "keep.bin").writeBytes(keep)
    }

    @After fun tearDown() { work.deleteRecursively() }

    private fun file(path: String, data: ByteArray = ByteArray(0)) =
        File(src, path).apply { parentFile!!.mkdirs(); writeBytes(data) }

    private fun mkfs(vararg extra: String) {
        RandomAccessFile(img, "rw").use { it.setLength(128L * 1024 * 1024) }
        val (rc, out) = run(
            "mkfs.ext4", "-q", "-F", "-m", "0", "-b", "4096", *extra,
            "-E", "lazy_itable_init=0,lazy_journal_init=0", "-d", src.absolutePath, img.absolutePath,
        )
        assertEquals("mkfs.ext4 failed: $out", 0, rc)
        assertClean("the starting volume")
    }

    private fun debugfs(command: String): String {
        val (rc, out) = run("debugfs", "-w", "-R", command, img.absolutePath)
        assertEquals("debugfs $command: $out", 0, rc)
        return out
    }

    private fun assertClean(stage: String) {
        val (rc, out) = run("e2fsck", "-fn", img.absolutePath)
        assertEquals("e2fsck after $stage:\n$out", 0, rc)
    }

    private fun free(what: String): Long {
        val (_, out) = run("dumpe2fs", "-h", img.absolutePath)
        return Regex("""Free $what:\s+(\d+)""").find(out)!!.groupValues[1].toLong()
    }

    private fun <R> withRoot(cached: Boolean = false, block: (UsbFile) -> R): R {
        val raw = Device(img)
        val dev: RawBlockDevice = if (cached) CachedBlockDevice(raw) else raw
        try {
            return block(Ext4FileSystem.create(dev).rootDirectory)
        } finally {
            dev.close()
        }
    }

    private fun UsbFile.bytes(): ByteArray {
        val out = ByteArray(length.toInt())
        read(0, ByteBuffer.wrap(out))
        return out
    }

    private fun assertKeepIntact() = withRoot { root ->
        assertArrayEquals("a file outside the folder changed", keep, root.search("keep.bin")!!.bytes())
    }

    /** A delete that has to refuse, leaving the image byte for byte as it was. */
    private fun assertRefused(path: String, reason: String) {
        val before = img.readBytes()
        val e = assertThrows(IOException::class.java) {
            withRoot { root -> root.search(path)!!.delete() }
        }
        assertTrue("the refusal says '${e.message}', not '$reason'", e.message!!.contains(reason))
        assertArrayEquals("a refused delete wrote to the volume", before, img.readBytes())
    }

    /** One of everything the app can delete, in a tree. */
    private fun richTree() {
        file("tree/a.txt", "a".toByteArray())
        file("tree/empty.bin")
        file("tree/big.bin", big)
        file("tree/sub/deep/deeper/file.bin", Random(3).nextBytes(50_000))
        File(src, "tree/sub/emptydir").mkdirs()
        repeat(200) { file("tree/many/f$it.txt", "file $it".toByteArray()) }
        Files.createSymbolicLink(File(src, "tree/short_link").toPath(), File("a.txt").toPath())
        Files.createSymbolicLink(File(src, "tree/long_link").toPath(), File("x".repeat(200)).toPath())
        assertEquals(0, run("mkfifo", File(src, "tree/fifo").absolutePath).first)
    }

    @Test
    fun aFolderGoesWithEverythingInIt() {
        richTree()
        mkfs()
        val blocksBefore = free("blocks")
        val inodesBefore = free("inodes")

        withRoot { root -> root.search("tree")!!.delete() }

        assertClean("deleting the tree")
        withRoot { root -> assertNull(root.search("tree")) }
        assertKeepIntact()
        assertTrue(
            "only ${free("blocks") - blocksBefore} blocks came back",
            free("blocks") - blocksBefore >= big.size / 4096,
        )
        // 207 files and links (200 of them in many/), and 6 folders: tree, sub, deep, deeper, emptydir, many.
        assertEquals("inodes that came back", 213L, free("inodes") - inodesBefore)
    }

    @Test
    fun theSameThroughTheBlockCache() {
        richTree()
        mkfs()
        withRoot(cached = true) { root -> root.search("tree")!!.delete() }
        assertClean("deleting the tree through the cache")
        assertKeepIntact()
    }

    @Test
    fun aFolderTheAppMadeGoesToo() {
        mkfs()
        withRoot { root ->
            val dir = root.createDirectory("made")
            dir.createFile("one.bin").apply { write(0, ByteBuffer.wrap(big)); flush() }
            dir.createDirectory("sub").createFile("two.bin").apply { write(0, ByteBuffer.wrap(keep)); flush() }
        }
        assertClean("making the folder")
        withRoot { root -> root.search("made")!!.delete() }
        assertClean("deleting the folder")
        withRoot { root -> assertNull(root.search("made")) }
    }

    @Test
    fun aLargeIndexedFolderGoes() {
        repeat(3000) { file("indexed/file_with_a_longer_name_$it.txt", "x".toByteArray()) }
        mkfs()
        val (rc, out) = run("e2fsck", "-fyD", img.absolutePath)
        assertTrue("e2fsck -D:\n$out", rc == 0 || rc == 1)
        assertTrue(
            "the folder did not become an htree",
            debugfs("htree_dump /indexed").contains("Root node dump"),
        )
        withRoot { root -> root.search("indexed")!!.delete() }
        assertClean("deleting an indexed folder")
        assertKeepIntact()
    }

    @Test
    fun aHardLinkOutsideTheFolderKeepsTheFile() {
        file("tree/linked.bin", big)
        Files.createLink(File(src, "outside.bin").toPath(), File(src, "tree/linked.bin").toPath())
        mkfs()
        val blocksBefore = free("blocks")
        withRoot { root -> root.search("tree")!!.delete() }
        assertClean("deleting one name of a hard link")
        withRoot { root -> assertArrayEquals(big, root.search("outside.bin")!!.bytes()) }
        assertTrue("the linked file's blocks were freed", free("blocks") - blocksBefore < big.size / 4096)
    }

    @Test
    fun bothNamesOfAHardLinkInTheFolderFreeTheFile() {
        file("tree/one.bin", big)
        Files.createLink(File(src, "tree/two.bin").toPath(), File(src, "tree/one.bin").toPath())
        mkfs()
        val blocksBefore = free("blocks")
        withRoot { root -> root.search("tree")!!.delete() }
        assertClean("deleting both names of a hard link")
        assertTrue("the file's blocks did not come back", free("blocks") - blocksBefore >= big.size / 4096)
    }

    @Test
    fun deletingOneNameOfAHardLinkKeepsTheOther() {
        file("one.bin", big)
        Files.createLink(File(src, "two.bin").toPath(), File(src, "one.bin").toPath())
        mkfs()
        withRoot { root -> root.search("one.bin")!!.delete() }
        assertClean("deleting one name")
        withRoot { root ->
            assertNull(root.search("one.bin"))
            assertArrayEquals(big, root.search("two.bin")!!.bytes())
        }
        withRoot { root -> root.search("two.bin")!!.delete() }
        assertClean("deleting the last name")
    }

    @Test
    fun aSymlinkAloneIsDeletedWithoutReadingItsTargetAsBlocks() {
        file("target.bin", big)
        // A target whose text would decode as an extent header if it were taken for one.
        Files.createSymbolicLink(File(src, "fast").toPath(), File("target.bin").toPath())
        Files.createSymbolicLink(File(src, "slow").toPath(), File("y".repeat(300)).toPath())
        mkfs()
        withRoot { root ->
            root.search("fast")!!.delete()
            root.search("slow")!!.delete()
        }
        assertClean("deleting symlinks")
        withRoot { root -> assertArrayEquals(big, root.search("target.bin")!!.bytes()) }
    }

    @Test
    fun anExternalXattrBlockIsFreed() {
        file("tree/f.bin", "x".toByteArray())
        mkfs()
        val value = File(work, "value").apply { writeBytes(ByteArray(1000) { 'v'.code.toByte() }) }
        debugfs("ea_set -f ${value.absolutePath} /tree/f.bin user.big")
        assertTrue(fileAcl("/tree/f.bin") != 0L)
        assertClean("adding the xattr")
        withRoot { root -> root.search("tree")!!.delete() }
        assertClean("deleting a file with an xattr block")
    }

    private fun fileAcl(path: String): Long =
        Regex("""File ACL:\s+(\d+)""").find(debugfs("stat $path"))!!.groupValues[1].toLong()

    @Test
    fun aSharedXattrBlockIsRefusedAndNothingIsWritten() {
        file("tree/first.txt", "1".toByteArray())
        file("tree/f.bin", "x".toByteArray())
        mkfs()
        val value = File(work, "value").apply { writeBytes(ByteArray(1000) { 'v'.code.toByte() }) }
        debugfs("ea_set -f ${value.absolutePath} /tree/f.bin user.big")
        // h_refcount, 4 bytes into the block. The kernel shares a block between files with equal xattrs.
        RandomAccessFile(img, "rw").use { raf ->
            raf.seek(fileAcl("/tree/f.bin") * 4096 + 4)
            raf.write(byteArrayOf(2, 0, 0, 0))
        }
        assertRefused("tree", "shared")
    }

    @Test
    fun aBlockMappedFileIsRefusedAndNothingIsWritten() {
        file("tree/first.txt", "1".toByteArray())
        file("tree/old.bin", big)
        mkfs("-O", "^extent,^64bit,^metadata_csum")
        // A volume from before extents, upgraded: new files use extents and old ones stay block mapped.
        assertEquals(0, run("tune2fs", "-O", "extent", img.absolutePath).first)
        assertClean("enabling extents")
        assertRefused("tree", "block map")
        withRoot { root -> assertNotNull(root.search("tree/first.txt")) }
    }

    @Test
    fun anImmutableFileIsRefusedAndNothingIsWritten() {
        file("tree/first.txt", "1".toByteArray())
        file("tree/locked.bin", "x".toByteArray())
        mkfs()
        // EXT4_EXTENTS_FL | EXT4_IMMUTABLE_FL
        debugfs("set_inode_field /tree/locked.bin flags 0x80010")
        assertClean("setting the flag")
        assertRefused("tree", "immutable")
    }

    @Test
    fun aFreedInodeInTheFolderIsRefusedAndNothingIsWritten() {
        file("tree/first.txt", "1".toByteArray())
        file("tree/gone.bin", "x".toByteArray())
        mkfs()
        debugfs("clri /tree/gone.bin")
        assertRefused("tree", "damaged")
    }

    @Test
    fun aNameThatIsNotUtf8IsRefusedAndNothingIsWritten() {
        file("tree/first.txt", "1".toByteArray())
        val bad = run("sh", "-c", "touch \"${File(src, "tree").absolutePath}/\$(printf 'bad\\377name')\"")
        assertEquals(0, bad.first)
        mkfs()
        assertRefused("tree", "name")
    }

    /**
     * Every step of a tree delete must leave the volume safe to cut off. Leaks and unattached
     * inodes are fine, as in [Ext4DeleteCrashTest]; a block in use but marked free is not, and
     * the link count of a surviving name may only be too high, never too low.
     */
    @Test
    fun anInterruptedTreeDeleteOnlyLeaks() {
        richTree()
        file("tree/linked.bin", big)
        Files.createLink(File(src, "outside.bin").toPath(), File(src, "tree/linked.bin").toPath())
        mkfs()
        val base = File(work, "base.img")
        img.copyTo(base)
        val total = Device(img).let { dev ->
            try {
                Ext4FileSystem.create(dev).rootDirectory.search("tree")!!.delete()
                dev.writes
            } finally {
                dev.close()
            }
        }
        println("deleting 208 files and links in 6 folders: $total writes")
        assertTrue("a complete delete took only $total writes, so the cuts below prove little", total > 500)
        for (n in listOf(1, 2, 3, 5, 8, 13) + (1..30).map { it * total / 31 }) {
            base.copyTo(img, overwrite = true)
            val dev = Device(img, allow = n)
            try {
                runCatching { Ext4FileSystem.create(dev).rootDirectory.search("tree")!!.delete() }
            } finally {
                dev.close()
            }
            val (_, out) = run("e2fsck", "-fn", img.absolutePath)
            val bad = out.lines().filter { l ->
                Regex("""Block bitmap differences:.*\+""").containsMatchIn(l) ||
                    l.contains("Multiply-claimed", ignoreCase = true) ||
                    l.contains("shared with", ignoreCase = true) ||
                    l.contains("has deleted/unused inode", ignoreCase = true)
            }
            assertTrue("cut off after $n writes:\n${bad.joinToString("\n")}", bad.isEmpty())
            assertArrayEquals("cut off after $n writes, outside.bin changed", big, readBack("outside.bin"))
        }
    }

    private fun readBack(name: String): ByteArray {
        val dev = Device(img)
        try {
            return Ext4FileSystem.create(dev).rootDirectory.search(name)!!.bytes()
        } finally {
            dev.close()
        }
    }
}
