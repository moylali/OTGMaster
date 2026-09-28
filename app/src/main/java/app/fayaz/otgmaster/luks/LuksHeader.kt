package app.fayaz.otgmaster.luks

import org.json.JSONObject
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Base64

// LUKS magic bytes: "LUKS\xBA\xBE"
private val LUKS_MAGIC = byteArrayOf(0x4C, 0x55, 0x4B, 0x53, 0xBA.toByte(), 0xBE.toByte())

private const val LUKS2_BINARY_HDR_SIZE = 4096

sealed class LuksHeader {
    abstract val cipherName: String
    abstract val cipherMode: String
    abstract val payloadOffsetSectors: Long
    abstract val keyBytes: Int

    data class Luks1(
        override val cipherName: String,
        override val cipherMode: String,
        val hashSpec: String,
        override val payloadOffsetSectors: Long,
        override val keyBytes: Int,
        val mkDigest: ByteArray,
        val mkDigestSalt: ByteArray,
        val mkDigestIter: Int,
        val uuid: String,
        val keyslots: List<Luks1Keyslot>
    ) : LuksHeader()

    data class Luks2(
        override val cipherName: String,
        override val cipherMode: String,
        override val payloadOffsetSectors: Long,
        override val keyBytes: Int,
        val sectorSize: Int,
        val ivTweak: Long,
        val keyslots: List<Luks2Keyslot>,
        val digest: Luks2Digest
    ) : LuksHeader()
}

data class Luks1Keyslot(
    val index: Int,
    val active: Boolean,
    val iterations: Int,
    val salt: ByteArray,
    val keyMaterialOffsetSectors: Long,
    val stripes: Int
)

sealed class Luks2Keyslot {
    abstract val index: Int
    abstract val keySize: Int
    abstract val areaOffsetBytes: Long
    abstract val areaSizeBytes: Long
    abstract val areaEncryption: String
    abstract val areaKeySize: Int
    abstract val afStripes: Int
    abstract val afHash: String

    data class Argon2id(
        override val index: Int,
        override val keySize: Int,
        override val areaOffsetBytes: Long,
        override val areaSizeBytes: Long,
        override val areaEncryption: String,
        override val areaKeySize: Int,
        override val afStripes: Int,
        override val afHash: String,
        val timeCost: Int,
        val memoryCostKiB: Int,
        val parallelism: Int,
        val salt: ByteArray
    ) : Luks2Keyslot()

    data class Pbkdf2(
        override val index: Int,
        override val keySize: Int,
        override val areaOffsetBytes: Long,
        override val areaSizeBytes: Long,
        override val areaEncryption: String,
        override val areaKeySize: Int,
        override val afStripes: Int,
        override val afHash: String,
        val hash: String,
        val iterations: Int,
        val salt: ByteArray
    ) : Luks2Keyslot()
}

data class Luks2Digest(
    val type: String,
    val hash: String,
    val iterations: Int,
    val salt: ByteArray,
    val digest: ByteArray
)

object LuksParser {

    fun hasLuksMagic(data: ByteArray, offset: Int = 0): Boolean {
        if (data.size < offset + 6) return false
        return LUKS_MAGIC.indices.all { data[offset + it] == LUKS_MAGIC[it] }
    }

    fun getVersion(data: ByteArray, offset: Int = 0): Int {
        if (data.size < offset + 8) return -1
        return ((data[offset + 6].toInt() and 0xFF) shl 8) or (data[offset + 7].toInt() and 0xFF)
    }

