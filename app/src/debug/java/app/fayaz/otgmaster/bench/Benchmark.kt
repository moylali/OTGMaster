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
        /** Overrides the user's read-only default for encrypted partitions; null keeps it. */
        val readOnly: Boolean? = null,
    )

    // A backstop only: the wait ends when every unlock has finished. Two 10-partition
    // drives unlock one partition at a time, LUKS1 keyslots at host-calibrated PBKDF2
    // counts (2.8M iterations) among them.
    private const val MOUNT_TIMEOUT_MS = 600_000L

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

    /** Long enough not to be noise, short enough to answer "is it stuck?". */
    private const val HEARTBEAT_MS = 30_000L

    /** What is running now, for the heartbeat. */
    @Volatile private var phase: String = "starting"
    @Volatile private var phaseSince: Long = 0L

    private fun phase(name: String) {
        phase = name
        phaseSince = System.currentTimeMillis()
    }

    /**
     * Reports that the run is alive every [HEARTBEAT_MS], naming the phase and how
     * long it has been in it.
     *
     * Several phases emit nothing for minutes — a cold 10,000-entry exFAT listing took
     * 136 seconds uncached, and a 2 GiB read runs for tens of seconds — so a run in
     * progress was indistinguishable from a hung one, particularly on the on-device
     * screen where there is no logcat to fall back on.
     *
     * Goes to the UI sink and logcat but deliberately not into the report: a heartbeat
     * is about watching, not about the result, and it would bury the findings.
     */
    private fun startHeartbeat(): Thread = Thread {
        try {
            // Without this the first tick reports seconds since the epoch, because
            // phaseSince is still 0 until the first phase() call.
            phase("starting")
            while (!Thread.currentThread().isInterrupted) {
                Thread.sleep(HEARTBEAT_MS)
                val since = phaseSince
                val secs = if (since <= 0L) 0L else (System.currentTimeMillis() - since) / 1000
                val line = "still running: $phase (${secs}s in this step)"
                Log.i(TAG, line)
                OtgMasterState.logSink?.invoke(line)
            }
        } catch (_: InterruptedException) {
            // Normal shutdown at the end of the run.
        }
    }.apply { name = "OTGBenchHeartbeat"; isDaemon = true; start() }

    fun runAll(
        context: Context,
        only: Set<String> = emptySet(),
        mount: MountCredentials? = null,
        remount: Boolean = false,
        driveFilter: String? = null,
    ): String {
        if (!running.compareAndSet(false, true)) {
            val msg = "*** a benchmark is already running — refusing to start a second one ***"
            Log.w(TAG, msg)
            OtgMasterState.logSink?.invoke(msg)
            return msg
        }
        val heartbeat = startHeartbeat()
        // Whichever screen is in front holds the display on while this is true. A
        // throttled run produces numbers that look like a regression.
        OtgMasterState.benchmarkRunning.value = true
        try {
            return runAllLocked(context, only, mount, remount, driveFilter)
        } finally {
            OtgMasterState.benchmarkRunning.value = false
            heartbeat.interrupt()
            running.set(false)
        }
    }

    private fun runAllLocked(
        context: Context,
        only: Set<String> = emptySet(),
        mount: MountCredentials? = null,
        remount: Boolean = false,
        driveFilter: String? = null,
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
            // Held, not released: see OtgMasterState.holdConnections. Cleared once the
            // run's mount below has completed.
            OtgMasterState.holdConnections = true
            OtgMasterState.unmountAllRequest?.invoke()
            val deadline = System.currentTimeMillis() + 30_000
            while (OtgMasterState.mountedDrives.isNotEmpty() &&
                    System.currentTimeMillis() < deadline) {
                Thread.sleep(300)
            }
            Thread.sleep(1500)   // let the USB handle settle before re-probing
        }

        // Request the mount whenever credentials were given, not only when nothing is
        // mounted. On a drive with a plain partition beside an encrypted one, the
        // re-probe after the unmount above auto-mounts the plain partition within
        // ~50 ms; testing "nothing mounted" then skipped the unlock, and the run
        // measured the plain partition alone. Already-mounted partitions are not in
        // the candidate list, so requesting again cannot double-mount them.
        if (mount != null) {
            emit("requesting mount (PIM ${mount.pim ?: "default"}, ${mount.cipher}/${mount.hash}); " +
                "${OtgMasterState.mountedDrives.size} drive(s) already mounted")
            val handler = OtgMasterState.mountRequest
            if (handler == null) {
                emit("*** no mount handler installed — is MainActivity running? ***")
            } else {
                handler.mount(mount.password, mount.pim, mount.cipher, mount.hash, mount.readOnly)
                // attemptUnlock is asynchronous and unlocks are serialised across
                // partitions, so they appear one at a time. Waiting only for the
                // list to become non-empty snapshotted it mid-sequence and ran the
                // whole suite against 2 partitions of a 4-partition drive. Wait for
                // the count to stop growing instead.
                //
                // When partitions were already mounted before the request, a steady
                // count proves nothing until it has grown: a key derivation takes
                // seconds, so 3 s of no change is expected before the first unlock
                // lands. The 15 s fallback covers a request with nothing left to unlock.
                val startCount = OtgMasterState.mountedDrives.size
                val deadline = System.currentTimeMillis() + MOUNT_TIMEOUT_MS
                var lastCount = -1
                var stableSince = System.currentTimeMillis()
                while (System.currentTimeMillis() < deadline) {
                    val n = OtgMasterState.mountedDrives.size
                    val steady = System.currentTimeMillis() - stableSince
                    val inFlight = OtgMasterState.unlocksInFlight.get()
                    if (n != lastCount) {
                        lastCount = n
                        stableSince = System.currentTimeMillis()
                    } else if (inFlight == 0 && n > startCount && steady >= 3_000) {
                        // Every unlock has finished; 3 s more for plain remounts.
                        break
                    } else if (inFlight == 0 && n > 0 && steady >= 15_000) {
                        break
                    }
                    Thread.sleep(300)
                }
                val stillUnlocking = OtgMasterState.unlocksInFlight.get()
                if (stillUnlocking > 0) {
                    emit("*** $stillUnlocking unlock(s) still running after ${MOUNT_TIMEOUT_MS / 1000}s; " +
                         "running against what is mounted ***")
                }
                OtgMasterState.holdConnections = false
                if (OtgMasterState.mountedDrives.isEmpty()) {
                    emit("*** mount did not complete within ${MOUNT_TIMEOUT_MS / 1000}s ***")
                } else {
                    emit("mounted: ${OtgMasterState.mountedDrives.joinToString { it.name }}")
                    emit("")
                }
            }
        }

        OtgMasterState.holdConnections = false   // whether or not a mount was requested

        // Skip drives whose filesystem has already been unmounted. A remount leaves
        // the old entry in the list briefly, and calling into a torn-down
        // ExFatFileSystem or NtfsFileSystem is what withNative exists to stop.
        val allDrives = OtgMasterState.mountedDrives.filter {
            !isTornDown(it)
        }

        // Every mounted drive runs, in sequence, unless narrowed. The filter is a
        // comma-separated list of 0-based indices or case-insensitive substrings of the
        // volume label or drive name, so "0", "VCFAT", "exfat" and "0,1" all work.
        val drives = if (driveFilter.isNullOrBlank() || driveFilter.equals("all", true)) {
            allDrives
        } else {
            val wanted = driveFilter.split(",").map { it.trim() }.filter { it.isNotEmpty() }
            allDrives.filterIndexed { i, d ->
                val label = runCatching { d.fileSystem.volumeLabel }.getOrDefault("")
                wanted.any { w ->
                    w == i.toString() ||
                        label.contains(w, ignoreCase = true) ||
                        d.name.contains(w, ignoreCase = true)
                }
            }
        }
        if (allDrives.size > 1) {
            emit("drives attached: " + allDrives.mapIndexed { i, d ->
                "[$i] ${runCatching { d.fileSystem.volumeLabel }.getOrDefault("?")}" +
                    " @${d.sourceVolumeCandidate?.startBlock ?: "plain"}"
            }.joinToString(" "))
            // If two drives tag the same, the remount-and-verify sections cannot tell
            // them apart and could check the wrong one. Say so rather than report a
            // meaningless pass.
            val tags = allDrives.map { driveTag(it) }
            if (tags.size != tags.toSet().size) {
                emit("*** two mounted volumes share an identity: $tags")
                emit("*** remount-based verification cannot distinguish them — " +
                     "run them one at a time with the 'drive' filter ***")
            }
            emit("running against : " + if (drives.size == allDrives.size) "all, in sequence"
                 else drives.joinToString { runCatching { it.fileSystem.volumeLabel }.getOrDefault("?") })
            emit("")
        }
        if (drives.isEmpty() && allDrives.isNotEmpty()) {
            emit("*** drive filter '$driveFilter' matched none of the mounted drives ***")
        }
        if (drives.isEmpty()) {
            emit("NO DRIVES MOUNTED — attach a prepared drive, or pass mount credentials")
            emit("")
            emit("--- conditions at the end of the run ---")
            emitPowerState(context, ::emit)
            return out.toString().also { save(context, it) }
        }

        // Identity now, object later. A MountedDrive is invalidated by any remount,
        // and the write, unaligned and correct sections all remount — so holding the
        // objects captured before the loop meant every drive after the first ran
        // against a closed block device. On the four-partition LUKS drive that
        // produced "block device is closed (volume was unmounted)" for every section
        // of partitions 2, 3 and 4, and only the first partition yielded a result.
        //
        // driveTag reads plain fields, so it is safe to compute from a stale object;
        // the tag survives a remount because it is a property of the medium.
        val plan = drives.map { driveTag(it) }

        for (tag in plan) {
            val drive = driveForTag(tag)
            if (drive == null) {
                emit("--- drive: $tag ---")
                emit("*** not mounted at the start of its turn — skipped rather than")
                emit("    measured through a closed device ***")
                emit("")
                continue
            }
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

            phase("free space")
            if (wants("free")) runCatching { benchFreeSpace(fs, ::emit) }.onFailure { emit("freeSpace     : FAILED ${it}") }
            phase("block layer")
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

            phase("directory listing")
            if (wants("dir")) runCatching { benchDirListing(bench, ::emit) }.onFailure { emit("dir listing   : FAILED ${it}") }
            phase("path resolve")
            if (wants("path")) runCatching { benchPathResolve(drive.fileSystem.rootDirectory, ::emit) }.onFailure { emit("path resolve  : FAILED ${it}") }
            phase("sequential read")
            if (wants("seq")) runCatching { benchSequentialRead(bench, ::emit) }.onFailure { emit("seq read      : FAILED ${it}") }
            phase("random read")
            if (wants("random")) runCatching { benchRandomRead(bench, ::emit) }.onFailure { emit("random read   : FAILED ${it}") }
            phase("dense opens")
            if (wants("opens")) runCatching { benchDenseOpens(bench, ::emit) }.onFailure { emit("dense opens   : FAILED ${it}") }
            // Each remounting section below replaces the MountedDrive, so the
            // reference captured at the top of this drive's turn is dead as soon as
            // the first of them runs. Re-resolving per *drive* is not enough; it has
            // to be per *section*.
            //
            // Seen on the Huawei P20 Lite: write verify remounted successfully, then
            // unaligned failed with "block device is closed (volume was unmounted)"
            // against the stale reference, correctness reported "no live mount", and
            // fixtures — running later still — found the volume perfectly fine. The
            // volume had come back all along; only the references were dead. On
            // faster phones the remount lands inside the section's own wait and none
            // of this shows.
            //
            // It waits rather than giving up at once, because on the slowest devices
            // the volume returns seconds after a section would have. Falling back to
            // the previous reference preserves the old behaviour when nothing comes
            // back, so a genuinely absent drive still reports as absent.
            fun live(): MountedDrive {
                val deadline = System.currentTimeMillis() + 30_000
                var d = driveForTag(tag)
                while (d == null && System.currentTimeMillis() < deadline) {
                    Thread.sleep(500)
                    d = driveForTag(tag)
                }
                return d ?: drive
            }

            // A volume the app mounts read-only (APFS, ext2/ext3, or one the user chose)
            // is reported as SKIPPED with its reason rather than failing three times:
            // refusing the write is the correct behaviour there, not a fault. A run
            // over the device-matrix drives otherwise printed FAILED for every APFS one.
            val readOnly = drive.readOnlyReason
            fun writeSection(name: String, label: String, body: () -> Unit) {
                if (!only.contains(name)) return
                if (readOnly != null) { emit("$label: SKIPPED — read-only: $readOnly"); return }
                runCatching { body() }.onFailure { emit("$label: FAILED ${it}") }
            }
            // Opt-in only: this one writes to the drive, so a default run stays
            // read-only.
            phase("write verification")
            writeSection("write", "write verify  ") { benchWriteVerify(live(), ::emit, mount) }
            // Opt-in: writes, and deliberately unaligned.
            phase("unaligned writes")
            writeSection("unaligned", "unaligned     ") { benchUnaligned(live(), ::emit, mount) }
            phase("correctness")
            writeSection("correct", "correctness   ") { benchCorrectness(live(), ::emit, mount) }
            // Opt-in: a 2.2 GB file and a churned tree, left for the host check.
            phase("big writes")
            writeSection("bigwrite", "bigwrite      ") { benchBigWrite(live(), ::emit, mount) }
            phase("SAF path")
            if (only.contains("saf")) runCatching { benchSaf(context, ::emit) }
                .onFailure { emit("saf           : FAILED ${it}") }
            // Opt-in: hashing a 2 GiB fixture takes minutes.
            phase("fixture hashes")
            // The trace, not just the message: this handler printed
            // "FAILED java.io.IOException: File is closed" and dropped the one
            // piece of information that identified the throwing line.
            if (only.contains("fixtures")) runCatching { benchFixtures(live(), ::emit) }
                .onFailure { emit("fixtures      : FAILED ${it.stackTraceToString()}") }
            emit("")
        }

        emit("")
        emit("--- conditions at the end of the run ---")
        emitPowerState(context, ::emit)
        val text = out.toString()
        save(context, text, plan)
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
        val tag = driveTag(drive)
        fun liveRoot(): UsbFile? = rootForTag(tag)

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
        emit("              remounting to discard filesystem metadata…")
        var remountWhy: String? = null
        var remountRan = false
        if (remountAndProve(mount, { liveRoot()?.let { "live" } }, { remountWhy = it })) {
            remountRan = true
            allOk = verifyPass("(remounted)") && allOk
        } else {
            // Said plainly, and counted as a failure of the check rather than a pass:
            // the remount pass is the only one that can catch metadata which was never
            // written back, so skipping it quietly would overstate what was verified.
            emit("verify (remounted)  : *** NOT PERFORMED — " +
                 "${remountWhy ?: "remount failed"} ***")
            emit("              the first two passes still hold, but nothing here proves " +
                 "the on-disk metadata is correct")
        }

        // "ALL PASSED" would overstate a run where the remount pass never happened: the
        // other two passes cannot distinguish correct on-disk metadata from metadata
        // that was only ever correct in memory.
        emit("write verify  : " + when {
            !allOk -> "*** FAILURES ABOVE ***"
            remountRan -> "ALL PASSED"
            else -> "PARTIAL — 2 of 3 passes; the remount pass was not performed"
        })
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
        // Resolve by tag, not by position: with several volumes mounted,
        // firstOrNull() picks whichever is first in the mount list, so the unaligned
        // cases would write to and judge a volume other than the one under test.
        // (This change was aimed at benchFixtures in 6df73db and landed here by
        // mistake; benchFixtures got it separately later. Both were wrong.)
        val root = rootForTag(driveTag(drive)) ?: drive.fileSystem.rootDirectory
        val dir = freshDir(root, "BENCH_UNALIGNED")
        val dirName = dir.name

        val tag = driveTag(drive)
        fun liveRoot(): UsbFile? = rootForTag(tag)

        var remountFailure: String? = null
        fun remount(): Boolean = remountAndProve(
            mount, { liveRoot()?.let { "live" } }, { remountFailure = it }
        )

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
            emit("unaligned     : *** NOT VERIFIED — ${remountFailure ?: "remount failed"} ***")
            emit("unaligned     : these cases only mean something after a remount, so no " +
                 "result is reported rather than a misleading pass")
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

        val tag = driveTag(drive)
        fun liveRoot(): UsbFile? = rootForTag(tag)

        var remountFailure: String? = null
        fun remount(): Boolean = remountAndProve(
            mount, { liveRoot()?.let { "live" } }, { remountFailure = it }
        )

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

        if (!remount()) {
            bad("NOT VERIFIED — ${remountFailure ?: "remount failed"}")
            emit("correctness   : these cases only mean something after a remount, so no " +
                 "result is reported rather than a misleading pass")
            return
        }

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
        // The drive under test, not the first mounted one: with several volumes
        // mounted this read another volume's cache counters, and "F read-only open
        // wrote nothing" was then a statement about a drive nothing had touched.
        val cache = driveForTag(driveTag(drive))?.blockDevice
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
        // Match the request size the FUSE bridge actually delivers.
        //
        // The direct arm used 64 KiB while the kernel coalesces FUSE reads to 128 KiB
        // (measured in the FD relay spike: onRead arrives at 131072 bytes). Comparing
        // those two produced an "overhead" ratio that mixed provider cost with kernel
        // readahead, and ranged 0.87x-3.52x across four device/filesystem pairs — a
        // figure below 1.0 being proof on its own that it was not measuring overhead.
        val bufSize = 128 * 1024
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
            emit("saf direct    : ${mbps(directBytes, directNs)} calling UsbFile directly " +
                 "(${bufSize / 1024} KiB reads, matching what FUSE delivers)")
            val ratio = safNs.toDouble() / directNs.toDouble()
            emit("saf ratio     : %.2fx provider vs direct".format(ratio))
            if (ratio < 1.0) {
                emit("              (below 1.0 — the two arms are not comparable; " +
                     "treat as noise, not a speedup)")
            }
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
        // Resolve by tag, not by position. 6df73db meant to make this change and made
        // it in benchUnaligned instead: the same line appears there first, and the
        // replacement took the first match. So on any run with more than one volume
        // mounted, every drive's fixtures section read whichever volume happened to
        // be first in the mount list — including all four partitions of the
        // LUKS1/2 + FAT32/exFAT drive, which then reported one comparison four
        // times, and the SD card, where the VeraCrypt partition's fixtures may have
        // been read from the plain partition beside it.
        val root = rootForTag(driveTag(drive)) ?: drive.fileSystem.rootDirectory
        val bench = root.search("BENCH") ?: return emit("fixtures      : BENCH/ not found")
        val manifest = bench.search("MANIFEST.txt")
            ?: return emit("fixtures      : MANIFEST.txt missing — re-run prepare_test_usb.sh")

        // Whole, however long: the device-matrix manifests (make_fixture_tree.py) list
        // all ~21,000 files, about 2 MB. A single 256 KiB read used to cap it there,
        // which silently dropped every entry past the cut.
        val text = runCatching {
            val out = java.io.ByteArrayOutputStream(manifest.length.toInt())
            val bb = ByteBuffer.allocate(256 * 1024)
            var off = 0L
            while (off < manifest.length) {
                bb.clear()
                manifest.read(off, bb)
                if (bb.position() <= 0) break
                out.write(bb.array(), 0, bb.position())
                off += bb.position()
            }
            out.toString(Charsets.UTF_8.name())
        }.getOrNull()
        if (text == null) return emit("fixtures      : could not read MANIFEST.txt")

        var checked = 0
        var failed = 0
        var skipped = 0

        var errors = 0
        // Each directory listed once. search() re-lists every directory on the path, so
        // the 20,000 entries of the two 10,000-file directories would otherwise cost
        // about 10^8 directory-entry reads.
        val listings = HashMap<String, Map<String, UsbFile>?>()
        fun lookup(path: String): UsbFile? {
            val parent = path.substringBeforeLast('/', "")
            val dir = listings.getOrPut(parent) {
                val d = if (parent.isEmpty()) bench else bench.search(parent)
                runCatching { d?.listFiles()?.associateBy { it.name } }.getOrNull()
            }
            return dir?.get(path.substringAfterLast('/'))
        }
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

            val file = lookup(path)
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
            checked++
            val got = digest.digest().joinToString("") { "%02x".format(it) }
            if (got == expected && off == file.length) {
                // One line per large file (with its throughput); the thousands of
                // small ones only count, or the report would be 21,000 lines long.
                if (file.length >= 16L * 1024 * 1024) {
                    emit("fixtures      : $path matches the host hash (${mbps(off, ns)})")
                } else if (checked % 2000 == 0) {
                    emit("fixtures      : $checked entries matched so far")
                }
            } else {
                emit("fixtures      : *** $path DIFFERS — read $off of ${file.length} bytes, " +
                     "expected ${expected.take(16)}…, got ${got.take(16)}… ***")
                failed++
            }
            // Closed last, after every read of `file`. Closing before the two
            // `file.length` reads above threw IOException("File is closed") out of
            // the whole section, because ExFatFile.length calls checkNotClosed() —
            // so the exFAT run reported `fixtures: FAILED` having successfully
            // hashed the file. It looked like a mid-read failure only because the
            // read it had just completed took 30-60s.
            //
            // ext4 and FAT32 hid it: Ext4File has no closed state at all, so
            // length-after-close simply works there. ExFatFile is the only
            // implementation that enforces the contract this was breaking.
            runCatching { file.close() }
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
    // ---------------------------------------------------------------------------
    // bigwrite: a 2 GB+ file and a churned tree of files, left on the volume with
    // their expected content so the host can check them without the app
    // (scripts/verify_matrix_drive.py; docs/TEST_DATA.md §16).
    // ---------------------------------------------------------------------------

    /**
     * The content every bigwrite file holds: the AES-128-CTR keystream (zero IV) under
     * a key derived from the volume label and the file's path. Fast on the phone
     * (hardware AES), regenerated on the host with Python's `cryptography`, and
     * different on every partition and path, so a write that lands on the wrong file
     * or the wrong partition cannot match.
     */
    private class Keystream(label: String, path: String) {
        private val cipher = javax.crypto.Cipher.getInstance("AES/CTR/NoPadding").apply {
            val key = java.security.MessageDigest.getInstance("SHA-256")
                .digest("otg-matrix:${label.trim()}/$path".toByteArray()).copyOf(16)
            init(javax.crypto.Cipher.ENCRYPT_MODE, javax.crypto.spec.SecretKeySpec(key, "AES"),
                javax.crypto.spec.IvParameterSpec(ByteArray(16)))
        }
        private var produced = 0L

        /** The next [n] bytes of the stream. */
        fun next(n: Int): ByteArray { produced += n; return cipher.update(ByteArray(n)) }

        /** Bytes [from, from + n) of the stream; [from] must not be behind what was produced. */
        fun at(from: Long, n: Int): ByteArray {
            var skip = from - produced
            require(skip >= 0) { "keystream cannot go backwards" }
            while (skip > 0) { val k = minOf(skip, 1L shl 20).toInt(); next(k); skip -= k }
            return next(n)
        }

        companion object {
            fun content(label: String, path: String, size: Long): ByteArray =
                Keystream(label, path).next(size.toInt())
        }
    }

    private fun sha256Hex(b: ByteArray, len: Int = b.size): String =
        java.security.MessageDigest.getInstance("SHA-256").apply { update(b, 0, len) }.digest()
            .joinToString("") { "%02x".format(it) }

    private const val BIG_BYTES = 2_200_000_000L          // past 2^31, under FAT32's 4 GiB
    private const val BIG_DIR = "BENCH_BIG"
    private const val TREE_DIR = "BENCH_TREE"

    private fun benchBigWrite(drive: MountedDrive, emit: (String) -> Unit, mount: MountCredentials?) {
        val label = runCatching { drive.fileSystem.volumeLabel }.getOrDefault("").trim()
        val tag = driveTag(drive)
        val root = drive.fileSystem.rootDirectory
        // The previous run's files go first: they were for the host check after that run.
        for (d in listOf(BIG_DIR, TREE_DIR)) runCatching { root.search(d)?.let { deleteRecursively(it) } }

        // --- the big file ---------------------------------------------------------
        val free = runCatching { drive.fileSystem.freeSpace }.getOrDefault(0L)
        val bigPath = "big.bin"
        var bigOk: Boolean? = null
        if (free < BIG_BYTES + 256L * 1024 * 1024) {
            emit("bigwrite      : big file SKIPPED — ${free / 1_000_000} MB free, needs ${BIG_BYTES / 1_000_000} MB + slack")
        } else {
            val f = root.createDirectory(BIG_DIR).createFile(bigPath)
            val ks = Keystream(label, "$BIG_DIR/$bigPath")
            // Odd sizes, so writes straddle sectors, clusters and 2^31.
            val sizes = intArrayOf(1 shl 20, (1 shl 20) + 4097, 65535, 3 shl 20, 777_777)
            var off = 0L
            var i = 0
            val ns = measureNanoTime {
                while (off < BIG_BYTES) {
                    val n = minOf(sizes[i++ % sizes.size].toLong(), BIG_BYTES - off).toInt()
                    f.write(off, ByteBuffer.wrap(ks.next(n)))
                    off += n
                }
                f.flush()
            }
            f.close()
            emit("bigwrite      : $BIG_DIR/$bigPath ${BIG_BYTES} bytes written -> ${mbps(BIG_BYTES, ns)}")
            bigOk = false
        }

        // --- the tree -------------------------------------------------------------
        val expected = sortedMapOf<String, ByteArray>()   // path under TREE_DIR -> content
        val rng = java.util.Random(label.hashCode().toLong())
        val tree = root.createDirectory(TREE_DIR)
        val dirs = mutableListOf<String>()
        for (a in 0 until 5) {
            tree.createDirectory("d$a").let { da ->
                dirs += "d$a"
                for (b in 0 until 3) { da.createDirectory("e$b"); dirs += "d$a/e$b" }
            }
        }
        fun dirOf(p: String): UsbFile = tree.search(p) ?: throw java.io.IOException("$TREE_DIR/$p missing")
        val edgeSizes = longArrayOf(0, 1, 511, 512, 513, 4095, 4096, 4097, 65536, 65537, 1L shl 20)
        val treeNs = measureNanoTime {
            for (n in 0 until 300) {
                val dir = dirs[rng.nextInt(dirs.size)]
                val size = if (n < edgeSizes.size) edgeSizes[n] else rng.nextInt(2 shl 20).toLong()
                val path = "$dir/f$n.bin"
                val content = Keystream.content(label, "$TREE_DIR/$path", size)
                dirOf(dir).createFile("f$n.bin").apply { if (size > 0) write(0, ByteBuffer.wrap(content)); close() }
                expected[path] = content
            }
            val names = expected.keys.toList()
            fun pick() = names[rng.nextInt(names.size)]
            fun file(p: String) = tree.search(p) ?: throw java.io.IOException("$TREE_DIR/$p missing")
            // Overwrite a range in the middle.
            repeat(40) {
                val p = pick(); val c = expected[p] ?: return@repeat
                if (c.size < 3000) return@repeat
                val at = rng.nextInt(c.size - 2000); val patch = Keystream.content(label, "ow:$p", 1000)
                file(p).apply { write(at.toLong(), ByteBuffer.wrap(patch)); close() }
                expected[p] = c.copyOf().also { patch.copyInto(it, at) }
            }
            // Append, continuing the file's own keystream.
            repeat(30) {
                val p = pick(); val c = expected[p] ?: return@repeat
                val add = 1 + rng.nextInt(70_000)
                val more = Keystream(label, "$TREE_DIR/$p").at(c.size.toLong(), add)
                file(p).apply { write(c.size.toLong(), ByteBuffer.wrap(more)); close() }
                expected[p] = c + more
            }
            // Truncate.
            repeat(30) {
                val p = pick(); val c = expected[p] ?: return@repeat
                val len = if (c.isEmpty()) 0 else rng.nextInt(c.size)
                file(p).apply { length = len.toLong(); close() }
                expected[p] = c.copyOf(len)
            }
            // Rename within the directory.
            repeat(30) {
                val p = pick(); val c = expected.remove(p) ?: return@repeat
                val np = p.substringBeforeLast('/') + "/renamed_" + p.substringAfterLast('/')
                file(p).name = np.substringAfterLast('/')
                expected[np] = c
            }
            // Move to another directory.
            repeat(20) {
                val p = expected.keys.elementAt(rng.nextInt(expected.size))
                val to = dirs[rng.nextInt(dirs.size)]
                if (p.substringBeforeLast('/') == to) return@repeat
                val np = "$to/" + p.substringAfterLast('/')
                if (np in expected) return@repeat
                file(p).moveTo(dirOf(to))
                expected[np] = expected.remove(p)!!
            }
            // Delete files, then one whole directory.
            repeat(40) {
                val p = expected.keys.elementAt(rng.nextInt(expected.size))
                file(p).delete(); expected.remove(p)
            }
            val gone = "d4/e2"
            dirOf(gone).let { deleteRecursively(it) }
            expected.keys.filter { it.startsWith("$gone/") }.forEach { expected.remove(it) }
        }
        // The expected state, for the host: computed from what was meant, never read back.
        val listing = expected.entries.joinToString("") { (p, c) -> "$p\t${c.size}\t${sha256Hex(c)}\n" }
        tree.createFile("EXPECTED.txt").apply {
            write(0, ByteBuffer.wrap(("# bigwrite tree, label $label: path<TAB>bytes<TAB>sha256\n" + listing).toByteArray()))
            close()
        }
        emit("bigwrite      : $TREE_DIR ${expected.size} files after create/overwrite/append/" +
             "truncate/rename/move/delete (${ms(treeNs)})")

        // --- remount, then read everything back -----------------------------------
        var why: String? = null
        if (!remountAndProve(mount, { rootForTag(tag)?.let { "live" } }, { why = it })) {
            emit("bigwrite      : *** NOT VERIFIED — ${why ?: "remount failed"}; the host check still applies ***")
            return
        }
        val live = rootForTag(tag) ?: return emit("bigwrite      : *** drive did not come back ***")
        if (bigOk != null) {
            val f = live.search(BIG_DIR)?.search(bigPath)
            if (f == null || f.length != BIG_BYTES) {
                emit("bigwrite      : *** $BIG_DIR/$bigPath is ${f?.length ?: "missing"}, expected $BIG_BYTES ***")
            } else {
                val ks = Keystream(label, "$BIG_DIR/$bigPath")
                val bb = ByteBuffer.allocate(1 shl 20)
                var off = 0L
                var bad = -1L
                val ns = measureNanoTime {
                    while (off < BIG_BYTES && bad < 0) {
                        bb.clear()
                        val n = minOf(bb.capacity().toLong(), BIG_BYTES - off).toInt()
                        bb.limit(n)
                        f.read(off, bb)
                        val want = ks.next(n)
                        val got = bb.array()
                        if (bb.position() != n) bad = off + bb.position()
                        else for (k in 0 until n) if (got[k] != want[k]) { bad = off + k; break }
                        off += n
                    }
                }
                bigOk = bad < 0
                emit("bigwrite      : $BIG_DIR/$bigPath after remount " +
                     (if (bigOk) "matches (${mbps(BIG_BYTES, ns)})" else "*** DIFFERS at byte $bad ***"))
            }
        }
        val liveTree = live.search(TREE_DIR)
        var treeBad = 0
        val found = mutableSetOf<String>()
        fun walk(d: UsbFile, prefix: String) {
            for (c in d.listFiles()) {
                val p = if (prefix.isEmpty()) c.name else "$prefix/${c.name}"
                if (c.isDirectory) walk(c, p) else if (p != "EXPECTED.txt") found += p
            }
        }
        if (liveTree == null) { emit("bigwrite      : *** $TREE_DIR missing after remount ***"); treeBad++ }
        else {
            walk(liveTree, "")
            for ((p, c) in expected) {
                val f = liveTree.search(p)
                if (f == null) { if (treeBad++ < 5) emit("bigwrite      : *** $p missing ***"); continue }
                val bb = ByteBuffer.allocate(maxOf(c.size, 1))
                if (c.isNotEmpty()) f.read(0, bb)
                if (f.length != c.size.toLong() || sha256Hex(bb.array(), c.size) != sha256Hex(c)) {
                    if (treeBad++ < 5) emit("bigwrite      : *** $p differs (${f.length} bytes, expected ${c.size}) ***")
                }
            }
            (found - expected.keys).forEach { extra ->
                if (treeBad++ < 5) emit("bigwrite      : *** $extra exists but should not ***")
            }
        }
        emit("bigwrite      : " + when {
            bigOk == false || treeBad > 0 -> "*** FAILED — big file ${bigOk ?: "skipped"}, $treeBad tree problem(s) ***"
            else -> "ALL PASSED after remount (big file ${if (bigOk == null) "skipped" else "verified"}, " +
                    "${expected.size} tree files verified); left on the volume for the host check"
        })
    }

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

    /**
     * Unmounts and remounts, and proves it happened.
     *
     * The previous version could not tell a real remount from none at all. It called
     * OtgMasterState.unmountAllRequest?.invoke() — a safe call — so when MainActivity
     * had been destroyed and the handlers were null, nothing was unmounted, the
     * wait-for-empty loop simply timed out, the mount request also did nothing, and
     * liveRoot() was still non-null, so it returned true. Every case that claims to be
     * judged "after a remount" was then judged against live in-memory metadata, which
     * is exactly what the remount exists to discard — and the suite still printed ALL
     * PASSED.
     *
     * That is how an entire run on a Huawei P20 Lite reported eleven passes without a
     * single remount: the on-device benchmark screen backgrounds MainActivity, Android
     * 9 destroyed it, and onDestroy nulls the handlers.
     *
     * Now it requires the mount list to actually empty, and the drive identity to
     * change, before claiming success. [why] receives the reason on failure so the
     * caller can report it rather than printing something untrue.
     */
    private fun remountAndProve(
        mount: MountCredentials?,
        tagOf: () -> String?,
        why: (String) -> Unit,
    ): Boolean {
        if (mount == null) {
            why("no credentials were supplied, so no remount was attempted")
            return false
        }
        val unmountAll = OtgMasterState.unmountAllRequest
        val mountReq = OtgMasterState.mountRequest
        if (unmountAll == null || mountReq == null) {
            why("the mount handler is unavailable — MainActivity is not running, so a " +
                "remount cannot be performed and on-disk state cannot be judged")
            return false
        }
        val before = OtgMasterState.mountedDrives.map { it.id }.toSet()

        // Keep every stick's connection through the unmount (OtgMasterState.holdConnections):
        // released, Android's storage stack takes the drive and mounts it itself.
        OtgMasterState.holdConnections = true
        try {
            return remountHeld(mount, mountReq, unmountAll, before, tagOf, why)
        } finally {
            OtgMasterState.holdConnections = false
        }
    }

    private fun remountHeld(
        mount: MountCredentials,
        mountReq: OtgMasterState.MountRequest,
        unmountAll: () -> Unit,
        before: Set<String>,
        tagOf: () -> String?,
        why: (String) -> Unit,
    ): Boolean {
        unmountAll.invoke()
        // Was a flat 30s. On a Huawei P20 Lite unmounting ext4-in-VeraCrypt from an
        // SD card, that expired before the unmount landed — and the unmount then
        // completed anyway, moments later. The volume therefore disappeared *after*
        // this function had concluded it had not, leaving every later section with a
        // closed device and nothing to bring it back, because the mount request is
        // only issued past this point. The visible result was one PARTIAL followed by
        // "block device is closed" and "no live mount" on a drive that was fine.
        //
        // Same budget as the mount side: if a mount may take 90s on the slowest
        // device, so may an unmount, and the unmount has metadata to flush.
        // Latch the empty observation instead of requiring it to be true when the
        // poll happens to look. The app re-mounts an attached drive on its own:
        // measured on a Huawei P20 Lite, the mount list emptied 7ms after the
        // request and the device was re-opened 24ms later. A 300ms poll never saw
        // the gap, so this loop ran the full timeout and reported "did not unmount"
        // about a drive that had unmounted and come back — costing the remount
        // verdict, and before d7c8fcd every section after it.
        //
        // Polling faster narrows the race but does not remove it; latching does.
        // The identity check at the end of this function is what actually proves a
        // remount happened, and it is unaffected either way.
        var sawEmpty = false
        var deadline = System.currentTimeMillis() + MOUNT_TIMEOUT_MS
        while (!sawEmpty && System.currentTimeMillis() < deadline) {
            if (OtgMasterState.mountedDrives.isEmpty()) sawEmpty = true else Thread.sleep(50)
        }
        if (!sawEmpty) {
            // Put it back before giving up. Returning here used to strand the volume:
            // this function had unmounted it, so the sections after this one had
            // nothing to run against, and the whole drive's remaining results were
            // lost to a timeout that only delayed one verdict.
            runCatching { mountReq.mount(mount.password, mount.pim, mount.cipher, mount.hash, mount.readOnly) }
            why("the drive did not unmount within ${MOUNT_TIMEOUT_MS / 1000}s, " +
                "so nothing was discarded")
            return false
        }
        Thread.sleep(1500)

        mountReq.mount(mount.password, mount.pim, mount.cipher, mount.hash, mount.readOnly)
        deadline = System.currentTimeMillis() + MOUNT_TIMEOUT_MS
        while (tagOf() == null && System.currentTimeMillis() < deadline) Thread.sleep(500)
        val after = OtgMasterState.mountedDrives.map { it.id }.toSet()
        if (tagOf() == null) {
            why("the drive did not come back after unmounting")
            return false
        }
        // A fresh mount gets a fresh id. Identical ids would mean the list never
        // actually turned over, which is the failure this function exists to catch.
        if (after.isNotEmpty() && after == before) {
            why("the mount identity did not change, so no real remount occurred")
            return false
        }
        return true
    }

    /**
     * Identifies a drive across an unmount/remount.
     *
     * MountedDrive.id is regenerated on every mount, so it cannot be used to find the
     * same drive again. sourceDeviceName is the stable USB identity, and the volume
     * label disambiguates partitions on one physical drive.
     *
     * This matters as soon as two drives are attached: the sections that remount to
     * force a re-read from disk used to re-resolve with firstOrNull, which is correct
     * with one drive and arbitrary with two — it could write to one drive and verify
     * against the other, reporting a pass that means nothing.
     */
    private fun driveTag(d: MountedDrive): String = buildString {
        // Physical device, then the partition's own offset on it. startBlock is what
        // actually makes this unique: two partitions on one drive share a
        // sourceDeviceName, ExFatFileSystem.volumeLabel is a hardcoded "exFAT" so it
        // distinguishes nothing there, and two partitions of equal size have equal
        // capacity. The offset cannot collide, and it is stable across a remount
        // because it is a property of the medium rather than of this mount.
        append(d.sourceDeviceName ?: "?")
        append('@')
        val start = d.sourceVolumeCandidate?.startBlock
        if (start != null) {
            append(start)
        } else {
            // A plain (unencrypted) auto-mounted volume has no candidate. Fall back to
            // label and capacity, which is weaker but only applies where there is no
            // partition offset to be had.
            append("plain:")
            append(runCatching { d.fileSystem.volumeLabel }.getOrDefault("?"))
            append(':')
            append(runCatching { d.fileSystem.capacity }.getOrDefault(-1L))
        }
    }

    /** True once the drive's native filesystem has been unmounted (exFAT and NTFS). */
    private fun isTornDown(d: MountedDrive): Boolean = when (val fs = d.fileSystem) {
        is ExFatFileSystem -> fs.isUnmounted
        is app.fayaz.otgmaster.ntfs.NtfsFileSystem -> fs.isUnmounted
        else -> false
    }

    /** The live [MountedDrive] matching [tag], or null if it is not mounted. */
    private fun driveForTag(tag: String): MountedDrive? = OtgMasterState.mountedDrives
        .firstOrNull {
            driveTag(it) == tag && !isTornDown(it)
        }

    /** The live root of the drive matching [tag], or null if it is not mounted. */
    private fun rootForTag(tag: String): UsbFile? = OtgMasterState.mountedDrives
        .firstOrNull {
            driveTag(it) == tag && !isTornDown(it)
        }?.fileSystem?.rootDirectory

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
    private fun save(context: Context, text: String, tags: List<String> = emptyList()) {
        val stamp = java.text.SimpleDateFormat("yyyyMMdd-HHmmss", java.util.Locale.US)
            .format(java.util.Date())
        val model = android.os.Build.MODEL.replace(Regex("[^A-Za-z0-9._-]"), "_")
        val name = "otgbench-$model-$stamp.txt"

        // 1. onto the drive, one file per run plus a one-line index entry
        // Onto every drive this run measured, resolved by tag. It used to go to
        // whichever volume was first in the mount list at the end of the run. On the
        // SD card that was the plain ext4 partition — the app had re-mounted it on its
        // own — so six runs measuring the VeraCrypt partition wrote their reports to a
        // partition none of them had tested, and a baseline compare of that partition
        // found files the run was never supposed to put there.
        val roots = tags.mapNotNull { rootForTag(it) }
        for (root in roots) runCatching {
            run {
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

        // 2. shared Documents/, retrievable over MTP — API 29 and up only.
        //
        // Below that, a MediaStore insert needs WRITE_EXTERNAL_STORAGE and fails with
        // a SecurityException (observed on a Huawei P20 Lite, Android 9). It is also
        // unnecessary there: this destination exists because Android/data became
        // unreadable over MTP in Android 11, and on older releases
        // getExternalFilesDir is directly accessible from a desktop. So the devices
        // that cannot use this path are exactly the ones that do not need it.
        if (android.os.Build.VERSION.SDK_INT >= 29) runCatching {
            val values = android.content.ContentValues().apply {
                put(android.provider.MediaStore.MediaColumns.DISPLAY_NAME, name)
                put(android.provider.MediaStore.MediaColumns.MIME_TYPE, "text/plain")
                put(android.provider.MediaStore.MediaColumns.RELATIVE_PATH, "Documents")
            }
            val collection = android.provider.MediaStore.Files.getContentUri(
                android.provider.MediaStore.VOLUME_EXTERNAL_PRIMARY)
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
            if (android.os.Build.VERSION.SDK_INT < 29) {
                Log.i(TAG, "on this Android version that folder is readable over MTP, " +
                           "so no Documents/ copy is needed")
            }
        }.onFailure { Log.e(TAG, "could not save results", it) }
    }
}
