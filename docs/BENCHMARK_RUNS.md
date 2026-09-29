# Benchmark run log

Append-only. One row per run, newest first. Required by `CLAUDE.md` — a run that
exists only in logcat and on the drive did not happen as far as the repo is
concerned.

This file is the raw record. `BENCHMARK_RESULTS.md` holds the curated reference
figures and the rules for comparing them; every figure there should be traceable
to a row here.

## How to read a row

- **Sections** is what `--es tests` actually named. Omitting it runs the read-only
  sections only and skips the five write sections **silently**, so a row that does
  not say what ran proves nothing about the write path.
- **`PARTIAL` / `NOT VERIFIED`** are recorded as themselves. They mean the suite
  declined to claim a verdict it could not support — most often because no remount
  was performed. They are never rounded up to a pass.
- **`e2fsck`** is the host-side check after the run, on the mapped/decrypted device.
  Hash-clean is not fsck-clean. `n/a` means the filesystem is not ext4.
- **Every completed run is here.** A run that did not complete — dead drive, dead USB
  handle, crash, interruption — produced no result and is not logged. A run that
  completed is logged whatever it says, including `FAILED` sections, `PARTIAL`
  verdicts and `*** CONTAMINATED ***` flags. If a row looks bad, that is the point.
- Figures from different devices, different sessions, or with a differing
  block-layer control are **not** comparable. See `BENCHMARK_RESULTS.md`.
- **Commit** is the tree the APK was actually built from. The report stamps itself
  from `git describe` at build time, so a `-dirty` label names a commit the APK is
  *not* — it carries uncommitted work the label does not mention. Commit before
  building. Where a run below was made from a dirty tree, the row says what the APK
  really contained and gives its sha256, because the label alone would send a reader
  to the wrong diff.

---

## 2026-09-28

### Huawei P20 Lite (ANE-LX1) · Android 9 (SDK 28) · build 0.3.13 (46) commit dc29660 — drive D, VeraCrypt + ext4

The fourth attempt at this cell, and the first to pass. Sections `write`,
`unaligned`, `correct` only — the read sections were measured on an earlier
attempt and are unchanged.

| Field | Value |
|---|---|
| Drive | Realtek card reader, 11927 MiB — VeraCrypt (AES/SHA-512, PIM 1) + ext4, `VCEXT4` |
| write verify | **ALL PASSED** — cached, cache-dropped, **and remounted** |
| unaligned | **A + B PASS** |
| correctness | **ALL PASSED** (A–G) |

The A/B for `dc29660`. The three preceding attempts, on `6da214b`, `d216ed8` and
`99c4ab3`, all reported `verify (remounted): NOT PERFORMED` and lost the
remount-dependent sections. Same drive, same device, same command.

The cause was never a slow unmount. Instrumenting it showed the unmount
completing in 7 ms and the app re-mounting the still-attached drive 24 ms later
on its own; the benchmark's 300 ms poll never saw the gap and concluded the
volume had not unmounted. Two earlier fixes aimed at the consequences of that.

---

### Samsung Galaxy M30 — VeraCrypt + exFAT, verified against its baseline

The run itself is recorded below. This is its post-run verification, and it is
the clearest demonstration so far of what the baseline tool adds:

| | |
|---|---|
| `fsck.exfat` | **CLEAN** — 19 directories, 20,097 files |
| files changed | **none** beyond the runner's own report and `INDEX.txt` |
| boot region | **identical** |
| FAT | **7 bytes of 60,817,408 differ — about one entry** |

One allocation-table entry moved, for the one file the run wrote.

Set against the FAT32 drive measured by the same tool earlier the same day —
526,547 bytes and roughly 131,636 entries changed, entry 0 zeroed, and an
unrelated `FILL/` file's chain destroyed — the two are four orders of magnitude
apart. Both drives passed every on-device check. Only this comparison
distinguishes them.

---

### OnePlus 7 (GM1901) · Android 16 (SDK 36) · build 0.3.13 (46) commit d216ed8 — LUKS2 + ext4

APK sha256 `02312fe0…`, clean tree, awake and unthrottled, battery 92%.

| Field | Value |
|---|---|
| Drive | PNY 59136 MiB — LUKS2 (Argon2id) + ext4, `LUKS2EXT4` |
| Sections | free, block, dir, path, seq, random, opens, write, unaligned, correct, fixtures |
| block read | 7.41 / 26.02 / 28.19 / 26.51 MB/s |
| seq read | 26.04 / 27.71 / 28.40 MB/s |
| random read | 3.5 ms each, 284.7 IOPS |
| write | 16 MiB → 0.78 MB/s |
| write verify | **ALL PASSED** (cached, cache-dropped, remounted) |
| unaligned | **A + B PASS** |
| correctness | **ALL PASSED** (A–G) |

