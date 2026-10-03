package app.fayaz.otgmaster.veracrypt

import app.fayaz.otgmaster.block.RawBlockDevice
import app.fayaz.otgmaster.fs.DetectedFilesystem
import app.fayaz.otgmaster.fs.FilesystemDetector
import app.fayaz.otgmaster.luks.LuksParser

class VeraCryptUnlocker {
    fun probeCandidates(device: RawBlockDevice): List<VolumeCandidate> {
        android.util.Log.i("VeraCryptUnlocker", "Probing candidates on device with blockSize=${device.blockSize}, blockCount=${device.blockCount}")
        val sector0 = device.readBlocks(startBlock = 0, blockCount = 1)
        val hexPrefix = sector0.take(32).joinToString("") { String.format("%02X", it) }
        val hexSuffix = sector0.slice(510..511).joinToString("") { String.format("%02X", it) }
        android.util.Log.i("VeraCryptUnlocker", "Sector 0 prefix: $hexPrefix, suffix: $hexSuffix")

        val partitions = app.fayaz.otgmaster.partition.MbrParser.parse(sector0)
        android.util.Log.i("VeraCryptUnlocker", "MBR partitions found: ${partitions.joinToString()}")

        val hasGpt = partitions.any { it.type == 0xEE }
        android.util.Log.i("VeraCryptUnlocker", "Has GPT partition? $hasGpt")

        val candidates = mutableListOf<VolumeCandidate>()

        if (hasGpt) {
            val gptPartitions = app.fayaz.otgmaster.partition.GptParser.parse(device)
            android.util.Log.i("VeraCryptUnlocker", "GPT partitions found: ${gptPartitions.joinToString()}")
            candidates.addAll(gptPartitions.map {
                classifyCandidate(
                    label = "Partition ${it.index + 1}",
                    startBlock = it.firstLba,
                    blockCount = it.sectorCount,
                    device = device,
                )
            })
        } else if (partitions.isNotEmpty()) {
            candidates.addAll(partitions.map {
                classifyCandidate(
                    label = "Partition ${it.index + 1}",
                    startBlock = it.firstLba,
                    blockCount = it.sectorCount,
                    device = device,
                )
            })
        } else {
            // No partition table — treat the whole device as one volume.
            candidates.add(classifyCandidate(
                label = "Whole device",
                startBlock = 0,
                blockCount = device.blockCount,
                device = device,
            ))
        }

        return candidates
    }

    private fun classifyCandidate(
        label: String,
        startBlock: Long,
        blockCount: Long?,
        device: RawBlockDevice,
    ): VolumeCandidate {
        val containerType = try {
            // Four sectors, not one: the LUKS magic and the FAT/NTFS OEM names sit
            // in the first, but the ext2/3/4 and F2FS superblocks start at byte 1024.
            val available = device.blockCount - startBlock
            val sectors = if (available <= 0) 1 else minOf(4L, available).toInt()
            val sector = device.readBlocks(startBlock, sectors)
            // BitLocker first: a BitLocker To Go volume begins with a genuine FAT32
            // boot sector (its discovery volume), which the filesystem check below
            // would take for an unencrypted partition.
            if (app.fayaz.otgmaster.bitlocker.BitLockerHeader.hasSignature(sector)) {
                ContainerType.BITLOCKER
            } else if (LuksParser.hasLuksMagic(sector)) {
                when (LuksParser.getVersion(sector)) {
                    1    -> ContainerType.LUKS1
                    2    -> ContainerType.LUKS2
                    else -> ContainerType.UNKNOWN
                }
            } else if (app.fayaz.otgmaster.apfs.ApfsFileSystem.hasSignature(sector)) {
                // Plain and encrypted APFS share the container superblock; only the
                // volume says which, so ask libfsapfs. Encrypted APFS has no outer
                // header — the password goes to the filesystem — but it needs the
                // unlock form like any container, so it is offered as one.
                val slice = app.fayaz.otgmaster.block.SlicedBlockDevice(
                    device, startBlock, blockCount ?: (device.blockCount - startBlock))
                when (app.fayaz.otgmaster.apfs.ApfsFileSystem.isEncrypted(slice)) {
                    true -> ContainerType.APFS
                    false -> ContainerType.UNENCRYPTED
                    null -> ContainerType.UNKNOWN
                }
            } else if (FilesystemDetector.detectFromBytes(sector) !is DetectedFilesystem.Unknown) {
                // A readable filesystem signature rules VeraCrypt out: the first
                // sector of a VeraCrypt volume is its encrypted header, so it
                // cannot spell "NTFS" or carry an ext4 magic. Without this check
                // the else branch guessed VERACRYPT for every plain partition, so
                // an NTFS partition was offered in the unlock picker tagged
                // VERACRYPT and then ran PBKDF2 against its own boot sector.
                ContainerType.UNENCRYPTED
            } else {
                // Nothing identifiable. A VeraCrypt header is indistinguishable
                // from random data without the password, so this is the residual
                // guess, not a positive identification.
                ContainerType.VERACRYPT
            }
        } catch (e: Exception) {
            ContainerType.UNKNOWN
        }
        // The container type is carried as a field and rendered as a tag in the
        // UI; baking it into the label leaked "[LUKS1]" into every place the
        // label is shown or logged.
        android.util.Log.i("VeraCryptUnlocker", "Candidate '$label': $containerType")
        return VolumeCandidate(label = label, startBlock = startBlock, blockCount = blockCount, containerType = containerType)
    }

