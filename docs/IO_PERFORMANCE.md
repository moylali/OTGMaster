# I/O Performance — getting near-filesystem responsiveness

Companion to [BACKUP_PLAN.md](BACKUP_PLAN.md) §7. Everything here is a defect in the **shipping
app**, independent of backups. Fixing it is what Phase A of that plan depends on.

---

## 1. Where the time actually goes

Two filesystems, two different shapes of waste.

```
Files app ──binder/AppFuse──▶ DocumentsProvider          [binder thread pool]
                              ↓ ProxyFileDescriptor.onRead   [ONE HandlerThread, all drives]
                              ↓ getFileForDocId → re-walks the whole path, re-lists every dir
                              ↓ UsbFile.read
   FAT32   Kotlin (libaums) ──────────────────────────────────▶ ─JNI per 512 B─▶ C crypto
   exFAT   Kotlin ─JNI─▶ C libexfat ─JNI upcall per block─▶ Kotlin ─JNI per 512 B─▶ C crypto
                              ↓
                         LibaumsRawBlockDevice → SCSI CBW/data/CSW
                              ↓ UsbDeviceConnection.bulkTransfer   [synchronous]
                             USB
```

### The exFAT path ping-pongs across the JNI boundary three times

`exfat_pread` (`app/src/main/cpp/exfat/io.c:97`) calls **up** into Kotlin for every block chunk,
and on every single call it does:

```c
jclass clazz = (*env)->FindClass(env, "app/fayaz/otgmaster/exfat/ExFatNative");   // per call
jmethodID preadMethod = (*env)->GetStaticMethodID(env, clazz, "pread", "...");    // per call
jbyteArray jBuffer = (*env)->NewByteArray(env, size);                             // per call
jint result = (*env)->CallStaticIntMethod(...);   // → Kotlin → RawBlockDevice → JNI per 512 B → C
(*env)->GetByteArrayRegion(env, jBuffer, 0, result, buffer);                      // copy out
```

`FindClass` resolves a class by name string through the classloader — it is one of the most
expensive JNI calls there is, and it is being made per block read. `GetStaticMethodID` likewise.
Both are trivially cacheable at `JNI_OnLoad` and never change.

Counting buffer allocations and copies for **one block chunk** on exFAT:

| Where | What |
|---|---|
| `io.c` | `NewByteArray(size)` |
| `ExFatNative.pread` | `readBlocks` allocates `blocksData` |
| `NativeDecryptedBlockDevice` | allocates full-size plaintext array |
| " | 2 × 512 B per sector, for every sector |
| `ExFatNative.pread` | `System.arraycopy` into `destBuffer` |
| `io.c` | `GetByteArrayRegion` copies out again |

So the §7.3 per-sector crypto loop is nested *inside* a per-block upcall that itself does a
`FindClass`. FAT32 avoids the upcall (libaums is pure Kotlin) but still pays the per-sector crypto.

### Nothing caches anything

There is no page cache. Every directory listing, every FAT/bitmap lookup, every re-read of the same
metadata goes to the USB bus and back through decryption. A kernel filesystem gets the page cache
for free; we get nothing.

---

## 2. "Can we move SAF exposure into C to remove JNI round trips?"

**No — and it would make things worse.** `DocumentsProvider`, `ContentProvider`, `MatrixCursor`,
`ParcelFileDescriptor`, `StorageManager` are framework Java APIs with no NDK equivalent. A C
implementation would have to call *up* into Java for all of it: strictly more JNI, not less.

The round trips that cost us are not at the SAF boundary. They are in the **middle of the stack**,
between the filesystem driver and the block device (§1).

**The legitimate version of the idea is to push the boundary *down*, not up**: make one JNI call per
client read, and give native code direct access to the device so it never calls back. That is
option 5 below — real, and the only thing that raises the ceiling, but also the largest change here.
Do it last, and only if measurement says transport is still the limit.

---

## 3. Options

### Tier 1 — cheap, high value, low risk

#### 1. Cache decrypted blocks

The single biggest win for *perceived* responsiveness, because it removes both the USB round trip
and the decryption on a hit. An LRU of decrypted blocks between `NativeDecryptedBlockDevice` and the
filesystem driver:

- **Sized dynamically** from `ActivityManager.getMemoryClass()` — a few MB on low-end devices, tens
  of MB on modern ones. Make it a setting for power users with large drives.
- **Cache the plaintext**, not ciphertext — a hit must skip the crypto too.
- **Write-through**, invalidating the block on write. Do not write-back: there is no journaling
  here, and a mid-write unplug on a VeraCrypt volume is unrecoverable by ordinary tools.
- **Per mount.** On unmount or lock, **zero the cache** — it holds plaintext from an encrypted
  volume. This is a security requirement, not housekeeping.

Metadata is where this pays off most: FAT chains, exFAT bitmaps and directory clusters are read over
and over. Caching them is most of what makes browsing stop feeling like network storage.

#### 2. Readahead

Detect sequential access and prefetch the next N blocks on a background thread, into the cache from
(1). This is what makes media playback and large copies feel native rather than stuttery. Modest
complexity, large perceived gain. Keep the window adaptive and drop it on a seek.

#### 3. Batch the crypto

BACKUP_PLAN §7.3: bulk `xtsCryptRange` transforming in place with one key schedule, then a native
handle holding the expanded schedule for the mount's lifetime. Removes ~32,768 JNI crossings and
~65,536 key expansions per 16 MiB, and the second full-size buffer allocation.

#### 4. Stop `FindClass`-ing per block in `io.c`

Cache the `jclass` (as a `NewGlobalRef`) and both `jmethodID`s once in `JNI_OnLoad`. Replace the
per-call `NewByteArray` with a preallocated direct `ByteBuffer` per `exfat_dev`, so the data lands
in native memory with no `GetByteArrayRegion` copy.

~30 lines, no behaviour change, removes the worst per-call overhead on the exFAT path.

#### 5. Metadata caches in the provider

