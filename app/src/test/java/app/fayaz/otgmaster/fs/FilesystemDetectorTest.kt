package app.fayaz.otgmaster.fs

import org.junit.Assert.assertTrue
import org.junit.Test

class FilesystemDetectorTest {

    private fun buf(size: Int = 2048) = ByteArray(size)

    private fun ByteArray.withAscii(offset: Int, text: String) = also {
        text.forEachIndexed { i, c -> this[offset + i] = c.code.toByte() }
    }

    private fun ByteArray.withBootSig() = also {
        this[510] = 0x55.toByte()
        this[511] = 0xAA.toByte()
    }

    @Test fun exfatIsSupported() {
        val r = FilesystemDetector.detectFromBytes(buf().withAscii(3, "EXFAT   "))
        assertTrue(r is DetectedFilesystem.Supported && r.displayName == "exFAT")
    }

    @Test fun fat32IsSupported() {
        val r = FilesystemDetector.detectFromBytes(buf().withBootSig().withAscii(82, "FAT32   "))
        assertTrue(r is DetectedFilesystem.Supported && r.displayName == "FAT32")
    }

    @Test fun fat16IsUnsupported() {
        val r = FilesystemDetector.detectFromBytes(buf().withBootSig().withAscii(54, "FAT16   "))
        assertTrue(r is DetectedFilesystem.Unsupported && r.displayName == "FAT16")
        assertTrue((r as DetectedFilesystem.Unsupported).reason.isNotEmpty())
    }

    @Test fun fat12IsUnsupported() {
        val r = FilesystemDetector.detectFromBytes(buf().withBootSig().withAscii(54, "FAT12   "))
        assertTrue(r is DetectedFilesystem.Unsupported && r.displayName == "FAT12")
    }

    @Test fun ntfsIsUnsupported() {
        val r = FilesystemDetector.detectFromBytes(buf().withAscii(3, "NTFS    "))
        assertTrue(r is DetectedFilesystem.Unsupported && r.displayName == "NTFS")
        assertTrue((r as DetectedFilesystem.Unsupported).reason.contains("not yet supported"))
    }

    @Test fun apfsIsUnsupported() {
        val data = buf()
        data[32] = 0x4E; data[33] = 0x58; data[34] = 0x53; data[35] = 0x42
        val r = FilesystemDetector.detectFromBytes(data)
        assertTrue(r is DetectedFilesystem.Unsupported && r.displayName == "APFS")
    }

    @Test fun hfsPlusIsUnsupported() {
        val data = buf()
        data[1024] = 0x48; data[1025] = 0x2B
        val r = FilesystemDetector.detectFromBytes(data)
        assertTrue(r is DetectedFilesystem.Unsupported && r.displayName == "HFS+")
    }

    @Test fun ext4IsUnsupported() {
        val data = buf()
        data[1080] = 0x53; data[1081] = 0xEF.toByte()
        data[1120] = 0x40  // INCOMPAT_EXTENTS bit → ext4
        val r = FilesystemDetector.detectFromBytes(data)
        assertTrue(r is DetectedFilesystem.Unsupported && r.displayName == "ext4")
    }

    @Test fun ext3IsUnsupported() {
        val data = buf()
        data[1080] = 0x53; data[1081] = 0xEF.toByte()
        data[1116] = 0x04  // COMPAT_HAS_JOURNAL bit → ext3
        val r = FilesystemDetector.detectFromBytes(data)
        assertTrue(r is DetectedFilesystem.Unsupported && r.displayName == "ext3")
    }

    @Test fun ext2IsUnsupported() {
        val data = buf()
        data[1080] = 0x53; data[1081] = 0xEF.toByte()
        val r = FilesystemDetector.detectFromBytes(data)
        assertTrue(r is DetectedFilesystem.Unsupported)
        assertTrue(r.displayName == "ext2" || r.displayName == "ext2/3/4")
    }

    @Test fun f2fsIsUnsupported() {
        val data = buf()
        data[1024] = 0x10; data[1025] = 0x20
        data[1026] = 0xF5.toByte(); data[1027] = 0xF2.toByte()
        val r = FilesystemDetector.detectFromBytes(data)
        assertTrue(r is DetectedFilesystem.Unsupported && r.displayName == "F2FS")
    }

    @Test fun allZeroesIsUnknown() {
        assertTrue(FilesystemDetector.detectFromBytes(ByteArray(2048)) is DetectedFilesystem.Unknown)
    }

    @Test fun tooSmallIsUnknown() {
        assertTrue(FilesystemDetector.detectFromBytes(ByteArray(128)) is DetectedFilesystem.Unknown)
    }
}
