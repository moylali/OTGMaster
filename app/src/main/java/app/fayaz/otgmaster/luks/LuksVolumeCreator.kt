package app.fayaz.otgmaster.luks

import app.fayaz.otgmaster.block.RawBlockDevice
import app.fayaz.otgmaster.veracrypt.NativeDecryptedBlockDevice
import app.fayaz.otgmaster.veracrypt.SingleCipher
import app.fayaz.otgmaster.veracrypt.VeraCryptNative
import org.json.JSONArray
import org.json.JSONObject
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.UUID

/**
 * Creates a LUKS2 volume the way `cryptsetup luksFormat --type luks2` lays one out, so
 * Linux opens it with `cryptsetup open` (and GNOME/KDE with a password prompt):
 *
 * - two 16 KiB headers (4 KiB binary + 12 KiB JSON), at 0 and 16 KiB, each with its own
 *   salt and a SHA-256 checksum over the whole 16 KiB;
 * - the keyslots area from 32 KiB, keyslot 0 at its start: the master key AF-split into
 *   4000 SHA-256 stripes and encrypted with aes-xts-plain64 under a key from Argon2id;
 * - a PBKDF2-SHA256 digest of the master key, which is how the right password is recognised;
 * - the data segment from 16 MiB to the end, aes-xts-plain64 with a 512-bit key (AES-256),
 *   512-byte sectors, IV = sector number from the start of the segment.
 *
 * Argon2id runs at 4 passes over 256 MiB with one lane: about a second on a phone and
 * within reach of a 2 GB one. cryptsetup would benchmark for two seconds with four lanes
 * and up to 1 GiB, which a low-end phone could not unlock. The cost is in the header, so
 * any LUKS2 implementation unlocks it.
 */
object LuksVolumeCreator {

    const val HEADER_SIZE = 16384L
    private const val BINARY_SIZE = 4096
    private const val KEYSLOTS_OFFSET = 2 * HEADER_SIZE
    const val DATA_OFFSET = 16L shl 20
    private const val KEY_BYTES = 64
    private const val STRIPES = 4000
    const val ARGON2_TIME = 4
    const val ARGON2_MEMORY_KIB = 256 * 1024
    const val ARGON2_LANES = 1
    private const val DIGEST_ITERATIONS = 100_000

    private val MAGIC = byteArrayOf(0x4C, 0x55, 0x4B, 0x53, 0xBA.toByte(), 0xBE.toByte())

    /** Bytes of the data segment on a partition of [partitionBytes]. */
    fun dataBytes(partitionBytes: Long): Long = partitionBytes - DATA_OFFSET

    /**
     * Writes a LUKS2 header with one keyslot for [password] over the start of [partition]
     * (512-byte sectors), and returns the decrypted data segment to put a filesystem on.
     * [password] is not wiped here; the caller owns it.
     */
    fun create(partition: RawBlockDevice, password: ByteArray, label: String = "",
               random: SecureRandom = SecureRandom(), progress: (String) -> Unit = {}): RawBlockDevice {
        require(partition.blockSize == 512) { "LUKS2 is created on 512-byte sectors only" }
        val partitionBytes = partition.blockCount * 512
        require(dataBytes(partitionBytes) >= 1L shl 20) { "The partition is too small for a LUKS2 volume." }

        val masterKey = ByteArray(KEY_BYTES).also { random.nextBytes(it) }
        try {
            // Keyslot 0: AF-split master key, encrypted under the Argon2id key.
            progress("Deriving the key")
            val kdfSalt = ByteArray(32).also { random.nextBytes(it) }
            val slotKey = VeraCryptNative.argon2id(password, kdfSalt, ARGON2_TIME, ARGON2_MEMORY_KIB,
                ARGON2_LANES, KEY_BYTES) ?: throw IllegalStateException("Argon2id failed")
            val afBytes = STRIPES * KEY_BYTES
            val areaSize = (afBytes + 4095) / 4096 * 4096L
            val area = ByteArray(areaSize.toInt()).also { random.nextBytes(it) }
            afSplit(masterKey, STRIPES, random).copyInto(area)
            val rc = VeraCryptNative.cryptSectorsInPlace(intArrayOf(SingleCipher.AES.nativeId), 1,
                slotKey, 0L, area, 0, area.size)
            slotKey.fill(0)
            check(rc == 0) { "keyslot encryption failed ($rc)" }

            progress("Writing the header")
            val digestSalt = ByteArray(32).also { random.nextBytes(it) }
            val digest = LuksUnlocker().pbkdf2("HmacSHA256", masterKey, digestSalt, DIGEST_ITERATIONS, 32)
            val json = json(kdfSalt, areaSize, digestSalt, digest)
            val uuid = UUID.randomUUID().toString()
            val primary = header(json, uuid, label, hdrOffset = 0, random)
            val secondary = header(json, uuid, label, hdrOffset = HEADER_SIZE, random)

            // Header, then the keyslots area: the keyslot, and zeros over whatever was in the
            // rest of it (an old filesystem's metadata, say) up to the data segment.
            partition.writeBlocks(0, primary + secondary)
            partition.writeBlocks(KEYSLOTS_OFFSET / 512, area)
            val zeros = ByteArray(1 shl 20)
            var off = KEYSLOTS_OFFSET + areaSize
            while (off < DATA_OFFSET) {
                val n = minOf(zeros.size.toLong(), DATA_OFFSET - off).toInt()
                partition.writeBlocks(off / 512, if (n == zeros.size) zeros else ByteArray(n))
                off += n
            }

            return NativeDecryptedBlockDevice(
                encryptedDevice = partition,
                masterKey = masterKey.copyOf(),
                volumeDataOffset = DATA_OFFSET / 512,
                decryptedBlockCount = dataBytes(partitionBytes) / 512,
                cipherNativeIds = intArrayOf(SingleCipher.AES.nativeId),
                tweakDataOffset = 0L,
            )
        } finally {
            masterKey.fill(0)
        }
    }