Replaces this device's earlier LUKS2 result, which was measured on a build
predating the ext4 metadata fixes and could not have gone into a tag report. The
fastest reads in the fleet — 28.4 MB/s sequential against the Pixel's 16.5 on the
same drive, which is a device difference and not a code one.

---

### Pixel 10 Pro XL · Android 17 (SDK 37) · build 0.3.13 (46) commit d216ed8 — LUKS1 + ext4

| Field | Value |
|---|---|
| Drive | PNY 59150 MiB — LUKS1 (PBKDF2) + ext4, `LUKS1EXT4` |
| block read | 1.26 / 13.39 / 19.32 / 22.26 MB/s |
| seq read | 16.54 / 16.41 / 15.85 MB/s |
| random read | 5.0 ms each, 199.9 IOPS |
| write | 16 MiB → 0.62 MB/s |
| write verify | **ALL PASSED** (cached, cache-dropped, remounted) |
| unaligned | **A + B PASS** |
| correctness | **ALL PASSED** (A–G) |
| fixtures | **ALL 5 MATCHED the host-computed hashes** |
| fsck | **CLEAN** — `e2fsck -fn`: 20039/3792896 files, 927643/15142400 blocks |
| baseline compare | **CLEAN** — see below |

**First pair of runs verified against a stored baseline.** Both this and the
Pixel's LUKS2 run were baselined before the run and compared after, with
`scripts/volume_baseline.sh`:

| | LUKS2 + ext4 | LUKS1 + ext4 |
|---|---|---|
| files unchanged | 20,007 of 20,009 | 20,009 of 20,011 |
| added | its own report | its own report |
| modified | `BENCH/reports/INDEX.txt` | `BENCH/reports/INDEX.txt` |
| free blocks | −1 | −1 |
| free inodes | −1 | −1 |

Every change is the benchmark's own output, and the metadata delta is exactly one
file's worth. This is a materially stronger statement than `fixtures` can make on
its own: that section covers 5 manifest entries, while this covers all ~20,000
files. The FAT32 corruption lived precisely in that gap — in a `FILL/` file no
manifest entry named.

---

### Samsung Galaxy M30 (SM-M305F) · Android 10 (SDK 29) · build 0.3.13 (46) commit d216ed8 — VeraCrypt + exFAT

APK sha256 `02312fe0…`, clean tree, awake and unthrottled at both ends, charging.

| Field | Value |
|---|---|
| Drive | PNY 59151 MiB — VeraCrypt (AES/SHA-512, PIM 1) + exFAT |
| Sections | free, block, dir, path, seq, random, opens, write, unaligned, correct, fixtures |
| block read | 3.37 / 12.04 / 12.97 / 11.29 MB/s |
| seq read | 9.73 / 9.91 / 10.01 MB/s |
| random read | 6.2 ms each, 160.8 IOPS |
| write | 16 MiB → 0.35 MB/s |
| write verify | **ALL PASSED** (cached, cache-dropped, remounted) |
| unaligned | **A + B PASS** |
| correctness | **ALL PASSED** (A–G) |
| fixtures | **ALL 16 MATCHED the host-computed hashes** |
| fsck | pending |

---

### Huawei P20 Lite (ANE-LX1) · Android 9 (SDK 28) · build 0.3.13 (46) commit d216ed8 — drive D, VeraCrypt + ext4

APK sha256 `02312fe0…`, clean tree, awake and unthrottled. **Partial — the
remount-dependent sections were lost.**

| Field | Value |
|---|---|
| Drive | Realtek card reader, 11927 MiB — VeraCrypt (AES/SHA-512, PIM 1) + ext4, `VCEXT4` |
| Sections | free, block, dir, path, seq, random, opens, write, unaligned, correct, fixtures |
| seq read | 6.61 / 6.82 / 7.10 MB/s |
| write verify | **PARTIAL — 2 of 3**; the remount pass was not performed |
| unaligned | **FAILED** — `block device is closed (volume was unmounted)` |
| correctness | **NOT RUN** — "no live mount" |
| fixtures | **ALL 5 MATCHED the host-computed hashes** |
| fsck | pending |

The drive was healthy throughout: `fixtures` hashed five entries against host
values in the same run, after the two failures. What was lost was the *ability to
judge* the write path, not the write path itself.

Cause, and why this run still shows it: `remountAndProve` waited a flat 30 s for
the unmount, gave up, and returned — and the unmount then landed anyway, moments
later. The volume vanished after the function had concluded it had not, and
nothing re-mounted it, because the mount request is only issued past that return.
Fixed in `d7c8fcd`, which raises the budget to the 90 s already allowed for a
mount and re-issues the mount request before bailing. This run predates that
build.