    // Parses a LUKS1 header from data starting at offset 0.
    // data must be at least 512 bytes (the binary header) + enough keyslot areas to read.
    fun parseLuks1(data: ByteArray): LuksHeader.Luks1 {
        require(data.size >= 592) { "LUKS1 header too short: ${data.size}" }
        val bb = ByteBuffer.wrap(data).order(ByteOrder.BIG_ENDIAN)

        check(hasLuksMagic(data)) { "Not a LUKS volume" }
        check(getVersion(data) == 1) { "Expected LUKS version 1, got ${getVersion(data)}" }

        val cipherName  = data.nullTermString(8, 32)
        val cipherMode  = data.nullTermString(40, 32)
        val hashSpec    = data.nullTermString(72, 32)
        val payloadOff  = bb.getInt(104).toLong() and 0xFFFFFFFFL
        val keyBytes    = bb.getInt(108)
        val mkDigest    = data.copyOfRange(112, 132)
        val mkDigestSalt= data.copyOfRange(132, 164)
        val mkDigestIter= bb.getInt(164)
        val uuid        = data.nullTermString(168, 40)

        val keyslots = (0 until 8).map { i ->
            val base = 208 + i * 48
            val active  = (bb.getInt(base).toLong() and 0xFFFFFFFFL) == 0x00AC71F3L
            val iters   = bb.getInt(base + 4)
            val salt    = data.copyOfRange(base + 8, base + 40)
            val kmOff   = (bb.getInt(base + 40).toLong() and 0xFFFFFFFFL)
            val stripes = bb.getInt(base + 44)
            Luks1Keyslot(i, active, iters, salt, kmOff, stripes)
        }

        return LuksHeader.Luks1(
            cipherName = cipherName,
            cipherMode = cipherMode,
            hashSpec    = hashSpec,
            payloadOffsetSectors = payloadOff,
            keyBytes    = keyBytes,
            mkDigest    = mkDigest,
            mkDigestSalt= mkDigestSalt,
            mkDigestIter= mkDigestIter,
            uuid        = uuid,
            keyslots    = keyslots
        )
    }

