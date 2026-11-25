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
        /** Every PBKDF2 digest, with the keyslots and segments each one covers. */
        val digests: List<Luks2Digest>,
        /** The id of the one data segment. */
        val segmentId: String = "0",
        /** The data segment's offset from the start of the partition, in bytes. */
        val segmentOffsetBytes: Long = payloadOffsetSectors * 512,
        /** The data segment's length in bytes, or null for "dynamic" (to the end of the partition). */
        val segmentSizeBytes: Long? = null,
        /** Size of one header copy (binary header plus JSON area). */
        val hdrSize: Long = 16384,
        /** Size of the binary keyslot area after the two header copies, if the header says. */
        val keyslotsSize: Long? = null,
        /** Keyslots the parser could not use, with why; reported if no usable slot is left. */
        val unusableSlots: List<String> = emptyList(),
    ) : LuksHeader() {
        /** Where the metadata (two header copies and the keyslot area) ends, in bytes. */
        val metadataEnd: Long get() = 2 * hdrSize + (keyslotsSize ?: 0)
    }
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
    /** 0 ignored, 1 normal, 2 preferred (LUKS2 spec); ignored slots are never parsed. */
    abstract val priority: Int
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
        val salt: ByteArray,
        /** "argon2id" or "argon2i", as the header names it; the key must be derived with that one. */
        val kdfType: String = "argon2id",
        override val priority: Int = 1,
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
        val salt: ByteArray,
        override val priority: Int = 1,
    ) : Luks2Keyslot()
}