Two earlier attempts at this cell failed the same way, on `6da214b` and
`d216ed8`. Both are recorded as this row rather than separately: same drive, same
device, same failure, differing only in which fix had landed.

---

### Pixel 10 Pro XL · Android 17 (SDK 37) · build 0.3.13 (46) commit d216ed8 — LUKS2 + ext4

APK sha256 `02312fe0…`, clean tree, awake and unthrottled at both ends.

| Field | Value |
|---|---|
| Drive | PNY 59136 MiB — LUKS2 (Argon2id) + ext4, `LUKS2EXT4` |
| Report | `otgbench-Pixel_10_Pro_XL-20260928-211430.txt` |
| Sections | free, block, dir, path, seq, random, opens, write, unaligned, correct, fixtures |
| block read | 2.36 / 11.38 / 19.28 / 23.32 MB/s |
| seq read | 16.47 / 16.64 / 16.48 MB/s |
| random read | 4.2 ms each, 240.1 IOPS |
| write | 16 MiB → 0.64 MB/s |
| write verify | **ALL PASSED** (cached, cache-dropped, remounted) |
| unaligned | **A + B PASS** |
| correctness | **ALL PASSED** (A–G) |
| fixtures | **ALL 5 MATCHED the host-computed hashes** |
| fsck | pending |

Completes the Pixel's row: all six cases now measured on that device. It is also
the first run on `d216ed8`, and it passed the sections that fail on the Huawei —
which is consistent with the diagnosis there being a timeout tuned on fast
hardware rather than anything wrong with the code path itself.

---

### Huawei P20 Lite (ANE-LX1) · Android 9 (SDK 28) · build 0.3.13 (46) commit 98fae8f — LUKS2 + ext4

APK sha256 `ee9188b0…`, clean tree, awake and unthrottled at both ends.

| Field | Value |
|---|---|
| Drive | PNY 59136 MiB — LUKS2 (Argon2id) + ext4, `LUKS2EXT4` |
| Sections | free, block, dir, path, seq, random, opens, write, unaligned, correct, fixtures |
| block read | 3.44 / 6.25 / 7.23 / 8.18 MB/s |
| seq read | 6.81 / 6.70 / 6.79 MB/s |
| random read | 8.4 ms each, 119.2 IOPS |
| dir listing | dense_short cold 652.5 / warm 212.4 ms; dense_lfn cold 708.7 / warm 242.5 |
| opens | short 216.8 ms each, lfn 231.3 ms each |
| write | 16 MiB → 0.53 MB/s |
| write verify | **ALL PASSED** (cached, cache-dropped, remounted) |
| unaligned | **A + B PASS** |
| correctness | **ALL PASSED** (A–G) |
| fixtures | **ALL 5 MATCHED the host-computed hashes** |
| fsck | pending |

Argon2id on the slowest device in the fleet, which is the case this cell exists
for: LUKS2's key derivation is memory-hard, and `prepare_drive_c.sh` pins
`--pbkdf-memory 65536` precisely because cryptsetup's desktop default of 1–4 GB
gets the process killed by Android's low-memory killer. It unlocked and ran
through without incident here.

---

### Huawei P20 Lite (ANE-LX1) · Android 9 (SDK 28) · build 0.3.13 (46) commit 98fae8f — exFAT

APK sha256 `ee9188b0…`, clean tree, awake and unthrottled at both ends, over WiFi.

| Field | Value |
|---|---|
| Drive | PNY 59151 MiB — VeraCrypt (AES/SHA-512, PIM 1) + exFAT |
| Sections | free, block, dir, path, seq, random, opens, write, unaligned, correct, fixtures |
| block read | 2.32 / 7.72 / 9.04 / 8.58 MB/s |
| seq read | 6.54 / 6.77 / 6.69 MB/s |
| random read | 10.3 ms each, 97.0 IOPS |
| dir listing | dense_short cold 5460.3 / warm 433.9 ms; dense_lfn cold 8689.6 / warm 81.1 |
| opens | short 64.3 ms each, lfn 82.9 ms each |
| write | 16 MiB → 0.41 MB/s |
| write verify | **ALL PASSED** (cached, cache-dropped, remounted) |
| unaligned | **A + B PASS** |
| correctness | **ALL PASSED** (A–G) |
| fixtures | **ALL 16 MATCHED the host-computed hashes** |
| fsck | **CLEAN** — `fsck.exfat`: 19 directories, 20096 files |

**The cell this fleet most needed.** This is the device whose 0.43 MB/s writes
exceeded the ten-second `FinalizerWatchdogDaemon` budget and killed the process,
which is why `ExFatFileSystem.pendingReleases` exists. It ran the full exFAT
suite — including hashing `seq_2g.bin` end to end over 329 s and two
10,000-entry listings — with no kill and no failure. The mechanism is exercised
on the hardware that broke it, not merely on faster phones where it never fired.