    class UnsupportedAlgorithmException(message: String) : Exception(message)

    fun unlock(
        device: RawBlockDevice,
        candidate: VolumeCandidate,
        password: CharArray,
        pim: Int? = null,
        keyfiles: List<android.net.Uri>? = null,
        contentResolver: android.content.ContentResolver? = null,
        cipher: VeraCryptCipher = VeraCryptCipher.DEFAULT,
        hash: VeraCryptHash = VeraCryptHash.DEFAULT
    ): RawBlockDevice {
        if (!cipher.isSupported) {
            throw UnsupportedAlgorithmException(
                "${cipher.displayName} is not yet supported. Supported ciphers: " +
                    VeraCryptCipher.entries.filter { it.isSupported }.joinToString(", ") { it.displayName }
            )
        }
        if (!hash.isSupported) {
            throw UnsupportedAlgorithmException(
                "${hash.displayName} is not yet supported. Supported hashes: " +
                    VeraCryptHash.entries.filter { it.isSupported }.joinToString(", ") { it.displayName }
            )
        }

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

        // Non-system encryption (OTGMaster only supports non-system volumes)
        val iterations = if (pim != null && pim > 0) {
            15000 + (pim * 1000)
        } else {
            when (hash) {
                VeraCryptHash.SHA512 -> 500000
                VeraCryptHash.WHIRLPOOL -> 500000
                VeraCryptHash.SHA256 -> 500000
                else -> 500000
            }
        }
        
        android.util.Log.i("VeraCryptUnlocker", "Iterations: $iterations")

        android.util.Log.i("VeraCryptUnlocker", "Password length: ${password.size}, PIM: $pim, Iterations: $iterations")
        
        val headerSector = device.readBlocks(candidate.startBlock, 1)
        android.util.Log.i("VeraCryptUnlocker", "startBlock: ${candidate.startBlock}, header length: ${headerSector.size}")
        android.util.Log.i("VeraCryptUnlocker", "Header first 16 bytes: " + headerSector.take(16).joinToString("") { "%02x".format(it) })
        
        val salt = headerSector.copyOfRange(0, 64)
        val encryptedHeader = headerSector.copyOfRange(64, 512)

        // Only single (non-cascaded) ciphers are supported today; cipher.isSupported was
        // already validated above, and every currently-supported VeraCryptCipher has exactly
        // one component.
        val cipherNativeId = cipher.components.first().nativeId

        val decryptedHeader = VeraCryptNative.decryptHeader(cipherNativeId, passwordBytes, salt, iterations, encryptedHeader)

        if (decryptedHeader == null) {
            throw IllegalArgumentException("Decryption failed internally")
        }

        // Check "VERA" magic bytes (ASCII)
        if (decryptedHeader[0] != 'V'.code.toByte() ||
            decryptedHeader[1] != 'E'.code.toByte() ||
            decryptedHeader[2] != 'R'.code.toByte() ||
            decryptedHeader[3] != 'A'.code.toByte()) {
            throw IllegalArgumentException(
                "Wrong password, PIM, keyfile, or cipher/hash selection (header could not be decrypted)."
            )
        }

        // Master key starts at offset 192 of the 448 decrypted bytes, sized per cipher.keySizeBytes
        val masterKey = decryptedHeader.copyOfRange(192, 192 + cipher.keySizeBytes)
        passwordBytes.fill(0)

        // For standard volume, data area starts at byte 131072 (sector 256 for 512-byte sectors)
        // relative to the START OF THE VOLUME — this is the XTS tweak offset. The physical I/O
        // offset additionally includes wherever the volume's partition starts on the disk.
        val dataOffsetSectors = 131072L / device.blockSize

        return NativeDecryptedBlockDevice(
            device,
            masterKey,
            volumeDataOffset = candidate.startBlock + dataOffsetSectors,
            decryptedBlockCount = (candidate.blockCount ?: (device.blockCount - candidate.startBlock)) - dataOffsetSectors,
            cipherNativeId = cipherNativeId,
            tweakDataOffset = dataOffsetSectors
        )
    }
}
