package app.fayaz.otgmaster.bench

import android.content.Context
import android.util.Log
import app.fayaz.otgmaster.MountedDrive
import app.fayaz.otgmaster.OtgMasterState
import app.fayaz.otgmaster.exfat.ExFatFileSystem
import app.fayaz.otgmaster.exfat.ExFatIoStats
import me.jahnen.libaums.core.fs.UsbFile
import java.io.File
import java.nio.ByteBuffer
import kotlin.random.Random
import kotlin.system.measureNanoTime

/**
 * Layered baseline measurements for docs/IO_PERFORMANCE.md.
 *
 * Deliberately measures at several depths so a number can be attributed rather
 * than just observed: the block layer isolates USB transport plus XTS decryption,
 * the filesystem layer adds driver overhead on top, and the metadata tests
 * exercise the paths the DocumentsProvider hits on every operation.
 *
 * Expects a drive prepared by scripts/prepare_test_usb.sh (BENCH/ fixtures).
 */
object Benchmark {

    private const val TAG = "OTGBench"

    private data class Result(val name: String, val detail: String)

    /** Credentials for mounting before measuring, when nothing is mounted yet. */
    data class MountCredentials(
        val password: String,
        val pim: Int? = null,
        val cipher: String = "AES",
        val hash: String = "SHA-512",
    )

    private const val MOUNT_TIMEOUT_MS = 90_000L

    /**
     * @param only run just these sections (empty = all). Names:
     *   free, block, dir, path, seq, random, opens
     *
     * A full run took 3.6 hours on the exFAT baseline, ~2.1 of which was the
     * random-read section, so subsets matter for iterating on an optimisation.
     */
    /**
     * Refuses a second concurrent run.
     *
     * Killing the host-side runner does not stop the benchmark: it lives on a
     * plain Thread on the device and keeps going. The next broadcast then started
     * a second run against the same USB device, and the two interleaved — doubled
     * output under one heading, sequential reads at 0.57 MB/s instead of ~6, and
     * one run unmounting the volume out from under the other (which the
     * use-after-unmount guard then reported as "exFAT filesystem is unmounted").
     * Numbers from overlapping runs are meaningless, so refuse rather than produce
     * them.
     */
    private val running = java.util.concurrent.atomic.AtomicBoolean(false)

    fun runAll(
        context: Context,
        only: Set<String> = emptySet(),
        mount: MountCredentials? = null,
        remount: Boolean = false,
    ): String {
        if (!running.compareAndSet(false, true)) {
            val msg = "*** a benchmark is already running — refusing to start a second one ***"
            Log.w(TAG, msg)
            OtgMasterState.logSink?.invoke(msg)
            return msg
        }
        try {
            return runAllLocked(context, only, mount, remount)
        } finally {
            running.set(false)
        }
    }

