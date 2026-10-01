package app.fayaz.otgmaster.bench

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import app.fayaz.otgmaster.OtgMasterState
import app.fayaz.otgmaster.block.CachedBlockDevice
import app.fayaz.otgmaster.block.RawBlockDevice
import app.fayaz.otgmaster.ntfs.NtfsFileSystem
import java.io.BufferedOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Copies a mounted partition's raw sectors to a file, for checking on the host.
 *
 * The host checkers (ntfs_check.py, ntfsfix, e2fsck) are the only things that can
 * see structural damage, and they normally need the drive moved to the laptop.
 * This lets them run on exactly what is on the drive with adb alone:
 *
 *   adb shell am broadcast -a app.fayaz.otgmaster.DUMP_VOLUME \
 *       -n app.fayaz.otgmaster/.bench.VolumeDumpReceiver --es drive NTFSPLAIN
 *   adb pull /storage/emulated/0/Android/data/app.fayaz.otgmaster/files/NTFSPLAIN.otgdump
 *   python3 scripts/undump_volume.py NTFSPLAIN.otgdump ntfs.img
 *
 * The read bypasses every cached line (the cache is dropped first, and 1 MiB reads
 * go around it), so the dump is the device's bytes, not the driver's view of
 * them. For NTFS the filesystem lock is held throughout, so no write can land
 * half-way through; every NTFS write is already on the device when its call
 * returns, so nothing needs flushing first.
 *
 * What is copied: for NTFS, every cluster $Bitmap marks in use, plus the first and
 * last megabyte (the boot sector and its backup, which lies past the last
 * cluster). Taking the driver's bitmap cannot hide damage: a cluster in use but
 * marked free is left out, reads back as zeros, and fails the host checks. For
 * other filesystems, every megabyte that is not all zeros — a partition that was
 * never zeroed then dumps whole. Format,
 * little-endian: "OTGDUMP1", u64 partition size, then (u64 offset, u32 length,
 * bytes) records.
 */
class VolumeDumpReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val want = intent.getStringExtra("drive")
        Thread {
            try {
                dump(context, want)
            } catch (e: Throwable) {
                Log.e(TAG, "dump failed", e)
            }
        }.apply { name = "OTGDump"; isDaemon = false }.start()
    }

    private fun dump(context: Context, want: String?) {
        val drive = OtgMasterState.mountedDrives.firstOrNull {
            want == null || runCatching { it.fileSystem.volumeLabel }.getOrDefault("")
                .equals(want, ignoreCase = true) || it.name.contains(want, ignoreCase = true)
        } ?: run { Log.e(TAG, "no mounted drive matches '$want'"); return }
        val device = drive.blockDevice ?: run { Log.e(TAG, "drive has no block device"); return }
        val label = runCatching { drive.fileSystem.volumeLabel }.getOrDefault("volume")
            .ifEmpty { "volume" }.replace(Regex("[^A-Za-z0-9_.-]"), "_")
        val out = File(context.getExternalFilesDir(null), "$label.otgdump")

        val ntfs = drive.fileSystem as? NtfsFileSystem
        val body = { copy(device, out, ntfs?.allocationBitmap()) }
        val t0 = System.currentTimeMillis()
        val (kept, total) = when (val fs = drive.fileSystem) {
            is NtfsFileSystem -> fs.withNative { body() }
            else -> body()
        }
        Log.i(TAG, "dump done: $out — ${kept shr 20} MiB of data in ${total shr 20} MiB, " +
            "${(System.currentTimeMillis() - t0) / 1000}s")
    }

    /** @param allocation cluster size and in-use bitmap; null copies every non-zero megabyte. */
    private fun copy(device: RawBlockDevice, out: File, allocation: Pair<Int, ByteArray>?): Pair<Long, Long> {
        (device as? CachedBlockDevice)?.invalidate()
        val total = device.blockCount * device.blockSize
        val chunkBlocks = (CHUNK / device.blockSize)
        var kept = 0L
        BufferedOutputStream(out.outputStream(), 1 shl 20).use { os ->
            os.write("OTGDUMP1".toByteArray())
            os.write(le64(total))
            var block = 0L
            var lastLog = System.currentTimeMillis()
            while (block < device.blockCount) {
                val n = minOf(chunkBlocks.toLong(), device.blockCount - block).toInt()
                if (allocation != null && !needed(block, n, device, total, allocation)) {
                    block += n
                    continue
                }
                val data = device.readBlocks(block, n)
                if (allocation != null || data.any { it != 0.toByte() }) {
                    os.write(le64(block * device.blockSize))
                    os.write(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(data.size).array())
                    os.write(data)
                    kept += data.size
                }
                block += n
                if (System.currentTimeMillis() - lastLog > 15_000) {
                    Log.i(TAG, "dumping: ${block * 100 / device.blockCount}%")
                    lastLog = System.currentTimeMillis()
                }
            }
        }
        return kept to total
    }

    /** Whether [n] blocks at [block] hold any allocated cluster or a boot sector. */
    private fun needed(block: Long, n: Int, device: RawBlockDevice, total: Long,
                       allocation: Pair<Int, ByteArray>): Boolean {
        val start = block * device.blockSize
        val end = start + n.toLong() * device.blockSize
        if (start < CHUNK || end > total - CHUNK) return true
        val (clusterSize, bitmap) = allocation
        var c = start / clusterSize
        val last = (end - 1) / clusterSize
        while (c <= last) {
            val byte = (c / 8).toInt()
            if (byte >= bitmap.size) return true  // past the bitmap: keep, never guess
            if (bitmap[byte].toInt() and (1 shl (c % 8).toInt()) != 0) return true
            c++
        }
        return false
    }

    private fun le64(v: Long) = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putLong(v).array()

    private companion object {
        const val TAG = "OTGDump"
        const val CHUNK = 1 shl 20
    }
}