    private fun json(kdfSalt: ByteArray, areaSize: Long, digestSalt: ByteArray, digest: ByteArray): ByteArray {
        val b64 = Base64.getEncoder()
        val keyslot = JSONObject()
            .put("type", "luks2")
            .put("key_size", KEY_BYTES)
            .put("af", JSONObject().put("type", "luks1").put("stripes", STRIPES).put("hash", "sha256"))
            .put("area", JSONObject().put("type", "raw").put("offset", KEYSLOTS_OFFSET.toString())
                .put("size", areaSize.toString()).put("encryption", "aes-xts-plain64").put("key_size", KEY_BYTES))
            .put("kdf", JSONObject().put("type", "argon2id").put("time", ARGON2_TIME)
                .put("memory", ARGON2_MEMORY_KIB).put("cpus", ARGON2_LANES).put("salt", b64.encodeToString(kdfSalt)))
        val segment = JSONObject()
            .put("type", "crypt").put("offset", DATA_OFFSET.toString()).put("size", "dynamic")
            .put("iv_tweak", "0").put("encryption", "aes-xts-plain64").put("sector_size", 512)
        val digestObj = JSONObject()
            .put("type", "pbkdf2").put("keyslots", JSONArray().put("0")).put("segments", JSONArray().put("0"))
            .put("hash", "sha256").put("iterations", DIGEST_ITERATIONS)
            .put("salt", b64.encodeToString(digestSalt)).put("digest", b64.encodeToString(digest))
        val root = JSONObject()
            .put("keyslots", JSONObject().put("0", keyslot))
            .put("tokens", JSONObject())
            .put("segments", JSONObject().put("0", segment))
            .put("digests", JSONObject().put("0", digestObj))
            .put("config", JSONObject().put("json_size", (HEADER_SIZE - BINARY_SIZE).toString())
                .put("keyslots_size", (DATA_OFFSET - KEYSLOTS_OFFSET).toString()))
        // org.json escapes '/' in base64 as "\/"; JSON allows it and cryptsetup's parser
        // (json-c) reads it, but plain '/' is what cryptsetup itself writes.
        val text = root.toString().replace("\\/", "/")
        val bytes = text.toByteArray(Charsets.UTF_8)
        require(bytes.size < HEADER_SIZE - BINARY_SIZE) { "LUKS2 JSON too large" }
        return bytes
    }

    /** One 16 KiB header copy: binary header, JSON area, SHA-256 over both. */
    private fun header(json: ByteArray, uuid: String, label: String, hdrOffset: Long, random: SecureRandom): ByteArray {
        val h = ByteArray(HEADER_SIZE.toInt())
        val b = ByteBuffer.wrap(h).order(ByteOrder.BIG_ENDIAN)
        MAGIC.copyInto(h, 0)
        b.putShort(6, 2)
        b.putLong(8, HEADER_SIZE)
        b.putLong(16, 1)                                       // seqid
        label.toByteArray(Charsets.UTF_8).let { it.copyInto(h, 24, 0, minOf(it.size, 47)) }
        "sha256".toByteArray().copyInto(h, 72)
        ByteArray(64).also { random.nextBytes(it) }.copyInto(h, 104)   // salt
        uuid.toByteArray().copyInto(h, 168)
        b.putLong(256, hdrOffset)
        json.copyInto(h, BINARY_SIZE)
        val csum = MessageDigest.getInstance("SHA-256").digest(h)   // csum field still zero
        csum.copyInto(h, 448)
        return h
    }

    /** cryptsetup's AF_split: the inverse of [LuksUnlocker.afMerge]. */
    internal fun afSplit(key: ByteArray, stripes: Int, random: SecureRandom): ByteArray {
        val n = key.size
        val out = ByteArray(n * stripes)
        val buf = ByteArray(n)
        for (i in 0 until stripes - 1) {
            val stripe = ByteArray(n).also { random.nextBytes(it) }
            stripe.copyInto(out, i * n)
            for (j in 0 until n) buf[j] = (buf[j].toInt() xor stripe[j].toInt()).toByte()
            diffuse(buf)
        }
        for (j in 0 until n) out[(stripes - 1) * n + j] = (buf[j].toInt() xor key[j].toInt()).toByte()
        return out
    }

    /** SHA-256 diffusion, as LuksUnlocker.hashDiffuse with "sha256". */
    private fun diffuse(buf: ByteArray) {
        val md = MessageDigest.getInstance("SHA-256")
        val result = ByteArray(buf.size)
        var pos = 0
        var chunk = 0
        while (pos < buf.size) {
            val count = minOf(32, buf.size - pos)
            md.reset()
            md.update(byteArrayOf((chunk shr 24).toByte(), (chunk shr 16).toByte(), (chunk shr 8).toByte(), chunk.toByte()))
            md.update(buf, pos, count)
            md.digest().copyInto(result, pos, 0, count)
            pos += 32
            chunk++
        }
        result.copyInto(buf)
    }
}
