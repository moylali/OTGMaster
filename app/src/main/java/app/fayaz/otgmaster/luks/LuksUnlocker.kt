package app.fayaz.otgmaster.luks

import android.util.Log
import app.fayaz.otgmaster.block.RawBlockDevice
import app.fayaz.otgmaster.veracrypt.NativeDecryptedBlockDevice
import app.fayaz.otgmaster.veracrypt.SingleCipher
import java.nio.ByteBuffer
import java.security.MessageDigest
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import app.fayaz.otgmaster.util.CheckedRange
import app.fayaz.otgmaster.util.Secrets

private const val TAG = "LuksUnlocker"

/** cryptsetup's ceiling for Argon2 memory (4 GiB) and lanes; a header asking for more is refused. */
private const val MAX_ARGON2_MEMORY_KIB = 4 * 1024 * 1024
private const val MAX_ARGON2_LANES = 16

/** Reads [length] bytes at [offset] bytes from the start of the partition at [partitionStartBlock]. */
internal fun RawBlockDevice.readPartitionBytes(partitionStartBlock: Long, offset: Long, length: Int): ByteArray =
    readBytes(CheckedRange.add(CheckedRange.multiply(partitionStartBlock, blockSize.toLong(), "partition start"),
        offset, "read offset"), length)

// Reads data from device at an absolute byte offset. Returns ceil(len/blockSize) * blockSize bytes.
private fun RawBlockDevice.readBytes(byteOffset: Long, len: Int): ByteArray {
    val startBlock = byteOffset / blockSize
    val endByte    = byteOffset + len
    val endBlock   = (endByte + blockSize - 1) / blockSize
    val data = readBlocks(startBlock, (endBlock - startBlock).toInt())
    val offsetInData = (byteOffset - startBlock * blockSize).toInt()
    return data.copyOfRange(offsetInData, offsetInData + len)
}

class LuksUnlocker {

    class WrongPasswordException : Exception("Wrong password or all keyslots exhausted")
    class UnsupportedFormatException(msg: String) : Exception(msg)

    // Returns true if the first sector of this candidate contains LUKS magic.
    fun isLuks(device: RawBlockDevice, startBlock: Long): Boolean {
        return try {
            val sector = device.readBlocks(startBlock, 1)
            LuksParser.hasLuksMagic(sector)
        } catch (e: Exception) {
            false
        }
    }

    fun luksVersion(device: RawBlockDevice, startBlock: Long): Int {
        return try {
            val sector = device.readBlocks(startBlock, 1)
            LuksParser.getVersion(sector)
        } catch (e: Exception) { -1 }
    }

    // Unlock a LUKS1 or LUKS2 partition.
    // device        — the raw block device for the whole physical disk
    // startBlock    — first block of the partition
    // blockCount    — number of blocks in the partition (null = rest of device)
    // password      — raw password bytes (zeroed after use)
    //
    // Returns a RawBlockDevice whose block 0 is the first decrypted payload sector.
    // Throws WrongPasswordException if no keyslot accepts the password.
    // Throws UnsupportedFormatException for unsupported cipher modes, Argon2 OOM, etc.
    fun unlock(
        device: RawBlockDevice,
        startBlock: Long,
        blockCount: Long?,
        password: ByteArray,
        /** Memory the phone has free, for refusing an Argon2 keyslot that cannot fit; null skips the check. */
        availableMemoryBytes: Long? = null,
    ): RawBlockDevice {
        val partitionBlocks = blockCount ?: (device.blockCount - startBlock)
        val partitionBytes = CheckedRange.multiply(partitionBlocks, device.blockSize.toLong(), "partition size")
        val version = LuksParser.getVersion(device.readBlocks(startBlock, 1))
        Log.i(TAG, "LUKS version $version at block $startBlock")

        return when (version) {
            1    -> {
                // The LUKS1 binary header is 592 bytes; 512 sectors also hold its keyslot table.
                val maxSectors = minOf(512L, partitionBlocks).toInt()
                unlockLuks1(device, startBlock, blockCount, device.readBlocks(startBlock, maxSectors), password)
            }
            2    -> {
                val meta = Luks2Metadata.read(partitionBytes) { off, len -> device.readPartitionBytes(startBlock, off, len) }
                Log.i(TAG, "LUKS2 metadata: copy at ${meta.offset}, seqid ${meta.seqId}, header ${meta.hdrSize} B")
                unlockLuks2(device, startBlock, partitionBytes,
                    LuksParser.parseLuks2Json(meta.json, meta.hdrSize), password, availableMemoryBytes)
            }
            else -> throw UnsupportedFormatException("Unknown LUKS version: $version")
        }
    }

