package app.fayaz.otgmaster.block

import java.io.File
import java.io.RandomAccessFile

class FileBlockDevice(file: File) : RawBlockDevice {
    private val randomAccessFile = RandomAccessFile(file, "r")
    
    override val blockSize: Int = 512
    override val blockCount: Long = run {
        var count = randomAccessFile.length() / blockSize
        if (count == 0L) {
            try {
                val sysfsSize = File("/sys/class/block/${file.name}/size").readText().trim().toLong()
                count = sysfsSize
            } catch (e: Exception) {
                count = 20480L // 10MB default for test images
            }
        }
        count
    }

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
