# Benchmark results: reference figures and how to read them

Consolidated observations across four devices and two filesystems, for use as a
regression baseline. For *how* to trigger a run see `docs/RUNNING_BENCHMARKS.md`; for
the narrative of how each finding was reached, including three retracted claims, see
`docs/IO_PERFORMANCE.md`.

**Every figure here is a reference point, not a target.** Before treating a difference
as a regression, read the comparison rules in §6 — most of the wrong conclusions
reached while gathering this data came from comparing numbers that were not
comparable.

## 1. Devices

| Short name | Model | Android | SDK | Notes |
|---|---|---|---|---|
| Huawei | ANE-LX1 (P20 Lite) | 9 | 28 | Slowest transport; exposed two bugs the others could not |
| Samsung | SM-M305F (Galaxy M30) | 10 | 29 | On the API-29 boundary for `Documents/` |
| OnePlus | GM1901 (OnePlus 7) | 16 | 36 | |
| Pixel | mustang (Pixel 10 Pro XL) | 17 | 37 | Fastest direct reads |

All drives: 62 GB PNY USB 3.2.1 FD, VeraCrypt AES/SHA-512, PIM 1, 4096-byte clusters,
prepared by `scripts/prepare_test_usb.sh`, bulk-filled to ~4 GiB free.

## 2. Block layer — the control

Reads the uncached decrypted device with no filesystem in the path: USB transport plus
AES-XTS only. **This is the figure to check first.** It should be unaffected by
filesystem, cache or app-level changes, so if it moves between two arms of a
comparison, the arms are not comparable and nothing else in the run means anything.

4 MiB span, both cache arms where measured:

| Device | Filesystem | Cache off | Cache on |
|---|---|---|---|
| Huawei | FAT32 | 7.90 MB/s | 8.11 MB/s |
| OnePlus | exFAT | 26.15 MB/s | 24.52 MB/s |
| Pixel | FAT32 | 20.17 MB/s | 19.41 MB/s |

Transfer size dominates far more than the device does, and it does so on every device.
The full ladder, same run, same drive:

| Span | OnePlus, exFAT | Huawei, FAT32 (cache on) | Huawei, FAT32 (cache off) |
|---|---|---|---|
| 4 KiB | 8.13 MB/s | 2.05 MB/s | 1.65 MB/s |
| 64 KiB | 29.82 MB/s | 5.11 MB/s | 3.84 MB/s |
| 512 KiB | — | 8.81 MB/s | 8.57 MB/s |
| 4096 KiB | 26.15 MB/s | 8.11 MB/s | 7.90 MB/s |

A 4 KiB request gets a quarter to a third of what a 64 KiB one gets. That is why the
block cache uses 64 KiB lines and why V6 (coalescing cluster reads) mattered — both
are about request size, not about caching. Note the ladder also peaks before 4 MiB:
past ~512 KiB there is nothing further to win.

**Screen state matters as much as anything else.** The block layer measured
**7.27 MB/s asleep against 17.26 MB/s awake** on the same device. Decryption is
CPU-bound, so a dozed run looks exactly like a regression. The runner re-asserts
wakefulness and flags any run that dozed as `*** CONTAMINATED ***`.

## 3. Filesystem operations

### 3.1 Cold directory listing, 10,000 entries

The measurement that motivated the block cache.

| Device | Filesystem | Cache off | Cache on | Effect |
|---|---|---|---|---|
| OnePlus | exFAT, short names | 136,002 ms | 1,679 ms | **81×** |
| OnePlus | exFAT, long names | 189,430 ms | 2,651 ms | **71×** |
| Huawei | FAT32, short names | 2,323 ms | 2,777 ms | none (noise) |
| Huawei | FAT32, long names | 2,080 ms | 1,864 ms | none (noise) |
| Pixel | FAT32, short names | 927 ms | 727 ms | 1.27× |

Uncached exFAT cold listings landed at **136–176 s** across several runs (and 340–366 s
for long names); uncached FAT32 ran **0.9–2.3 s** on the same suite. Those are different
phones, so treat the gap as order-of-magnitude, not as a clean ratio — but with the
cache on, exFAT comes down to 1.7–2.7 s, which is FAT32 territory.