BACKUP_PLAN §7.8: a path → `UsbFile` map and per-directory listing cache on the `DocumentSource`,
invalidated on write and unmount. Removes the full path re-walk (and full directory re-list) that
currently happens on every `queryDocument` / `openDocument`. Plus free-space caching, so
`queryRoots` stops triggering a full exFAT allocation-bitmap scan.

#### 6. Larger SCSI transfers

Every SCSI `READ(10)` costs CBW + data + CSW — three USB transactions of fixed overhead. Reading
4 KB per command wastes most of the bus; 64–128 KB amortizes it. **Measure what sizes
`ByteBlockDevice` and `UsbFileStreamFactory` actually request today** before tuning — this may be
a one-constant change with a large payoff, or already fine.

#### 7. Per-device I/O lock

BACKUP_PLAN §7.2. Correctness, not speed — but it is a prerequisite for anything that adds
concurrency below, and FAT32 is racy today.

### Tier 2 — moderate

#### 8. Per-device `ProxyFileDescriptor` handler threads

One `HandlerThread` (`proxyHandler`) currently serializes every open file read across *every*
mounted drive. Two apps reading two files on two different drives block each other for no reason.
Give each device its own handler thread, paired with (7). Also directly benefits the two-USB
backup case.

#### 9. Direct `ByteBuffer`s end to end

Replace `GetByteArrayElements` / `NewByteArray` with `allocateDirect` +
`GetDirectBufferAddress` on the hot paths, so buffers are never copied between the Java heap and
native memory. Pairs naturally with (3) and (4).

### Tier 3 — large; only if measurement still shows transport as the limit

#### 10. Native USB transport and a native block-device stack

There is no NDK USB host API, **but** `UsbDeviceConnection.getFileDescriptor()` returns a real fd
for the `usbfs` node, and native code can issue `USBDEVFS_*` ioctls on it directly — this is exactly
how libusb's Android port works. That makes it possible to move the whole mass-storage transport
into C:

```c
struct otg_blockdev {
    void* ctx;
    int (*read)(void* ctx, uint64_t off, size_t len, void* out);
    int (*write)(void* ctx, uint64_t off, size_t len, const void* in);
};
```

layered as `usb_blockdev` → `crypt_blockdev` (persistent key schedule, in place) →
`cache_blockdev` (the LRU from (1)), with libexfat on top. The result:

- **Zero JNI upcalls.** `exfat_pread` talks to a native vtable. One JNI call per client read.
- **Asynchronous, pipelined USB.** `USBDEVFS_SUBMITURB` is async, so multiple URBs can be in flight.
  `bulkTransfer` is synchronous and cannot overlap commands at all — this is the one change that
  lifts the transport ceiling rather than just avoiding waste above it.

The costs are real: a hand-rolled USB mass-storage transport is a meaningful amount of new code in
the riskiest part of the stack, it diverges from libaums (losing upstream fixes), and FAT32 would
need a native driver too or it keeps the old path. **Do not start here.** Tier 1 may well make this
unnecessary.

---

## 4. Sequencing

1. **Build the benchmark harness first** (§5). Without it, everything below is guesswork.
2. Tier 1 items (1)–(7). They are independent of each other and each is individually verifiable.
3. Re-measure. Decide whether Tier 2 is worth it.
4. Tier 3 only if the numbers say transport, not overhead, is the wall.

Expected shape of the result: (1), (2) and (5) fix *responsiveness* (browsing, thumbnails, seeking);
(3), (4) and (6) fix *throughput* (large copies, and later backups). Both matter, and they are
largely independent.

---

## 5. Measurement harness

Add to the existing QEMU E2E suite (`scripts/run_e2e_tests.sh`), so every change is justified by a
number and regressions are caught:

| Benchmark | Targets |
|---|---|
| Sequential read, 100 MB file | throughput (MB/s) |
| Sequential write, 100 MB file | throughput (MB/s) |
| List a 10,000-entry directory | wall time, first byte to last |
| Open 200 files in a 10,000-entry directory | total time (the O(n²) thumbnail case) |
| Random 4 KB reads across a large file | IOPS, seek responsiveness |
| `queryRoots` in a loop | exposes the exFAT bitmap rescan |

Run each against **FAT32 plain, FAT32 VeraCrypt, exFAT plain, exFAT VeraCrypt** — the four
combinations have materially different profiles, and a change that helps one can regress another.

Also record allocation counts and GC activity, not just wall time: the per-sector churn in §7.3 is
partly a GC-pressure problem and will not show up cleanly in a throughput number alone.

---

### 5.1 Test hardware

**The emulator cannot produce performance numbers.** QEMU's emulated `usb-storage` does not
reproduce bulk-transfer latency, SCSI round-trip cost or flash behaviour. It remains the right tool
for correctness (and every Phase A change is behaviour-preserving, so the existing suite passing
unchanged is the proof). Numbers require real hardware.

**Capacity is not the constraint — speed is.** A slow USB 2.0 stick makes everything look I/O bound
and hides exactly the software overhead this document is about; you cannot tell whether an
optimization worked. A fast USB 3.x drive keeps the transport out of the way so per-sector JNI,
allocation churn and GC pressure become visible. 64 GB is ample.

Worth having:

| | Why |
|---|---|
| A fast USB 3.x flash drive, 64 GB | Primary benchmark target — exposes software overhead |
| A cheap slow drive | Cross-check: what is software cost vs. transport cost |
| A second drive + **powered** USB-C hub | Requirement 2 (USB → USB), later |

Note many phones' OTG ports are electrically USB 2.0 even with a USB-C connector, capping practical
throughput around 35–40 MB/s. A bus-powered SSD may also exceed what the phone will supply; a good
flash drive is the safer choice.

Use a drive with nothing valuable on it — the write and backup-destination tests write to it, and
creating a VeraCrypt volume wipes it.

### 5.2 You do not need a large drive to reproduce large-drive problems

The metadata costs in BACKUP_PLAN §7.8 scale with **cluster count and file count**, not capacity —
so force them by choosing the format parameters:

