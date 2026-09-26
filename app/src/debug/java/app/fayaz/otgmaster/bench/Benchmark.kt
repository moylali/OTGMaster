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
        // A report retrieved days later has to say which code produced it. This
        // session already had one A/B invalidated by an un-rebuilt APK, caught only
        // by comparing file hashes afterwards.
        emit("build: ${app.fayaz.otgmaster.BuildConfig.VERSION_NAME} " +
             "(${app.fayaz.otgmaster.BuildConfig.VERSION_CODE}) " +
             "commit ${app.fayaz.otgmaster.BuildConfig.GIT_COMMIT}")
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
            emit("")
            emit("--- conditions at the end of the run ---")
            emitPowerState(context, ::emit)
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
            if (only.contains("correct")) runCatching { benchCorrectness(drive, ::emit, mount) }
                .onFailure { emit("correctness   : FAILED ${it}") }
            if (only.contains("saf")) runCatching { benchSaf(context, ::emit) }
                .onFailure { emit("saf           : FAILED ${it}") }
            // Opt-in: hashing a 2 GiB fixture takes minutes.
            if (only.contains("fixtures")) runCatching { benchFixtures(drive, ::emit) }
                .onFailure { emit("fixtures      : FAILED ${it}") }
            emit("")
        }

        emit("")
        emit("--- conditions at the end of the run ---")
        emitPowerState(context, ::emit)
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
        val root = OtgMasterState.mountedDrives.firstOrNull()?.fileSystem?.rootDirectory
            ?: drive.fileSystem.rootDirectory
        val dir = freshDir(root, "BENCH_UNALIGNED")
        val dirName = dir.name

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
            // Report the length mismatch rather than trusting it: a corrupt directory
            // entry can claim an absurd size, and allocating it OOMs the process. A
            // 178 MB claim for a 2 KiB file is how the stale-startCluster defect first
            // showed itself here.
            if (f.length != expectLen) {
                emit("unaligned     : *** $name length ${f.length}, expected $expectLen " +
                     "— directory entry is wrong ***")
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

    /**
     * Data-correctness suite. Latency is not the point here; surviving a remount is.
     *
     * The latency-oriented write check reported 32/32 passing while two real
     * corruption defects sat in the write path, because it only ever wrote aligned
     * 64 KiB chunks of a repeating pattern. These cases are chosen for what they can
     * destroy, and every one of them is judged only after a full unmount/remount, so
     * in-memory metadata cannot vouch for a corrupt on-disk structure.
     *
     *  A. neighbour  — write unaligned to one file, prove a second file is untouched.
     *                  V1's blast radius reached past the file being written.
     *  B. offsets    — writes at unaligned offsets AND unaligned lengths, which take
     *                  the read-modify-write path on both ends of the range.
     *  C. appends    — many small appends, then verify the whole file. Catches a stale
     *                  cached size and a mis-grown cluster chain.
     *  D. reuse      — delete a file, create another, and prove no old content shows
     *                  through the recycled clusters.
     */
    private fun benchCorrectness(
        drive: MountedDrive,
        emit: (String) -> Unit,
        mount: MountCredentials? = null,
    ) {
        var dirName = "BENCH_CORRECT"
        var failures = 0
        fun bad(msg: String) { emit("correctness   : *** $msg ***"); failures++ }
        fun good(msg: String) = emit("correctness   : $msg")

        fun liveRoot(): UsbFile? = OtgMasterState.mountedDrives
            .firstOrNull { (it.fileSystem as? ExFatFileSystem)?.isUnmounted != true }
            ?.fileSystem?.rootDirectory

        fun remount(): Boolean {
            if (mount == null) return false
            OtgMasterState.unmountAllRequest?.invoke()
            var dl = System.currentTimeMillis() + 30_000
            while (OtgMasterState.mountedDrives.isNotEmpty() && System.currentTimeMillis() < dl)
                Thread.sleep(300)
            Thread.sleep(1500)
            OtgMasterState.mountRequest?.mount(mount.password, mount.pim, mount.cipher, mount.hash)
            dl = System.currentTimeMillis() + MOUNT_TIMEOUT_MS
            while (liveRoot() == null && System.currentTimeMillis() < dl) Thread.sleep(500)
            return liveRoot() != null
        }

        fun sha(b: ByteArray): String = java.security.MessageDigest.getInstance("SHA-256")
            .digest(b).joinToString("") { "%02x".format(it) }.take(16)

        /** Reads a whole file, refusing to trust an absurd reported length. */
        fun slurp(name: String, expect: Int): ByteArray? {
            val f = liveRoot()?.search(dirName)?.search(name)
                ?: return null.also { bad("$name missing after remount") }
            if (f.length != expect.toLong()) {
                bad("$name length ${f.length}, expected $expect — directory entry is wrong")
                return null
            }
            val bb = ByteBuffer.allocate(expect)
            f.read(0, bb)
            if (bb.position() != expect) {
                bad("$name short read ${bb.position()} of $expect"); return null
            }
            return bb.array()
        }

        // Resolve the root from the live mount, not from the drive runAll captured
        // before the loop: an earlier section (benchUnaligned) remounts, which
        // replaces the entry in mountedDrives and leaves the captured drive pointing
        // at a closed block device. Every call through it then fails, which looked
        // like "could not create a working directory".
        val root = liveRoot() ?: run { bad("no live mount — cannot run"); return }
        val dir = freshDir(root, dirName)
        dirName = dir.name

        // ---------- build the fixtures ----------
        val neighbour = ByteArray(4096) { 0x42 }        // must survive untouched
        val victimLen = 4096
        val offsets = ByteArray(8192) { i -> (i % 253).toByte() }
        val appendTotal = 100
        val appendChunk = 37                             // deliberately odd
        val reuseOld = ByteArray(4096) { 0xC3.toByte() }

        runCatching {
            dir.createFile("neighbour.bin").apply {
                write(0, ByteBuffer.wrap(neighbour.copyOf())); flush(); close()
            }
            // A: unaligned write into victim, neighbour must not move
            dir.createFile("victim.bin").apply {
                write(0, ByteBuffer.wrap(ByteArray(victimLen) { 0xFF.toByte() }))
                flush()
                write(0, ByteBuffer.wrap(ByteArray(10) { 0xAA.toByte() }))
                flush(); close()
            }
            // B: unaligned offsets and lengths
            dir.createFile("offsets.bin").apply {
                write(0, ByteBuffer.wrap(offsets.copyOf())); flush()
                // 700 bytes at offset 1234: unaligned at both ends.
                val patch = ByteArray(700) { 0x11 }
                write(1234, ByteBuffer.wrap(patch))
                for (i in 0 until 700) offsets[1234 + i] = 0x11
                flush(); close()
            }
            // C: many small odd-sized appends
            dir.createFile("appends.bin").apply {
                for (n in 0 until appendTotal) {
                    write(n.toLong() * appendChunk,
                          ByteBuffer.wrap(ByteArray(appendChunk) { (n % 251).toByte() }))
                }
                flush(); close()
            }
            // D: write then delete, so the clusters are recycled
            dir.createFile("gone.bin").apply {
                write(0, ByteBuffer.wrap(reuseOld.copyOf())); flush(); close()
            }
            dir.search("gone.bin")?.let { deleteRecursively(it) }
            dir.createFile("fresh.bin").apply {
                write(0, ByteBuffer.wrap(ByteArray(4096) { 0x07 })); flush(); close()
            }
            // E: fill clusters with a marker, free them, then put a tiny file where
            // they were. A read longer than the file must not return the marker.
            dir.createFile("slackfill.bin").apply {
                write(0, ByteBuffer.wrap(ByteArray(64 * 1024) { 0xDD.toByte() }))
                flush(); close()
            }
            dir.search("slackfill.bin")?.let { deleteRecursively(it) }
            dir.createFile("tiny.bin").apply {
                write(0, ByteBuffer.wrap(ByteArray(TINY_LEN) { 0xE7.toByte() }))
                flush(); close()
            }
        }.onFailure { bad("fixture setup failed: $it") }

        if (!remount()) { bad("remount failed — cannot judge on-disk state"); return }

        // ---------- judge ----------
        slurp("neighbour.bin", neighbour.size)?.let {
            val i = it.indices.firstOrNull { k -> it[k] != neighbour[k] }
            if (i == null) good("A neighbour intact — unaligned write stayed in its own file")
            else bad("A neighbour CORRUPTED at byte $i (0x%02X, expected 0x42)".format(it[i]))
        }
        slurp("victim.bin", victimLen)?.let {
            val head = (0 until 10).all { k -> it[k] == 0xAA.toByte() }
            val tail = (10 until victimLen).firstOrNull { k -> it[k] != 0xFF.toByte() }
            when {
                !head -> bad("A victim head not written")
                tail != null -> bad("A victim tail zeroed from byte $tail " +
                    "(${(10 until victimLen).count { k -> it[k] == 0.toByte() }} bytes lost)")
                else -> good("A victim tail preserved")
            }
        }
        slurp("offsets.bin", offsets.size)?.let {
            val i = it.indices.firstOrNull { k -> it[k] != offsets[k] }
            if (i == null) good("B unaligned offset+length write correct (sha ${sha(it)})")
            else bad("B mismatch at byte $i (0x%02X, expected 0x%02X)".format(it[i], offsets[i]))
        }
        slurp("appends.bin", appendTotal * appendChunk)?.let {
            val i = it.indices.firstOrNull { k -> it[k] != ((k / appendChunk) % 251).toByte() }
            if (i == null) good("C $appendTotal odd-sized appends correct")
            else bad("C append mismatch at byte $i")
        }
        slurp("fresh.bin", 4096)?.let {
            val leaked = it.indices.firstOrNull { k -> it[k] == 0xC3.toByte() }
            val wrong = it.indices.firstOrNull { k -> it[k] != 0x07.toByte() }
            when {
                leaked != null -> bad("D deleted file's content leaked into new file at byte $leaked")
                wrong != null -> bad("D fresh file wrong at byte $wrong (0x%02X)".format(it[wrong]))
                else -> good("D recycled clusters clean")
            }
        }

        // ---------- E: does an over-long read leak cluster slack? ----------
        //
        // On an encrypted volume this is an information-disclosure question, not a
        // tidiness one: slack holds the decrypted plaintext of whatever previously
        // occupied the cluster. A caller asking for 4 KiB of a 100-byte file must get
        // 100 bytes, not the rest of the cluster.
        val tinyFile = liveRoot()?.search(dirName)?.search("tiny.bin")
        if (tinyFile == null) bad("E tiny.bin missing after remount")
        else {
            if (tinyFile.length != TINY_LEN.toLong()) {
                bad("E tiny.bin length ${tinyFile.length}, expected $TINY_LEN")
            }
            val over = ByteBuffer.allocate(4096)
            val outcome = runCatching { tinyFile.read(0, over) }
            when {
                outcome.isFailure ->
                    bad("E over-long read threw ${outcome.exceptionOrNull()}")
                over.position() > TINY_LEN -> {
                    val leaked = (TINY_LEN until over.position())
                        .count { over.array()[it] == 0xDD.toByte() }
                    bad("E slack leaked: asked 4096 of a $TINY_LEN-byte file, got " +
                        "${over.position()} bytes, $leaked of them the freed marker 0xDD")
                }
                over.position() < TINY_LEN -> bad("E short read ${over.position()}")
                else -> good("E over-long read bounded to the file (no slack leak)")
            }
            // Reading at or past EOF must be a clean no-op, not an exception.
            val past = ByteBuffer.allocate(512)
            val atEof = runCatching { tinyFile.read(TINY_LEN.toLong() + 8, past) }
            if (atEof.isFailure) bad("E read past EOF threw ${atEof.exceptionOrNull()}")
            else if (past.position() != 0) bad("E read past EOF returned ${past.position()} bytes")
            else good("E read past EOF is a clean no-op")
            runCatching { tinyFile.close() }
        }

        // ---------- F: does a read-only open write to the disk? ----------
        //
        // FAT32's close() flushed unconditionally, and read() touches the access
        // time, so purely reading a file rewrote the whole parent directory table.
        // ExFatFile already tracks a dirty flag for exactly this.
        val cache = OtgMasterState.mountedDrives.firstOrNull()?.blockDevice
                as? app.fayaz.otgmaster.block.CachedBlockDevice
        if (cache == null) emit("correctness   : F skipped (no cache in the stack)")
        else {
            val target = liveRoot()?.search(dirName)?.search("offsets.bin")
            if (target == null) bad("F offsets.bin missing")
            else {
                val before = cache.delegateBlocksWritten
                val bb = ByteBuffer.allocate(512)
                runCatching { target.read(0, bb); target.close() }
                val wrote = cache.delegateBlocksWritten - before
                if (wrote == 0L) good("F read-only open wrote nothing to the device")
                else bad("F read-only open wrote $wrote block(s) to the device")
            }
        }

        // ---------- G: does reading a large existing file still work? ----------
        //
        // Every fixture above is 4-8 KiB, which is why a 2 GiB read regression passed
        // all of them: (length - offset).toInt() overflowed to negative at exactly
        // 2 GiB and returned zero bytes for any file that size. Only the SAF section
        // read something big enough to notice. Check it here, cheaply, by sampling
        // rather than reading the whole file.
        val big = liveRoot()?.search("BENCH")?.search("large")?.listFiles()
            ?.maxByOrNull { runCatching { it.length }.getOrDefault(0L) }
        if (big == null) emit("correctness   : G skipped (BENCH/large missing)")
        else {
            val len = runCatching { big.length }.getOrDefault(-1L)
            if (len <= 0) bad("G ${big.name} reports length $len")
            else {
                // Near the start, straddling 2 GiB if the file reaches it, and at the
                // very end — the offsets where narrowing bugs bite.
                val probes = listOfNotNull(
                    0L,
                    (len / 2) and 0xFFFFF000L.inv().inv(),
                    if (len > 2L * 1024 * 1024 * 1024) 2L * 1024 * 1024 * 1024 - 4096 else null,
                    maxOf(0L, len - 4096),
                )
                var ok = true
                for (off in probes) {
                    val want = minOf(4096L, len - off).toInt()
                    if (want <= 0) continue
                    val bb = ByteBuffer.allocate(want)
                    val r = runCatching { big.read(off, bb) }
                    when {
                        r.isFailure -> { bad("G read at $off threw ${r.exceptionOrNull()}"); ok = false }
                        bb.position() != want -> {
                            bad("G read at $off returned ${bb.position()} of $want bytes"); ok = false
                        }
                    }
                }
                val past = ByteBuffer.allocate(512)
                val eof = runCatching { big.read(len, past) }
                if (eof.isFailure) { bad("G read at EOF threw ${eof.exceptionOrNull()}"); ok = false }
                else if (past.position() != 0) { bad("G read at EOF returned ${past.position()} bytes"); ok = false }
                if (ok) good("G large file (${len / 1024 / 1024} MiB) reads correctly at all probes")
                runCatching { big.close() }
            }
        }

        emit("correctness   : ${if (failures == 0) "ALL PASSED" else "$failures FAILURE(S) ABOVE"}")
        runCatching { liveRoot()?.search(dirName)?.let { deleteRecursively(it) } }
    }

    /** Small enough to sit well inside one cluster, so slack is large if leaked. */
    private const val TINY_LEN = 100

    /**
     * Measures I/O **through** the DocumentsProvider, not around it.
     *
     * Every other section in this file calls UsbFile directly. No real client does
     * that: a SAF client's read crosses ContentResolver, the provider, a
     * ProxyFileDescriptor and the FUSE bridge, and lands on a
     * ProxyFileDescriptorCallback dispatched on one process-wide HandlerThread. So
     * every number this harness has ever produced describes a path nothing actually
     * uses, and the serialisation that a real client hits was invisible.
     *
     * Three measurements:
     *   1. single stream through SAF, against the same file read directly, in the
     *      same session — the provider's own overhead;
     *   2. two streams in parallel on the SAME drive — expected to show no gain,
     *      since libaums is not thread-safe and exFAT holds a filesystem-wide lock;
     *   3. two streams in parallel across DIFFERENT drives — the case the single
     *      shared handler penalises for no reason. If aggregate throughput here is
     *      flat against the single-stream figure, the shared handler is the cause and
     *      a per-drive handler should lift it.
     *
     * Case 3 needs two drives mounted (a powered hub), and is skipped otherwise.
     */
    private fun benchSaf(context: Context, emit: (String) -> Unit) {
        val cr = context.contentResolver
        val bufSize = 64 * 1024
        val capBytes = 32L * 1024 * 1024   // cap so a slow path cannot run for hours

        fun docUri(driveId: String, path: String): android.net.Uri =
            android.provider.DocumentsContract.buildDocumentUri(
                app.fayaz.otgmaster.provider.VeraCryptDocumentProvider.AUTHORITY,
                "$driveId:$path")

        /** Reads up to capBytes through SAF; returns bytes read, or -1 on failure. */
        fun safRead(driveId: String, path: String): Long {
            return try {
                cr.openFileDescriptor(docUri(driveId, path), "r").use { pfd ->
                    if (pfd == null) return -1
                    java.io.FileInputStream(pfd.fileDescriptor).use { ins ->
                        val buf = ByteArray(bufSize)
                        var total = 0L
                        while (total < capBytes) {
                            val n = ins.read(buf)
                            if (n <= 0) break
                            total += n
                        }
                        total
                    }
                }
            } catch (e: Throwable) {
                emit("saf           : read failed for $path: $e")
                -1
            }
        }

        /** Largest file under BENCH/large on a drive, as a provider path. */
        fun largePath(drive: MountedDrive): String? = runCatching {
            val large = drive.fileSystem.rootDirectory.search("BENCH")?.search("large")
                ?: return@runCatching null
            val f = large.listFiles().maxByOrNull { runCatching { it.length }.getOrDefault(0L) }
                ?: return@runCatching null
            "/BENCH/large/${f.name}"
        }.getOrNull()

        val drives = OtgMasterState.mountedDrives.toList()
        if (drives.isEmpty()) return emit("saf           : no drives mounted")

        // ---- 1. single stream: through SAF vs direct, same file, same session
        val d0 = drives[0]
        val p0 = largePath(d0) ?: return emit("saf           : BENCH/large missing")

        val safNs = measureNanoTime { }.let {
            var n = 0L
            val t = measureNanoTime { n = safRead(d0.id, p0) }
            if (n <= 0) return emit("saf           : single-stream read produced nothing")
            emit("saf single    : ${mbps(n, t)} through the provider ($p0)")
            t
        }

        var directBytes = 0L
        val directNs = measureNanoTime {
            val f = d0.fileSystem.rootDirectory.search("BENCH")?.search("large")
                ?.search(p0.substringAfterLast('/'))
            if (f != null) {
                val bb = ByteBuffer.allocate(bufSize)
                while (directBytes < capBytes) {
                    bb.clear()
                    f.read(directBytes, bb)
                    if (bb.position() <= 0) break
                    directBytes += bb.position()
                    if (bb.position() < bufSize) break
                }
            }
        }
        if (directBytes > 0) {
            emit("saf direct    : ${mbps(directBytes, directNs)} calling UsbFile directly")
            emit("saf overhead  : provider costs %.2fx".format(
                safNs.toDouble() / directNs.toDouble()))
        }

        /** Runs [tasks] in parallel, returns total bytes and wall time. */
        fun parallel(tasks: List<() -> Long>): Pair<Long, Long> {
            val results = LongArray(tasks.size)
            val threads = tasks.mapIndexed { i, t ->
                Thread { results[i] = t() }.apply { name = "SafBench$i" }
            }
            val ns = measureNanoTime {
                threads.forEach { it.start() }
                threads.forEach { it.join() }
            }
            return results.filter { it > 0 }.sum() to ns
        }

        // ---- 2. two streams, same drive
        val large0 = runCatching {
            d0.fileSystem.rootDirectory.search("BENCH")?.search("large")?.listFiles()
                ?.sortedByDescending { runCatching { it.length }.getOrDefault(0L) }
        }.getOrNull()
        if (large0 != null && large0.size >= 2) {
            val a = "/BENCH/large/${large0[0].name}"
            val b = "/BENCH/large/${large0[1].name}"
            val (bytes, ns) = parallel(listOf({ safRead(d0.id, a) }, { safRead(d0.id, b) }))
            emit("saf par same  : ${mbps(bytes, ns)} aggregate, 2 streams on one drive")
        } else {
            emit("saf par same  : needs 2 files in BENCH/large, skipped")
        }

        // ---- 3. two streams, different drives — the multi-drive question
        if (drives.size >= 2) {
            val d1 = drives[1]
            val p1 = largePath(d1)
            if (p1 == null) {
                emit("saf par cross : second drive has no BENCH/large, skipped")
            } else {
                val (bytes, ns) = parallel(listOf({ safRead(d0.id, p0) }, { safRead(d1.id, p1) }))
                emit("saf par cross : ${mbps(bytes, ns)} aggregate across 2 drives")
                emit("              (flat against 'saf single' means the shared " +
                     "ProxyFileDescriptorThread is the limit, not the bus)")
            }
        } else {
            emit("saf par cross : only ${drives.size} drive mounted — attach a second " +
                 "via a powered hub to measure cross-drive concurrency")
        }
    }

    /**
     * Verifies the drive's fixtures against hashes computed on the **host**.
     *
     * This is the one check in this file that does not grade its own homework. The
     * correctness suite generates expected content and compares against that, which
     * proves the data written is the data read back — but a systematic fault in the
     * generator, or in the read path applied consistently to both, would be invisible
     * to it. BENCH/MANIFEST.txt carries SHA-256 values produced by
     * scripts/prepare_test_usb.sh on a Mac, through a completely different code path,
     * so agreement means something these other cases cannot establish.
     *
     * It is also what makes an offline run on a phone with no adb trustworthy: the
     * verdict does not depend on anything the phone computed for itself.
     *
     * Directory lines are hashed over the sorted "name<TAB>size" listing rather than
     * file contents, which pins the entry count and every name and length — what the
     * dense directories exist to stress — without reading 10,000 files.
     *
     * Hashing 2 GiB at the measured ~15 MB/s takes minutes, so this is opt-in
     * ("fixtures") rather than part of a default run.
     */
    private fun benchFixtures(drive: MountedDrive, emit: (String) -> Unit) {
        val root = OtgMasterState.mountedDrives.firstOrNull()?.fileSystem?.rootDirectory
            ?: drive.fileSystem.rootDirectory
        val bench = root.search("BENCH") ?: return emit("fixtures      : BENCH/ not found")
        val manifest = bench.search("MANIFEST.txt")
            ?: return emit("fixtures      : MANIFEST.txt missing — re-run prepare_test_usb.sh")

        val text = runCatching {
            val bb = ByteBuffer.allocate(manifest.length.toInt().coerceAtMost(256 * 1024))
            manifest.read(0, bb)
            String(bb.array(), 0, bb.position())
        }.getOrNull()
        if (text == null) return emit("fixtures      : could not read MANIFEST.txt")

        var checked = 0
        var failed = 0
        var skipped = 0

        var errors = 0
        for (raw in text.lines()) {
            val line = raw.trim()
            if (line.isEmpty() || line.startsWith("#")) continue
            val parts = line.split('\t')
            if (parts.size < 3) continue
            val (path, sizeField, expected) = Triple(parts[0], parts[1], parts[2].trim())
            if (expected == "-" || expected.length != 64) {
                skipped++
                continue
            }

            if (path.endsWith("/")) {
                // A directory line must record a count, as "<N> files". The older
                // manifest format wrote `nested/<TAB>10 levels<TAB><hash of one file>`,
                // where the hash is of a file's contents rather than of a listing —
                // comparing the two produces a mismatch that looks like corruption and
                // is not. Skip those and say so.
                if (!sizeField.trim().endsWith("files")) {
                    emit("fixtures      : $path skipped — manifest predates this check " +
                         "(re-run scripts/prepare_test_usb.sh to hash fixtures)")
                    skipped++
                    continue
                }
                // Directory: hash the sorted name<TAB>size listing.
                val dir = bench.search(path.trimEnd('/'))
                if (dir == null) { emit("fixtures      : *** ${path} missing ***"); failed++; continue }
                val entries = runCatching { dir.listFiles() }.getOrNull()
                if (entries == null) {
                    emit("fixtures      : *** $path could not be listed ***")
                    failed++
                } else {
                    // Subdirectories have no length — libaums throws
                    // UnsupportedOperationException("This is a directory!") — and the
                    // host cannot produce a comparable value for one either, so they
                    // are listed by name alone. Found by running this against a
                    // manifest whose nested/ line pointed at a directory of
                    // directories, which threw rather than reporting a mismatch.
                    val listing = entries.map {
                        if (it.isDirectory) "${it.name}\tdir"
                        else "${it.name}\t${runCatching { it.length }.getOrDefault(-1L)}"
                    }.sorted().joinToString("\n")
                    val got = sha256Of(listing.toByteArray())
                    checked++
                    if (got == expected) {
                        emit("fixtures      : $path listing matches (${entries.size} entries)")
                    } else {
                        emit("fixtures      : *** $path LISTING DIFFERS — expected " +
                             "${expected.take(16)}…, got ${got.take(16)}… ***")
                        failed++
                    }
                }
                continue
            }

            val file = bench.search(path)
            if (file == null) { emit("fixtures      : *** $path missing ***"); failed++; continue }
            val wantBytes = sizeField.toLongOrNull()
            if (wantBytes != null && file.length != wantBytes) {
                emit("fixtures      : *** $path is ${file.length} bytes, manifest says $wantBytes ***")
                failed++
                continue
            }
            val digest = java.security.MessageDigest.getInstance("SHA-256")
            val buf = ByteBuffer.allocate(256 * 1024)
            var off = 0L
            val ns = measureNanoTime {
                while (off < file.length) {
                    buf.clear()
                    file.read(off, buf)
                    if (buf.position() <= 0) break
                    digest.update(buf.array(), 0, buf.position())
                    off += buf.position()
                }
            }
            runCatching { file.close() }
            checked++
            val got = digest.digest().joinToString("") { "%02x".format(it) }
            if (got == expected && off == file.length) {
                emit("fixtures      : $path matches the host hash (${mbps(off, ns)})")
            } else {
                emit("fixtures      : *** $path DIFFERS — read $off of ${file.length} bytes, " +
                     "expected ${expected.take(16)}…, got ${got.take(16)}… ***")
                failed++
            }
        }

        if (errors > 0) emit("fixtures      : $errors manifest line(s) could not be read")
        emit("fixtures      : " + when {
            checked == 0 -> "nothing to check — manifest has no hashes (old prepare script?)"
            failed == 0 -> "ALL $checked MATCHED the host-computed hashes" +
                           if (skipped > 0) " ($skipped entry/entries skipped — see above)" else ""
            else -> "*** $failed of $checked FAILED ***"
        })
    }

    /**
     * Appends one line per run to BENCH/reports/INDEX.txt.
     *
     * Several runs can be done before the drive is next plugged into a desktop, so
     * each report is its own timestamped file and nothing is overwritten. The index
     * exists so the whole set can be read at a glance — which runs happened, on what
     * build, and whether the integrity checks passed — without opening each file.
     *
     * Appends rather than rewrites: a later run must not be able to lose an earlier
     * run's record, and a run that dies midway should still leave the runs before it
     * intact.
     */
    private fun appendIndex(reports: UsbFile, reportName: String, text: String) {
        runCatching {
            fun verdict(prefix: String, needle: String): String = text.lines()
                .lastOrNull { it.startsWith(prefix) && it.contains(needle) }
                ?.substringAfter(needle)?.trim()?.take(40) ?: "-"

            val correctness = verdict("correctness   :", ":")
            val fixtures = verdict("fixtures      :", ":")
            val contaminated = if (text.contains("deviceIdle=true") ||
                                   text.contains("interactive=false")) "DOZED" else "awake"
            val line = listOf(
                reportName,
                android.os.Build.MODEL,
                "Android ${android.os.Build.VERSION.RELEASE}",
                "commit ${app.fayaz.otgmaster.BuildConfig.GIT_COMMIT}",
                contaminated,
                "correctness: $correctness",
                "fixtures: $fixtures",
            ).joinToString("  |  ") + "\n"

            val index = reports.search("INDEX.txt") ?: reports.createFile("INDEX.txt")
            val at = index.length
            index.write(at, ByteBuffer.wrap(line.toByteArray()))
            index.flush()
            index.close()
            // State the running total in the log and in the next report, so "did my
            // earlier runs survive?" is answerable without pulling the drive.
            val kept = runCatching { reports.listFiles().count { !it.isDirectory } }.getOrDefault(-1)
            Log.i(TAG, "index updated: BENCH/reports/INDEX.txt — " +
                       "$kept file(s) now in BENCH/reports/")
        }.onFailure { Log.w(TAG, "could not update the report index: $it") }
    }

    private fun sha256Of(bytes: ByteArray): String =
        java.security.MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it) }

    /**
     * Returns an empty directory named [base], tolerating leftovers.
     *
     * A previous run that died mid-way (an OOM from a corrupt directory entry, an
     * app kill) leaves the directory behind with files in it, and createDirectory
     * then fails with "Item already exists!". Delete what is there; if that cannot
     * be done, fall back to a suffixed name rather than abandoning the run.
     */
    private fun freshDir(root: UsbFile, base: String): UsbFile {
        runCatching { root.search(base)?.let { deleteRecursively(it) } }
        runCatching { return root.createDirectory(base) }
        for (n in 1..20) {
            val name = "${base}_$n"
            runCatching { root.search(name)?.let { deleteRecursively(it) } }
            runCatching { return root.createDirectory(name) }
        }
        throw java.io.IOException("could not create a working directory for $base")
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

    /**
     * Writes the report where it can be retrieved without adb.
     *
     * Three destinations, because each fails differently:
     *
     *  1. **The mounted USB drive**, at BENCH/reports/. This is the one that matters
     *     for a device with no working adb: the drive is already being carried to a
     *     desktop, the report travels with it, and it sits next to the fixtures it
     *     describes. Skipped when nothing is mounted, or when the run is what broke
     *     the mount.
     *  2. **Shared `Documents/`**, via MediaStore, so it is visible over MTP and to any
     *     file manager. getExternalFilesDir is not: Android/data/ is unreadable over
     *     MTP from Android 11 on, which makes the historical location useless on
     *     exactly the modern devices where it still exists.
     *  3. **getExternalFilesDir**, unchanged, as the last resort and for adb pulls.
     */
    private fun save(context: Context, text: String) {
        val stamp = java.text.SimpleDateFormat("yyyyMMdd-HHmmss", java.util.Locale.US)
            .format(java.util.Date())
        val model = android.os.Build.MODEL.replace(Regex("[^A-Za-z0-9._-]"), "_")
        val name = "otgbench-$model-$stamp.txt"

        // 1. onto the drive, one file per run plus a one-line index entry
        runCatching {
            val root = OtgMasterState.mountedDrives
                .firstOrNull { (it.fileSystem as? ExFatFileSystem)?.isUnmounted != true }
                ?.fileSystem?.rootDirectory
            if (root != null) {
                val bench = root.search("BENCH") ?: root.createDirectory("BENCH")
                val reports = bench.search("reports") ?: bench.createDirectory("reports")
                val bytes = text.toByteArray()
                val f = reports.search(name) ?: reports.createFile(name)
                f.write(0, ByteBuffer.wrap(bytes))
                f.flush()
                f.close()
                Log.i(TAG, "report written to the drive: BENCH/reports/$name")
                appendIndex(reports, name, text)
            }
        }.onFailure { Log.w(TAG, "could not write the report to the drive: $it") }

        // 2. shared Documents/, retrievable over MTP
        runCatching {
            val values = android.content.ContentValues().apply {
                put(android.provider.MediaStore.MediaColumns.DISPLAY_NAME, name)
                put(android.provider.MediaStore.MediaColumns.MIME_TYPE, "text/plain")
                if (android.os.Build.VERSION.SDK_INT >= 29) {
                    put(android.provider.MediaStore.MediaColumns.RELATIVE_PATH, "Documents")
                }
            }
            val collection = if (android.os.Build.VERSION.SDK_INT >= 29) {
                android.provider.MediaStore.Files.getContentUri(
                    android.provider.MediaStore.VOLUME_EXTERNAL_PRIMARY)
            } else {
                android.provider.MediaStore.Files.getContentUri("external")
            }
            context.contentResolver.insert(collection, values)?.let { uri ->
                context.contentResolver.openOutputStream(uri)?.use { it.write(text.toByteArray()) }
                Log.i(TAG, "report written to Documents/$name")
            }
        }.onFailure { Log.w(TAG, "could not write the report to Documents: $it") }

        // 3. the historical location
        runCatching {
            val dir = context.getExternalFilesDir(null) ?: context.filesDir
            File(dir, "benchmark.txt").writeText(text)
            File(dir, name).writeText(text)
            Log.i(TAG, "results written to ${File(dir, "benchmark.txt").absolutePath}")
        }.onFailure { Log.e(TAG, "could not save results", it) }
    }
}