    private fun runAllLocked(
        context: Context,
        only: Set<String> = emptySet(),
        mount: MountCredentials? = null,
        remount: Boolean = false,
    ): String {
        fun wants(name: String) = only.isEmpty() || name in only
        val out = StringBuilder()
        fun emit(line: String) {
            Log.i(TAG, line)
            out.appendLine(line)
            // Mirror into the app's log pane so progress and results are visible
            // on the device, without needing adb.
            OtgMasterState.log(line)
        }

        emit("=== OTG Master I/O baseline === (running, this takes a while)")
        emit("time: ${java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US).format(java.util.Date())}")
        emit("device: ${android.os.Build.MODEL} / Android ${android.os.Build.VERSION.RELEASE} (SDK ${android.os.Build.VERSION.SDK_INT})")
        emitPowerState(context, ::emit)
        emit("")

        val cfg = OtgMasterState.cacheConfig
        emit("cache config: " + when {
            cfg == null -> "default (per-filesystem readahead)"
            !cfg.enabled -> "DISABLED"
            else -> "enabled, readahead ${cfg.readAheadBytes ?: "default"} bytes"
        })
        emit("")

        // Remount when a cache configuration is requested: the cache is chosen at
        // mount time, so an existing mount would keep the previous setting and the
        // comparison would be meaningless.
        if (remount && OtgMasterState.mountedDrives.isNotEmpty()) {
            emit("unmounting ${OtgMasterState.mountedDrives.size} drive(s) to apply cache config")
            OtgMasterState.unmountAllRequest?.invoke()
            val deadline = System.currentTimeMillis() + 30_000
            while (OtgMasterState.mountedDrives.isNotEmpty() &&
                    System.currentTimeMillis() < deadline) {
                Thread.sleep(300)
            }
            Thread.sleep(1500)   // let the USB handle settle before re-probing
        }

        if (OtgMasterState.mountedDrives.isEmpty() && mount != null) {
            emit("no drives mounted — requesting mount (PIM ${mount.pim ?: "default"}, ${mount.cipher}/${mount.hash})")
            val handler = OtgMasterState.mountRequest
            if (handler == null) {
                emit("*** no mount handler installed — is MainActivity running? ***")
            } else {
                handler.mount(mount.password, mount.pim, mount.cipher, mount.hash)
                // attemptUnlock is asynchronous; wait for a drive to appear.
                val deadline = System.currentTimeMillis() + MOUNT_TIMEOUT_MS
                while (OtgMasterState.mountedDrives.isEmpty() &&
                        System.currentTimeMillis() < deadline) {
                    Thread.sleep(500)
                }
                if (OtgMasterState.mountedDrives.isEmpty()) {
                    emit("*** mount did not complete within ${MOUNT_TIMEOUT_MS / 1000}s ***")
                } else {
                    emit("mounted: ${OtgMasterState.mountedDrives.joinToString { it.name }}")
                    emit("")
                }
            }
        }

        // Skip drives whose filesystem has already been unmounted. A remount leaves
        // the old entry in the list briefly, and calling into a torn-down
        // ExFatFileSystem is what the withNative guard exists to stop.
        val drives = OtgMasterState.mountedDrives.filter {
            (it.fileSystem as? ExFatFileSystem)?.isUnmounted != true
        }
        if (drives.isEmpty()) {
            emit("NO DRIVES MOUNTED — attach a prepared drive, or pass mount credentials")
            return out.toString().also { save(context, it) }
        }

        for (drive in drives) {
            emit("--- drive: ${drive.name} ---")
            val fs = drive.fileSystem
            emit("volumeLabel   : ${runCatching { fs.volumeLabel }.getOrDefault("?")}")
            emit("capacity      : ${fs.capacity / 1024 / 1024} MiB")
            emit("chunkSize     : ${runCatching { fs.chunkSize }.getOrDefault(-1)}")
            emit("blockDevice   : ${drive.blockDevice?.javaClass?.simpleName ?: "none"}")
            val cache = drive.blockDevice as? app.fayaz.otgmaster.block.CachedBlockDevice
            if (cache != null) emit("cache         : enabled (${cache.stats()})")
            val underlying = (drive.blockDevice as? app.fayaz.otgmaster.block.CachedBlockDevice)
                ?.uncached ?: drive.blockDevice
            emit("encrypted     : ${underlying?.javaClass?.simpleName == "NativeDecryptedBlockDevice"}")
            emit("")

            if (wants("free")) runCatching { benchFreeSpace(fs, ::emit) }.onFailure { emit("freeSpace     : FAILED ${it}") }
            if (wants("block")) runCatching { benchBlockLayer(drive, ::emit) }.onFailure {
                emit("block layer   : FAILED ${it}")
                emit("*** the device is not readable — every number below is meaningless ***")
            }

            val bench = runCatching { drive.fileSystem.rootDirectory.search("BENCH") }.getOrNull()
            if (bench == null) {
                emit("BENCH/ not found — is this a prepared fixture drive?")
                emit("")
                continue
            }

            if (wants("dir")) runCatching { benchDirListing(bench, ::emit) }.onFailure { emit("dir listing   : FAILED ${it}") }
            if (wants("path")) runCatching { benchPathResolve(drive.fileSystem.rootDirectory, ::emit) }.onFailure { emit("path resolve  : FAILED ${it}") }
            if (wants("seq")) runCatching { benchSequentialRead(bench, ::emit) }.onFailure { emit("seq read      : FAILED ${it}") }
            if (wants("random")) runCatching { benchRandomRead(bench, ::emit) }.onFailure { emit("random read   : FAILED ${it}") }
            if (wants("opens")) runCatching { benchDenseOpens(bench, ::emit) }.onFailure { emit("dense opens   : FAILED ${it}") }
            // Opt-in only: this one writes to the drive, so a default run stays
            // read-only.
            if (only.contains("write")) runCatching { benchWriteVerify(drive, ::emit, mount) }
                .onFailure { emit("write verify  : FAILED ${it}") }
            // Opt-in: writes, and deliberately unaligned.
            if (only.contains("unaligned")) runCatching { benchUnaligned(drive, ::emit, mount) }
                .onFailure { emit("unaligned     : FAILED ${it}") }
            emit("")
        }

        val text = out.toString()
        save(context, text)
        emit("=== benchmark finished ===")
        return text
    }