| Fixture | Command | Effect |
|---|---|---|
| exFAT, inflated bitmap | `mkfs.exfat -c 4096` on 64 GB | 16.7M clusters → **2 MB allocation bitmap**, identical to a 2 TB drive at default 128 KB clusters. Rescanned on every `queryRoots`. |
| exFAT, default | `mkfs.exfat` on 64 GB | ~128 KB clusters → 64 KB bitmap. The baseline to compare against. |
| FAT32, large FAT | `mkfs.vfat -F 32 -s 8` on 64 GB | 4 KB clusters → 16.7M entries → **64 MB FAT** to walk |
| Dense directory | script 10,000 small files into one dir | The O(n²) path re-walk and `MatrixCursor` cases |
| Large files | a few 1–4 GB files | Sequential throughput; long enough to measure at ~10 MB/s |

Generate these with a script alongside `scripts/generate_testdata.sh`, so fixtures are reproducible
and can be rebuilt on any drive rather than being a hand-made artifact of one stick.

Run the matrix against **FAT32 plain, FAT32 VeraCrypt, exFAT plain, exFAT VeraCrypt** — four
materially different profiles, and a change that helps one can regress another.

## 5.3 Baseline measurements

Captured with `scripts/prepare_test_usb.sh` fixtures via the in-app harness
(`app/src/main/java/app/fayaz/otgmaster/bench/Benchmark.kt`, triggered by
`adb shell am broadcast -a app.fayaz.otgmaster.RUN_BENCHMARK`).

### exFAT, VeraCrypt (AES/SHA-512), 4096-byte clusters — OnePlus 7, Android 16

2026-09-23. PNY USB 3.2.1 FD, 57 GiB, ~4 GiB free. Whole run took **3.6 hours**.

| Measurement | Result |
|---|---|
| `freeSpace` | first 114.2 ms, then **85.8 ms** every call |
| Block read, 4 KiB span | 7.14 MB/s |
| Block read, 64 KiB span | **22.72 MB/s** |
| Block read, 512 KiB span | 22.60 MB/s |
| Block read, 4096 KiB span | 21.47 MB/s |
| List `dense_short` (10,000) | cold **159,837 ms**, warm 32.9 ms |
| List `dense_lfn` (10,000) | cold **349,773 ms**, warm 167.7 ms |
| Resolve depth-10 path | 46.0 ms each |
| Sequential read, 32 KiB buffer | 0.78 MB/s |
| Sequential read, 128 KiB buffer | **1.19 MB/s** |
| Sequential read, 512 KiB buffer | 1.14 MB/s |
| Random 4 KiB read (2 GiB file) | **75,958 ms each** (~0.013 IOPS) |
| Open 50 files in `dense_short` | 129.3 ms each |
| Open 50 files in `dense_lfn` | 158.3 ms each |

### FAT32, VeraCrypt (AES/SHA-512), 4096-byte clusters — OnePlus 7, Android 16

2026-09-24. Same phone, same drive model, same fixtures. Whole run took **61 seconds**.

| Measurement | Result |
|---|---|
| `freeSpace` | **0.0 ms** (cached FSInfo field) |
| Block read, 4 KiB span | 2.56 MB/s |
| Block read, 64 KiB span | 12.42 MB/s |
| Block read, 512 KiB span | **22.03 MB/s** |
| Block read, 4096 KiB span | 19.67 MB/s |
| List `dense_short` (10,000) | cold **1,256 ms**, warm 556 ms |
| List `dense_lfn` (10,000) | cold **1,367 ms**, warm 230 ms |
| Resolve depth-10 path | 11.8 ms each |
| Sequential read, 32 KiB buffer | 6.35 MB/s |
| Sequential read, 512 KiB buffer | **7.45 MB/s** |
| Random 4 KiB read (256 MiB file) | **7.8 ms** (128 IOPS) |
| Open 50 files in `dense_short` | 552.9 ms each |
| Open 50 files in `dense_lfn` | 221.9 ms each |

### exFAT vs FAT32 — the decisive comparison

Both filesystems sit on the *same* block stack (`NativeDecryptedBlockDevice` ->
`LibaumsRawBlockDevice` -> SCSI), so anything that differs is attributable to the
layer above it. exFAT goes Kotlin -> JNI -> libexfat -> **JNI upcall per block,
with a `FindClass` every time** -> Kotlin -> block device. FAT32 is pure Kotlin
(libaums) straight to the block device.

| Measurement | exFAT | FAT32 | FAT32 advantage |
|---|---|---|---|
| Block read, 512 KiB | 22.60 MB/s | 22.03 MB/s | **same** (shared layer) |
| `freeSpace` | 85.8 ms | 0.0 ms | eliminated |
| Sequential read (best) | 1.19 MB/s | 7.45 MB/s | **6.3x** |
| List 10,000 entries, cold | 159,837 ms | 1,256 ms | **127x** |
| List 10,000 (LFN), cold | 349,773 ms | 1,367 ms | **256x** |
| Random 4 KiB read | 75,958 ms | 7.8 ms | **~9,700x** |
| Path resolve, depth 10 | 46.0 ms | 11.8 ms | 3.9x |
| Open file in dense dir | 129.3 ms | 552.9 ms | **0.23x (FAT32 worse)** |

**The block layer is confirmed innocent.** 22.0 vs 22.6 MB/s at 512 KiB spans —
identical within noise, on the same hardware, both including USB transport and
XTS decryption. Every large difference above lives in the filesystem layer.

**The `io.c` JNI bridge is the single biggest cost, and it is exFAT-only.** FAT32
reaches the same blocks 6x faster sequentially, 127x faster on a cold directory
listing and ~9,700x faster on a random read, using a path with no JNI upcalls.
That makes §3 option 4 — cache the `jclass`/`jmethodID` at `JNI_OnLoad` and use
a preallocated direct `ByteBuffer` — the highest-value change available, and it
was filed as a ~30-line cleanup.

