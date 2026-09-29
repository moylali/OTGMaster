# Release coverage matrix — 4 devices × 6 cases

The live picture of what has been exercised on hardware and what has not. A
tagging report (`docs/test-reports/<tag>.md`) is cut from this once the grid is
full enough to justify the tag; `CLAUDE.md` requires at least four devices.

Every ✅ here traces to a row in [`../BENCHMARK_RUNS.md`](../BENCHMARK_RUNS.md).
Nothing is marked done on the strength of a build, a unit test, or another
device's result.

**Target release:** v0.4.0 (versionCode 46). Tagging report will be `docs/test-reports/v0.4.0.md`.
**Last updated:** 2026-09-29. Rows recorded as 0.3.13 (46) are the same unreleased line, before the versionName changed.

## Devices

| # | Device | Model | Android | Address | Notes |
|---|---|---|---|---|---|
| D1 | Pixel 10 Pro XL | mustang | 17 (SDK 37) | `192.168.1.9:33057` | fastest reads; the reference device |
| D2 | OnePlus 7 | — | 16 | *offline* | Wireless Debugging port rotates; not reachable today |
| D3 | Samsung Galaxy M30 | SM-M305F | 10 (SDK 29) | `192.168.1.18:5555` | the API-29 boundary for `Documents/` |
| D4 | Huawei P20 Lite | ANE-LX1 | 9 (SDK 28) | `192.168.1.17:5555` | slowest by 4–5×; the finalizer-watchdog device |

## Cases

| # | Case | Media |
|---|---|---|
| C1 | VeraCrypt + FAT32 | PNY 64 GB, `VCFAT` |
| C2 | VeraCrypt + exFAT | PNY 64 GB, `exFAT` |
| C3 | VeraCrypt + ext4 | Drive D p1, `VCEXT4` |
| C4 | LUKS1 + ext4 | Drive B, `LUKS1EXT4` |
| C5 | LUKS2 + ext4 | Drive C, `LUKS2EXT4` |
| C6 | Unencrypted ext4 + NTFS refusal | Drive D p2 `PLAINEXT4`, p3 `NTFSPLAIN` |

## The grid

| | C1 VC+FAT32 | C2 VC+exFAT | C3 VC+ext4 | C4 LUKS1+ext4 | C5 LUKS2+ext4 | C6 plain+NTFS |
|---|---|---|---|---|---|---|
| **D1** Pixel 10 Pro XL | ✅ full | ✅ full | ✅ full | ✅ full | ✅ full | ✅ partial |
| **D2** OnePlus 7 | ❌ | ✅ partial | ❌ | ✅ partial | ✅ full | ❌ |
| **D3** Samsung M30 | ❌ | ✅ partial | ❌ | ✅ full | ✅ full | ❌ |
| **D4** Huawei P20 Lite | ✅ partial | ✅ full | ✅ full | ❌ | ✅ partial | ❌ |

✅ full — every section run and passed, including `fixtures`, on the current
build, with a host-side filesystem check afterwards.

**"Full" is not "proven uncorrupted."** The host checker validates structure, not
file contents — no filesystem here carries data checksums. `fixtures` covers
contents for the manifest's entries only: on the exFAT drive that is 14 files of
20,096, plus names and sizes for the 20,000 in the two dense directories. The
FAT32 damage sat precisely in that gap — in a `FILL/` file no manifest entry
covers — which is why it passed `fixtures: ALL 16 MATCHED` while wrecked. A full
cell means sampled-clean, not audited-clean.
✅ partial — passed, but missing at least one of: fixture hashes, a host `fsck`,
or the full read section set. Detail in the run log.
🔄 running · ❌ not attempted.

**Coverage: 18 of 24 cells, 10 full. The Pixel's row is complete — all six
cases measured on one device.**

**D4·C3 is resolved.** Four attempts; the fourth, on `dc29660`, passes every
section including the remount. The cause was the benchmark's unmount check racing
the app's own auto-mount, not a slow unmount — see `BENCHMARK_RUNS.md`.

