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

    fun unlock(
        device: RawBlockDevice,
        candidate: VolumeCandidate,
        password: CharArray,
        pim: Int? = null,
        keyfiles: List<android.net.Uri>? = null,
        contentResolver: android.content.ContentResolver? = null
    ): RawBlockDevice {
        var passwordBytes = String(password).toByteArray(Charsets.UTF_8)
        password.fill('\u0000')

        // Process keyfiles
        if (!keyfiles.isNullOrEmpty() && contentResolver != null) {
            val keyfilePool = ByteArray(64)
            for (uri in keyfiles) {
                contentResolver.openInputStream(uri)?.use { stream ->
                    var crc = 0xFFFFFFFFL
                    val buffer = ByteArray(8192)
                    var totalRead = 0
                    while (totalRead < 1048576) {
                        val toRead = Math.min(8192, 1048576 - totalRead)
                        val read = stream.read(buffer, 0, toRead)
                        if (read <= 0) break
                        
                        for (i in 0 until read) {
                            val byteVal = buffer[i].toInt() and 0xFF
                            // standard CRC32 update (polynomial 0xEDB88320)
                            crc = crc xor byteVal.toLong()
                            for (j in 0..7) {
                                crc = if ((crc and 1L) != 0L) {
                                    (crc ushr 1) xor 0xEDB88320L
                                } else {
                                    crc ushr 1
                                }
                            }
                            
                            val poolIndex = (totalRead + i) % 64
                            val crcHighByte = (crc ushr 24) and 0xFF
                            keyfilePool[poolIndex] = (keyfilePool[poolIndex].toInt() + crcHighByte.toInt()).toByte()
                        }
                        totalRead += read
                    }
                }
            }
            // Append pool to password bytes
            val combined = ByteArray(passwordBytes.size + 64)
            System.arraycopy(passwordBytes, 0, combined, 0, passwordBytes.size)
            System.arraycopy(keyfilePool, 0, combined, passwordBytes.size, 64)
            passwordBytes.fill(0)
            passwordBytes = combined
        }

        val iterations = if (pim != null && pim > 0) 15000 + (pim * 1000) else 500000

        val headerSector = device.readBlocks(candidate.startBlock, 1)
        val salt = headerSector.copyOfRange(0, 64)
        val encryptedHeader = headerSector.copyOfRange(64, 512)

        val decryptedHeader = VeraCryptNative.decryptHeader(passwordBytes, salt, iterations, encryptedHeader)
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