    // -----------------------------------------------------------------------
    // LUKS1
    // -----------------------------------------------------------------------

    private fun unlockLuks1(
        device: RawBlockDevice,
        startBlock: Long,
        blockCount: Long?,
        headerData: ByteArray,
        password: ByteArray
    ): RawBlockDevice {
        val hdr = LuksParser.parseLuks1(headerData)
        Log.i(TAG, "LUKS1: cipher=${hdr.cipherName}-${hdr.cipherMode} hash=${hdr.hashSpec} keyBytes=${hdr.keyBytes} payloadOff=${hdr.payloadOffsetSectors}")

        verifyCipher(hdr.cipherName, hdr.cipherMode)

        val activeSlots = hdr.keyslots.filter { it.active }
        if (activeSlots.isEmpty()) throw UnsupportedFormatException("No active LUKS1 keyslots")

        for (slot in activeSlots) {
            Log.i(TAG, "Trying keyslot ${slot.index}: iters=${slot.iterations} stripes=${slot.stripes} kmOff=${slot.keyMaterialOffsetSectors}")
            val masterKey = tryLuks1Keyslot(device, startBlock, hdr, slot, password) ?: continue

            // Digest verification: PBKDF2(masterKey, mkDigestSalt, mkDigestIter, 20)
            val derived = pbkdf2(pbkdf2AlgFor(hdr.hashSpec), masterKey, hdr.mkDigestSalt, hdr.mkDigestIter, 20)
            val matches = MessageDigest.isEqual(derived, hdr.mkDigest)
            Secrets.wipe(derived)
            if (!matches) {
                Log.w(TAG, "Keyslot ${slot.index}: key derived but digest mismatch — possible bug")
                masterKey.fill(0)
                continue
            }

            Log.i(TAG, "Keyslot ${slot.index}: digest verified, payload at sector ${hdr.payloadOffsetSectors}")
            return makeCryptoDevice(device, startBlock, blockCount, masterKey, hdr.payloadOffsetSectors)
        }

        throw WrongPasswordException()
    }

    private fun tryLuks1Keyslot(
        device: RawBlockDevice,
        partitionStartBlock: Long,
        hdr: LuksHeader.Luks1,
        slot: Luks1Keyslot,
        password: ByteArray
    ): ByteArray? {
        // Step 1: derive slot key
        val slotKey = pbkdf2(pbkdf2AlgFor(hdr.hashSpec), password, slot.salt, slot.iterations, hdr.keyBytes)

        // Step 2: read and decrypt key material
        // Key material is at slot.keyMaterialOffsetSectors (in 512-byte units from start of device)
        val kmSectorCount = (slot.stripes * hdr.keyBytes + 511) / 512
        val kmAbsoluteBlock = partitionStartBlock + slot.keyMaterialOffsetSectors
        val kmBlocks = device.readBlocks(kmAbsoluteBlock, (kmSectorCount * 512 + device.blockSize - 1) / device.blockSize)

        // XTS-decrypt key material using slotKey (plain64 IV: sector 0 at start of key material)
        val rc = app.fayaz.otgmaster.veracrypt.VeraCryptNative.cryptSectorsInPlace(
            intArrayOf(SingleCipher.AES.nativeId),
            0, // decrypt
            slotKey,
            0L, // start sector 0 — plain64 from start of key material area
            kmBlocks, 0, (kmSectorCount * 512).coerceAtMost(kmBlocks.size)
        )
        slotKey.fill(0)
        if (rc != 0) {
            Log.w(TAG, "Keyslot ${slot.index}: XTS decrypt failed (rc=$rc)")
            Secrets.wipe(kmBlocks)
            return null
        }

        // Step 3: AF-split merge → master key candidate
        val keyMaterial = kmBlocks.copyOfRange(0, slot.stripes * hdr.keyBytes)
        Secrets.wipe(kmBlocks)
        return afMerge(keyMaterial, slot.stripes, hdr.keyBytes, hdr.hashSpec)
    }

    // -----------------------------------------------------------------------
    // LUKS2
    // -----------------------------------------------------------------------