The mechanism is measured rather than inferred: libexfat issues **100,844 preads
averaging 24 bytes** — only 2.3 MiB of real data — for one 10,000-entry listing, where
FAT32 reads its directory table in bulk. That counter reproduced on three devices and
three Android versions.

This is why the cache transforms exFAT and does nothing for FAT32: it collapses request
*count*, not bytes. Amplification measured 1.00× — the cache was never over-reading.
On FAT32 the cache is worth **nothing** for cold listings (it is marginally negative on
the Huawei, within noise); its value there is in §3.3.

### 3.2 Sequential read

| Device | Filesystem | 32 KiB | 128 KiB | 512 KiB |
|---|---|---|---|---|
| OnePlus | exFAT (cache on) | 15.35 | 24.90 | 26.46 MB/s |
| Pixel | FAT32 **after** V6 | 6.51 | 14.94 | 11.31 MB/s |
| Pixel | FAT32 **before** V6 | 4.53 | 4.99 | 4.97 MB/s |
| Huawei | FAT32 | 1.54 | 3.88 | 3.86 MB/s |

**The V6 signature is the shape, not the ratio.** Before the fix, throughput ignored
the caller's buffer size — 4.53, 4.99, 4.97 — because `ClusterChain.read` issued one
SCSI command per cluster regardless. After, it scales. That shape change appeared in
*both* cache arms, which is what attributes it to the fix rather than to the cache or
to session drift.

### 3.3 Random 4 KiB and dense opens

| Device | Filesystem | Random, cache off | Random, cache on |
|---|---|---|---|
| Pixel | FAT32 | 24.2 ms | 7.2 ms (**3.36×**) |
| Huawei | FAT32 | 21.8 ms | 14.6 ms (**1.49×**) |
| OnePlus | exFAT | — | 2.6 ms |

**Random 4 KiB is where the cache earns its place on FAT32.** Its sequential
contribution is now neutral, because V6 removed the fragmentation the cache had partly
been compensating for.

Opening 50 files in a dense directory: 66–1,277 ms each depending on device and name
length. Long-name directories are consistently *cheaper* per open than 8.3-name ones
on the same drive, which is counterintuitive and unexplained.

## 4. SAF — what clients actually get

Measured *through* the DocumentsProvider. Everything in §2–§3 calls `UsbFile`
directly, which no real client does.

**Both arms use 128 KiB requests.** The kernel coalesces FUSE reads to 131,072 bytes
regardless of what the client asks for (established directly by the FD relay spike), so
an earlier version issuing 64 KiB in the direct arm produced ratios that mixed provider
cost with kernel readahead — including one below 1.0, which is what exposed it.

| Device | Filesystem | Single stream | Direct | Ratio | 2 streams |
|---|---|---|---|---|---|
| Pixel | exFAT | 7.14 | 30.26 | 4.24× | 24.06 MB/s |
| OnePlus | exFAT | 8.08 | 24.11 | 2.98× | 17.99 MB/s |
| Pixel | FAT32 | 5.94 | 14.23 | 2.40× | 13.10 MB/s |
| OnePlus | FAT32 | 7.08 | 9.34 | 1.32× | 7.95 MB/s |
| Huawei | FAT32 | 3.42 | 3.81 | 1.11× | 3.95 MB/s |

**Read the absolute figure, not the ratio.** Single-stream throughput spans
3.42–8.08 MB/s while the direct path spans 3.81–30.26. (`IO_PERFORMANCE.md` §5.9 quotes
a narrower 5.94–8.08 span: it was written before the Huawei cell existed, and that row
extends the low end.) The model that fits all five:

> **SAF single ≈ min(a ~6–8 MB/s provider ceiling, the direct-path speed).**

The ceiling only binds when the stack underneath is faster than it. On the Huawei the
transport *is* the constraint, so SAF tracks it and the ratio collapses to 1.11× — the
same provider behaviour over a much smaller denominator. A high ratio means a fast
underlying path, not a worse provider.

**Concurrency lifts past the ceiling**, reaching 75–92% of the direct rate where there
is headroom. That is why replacing the single `ProxyFileDescriptorThread` with
per-drive threads is **not** justified: one handler thread already carries two streams
to within 75–92% of direct. Cross-drive concurrency (`saf par cross`) remains
unmeasured — it needs two volumes on one device.

