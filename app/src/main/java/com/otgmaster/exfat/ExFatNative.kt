package com.otgmaster.exfat

import com.otgmaster.block.RawBlockDevice
import java.nio.ByteBuffer

object ExFatNative {

    init {
        System.loadLibrary("exfat")
    }

    /**
     * Mounts the exFAT filesystem.
     * @param blockDevice The Kotlin BlockDevice to read from.
     * @return A pointer to the native `struct exfat` or 0 if failed.
     */
    external fun mount(blockDevice: RawBlockDevice): Long

    /**
     * Unmounts the exFAT filesystem and frees memory.
     */
    external fun unmount(exfatPtr: Long)

    @JvmStatic
    external fun getRootNode(exfatPtr: Long): ExFatNode?

    @JvmStatic
    external fun readDir(exfatPtr: Long, nodePtr: Long): Array<ExFatNode>

    @JvmStatic
    external fun readFile(exfatPtr: Long, nodePtr: Long, offset: Long, size: Int, buffer: ByteArray): Int

    // These functions will be called FROM C via JNI to read/write the block device.
    
    @JvmStatic
    fun pread(blockDevice: RawBlockDevice, offset: Long, size: Int, destBuffer: ByteArray): Int {
        return try {
            val blockSize = blockDevice.blockSize
            val startBlock = offset / blockSize
            val endBlock = (offset + size - 1) / blockSize
            val numBlocks = (endBlock - startBlock + 1).toInt()
            
            // Read the required blocks
            val blocksData = blockDevice.readBlocks(startBlock, numBlocks)
            
            // Copy the requested byte range into destBuffer
            val blockOffset = (offset % blockSize).toInt()
            System.arraycopy(blocksData, blockOffset, destBuffer, 0, size)
            size
        } catch (e: Exception) {
            e.printStackTrace()
            -1
        }
    }

    @JvmStatic
    fun pwrite(blockDevice: RawBlockDevice, offset: Long, size: Int, srcBuffer: ByteArray): Int {
        // Phase 3 asks for Write access, but RawBlockDevice currently throws UnsupportedOperationException.
        // We will implement writeBlocks in RawBlockDevice later.
        return try {
            val blockSize = blockDevice.blockSize
            val startBlock = offset / blockSize
            val endBlock = (offset + size - 1) / blockSize
            val numBlocks = (endBlock - startBlock + 1).toInt()
            
            val blockOffset = (offset % blockSize).toInt()
            
            if (blockOffset == 0 && size % blockSize == 0) {
                // Perfectly block aligned write
                blockDevice.writeBlocks(startBlock, srcBuffer)
            } else {
                // Read-modify-write for unaligned writes
                val blocksData = blockDevice.readBlocks(startBlock, numBlocks)
                System.arraycopy(srcBuffer, 0, blocksData, blockOffset, size)
                blockDevice.writeBlocks(startBlock, blocksData)
            }
            size
        } catch (e: Exception) {
            e.printStackTrace()
            -1
        }
    }
    
    @JvmStatic
    fun getDeviceSize(blockDevice: RawBlockDevice): Long {
        return blockDevice.blockCount * blockDevice.blockSize
    }
}
