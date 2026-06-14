package app.fayaz.otgmaster.block

import java.io.Closeable

interface RawBlockDevice : Closeable {
    val blockSize: Int
    val blockCount: Long

    fun readBlocks(startBlock: Long, blockCount: Int): ByteArray

    fun writeBlocks(startBlock: Long, data: ByteArray) {
        throw UnsupportedOperationException("Write support is intentionally disabled for the first prototype.")
    }
}