Its 0.41 MB/s write is the slowest figure in the log and is a device
characteristic, not a regression.

---

### Samsung Galaxy M30 (SM-M305F) · Android 10 (SDK 29) · build 0.3.13 (46) commit 98fae8f — LUKS1

APK sha256 `ee9188b0…`, clean tree, awake and unthrottled at both ends.

| Field | Value |
|---|---|
| Drive | PNY 59150 MiB — LUKS1 (PBKDF2) + ext4, `LUKS1EXT4` |
| Sections | free, block, dir, path, seq, random, opens, write, unaligned, correct, fixtures |
| block read | 1.64 / 5.36 / 10.87 / 10.47 MB/s |
| seq read | 10.64 / 10.30 / 10.59 MB/s |
| random read | 6.2 ms each, 161.6 IOPS |
| write | 16 MiB → 0.45 MB/s |
| write verify | **ALL PASSED** (cached, cache-dropped, remounted) |
| unaligned | **A + B PASS** |
| correctness | **ALL PASSED** (A–G) |
| fixtures | **ALL 5 MATCHED the host-computed hashes** |
| fsck | **CLEAN** — `e2fsck -fn`: 20038/3792896 files, 927642/15142400 blocks |

Unlike this device's LUKS2 run earlier, this one was awake from the start, so
its throughput is usable. Against that LUKS2 run — same device, same session,
block-layer control 10.47 against 10.88 MB/s, 4% apart and therefore comparable —
LUKS1 reads slightly faster (10.6 against 10.0 MB/s sequential) and writes
slightly slower (0.45 against 0.56 MB/s). Both differences are small enough to be
run-to-run noise on a phone this slow; neither should be quoted as a LUKS1/LUKS2
finding without repeats.

---

### Samsung Galaxy M30 (SM-M305F) · Android 10 (SDK 29) · build 0.3.13 (46) commit 98fae8f

APK sha256 `ee9188b0…`, clean tree, on battery at 33%.

| Field | Value |
|---|---|
| Drive | PNY 59136 MiB — LUKS2 (Argon2id) + ext4, `LUKS2EXT4` |
| Report | `otgbench-SM-M305F-20260928-192923.txt` |
| Sections | free, block, dir, path, seq, random, opens, write, unaligned, correct, fixtures |
| block read | 1.89 / 5.33 / 11.93 / 10.88 MB/s |
| seq read | 9.99 / 9.82 / 10.01 MB/s |
| random read | 6.9 ms each, 144.0 IOPS |
| dir listing | dense_short cold 470.0 / warm 152.3 ms; dense_lfn cold 494.0 / warm 161.3 |
| opens | short 143.1 ms each, lfn 152.8 ms each |
| write | 16 MiB → 0.56 MB/s |
| write verify | **ALL PASSED** (cached, cache-dropped, remounted) |
| unaligned | **A + B PASS** |
| correctness | **ALL PASSED** (A–G) |
| fixtures | **ALL 5 MATCHED the host-computed hashes** |
| fsck | **CLEAN** — `e2fsck -fn`: 20035/3784704 files, 927120/15138816 blocks |

**Throughput from this run is not usable.** It began with the screen off:

```
power : interactive=false deviceIdle=false powerSave=false
*** DEVICE IS IDLE OR THROTTLED — throughput here is not comparable
```

The screen was woken about a minute in and the end-of-run block reads
`interactive=true`, so it recovered — but it did not start clean, and a run that
spent its first minute throttled cannot have its figures compared with anything.
The verdicts are unaffected: every one of them is a hash or structural
comparison rather than a measurement.

---

### Huawei P20 Lite (ANE-LX1) · Android 9 (SDK 28) · build 0.3.13 (46) commit 98fae8f

APK sha256 `ee9188b0…`, clean tree, awake and unthrottled at both ends, on
battery. Driven entirely over WiFi — see the note below.

| Field | Value |
|---|---|
| Drive | PNY USB 3.2.1 FD, 59151 MiB — VeraCrypt (AES/SHA-512, PIM 1) + FAT32, `VCFAT` |
| Report | `otgbench-ANE-LX1-20260928-192814.txt` |
| Sections | free, block, dir, path, seq, random, opens, write, unaligned, correct, fixtures |
| block read | 1.63 / 6.13 / 7.87 / 8.06 MB/s (4 / 64 / 512 / 4096 KiB span) |
| seq read | 1.50 / 4.00 / 3.90 MB/s (32 / 128 / 512 KiB buf) |
| random read | 17.5 ms each, 57.2 IOPS |
| dir listing | dense_short cold 4834.9 / warm 1733.2 ms; dense_lfn cold 2550.9 / warm 588.5 |
| opens | short 1265.9 ms each, lfn 466.0 ms each |
| write | 16 MiB → 1.83 MB/s |
| write verify | **ALL PASSED** (cached, cache-dropped, remounted) |
| unaligned | **A + B PASS** |
| correctness | **ALL PASSED** (A–G) |
| fixtures | **ALL 16 MATCHED the host-computed hashes** |
| fsck | not yet run — drive still on the phone |