**Random access on exFAT is the worst pathology.** Reaching an offset walks the
cluster chain, and on exFAT every step crosses the bridge and hits USB; on FAT32
the FAT is resident, so the same operation costs 7.8 ms instead of 76 seconds.

**FAT32 is still 3x off its own block layer** (7.45 vs 22.03 MB/s), so libaums
overhead and the shared per-sector XTS loop (§7.3) still matter once the bridge
is fixed. They are the next ceiling, not the current one.

**The one place exFAT wins is warm/repeat access**: 33 ms to re-list a
10,000-entry directory versus FAT32's 556 ms, and 129 ms vs 553 ms to open a file
inside one. libexfat caches directory state that libaums rebuilds. So the two
filesystems fail in opposite directions — exFAT is catastrophic cold and good
warm, FAT32 is decent cold and mediocre warm. A block cache (§3 option 1) helps
both, for different reasons.

**Caveat on test ordering.** Within a run, `dense_short` is measured before
`dense_lfn`, and on FAT32 the later directory came out *faster* (230 vs 556 ms
warm) despite having larger entries — the shared FAT and cluster chains were
warmer by then. Per-directory FAT32 figures should not be compared against each
other; only across filesystems, where the ordering is identical.

### What the numbers say

**The filesystem layer costs ~19x, not the transport.** Block reads reach
22.7 MB/s *including* USB and XTS decryption; the same drive read through
`ExFatFile.read()` manages 1.19 MB/s at best. So USB and crypto together are not
the bottleneck — everything layered above them is. This reorders the priorities
in §3: the `io.c` ping-pong (option 4, filed as a ~30-line cleanup) looks like
the single highest-value change, ahead of the bulk-crypto work in §3 option 3.

**Random access is O(cluster index), with a USB round trip per step.** 76 seconds
for one 4 KiB read at a random offset in a 2 GiB file. Reaching offset N means
walking the cluster chain from the start — ~262,000 steps on average for this
file at 4 KiB clusters — and each step pays a JNI upcall, an XTS decryption and
potentially a USB read. This is the strongest argument for the block cache
(§3 option 1): the FAT would be resident after the first walk.

**Cold directory listing is effectively a hang**, at 160 s and 350 s for 10,000
entries. Warm is 33 ms / 168 ms, so libexfat does cache directories — the cost
is entirely in the first read. Long filenames cost **2.2x** more than 8.3 names,
confirming §7.4's LFN analysis with a measurement.

**Opening a file by name in a large directory costs ~130 ms**, because each open
re-lists the directory (§7.8). Browsing 10,000 files means that per thumbnail.

**Large spans stop helping past 64 KiB.** 64 KiB and 512 KiB are indistinguishable
(22.7 vs 22.6 MB/s), and 4 KiB spans collapse to 7.1 MB/s on per-operation
overhead. The 120 KiB transfer chunk chosen for the USB transfer fix sits in the
flat part of that curve, so it costs nothing.

**Run-to-run variance is significant.** The 4 MiB block read measured 6.74 MB/s
in one run and 21.47 MB/s in the next with no code change, most likely GC state.
Single samples cannot be trusted when judging whether an optimisation worked —
repeat every comparison.

## 5.4 Result: the JNI bridge was NOT the bottleneck

Option 4 of §3 (cache `jclass`/`jmethodID` at first use, replace the per-call
`NewByteArray` with one reused direct `ByteBuffer`) was implemented and measured
against two baseline samples on the same phone, drive and fixtures.

| Measurement | Baseline R1 | Baseline R2 | After fix |
|---|---|---|---|
| Seq read, 32 KiB buffer | 0.78 MB/s | 1.03 MB/s | 1.13 MB/s |
| Seq read, 128 KiB buffer | 1.19 MB/s | 1.07 MB/s | 1.13 MB/s |
| Seq read, 512 KiB buffer | 1.14 MB/s | 1.05 MB/s | 1.13 MB/s |
| List 10,000 cold | 159,837 ms | 176,183 ms | 163,397 ms |
| List 10,000 LFN cold | 349,773 ms | 366,191 ms | 356,285 ms |
| Open in dense dir | 129.3 ms | 123.7 ms | 122.2 ms |
| Block read 512 KiB (control) | 22.60 MB/s | 23.79 MB/s | 23.00 MB/s |

**No effect.** Every figure lands inside the noise band the two baseline samples
established. `FindClass` per call was real work, but ART resolves it far more
cheaply than assumed, and it was never the dominant cost. The hypothesis in §5.3
("the highest-value change available") was wrong.

### What the numbers actually point at

Sequential read is **1.13 MB/s regardless of the caller's buffer size** — 32, 128
and 512 KiB are identical. A fixed granularity below the caller is therefore the
constraint, and the source confirms it (`io.c`, `exfat_generic_pread`):

```c
lsize = MIN(CLUSTER_SIZE(*ef->sb) - loffset, remainder);
exfat_pread(ef->dev, bufp, lsize, exfat_c2o(ef, cluster) + loffset);
cluster = exfat_next_cluster(ef, node, cluster);
```

**One pread per cluster.** At 4 KiB clusters a 512 KiB read is 128 separate 4 KiB
reads, each its own SCSI command. Decomposing the loss:

```
23.0  MB/s   block layer, 512 KiB spans
 7.19 MB/s   block layer, 4 KiB spans      <- 3.2x lost purely to request size
 1.13 MB/s   through libexfat              <- 6.4x lost above that
```

The 3.2x is request granularity. Note the block-layer figure already includes the
per-sector XTS work (the benchmark calls `NativeDecryptedBlockDevice.readBlocks`
directly), so §7.3's key-schedule churn is **inside** the 7.19 MB/s and cannot
account for the remaining 6.4x.

Candidates for that 6.4x, in order of suspicion — `exfat_next_cluster` and
`exfat_advance_cluster` issue their own preads to walk the FAT, so a sequential
read may cost roughly twice as many round trips as the data alone requires; plus
the JNI round trip and buffer copies per cluster.