    private fun unlockLuks2(
        device: RawBlockDevice,
        startBlock: Long,
        partitionBytes: Long,
        hdr: LuksHeader.Luks2,
        password: ByteArray,
        availableMemoryBytes: Long?,
    ): RawBlockDevice {
        Log.i(TAG, "LUKS2: sectorSize=${hdr.sectorSize} segment=${hdr.segmentOffsetBytes}+${hdr.segmentSizeBytes ?: "dynamic"} " +
            "keyslots=${hdr.keyslots.size} digests=${hdr.digests.size}")

        // cryptsetup has defaulted to 4096-byte sectors since 2.4 wherever the device
        // allows it, so a LUKS2 drive formatted on current Linux usually has them.
        if (hdr.sectorSize !in setOf(512, 1024, 2048, 4096)) {
            throw UnsupportedFormatException("LUKS2 sector size ${hdr.sectorSize} is not supported.")
        }
        // L07: the IV offset is applied on 512-byte sectors; on larger ones its units have not
        // been verified against dm-crypt, so such a volume is refused rather than misread.
        if (hdr.ivTweak != 0L && hdr.sectorSize != 512) {
            throw UnsupportedFormatException("LUKS2 iv_tweak ${hdr.ivTweak} with ${hdr.sectorSize}-byte sectors is not supported.")
        }

        // L04: the data segment must lie after the metadata and inside the partition, and the
        // device exposes exactly the segment, not everything to the end of the partition.
        val dataBytes = try {
            val metaEnd = hdr.metadataEnd
            if (hdr.segmentOffsetBytes < metaEnd) {
                throw CheckedRange.OutOfRange("the data segment starts at ${hdr.segmentOffsetBytes}, inside the metadata (to $metaEnd)")
            }
            CheckedRange.requireAligned(hdr.segmentOffsetBytes, maxOf(512L, device.blockSize.toLong()), "LUKS2 segment offset")
            val size = hdr.segmentSizeBytes ?: (partitionBytes - hdr.segmentOffsetBytes)
            CheckedRange.requireWithin(hdr.segmentOffsetBytes, size, 0, partitionBytes, "LUKS2 data segment")
            CheckedRange.requireAligned(size, hdr.sectorSize.toLong(), "LUKS2 segment size")
            size
        } catch (e: CheckedRange.OutOfRange) {
            throw UnsupportedFormatException("The LUKS2 header describes a data area that does not fit: ${e.message}")
        }

        // L03: a key is accepted only through a digest bound to this data segment.
        val segmentDigests = hdr.digests.filter { hdr.segmentId in it.segments }
        if (segmentDigests.isEmpty()) {
            throw UnsupportedFormatException("This LUKS2 volume has no key digest for its data segment; OTG Master cannot verify a key for it.")
        }

        val reasons = hdr.unusableSlots.toMutableList()
        var tried = false
        // L08: preferred slots (priority 2) before normal ones.
        for (slot in hdr.keyslots.sortedWith(compareByDescending<Luks2Keyslot> { it.priority }.thenBy { it.index })) {
            val id = slot.index.toString()
            val digest = segmentDigests.firstOrNull { id in it.keyslots }
            if (digest == null) { reasons += "keyslot $id: its key is not bound to the data segment"; continue }
            val unusable = luks2SlotProblem(slot, hdr, partitionBytes, availableMemoryBytes)
            if (unusable != null) { reasons += "keyslot $id: $unusable"; continue }

            Log.i(TAG, "Trying LUKS2 keyslot $id (${slot::class.simpleName}, priority ${slot.priority})")
            tried = true
            val masterKey = tryLuks2Keyslot(device, startBlock, slot, password) ?: continue
            if (!digestMatches(masterKey, digest)) {
                Log.w(TAG, "Keyslot $id: key derived but digest mismatch")
                Secrets.wipe(masterKey)
                continue
            }
            Log.i(TAG, "Keyslot $id: digest verified")
            val payloadBlocks = hdr.segmentOffsetBytes / device.blockSize
            val decryptedBlocks = dataBytes / device.blockSize
            if (hdr.sectorSize != 512) {
                return LargeSectorXtsDevice(device, startBlock + payloadBlocks, decryptedBlocks,
                    masterKey, hdr.sectorSize, hdr.ivTweak)
            }
            return makeCryptoDevice(device, startBlock, masterKey, payloadBlocks, decryptedBlocks, hdr.ivTweak)
        }

        // A password can only be wrong if some keyslot was able to test it.
        if (!tried) {
            throw UnsupportedFormatException("No keyslot of this LUKS2 volume can be used by OTG Master: " + reasons.joinToString("; "))
        }
        throw WrongPasswordException()
    }

