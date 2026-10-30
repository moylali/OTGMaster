/*
 * (C) Copyright 2014 mjahnen <github@mgns.tech>
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 * 
 */

package me.jahnen.libaums.core.driver.scsi

import android.util.Log
import me.jahnen.libaums.core.driver.BlockDeviceDriver
import me.jahnen.libaums.core.driver.scsi.commands.CommandBlockWrapper
import me.jahnen.libaums.core.driver.scsi.commands.CommandBlockWrapper.Direction
import me.jahnen.libaums.core.driver.scsi.commands.CommandStatusWrapper
import me.jahnen.libaums.core.driver.scsi.commands.ScsiInquiry
import me.jahnen.libaums.core.driver.scsi.commands.ScsiInquiryResponse
import me.jahnen.libaums.core.driver.scsi.commands.ScsiRead10
import me.jahnen.libaums.core.driver.scsi.commands.ScsiReadCapacity
import me.jahnen.libaums.core.driver.scsi.commands.ScsiReadCapacity16
import me.jahnen.libaums.core.driver.scsi.commands.ScsiReadCapacity16Response
import me.jahnen.libaums.core.driver.scsi.commands.ScsiReadWrite16
import me.jahnen.libaums.core.driver.scsi.commands.ScsiReadCapacityResponse
import me.jahnen.libaums.core.driver.scsi.commands.ScsiTestUnitReady
import me.jahnen.libaums.core.driver.scsi.commands.ScsiWrite10
import me.jahnen.libaums.core.driver.scsi.commands.sense.InitRequired
import me.jahnen.libaums.core.driver.scsi.commands.sense.NotReadyTryAgain
import me.jahnen.libaums.core.driver.scsi.commands.sense.UnitAttention
import me.jahnen.libaums.core.driver.scsi.commands.sense.ScsiRequestSense
import me.jahnen.libaums.core.driver.scsi.commands.sense.ScsiRequestSenseResponse
import me.jahnen.libaums.core.driver.scsi.commands.sense.SenseException
import me.jahnen.libaums.core.usb.PipeException
import me.jahnen.libaums.core.usb.UsbCommunication
import me.jahnen.libaums.core.usb.UsbDiagnostics
import java.io.IOException
import java.nio.ByteBuffer
import java.util.Arrays

/**
 * This class is responsible for handling mass storage devices which follow the
 * SCSI standard. This class communicates with the mass storage device via the
 * different SCSI commands.
 *
 * @author mjahnen, Derpalus
 * @see com.github.mjdev.libaums.driver.scsi.commands
 */
class ScsiBlockDevice(private val usbCommunication: UsbCommunication, private val lun: Byte) : BlockDeviceDriver {
    private val outBuffer: ByteBuffer = ByteBuffer.allocate(31)
    private val cswBuffer: ByteBuffer = ByteBuffer.allocate(CommandStatusWrapper.SIZE)

    override var blockSize: Int = 0
        private set

    private val writeCommand = ScsiWrite10(lun=lun)
    private val readCommand = ScsiRead10(lun=lun)
    // LOCAL PATCH (docs/VENDOR_FIXES.md V23): for blocks past the 32-bit address range.
    private val write16 = ScsiReadWrite16(write = true, lun = lun)
    private val read16 = ScsiReadWrite16(write = false, lun = lun)
    private val csw = CommandStatusWrapper()

    private var cbwTagCounter = 1

    /**
     * The size of the block device, in blocks of [blockSize] bytes,
     *
     * @return The block device size in blocks
     */
    override var blocks: Long = 0
        private set

