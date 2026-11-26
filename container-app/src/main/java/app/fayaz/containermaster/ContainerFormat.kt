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
    ISO(true),
    AESCRYPT(true),
    TAR(true),
    TARGZ(true),
    SEVENZIP(true),
    /** A macOS disk image (UDIF), told from the "koly" trailer at its end. */
    DMG(true),
    /** A restic repository: a folder, picked with the folder picker, not told from a file's bytes. */
    RESTIC(true);

    companion object {
        private val LUKS_MAGIC = byteArrayOf(0x4C, 0x55, 0x4B, 0x53, 0xBA.toByte(), 0xBE.toByte())
        private val ZIP_MAGIC = byteArrayOf(0x50, 0x4B, 0x03, 0x04)
        private val SEVENZIP_MAGIC = byteArrayOf(0x37, 0x7A, 0xBC.toByte(), 0xAF.toByte(), 0x27, 0x1C)

        /** [sector16] is the start of the file's 2,048-byte sector 16, where ISO 9660 puts "CD001". */
        fun detect(head: ByteArray, sector16: ByteArray = ByteArray(0)): ContainerFormat = when {
            head.startsWith(LUKS_MAGIC) -> LUKS
            head.startsWith(ZIP_MAGIC) -> ZIP
            head.startsWith(SEVENZIP_MAGIC) -> SEVENZIP
            app.fayaz.otgmaster.tar.TarArchive.looksLikeTar(head) -> TAR
            app.fayaz.otgmaster.tar.TarArchive.looksLikeTarGz(head) -> TARGZ
            app.fayaz.otgmaster.iso.IsoDocumentSource.looksLikeIso(sector16) -> ISO
            app.fayaz.otgmaster.aescrypt.AesCryptSource.looksLikeAesCrypt(head) -> AESCRYPT
            else -> VERACRYPT
        }

        private fun ByteArray.startsWith(p: ByteArray) = size >= p.size && p.indices.all { this[it] == p[it] }
    }
}