    /**
     * Why [slot] cannot be tried here, or null if it can: a key size this app's AES-XTS does
     * not take (L06), a key area outside the metadata or too small for its AF stripes (L04),
     * or an Argon2 cost out of range or beyond the phone's free memory (L08). Each is an
     * unsupported slot, never a wrong password.
     */
    private fun luks2SlotProblem(slot: Luks2Keyslot, hdr: LuksHeader.Luks2, partitionBytes: Long, availableMemoryBytes: Long?): String? {
        if (slot.keySize != 64) return "a ${slot.keySize * 8}-bit AES-XTS volume key is not supported (only 512-bit)"
        if (slot.areaKeySize != 64) return "a ${slot.areaKeySize * 8}-bit keyslot encryption key is not supported (only 512-bit)"
        try {
            val afBytes = CheckedRange.multiply(slot.afStripes.toLong(), slot.keySize.toLong(), "AF material")
            if (afBytes > slot.areaSizeBytes) return "its key material ($afBytes B) does not fit its area (${slot.areaSizeBytes} B)"
            val areaUpper = if (hdr.keyslotsSize != null) hdr.metadataEnd else minOf(hdr.segmentOffsetBytes, partitionBytes)
            CheckedRange.requireWithin(slot.areaOffsetBytes, slot.areaSizeBytes, 2 * hdr.hdrSize, areaUpper, "keyslot area")
            CheckedRange.requireAligned(slot.areaOffsetBytes, 512, "keyslot area offset")
        } catch (e: CheckedRange.OutOfRange) {
            return e.message
        }
        when (slot) {
            is Luks2Keyslot.Pbkdf2 -> if (slot.iterations <= 0) return "${slot.iterations} PBKDF2 iterations"
            is Luks2Keyslot.Argon2id -> {
                if (slot.timeCost < 1 || slot.parallelism !in 1..MAX_ARGON2_LANES ||
                    slot.memoryCostKiB !in 8..MAX_ARGON2_MEMORY_KIB) {
                    return "Argon2 cost out of range (time ${slot.timeCost}, memory ${slot.memoryCostKiB} KiB, lanes ${slot.parallelism})"
                }
                if (availableMemoryBytes != null && slot.memoryCostKiB * 1024L > availableMemoryBytes) {
                    return "it needs ${slot.memoryCostKiB / 1024} MiB to unlock and the phone has " +
                        "${availableMemoryBytes / (1024 * 1024)} MiB free"
                }
            }
        }
        return null
    }

    /** Whether [masterKey] matches [digest]: PBKDF2 to the digest's own length, compared in constant time. */
    private fun digestMatches(masterKey: ByteArray, digest: Luks2Digest): Boolean {
        val derived = pbkdf2(LuksAlgorithms.hmac(digest.hash, "digest hash"), masterKey, digest.salt,
            digest.iterations, digest.digest.size)
        return try { MessageDigest.isEqual(derived, digest.digest) } finally { Secrets.wipe(derived) }
    }