    /**
     * Issues a SCSI Inquiry to determine the connected device. After that it is
     * checked if the unit is ready. Logs a warning if the unit is not ready.
     * Finally the capacity of the mass storage device is read.
     *
     * @throws IOException
     * If initialing fails due to an unsupported device or if
     * reading fails.
     * @see com.github.mjdev.libaums.driver.scsi.commands.ScsiInquiry
     *
     * @see com.github.mjdev.libaums.driver.scsi.commands.ScsiInquiryResponse
     *
     * @see com.github.mjdev.libaums.driver.scsi.commands.ScsiTestUnitReady
     *
     * @see com.github.mjdev.libaums.driver.scsi.commands.ScsiReadCapacity
     *
     * @see com.github.mjdev.libaums.driver.scsi.commands.ScsiReadCapacityResponse
     */
    @Throws(IOException::class)
    override fun init() {
        var lastException: Exception? = null
        for(i in 0..MAX_RECOVERY_ATTEMPTS) {
            try {
                initAttempt()
                return
            } catch (e: InitRequired) {
                Log.i(TAG, e.message ?: "Reinitializing device")
                lastException = e
            } catch (e: NotReadyTryAgain) {
                Log.i(TAG, e.message ?: "Reinitializing device")
                lastException = e
            } catch (e: UnitAttention) {
                // LOCAL PATCH (docs/VENDOR_FIXES.md V10): a card reader reports "not
                // ready to ready change, medium may have changed" (ASC 0x28) on the
                // first command after a card goes in. SCSI reports a unit attention
                // once and then clears it, so the right response is to try again —
                // upstream threw instead, and every first plug-in of an SD card in a
                // USB reader failed with "Could not open RawBlockDevice via libaums".
                //
                // Retried here and nowhere else. During init nothing has been read,
                // so a changed medium is exactly what is expected. Mid-transfer the
                // same condition means the card really was swapped, and retrying
                // there would silently continue on a different card — so
                // checkResponseForError still throws it for every other caller.
                if ((e.additionalSenseCode as Number).toInt() and 0xFF != MEDIUM_MAY_HAVE_CHANGED) throw e
                Log.i(TAG, "medium changed (unit attention 0x28) during init, retrying")
                lastException = e
            }
            Thread.sleep(100)
        }

        throw IOException(
            "MAX_RECOVERY_ATTEMPTS Exceeded while trying to init communication with USB device, please reattach device and try again",
            lastException
        )
    }