**Do not guess again.** The next step is to instrument `exfat_pread` — count calls
and bytes per logical operation — so the call pattern is known before choosing a
fix. One wrong hypothesis has already been paid for; a counter is cheap.

### Revised priority

1. **Instrument the pread call pattern.** Cheap, and decides everything below.
2. **Coalesce and/or cache below the cluster granularity** (§3 options 1 and 2).
   A block cache with readahead turns 4 KiB preads into large underlying reads and
   would also serve repeated FAT lookups from memory — addressing the 3.2x and
   plausibly much of the 6.4x, and helping cold listings and random access too.
3. **Bulk XTS** (§7.3) — still worth doing, but now known to be bounded by the
   22-23 MB/s the block layer already achieves, not a 20x win.

### Correction to §7.8

`freeSpace` was unchanged at 85.9 ms, and the mechanism given in §7.8 is wrong: it
does not re-read the bitmap over USB. The bitmap is resident after mount, and
`exfat_count_free_clusters` iterates **15.1 million clusters one bit at a time**,
so the 86 ms is pure CPU. The fix is to cache the result or use a word-at-a-time
popcount, not to cache a read.

## 5.6 Result: the cache needs per-filesystem readahead

The block cache was a large win for exFAT and a regression for FAT32. Resolving
that needed two things the earlier measurements could not provide: same-session
A/B, and a runtime toggle.

### Cross-session comparison was unsound

The block-layer control — which reads through `cache.uncached` and therefore
cannot be affected by the cache — measured **22.03 MB/s in one session and
12.94 MB/s in another with identical code**. That drift is larger than the effect
being measured, so every cross-session conclusion drawn before this point is
unreliable, including an earlier claim that the cache caused a "2x" FAT32
listing regression. `OtgMasterState.cacheConfig` now allows on/off and line size
to be set per mount, so both arms run minutes apart under one set of conditions.

A second artefact worth recording: a run taken with the screen off measured the
control at 5.05 MB/s, because Doze throttles the CPU and decryption is CPU-bound.
The benchmark now prints `interactive` / `deviceIdle` / `powerSave` and CPU
frequencies, and flags the run as not comparable when the device is idle.

### FAT32, same session: the cache traded listing for streaming

| metric | cache off | cache on (64 KiB) |
|---|---|---|
| Sequential read, 512 KiB | 2.94 MB/s | **10.28 MB/s** (3.5x better) |
| Random 4 KiB | 30.3 ms | **10.5 ms** (2.9x better) |
| List 10,000 cold | **1,356 ms** | 2,353 ms (1.7x worse) |
| List 10,000 LFN cold | **1,594 ms** | 5,660 ms (3.6x worse) |
| Write 16 MiB | **1.85 MB/s** | 1.08 MB/s (1.7x worse) |

### Readahead sweep found a setting that loses nothing

|  | off | **4K** | 8K | 16K | 32K | 64K |
|---|---|---|---|---|---|---|
| List 10,000 cold (ms) | 1,363 | **1,185** | 1,471 | 1,512 | 1,820 | 2,355 |
| List LFN cold (ms) | 1,672 | **1,613** | 2,688 | 3,015 | 3,755 | 5,570 |
| Path resolve (ms) | 9.6 | **6.3** | 9.0 | 7.2 | 8.8 | 7.4 |
| Random 4 KiB (ms) | 28.9 | **10.0** | 7.9 | 7.2 | 8.7 | 9.8 |
| Sequential (MB/s) | 2.91 | 2.94 | 4.41 | 6.35 | 8.50 | **10.31** |

Cold listing degrades monotonically as the line grows — walking a large FAT
sparsely re-fetches most of each line for nothing — while sequential read improves
monotonically for the opposite reason. **4 KiB is the only setting that beats
no-cache on every metric and loses on none.** exFAT wants the opposite (64 KiB,
90x on cold listing) because its metadata sits in a small region read repeatedly.

Hence `readAheadFor()`: 64 KiB for exFAT, 4 KiB for FAT.

### Writes: patch cached lines instead of invalidating

Invalidating on write forced the next read-modify-write to refetch a whole line.
`ByteBlockDevice` does a RMW per unaligned write, so a 16 MiB write read **279 MiB**
back from the device. Patching in place is safe — the lock is held and this is the
only writer, so the patched line is exactly what the device holds:

| | before | off | after |
|---|---|---|---|
| Write 16 MiB | 1.08 MB/s | 1.28 MB/s | **1.60 MB/s** |
| Blocks read from device | 101,120 | — | **66,176** |

### Confirmation with the final defaults

Medians over 3 read and 2 write repeats per arm, one session, FAT32:

| metric | off | default (4 KiB) | change |
|---|---|---|---|
| Random 4 KiB | 32.0 ms | **11.1 ms** | **2.88x better** |
| Path resolve | 10.2 ms | **6.6 ms** | 1.55x better |
| List LFN cold | 1,791 ms | **1,598 ms** | 1.12x better |
| List 10,000 cold | 1,255 ms | **1,188 ms** | 1.06x better |
| Sequential 512 KiB | 2.93 MB/s | 2.92 MB/s | neutral |
| Write 16 MiB | 1.46 MB/s | 1.45 MB/s | neutral |
| Block layer (control) | 12.66 MB/s | 13.70 MB/s | within drift |

No regressions. Writes verified byte-for-byte in every run, including a pass after
invalidating the whole cache, which proves the bytes reached the device rather
than being served from memory.

The cost is giving up FAT32's 3.5x sequential gain. That favours the common case —
browsing and writing — over streaming throughput, and is a one-constant change if
the trade should be weighted differently.

## 5.7 Cross-device validation: no regressions on either filesystem

Same-session A/B on two phones with hash-identical builds, cache off versus the
per-filesystem defaults. Medians where repeated; the block-layer figure is a
control that cannot be affected by the cache, and it matched in every arm.

### exFAT — OnePlus 7 (64 KiB readahead)

