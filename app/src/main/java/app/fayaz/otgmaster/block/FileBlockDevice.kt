package app.fayaz.otgmaster.block

import java.io.File
import java.io.RandomAccessFile

class FileBlockDevice(file: File) : RawBlockDevice {
    private val randomAccessFile = RandomAccessFile(file, "r")
    
    override val blockSize: Int = 512
    override val blockCount: Long = randomAccessFile.length() / blockSize

    override fun readBlocks(startBlock: Long, blockCount: Int): ByteArray {
        val buffer = ByteArray(blockCount * blockSize)
        randomAccessFile.seek(startBlock * blockSize)
        randomAccessFile.readFully(buffer)
        return buffer
    }

    override fun writeBlocks(startBlock: Long, data: ByteArray) {
        throw UnsupportedOperationException("Write support is intentionally disabled.")
    }

    override fun close() {
        randomAccessFile.close()
    }
}