    /** exFAT rescans the whole allocation bitmap per call; FAT32 reads a cached field. */
    private fun benchFreeSpace(fs: me.jahnen.libaums.core.fs.FileSystem, emit: (String) -> Unit) {
        val first = measureNanoTime { fs.freeSpace }
        val n = 5
        val rest = measureNanoTime { repeat(n) { fs.freeSpace } }
        emit("freeSpace     : first ${ms(first)}, then ${ms(rest / n)} avg over $n  (queryRoots calls this per drive)")
    }

    /**
     * Isolates USB transport + XTS decryption, with no filesystem in the way.
     *
     * Deliberately measures the *uncached* device, so this stays comparable with the
     * pre-cache baselines in docs/IO_PERFORMANCE.md rather than measuring the cache.
     */
    private fun benchBlockLayer(drive: MountedDrive, emit: (String) -> Unit) {
        val exposed = drive.blockDevice ?: return emit("block layer   : no block device exposed")
        val dev = (exposed as? app.fayaz.otgmaster.block.CachedBlockDevice)?.uncached ?: exposed
        val bs = dev.blockSize
        for (spanKiB in intArrayOf(4, 64, 512, 4096)) {
            val blocks = (spanKiB * 1024) / bs
            if (blocks <= 0) continue
            val reps = if (spanKiB >= 4096) 2 else 8
            var bytes = 0L
            val ns = measureNanoTime {
                repeat(reps) { i ->
                    val start = 64L + i.toLong() * blocks
                    bytes += dev.readBlocks(start, blocks).size.toLong()
                }
            }
            emit("block read    : ${spanKiB.toString().padStart(5)} KiB span -> ${mbps(bytes, ns)}")
        }
    }

    private fun benchDirListing(bench: UsbFile, emit: (String) -> Unit) {
        for (name in listOf("dense_short", "dense_lfn")) {
            val dir = bench.search(name) ?: run { emit("dir listing   : $name missing"); return@benchDirListing }
            var count = 0
            withIoStats(emit, 0L) {
                val cold = measureNanoTime { count = dir.listFiles().size }
                val warm = measureNanoTime { dir.listFiles() }
                emit("list $name".padEnd(14) + ": $count entries, cold ${ms(cold)}, warm ${ms(warm)}")
                if (count == 0) emit("    *** 0 entries — fixture missing or device failing, treat as invalid ***")
            }
        }
    }

    /**
     * VeraCryptDocumentProvider.getFileForDocId re-walks from the root and
     * re-lists every directory on the path for every single operation.
     */
    private fun benchPathResolve(root: UsbFile, emit: (String) -> Unit) {
        val path = "BENCH/nested/level_01/level_02/level_03/level_04/level_05/" +
                "level_06/level_07/level_08/level_09/level_10/leaf_at_depth_10.dat"
        val reps = 20
        val ns = measureNanoTime { repeat(reps) { root.search(path) } }
        emit("path resolve  : depth-10 path x$reps -> ${ms(ns / reps)} each")
    }