First completed case on this device. It is the slowest of the four by a wide
margin — 8.06 MB/s block read against the Pixel's 35.33, and hashing `seq_2g.bin`
took 432 s at 4.74 MB/s against the Pixel's 65 s. Those figures are a device
characteristic, not a regression: `IO_PERFORMANCE.md` already records this phone
at roughly a quarter to a fifth of the others.

**`adb tcpip` over WiFi survived unplugging USB on this Android 9 device.**
`RUNNING_BENCHMARKS.md` states that legacy `adb tcpip` "does not survive losing
the USB transport", and steers Android ≤10 devices to the on-device OTG Bench
flow, which cannot verify on-disk correctness because backgrounding MainActivity
nulls the mount handlers. This entire run — install, mount, benchmark, remount
for the correctness section — was driven over `192.168.1.17:5555` with the cable
out, so that guidance is at least not universally true. Worth re-testing before
the doc is rewritten, since one success does not disprove a flaky behaviour.

---

### Pixel 10 Pro XL · Android 17 (SDK 37) · build 0.3.13 (46) commit e78cf0a — FAT32

APK sha256 `2db42f29…`, clean tree, awake and unthrottled, on a powered hub.
**The reference VeraCrypt+FAT32 run** — first complete one with every section
passing.

| Field | Value |
|---|---|
| Drive | PNY USB 3.2.1 FD, 59151 MiB — VeraCrypt (AES/SHA-512, PIM 1) + FAT32, `VCFAT` |
| Report | `otgbench-Pixel_10_Pro_XL-20260928-190012.txt` |
| Sections | free, block, dir, path, seq, random, opens, write, unaligned, correct, fixtures |
| block read | 2.02 / 16.92 / 29.68 / 32.59 MB/s (4 / 64 / 512 / 4096 KiB span) |
| seq read | 9.70 / 22.59 / 22.56 MB/s (32 / 128 / 512 KiB buf) |
| random read | 7.1 ms each, 141.4 IOPS |
| dir listing | dense_short cold 743.0 / warm 249.1 ms; dense_lfn cold 915.0 / warm 112.0 |
| opens | short 306.2 ms each, lfn 121.2 ms each |
| write | 16 MiB → 1.91 MB/s |
| write verify | **ALL PASSED** (cached, cache-dropped, remounted) |
| unaligned | **A + B PASS** |
| correctness | **ALL PASSED** (A–G) |
| fixtures | **ALL 16 MATCHED the host-computed hashes** |
| fsck | not yet run — drive still on the phone |

First FAT32 run with working fixture hashing: three large files read end to end
(`seq_2g.bin` 78.0 s at 26.25 MB/s), two 10,000-entry listings, eleven nested
files. Both prerequisites landed today — the manifest had no hashes until
`regen_manifest.sh` was generalised, and the section that checks them was broken
until `e78cf0a`.

**Not comparable to the `d455547` FAT32 run.** Its write figure was 0.59 MB/s
against 1.91 here, which looks like a large improvement and is not a measurement
of one: the block-layer controls differ by 7% (32.59 against 35.09 MB/s at
4096 KiB), which is past the threshold this project treats as comparable, and
the earlier run was made on a phone at 21% battery powering the drive from its
own cell. Nothing in the code between those builds touches the FAT32 write path.

### Pixel 10 Pro XL · Android 17 (SDK 37) · build 0.3.13 (46) commit e78cf0a

APK sha256 `2db42f29…`, clean tree, awake and unthrottled throughout, on a
powered hub. **The reference VeraCrypt+exFAT run** — the first complete one with
every section passing.

| Field | Value |
|---|---|
| Drive | PNY USB 3.2.1 FD, 59151 MiB — VeraCrypt (AES/SHA-512, PIM 1) + exFAT |
| Report | `otgbench-Pixel_10_Pro_XL-20260928-185058.txt` |
| Sections | free, block, dir, path, seq, random, opens, write, unaligned, correct, fixtures |
| freeSpace | first 101.8 ms, then 68.6 ms avg |
| block read | 3.09 / 26.90 / 35.37 / 35.33 MB/s (4 / 64 / 512 / 4096 KiB span) |
| seq read | 30.07 / 31.24 / 31.27 MB/s (32 / 128 / 512 KiB buf) |
| random read | 2.6 ms each, 385.8 IOPS |
| dir listing | dense_short cold 1203.6 / warm 13.7 ms; dense_lfn cold 2057.5 / warm 20.0 |
| opens | short 12.0 ms each, lfn 16.1 ms each |
| write | 16 MiB → 0.63 MB/s |
| write verify | **ALL PASSED** (cached, cache-dropped, remounted) |
| unaligned | **A + B PASS** |
| correctness | **ALL PASSED** (A–G) |
| fixtures | **ALL 16 MATCHED the host-computed hashes** |
| fsck | `fsck.exfat` CLEAN earlier the same day (19 dirs, 20091 files) |