    @Throws(IOException::class)
    private fun initAttempt() {
        val inBuffer = ByteBuffer.allocate(36)
        val inquiry = ScsiInquiry(inBuffer.array().size.toByte(), lun=lun)
        transferCommand(inquiry, inBuffer)
        inBuffer.clear()
        val inquiryResponse = ScsiInquiryResponse.read(inBuffer)
        Log.d(TAG, "inquiry response: $inquiryResponse")

        if (inquiryResponse.peripheralQualifier.toInt() != 0 || inquiryResponse.peripheralDeviceType.toInt() != 0) {
            throw IOException("unsupported PeripheralQualifier or PeripheralDeviceType")
        }

        val testUnit = ScsiTestUnitReady(lun=lun)
        transferCommandWithoutDataPhase(testUnit)

        val readCapacity = ScsiReadCapacity(lun=lun)
        fun readCapacity10(): ScsiReadCapacityResponse {
            inBuffer.clear()
            transferCommand(readCapacity, inBuffer)
            inBuffer.clear()
            return ScsiReadCapacityResponse.read(inBuffer)
        }
        fun saysTooLarge(r: ScsiReadCapacityResponse) = (r.logicalBlockAddress.toLong() and 0xffff_ffffL) == 0xffff_ffffL
        var readCapacityResponse = readCapacity10()
        if (saysTooLarge(readCapacityResponse)) {
            // LOCAL PATCH (docs/VENDOR_FIXES.md V26): ask again before believing it. A
            // bridge can say 0xFFFFFFFF while the disk behind it is still starting: issue
            // #87's 2 TB SSD (3,907,029,168 blocks, under 2^32) in a Realtek RTL9210
            // enclosure did, then answered no READ CAPACITY(16) until it was replugged,
            // and on the replug said its real size to READ CAPACITY(10). A disk really
            // over 2 TiB keeps saying 0xFFFFFFFF and costs CAPACITY_RECHECKS seconds once.
            val first = "0xFFFFFFFF, block length ${readCapacityResponse.blockLength}"
            for (i in 1..CAPACITY_RECHECKS) {
                Thread.sleep(CAPACITY_RECHECK_MS)
                transferCommandWithoutDataPhase(testUnit)
                readCapacityResponse = readCapacity10()
                if (!saysTooLarge(readCapacityResponse)) {
                    UsbDiagnostics.report("libaums: READ CAPACITY(10) said $first, then " +
                        "${readCapacityResponse.blockCount} blocks after ${i}s; using that")
                    break
                }
            }
            if (saysTooLarge(readCapacityResponse)) {
                UsbDiagnostics.report("libaums: READ CAPACITY(10) said $first ${CAPACITY_RECHECKS + 1} times; asking READ CAPACITY(16)")
            }
        }
        if (saysTooLarge(readCapacityResponse)) {
            // LOCAL PATCH (docs/VENDOR_FIXES.md V23): 0xFFFFFFFF means "too large for
            // this command, ask READ CAPACITY(16)" (SBC-3 5.16.2). Upstream gave up here,
            // so a drive over 2 TiB did not open at all (#87).
            val buffer16 = ByteBuffer.allocate(ScsiReadCapacity16.RESPONSE_LENGTH)
            transferCommand(ScsiReadCapacity16(lun = lun), buffer16)
            buffer16.clear()
            val capacity16 = ScsiReadCapacity16Response.read(buffer16)
            blockSize = capacity16.blockLength
            blocks = capacity16.blockCount
            UsbDiagnostics.report("libaums: over 2 TiB; READ CAPACITY(16) says $blocks blocks of $blockSize B")
        } else {
            blockSize = readCapacityResponse.blockLength
            blocks = readCapacityResponse.blockCount
        }

        Log.i(TAG, "Block size: $blockSize")
        Log.i(TAG, "Block count: $blocks")
    }

