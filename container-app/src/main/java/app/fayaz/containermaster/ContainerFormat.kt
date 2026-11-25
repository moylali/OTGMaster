package app.fayaz.containermaster

/**
 * What a picked file looks like from its first bytes, which decides the form. A VeraCrypt
 * volume has no signature (its header is indistinguishable from random bytes), so anything
 * unrecognised is offered as VeraCrypt (architecture proposal, F8: "Try as VeraCrypt").
 */
enum class ContainerFormat(val supported: Boolean) {
    VERACRYPT(true),
    LUKS(true),
    ZIP(true),
    ISO(true);

    companion object {
        private val LUKS_MAGIC = byteArrayOf(0x4C, 0x55, 0x4B, 0x53, 0xBA.toByte(), 0xBE.toByte())
        private val ZIP_MAGIC = byteArrayOf(0x50, 0x4B, 0x03, 0x04)

        /** [sector16] is the start of the file's 2,048-byte sector 16, where ISO 9660 puts "CD001". */
        fun detect(head: ByteArray, sector16: ByteArray = ByteArray(0)): ContainerFormat = when {
            head.startsWith(LUKS_MAGIC) -> LUKS
            head.startsWith(ZIP_MAGIC) -> ZIP
            app.fayaz.otgmaster.iso.IsoDocumentSource.looksLikeIso(sector16) -> ISO
            else -> VERACRYPT
        }

        private fun ByteArray.startsWith(p: ByteArray) = size >= p.size && p.indices.all { this[it] == p[it] }
    }
}