The fixture hashes are the part worth noting: three large files read end to end
and hashed (`seq_1g.bin` 33.1 s, `seq_2g.bin` 64.9 s, `seq_256m.bin` 8.0 s at
~31 MB/s), two 10,000-entry directory listings, and eleven files down the nested
tree — all matching hashes computed on the host. This drive had **no** fixture
hash coverage at all until `regen_manifest.sh` was generalised, and the section
that checks them was broken until `e78cf0a`.

Block-layer control at 4096 KiB is 35.33 MB/s here against 36.25 on `110e1cd`,
a 2.5% difference, so the two runs are comparable and nothing regressed.

---

### Pixel 10 Pro XL · Android 17 (SDK 37) · build 0.3.13 (46) commit e78cf0a — fixtures only

APK sha256 `2db42f29…`, clean tree. The first verification run for the `fixtures`
fix, superseded by the full run above but kept because it is the A/B pair.

| Field | Value |
|---|---|
| Drive | PNY USB 3.2.1 FD, 59151 MiB — VeraCrypt (AES/SHA-512, PIM 1) + exFAT |
| Report | `otgbench-Pixel_10_Pro_XL-20260928-184209.txt` |
| Sections | fixtures |
| fixtures | **ALL 16 MATCHED the host-computed hashes** |
| fsck | `fsck.exfat` CLEAN earlier the same day (19 dirs, 20091 files) |

This is the same drive and the same section that reported
`FAILED java.io.IOException: File is closed` on `110e1cd`. The fix is closing the
handle after the last read of it rather than before. Confirmed on hardware: the
section fails on the old build and passes on the new one.

**Throughput from this run is not usable.** The runner flagged it:

```
power : interactive=true deviceIdle=false powerSave=true
*** DEVICE IS IDLE OR THROTTLED — throughput here is not comparable
*** to an awake run; wake the screen and disable Doze before measuring
```

The phone had dropped to 20% battery and Android turned on battery saver. That
does not affect this run's verdict, which is a hash comparison rather than a
measurement, but none of its MB/s figures are quoted anywhere and none should be.

Before this run the drive had dropped off the USB bus entirely — `dumpsys usb`
listed no device, and a benchmark attempt sat through its full 90 s mount timeout
reporting `NO DRIVES MOUNTED`. Replugging restored it. The phone was powering a
USB 3.2 stick from a 21% battery at the time, which is the most likely
explanation. That attempt produced no result and is not logged as a run.

### Pixel 10 Pro XL · Android 17 (SDK 37) · build 0.3.13 (46) commit 110e1cd

APK sha256 `ab7f758f…`. Clean tree — the first run whose label names a tree it was
actually built from.

| Field | Value |
|---|---|
| Drive | PNY USB 3.2.1 FD, 59151 MiB — VeraCrypt (AES/SHA-512, PIM 1) + exFAT |
| Report | `otgbench-Pixel_10_Pro_XL-20260928-181659.txt` |
| Sections | free, block, dir, path, seq, random, opens, write, unaligned, correct, fixtures |
| block read | 2.25 / 24.88 / 34.74 / 36.25 MB/s (4 / 64 / 512 / 4096 KiB span) |
| seq read | 29.96 / 30.79 / 31.20 MB/s (32 / 128 / 512 KiB buf) |
| random read | 2.5 ms each, 399.7 IOPS |
| dir listing | dense_short cold 1256.4 / warm 15.9 ms; dense_lfn cold 1986.6 / warm 17.5 |
| opens | short 12.6 ms each, lfn 16.5 ms each |
| write | 16 MiB → 0.64 MB/s |
| write verify | **ALL PASSED** (cached, cache-dropped, remounted) |
| unaligned | **A + B PASS** |
| correctness | **ALL PASSED** (A–G) |
| fixtures | **FAILED — `java.io.IOException: File is closed`** |
| fsck | `fsck.exfat` CLEAN before this run (19 dirs, 20091 files) |

The manifest had been regenerated with `regen_manifest.sh`, so this is the first
time the fixture hashing ran on this drive at all. It aborts roughly 29 s into the
first large file, and reproduces on its own with `--es tests fixtures` — so it is
not an interaction with the remount that the correctness section performs.