    /**
     * Transfers the desired command to the device. If the command has a data
     * phase the parameter `inBuffer` is used to store or read data
     * to resp. from it. The direction of the data phase is determined by
     * [#getDirection()][com.github.mjdev.libaums.driver.scsi.commands.CommandBlockWrapper]
     * .
     *
     *
     * Return value is true if the status of the command status wrapper is
     * successful (
     * [#getbCswStatus()][com.github.mjdev.libaums.driver.scsi.commands.CommandStatusWrapper]
     * ).
     *
     * @param command
     * The command which should be transferred.
     * @param inBuffer
     * The buffer used for reading or writing.
     * @throws IOException
     * If something fails.
     */
    @Throws(IOException::class)
    private fun transferCommand(command: CommandBlockWrapper, inBuffer: ByteBuffer) {
        var lastException: Exception? = null
        // LOCAL PATCH (docs/VENDOR_FIXES.md V11): every attempt restarts the data
        // phase from the caller's window. A failed partial transfer has already moved
        // the position, and upstream re-derived the start from it: the retry threw
        // IllegalArgumentException, or — when the backing array had room — read into
        // or wrote from the wrong offset without any error.
        val startPosition = inBuffer.position()
        val startLimit = inBuffer.limit()
        // LOCAL PATCH (docs/VENDOR_FIXES.md V22): what each failed attempt and Reset
        // Recovery said, summarised in one line for the app's log (UsbDiagnostics).
        val failures = mutableListOf<String>()
        var resetsOk = 0
        val resetErrors = mutableListOf<String>()
        fun summary() = "${failures.size} failed attempt(s): " +
            failures.groupingBy { it }.eachCount().entries.joinToString("; ") { (m, n) -> if (n > 1) "$m (x$n)" else m } +
            "; Reset Recovery $resetsOk ok" +
            (if (resetErrors.isEmpty()) "" else ", ${resetErrors.size} failed: ${resetErrors.distinct().joinToString("; ")}")
        for(i in 0..MAX_RECOVERY_ATTEMPTS) {
            inBuffer.limit(startLimit)
            inBuffer.position(startPosition)
            try {
                val result = transferOneCommand(command, inBuffer)
                val senseWasNotIssued = handleCommandResult(result)
                if (senseWasNotIssued || command.direction == Direction.NONE) {
                    // successful w/o need of sending sense command
                    // OR
                    // command has no data phase ie. no need to sent again
                    // and read response into buffer
                    if (failures.isNotEmpty()) UsbDiagnostics.report("libaums: $command recovered after ${summary()}")
                    return
                }
                failures += "sense issued, retrying"

                // sense command was sent because of error, sense was successful
                // ie. NO_SENSE, RECOVERED_ERROR, COMPLETED, see
                // sense response impl
                // try again and hope that data phase ie. filling inBuffer works now

            } catch (e: SenseException) {
                Log.w(TAG, (e.message ?: "SenseException"))
                failures += "${e.javaClass.simpleName}: ${e.message}"
                when(e) {
                    is InitRequired -> init()
                    is NotReadyTryAgain -> {} // try again
                    else -> throw e
                }
                lastException = e
            } catch(e: PipeException) {
                Log.w(TAG, (e.message ?: "PipeException") + ", try bulk storage reset and retry")
                failures += "PipeException: ${e.message}"
                bulkOnlyMassStorageReset()
                resetsOk++
                lastException = e
            } catch (e: IOException) {
                // Retry
                Log.w(TAG, (e.message ?: "IOException") + ", retrying...")
                failures += "${e.message}"
                // LOCAL PATCH (docs/VENDOR_FIXES.md V12): resynchronise before retrying.
                // A transfer that failed part-way leaves the rest of its data and its
                // CSW queued in the device; a retry without Reset Recovery reads that
                // stale CSW ("wrong csw tag!"), and every later command stays one
                // status behind until the drive is replugged. BOT 1.0 §5.3.4 requires
                // Reset Recovery after an invalid CSW.
                try {
                    bulkOnlyMassStorageReset()
                    resetsOk++
                } catch (r: Exception) {
                    Log.w(TAG, "reset recovery failed: ${r.message}")
                    resetErrors += "${r.message}"
                } catch (r: LinkageError) {
                    // LOCAL PATCH (docs/VENDOR_FIXES.md V13): a missing native method is
                    // an Error, not an Exception; it must fail the reset, not the app.
                    Log.e(TAG, "reset recovery unavailable: ${r.message}")
                    resetErrors += "unavailable: ${r.message}"
                }
                lastException = e
            }

            Thread.sleep(100)
        }

        UsbDiagnostics.report("libaums: $command gave up after ${summary()}")
        throw IOException(
            "MAX_RECOVERY_ATTEMPTS Exceeded while trying to transfer command to device, please reattach device and try again",
            lastException
        )
    }

    @Throws(IOException::class)
    private fun transferCommandWithoutDataPhase(command: CommandBlockWrapper) {
        require(command.direction == Direction.NONE) { "Command has a data phase" }
        transferCommand(command, ByteBuffer.allocate(0))
    }

    @Throws(IOException::class)
    private fun handleCommandResult(status: Int): Boolean {
        return when (status) {
            CommandStatusWrapper.COMMAND_PASSED -> true
            CommandStatusWrapper.COMMAND_FAILED -> {
                requestSense()
                false
            }
            CommandStatusWrapper.PHASE_ERROR -> {
                bulkOnlyMassStorageReset()
                throw IOException("phase error, please reattach device and try again")
            }
            else -> throw IllegalStateException("CommandStatus wrapper illegal status $status")
        }
    }

