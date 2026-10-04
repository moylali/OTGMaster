package app.fayaz.otgmaster.block

import java.io.Closeable

interface RawBlockDevice : Closeable {
    val blockSize: Int
    val blockCount: Long

    fun readBlocks(startBlock: Long, blockCount: Int): ByteArray

    fun writeBlocks(startBlock: Long, data: ByteArray)
}

/**
 * Throws unless [blocks] blocks from [startBlock] lie inside this device.
 *
 * Every device that maps its blocks onto a larger one (a decrypted volume onto the
 * USB stick it sits on) must check before it translates: the layer below accepts
 * any position on the whole stick. A FAT32 allocator that ran past the end of its
 * volume (libaums V14) wrote straight through the VeraCrypt device into the next
 * partition's header on device-matrix Drive 2 (OnePlus 7, D2VCFAT32 -> D2VCEXFAT).
 */
fun RawBlockDevice.requireInRange(startBlock: Long, blocks: Long, op: String) {
    require(startBlock >= 0 && blocks >= 0 && startBlock + blocks <= blockCount) {
        "$op of $blocks blocks at $startBlock exceeds device ($blockCount blocks)"
    }
}