**This is not a regression from the commits under test.** The same section passes
on the ext4 drives (`ALL 5 MATCHED`), which go through libaums; the failure is in
`ExFatFile`, which none of today's commits touch, and it could not have been seen
here before because the manifest carried no hashes to check against.

Suspected cause, not yet proven: `ExFatFile.finalize()` sets `isClosed = true` and
queues its native node for release. `search()` builds a fresh `ExFatFile` for every
directory entry it walks past, so a single lookup litters the heap with wrappers
over live nodes — and ART may finalize an object while a method on it is still
running, which is what `Reference.reachabilityFence` exists for. A long read loop
under GC pressure is exactly the shape that provokes it. If that is right the same
failure is reachable from the DocumentsProvider, where copying a large file off an
exFAT drive runs the same loop, so it is worth settling rather than working around.

This run also confirms the `onDestroy` handler-ownership fix on hardware: the
sequence that produced "no mount handler installed" twice — back out of
MainActivity, relaunch, broadcast — mounted cleanly here.

### Pixel 10 Pro XL · Android 17 (SDK 37) · build 0.3.13 (46) commit d455547

APK sha256 `d1a9adc7…`. Label `d455547-dirty`; the dirt is six untracked scratch
scripts, so the tracked tree matches `d455547` exactly. (`gitDirty()` runs
`git status --porcelain`, which counts untracked files — worth knowing before
reading any `-dirty` label as uncommitted *code*.)

| Field | Value |
|---|---|
| Drive | PNY USB 3.2.1 FD, 59151 MiB — VeraCrypt (AES/SHA-512, PIM 1) + FAT32, `VCFAT` |
| Report | `otgbench-Pixel_10_Pro_XL-20260928-180144.txt` |
| Sections | free, block, dir, path, seq, random, opens, write, unaligned, correct, fixtures |
| block read | — / 18.39 / 30.58 / 35.09 MB/s (4 / 64 / 512 / 4096 KiB span) |
| seq read | 9.78 / 22.11 / 22.86 MB/s (32 / 128 / 512 KiB buf) |
| random read | 6.2 ms each, 160.0 IOPS |
| dir listing | dense_short cold 786.9 / warm 310.4 ms; dense_lfn cold 821.8 / warm 110.3 |
| opens | short 299.5 ms each, lfn 115.9 ms each |
| write | 16 MiB → 0.59 MB/s |
| write verify | **ALL PASSED** (cached, cache-dropped, remounted) |
| unaligned | **A + B PASS** |
| correctness | **ALL PASSED** (A–G) |
| fixtures | not checked — manifest has no hashes (old prepare script) |
| fsck | not yet run — drive still on the phone |

Two earlier attempts at this run produced no result and are not logged: both died
at `*** no mount handler installed — is MainActivity running? ***` with
MainActivity visible and focused. That is the `onDestroy` ownership bug — a
finishing instance nulling its successor's handlers — fixed in the same commit as
this row.

### Pixel 10 Pro XL · Android 17 (SDK 37) · build 0.3.13 (46)

APK sha256 `f9d3269c…`. Report label `6d33f88-dirty` — **built from a dirty tree**:
the contents are `6d33f88` plus the UI fixes committed afterwards as `654ceda`.
This is the run that prompted the commit-before-build rule.

| Field | Value |
|---|---|
| Drive | PNY USB 3.2.1 FD, 59151 MiB — VeraCrypt (AES/SHA-512, PIM 1) + exFAT |
| Report | `otgbench-Pixel_10_Pro_XL-20260928-174901.txt` |
| Sections | free, block, dir, path, seq, random, opens, write, unaligned, correct, fixtures |
| block read | 3.22 / 29.32 / 34.14 / 34.22 MB/s (4 / 64 / 512 / 4096 KiB span) |
| seq read | 31.56 / 31.81 / 32.25 MB/s (32 / 128 / 512 KiB buf) |
| random read | 2.5 ms each, 406.2 IOPS |
| dir listing | dense_short cold 1227.9 ms / warm 13.7 ms; dense_lfn cold 1978.5 / warm 21.1 |
| opens | short 12.6 ms each, lfn 18.6 ms each |
| write | 16 MiB → 0.67 MB/s |
| write verify | **ALL PASSED** (cached, cache-dropped, remounted) |
| unaligned | **A + B PASS** |
| correctness | **ALL PASSED** (A–G) |
| fixtures | not checked — this drive's manifest predates the hash check |
| fsck | **CLEAN** — `fsck.exfat` (exfatprogs 1.3.2): 19 directories, 20091 files |