    private fun benchSequentialRead(bench: UsbFile, emit: (String) -> Unit) {
        val large = bench.search("large") ?: return emit("seq read      : large/ missing")
        val file = large.listFiles().maxByOrNull { runCatching { it.length }.getOrDefault(0L) }
            ?: return emit("seq read      : no files in large/")
        val target = minOf(file.length, 64L * 1024 * 1024)
        for (bufKiB in intArrayOf(32, 128, 512)) {
            val buf = ByteBuffer.allocate(bufKiB * 1024)
            var off = 0L
            withIoStats(emit, target) {
                val ns = measureNanoTime {
                    while (off < target) {
                        buf.clear()
                        val want = minOf(buf.capacity().toLong(), target - off).toInt()
                        buf.limit(want)
                        file.read(off, buf)
                        // UsbFile.read reports nothing on failure — ExFatFile swallows
                        // the error and leaves the buffer untouched. Without this check
                        // a dead device produces spectacular throughput: a closed USB
                        // connection once measured 500 MB/s at 0.01x amplification.
                        val got = buf.position()
                        require(got == want) {
                            "short read at offset $off: asked $want, got $got " +
                            "(device or filesystem failing — measurement invalid)"
                        }
                        off += want
                    }
                }
                emit("seq read      : ${file.name} ${bufKiB.toString().padStart(4)} KiB buf -> ${mbps(off, ns)}")
            }
        }
    }

    private fun benchRandomRead(bench: UsbFile, emit: (String) -> Unit) {
        val large = bench.search("large") ?: return
        // Deliberately the SMALLEST fixture. Seeking to offset N walks the cluster
        // chain from the start, so cost scales with file size: on the 2 GiB file a
        // single 4 KiB read took ~76 s, making 100 reps a 2.1-hour test. The
        // smallest file shows the same behaviour in a usable amount of time.
        val file = large.listFiles()
            .filter { runCatching { !it.isDirectory && it.length > 0 }.getOrDefault(false) }
            .minByOrNull { it.length } ?: return
        val buf = ByteBuffer.allocate(4096)
        val reps = 10
        val rnd = Random(42)
        val maxOff = (file.length - 4096).coerceAtLeast(1)
        val ns = measureNanoTime {
            repeat(reps) {
                buf.clear()
                file.read(rnd.nextLong(maxOff) and 0xFFFFFFFFFFFFF000uL.toLong(), buf)
                require(buf.position() == buf.capacity()) {
                    "short random read: got ${buf.position()} of ${buf.capacity()} " +
                    "(measurement invalid)"
                }
            }
        }
        val iops = reps * 1_000_000_000.0 / ns
        emit("random read   : 4 KiB x$reps in ${file.name} (${file.length / 1024 / 1024} MiB) -> " +
                "${ms(ns / reps)} each, ${"%.3f".format(iops)} IOPS")
    }

    /** The O(n^2) case: each open re-lists the whole directory. */
    private fun benchDenseOpens(bench: UsbFile, emit: (String) -> Unit) {
        for (dirName in listOf("dense_short", "dense_lfn")) {
            val dir = bench.search(dirName) ?: continue
            // name is a platform type from libaums and has been observed null,
            // which threw an NPE mid-run and lost the whole section.
            val listed = runCatching {
                dir.listFiles().take(50).mapNotNull { runCatching { it.name }.getOrNull() }
            }
            val names = listed.getOrNull()
            if (names == null) {
                emit("open in $dirName".padEnd(14) + ": could not list (${listed.exceptionOrNull()})")
                continue
            }
            if (names.isEmpty()) continue
            val ns = measureNanoTime { names.forEach { dir.search(it) } }
            emit("open in $dirName".padEnd(14) + ": ${names.size} files -> ${ms(ns / names.size)} each, ${ms(ns)} total")
        }
    }