**D4·C1 is reinstated.** Re-run on a freshly prepared drive after the V8
fix: every section passes and the baseline compare shows ~2 FAT entries changed,
all accounted for, against ~131,636 before the fix. Held at *partial* only because
`fsck.fat` still reports the two FATs differing — the unmirrored-FAT defect, now
safe to fix.

## What each ✅ actually covers

| Cell | Build | Evidence |
|---|---|---|
| D1·C1 | `e78cf0a` | write verify 3/3, unaligned A+B, correctness A–G, fixtures ALL 16 |
| D1·C2 | `e78cf0a` | write verify 3/3, unaligned A+B, correctness A–G, fixtures ALL 16, `fsck.exfat` clean |
| D1·C3 | `cdd87b5` | write verify 3/3, unaligned A+B, correctness A–G, fixtures ALL 5, `e2fsck` clean |
| D1·C4 | earlier | write ALL PASSED 0.82 MB/s, correctness ALL PASSED, fixtures ALL 5, `e2fsck` clean |
| D1·C6 | `cdd87b5` | write verify **PARTIAL 2/3**, fixtures ALL 5, `e2fsck` clean; NTFS correctly refused |
| D2·C4 | earlier | write ALL PASSED 0.76 MB/s, correctness ALL PASSED, fixtures ALL 5, `e2fsck` clean |
| D2·C5 | earlier | write ALL PASSED 0.73 MB/s, correctness ALL PASSED, fixtures ALL 5, `e2fsck` clean |
| D4·C1 | `98fae8f` | write verify 3/3, unaligned A+B, correctness A–G, fixtures ALL 16; **fsck pending** |
| D3·C5 | `98fae8f` | write verify 3/3, unaligned A+B, correctness A–G, fixtures ALL 5, `e2fsck` clean; **throughput unusable (started throttled)** |
| D3·C4 | `98fae8f` | write verify 3/3, unaligned A+B, correctness A–G, fixtures ALL 5, `e2fsck` clean |
| D4·C2 | `98fae8f` | write verify 3/3, unaligned A+B, correctness A–G, fixtures ALL 16, `fsck.exfat` clean — the finalizer-watchdog device, no process kill |
| D4·C5 | `98fae8f` | write verify 3/3, unaligned A+B, correctness A–G, fixtures ALL 5; **fsck pending** — Argon2id on the slowest device |

## Known gaps, and which matter

**D4 (Huawei, Android 9) has one case done — C1 — awaiting only its host fsck.**
The gap that remains there has the most history behind it: it is the device whose 0.43 MB/s writes blew the 10-second
finalizer budget and killed the process, which is why
`ExFatFileSystem.pendingReleases` exists. C2 on D4 is the single most valuable
missing cell, because it exercises that path on the hardware that broke it.

**No device has run C3 except D1.** ext4 write support is the largest change in
this release and it has been validated on one phone. The LUKS+ext4 cells (C4,
C5) cover the ext4 code on other devices, so ext4 itself is not single-device —
but VeraCrypt+ext4 as a combination is.

**C6's write path cannot be fully judged on-device.** `write`/`unaligned`/
`correct` decide their verdicts after a remount, and the runner's only remount
path takes a password, so an unencrypted partition reports `PARTIAL` /
`NOT VERIFIED`. Covered from the host with `e2fsck` instead. This is a runner
limitation, not a result — see `RUNNING_BENCHMARKS.md`.

**D2 is offline.** Its two cells are from an earlier session on an older build.
They should be re-run on the release build before they are quoted in a tag
report, since neither predates the ext4 metadata fixes.

**Fixture hashing is new.** Until today the VeraCrypt drives carried no hashes,
so every `fixtures` verdict on C1 and C2 before build `e78cf0a` was vacuous. Any
result quoted from before then covers throughput and structure, not data
integrity.

## Before tagging

1. Finish the two runs in flight (D4·C1, D3·C5).
2. Re-run D2's cells on the release build, once the OnePlus is reachable.
3. Get C2 onto D4 — the finalizer path on the device that broke it.
4. Host `fsck` every drive after its last run. Hash-clean is not fsck-clean.
5. Cut `docs/test-reports/<tag>.md` from this file.
