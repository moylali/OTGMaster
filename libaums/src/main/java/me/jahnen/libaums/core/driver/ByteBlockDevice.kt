package me.jahnen.libaums.core.driver

import java.io.IOException
import java.nio.ByteBuffer

/**
 * Simple class which wraps around an existing [BlockDeviceDriver] to enable byte addressing
 * of content. Uses byte offsets instead of device oddsets in [ByteBlockDevice.write]
 * and [ByteBlockDevice.read]. Uses [BlockDeviceDriver.getBlockSize]
 * to calculate device offsets.
 */
open class ByteBlockDevice @JvmOverloads constructor(private val targetBlockDevice: BlockDeviceDriver, private val logicalOffsetToAdd: Long = 0) : BlockDeviceDriver {
    override val blockSize: Int
        get() = targetBlockDevice.blockSize

    override val blocks: Long
        get() = targetBlockDevice.blocks
    
    @Throws(IOException::class)
    override fun init() {
        targetBlockDevice.init()
    }

    @Throws(IOException::class)
    override fun read(byteOffset: Long, dest: ByteBuffer) {
        var devOffset = byteOffset / blockSize + logicalOffsetToAdd
        // TODO try to make this more efficient by for example making tmp buffer
        // global
        if (byteOffset % blockSize != 0L) {
            //Log.w(TAG, "device offset " + offset + " not a multiple of block size");
            val tmp = ByteBuffer.allocate(blockSize)

            targetBlockDevice.read(devOffset, tmp)
            tmp.clear()
            tmp.position((byteOffset % blockSize).toInt())
            val limit = Math.min(dest.remaining(), tmp.remaining())
            tmp.limit(tmp.position() + limit)
            dest.put(tmp)

            devOffset++
        }

        if (dest.remaining() > 0) {
            val buffer: ByteBuffer
            if (dest.remaining() % blockSize != 0) {
                //Log.w(TAG, "we have to round up size to next block sector");
                val rounded = blockSize - dest.remaining() % blockSize + dest.remaining()
                buffer = ByteBuffer.allocate(rounded)
                buffer.limit(rounded)
            } else {
                buffer = dest
            }

            targetBlockDevice.read(devOffset, buffer)

            if (dest.remaining() % blockSize != 0) {
                // LOCAL PATCH (docs/VENDOR_FIXES.md V5): no array() on either side —
                // it throws on a direct ByteBuffer.
                buffer.clear()
                buffer.limit(dest.remaining())
                dest.put(buffer)
            } else {
                dest.position(dest.limit())
            }
        }
    }

    @Throws(IOException::class)
    override fun write(byteOffset: Long, src: ByteBuffer) {
        var devOffset = byteOffset / blockSize + logicalOffsetToAdd
        // TODO try to make this more efficient by for example making tmp buffer
        // global
        if (byteOffset % blockSize != 0L) {
            //Log.w(TAG, "device offset " + offset + " not a multiple of block size");
            val tmp = ByteBuffer.allocate(blockSize)

            targetBlockDevice.read(devOffset, tmp)
            tmp.clear()
            tmp.position((byteOffset % blockSize).toInt())
            val remaining = Math.min(tmp.remaining(), src.remaining())
            // LOCAL PATCH (docs/VENDOR_FIXES.md V5): array() throws
            // UnsupportedOperationException on a direct ByteBuffer, so any caller
            // using allocateDirect or NDK shared memory crashed here. Copy through
            // the buffer API instead, which works for both kinds.
            val slice = src.slice()
            slice.limit(remaining)
            tmp.put(slice)
            src.position(src.position() + remaining)
            tmp.clear()
            targetBlockDevice.write(devOffset, tmp)

            devOffset++
        }

        if (src.remaining() > 0) {
            // TODO try to make this more efficient by for example only allocating
            // blockSize and making it global
            val buffer: ByteBuffer
            if (src.remaining() % blockSize != 0) {
                //Log.w(TAG, "we have to round up size to next block sector");
                val rounded = blockSize - src.remaining() % blockSize + src.remaining()
                buffer = ByteBuffer.allocate(rounded)
                buffer.limit(rounded)

                // LOCAL PATCH (docs/VENDOR_FIXES.md V1): read the sectors before
                // overlaying, rather than zero-padding the tail.
                //
                // ByteBuffer.allocate() is zero-filled, so writing it back destroyed
                // whatever the disk already held past the end of src -- up to
                // blockSize-1 bytes of another part of the file, or of a neighbouring
                // file in the same cluster. Any unaligned write corrupted data it was
                // never asked to touch. Upstream's TODO (kept below in spirit) asked
                // exactly this question; the answer is yes.
                //
                // The leading-partial branch above already does this; only the tail
                // was missing it.
                val remaining = src.remaining()
                targetBlockDevice.read(devOffset, buffer)
                buffer.clear()
                // See V5 above: no array() on either side.
                val slice = src.slice()
                slice.limit(remaining)
                buffer.put(slice)
                buffer.clear()

                src.position(src.limit())
            } else {
                buffer = src
            }

            targetBlockDevice.write(devOffset, buffer)
        }
    }

    companion object {

        private val TAG = ByteBlockDevice::class.java.simpleName
    }
} 