    /**
     * Runs [body] and reports the libexfat pread/pwrite traffic it caused.
     *
     * Throughput alone cannot separate "the bytes are slow" from "there are far
     * more round trips than the bytes need" — that distinction is what found the
     * missing block cache. Reports nothing for FAT32, which bypasses libexfat.
     */
    private inline fun withIoStats(emit: (String) -> Unit, logicalBytes: Long, body: () -> Unit) {
        runCatching { ExFatIoStats.reset() }
        body()
        val st = runCatching { ExFatIoStats.snapshot() }.getOrNull() ?: return
        if (st.size < 10 || (st[0] == 0L && st[2] == 0L)) return
        val calls = st[0]
        val bytes = st[1]
        emit("    io: %d preads, %.1f MiB read%s".format(
            calls, bytes / 1048576.0,
            if (logicalBytes > 0)
                " for %.1f MiB asked (%.2fx amplification)".format(
                    logicalBytes / 1048576.0, bytes.toDouble() / logicalBytes)
            else ""))
        emit("    io: sizes <=512:%d  <=4K:%d  <=16K:%d  <=64K:%d  <=256K:%d  >256K:%d"
                .format(st[4], st[5], st[6], st[7], st[8], st[9]))
        if (calls > 0) emit("    io: mean pread %.0f bytes".format(bytes.toDouble() / calls))
    }

    /**
     * End-to-end write verification.
     *
     * The cache is write-through and invalidates on write, but that had only ever
     * been checked against a fake device in unit tests. This proves it on real
     * hardware, and the invalidate step is the point: re-reading after dropping
     * every cached line shows the bytes genuinely reached the device rather than
     * being served back out of memory.
     *
     * Writes into a scratch directory and removes it afterwards. The prepared
     * fixtures deliberately leave ~4 GiB free for this.
     */
    /**
     * Position-dependent byte for an absolute file offset.
     *
     * Every byte depends on where it lives, so data landing at the wrong offset is
     * detectable. An earlier version wrote one 64 KiB pattern repeatedly, which
     * could not distinguish correct data from two chunks swapped or a read served
     * from the wrong block — the exact failure mode a block cache risks.
     */
    private fun expectedByteAt(offset: Long): Byte =
        (((offset * 2654435761L) xor (offset ushr 13)) and 0xFF).toByte()

