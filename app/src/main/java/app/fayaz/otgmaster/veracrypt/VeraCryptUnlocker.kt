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
            app.fayaz.otgmaster.OtgMasterState.logFailure("container",
                "Could not read the header of the partition at block $startBlock", e)
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
        hash: VeraCryptHash = VeraCryptHash.DEFAULT,
        /** Non-null mounts the outer volume with its hidden volume protected against writes to it. */
        protect: HiddenVolumeCredentials? = null,
    ): NativeDecryptedBlockDevice {
        requireSupported(cipher, hash)
        protect?.let { requireSupported(it.cipher, it.hash) }

        val passwordBytes = keyedPassword(password, keyfiles, contentResolver)
        val kdf = KdfParams.forVolume(hash, pim)
        android.util.Log.i("VeraCryptUnlocker", "PIM: $pim, ${hash.displayName}: " +
            if (hash == VeraCryptHash.ARGON2) "${kdf.iterations} passes over ${kdf.memoryKiB / 1024} MiB"
            else "${kdf.iterations} iterations")

        // In VeraCrypt's own order. Each header has its own salt and so costs a full key
        // derivation, which is why the hidden one is tried only after the standard one fails:
        // an ordinary unlock costs exactly what it always did.
        val header = try {
            readHeader(device, candidate, VeraCryptHeader.STANDARD_HEADER_OFFSET, passwordBytes, cipher, hash, kdf)
                ?: readHeader(device, candidate, VeraCryptHeader.HIDDEN_HEADER_OFFSET, passwordBytes, cipher, hash, kdf)
        } finally {
            passwordBytes.fill(0)
        } ?: throw IllegalArgumentException(WRONG_PASSWORD)

        // Volume::Open: the main password must open the outer volume when protecting a hidden one.
        if (protect != null && header.isHidden) {
            header.keyArea.fill(0)
            throw IllegalArgumentException(WRONG_PASSWORD)
        }

        val masterKey = header.keyArea.copyOf(cipher.keySizeBytes)
        header.keyArea.fill(0)

        try {
            val partitionBlocks = candidate.blockCount ?: (device.blockCount - candidate.startBlock)
            // From the header: a hidden volume's data lies in the outer volume's free space. The
            // XTS data unit is this offset plus the sector, counted from the start of the host
            // volume, which is what tweakDataOffset carries and what VeraCrypt computes as
            // hostOffset / SectorSize.
            val (dataOffset, dataSize) = dataArea(header, partitionBlocks * device.blockSize)
            requireSectorAligned(dataOffset, device.blockSize)
            requireSectorAligned(dataSize, device.blockSize)
            val protection = protect?.let { openProtection(device, candidate, partitionBlocks, contentResolver, it) }
            val dataOffsetSectors = dataOffset / device.blockSize
            return NativeDecryptedBlockDevice(
                device,
                masterKey,
                volumeDataOffset = candidate.startBlock + dataOffsetSectors,
                decryptedBlockCount = dataSize / device.blockSize,
                cipherNativeIds = cipher.nativeIds,
                tweakDataOffset = dataOffsetSectors,
                volumeType = when {
                    header.isHidden -> VeraCryptVolumeType.HIDDEN
                    protection != null -> VeraCryptVolumeType.OUTER
                    else -> VeraCryptVolumeType.NORMAL
                },
                hiddenVolumeProtection = protection,
            )
        } catch (e: Exception) {
            masterKey.fill(0)
            throw e
        }
    }

    /**
     * Learns where the hidden volume is, as VeraCrypt does: its header is decrypted only to read
     * its offset and size, it is not mounted, and its key is thrown away.
     */
    private fun openProtection(
        device: RawBlockDevice,
        candidate: VolumeCandidate,
        partitionBlocks: Long,
        contentResolver: android.content.ContentResolver?,
        protect: HiddenVolumeCredentials,
    ): HiddenVolumeProtection {
        val withKeyfiles = protect.keyfiles.isNotEmpty()
        val bytes = keyedPassword(protect.password, protect.keyfiles, contentResolver)
        val header = try {
            readHeader(device, candidate, VeraCryptHeader.HIDDEN_HEADER_OFFSET, bytes, protect.cipher, protect.hash,
                KdfParams.forVolume(protect.hash, protect.pim))
        } finally {
            bytes.fill(0)
        }
        // Only the hidden location is tried, and its header has to say that it is one.
        if (header == null || !header.isHidden) {
            header?.keyArea?.fill(0)
            throw HiddenVolumeCredentialsException(withKeyfiles)
        }
        header.keyArea.fill(0)
        val (offset, size) = dataArea(header, partitionBlocks * device.blockSize)
        return HiddenVolumeProtection(offset, offset + size)
    }

    private fun requireSupported(cipher: VeraCryptCipher, hash: VeraCryptHash) {
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
    }

    /** The password's bytes with its keyfiles mixed in; [password] is wiped. An unreadable keyfile is skipped. */
    private fun keyedPassword(
        password: CharArray,
        keyfiles: List<android.net.Uri>?,
        contentResolver: android.content.ContentResolver?,
    ): ByteArray {
        val bytes = String(password).toByteArray(Charsets.UTF_8)
        password.fill('\u0000')
        if (keyfiles.isNullOrEmpty() || contentResolver == null) return bytes
        return VeraCryptKeyfiles.apply(bytes,
            keyfiles.map { uri -> { contentResolver.openInputStream(uri) } }, skipUnreadable = true)
    }

    /** The header at [offset] bytes into the partition if these credentials open it, else null. */
    private fun readHeader(
        device: RawBlockDevice,
        candidate: VolumeCandidate,
        offset: Long,
        passwordBytes: ByteArray,
        cipher: VeraCryptCipher,
        hash: VeraCryptHash,
        kdf: KdfParams,
    ): VeraCryptHeader? {
        if (offset % device.blockSize != 0L) return null
        val block = candidate.startBlock + offset / device.blockSize
        val partitionEnd = candidate.startBlock + (candidate.blockCount ?: (device.blockCount - candidate.startBlock))
        if (block >= partitionEnd) return null

        val sector = device.readBlocks(block, 1)
        val plain = try {
            VeraCryptNative.decryptHeader(
                cipher.nativeIds, hash.kdfNativeId!!, passwordBytes, sector.copyOfRange(0, 64),
                kdf.iterations, kdf.memoryKiB, sector.copyOfRange(64, 512))
        } catch (e: IllegalStateException) {
            // Only Argon2id throws, almost always because its working memory, hundreds of MiB
            // at the default PIM and up to 1 GiB, failed to allocate. The other location
            // would fail the same way, so this does not move on to it.
            throw IllegalStateException(
                "Argon2 could not derive the key (${e.message}). It needs " +
                    "${kdf.memoryKiB / 1024} MiB of memory at this PIM; close other apps and try again.", e)
        }
        if (plain == null) {
            android.util.Log.w("VeraCryptUnlocker", "the native header decryption failed")
            return null
        }
        return try { VeraCryptHeader.parse(plain) } finally { plain.fill(0) }
    }

    /** Offset and size of the data area, in bytes from the start of the host volume. */
    private fun dataArea(header: VeraCryptHeader, hostBytes: Long): Pair<Long, Long> {
        val offset = if (header.hasDataAreaFields) header.dataAreaOffset else VeraCryptHeader.STANDARD_DATA_OFFSET
        // Volume size, not the partition: VolumeLayoutV2 takes it from the header, and for a
        // standard volume it leaves out the 128 KiB of backup headers at the end.
        val size = if (header.hasDataAreaFields && header.volumeSize > 0) header.volumeSize else hostBytes - offset
        if (offset < 0 || size <= 0 || offset > hostBytes || size > hostBytes - offset) {
            throw IllegalArgumentException("The volume header describes a data area outside this partition.")
        }
        return offset to size
    }

    private fun requireSectorAligned(bytes: Long, blockSize: Int) {
        if (bytes % blockSize != 0L) {
            throw IllegalArgumentException("The volume's data area does not start on a sector of this device.")
        }
    }

    private companion object {
        const val WRONG_PASSWORD = "Wrong password, PIM, keyfile, or cipher/hash selection. " +
            "Neither the standard nor the hidden volume header could be decrypted."
    }
}