| metric | cache off | cache on | |
|---|---|---|---|
| List 10,000 cold | 164,502 ms | **2,016 ms** | **81.6x better** |
| List 10,000 LFN cold | 340,297 ms | **3,533 ms** | **96.3x better** |
| Sequential, 512 KiB | 1.30 MB/s | **20.01 MB/s** | **15.4x better** |
| Write 16 MiB | 0.25 / 0.26 MB/s | **0.41 / 0.52 MB/s** | **1.6-2x better** |
| Random 4 KiB | **2.6 ms** | 3.0 ms | 15% worse |
| Block layer, 512 KiB (control) | 24.37 MB/s | 24.77 MB/s | matched |

### FAT32 — OnePlus 7 (4 KiB readahead)

| metric | cache off | cache on | |
|---|---|---|---|
| Random 4 KiB | 32.0 ms | **11.1 ms** | **2.88x better** |
| Path resolve | 10.2 ms | **6.6 ms** | 1.55x better |
| List LFN cold | 1,791 ms | **1,598 ms** | 1.12x better |
| List 10,000 cold | 1,255 ms | **1,188 ms** | 1.06x better |
| Sequential, 512 KiB | 2.93 MB/s | 2.92 MB/s | neutral |
| Write 16 MiB | 1.46 MB/s | 1.45 MB/s | neutral |

### FAT32 — Pixel 10 Pro XL, Android 17 (4 KiB readahead)

| metric | cache off | cache on | |
|---|---|---|---|
| Random 4 KiB | 12.1 / 12.9 ms | **5.8 / 5.3 ms** | **2.25x better** |
| Path resolve | 4.6 / 6.3 ms | **2.9 / 3.0 ms** | **1.85x better** |
| Write 16 MiB | 1.53 / 1.66 MB/s | **1.87 / 1.91 MB/s** | **1.18x better** |
| List LFN cold | 985 / 905 ms | **895 / 889 ms** | 1.06x better |
| List 10,000 cold | 822 / 685 ms | 705 / 737 ms | 1.05x better |
| Sequential, 512 KiB | 6.98 / 6.95 MB/s | 6.96 / 6.98 MB/s | neutral |
| Block layer (control) | 17.59 / 17.26 MB/s | 18.16 / 16.96 MB/s | matched |

### Write correctness

**The first version of this check was too weak to mean much.** It wrote one 64 KiB
pattern 256 times and compared byte-for-byte. Against a repeating pattern, two
swapped chunks, an off-by-one in the offset arithmetic, or a read served from the
wrong cached line all compare equal — precisely the failure modes a block cache
introduces. It was passing on content it could not distinguish.

The check now uses position-dependent content, so misplacement counts as
corruption:

```kotlin
private fun expectedByteAt(offset: Long): Byte =
    (((offset * 2654435761L) xor (offset ushr 13)) and 0xFF).toByte()
```

and verifies four things rather than one — byte-for-byte content, the file length
(which a content comparison alone cannot catch), and a SHA-256 over the whole file,
across three passes that each re-open the file by name:

| Pass | Discards | Proves |
|---|---|---|
| `(cached)` | nothing | the write path is self-consistent |
| `(cache dropped)` | every cached line | bytes reached the device, not just memory |
| `(remounted)` | full unmount + remount | FAT chain and directory entries were written back |

The remount pass is the one that earns its keep: a cache invalidation leaves
libexfat's and libaums' in-memory metadata intact, so a corrupt cluster chain that
was never flushed would still verify.

**Result: 32/32 cases passed** — 8 before the fix below, 8 after, and 16 more while
gathering write-throughput repetitions, across both filesystems, both phones, cache
on and off. Every pass reported the correct length and a matching SHA-256
(`8645696846c56668…`, identical on both devices, which also confirms the two phones
wrote byte-identical content).

### The strengthened check crashed the app — twice

Tightening the verification was what exposed it. Both crashes were
use-after-unmount in libexfat:

```
signal 11 (SIGSEGV), fault addr 0x1a00000060
  exfat_utf16_to_utf8 / exfat_get_name / ExFatNative_getRootNode

signal 11 (SIGSEGV), fault addr 0x6c
  exfat_truncate / exfat_generic_pwrite / ExFatNative_writeFile
```

The second fault address identifies the cause exactly. `exfat_truncate` opens with
`bytes2clusters(ef, node->size)`, which reads `sb->sector_bits` — and `sector_bits`
is at offset **0x6C** of `struct exfat_super_block` (`exfatfs.h`). A fault at `0x6c`
is `ef->sb == NULL`: a `struct exfat` that `exfat_unmount` had already freed.

Only `freeSpace()`, `flush()`, `close()` and `finalize()` tested `isUnmounted`.
Every other native entry point — `read`, `write`, `listFiles`, `delete`,
`createFile`, `createDirectory`, `rename`, `setLength`, and the lazy `getRootNode`
— passed `exfatPtr` straight into C. The JNI `if (!ef || !node)` guards cannot
catch this: the pointers are non-NULL, they are dangling.

This is reachable from ordinary use, not only from the harness: a SAF client with an
open `ProxyFileDescriptor` when the user unmounts, a listing or copy in flight on a
worker thread, or the stick pulled mid-write. `unmountDrive` already drained SAF
release callbacks before unmounting, which was a point fix for one path of this
same bug — it did not cover the others.

Fix: every native call routes through `ExFatFileSystem.withNative`, which tests the
flag under the same lock `unmount()` sets it under and raises `IOException` instead
of dereferencing. `close()`/`flush()` keep their silent no-op, being idempotent
teardown. After the fix the same 8 cases ran with no crash on either device.

**Note on what found this.** The crash was not caught by any assertion. It was
caught because a case emitted no output at all, and the runner reported `APP DIED`.
The hash checks themselves all passed — including in the run that crashed.

### Conclusions

- **No regressions on any metric on either filesystem or phone.** The one
  exception is exFAT random 4 KiB, 2.6 -> 3.0 ms — 0.4 ms, and the only place the
  cache loses anything.
