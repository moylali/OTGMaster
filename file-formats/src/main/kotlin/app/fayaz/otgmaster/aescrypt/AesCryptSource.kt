package app.fayaz.otgmaster.aescrypt

import app.fayaz.otgmaster.core.DocumentHandle
import app.fayaz.otgmaster.core.DocumentNode
import app.fayaz.otgmaster.core.DocumentSource
import java.io.EOFException
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * An AES Crypt file (format version 2) as a read-only DocumentSource holding one file
 * (architecture proposal, Milestone 6, F10). The layout is AES Crypt's: "AES", version 2, a
 * reserved byte, extensions, IV1, the session IV and key encrypted under a key stretched from
 * the password (SHA-256, 8192 rounds over IV1 and the UTF-16LE password), its HMAC-SHA256, the
 * AES-256-CBC ciphertext, the plaintext length modulo 16, and the ciphertext's HMAC-SHA256.
 *
 * Verified before it is served: [open] checks the header's HMAC (a wrong password fails there,
 * with no decryption tried) and then the HMAC of the whole ciphertext, so a damaged or altered
 * file never serves a byte. Reads are random: CBC decrypts any block from the ciphertext block
 * before it. Version 2 does not authenticate the length byte, so the last block's length (up
 * to 15 bytes) is the one unauthenticated thing; that is the format's, as pyAesCrypt notes.
 * The file is assumed not to change while open; Container Master opens it read-only.
 * Version 3 is refused for now.
 */