    /**
     * End-to-end write verification.
     *
     * Four checks, because a write bug on a VeraCrypt volume is not recoverable by
     * ordinary tools:
     *  1. byte-for-byte against position-dependent content, so misplacement counts
     *     as corruption;
     *  2. file length, which a content comparison alone cannot catch;
     *  3. re-read after invalidating the cache, proving bytes reached the device
     *     rather than being served from memory;
     *  4. re-read after a full unmount and remount, which discards libexfat's and
     *     libaums' in-memory metadata — otherwise a corrupt FAT chain or directory
     *     entry that was never written back would still verify.
     *
     * The SHA-256 is reported so it can be cross-checked on a host with shasum
     * against the same generator.
     */
    private fun benchWriteVerify(
        drive: MountedDrive,
        emit: (String) -> Unit,
        mount: MountCredentials? = null,
    ) {
        val root = drive.fileSystem.rootDirectory
        val dirName = "BENCH_WRITE"
        val fileName = "verify.bin"
        val sizeMiB = 16
        val chunk = 64 * 1024
        val total = sizeMiB * 1024L * 1024L

        runCatching { root.search(dirName)?.let { deleteRecursively(it) } }
        val dir = root.createDirectory(dirName)

        var expectedHash = ""
        try {
            val file = dir.createFile(fileName)
            val md = java.security.MessageDigest.getInstance("SHA-256")
            val buf = ByteArray(chunk)

            val writeNs = measureNanoTime {
                var off = 0L
                while (off < total) {
                    for (i in 0 until chunk) buf[i] = expectedByteAt(off + i)
                    md.update(buf)
                    file.write(off, ByteBuffer.wrap(buf))
                    off += chunk
                }
                file.flush()
            }
            expectedHash = md.digest().joinToString("") { "%02x".format(it) }
            emit("write         : $sizeMiB MiB -> ${mbps(total, writeNs)}")
            emit("              expected sha256 ${expectedHash.take(32)}…")

            file.close()
        } catch (e: Throwable) {
            emit("write         : FAILED $e")
            runCatching { root.search(dirName)?.let { deleteRecursively(it) } }
            return
        }

        /**
         * Picks a drive whose filesystem is actually still live.
         *
         * Re-querying mountedDrives blindly is how this harness crashed the app: an
         * unmount can leave a stale entry in the list, and calling into libexfat
         * through it dereferences a freed `struct exfat` (SIGSEGV at ef->sb, not a
         * catchable exception).
         */
        fun liveRoot(): UsbFile? = OtgMasterState.mountedDrives
            .firstOrNull { (it.fileSystem as? ExFatFileSystem)?.isUnmounted != true }
            ?.fileSystem?.rootDirectory

        /** Re-opens the file by name so nothing is carried over in a stale handle. */
        fun verifyPass(label: String): Boolean {
            val d = liveRoot()?.search(dirName)
            val f = d?.search(fileName)
            if (f == null) {
                emit("verify $label".padEnd(20) + ": *** file not found ***")
                return false
            }
            val len = runCatching { f.length }.getOrDefault(-1L)
            if (len != total) {
                emit("verify $label".padEnd(20) + ": *** LENGTH $len, expected $total ***")
                return false
            }
            val md = java.security.MessageDigest.getInstance("SHA-256")
            val bb = ByteBuffer.allocate(chunk)
            var off = 0L
            var mismatchAt = -1L
            val ns = measureNanoTime {
                while (off < total) {
                    bb.clear()
                    f.read(off, bb)
                    if (bb.position() != chunk) { mismatchAt = off; return@measureNanoTime }
                    val got = bb.array()
                    for (i in 0 until chunk) {
                        if (got[i] != expectedByteAt(off + i)) { mismatchAt = off + i; break }
                    }
                    if (mismatchAt >= 0) return@measureNanoTime
                    md.update(got, 0, chunk)
                    off += chunk
                }
            }
            if (mismatchAt >= 0) {
                emit("verify $label".padEnd(20) + ": *** MISMATCH at byte $mismatchAt ***")
                return false
            }
            val h = md.digest().joinToString("") { "%02x".format(it) }
            val ok = h == expectedHash
            emit("verify $label".padEnd(20) + ": $total bytes OK, sha256 " +
                    (if (ok) "matches" else "*** DIFFERS ***") + " (${ms(ns)})")
            return ok
        }

        var allOk = verifyPass("(cached)")

        val cache = drive.blockDevice as? app.fayaz.otgmaster.block.CachedBlockDevice
        if (cache != null) {
            cache.invalidate()
            allOk = verifyPass("(cache dropped)") && allOk
            emit("              ${cache.stats()}")
        } else {
            emit("              (no cache in the stack — device read not isolated)")
        }

        // Full unmount/remount: discards filesystem metadata held in memory, which
        // a cache-only invalidation leaves intact.
        if (mount != null && OtgMasterState.unmountAllRequest != null) {
            emit("              remounting to discard filesystem metadata…")
            OtgMasterState.unmountAllRequest?.invoke()
            var deadline = System.currentTimeMillis() + 30_000
            while (OtgMasterState.mountedDrives.isNotEmpty() &&
                    System.currentTimeMillis() < deadline) Thread.sleep(300)
            Thread.sleep(1500)
            OtgMasterState.mountRequest?.mount(mount.password, mount.pim, mount.cipher, mount.hash)
            deadline = System.currentTimeMillis() + MOUNT_TIMEOUT_MS
            while (OtgMasterState.mountedDrives.isEmpty() &&
                    System.currentTimeMillis() < deadline) Thread.sleep(500)
            if (liveRoot() == null) {
                emit("verify (remounted)  : *** remount failed, could not verify ***")
                allOk = false
            } else {
                allOk = verifyPass("(remounted)") && allOk
            }
        } else {
            emit("              (no credentials — skipped the remount verify)")
        }

        emit("write verify  : ${if (allOk) "ALL PASSED" else "*** FAILURES ABOVE ***"}")
        runCatching {
            liveRoot()?.search(dirName)?.let { deleteRecursively(it) }
        }.onFailure { emit("write verify  : could not remove $dirName: $it") }
    }

