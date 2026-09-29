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