    private fun tryLuks2Keyslot(
        device: RawBlockDevice,
        partitionStartBlock: Long,
        slot: Luks2Keyslot,
        password: ByteArray
    ): ByteArray? {
        // The keyslot area's own key, at the area's key size (L06), not the volume key's.
        val slotKey: ByteArray = when (slot) {
            is Luks2Keyslot.Argon2id -> {
                // The vendored reference Argon2 (as VeraCrypt volumes use), not Argon2Kt: the
                // same code then runs in host tests, where a LUKS2 volume made by
                // LuksVolumeCreator or by cryptsetup is unlocked against a loopback image.
                try {
                    // The variant the keyslot names: Argon2i keyslots were derived as Argon2id
                    // and never unlocked (review, 2026-11-09).
                    (if (slot.kdfType == "argon2i")
                        app.fayaz.otgmaster.veracrypt.VeraCryptNative.argon2i(password, slot.salt,
                            slot.timeCost, slot.memoryCostKiB, slot.parallelism, slot.areaKeySize)
                    else
                        app.fayaz.otgmaster.veracrypt.VeraCryptNative.argon2id(password, slot.salt,
                            slot.timeCost, slot.memoryCostKiB, slot.parallelism, slot.areaKeySize))
                        ?: throw UnsupportedFormatException("Argon2 could not run")
                } catch (e: IllegalStateException) {
                    throw UnsupportedFormatException(
                        "This LUKS2 volume requires ${slot.memoryCostKiB / 1024} MiB to unlock " +
                        "— Argon2id failed: ${e.message}"
                    )
                } catch (e: OutOfMemoryError) {
                    throw UnsupportedFormatException(
                        "This LUKS2 volume requires ${slot.memoryCostKiB / 1024} MiB to unlock " +
                        "— not enough memory available on this device."
                    )
                }
            }
            is Luks2Keyslot.Pbkdf2 ->
                pbkdf2(LuksAlgorithms.hmac(slot.hash, "keyslot PBKDF2 hash"), password, slot.salt, slot.iterations, slot.areaKeySize)
        }

        // Bounds were checked by luks2SlotProblem; the area is read whole sectors at a time.
        val afBytes = slot.afStripes * slot.keySize
        val kmSectors = (afBytes + 511) / 512
        val kmData = device.readPartitionBytes(partitionStartBlock, slot.areaOffsetBytes, kmSectors * 512)
        try {
            val rc = Secrets.use(slotKey) { key ->
                app.fayaz.otgmaster.veracrypt.VeraCryptNative.cryptSectorsInPlace(
                    intArrayOf(SingleCipher.AES.nativeId), 0, key, 0L, kmData, 0, kmSectors * 512)
            }
            if (rc != 0) {
                Log.w(TAG, "Keyslot ${slot.index}: XTS decrypt failed (rc=$rc)")
                return null
            }
            return afMerge(kmData.copyOfRange(0, afBytes), slot.afStripes, slot.keySize, slot.afHash)
        } finally {
            Secrets.wipe(kmData)
        }
    }

    // -----------------------------------------------------------------------
    // Shared: build the decrypted block device
    // -----------------------------------------------------------------------

    /** The LUKS1 data area: from [payloadOffsetSectors] (512-byte units) to the end of the partition. */
    private fun makeCryptoDevice(
        device: RawBlockDevice,
        partitionStartBlock: Long,
        blockCount: Long?,
        masterKey: ByteArray,
        payloadOffsetSectors: Long
    ): RawBlockDevice {
        // payloadOffsetSectors is from the start of the PARTITION (not the physical disk),
        // and LUKS counts it in 512-byte sectors regardless of the medium.
        // NativeDecryptedBlockDevice's volumeDataOffset is in *device blocks*, so the two
        // units only coincide on a 512-byte-sector device.  Adding them directly put the
        // data area 8x too close to the partition start on a 4Kn drive, which decrypts
        // and writes at the wrong offset — silently, in both directions.
        val payloadOffsetBytes = payloadOffsetSectors * 512L
        require(payloadOffsetBytes % device.blockSize == 0L) {
            "LUKS payload offset $payloadOffsetBytes B is not a multiple of the " +
            "device block size ${device.blockSize} B"
        }
        val payloadOffsetBlocks = payloadOffsetBytes / device.blockSize
        val totalPartitionBlocks = blockCount ?: (device.blockCount - partitionStartBlock)
        return makeCryptoDevice(device, partitionStartBlock, masterKey, payloadOffsetBlocks,
            totalPartitionBlocks - payloadOffsetBlocks, ivTweak = 0L)
    }

    /**
     * A 512-byte-sector AES-XTS data area of [decryptedBlocks] device blocks starting
     * [payloadOffsetBlocks] into the partition. plain64's IV is the sector number within the
     * segment plus [ivTweak] (in 512-byte sectors), which the old code always took as 0 (L07).
     */
    private fun makeCryptoDevice(
        device: RawBlockDevice,
        partitionStartBlock: Long,
        masterKey: ByteArray,
        payloadOffsetBlocks: Long,
        decryptedBlocks: Long,
        ivTweak: Long,
    ): RawBlockDevice = NativeDecryptedBlockDevice(
        encryptedDevice    = device,
        masterKey          = masterKey,
        volumeDataOffset   = partitionStartBlock + payloadOffsetBlocks,
        decryptedBlockCount= decryptedBlocks,
        cipherNativeIds    = intArrayOf(SingleCipher.AES.nativeId),
        tweakDataOffset    = ivTweak
    )

    // -----------------------------------------------------------------------
    // AF-split merge
    // -----------------------------------------------------------------------