    @Throws(IOException::class)
    private fun requestSense() {
        val inBuffer = ByteBuffer.allocate(252)
        val sense = ScsiRequestSense(inBuffer.array().size, lun = lun)
        when (val status = transferOneCommand(sense, inBuffer)) {
            CommandStatusWrapper.COMMAND_PASSED -> {
                inBuffer.clear()
                val response = ScsiRequestSenseResponse.read(inBuffer)
                response.checkResponseForError()
            }
            CommandStatusWrapper.COMMAND_FAILED -> throw IOException("requesting sense failed")
            CommandStatusWrapper.PHASE_ERROR -> {
                bulkOnlyMassStorageReset()
                throw IOException("phase error, please reattach device and try again")
            }
            else -> throw IllegalStateException("CommandStatus wrapper illegal status $status")
        }
    }

    /**
     * LOCAL PATCH (docs/VENDOR_FIXES.md V25): how many Bulk-Only Mass Storage Resets
     * this device has sent, including those inside a retry that went on to succeed.
     *
     * A reset can make the drive lose data it had already acknowledged. On a OnePlus 7
     * a reset sent while a write was failing left the sector *before* that write - which
     * an earlier command had written and the drive had confirmed - reading back as
     * zeros: the drive had been rewriting the 4 KiB unit that sector shares with the
     * failing write's start. The app above cannot see resets that libaums recovers
     * from on its own; this counter lets it notice them and write that data again.
     */
    @Volatile var resets = 0L
        private set

    @Throws(IOException::class)
    private fun bulkOnlyMassStorageReset() {
        resets++ // LOCAL PATCH (docs/VENDOR_FIXES.md V25)
        Log.w(TAG, "sending bulk only mass storage request")
        val bArr = ByteArray(2)
        // REQUEST_BULK_ONLY_MASS_STORAGE_RESET = 255
        // REQUEST_TYPE_BULK_ONLY_MASS_STORAGE_RESET = 33
        val transferred: Int = usbCommunication.controlTransfer(33, 255, 0, usbCommunication.usbInterface.id, bArr, 0)
        if (transferred == -1) {
            throw IOException("bulk only mass storage reset failed!")
        }
        Log.d(TAG, "Trying to clear halt on both endpoints")
        usbCommunication.clearFeatureHalt(usbCommunication.inEndpoint)
        usbCommunication.clearFeatureHalt(usbCommunication.outEndpoint)
    }

    @Throws(IOException::class)
    private fun transferOneCommand(command: CommandBlockWrapper, inBuffer: ByteBuffer): Int {
        val outArray = outBuffer.array()
        Arrays.fill(outArray, 0.toByte())

        command.dCbwTag = cbwTagCounter
        cbwTagCounter++

        outBuffer.clear()
        command.serialize(outBuffer)
        outBuffer.clear()

        var written = usbCommunication.bulkOutTransfer(outBuffer)
        if (written != outArray.size) {
            throw IOException("Writing all bytes on command $command failed!")
        }

        var transferLength = command.dCbwDataTransferLength
        val initialPosition = inBuffer.position()
        inBuffer.limit(initialPosition + transferLength)

        var read = 0
        if (transferLength > 0) {

            if (command.direction == Direction.IN) {
                do {
                    read += usbCommunication.bulkInTransfer(inBuffer)
                    if (command.bCbwDynamicSize) {
                        transferLength = command.dynamicSizeFromPartialResponse(inBuffer)
                        val clampedLength =
                            transferLength.coerceAtMost(inBuffer.capacity() - initialPosition)
                        if (transferLength != clampedLength) {
                            Log.w(
                                TAG,
                                "Device returned transfer length $transferLength, clamping to $clampedLength to prevent overflow"
                            )
                            transferLength = clampedLength
                        }
                        inBuffer.limit(initialPosition + transferLength)
                    }
                } while (read < transferLength)

                if (read != transferLength) {
                    throw IOException("Unexpected command size ($read) on response to $command")
                }
            } else {
                written = 0
                do {
                    written += usbCommunication.bulkOutTransfer(inBuffer)
                } while (written < transferLength)

                if (written != transferLength) {
                    throw IOException("Could not write all bytes: $command")
                }
            }
        }


        // expecting csw now
        cswBuffer.clear()
        read = usbCommunication.bulkInTransfer(cswBuffer)
        if (read != CommandStatusWrapper.SIZE) {
            throw IOException("Unexpected command size while expecting csw")
        }
        cswBuffer.clear()

        csw.read(cswBuffer)
        if (csw.dCswTag != command.dCbwTag) {
            throw IOException("wrong csw tag!")
        }

        return csw.bCswStatus.toInt()
    }