data class Luks2Digest(
    val type: String,
    val hash: String,
    val iterations: Int,
    val salt: ByteArray,
    val digest: ByteArray,
    /** Ids of the keyslots whose key this digest verifies. */
    val keyslots: Set<String> = emptySet(),
    /** Ids of the segments that key decrypts. */
    val segments: Set<String> = emptySet(),
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

    /**
     * Parses the primary LUKS2 header copy at the start of [data] (which must hold the whole
     * copy), refusing it if its checksum or fields are not valid. The unlocker reads both
     * copies through [Luks2Metadata.read] instead; this is for a header already in memory.
     */
    fun parseLuks2(data: ByteArray): LuksHeader.Luks2 {
        require(data.size >= LUKS2_BINARY_HDR_SIZE) { "LUKS2 data too short: ${data.size}" }
        check(hasLuksMagic(data)) { "Not a LUKS volume" }
        check(getVersion(data) == 2) { "Expected LUKS version 2, got ${getVersion(data)}" }
        val hdrSize = ByteBuffer.wrap(data).order(ByteOrder.BIG_ENDIAN).getLong(8)
        // hdrSize comes off the medium; a value that is not one LUKS2 allows (smaller than
        // the binary header, negative, or larger than what was read) refuses the volume
        // instead of sizing a copy from it.
        if (hdrSize !in Luks2Metadata.HEADER_SIZES || hdrSize > data.size) {
            throw LuksUnlocker.UnsupportedFormatException("LUKS2 header size $hdrSize is not valid")
        }
        var why = "invalid"
        val copy = Luks2Metadata.parseCopy(data.copyOf(hdrSize.toInt()), 0) { why = it }
            ?: throw LuksUnlocker.UnsupportedFormatException("The LUKS2 header is damaged: $why")
        return parseLuks2Json(copy.json, copy.hdrSize)
    }

    /**
     * The LUKS2 JSON metadata, validated for what this app can safely open: exactly one
     * ordinary "crypt" segment in aes-xts-plain64, no mandatory requirements, no integrity,
     * no reencryption flags. Anything else is refused here, before a key is derived
     * (review of 2026-11-11, L01, L09). Keyslots this app cannot use are listed in
     * unusableSlots instead of failing the volume, so another slot can still open it.
     */
    fun parseLuks2Json(jsonStr: String, hdrSize: Long): LuksHeader.Luks2 {
        fun refuse(msg: String): Nothing = throw LuksUnlocker.UnsupportedFormatException(msg)
        val json = try { JSONObject(jsonStr) } catch (e: Exception) { refuse("The LUKS2 metadata is not valid JSON") }

        val config = json.optJSONObject("config")
        val mandatory = config?.optJSONObject("requirements")?.optJSONArray("mandatory")
        if (mandatory != null && mandatory.length() > 0) {
            val names = (0 until mandatory.length()).map { mandatory.optString(it) }
            refuse("This LUKS2 volume requires ${names.joinToString()}, which OTG Master does not support " +
                "(for example, a reencryption that has not finished). Open it with cryptsetup on a computer.")
        }
        val keyslotsSize = config?.optString("keyslots_size")?.toLongOrNull()

        val segsJson = json.getJSONObject("segments")
        val segIds = segsJson.keys().asSequence().toList()
        if (segIds.size != 1) {
            refuse("This LUKS2 volume has ${segIds.size} data segments (a reencryption in progress?); " +
                "OTG Master opens only volumes with one.")
        }
        val segId = segIds[0]
        val seg = segsJson.getJSONObject(segId)
        if (seg.optString("type") != "crypt") refuse("LUKS2 segment type \"${seg.optString("type")}\" is not supported")
        seg.optJSONArray("flags")?.let { if (it.length() > 0) refuse("This LUKS2 volume's data segment is flagged ${(0 until it.length()).joinToString { i -> it.optString(i) }}; OTG Master does not open it") }
        if (seg.has("integrity")) refuse("This LUKS2 volume uses dm-integrity, which OTG Master does not support")
        val encryption = seg.getString("encryption")
        LuksAlgorithms.requireAesXtsPlain64(encryption, "The LUKS2 data encryption")
        val segOffset = seg.getString("offset").toLongOrNull() ?: refuse("LUKS2 segment offset is not a number")
        val sizeField = seg.getString("size")
        val segSize = if (sizeField == "dynamic") null
            else sizeField.toLongOrNull()?.takeIf { it > 0 } ?: refuse("LUKS2 segment size \"$sizeField\" is not valid")
        val sectorSize = seg.optInt("sector_size", 512)
        val ivTweak = seg.optString("iv_tweak", "0").toLongOrNull()?.takeIf { it >= 0 }
            ?: refuse("LUKS2 iv_tweak is not valid")

        val unusable = mutableListOf<String>()
        val kslJson = json.getJSONObject("keyslots")
        val keyslots = mutableListOf<Luks2Keyslot>()
        for (key in kslJson.keys()) {
            val ksl = kslJson.getJSONObject(key)
            try {
                val index = key.toIntOrNull() ?: throw IllegalArgumentException("its id is not a number")
                if (ksl.getString("type") != "luks2") throw IllegalArgumentException("type ${ksl.getString("type")}")
                val priority = ksl.optInt("priority", 1)
                if (priority == 0) { unusable += "keyslot $key: priority 0 (ignored)"; continue }
                val area = ksl.getJSONObject("area")
                if (area.optString("type", "raw") != "raw") throw IllegalArgumentException("area type ${area.optString("type")}")
                val areaEnc = area.getString("encryption")
                LuksAlgorithms.requireAesXtsPlain64(areaEnc, "Keyslot $key's area encryption")
                val af = ksl.getJSONObject("af")
                if (af.optString("type", "luks1") != "luks1") throw IllegalArgumentException("AF type ${af.optString("type")}")
                val afHash = af.getString("hash").also { LuksAlgorithms.digest(it, "keyslot $key AF hash") }
                val afStripes = af.getInt("stripes")
                if (afStripes <= 0) throw IllegalArgumentException("$afStripes AF stripes")
                val keySize = ksl.getInt("key_size")
                val areaOff = area.getString("offset").toLong()
                val areaSize = area.getString("size").toLong()
                val areaKsz = area.getInt("key_size")
                val kdf = ksl.getJSONObject("kdf")
                val salt = Base64.getDecoder().decode(kdf.getString("salt"))
                when (val type = kdf.getString("type")) {
                    "argon2id", "argon2i" -> keyslots += Luks2Keyslot.Argon2id(
                        index = index, keySize = keySize, areaOffsetBytes = areaOff, areaSizeBytes = areaSize,
                        areaEncryption = areaEnc, areaKeySize = areaKsz, afStripes = afStripes, afHash = afHash,
                        timeCost = kdf.getInt("time"), memoryCostKiB = kdf.getInt("memory"),
                        parallelism = kdf.getInt("cpus"), salt = salt, kdfType = type, priority = priority)
                    "pbkdf2" -> keyslots += Luks2Keyslot.Pbkdf2(
                        index = index, keySize = keySize, areaOffsetBytes = areaOff, areaSizeBytes = areaSize,
                        areaEncryption = areaEnc, areaKeySize = areaKsz, afStripes = afStripes, afHash = afHash,
                        hash = kdf.getString("hash").also { LuksAlgorithms.hmac(it, "keyslot $key PBKDF2 hash") },
                        iterations = kdf.getInt("iterations"), salt = salt, priority = priority)
                    else -> throw IllegalArgumentException("KDF $type")
                }
            } catch (e: Exception) {
                unusable += "keyslot $key: ${e.message}"
            }
        }

        val digsJson = json.getJSONObject("digests")
        val digests = mutableListOf<Luks2Digest>()
        for (key in digsJson.keys()) {
            val d = digsJson.getJSONObject(key)
            if (d.optString("type") != "pbkdf2") continue
            fun ids(name: String) = d.optJSONArray(name)?.let { a -> (0 until a.length()).map { a.optString(it) }.toSet() } ?: emptySet()
            val hash = d.getString("hash")
            runCatching { LuksAlgorithms.hmac(hash, "digest $key hash") }.getOrNull() ?: continue
            val dig = Luks2Digest(
                type = "pbkdf2", hash = hash, iterations = d.getInt("iterations"),
                salt = Base64.getDecoder().decode(d.getString("salt")),
                digest = Base64.getDecoder().decode(d.getString("digest")),
                keyslots = ids("keyslots"), segments = ids("segments"))
            if (dig.iterations > 0 && dig.salt.isNotEmpty() && dig.digest.isNotEmpty()) digests += dig
        }

        return LuksHeader.Luks2(
            cipherName = "aes",
            cipherMode = "xts-plain64",
            payloadOffsetSectors = segOffset / 512L,
            keyBytes = keyslots.firstOrNull()?.keySize ?: 64,
            sectorSize = sectorSize,
            ivTweak = ivTweak,
            keyslots = keyslots,
            digests = digests,
            segmentId = segId,
            segmentOffsetBytes = segOffset,
            segmentSizeBytes = segSize,
            hdrSize = hdrSize,
            keyslotsSize = keyslotsSize,
            unusableSlots = unusable,
        )
    }

    // --- helpers ---

    private fun ByteArray.nullTermString(offset: Int, maxLen: Int): String {
        var end = offset
        while (end < offset + maxLen && end < size && this[end] != 0.toByte()) end++
        return String(this, offset, end - offset, Charsets.US_ASCII)
    }
}