## 5. Writes, and why no figure is quoted as an improvement

16 MiB of position-dependent content, verified three times (as written, after dropping
every cached line, after a full remount):

| Device | Filesystem | Throughput |
|---|---|---|
| Pixel | FAT32 | 2.66 MB/s |
| OnePlus | FAT32 | 1.91 MB/s |
| Samsung | exFAT | ~1.9 MB/s |
| Huawei | FAT32 | 1.37 MB/s |
| Huawei | exFAT | 0.43 MB/s |

**Write throughput is not usable for comparison.** Six repetitions per arm showed a
single arm spanning 3× on one device (0.78–2.33 MB/s with the cache off). SLC cache
exhaustion, wear levelling and controller garbage collection dominate. An earlier claim
of "1.18× on the Pixel, 1.6–2× on exFAT" was retracted for exactly this reason: it was
a ratio between single noisy samples.

## 6. What to validate

Correctness first — it is the part that means something.

| Check | Expectation |
|---|---|
| `correct` — 11 cases | ALL PASSED, and case B's SHA-256 must be **`ac3e360a187bfe9f…` on every device and filesystem** |
| `unaligned` — cases A, B | PASS; these are the ones that catch V1 and V2 |
| `write` | ALL PASSED, three passes; `PARTIAL — 2 of 3` means the remount did not happen |
| `fixtures` | **Currently reports "nothing to check" everywhere.** The only externally-grounded check, and it has never compared a host-computed hash — the drives carry `-` placeholders |
| Block layer | Matched across arms, or the comparison is void |
| Power state | `interactive=true` at *both* ends of the report |
| Build stamp | `commit <sha>` with no `-dirty` |

Case B's hash agreeing across devices is what makes the results evidence rather than
each device merely agreeing with itself.

### The comparison rules, each learned by getting it wrong

1. **Same session.** Two retractions in `IO_PERFORMANCE.md` came from comparing runs
   taken at different times.
2. **Same device.** A third came from generalising a ratio measured on one phone. Same
   code and same drive does not make two devices equivalent.
3. **Check the control.** If the block layer moved, stop.
4. **Verify the build.** One A/B was invalidated by an un-rebuilt APK — the two files
   were byte-identical. Compare APK hashes.
5. **Distrust a suite that passes when it should not be able to.** Every false pass
   came from the test's *shape*: aligned-only writes hid two corruption bugs, 4–8 KiB
   fixtures hid a 2 GiB overflow, single-threaded tests hid a lock shipped wrong, and a
   no-op remount produced eleven meaningless passes. When a fix lands, confirm the new
   test **fails on the old code**.

## 7. Provenance

Every figure here was taken from a benchmark report emitted by the harness, checked
back against the raw run output before being written down. The raw outputs live in a
session scratchpad and are **not** durable — this document and
`docs/IO_PERFORMANCE.md` are the record. When a run produces something worth keeping,
copy the figure into one of them; do not rely on the logs still being there.

Two figures were dropped while compiling this — a Huawei exFAT cold-listing time and a
Huawei exFAT block-layer rate — because neither could be traced to any surviving run
output. If a number cannot be found in a report, treat it as unverified and re-measure
rather than reasoning from it.

## 8. Known gaps

- `fixtures` has never validated a hash on any device.
- Serpent is untested; every run used AES/SHA-512.
- Multi-drive is untested: the `drive` selector, `driveTag` collision detection and
  `saf par cross` all need two volumes on one device. `docs/LUKS_SUPPORT.md` §5
  describes a drive that would provide them.
- **The Huawei has no exFAT block-layer figure**, so its exFAT results have no control
  to check against. Only FAT32 was measured there (7.90/8.11 MB/s).
- The uncached exFAT arm on the Huawei is incomplete — it reported `no files in large/`
  after 325 s of cold listing, and `ExFatFile.listFiles` could not then distinguish a
  failure from an empty directory. That specific ambiguity is fixed; the figures were
  never re-gathered.
- The on-device (no-adb) flow cannot verify correctness at all: Android destroys the
  backgrounded MainActivity, nulling the mount handlers, so no remount can occur.