    /**
     * This method reads from the device at the specific device offset. The
     * devOffset specifies at which block the reading should begin. That means
     * the devOffset is not in bytes!
     */
    @Synchronized
    @Throws(IOException::class)
    override fun read(deviceOffset: Long, buffer: ByteBuffer) {
        //long time = System.currentTimeMillis();
        require(buffer.remaining() % blockSize == 0) { "buffer.remaining() must be multiple of blockSize!" }

        // LOCAL PATCH (docs/VENDOR_FIXES.md V23): READ(10) takes a 32-bit address, and
        // toInt() wrapped a larger one onto the start of the drive. READ(16) past it.
        val command = if (needs16(deviceOffset, buffer.remaining())) {
            read16.apply { init(deviceOffset, buffer.remaining(), blockSize) }
        } else {
            readCommand.apply { init(deviceOffset.toInt(), buffer.remaining(), blockSize) }
        }
        //Log.d(TAG, "reading: " + read);

        transferCommand(command, buffer)
        buffer.position(buffer.limit())

        //Log.d(TAG, "read time: " + (System.currentTimeMillis() - time));
    }

    /**
     * This method writes from the device at the specific device offset. The
     * devOffset specifies at which block the writing should begin. That means
     * the devOffset is not in bytes!
     */
    @Synchronized
    @Throws(IOException::class)
    override fun write(deviceOffset: Long, buffer: ByteBuffer) {
        //long time = System.currentTimeMillis();
        require(buffer.remaining() % blockSize == 0) { "buffer.remaining() must be multiple of blockSize!" }

        // LOCAL PATCH (docs/VENDOR_FIXES.md V23): as in read. Here the wrap was worse
        // than a failure: a write past 2 TiB landed on the start of the drive.
        val command = if (needs16(deviceOffset, buffer.remaining())) {
            write16.apply { init(deviceOffset, buffer.remaining(), blockSize) }
        } else {
            writeCommand.apply { init(deviceOffset.toInt(), buffer.remaining(), blockSize) }
        }
        //Log.d(TAG, "writing: " + write);

        transferCommand(command, buffer)
        buffer.position(buffer.limit())

        //Log.d(TAG, "write time: " + (System.currentTimeMillis() - time));
    }

    /**
     * LOCAL PATCH (docs/VENDOR_FIXES.md V23): whether a transfer reaches past the last
     * block a 32-bit address can name. Only those use the 16-byte commands, so drives
     * up to 2 TiB send exactly what they always have.
     */
    private fun needs16(deviceOffset: Long, bytes: Int): Boolean {
        require(deviceOffset >= 0) { "negative block address $deviceOffset" }
        return deviceOffset + bytes / blockSize - 1 > 0xffff_ffffL
    }

    companion object {
        private const val MAX_RECOVERY_ATTEMPTS = 5
        /** LOCAL PATCH (docs/VENDOR_FIXES.md V10): ASC for "medium may have changed". */
        private const val MEDIUM_MAY_HAVE_CHANGED = 0x28
        /** LOCAL PATCH (docs/VENDOR_FIXES.md V26): READ CAPACITY(10) asked again this often, a second apart, after 0xFFFFFFFF. */
        private const val CAPACITY_RECHECKS = 3
        private const val CAPACITY_RECHECK_MS = 1000L
        private val TAG = ScsiBlockDevice::class.java.simpleName
    }
}
