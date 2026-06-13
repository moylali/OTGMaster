package com.otgmaster.veracrypt

import com.otgmaster.block.RawBlockDevice

class VeraCryptUnlocker {
    fun probeCandidates(device: RawBlockDevice): List<VolumeCandidate> {
        val sector0 = device.readBlocks(startBlock = 0, blockCount = 1)
        val partitions = com.otgmaster.partition.MbrParser.parse(sector0)

        val wholeDevice = VolumeCandidate(
            label = "Whole device",
            startBlock = 0,
            blockCount = device.blockCount,
        )

        return listOf(wholeDevice) + partitions.map {
            VolumeCandidate(
                label = "MBR partition ${it.index + 1} type 0x${it.type.toString(16)}",
                startBlock = it.firstLba,
                blockCount = it.sectorCount,
            )
        }
    }

    fun unlock(device: RawBlockDevice, candidate: VolumeCandidate, password: CharArray): RawBlockDevice {
        val passwordBytes = String(password).toByteArray(Charsets.UTF_8)
        password.fill('\u0000')

        val headerSector = device.readBlocks(candidate.startBlock, 1)
        val salt = headerSector.copyOfRange(0, 64)
        val encryptedHeader = headerSector.copyOfRange(64, 512)

        val decryptedHeader = VeraCryptNative.decryptHeader(passwordBytes, salt, encryptedHeader)
        passwordBytes.fill(0)

        if (decryptedHeader == null) {
            throw IllegalArgumentException("Decryption failed (incorrect password or corrupted header).")
        }

        val magic = String(decryptedHeader.copyOfRange(0, 4), Charsets.US_ASCII)
        if (magic != "VERA") {
            throw IllegalArgumentException("Magic bytes 'VERA' not found. Decryption failed.")
        }

        // Master key is at offset 192 of the 448 decrypted bytes
        val masterKey = decryptedHeader.copyOfRange(192, 192 + 64)

        // For standard volume, data area starts at byte 131072 (sector 256 for 512-byte sectors)
        val dataOffsetSectors = 131072L / device.blockSize

        return NativeDecryptedBlockDevice(device, masterKey, candidate.startBlock + dataOffsetSectors)
    }
}
