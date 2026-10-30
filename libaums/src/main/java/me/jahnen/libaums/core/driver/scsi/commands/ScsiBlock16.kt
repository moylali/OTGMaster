package me.jahnen.libaums.core.driver.scsi.commands

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * LOCAL PATCH (docs/VENDOR_FIXES.md V23): 16-byte SCSI commands for drives over 2 TiB.
 *
 * READ(10) and WRITE(10) carry a 32-bit block address and READ CAPACITY(10) a 32-bit
 * last address, so with 512-byte sectors nothing at or past 2 TiB can be addressed.
 * A drive that large answers READ CAPACITY(10) with 0xFFFFFFFF, which upstream turns
 * into "Device requires SCSI READ CAPACITY(16)" and the whole drive fails to open
 * (issue #87: a 3 TB disk in a UGREEN enclosure). SBC-3 defines the 16-byte forms:
 * READ CAPACITY(16) is SERVICE ACTION IN(16) (0x9E) with service action 0x10, and
 * READ(16) / WRITE(16) (0x88 / 0x8A) take a 64-bit address and a 32-bit length.
 */
class ScsiReadCapacity16(lun: Byte) : CommandBlockWrapper(RESPONSE_LENGTH, Direction.IN, lun, LENGTH) {

    override fun serialize(buffer: ByteBuffer) {
        super.serialize(buffer)
        buffer.apply {
            order(ByteOrder.BIG_ENDIAN)
            put(OPCODE)
            put(SERVICE_ACTION)
            putLong(0L)
            putInt(RESPONSE_LENGTH)
            put(0.toByte())
            put(0.toByte())
        }
    }

    override fun toString() = "ScsiReadCapacity16"

    companion object {
        const val RESPONSE_LENGTH = 32
        private const val LENGTH: Byte = 16
        private const val OPCODE: Byte = 0x9E.toByte()
        private const val SERVICE_ACTION: Byte = 0x10
    }
}

/** See [ScsiReadCapacity16]. The last block's address and the block length. */
class ScsiReadCapacity16Response private constructor(val lastBlockAddress: Long, val blockLength: Int) {
    val blockCount: Long get() = lastBlockAddress + 1

    companion object {
        fun read(buffer: ByteBuffer): ScsiReadCapacity16Response {
            buffer.order(ByteOrder.BIG_ENDIAN)
            return ScsiReadCapacity16Response(buffer.long, buffer.int)
        }
    }
}

/** READ(16) or WRITE(16); see [ScsiReadCapacity16]. */
class ScsiReadWrite16(private val write: Boolean, lun: Byte) :
    CommandBlockWrapper(0, if (write) Direction.OUT else Direction.IN, lun, LENGTH) {

    private var blockAddress = 0L
    private var transferBlocks = 0

    fun init(blockAddress: Long, transferBytes: Int, blockSize: Int) {
        require(transferBytes % blockSize == 0) { "transfer bytes is not a multiple of block size" }
        dCbwDataTransferLength = transferBytes
        this.blockAddress = blockAddress
        this.transferBlocks = transferBytes / blockSize
    }

    override fun serialize(buffer: ByteBuffer) {
        super.serialize(buffer)
        buffer.apply {
            order(ByteOrder.BIG_ENDIAN)
            put(if (write) WRITE_OPCODE else READ_OPCODE)
            put(0.toByte())
            putLong(blockAddress)
            putInt(transferBlocks)
            put(0.toByte())
            put(0.toByte())
        }
    }

    override fun toString() =
        "${if (write) "ScsiWrite16" else "ScsiRead16"} [blockAddress=$blockAddress, transferBlocks=$transferBlocks]"

    companion object {
        private const val LENGTH: Byte = 16
        private const val READ_OPCODE: Byte = 0x88.toByte()
        private const val WRITE_OPCODE: Byte = 0x8A.toByte()
    }
}
