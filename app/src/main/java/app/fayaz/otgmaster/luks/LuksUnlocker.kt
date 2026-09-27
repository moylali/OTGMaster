package app.fayaz.otgmaster.luks

import android.util.Log
import app.fayaz.otgmaster.block.RawBlockDevice
import app.fayaz.otgmaster.veracrypt.NativeDecryptedBlockDevice
import app.fayaz.otgmaster.veracrypt.SingleCipher
import com.lambdapioneer.argon2kt.Argon2Kt
import com.lambdapioneer.argon2kt.Argon2Mode
import java.nio.ByteBuffer
import java.security.MessageDigest
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

private const val TAG = "LuksUnlocker"

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
        password: ByteArray
    ): RawBlockDevice {
        // Read enough data to cover both the LUKS1 binary header (512 bytes)
        // and the LUKS2 binary header + JSON area (we read 512 sectors = 256 KiB,
        // which is always enough for the JSON on standard-format volumes).
        val maxSectors = minOf(512L, blockCount ?: device.blockCount - startBlock).toInt()
        val headerData = device.readBlocks(startBlock, maxSectors)

        val version = LuksParser.getVersion(headerData)
        Log.i(TAG, "LUKS version $version at block $startBlock")

        return when (version) {
            1    -> unlockLuks1(device, startBlock, blockCount, headerData, password)
            2    -> unlockLuks2(device, startBlock, blockCount, headerData, password)
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
            if (!derived.contentEquals(hdr.mkDigest)) {
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
            SingleCipher.AES.nativeId,
            0, // decrypt
            slotKey,
            0L, // start sector 0 — plain64 from start of key material area
            kmBlocks, 0, (kmSectorCount * 512).coerceAtMost(kmBlocks.size)
        )
        slotKey.fill(0)
        if (rc != 0) {
            Log.w(TAG, "Keyslot ${slot.index}: XTS decrypt failed (rc=$rc)")
            return null
        }

        // Step 3: AF-split merge → master key candidate
        val keyMaterial = kmBlocks.copyOfRange(0, slot.stripes * hdr.keyBytes)
        return afMerge(keyMaterial, slot.stripes, hdr.keyBytes, hdr.hashSpec)
    }

    // -----------------------------------------------------------------------
    // LUKS2
    // -----------------------------------------------------------------------

    private fun unlockLuks2(
        device: RawBlockDevice,
        startBlock: Long,
        blockCount: Long?,
        headerData: ByteArray,
        password: ByteArray
    ): RawBlockDevice {
        val hdr = LuksParser.parseLuks2(headerData)
        Log.i(TAG, "LUKS2: cipher=${hdr.cipherName}-${hdr.cipherMode} sectorSize=${hdr.sectorSize} keyBytes=${hdr.keyBytes} payloadOff=${hdr.payloadOffsetSectors}")

        verifyCipher(hdr.cipherName, hdr.cipherMode)

        if (hdr.sectorSize != 512) {
            throw UnsupportedFormatException(
                "LUKS2 sector size ${hdr.sectorSize} is not yet supported (only 512-byte sectors)."
            )
        }

        if (hdr.keyslots.isEmpty()) throw UnsupportedFormatException("No LUKS2 keyslots found")

        for (slot in hdr.keyslots) {
            Log.i(TAG, "Trying LUKS2 keyslot ${slot.index} (${slot::class.simpleName})")
            val masterKey = tryLuks2Keyslot(device, startBlock, hdr, slot, password) ?: continue

            // Verify using PBKDF2 digest
            val dig = hdr.digest
            val derived = pbkdf2(pbkdf2AlgFor(dig.hash), masterKey, dig.salt, dig.iterations, masterKey.size)
            if (!derived.copyOfRange(0, dig.digest.size).contentEquals(dig.digest)) {
                Log.w(TAG, "Keyslot ${slot.index}: key derived but digest mismatch")
                masterKey.fill(0)
                continue
            }

            Log.i(TAG, "Keyslot ${slot.index}: digest verified, payload at sector ${hdr.payloadOffsetSectors}")
            return makeCryptoDevice(device, startBlock, blockCount, masterKey, hdr.payloadOffsetSectors)
        }

        throw WrongPasswordException()
    }

    private fun tryLuks2Keyslot(
        device: RawBlockDevice,
        partitionStartBlock: Long,
        hdr: LuksHeader.Luks2,
        slot: Luks2Keyslot,
        password: ByteArray
    ): ByteArray? {
        val slotKey: ByteArray = when (slot) {
            is Luks2Keyslot.Argon2id -> {
                try {
                    val argon2 = Argon2Kt()
                    val result = argon2.hash(
                        mode             = Argon2Mode.ARGON2_ID,
                        password         = password,
                        salt             = slot.salt,
                        tCostInIterations= slot.timeCost,
                        mCostInKibibyte  = slot.memoryCostKiB,
                        parallelism      = slot.parallelism,
                        hashLengthInBytes= slot.keySize
                    )
                    result.rawHashAsByteArray()
                } catch (e: OutOfMemoryError) {
                    throw UnsupportedFormatException(
                        "This LUKS2 volume requires ${slot.memoryCostKiB / 1024} MiB to unlock " +
                        "— not enough memory available on this device."
                    )
                }
            }
            is Luks2Keyslot.Pbkdf2 -> {
                pbkdf2(pbkdf2AlgFor(slot.hash), password, slot.salt, slot.iterations, slot.keySize)
            }
        }

        // Key area offset is in bytes from partition start
        val kmAbsoluteByteOffset = partitionStartBlock.toLong() * device.blockSize + slot.areaOffsetBytes
        val kmSectors = (slot.afStripes * slot.keySize + 511) / 512
        val kmData = device.readBytes(kmAbsoluteByteOffset, kmSectors * 512)

        // XTS-decrypt using slotKey (area encryption is e.g. "aes-xts-plain64")
        val rc = app.fayaz.otgmaster.veracrypt.VeraCryptNative.cryptSectorsInPlace(
            SingleCipher.AES.nativeId,
            0,        // decrypt
            slotKey,
            0L,       // plain64: sector 0 at start of key material
            kmData, 0, kmSectors * 512
        )
        slotKey.fill(0)
        if (rc != 0) {
            Log.w(TAG, "Keyslot ${slot.index}: XTS decrypt failed (rc=$rc)")
            return null
        }

        val keyMaterial = kmData.copyOfRange(0, slot.afStripes * slot.keySize)
        return afMerge(keyMaterial, slot.afStripes, slot.keySize, slot.afHash)
    }

    // -----------------------------------------------------------------------
    // Shared: build the decrypted block device
    // -----------------------------------------------------------------------

    private fun makeCryptoDevice(
        device: RawBlockDevice,
        partitionStartBlock: Long,
        blockCount: Long?,
        masterKey: ByteArray,
        payloadOffsetSectors: Long
    ): RawBlockDevice {
        // payloadOffsetSectors is from the start of the PARTITION (not the physical disk).
        // NativeDecryptedBlockDevice's volumeDataOffset is in blocks from the start of 'device'.
        val volumeDataOffsetBlocks = partitionStartBlock + payloadOffsetSectors
        val totalPartitionBlocks   = blockCount ?: (device.blockCount - partitionStartBlock)
        val decryptedBlocks        = totalPartitionBlocks - payloadOffsetSectors

        // LUKS plain64 IV: tweak sector = 0 for first payload sector, so tweakDataOffset = 0.
        // This differs from VeraCrypt where tweakDataOffset = dataOffsetSectors (≈256).
        return NativeDecryptedBlockDevice(
            encryptedDevice    = device,
            masterKey          = masterKey,
            volumeDataOffset   = volumeDataOffsetBlocks,
            decryptedBlockCount= decryptedBlocks,
            cipherNativeId     = SingleCipher.AES.nativeId,
            tweakDataOffset    = 0L
        )
    }

    // -----------------------------------------------------------------------
    // AF-split merge
    // -----------------------------------------------------------------------

    // Merges stripes × keyLen bytes of AF-encoded key material into the keyLen-byte master key.
    // See LUKS spec §7 and cryptsetup/lib/crypt_af.c.
    internal fun afMerge(keyMaterial: ByteArray, stripes: Int, keyLen: Int, hashSpec: String): ByteArray {
        val buf = ByteArray(keyLen)  // accumulator, starts as zeros
        for (i in 0 until stripes - 1) {
            val stripe = keyMaterial.copyOfRange(i * keyLen, (i + 1) * keyLen)
            xorInto(buf, stripe)
            hashDiffuse(buf, hashSpec)
        }
        xorInto(buf, keyMaterial.copyOfRange((stripes - 1) * keyLen, stripes * keyLen))
        return buf
    }

    // Applies the LUKS hash-diffuse function in place.
    // Splits buf into SHA-output-size chunks, replacing each chunk j with
    // SHA(uint32_be(j) || chunk).
    private fun hashDiffuse(buf: ByteArray, hashSpec: String) {
        val mdAlg = when (hashSpec.lowercase()) {
            "sha1"   -> "SHA-1"
            "sha256" -> "SHA-256"
            "sha512" -> "SHA-512"
            else     -> "SHA-256"
        }
        val md     = MessageDigest.getInstance(mdAlg)
        val hLen   = md.digestLength
        val result = ByteArray(buf.size)
        var pos    = 0
        var chunk  = 0
        while (pos < buf.size) {
            val end   = minOf(pos + hLen, buf.size)
            val count = end - pos
            md.reset()
            md.update(byteArrayOf(0, 0, (chunk shr 8).toByte(), chunk.toByte()))
            md.update(buf, pos, count)
            val hash = md.digest()
            hash.copyInto(result, pos, 0, count)
            pos   += hLen
            chunk += 1
        }
        result.copyInto(buf)
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
        if (!cipherMode.startsWith("xts-plain64", ignoreCase = true)) {
            throw UnsupportedFormatException(
                "Cipher mode '$cipherMode' is not supported. Only xts-plain64 is supported. " +
                "Older volumes using aes-cbc-essiv:sha256 require reformatting."
            )
        }
    }

    private fun pbkdf2AlgFor(hashSpec: String): String = when (hashSpec.lowercase()) {
        "sha1"   -> "HmacSHA1"
        "sha256" -> "HmacSHA256"
        "sha512" -> "HmacSHA512"
        else     -> "HmacSHA256"
    }
}