Regression check for `6d33f88` / `654ceda`. The volume still classifies as
VERACRYPT, which is the case the classification change had to leave alone: a
VeraCrypt header is random, so `FilesystemDetector` returns Unknown and the
residual guess stands.

### Pixel 10 Pro XL · Android 17 (SDK 37) · build 0.3.13 (46) commit cdd87b5

Drive D (see `TEST_DATA.md` §11a), first hardware run of VeraCrypt+ext4 and of
the plain-ext4 control.

**p2 — plain ext4, `PLAINEXT4`, 8947 MiB** — report `…-20260928-173746.txt`

| Field | Value |
|---|---|
| Commit | APK sha256 `d20bec6b…`, label `cdd87b5-dirty` — contents are `cdd87b5` plus the classification work later committed as `6d33f88`, without its `restoreDeferredCandidates` fix |
| Sections | free, block, dir, path, seq, random, opens, write, unaligned, correct, fixtures |
| block read | 1.57 / 44.08 / 48.09 / 50.16 MB/s |
| seq read | 32.84 / 33.74 / 35.92 MB/s |
| random read | 105.8 ms each, 9.45 IOPS |
| dir listing | dense_short cold 175.7 / warm 65.5 ms; dense_lfn cold 285.0 / warm 72.9 |
| opens | short 70.4 ms each, lfn 71.8 ms each |
| write | 16 MiB → 0.99 MB/s |
| write verify | **PARTIAL — 2 of 3 passes**; the remount pass was not performed |
| unaligned | **NOT VERIFIED** — no remount |
| correctness | **NOT VERIFIED** — no remount |
| fixtures | **ALL 5 MATCHED** the host-computed hashes |
| e2fsck | **CLEAN** — 20044/573440 files, 672300/2290432 blocks |

The three `PARTIAL`/`NOT VERIFIED` verdicts are a runner limitation, not a result:
those sections judge themselves after a remount, and the only remount path the
runner has takes a password, so an unencrypted partition cannot be fully judged on
the device. The host `e2fsck` covers it instead. See `RUNNING_BENCHMARKS.md`.

**p1 — VeraCrypt (AES/SHA-512, PIM 1) + ext4, `VCEXT4`, 11927 MiB**

| Field | Value |
|---|---|
| Report | `…-20260928-172852.txt` (write sections), `…-20260928-172532.txt` (reads) |
| Commit | `cdd87b5-dirty`; the dirt was untracked scripts only, so the code matches `cdd87b5` |
| Sections | reads in one run, then write, unaligned, correct, fixtures in a second |
| block read | 1.24 / 17.59 / 26.98 / 33.01 MB/s |
| seq read | 25.28 / 26.72 / 25.51 MB/s |
| random read | 4.8 ms each, 209.3 IOPS |
| dir listing | dense_short cold 150.2 / warm 49.2 ms; dense_lfn cold 341.0 / warm 52.1 |
| opens | short 68.3 ms each, lfn 74.1 ms each |
| write | 16 MiB → 3.05 MB/s |
| write verify | **ALL PASSED** (cached, cache-dropped, remounted) |
| unaligned | **A + B PASS** |
| correctness | **ALL PASSED** (A–G) |
| fixtures | **ALL 5 MATCHED** the host-computed hashes |
| e2fsck | **CLEAN** — 20035/764032 files, 685283/3053504 blocks |

**Do not read p1 against p2 as an A/B.** Their block-layer controls differ (33.0 vs
50.2 MB/s at 4096 KiB), which by this project's own rule means the arms are not
comparable — p1 ran on a fresh mount, p2 on a long-lived one. The crypto layer
costs something here; these numbers do not measure how much.

The first p1 run (`…-172532.txt`) omitted `--es tests` and therefore ran the
read-only sections only. It is listed because it happened, not because it proves
anything about the write path.

### OnePlus 7 · Android 16 · LUKS2 + ext4

| Field | Value |
|---|---|
| write | ALL PASSED, 0.73 MB/s |
| correctness | ALL PASSED |
| fixtures | ALL 5 MATCHED |
| e2fsck | CLEAN |

### Pixel 10 Pro XL · Android 17 · LUKS1 + ext4

| Field | Value |
|---|---|
| write | ALL PASSED, 0.82 MB/s |
| correctness | ALL PASSED |
| fixtures | ALL 5 MATCHED |
| e2fsck | CLEAN |

### OnePlus 7 · Android 16 · LUKS1 + ext4

| Field | Value |
|---|---|
| write | ALL PASSED, 0.76 MB/s |
| correctness | ALL PASSED |
| fixtures | ALL 5 MATCHED |
| e2fsck | CLEAN |

The three LUKS rows above are carried over from the runs that validated the ext4
write fixes. They are recorded at the level of detail that was captured at the
time — the per-section read figures were not, which is the gap this file exists to
stop recurring.