    // Parses a LUKS2 header from data starting at offset 0.
    // data must contain the 4096-byte binary header followed by the JSON section.
    fun parseLuks2(data: ByteArray): LuksHeader.Luks2 {
        require(data.size >= LUKS2_BINARY_HDR_SIZE) { "LUKS2 data too short: ${data.size}" }
        check(hasLuksMagic(data)) { "Not a LUKS volume" }
        check(getVersion(data) == 2) { "Expected LUKS version 2, got ${getVersion(data)}" }

        val bb = ByteBuffer.wrap(data).order(ByteOrder.BIG_ENDIAN)
        val hdrSize = bb.getLong(8)  // total size of primary + secondary headers
        // hdrSize comes off the medium, so a malformed or hostile header can make it
        // smaller than the binary header it must follow. copyOfRange then gets a
        // fromIndex above its toIndex and throws IllegalArgumentException, taking the
        // app down instead of refusing the volume.
        if (hdrSize <= LUKS2_BINARY_HDR_SIZE) {
            throw LuksUnlocker.UnsupportedFormatException(
                "LUKS2 header size $hdrSize is not larger than the " +
                "$LUKS2_BINARY_HDR_SIZE-byte binary header"
            )
        }

        // JSON area: bytes [4096 .. hdrSize) of the primary header block
        val jsonEnd = minOf(hdrSize, data.size.toLong()).toInt()
        val jsonBytes = data.copyOfRange(LUKS2_BINARY_HDR_SIZE, jsonEnd)
        val jsonStr = String(jsonBytes, Charsets.UTF_8).trimEnd('\u0000')

        val json = JSONObject(jsonStr)

        // --- segments: find the first "crypt" segment ---
        val segsJson = json.getJSONObject("segments")
        var cipherName = ""
        var cipherMode = ""
        var payloadOffBytes = 0L
        var sectorSize = 512
        var ivTweak = 0L
        for (key in segsJson.keys()) {
            val seg = segsJson.getJSONObject(key)
            if (seg.getString("type") == "crypt") {
                val encryption = seg.getString("encryption")  // e.g. "aes-xts-plain64"
                val parts = encryption.split("-")
                cipherName = parts.getOrElse(0) { "aes" }
                cipherMode = if (parts.size > 1) parts.drop(1).joinToString("-") else "xts-plain64"
                payloadOffBytes = seg.getString("offset").toLong()
                sectorSize = seg.optInt("sector_size", 512)
                ivTweak = seg.optString("iv_tweak", "0").toLong()
                break
            }
        }

        // --- keyslots: parse active LUKS2 keyslots ---
        val kslJson = json.getJSONObject("keyslots")
        val keyslots = mutableListOf<Luks2Keyslot>()
        for (key in kslJson.keys()) {
            val ksl = kslJson.getJSONObject(key)
            if (ksl.getString("type") != "luks2") continue

            val index     = key.toInt()
            val keySize   = ksl.getInt("key_size")
            val area      = ksl.getJSONObject("area")
            val areaOff   = area.getString("offset").toLong()
            val areaSize  = area.getString("size").toLong()
            val areaEnc   = area.getString("encryption")
            val areaKsz   = area.getInt("key_size")
            val af        = ksl.getJSONObject("af")
            val afStripes = af.getInt("stripes")
            val afHash    = af.getString("hash")
            val kdf       = ksl.getJSONObject("kdf")

            when (kdf.getString("type")) {
                "argon2id", "argon2i" -> {
                    keyslots.add(Luks2Keyslot.Argon2id(
                        index        = index,
                        keySize      = keySize,
                        areaOffsetBytes = areaOff,
                        areaSizeBytes   = areaSize,
                        areaEncryption  = areaEnc,
                        areaKeySize  = areaKsz,
                        afStripes    = afStripes,
                        afHash       = afHash,
                        timeCost     = kdf.getInt("time"),
                        memoryCostKiB= kdf.getInt("memory"),
                        parallelism  = kdf.getInt("cpus"),
                        salt         = Base64.getDecoder().decode(kdf.getString("salt"))
                    ))
                }
                "pbkdf2" -> {
                    keyslots.add(Luks2Keyslot.Pbkdf2(
                        index        = index,
                        keySize      = keySize,
                        areaOffsetBytes = areaOff,
                        areaSizeBytes   = areaSize,
                        areaEncryption  = areaEnc,
                        areaKeySize  = areaKsz,
                        afStripes    = afStripes,
                        afHash       = afHash,
                        hash         = kdf.getString("hash"),
                        iterations   = kdf.getInt("iterations"),
                        salt         = Base64.getDecoder().decode(kdf.getString("salt"))
                    ))
                }
            }
        }

        // --- digest (first pbkdf2 digest) ---
        val digsJson = json.getJSONObject("digests")
        var digest = Luks2Digest("pbkdf2", "sha256", 0, ByteArray(0), ByteArray(0))
        for (key in digsJson.keys()) {
            val d = digsJson.getJSONObject(key)
            if (d.getString("type") == "pbkdf2") {
                digest = Luks2Digest(
                    type       = "pbkdf2",
                    hash       = d.getString("hash"),
                    iterations = d.getInt("iterations"),
                    salt       = Base64.getDecoder().decode(d.getString("salt")),
                    digest     = Base64.getDecoder().decode(d.getString("digest"))
                )
                break
            }
        }

        // payloadOffBytes → sectors (512-byte units; LUKS2 always references the device in bytes)
        val payloadOffSectors = payloadOffBytes / 512L

        return LuksHeader.Luks2(
            cipherName           = cipherName,
            cipherMode           = cipherMode,
            payloadOffsetSectors = payloadOffSectors,
            keyBytes             = keyslots.firstOrNull()?.keySize ?: 64,
            sectorSize           = sectorSize,
            ivTweak              = ivTweak,
            keyslots             = keyslots,
            digest               = digest
        )
    }

    // --- helpers ---

    private fun ByteArray.nullTermString(offset: Int, maxLen: Int): String {
        var end = offset
        while (end < offset + maxLen && end < size && this[end] != 0.toByte()) end++
        return String(this, offset, end - offset, Charsets.US_ASCII)
    }
}
