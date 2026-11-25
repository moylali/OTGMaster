package app.fayaz.containermaster

/**
 * What a picked file looks like from its first bytes, which decides the form. A VeraCrypt
 * volume has no signature (its header is indistinguishable from random bytes), so anything
 * unrecognised is offered as VeraCrypt (architecture proposal, F8: "Try as VeraCrypt").
 */
enum class ContainerFormat(val supported: Boolean) {
    VERACRYPT(true),
    LUKS(false),
    ZIP(false);

    companion object {
        private val LUKS_MAGIC = byteArrayOf(0x4C, 0x55, 0x4B, 0x53, 0xBA.toByte(), 0xBE.toByte())
        private val ZIP_MAGIC = byteArrayOf(0x50, 0x4B, 0x03, 0x04)

        fun detect(head: ByteArray): ContainerFormat = when {
            head.startsWith(LUKS_MAGIC) -> LUKS
            head.startsWith(ZIP_MAGIC) -> ZIP
            else -> VERACRYPT
        }

        private fun ByteArray.startsWith(p: ByteArray) = size >= p.size && p.indices.all { this[it] == p[it] }
    }
}