    /**
     * Records the conditions the run happened under.
     *
     * Decryption is CPU-bound, so Doze makes everything uniformly slower: a run
     * taken with the screen off measured the block-layer control at 5.05 MB/s
     * against 22.03 MB/s awake. Without this in the output a throttled run looks
     * like a regression, which is how it was first misread.
     */
    private fun emitPowerState(context: Context, emit: (String) -> Unit) {
        val pm = context.getSystemService(android.os.PowerManager::class.java)
        val interactive = runCatching { pm?.isInteractive }.getOrNull()
        val idle = runCatching { pm?.isDeviceIdleMode }.getOrNull()
        val saver = runCatching { pm?.isPowerSaveMode }.getOrNull()
        val freqs = (0 until 8).mapNotNull { n ->
            runCatching {
                File("/sys/devices/system/cpu/cpu$n/cpufreq/scaling_cur_freq")
                    .readText().trim().toLong() / 1000
            }.getOrNull()
        }
        emit("power : interactive=$interactive deviceIdle=$idle powerSave=$saver")
        if (freqs.isNotEmpty()) emit("cpu   : ${freqs.joinToString(", ")} MHz")
        if (idle == true || interactive == false || saver == true) {
            emit("*** DEVICE IS IDLE OR THROTTLED — throughput here is not comparable")
            emit("*** to an awake run; wake the screen and disable Doze before measuring")
        }
    }