- **Writes are unaffected — and the earlier claim here was wrong.** This section
  first reported "1.18x on the Pixel, 1.6-2x on exFAT", from one or two samples per
  arm. Six repetitions per arm show that USB flash write variance is far larger than
  any effect the cache has:

  | | n | min | median | max | ratio | ranges |
  |---|---|---|---|---|---|---|
  | exFAT / OnePlus, cache off | 6 | 0.84 | 0.95 | 1.37 | | |
  | exFAT / OnePlus, cache on | 6 | 0.82 | 0.94 | 1.12 | 0.99x | overlap |
  | FAT32 / Pixel, cache off | 6 | 0.78 | 1.61 | 2.33 | | |
  | FAT32 / Pixel, cache on | 6 | 0.89 | 2.05 | 2.63 | 1.27x | overlap |

  MB/s for a 16 MiB write. A single arm spans 3x on the Pixel, so the medians are
  not separable — SLC cache exhaustion, wear levelling and controller garbage
  collection dominate. The honest statement is **no measurable write effect in
  either direction**. The invalidate-to-patch change is still correct (it avoids a
  refetch that serves no purpose) but it does not show up as throughput.

  Lesson repeated from §5.6: a ratio between single samples is not a result. The
  read improvements survive this treatment — 81x cold listing is not noise — but
  the write figures never should have been stated.
- The Pixel is substantially faster than the OnePlus on FAT32 (cold listing ~750 ms
  vs ~1,200 ms, sequential 6.97 vs 2.93 MB/s), so absolute figures are not
  comparable across devices — only the within-device arms are.
- The Pixel ran eight consecutive mount/unmount cycles with no failures, which
  independently exercises the refreshDevices fix: that device is where a mount
  would previously succeed and then read as an empty volume.

## 5.8 The SAF path: what clients actually get

Every measurement in this document before this section called `UsbFile` directly.
No real client does that. A SAF client's read crosses `ContentResolver`, the
provider, a `ProxyFileDescriptor` and the FUSE bridge, landing on a
`ProxyFileDescriptorCallback` dispatched on one process-wide `HandlerThread`. So
the figures above describe a path nothing uses, and the cost of the real one was
invisible.

Measured with the harness's `saf` section, 32 MiB capped sequential read, 64 KiB
buffer, same file read both ways in the same session:

| | exFAT / OnePlus 7 | FAT32 / Pixel 10 Pro XL |
|---|---|---|
| Single stream through the provider | 7.93 MB/s | 3.38 MB/s |
| Same file, direct `UsbFile` call | 23.04 MB/s | 5.07 MB/s |
| **Provider overhead** | **2.90x** | **1.50x** |
| Two streams, one drive, aggregate | 16.07 MB/s (**2.03x**) | 4.79 MB/s (**1.43x**) |

The FAT32 column reproduced to within 1% across two sessions on different builds
(3.35 / 5.05 / 1.51x / 4.81), so unlike the write-throughput figures in §5.7 these
are stable enough that small differences carry signal.

### Two consequences

**Published read figures overstate what apps see.** Sequential reads on exFAT lose
15 MB/s to the provider — more than the block cache ever won back. Any claim about
user-visible read speed has to be discounted by 1.5x on FAT32 and 2.9x on exFAT.

**The shared ProxyFileDescriptorThread is not the bottleneck.** Two streams on one
drive scaled 2.03x on exFAT and 1.43x on FAT32 — on a single thread. On exFAT that
is despite `ExFatFileSystem.lock` serialising every native call. If either the
shared handler or the filesystem lock were binding, aggregate throughput would have
been at or below the single-stream figure instead of double it.

The single-stream path is therefore **latency-bound**: the thread idles waiting for
the FUSE bridge between callbacks, and a second stream fills the gaps. That is a
measured rejection of replacing the shared handler with per-drive handlers or a
thread pool — including for multiple drives, since the cross-drive gain would come
from the same latency-hiding one thread already provides. (A thread pool is
separately unsafe: libaums has no locking, so concurrent callbacks on one drive
would race its FAT cache and directory entries.)

**Correction.** This section first concluded that the lever was "fewer, larger
callbacks". That is wrong, and the FD relay spike is what showed it: the callbacks
are *already* large. With a client reading in 64 KiB blocks, `onRead` arrives at
**131,072 bytes** — the kernel coalesces to 128 KiB before it ever reaches the
callback:

```
worker: onRead off=0      size=131072 pid=4294
worker: onRead off=131072 size=131072 pid=4294
```

So the 1.50x–2.90x provider penalty is not callback granularity. What remains is
the FUSE round trip itself: a kernel transition per request, the request handed to a
userspace handler thread, and the data copied back through the bridge. None of that
is reduced by batching we already get, and none of it is reduced by more threads.

That leaves no identified lever on the provider path. It may simply be the cost of
SAF on this platform, which would make it a fixed tax to plan around — for example
by having the backup engine use `UsbFile` directly rather than going through the
provider — rather than something to optimise away.

Cross-drive throughput remains unmeasured; it needs two drives through a powered hub
and is reported as `saf par cross` when present.

## 5.9 Re-baseline after the correctness fixes

The FAT32 figures in §5.5-§5.8 were taken before the defects in
`docs/VENDOR_FIXES.md` V1-V6 were fixed, and V6 changed the read path enough to
invalidate them. Re-measured on a Pixel 10 Pro XL, FAT32 VeraCrypt volume, both
cache arms in one session.

### V6 lifted sequential reads, and it is visible in both arms

| Buffer | Pre-fix (off / on) | Post-fix (off / on) |
|---|---|---|
| 32 KiB | 4.53 / 4.94 MB/s | 6.51 / 6.99 MB/s |
| 128 KiB | 4.99 / 5.09 MB/s | **14.94 / 14.39 MB/s** |
| 512 KiB | 4.97 / 5.12 MB/s | 11.31 / 14.92 MB/s |

