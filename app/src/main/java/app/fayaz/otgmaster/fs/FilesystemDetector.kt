package app.fayaz.otgmaster.fs

import app.fayaz.otgmaster.block.RawBlockDevice

sealed class DetectedFilesystem(val displayName: String) {
    class Supported(name: String) : DetectedFilesystem(name)
    class Unsupported(name: String, val reason: String) : DetectedFilesystem(name)
    object Unknown : DetectedFilesystem("Unknown")
}

object FilesystemDetector {

    fun detect(device: RawBlockDevice): DetectedFilesystem {
        val count = minOf(4, device.blockCount.toInt())
        val data = try {
            device.readBlocks(0L, count)
        } catch (e: Exception) {
            return DetectedFilesystem.Unknown
        }
        return detectFromBytes(data)
    }

    fun detectFromBytes(data: ByteArray): DetectedFilesystem {
        if (data.size < 512) return DetectedFilesystem.Unknown

        // exFAT: OEM name "EXFAT   " at bytes 3-10
        if (data.size >= 11 && data.ascii(3, 8) == "EXFAT   ") {
            return DetectedFilesystem.Supported("exFAT")
        }

        // NTFS: OEM name "NTFS    " at bytes 3-10
        if (data.size >= 7 && data.ascii(3, 4) == "NTFS") {
            return DetectedFilesystem.Unsupported("NTFS",
                "NTFS is not yet supported. Please reformat the volume as FAT32 or exFAT.")
        }

        // APFS container superblock: magic "NXSB" at bytes 32-35
        if (data.size >= 36 &&
            data[32] == 0x4E.toByte() && data[33] == 0x58.toByte() &&
            data[34] == 0x53.toByte() && data[35] == 0x42.toByte()) {
            return DetectedFilesystem.Unsupported("APFS",
                "APFS (Apple File System) is not supported. Please reformat as FAT32 or exFAT.")
        }

        // FAT family — all valid FAT/exFAT boot sectors have 0x55AA at bytes 510-511
        val hasBootSig = (data[510].toInt() and 0xFF) == 0x55 &&
                         (data[511].toInt() and 0xFF) == 0xAA

        if (hasBootSig) {
            // FAT32 Extended BPB: "FAT32   " at bytes 82-89
            if (data.size >= 90 && data.ascii(82, 8) == "FAT32   ") {
                return DetectedFilesystem.Supported("FAT32")
            }
            // FAT16 Extended BPB: "FAT16   " at bytes 54-61
            if (data.size >= 62 && data.ascii(54, 8) == "FAT16   ") {
                return DetectedFilesystem.Unsupported("FAT16",
                    "FAT16 is not supported. Please reformat the volume as FAT32 or exFAT.")
            }
            // FAT12 Extended BPB: "FAT12   " at bytes 54-61
            if (data.size >= 62 && data.ascii(54, 8) == "FAT12   ") {
                return DetectedFilesystem.Unsupported("FAT12",
                    "FAT12 is not supported. Please reformat the volume as FAT32 or exFAT.")
            }
        }

        // Checks below need data from sector 2 onward (byte 1024+)
        if (data.size >= 1026) {
            // HFS+ Volume Header at byte 1024: magic 0x482B ("H+") or 0x4858 ("HX" for HFSX)
            val b0 = data[1024].toInt() and 0xFF
            val b1 = data[1025].toInt() and 0xFF
            if ((b0 == 0x48 && b1 == 0x2B) || (b0 == 0x48 && b1 == 0x58)) {
                return DetectedFilesystem.Unsupported("HFS+",
                    "HFS+ (macOS Extended) is not supported. Please reformat as FAT32 or exFAT.")
            }
        }

        // ext2/3/4: superblock at byte 1024, magic 0xEF53 (little-endian) at superblock offset 56 = byte 1080
        if (data.size >= 1082 &&
            (data[1080].toInt() and 0xFF) == 0x53 &&
            (data[1081].toInt() and 0xFF) == 0xEF) {
            val extType = detectExtVersion(data)
            return DetectedFilesystem.Unsupported(extType,
                "$extType (Linux) is not supported. Please reformat as FAT32 or exFAT.")
        }

        // F2FS: superblock at byte 1024, magic 0xF2F52010 (little-endian) = bytes 0x10 0x20 0xF5 0xF2
        if (data.size >= 1028 &&
            (data[1024].toInt() and 0xFF) == 0x10 &&
            (data[1025].toInt() and 0xFF) == 0x20 &&
            (data[1026].toInt() and 0xFF) == 0xF5 &&
            (data[1027].toInt() and 0xFF) == 0xF2) {
            return DetectedFilesystem.Unsupported("F2FS",
                "F2FS (Flash-Friendly File System) is not supported. Please reformat as FAT32 or exFAT.")
        }

        return DetectedFilesystem.Unknown
    }

    // Distinguishes ext2/ext3/ext4 via superblock feature flags
    private fun detectExtVersion(data: ByteArray): String {
        if (data.size < 1122) return "ext2/3/4"
        // Incompatible features at superblock offset 96 = byte 1120; INCOMPAT_EXTENTS 0x0040 → ext4
        val incompat = (data[1120].toInt() and 0xFF) or ((data[1121].toInt() and 0xFF) shl 8)
        if (incompat and 0x0040 != 0) return "ext4"
        if (data.size >= 1118) {
            // Compatible features at superblock offset 92 = byte 1116; HAS_JOURNAL 0x0004 → ext3
            val compat = (data[1116].toInt() and 0xFF) or ((data[1117].toInt() and 0xFF) shl 8)
            if (compat and 0x0004 != 0) return "ext3"
        }
        return "ext2"
    }

    private fun ByteArray.ascii(offset: Int, length: Int): String =
        String(this, offset, length, Charsets.US_ASCII)
}