    /**
     * Unaligned writes and truncate-rewrite — the two cases the aligned write
     * verification cannot see.
     *
     * benchWriteVerify writes 64 KiB chunks at 64 KiB offsets, so every write is a
     * whole number of sectors. That is precisely the shape that hides
     * ByteBlockDevice's trailing-sector bug (docs/VENDOR_FIXES.md V1), where a write
     * whose length is not a multiple of blockSize zero-fills the rest of the final
     * sector instead of preserving what is on disk. 32/32 aligned cases passed while
     * that bug sat in the write path.
     *
     * Case A (tail preservation): fill a region with 0xFF, then overwrite only the
     * first 10 bytes with 0xAA. Everything after byte 10 must still be 0xFF.
     *
     * Case B (truncate then rewrite): write, truncate to 0, write again, then remount
     * and read. Exercises the stale entry.startCluster path (V2), where the directory
     * entry keeps pointing at a freed cluster.
     *
     * Both are read back after a remount, because an in-memory chain or cached node
     * can make a corrupt on-disk structure look correct.
     */
    private fun benchUnaligned(
        drive: MountedDrive,
        emit: (String) -> Unit,
        mount: MountCredentials? = null,
    ) {
        val root = drive.fileSystem.rootDirectory
        val dirName = "BENCH_UNALIGNED"
        runCatching { root.search(dirName)?.let { deleteRecursively(it) } }
        val dir = root.createDirectory(dirName)

        fun liveRoot(): UsbFile? = OtgMasterState.mountedDrives
            .firstOrNull { (it.fileSystem as? ExFatFileSystem)?.isUnmounted != true }
            ?.fileSystem?.rootDirectory

        fun remount(): Boolean {
            if (mount == null) return false
            OtgMasterState.unmountAllRequest?.invoke()
            var deadline = System.currentTimeMillis() + 30_000
            while (OtgMasterState.mountedDrives.isNotEmpty() &&
                    System.currentTimeMillis() < deadline) Thread.sleep(300)
            Thread.sleep(1500)
            OtgMasterState.mountRequest?.mount(mount.password, mount.pim, mount.cipher, mount.hash)
            deadline = System.currentTimeMillis() + MOUNT_TIMEOUT_MS
            while (liveRoot() == null && System.currentTimeMillis() < deadline) Thread.sleep(500)
            return liveRoot() != null
        }

        fun readBack(name: String, expectLen: Long): ByteArray? {
            val f = liveRoot()?.search(dirName)?.search(name) ?: run {
                emit("unaligned     : *** $name not found after remount ***"); return null
            }
            if (f.length != expectLen) {
                emit("unaligned     : *** $name length ${f.length}, expected $expectLen ***")
                return null
            }
            val bb = ByteBuffer.allocate(expectLen.toInt())
            f.read(0, bb)
            if (bb.position() != expectLen.toInt()) {
                emit("unaligned     : *** $name short read ${bb.position()} of $expectLen ***")
                return null
            }
            return bb.array()
        }

        // ---- Case A: does an unaligned overwrite destroy the rest of the sector?
        val size = 4096
        val tailFile = "tail.bin"
        runCatching {
            val f = dir.createFile(tailFile)
            f.write(0, ByteBuffer.wrap(ByteArray(size) { 0xFF.toByte() }))
            f.flush()
            // 10 bytes: deliberately not a sector multiple, and not sector-aligned in
            // length, which is what triggers the zero-pad path.
            f.write(0, ByteBuffer.wrap(ByteArray(10) { 0xAA.toByte() }))
            f.flush()
            f.close()
        }.onFailure { emit("unaligned     : case A write FAILED $it"); }

        // ---- Case B: truncate to zero, rewrite, and see if the chain survives.
        val truncFile = "trunc.bin"
        val second = ByteArray(2048) { i -> (i % 251).toByte() }
        runCatching {
            val f = dir.createFile(truncFile)
            f.write(0, ByteBuffer.wrap(ByteArray(8192) { 0x5A.toByte() }))
            f.flush()
            f.length = 0
            f.write(0, ByteBuffer.wrap(second.copyOf()))
            f.flush()
            f.close()
        }.onFailure { emit("unaligned     : case B write FAILED $it") }

        if (!remount()) {
            emit("unaligned     : *** remount failed, cannot judge on-disk state ***")
            return
        }

        readBack(tailFile, size.toLong())?.let { got ->
            val firstBad = (10 until size).firstOrNull { got[it] != 0xFF.toByte() }
            val headOk = (0 until 10).all { got[it] == 0xAA.toByte() }
            when {
                !headOk -> emit("unaligned     : *** case A head not written ***")
                firstBad == null -> emit("unaligned     : case A PASS — sector tail preserved")
                else -> {
                    val zeros = (10 until size).count { got[it] == 0.toByte() }
                    emit("unaligned     : *** case A FAIL — tail corrupted from byte " +
                         "$firstBad (got 0x%02X, $zeros of ${size - 10} bytes zeroed) ***"
                             .format(got[firstBad]))
                }
            }
        }

        readBack(truncFile, second.size.toLong())?.let { got ->
            val bad = second.indices.firstOrNull { got[it] != second[it] }
            if (bad == null) emit("unaligned     : case B PASS — truncate+rewrite survived remount")
            else emit("unaligned     : *** case B FAIL — mismatch at byte $bad " +
                      "(got 0x%02X, expected 0x%02X) ***".format(got[bad], second[bad]))
        }

        runCatching { liveRoot()?.search(dirName)?.let { deleteRecursively(it) } }
    }

    /** Depth-first delete; a non-empty directory cannot be removed directly. */
    private fun deleteRecursively(file: UsbFile) {
        if (file.isDirectory) {
            runCatching { file.listFiles() }.getOrNull()
                ?.forEach { runCatching { deleteRecursively(it) } }
        }
        file.delete()
    }

    private fun ms(ns: Long) = "%.1f ms".format(ns / 1_000_000.0)
    private fun mbps(bytes: Long, ns: Long) =
        "%.2f MB/s (%s)".format(bytes / 1_048_576.0 / (ns / 1_000_000_000.0), ms(ns))

    private fun save(context: Context, text: String) {
        runCatching {
            val dir = context.getExternalFilesDir(null) ?: context.filesDir
            File(dir, "benchmark.txt").writeText(text)
            Log.i(TAG, "results written to ${File(dir, "benchmark.txt").absolutePath}")
        }.onFailure { Log.e(TAG, "could not save results", it) }
    }
}