class AesCryptSource private constructor(
    private val channel: FileChannel,
    private val cipherStart: Long,
    private val cipherLength: Long,
    private val plainLength: Long,
    private val iv0: ByteArray,
    private val key: ByteArray,
    private val fileName: String,
) : DocumentSource {

    class WrongPasswordException : Exception("Wrong password, or the file is damaged")
    class AesCryptFormatException(message: String) : IOException(message)

    private class Node(override val name: String, override val isDirectory: Boolean, override val isRoot: Boolean, override val size: Long) : DocumentNode {
        override val lastModified: Long get() = 0
    }

    private val root = Node("", true, true, 0)
    private val file = Node(fileName, false, false, plainLength)

    override val readOnlyReason: String get() = READ_ONLY
    override fun root(): DocumentNode = root
    override fun child(dir: DocumentNode, name: String): DocumentNode? = if (dir === root && name == fileName) file else null
    override fun children(dir: DocumentNode): List<DocumentNode> = if (dir === root) listOf(file) else emptyList()

    override fun open(node: DocumentNode): DocumentHandle {
        if (node !== file) throw IOException("A folder cannot be opened")
        return object : DocumentHandle {
            private val aes = Cipher.getInstance("AES/CBC/NoPadding")
            override fun size() = plainLength
            override fun read(offset: Long, buffer: ByteArray, length: Int): Int {
                if (offset >= plainLength) return 0
                val want = minOf(length.toLong(), plainLength - offset).toInt()
                val first = offset / 16
                val last = (offset + want - 1) / 16
                val iv = if (first == 0L) iv0 else readFully(channel, cipherStart + (first - 1) * 16, 16)
                val ct = readFully(channel, cipherStart + first * 16, ((last - first + 1) * 16).toInt())
                aes.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
                val pt = aes.doFinal(ct)
                System.arraycopy(pt, (offset - first * 16).toInt(), buffer, 0, want)
                pt.fill(0)
                return want
            }
            override fun write(offset: Long, buffer: ByteArray, length: Int) = refuse()
            override fun truncate() = refuse()
            override fun flush() {}
            override fun close() {}
        }
    }

    private fun refuse(): Nothing = throw IOException(READ_ONLY)
    override fun createFile(dir: DocumentNode, name: String): DocumentNode = refuse()
    override fun createDirectory(dir: DocumentNode, name: String): DocumentNode = refuse()
    override fun rename(node: DocumentNode, name: String) = refuse()
    override fun delete(node: DocumentNode) = refuse()

    /** Forgets the session key. */
    fun close() = key.fill(0)

    companion object {
        const val READ_ONLY = "AES Crypt files open read-only"

        fun looksLikeAesCrypt(head: ByteArray) = head.size >= 4 && head[0] == 'A'.code.toByte() &&
            head[1] == 'E'.code.toByte() && head[2] == 'S'.code.toByte() && (head[3].toInt() in 1..3)

        private fun readFully(channel: FileChannel, pos: Long, len: Int): ByteArray {
            val b = ByteBuffer.allocate(len); var p = pos
            while (b.hasRemaining()) { val n = channel.read(b, p); if (n < 0) throw EOFException("the file ended at byte $p"); p += n }
            return b.array()
        }

        /**
         * Opens [channel] with [password], verifying the header and the whole ciphertext. [name] is
         * the container's file name; the file inside is named after it without its ".aes".
         */
        fun open(channel: FileChannel, length: Long, password: CharArray, name: String): AesCryptSource {
            if (length < 5 + 2 + 16 + 48 + 32 + 1 + 32) throw AesCryptFormatException("too short to be an AES Crypt file")
            val head = readFully(channel, 0, 5)
            if (!(head[0] == 'A'.code.toByte() && head[1] == 'E'.code.toByte() && head[2] == 'S'.code.toByte()))
                throw AesCryptFormatException("not an AES Crypt file")
            when (head[3].toInt()) {
                2 -> {}
                3 -> throw AesCryptFormatException("AES Crypt version 3 files are not supported yet; version 2 is")
                else -> throw AesCryptFormatException("AES Crypt version ${head[3]} is not supported")
            }
            var p = 5L
            var extensions = 0
            while (true) {
                val lenBytes = readFully(channel, p, 2); p += 2
                val len = ((lenBytes[0].toInt() and 0xFF) shl 8) or (lenBytes[1].toInt() and 0xFF)
                if (len == 0) break
                if (++extensions > 256) throw AesCryptFormatException("too many header extensions")
                p += len
                if (p > length) throw AesCryptFormatException("the header runs past the end")
            }
            val iv1 = readFully(channel, p, 16); p += 16
            val encrypted = readFully(channel, p, 48); p += 48
            val hmac1 = readFully(channel, p, 32); p += 32

            val pw = String(password).toByteArray(Charsets.UTF_16LE)
            var digest = iv1 + ByteArray(16)
            val sha = MessageDigest.getInstance("SHA-256")
            repeat(8192) { sha.update(digest); sha.update(pw); digest = sha.digest() }
            pw.fill(0)
            try {
                val mac = Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(digest, "HmacSHA256")) }
                if (!MessageDigest.isEqual(mac.doFinal(encrypted), hmac1)) throw WrongPasswordException()
                val ivKey = Cipher.getInstance("AES/CBC/NoPadding").run {
                    init(Cipher.DECRYPT_MODE, SecretKeySpec(digest, "AES"), IvParameterSpec(iv1)); doFinal(encrypted)
                }
                val iv0 = ivKey.copyOfRange(0, 16); val key = ivKey.copyOfRange(16, 48); ivKey.fill(0)

                val cipherStart = p
                val cipherLength = length - 33 - cipherStart
                if (cipherLength < 0 || cipherLength % 16 != 0L) throw AesCryptFormatException("the ciphertext is not a whole number of blocks")
                val tail = readFully(channel, length - 33, 33)
                val fs16 = tail[0].toInt() and 0xFF
                if (fs16 > 15) throw AesCryptFormatException("a damaged length byte")
                // The whole ciphertext's HMAC, before anything is served.
                val mac0 = Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(key, "HmacSHA256")) }
                val buf = ByteBuffer.allocate(1 shl 20)
                var q = cipherStart
                while (q < cipherStart + cipherLength) {
                    buf.clear(); buf.limit(minOf(buf.capacity().toLong(), cipherStart + cipherLength - q).toInt())
                    val n = channel.read(buf, q); if (n < 0) throw EOFException("the file ended at byte $q")
                    mac0.update(buf.array(), 0, n); q += n
                }
                if (!MessageDigest.isEqual(mac0.doFinal(), tail.copyOfRange(1, 33)))
                    throw AesCryptFormatException("the file is damaged or was altered: its HMAC does not match")
                val removed = if (cipherLength == 0L) 0 else (16 - fs16) % 16
                val plain = cipherLength - removed
                val inner = name.removeSuffix(".aes").removeSuffix(".AES").ifEmpty { "file" }
                return AesCryptSource(channel, cipherStart, cipherLength, plain, iv0, key, inner)
            } finally {
                digest.fill(0)
            }
        }
    }
}