The claim here is the **change of shape**, not the ratio. Before, throughput was
flat across buffer sizes in both arms — the signature of `ClusterChain.read`
issuing one SCSI command per cluster no matter what the caller asked for. After, it
scales with buffer size. Because the change appears in the cache-off arm too, it is
attributable to the coalescing fix rather than to the cache or to session drift.

### The cache, re-measured post-fix

| | Cache off | Cache on | Effect |
|---|---|---|---|
| Random 4 KiB | 24.2 ms | 7.2 ms | **3.36x** |
| Cold list, 10,000 short names | 927 ms | 727 ms | 1.27x |
| Cold list, 10,000 long names | 1,086 ms | 947 ms | 1.15x |
| Path resolve, depth 10 | 4.3 ms | 3.5 ms | 1.23x |
| Sequential, 128 KiB | 14.94 MB/s | 14.39 MB/s | neutral |
| Dense opens, 50 files | 293.5 / 110.0 ms | 286.4 / 113.1 ms | neutral |
| Block layer, 4 MiB span (control) | 20.17 MB/s | 19.41 MB/s | matched |
| SAF provider overhead | 1.66x | 1.66x | unchanged |

The block-layer control matching across arms is what establishes the two runs are
comparable rather than one being clock-throttled — the failure that was first
misread as a regression in §5.7.

**Random 4 KiB is where the cache earns its place on FAT32**, at 3.36x. Its
sequential contribution is now neutral, because V6 removed the fragmentation the
cache had been partly compensating for. Cold directory listing improved less than
the pre-fix measurements suggested (1.15-1.27x against the 2.2x seen earlier),
which is consistent: V4 stopped read-only opens rewriting the parent directory, so
less of the listing cost is now writes the cache could absorb.

### exFAT, re-baselined

OnePlus 7, Android 16, exFAT VeraCrypt volume, both arms in one session.

| | Cache off | Cache on | Effect |
|---|---|---|---|
| Cold list, 10,000 short names | 136,002 ms | 1,679 ms | **81x** |
| Cold list, 10,000 long names | 189,430 ms | 2,651 ms | **71x** |
| Sequential, 512 KiB buffer | (not measured) | 26.46 MB/s | |
| Random 4 KiB | (not measured) | 2.6 ms | |
| Block layer, 4 MiB span (control) | 26.15 MB/s | 24.52 MB/s | matched |

**The cache-off arm is incomplete.** After 325 seconds of cold directory listing it
reported `seq read: no files in large/` — `listFiles()` returned empty for a
directory that the cache-on arm read without trouble. `ExFatFile.listFiles` returns
an empty array when libexfat's `readDir` returns null, so a failure there is
indistinguishable from an empty directory. That is a reporting gap worth closing;
the sequential and random figures for the uncached arm are simply missing rather
than slow.

The 81x listing result reproduces the original motivation for the cache (§5.5) and
is a same-session comparison, so it stands on its own.

### Retraction: the "provider overhead" figure was not measuring overhead

§5.8 reported a 1.50x–2.90x provider penalty, and an earlier revision of this section
claimed the read copy elimination had cut exFAT's to 1.04x. **Both are withdrawn.**
The metric was confounded, and swapping the drives between devices is what exposed
it.

The same drive and the same build, measured on all four device/filesystem pairs:

| | `saf single` | `saf direct` | ratio |
|---|---|---|---|
| exFAT, Pixel 10 Pro XL | 8.36 MB/s | 29.41 MB/s | 3.52x |
| exFAT, OnePlus 7 | 20.77 MB/s | 21.61 MB/s | 1.04x |
| FAT32, Pixel 10 Pro XL | 3.38 MB/s | 5.07 MB/s | 1.66x |
| FAT32, OnePlus 7 | 8.22 MB/s | 7.17 MB/s | **0.87x** |

A ratio below 1.0 cannot be overhead, and that is the tell. The two arms were not
comparable: the direct arm issued explicit **64 KiB** reads, while the kernel
coalesces FUSE reads to **128 KiB** — measured directly in the FD relay spike, where
`onRead` arrives at 131,072 bytes regardless of what the client asked for. So the
ratio mixed provider cost with kernel readahead, in proportions that vary by device.

The benchmark now issues 128 KiB in the direct arm to match, reports it as
`saf ratio` rather than `saf overhead`, and flags any value below 1.0 as
non-comparable. **No provider-overhead figure should be quoted from this document
until that has been re-measured on both devices.**

Two things that do survive:

- **exFAT single-stream SAF throughput more than doubled** on the OnePlus after the
  copy elimination, 8.94 to 20.77 MB/s. That is a same-device comparison of the same
  arm, so it holds independently of the ratio.
- **The rejection of per-drive handler threads stands on other evidence** — a single
  handler thread served two concurrent streams at 2.03x on exFAT and 1.43x on FAT32,
  which is incompatible with it being the bottleneck. Note that the two-stream figure
  is itself device-dependent: 2.67x on the Pixel against slightly *worse* than one
  stream on the OnePlus, so it should not be quoted as a single number either.

**The general lesson, which has now cost three retractions in this document.** §5.7
retracted a write-throughput claim built on cross-session comparison. §5.6 retracted
a listing comparison for the same reason. This one was sound within a session and
still wrong, because it generalised from one device. A ratio is only as good as the
equivalence of its two arms, and "same code, same drive" does not make two *devices*
equivalent.

## 6. The honest ceiling

Without root there is no kernel mount, and two floors cannot be removed:

- **AppFuse round-trip per read.** `openProxyFileDescriptor` routes every read from the client
  process through the kernel's FUSE layer and `system_server` before reaching our callback.
- **USB bulk-only transport.** One SCSI command at a time, unless URBs are pipelined (Tier 3).

So the target is **"browsing feels instant, media plays smoothly, large copies run at bus speed"** —
not parity with internal storage. Tier 1 should get most of the way there; state that goal
explicitly so the work has a defined finish line rather than an open-ended optimization loop.
