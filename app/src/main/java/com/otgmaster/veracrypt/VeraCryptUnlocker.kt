package com.otgmaster.veracrypt

import com.otgmaster.block.RawBlockDevice

class VeraCryptUnlocker {
    fun probeCandidates(device: RawBlockDevice): List<VolumeCandidate> {
        android.util.Log.i("VeraCryptUnlocker", "Probing candidates on device with blockSize=${device.blockSize}, blockCount=${device.blockCount}")
        val sector0 = device.readBlocks(startBlock = 0, blockCount = 1)
        val hexPrefix = sector0.take(32).joinToString("") { String.format("%02X", it) }
        val hexSuffix = sector0.slice(510..511).joinToString("") { String.format("%02X", it) }
        android.util.Log.i("VeraCryptUnlocker", "Sector 0 prefix: $hexPrefix, suffix: $hexSuffix")
        
        val partitions = com.otgmaster.partition.MbrParser.parse(sector0)
        android.util.Log.i("VeraCryptUnlocker", "MBR partitions found: ${partitions.joinToString()}")

        val wholeDevice = VolumeCandidate(
            label = "Whole device",
            startBlock = 0,
            blockCount = device.blockCount,
        )

        val candidates = mutableListOf(wholeDevice)
        val hasGpt = partitions.any { it.type == 0xEE }
        android.util.Log.i("VeraCryptUnlocker", "Has GPT partition? $hasGpt")
        
        if (hasGpt) {
            val gptPartitions = com.otgmaster.partition.GptParser.parse(device)
            android.util.Log.i("VeraCryptUnlocker", "GPT partitions found: ${gptPartitions.joinToString()}")
            candidates.addAll(gptPartitions.map {
                VolumeCandidate(
                    label = "GPT partition ${it.index + 1}",
                    startBlock = it.firstLba,
                    blockCount = it.sectorCount,
                )
            })
        } else {
            candidates.addAll(partitions.map {
                VolumeCandidate(
                    label = "MBR partition ${it.index + 1} type 0x${it.type.toString(16)}",
                    startBlock = it.firstLba,
                    blockCount = it.sectorCount,
                )
            })
        }
        
        return candidates
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
                try {
                    contentResolver.openInputStream(uri)?.use { stream ->
                        var crc = 0xFFFFFFFFL
                        val buffer = ByteArray(65536)
                        var writePos = 0
                        var totalRead = 0
                        while (totalRead < 1048576) {
                            val toRead = Math.min(65536, 1048576 - totalRead)
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
                                
                                keyfilePool[writePos++] = (keyfilePool[writePos - 1].toInt() + ((crc ushr 24) and 0xFF).toInt()).toByte()
                                if (writePos >= 64) writePos = 0
                                keyfilePool[writePos++] = (keyfilePool[writePos - 1].toInt() + ((crc ushr 16) and 0xFF).toInt()).toByte()
                                if (writePos >= 64) writePos = 0
                                keyfilePool[writePos++] = (keyfilePool[writePos - 1].toInt() + ((crc ushr 8) and 0xFF).toInt()).toByte()
                                if (writePos >= 64) writePos = 0
                                keyfilePool[writePos++] = (keyfilePool[writePos - 1].toInt() + (crc and 0xFF).toInt()).toByte()
                                if (writePos >= 64) writePos = 0
                            }
                            totalRead += read
                        }
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
            
            // VeraCrypt adds the keyfile pool to the password byte-by-byte (modulo 256)
            val combined = ByteArray(64)
            for (i in 0 until 64) {
                if (i < passwordBytes.size) {
                    combined[i] = (passwordBytes[i].toInt() + keyfilePool[i].toInt()).toByte()
                } else {
                    combined[i] = keyfilePool[i]
                }
            }
            passwordBytes.fill(0)
            passwordBytes = combined
        }

        val iterations = if (pim != null && pim > 0) 15000 + (pim * 1000) else 500000

        android.util.Log.i("VeraCryptUnlocker", "Iterations: $iterations, PasswordBytes Size: ${passwordBytes.size}")
        if (passwordBytes.size >= 64) {
            val poolHex = passwordBytes.takeLast(64).toByteArray().joinToString("") { String.format("%02X", it) }
            android.util.Log.i("VeraCryptUnlocker", "Appended Keyfile Pool Hex: $poolHex")
        }

        val headerSector = device.readBlocks(candidate.startBlock, 1)
        val salt = headerSector.copyOfRange(0, 64)
        val encryptedHeader = headerSector.copyOfRange(64, 512)

        val decryptedHeader = VeraCryptNative.decryptHeader(passwordBytes, salt, iterations, encryptedHeader)
        
        if (decryptedHeader == null) {
            throw IllegalArgumentException("Decryption failed internally")
        }
        
        // Check "VERA" magic bytes (ASCII)
        if (decryptedHeader[0] != 'V'.code.toByte() ||
            decryptedHeader[1] != 'E'.code.toByte() ||
            decryptedHeader[2] != 'R'.code.toByte() ||
            decryptedHeader[3] != 'A'.code.toByte()) {
            throw IllegalArgumentException("Magic bytes 'VERA' not found. Decryption failed.")
        }

        // Master key is at offset 192 of the 448 decrypted bytes
        val masterKey = decryptedHeader.copyOfRange(192, 192 + 64)
        passwordBytes.fill(0)

        // For standard volume, data area starts at byte 131072 (sector 256 for 512-byte sectors)
        val dataOffsetSectors = 131072L / device.blockSize

        return NativeDecryptedBlockDevice(device, masterKey, candidate.startBlock + dataOffsetSectors)
    }
}