    // Merges stripes × keyLen bytes of AF-encoded key material into the keyLen-byte master key.
    // See LUKS spec §7 and cryptsetup/lib/crypt_af.c.
    internal fun afMerge(keyMaterial: ByteArray, stripes: Int, keyLen: Int, hashSpec: String): ByteArray {
        val buf = ByteArray(keyLen)  // accumulator, starts as zeros
        val stripe = ByteArray(keyLen)
        try {
            for (i in 0 until stripes - 1) {
                keyMaterial.copyInto(stripe, 0, i * keyLen, (i + 1) * keyLen)
                xorInto(buf, stripe)
                hashDiffuse(buf, hashSpec)
            }
            keyMaterial.copyInto(stripe, 0, (stripes - 1) * keyLen, stripes * keyLen)
            xorInto(buf, stripe)
            return buf
        } finally {
            // S01: the stripes and the material they came from are key material too.
            Secrets.wipe(stripe, keyMaterial)
        }
    }

    // Applies the LUKS hash-diffuse function in place.
    // Splits buf into SHA-output-size chunks, replacing each chunk j with
    // SHA(uint32_be(j) || chunk).
    private fun hashDiffuse(buf: ByteArray, hashSpec: String) {
        val mdAlg = LuksAlgorithms.digest(hashSpec, "AF hash")
        val md     = MessageDigest.getInstance(mdAlg)
        val hLen   = md.digestLength
        val result = ByteArray(buf.size)
        var pos    = 0
        var chunk  = 0
        while (pos < buf.size) {
            val end   = minOf(pos + hLen, buf.size)
            val count = end - pos
            md.reset()
            // The chunk index is a full 32-bit big-endian counter in the LUKS AF spec.
            // Hardcoding the top two bytes to zero is correct for any key material we
            // will ever see (chunk peaks near 1; reaching 65536 needs a ~1.3 MB buffer)
            // but only by accident of size.
            md.update(byteArrayOf(
                (chunk shr 24).toByte(),
                (chunk shr 16).toByte(),
                (chunk shr 8).toByte(),
                chunk.toByte(),
            ))
            md.update(buf, pos, count)
            val hash = md.digest()
            hash.copyInto(result, pos, 0, count)
            hash.fill(0)
            pos   += hLen
            chunk += 1
        }
        result.copyInto(buf)
        result.fill(0)
    }

    private fun xorInto(dest: ByteArray, src: ByteArray) {
        for (i in dest.indices) dest[i] = (dest[i].toInt() xor src[i].toInt()).toByte()
    }

    // -----------------------------------------------------------------------
    // PBKDF2 with raw byte password
    // -----------------------------------------------------------------------

    internal fun pbkdf2(hmacAlg: String, password: ByteArray, salt: ByteArray, iterations: Int, keyLen: Int): ByteArray {
        val mac = Mac.getInstance(hmacAlg)
        mac.init(SecretKeySpec(password, hmacAlg))
        val hLen   = mac.macLength
        val result = ByteArray(keyLen)
        var remaining = keyLen
        var offset    = 0
        var blockIdx  = 1
        while (remaining > 0) {
            // U1 = HMAC(password, salt || INT_be(block))
            mac.reset()
            mac.update(salt)
            val idxBuf = ByteBuffer.allocate(4).putInt(blockIdx).array()
            mac.update(idxBuf)
            var u = mac.doFinal()
            val t = u.copyOf()
            for (iter in 2..iterations) {
                mac.reset()
                mac.update(u)
                u = mac.doFinal()
                for (k in u.indices) t[k] = (t[k].toInt() xor u[k].toInt()).toByte()
            }
            val len = minOf(remaining, hLen)
            t.copyInto(result, offset, 0, len)
            offset    += len
            remaining -= len
            blockIdx  += 1
        }
        return result
    }

    // -----------------------------------------------------------------------
    // Cipher validation
    // -----------------------------------------------------------------------

    private fun verifyCipher(cipherName: String, cipherMode: String) {
        if (!cipherName.equals("aes", ignoreCase = true)) {
            throw UnsupportedFormatException(
                "Cipher '$cipherName' is not supported. Only AES (aes-xts-plain64) is supported."
            )
        }
        if (!cipherMode.equals("xts-plain64", ignoreCase = true)) {
            throw UnsupportedFormatException(
                "Cipher mode '$cipherMode' is not supported: OTG Master opens only aes-xts-plain64. " +
                "The volume can still be opened with cryptsetup on a computer."
            )
        }
    }

    private fun pbkdf2AlgFor(hashSpec: String): String = LuksAlgorithms.hmac(hashSpec, "LUKS1 hash")
}
