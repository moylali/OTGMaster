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

## 2026-09-29

### Pixel 10 Pro XL · Android 17 (SDK 37) · build 0.4.0 (46) commit eb4ff66 — VeraCrypt + exFAT and VeraCrypt + FAT32

Release-candidate code. Clean tree, installed immediately before, awake and
unthrottled at both ends, AC powered at 79%. Both partitions set read-write
(Read-only mode is on for this phone). Both drives were host-checked clean just
before (exFAT after the OnePlus run, FAT32 after the Samsung run).

| Field | VC + exFAT, `exFAT` | VC + FAT32, `VCFAT` |
|---|---|---|
| seq read (512 KiB) | 32.25 MB/s | 21.16 MB/s |
| random read | 2.9 ms each, 346.5 IOPS | 5.9 ms each, 169.9 IOPS |
| write | 16 MiB → 0.62 MB/s | 16 MiB → 2.57 MB/s |
| write verify | **ALL PASSED** | **ALL PASSED** |
| unaligned | **A + B PASS** | **A + B PASS** |
| correctness | **ALL PASSED** (A–G) | **ALL PASSED** (A–G) |
| fixtures | **ALL 16 MATCHED** | **ALL 16 MATCHED** |
| fsck / compare | pending | pending |

Report `otgbench-Pixel_10_Pro_XL-20260929-200239.txt`, on both drives. No USB
errors or retries.

### Huawei P20 Lite (ANE-LX1) · Android 9 (SDK 28) · build 0.4.0 (46) commit 4a88c83 — drive D, p2 plain ext4 + p1 VeraCrypt + ext4

Release-candidate code plus the runner fix `7b5e3bb`, whose first run this is:
after the startup unmount the log shows `Mount request: remounting plain
Partition 2`, and both partitions were measured. Clean tree, installed
immediately before, awake and unthrottled at both ends, **on battery at 35% →
30%**. p2 had been repaired and re-baselined on the host just before.

| Field | p2 plain ext4, `PLAINEXT4` | p1 VeraCrypt + ext4, `VCEXT4` |
|---|---|---|
| block read | 1.45 / 20.70 / 26.71 / 29.03 MB/s | 1.46 / 6.65 / 7.71 / 8.48 MB/s |
| seq read | 17.14 / 18.94 / 20.12 MB/s | 6.42 / 6.54 / 6.37 MB/s |
| random read | 4.9 ms each, 203.7 IOPS | 11.9 ms each, 83.8 IOPS |
| write | 16 MiB → 2.34 MB/s | 16 MiB → 1.91 MB/s |
| write verify | **ALL PASSED** (cached, cache-dropped, remounted) | **ALL PASSED** |
| unaligned | **A + B PASS** | **A + B PASS** |
| correctness | **ALL PASSED** (A–G) | **ALL PASSED** (A–G) |
| fixtures | **ALL 5 MATCHED** | **ALL 5 MATCHED** |
| e2fsck / compare | pending | pending |

Report `otgbench-ANE-LX1-20260929-195401.txt`, on both partitions. The Huawei's
first plain-ext4 result, and its remount pass completed — EMUI does not mount
ext4 itself, so p2 came up writable.

### Pixel 10 Pro XL · Android 17 (SDK 37) · build 0.4.0 (46) commit 3e3d051 — drive A, all four partitions

Release-candidate code. Clean tree, installed immediately before the run, awake
and unthrottled at both ends, on battery at 80%. Read-only mode is on for this
phone; the four partitions were set read-write for the run. Not host-checked
before the run; last checked clean after the OnePlus `4ea6a49` run.

| Partition | seq read (512 KiB) | write | write verify | unaligned | correctness | fixtures |
|---|---|---|---|---|---|---|
| P1 LUKS1 + FAT32 | 34.08 MB/s | 1.93 MB/s | **ALL PASSED** | **A + B PASS** | **ALL PASSED** | **ALL 4 MATCHED** |
| P2 LUKS1 + exFAT | 32.37 MB/s | 0.56 MB/s | **ALL PASSED** | **A + B PASS** | **ALL PASSED** | **ALL 4 MATCHED** |
| P3 LUKS2 + FAT32 | 34.96 MB/s | 2.26 MB/s | **ALL PASSED** | **A + B PASS** | **ALL PASSED** | **ALL 4 MATCHED** |
| P4 LUKS2 + exFAT | 32.45 MB/s | 0.65 MB/s | **ALL PASSED** | **A + B PASS** | **ALL PASSED** | **ALL 4 MATCHED** |

Report `otgbench-Pixel_10_Pro_XL-20260929-195058.txt`, written to all four
partitions.

**Host check:** all four **CLEAN** (`fsck.fat` P1 20026 files, P3 20023; `fsck.exfat`
P2 and P4 17 dirs, 20007 files). Each compare shows only the OnePlus 06:25 report
and this run's, plus `INDEX.txt`. FAT32: FAT[0] and FAT[1] change identically on P1
(26 bytes) and P3 (30 bytes). exFAT FAT churn ~4,099 entries on P2 (one 16 MiB
write since its baseline = 4,096 clusters) and ~8,161 on P4 (two runs) — the stale
entries a freed fragmented file leaves, not damage. (The partitions are listed by what
the runner measured, `[0] exFAT @33554432 … [3] LUKS1FAT @2048`, mapped to P1–P4
by start block.)

### Huawei P20 Lite (ANE-LX1) · Android 9 (SDK 28) · build 0.4.0 (46) commit f51131f — drive D p1, VeraCrypt + ext4

Release-candidate code. Clean tree, installed immediately before the run, awake
and unthrottled at both ends, on battery at 40%. Meant to cover p1 and p2; **p2
was not measured** — the runner's startup unmount took down the auto-mounted p2
and the mount request brought back p1 only. Fixed in `7b5e3bb`.

| Field | Value |
|---|---|
| Report | `otgbench-ANE-LX1-20260929-194018.txt`, on the drive |
| block read | 1.25 / 6.72 / 7.80 / 8.36 MB/s |
| seq read | 6.42 / 6.62 / 6.67 MB/s |
| random read | 11.5 ms each, 86.9 IOPS |
| write | 16 MiB → 1.43 MB/s |
| write verify | **ALL PASSED** (cached, cache-dropped, remounted) |
| unaligned | **A + B PASS** |
| correctness | **ALL PASSED** (A–G) |
| fixtures | **ALL 5 MATCHED the host-computed hashes** |
| e2fsck / compare | pending |

### OnePlus 7 (GM1901) · Android 16 (SDK 36) · build 0.4.0 (46) commit 3f535b7 — VeraCrypt + exFAT

Release-candidate code. Clean tree, installed immediately before the run, awake
and unthrottled at both ends, on battery at 54%. Read-only mode is on for this
phone; this partition was set read-write for the run. **The drive was not
host-checked before the run** — its last runs (Samsung `7beefe8`, and the three
read-only checks) are still unchecked, so the next host compare covers all of
them together.

| Field | Value |
|---|---|
| Drive | PNY 59151 MiB — VeraCrypt (AES/SHA-512, PIM 1) + exFAT, `exFAT` |
| Report | `otgbench-GM1901-20260929-193341.txt`, on the drive |
| block read | 5.24 / 19.29 / 17.52 / 19.40 MB/s |
| seq read | 12.88 / 12.76 / 12.82 MB/s |
| random read | 3.8 ms each, 265.7 IOPS |
| write | 16 MiB → 0.46 MB/s |
| write verify | **ALL PASSED** (cached, cache-dropped, remounted) |
| unaligned | **A + B PASS** |
| correctness | **ALL PASSED** (A–G) |
| fixtures | **ALL 16 MATCHED the host-computed hashes** |
| fsck / compare | **CLEAN** (host check after the OnePlus `3f535b7` run, covering the Samsung `7beefe8` run and the read-only checks too): `fsck.exfat` clean, 19 dirs, 20100 files. Compare vs the post-repair baseline: only the Samsung 09:32 read-write report and the OnePlus 19:33 report added, `INDEX.txt` modified; FAT ~1 entry; boot region identical. **Nothing from the two read-only runs reached the drive.** |

### Samsung Galaxy M30 (SM-M305F) · Android 10 (SDK 29) · build 0.4.0 (46) commit 3f535b7 — VeraCrypt + FAT32

Release-candidate code. Clean tree, installed immediately before the run, awake
and unthrottled at both ends, on battery at 63%, **drive plugged in directly — no
multiport adapter**. The phone's Read-only mode setting is on; this partition was
set read-write for the run (its per-partition choice, as the form switch sets it).

| Field | Value |
|---|---|
| Drive | PNY 59151 MiB — VeraCrypt (AES/SHA-512, PIM 1) + FAT32, `VCFAT` |
| Report | `otgbench-SM-M305F-20260929-193313.txt`, on the drive |
| block read | 1.90 / 4.57 / 12.20 / 10.76 MB/s |
| seq read | 2.63 / 7.22 / 7.00 MB/s |
| random read | 6.7 ms each, 148.2 IOPS |
| write | 16 MiB → 0.99 MB/s |
| write verify | **ALL PASSED** (cached, cache-dropped, remounted) |
| unaligned | **A + B PASS** |
| correctness | **ALL PASSED** (A–G) |
| fixtures | **ALL 16 MATCHED the host-computed hashes** |
| fsck | **CLEAN** — `fsck.fat`: 20089 files, 14241975/15113321 clusters, no "FATs differ" |
| baseline compare | only this run's report added since the last check (plus the two earlier reports already seen), `INDEX.txt` modified; **FAT[0] and FAT[1] each 21 bytes at byte 57,000,901, identical** (14 before this run); FSInfo 3 bytes |

No USB errors or retries in the whole run — the first Samsung run of the day
without the adapter, and the first to complete `fixtures`.

### Huawei P20 Lite (ANE-LX1) · Android 9 (SDK 28) · build 0.4.0 (46) commit 9b91328 — LUKS2 + ext4

Release-candidate code (`9b91328` is docs-only on top of `59805ed`). Clean tree,
installed immediately before the run, awake and unthrottled at both ends, **on
battery at 46%**. The drive was checked clean on the host just before.

| Field | Value |
|---|---|
| Drive | PNY USB 3.2.1 FD — LUKS2 (Argon2id) + ext4, `LUKS2EXT4` |
| Report | `otgbench-ANE-LX1-20260929-192628.txt`, on the drive |
| block read | 2.34 / 5.99 / 8.10 / 8.27 MB/s |
| seq read | 6.52 / 6.75 / 6.66 MB/s |
| random read | 8.9 ms each, 112.5 IOPS |
| write | 16 MiB → 0.55 MB/s |
| write verify | **ALL PASSED** (cached, cache-dropped, remounted) |
| unaligned | **A + B PASS** |
| correctness | **ALL PASSED** (A–G) |
| fixtures | **ALL 5 MATCHED the host-computed hashes** |
| e2fsck | **CLEAN** — `LUKS2EXT4: 20039/3784704 files, 927124/15138816 blocks` |
| baseline compare | against the check taken just before this run, one addition: this run's report (`INDEX.txt` modified). Free blocks and inodes each down by one more; superblock, group 0 and bitmap checksums changed with them. Nothing else. |

In line with this device's LUKS1+ext4 run this morning (seq 6.2–6.7, write 0.57).

### Drive D p2 (plain ext4) — host repair after Android's interrupted mounts

p2 had been mounted by the OnePlus's own Android at every plug-in and cut off
each time OTG Master claimed the reader, leaving `needs_recovery` set; one
laptop mount had already replayed a stale journal (08:30:49).

| Step | Result |
|---|---|
| `e2fsck -fn` before | `needs_recovery` set (last kernel mount 09:01:49, mount count 6); group 0 free blocks off by one (22985 vs 22986) — the same numbers as before the 08:30 replay |
| `e2fsck -fy` | journal recovered; the free-block count fixed; `FILE SYSTEM WAS MODIFIED` |
| `e2fsck -fn` after | **CLEAN**; `needs_recovery` gone, state clean |
| compare vs the 2026-09-28 baseline | 4 OnePlus reports added (07:44, 07:47, 07:55, 08:02), `INDEX.txt` modified; 8 scratch files removed from `BENCH_CORRECT/` and `BENCH_UNALIGNED/` (left by the Pixel run before the baseline, cleaned up by today's runs) with their two directories; free inodes +6 = 8 + 2 − 4. No other file changed. |

So the interrupted Android mounts and the journal replay damaged nothing beyond
the one summary count. p1, written by the same app code and invisible to
Android, was clean throughout — the count came from the OS side. Re-baselined
after the repair (20016 files, 20033 tree entries); the old baseline is kept as
`PLAINEXT4.pre-repair-20260929`.

### LUKS2 + ext4 (Drive C) — host check before the Huawei release-candidate run

`e2fsck` **CLEAN**. The compare shows only the 2026-09-28 Pixel (21:14) and OnePlus
(21:28) reports and `INDEX.txt`; free blocks and inodes are down by exactly 2.
This closes the host check for both of those runs.


### OnePlus 7 (GM1901) · Android 16 (SDK 36) · build 0.4.0 (46) commit 5bc20eb — drive D p1, VeraCrypt + ext4

Clean tree, installed immediately before the run, awake and unthrottled at both
ends, **on battery at 57%**. The release candidate's code: connection fix, V11–V13,
read-only mode (off for this partition), the ext4 journal guard. Run with
`--es drive VCEXT4`: p2 is mounted read-only on this phone because Android had
mounted it and been cut off (`needs_recovery`), so its write sections could only
fail; it needs a host `e2fsck` first.

| Field | Value |
|---|---|
| Report | `otgbench-GM1901-20260929-190728.txt`, on the drive |
| block read | 1.33 / 14.80 / 17.89 / 16.84 MB/s |
| seq read | 11.48 / 11.79 / 11.72 MB/s |
| random read | 5.8 ms each, 171.9 IOPS |
| write | 16 MiB → 1.09 MB/s |
| write verify | **ALL PASSED** (cached, cache-dropped, remounted) |
| unaligned | **A + B PASS** |
| correctness | **ALL PASSED** (A–G) |
| fixtures | **ALL 5 MATCHED the host-computed hashes** |
| e2fsck | **CLEAN** — `VCEXT4: 20038/764032 files, 685287/3053504 blocks` |
| baseline compare | 3 reports added since the baseline — 06:33 Huawei, 08:02 OnePlus (`dae0ebf`), and this run's — and `INDEX.txt` modified. Free inodes 743997 → 743994 (one per report), free blocks 2368221 → 2368217 (the reports and `INDEX.txt` growing); superblock, group 0 and bitmap checksums changed with them. Nothing else. This covers the `dae0ebf` run's p1 too. |

**Throughput is about half the `dae0ebf` run on the same card and phone** (seq
21.1 → 11.7 MB/s, write 3.17 → 1.09 MB/s). That run was also awake and
unthrottled; this one was on battery at 57%. The builds differ only in error
paths and an unused read-only wrapper, so a code cause is unlikely, but not
shown. Not comparable figures until re-run under matching conditions.

### Samsung Galaxy M30 (SM-M305F) · Android 10 (SDK 29) — VeraCrypt + exFAT, read-only mode checks

Three `--es tests write` runs to check read-only mode on exFAT, the one
filesystem its host tests cannot cover (libexfat is native Android code). Clean
tree each time, installed immediately before. Setting toggled through the real
Settings drawer; the unlock form's switch followed it.

| Time | Commit | Read-only | Result |
|---|---|---|---|
| 09:29 | `0aee16b` | on | mounted `exfat mounted successfully (read-only)`, card tagged **READ-ONLY**. `write verify` **FAILED** as intended — but the refusal came from `ReadOnlyBlockDeviceDriver` (`volume is mounted read-only`): libexfat's mkdir still issued a device write despite `ro`. Fixed in `640d05c`. |
| 09:31 | `640d05c` | on | `write verify` **FAILED** as intended, now with `exFAT volume is mounted read-only` from the exFAT layer; no write reached the device layer. Report not written to the drive, as intended. |
| 09:31 | `640d05c` | off | mounted read-write; 16 MiB → 0.36 MB/s; `write verify` **ALL PASSED** (remounted); report written to the drive. |

Host compare afterwards (after the OnePlus `3f535b7` run): only the 09:32
read-write run's report and the OnePlus report were added — **nothing from the
read-only runs**, confirmed through the kernel's exFAT driver rather than ours.

### Samsung Galaxy M30 (SM-M305F) · Android 10 (SDK 29) · build 0.4.0 (46) commit 7beefe8 — VeraCrypt + exFAT

Clean tree (later commits are docs only), installed immediately before the run,
awake and unthrottled at both ends, AC powered at 100% — through the USB-C
multiport adapter the drive also hangs off. The V12 validation run.

| Field | Value |
|---|---|
| Drive | PNY 59151 MiB — VeraCrypt (AES/SHA-512, PIM 1) + exFAT, `exFAT` |
| Report | `Documents/otgbench-SM-M305F-20260929-085653.txt` on the phone only |
| block read | 3.84 / 11.65 / 12.97 / 11.68 MB/s |
| seq read | 9.76 / 9.99 / 10.04 MB/s |
| random read | 6.5 ms each, 154.0 IOPS |
| write | 16 MiB → **0.36 MB/s** (0.68 on the previous run) |
| write verify | **ALL PASSED** (cached, cache-dropped, remounted) |
| unaligned | **A + B PASS** |
| correctness | **ALL PASSED** (A–G) |
| fixtures | **FAILED** — `exFAT read failed at offset 615776256 (262144 bytes): -5` |
| fsck / compare | **CLEAN** (host check after the OnePlus `3f535b7` run, covering the Samsung `7beefe8` run and the read-only checks too): `fsck.exfat` clean, 19 dirs, 20100 files. Compare vs the post-repair baseline: only the Samsung 09:32 read-write report and the OnePlus 19:33 report added, `INDEX.txt` modified; FAT ~1 entry; boot region identical. **Nothing from the two read-only runs reached the drive.** |

**The adapter disconnected, not the app.** At 08:56:51.5 `UsbHostManager` logged
the removal of every device on the multiport adapter at once — its gigabit LAN
(`0bda:8153`), its hub (`2109:8817`) and the drive (`154b:1006`). Every transfer
then returned `-1`; V12's Reset Recovery ran before each retry and failed
immediately (`bulk only mass storage reset failed!`) because there was no device
to reset, was logged, and the retries proceeded to `MAX_RECOVERY_ATTEMPTS` as
before. So this run shows V11 + V12 do not disturb normal transfers — every write
section passed — but not V12's recovery succeeding on hardware; that rests on
`ScsiResetRecoveryTest`. This morning's dropped Samsung exFAT run went through the
same adapter.

### Samsung Galaxy M30 (SM-M305F) · Android 10 (SDK 29) · build 0.4.0 (46) commit a192b12 — VeraCrypt + exFAT

Clean tree, installed immediately before the run, awake and unthrottled at both
ends, on the charger directly at 100%. The hardware validation run for V11, on
the drive repaired and re-baselined this morning.

| Field | Value |
|---|---|
| Drive | PNY 59151 MiB — VeraCrypt (AES/SHA-512, PIM 1) + exFAT, `exFAT` |
| Report | `Documents/otgbench-SM-M305F-20260929-081623.txt` on the phone only — writing it to the drive failed |
| block read | 3.08 / 12.04 / 12.88 / 11.82 MB/s |
| seq read | 9.82 / 9.93 / 10.05 MB/s |
| random read | 6.3 ms each, 159.3 IOPS |
| write | 16 MiB → 0.68 MB/s |
| write verify | **ALL PASSED** (cached, cache-dropped, remounted) |
| unaligned | **A + B PASS** |
| correctness | **ALL PASSED** (A–G) |
| fixtures | **FAILED** — `exFAT read failed at offset 732692480 (262144 bytes): -5` |
| fsck | **CLEAN** — `fsck.exfat`: directories 19, files 20098 |
| baseline compare | **NO CHANGE** in files, FAT or boot region against the post-repair baseline. One directory fewer (20 → 19; tree entries 20117 → 20116): `BENCH_WRITE`, left by the repair, which this run's `write verify` recreated and removed as designed. The script's verdict ignores directory-only changes — a gap in `volume_baseline.sh`, not in the drive. |

A read returned `-1`; every retry then failed with `wrong csw tag!` until
`MAX_RECOVERY_ATTEMPTS`, and so did the next command, and the report write after
that. **V11 held** — no `IllegalArgumentException`, nothing misplaced — but it
exposed V12: libaums never sent Reset Recovery after a failed transfer, so the
device's leftover CSW kept every later command one status behind. The failure
was a read, after every write section had finished, so no write was in flight.

### OnePlus 7 (GM1901) · Android 16 (SDK 36) · build 0.4.0 (46) commit dae0ebf — drive D, p2 plain ext4 + p1 VeraCrypt + ext4

**The hardware confirmation of `9404e46`**, and the first run on any device in
which a plain partition passed the remount pass. Clean tree, installed
immediately before the run, awake and unthrottled at both ends, on battery at
81%. An earlier start on the same code was stopped during the read-only sections
because its APK had been built before `dae0ebf` was committed and was labelled
`9404e46-dirty`; nothing had been written.

With `dae0ebf` the runner requests the unlock even though the re-probe has
already auto-mounted p2, so both partitions were mounted — and every remount in
the run unmounted and remounted **two drives on one USB connection**, the case
that failed on `b850696`.

| Field | p2 plain ext4, `PLAINEXT4` | p1 VeraCrypt + ext4, `VCEXT4` |
|---|---|---|
| block read | 1.97 / 43.88 / 62.13 / 59.33 MB/s | 4.60 / 19.84 / 23.45 / 23.84 MB/s |
| seq read | 31.19 / 34.17 / 36.50 MB/s | 21.12 / 20.22 / 21.23 MB/s |
| random read | 3.9 ms each, 253.8 IOPS | **94.7 ms each, 10.6 IOPS** — 5.5 ms on the same card and phone this morning; unexplained |
| write | 16 MiB → 3.40 MB/s | 16 MiB → 3.17 MB/s |
| write verify | **ALL PASSED** (cached, cache-dropped, remounted) | **ALL PASSED** |
| unaligned | **A + B PASS** | **A + B PASS** |
| correctness | **ALL PASSED** (A–G) | **ALL PASSED** (A–G) |
| fixtures | **ALL 5 MATCHED** | **ALL 5 MATCHED** |
| e2fsck / compare | pending | pending |

Report `otgbench-GM1901-20260929-080242.txt`, written to both partitions.

---

### OnePlus 7 (GM1901) · Android 16 (SDK 36) · build 0.4.0 (46) commit 9404e46 — drive D p2, plain ext4

The first run with the connection fix, before the runner change. After the
runner's startup unmount the re-probe auto-mounted p2, the runner then saw a
mounted drive and skipped the unlock, so this run measured **p2 only** — the
defect `dae0ebf` fixes.

| Field | Value |
|---|---|
| seq read | 34.88 / 33.28 / 35.69 MB/s |
| write | 16 MiB → 3.38 MB/s |
| write verify | **PARTIAL — 2 of 3**; the remount pass was not performed. p2 was the only drive mounted, p1's candidate held the connection, so nothing re-probed and p2 did not come back. |
| unaligned | **A + B PASS** |
| correctness | **ALL PASSED** (A–G) |
| fixtures | **ALL 5 MATCHED** |

The trace confirms the fix. At 07:54:00 a two-drive unmount went: p2 done with
`stillUnmounting=true` and **no probe**; p1 done 3 ms later, old connection
closed; then one probe, one new connection (`11bd0c8`), and p1 unlocked through
it. On `b850696` a probe opened a second connection while p1 was still
unmounting.

---

### OnePlus 7 (GM1901) · Android 16 (SDK 36) · build 0.4.0 (46) commit a03da36 — drive D, two `write,unaligned` repros

Instrumented build (connection identities logged), run twice with
`--es tests write,unaligned` to reproduce the `b850696` failure. **Neither
reproduced it** — the window depends on p1's unmount finishing after the re-probe,
and in both runs it finished within 4 ms of p2's. Both runs happened to measure
p2, the first drive mounted.

| Run | write verify | unaligned |
|---|---|---|
| 07:41 | **PARTIAL — 2 of 3** (p2 did not come back; p1 did) | **A + B PASS** |
| 07:44 | **PARTIAL — 2 of 3** | **A + B PASS** |

What they did show: every unmount logged `opened=null`, so the old connection
was never closed, and at 07:44:46 a probe opened a new connection 19 ms after
the first of two unmounts finished, while the second was still in progress.
Both are fixed in `9404e46`.

---

### Samsung Galaxy M30 (SM-M305F) · Android 10 (SDK 29) · build 0.4.0 (46) commit a03da36 — VeraCrypt + FAT32

Clean tree, installed immediately before the run, awake and unthrottled at both
ends, on the charger directly (no hub) at 100%. `a03da36` is logging only; a
single-partition drive does not reach the path `9404e46` changes.

| Field | Value |
|---|---|
| Drive | PNY 59151 MiB — VeraCrypt (AES/SHA-512, PIM 1) + FAT32, `VCFAT` |
| Report | `Documents/otgbench-SM-M305F-20260929-075345.txt` on the phone only — writing it to the drive failed |
| block read | 1.97 / 5.41 / 13.24 / 10.86 MB/s |
| seq read | 2.85 / 7.26 / 7.22 MB/s |
| random read | 7.1 ms each, 141.8 IOPS |
| write | 16 MiB → 1.04 MB/s |
| write verify | **ALL PASSED** (cached, cache-dropped, remounted) |
| unaligned | **A + B PASS** |
| correctness | **ALL PASSED** (A–G) |
| fixtures | **FAILED** — `IllegalArgumentException` from `ByteBuffer.limit()` in libaums `transferOneCommand` |
| fsck | **CLEAN** — `fsck.fat`: 20088 files, 14241973/15113321 clusters, no "FATs differ" |
| baseline compare | **identical to the compare taken before this run**: the same two earlier reports and `INDEX.txt`, and FAT[0]/FAT[1] each the same 14 bytes at byte 57,000,901. This run's scratch files were all removed and its report never reached the drive, so it left no trace — the fixtures failure was a read. |

A read returned `-1`, the retry got `wrong csw tag!`, and the next retry threw.
`transferOneCommand` takes `inBuffer.position()` as each attempt's start, but the
failed attempt had already advanced it with a partial read, so the retry set a
limit past the buffer's end. `IllegalArgumentException` is not an `IOException`,
so it escaped the retry loop at once: a transport hiccup the loop exists to
absorb became a hard failure, and the device stayed out of step for the report
write too. A libaums defect, not the drive.

---

### VeraCrypt + exFAT (PNY, `VCEXFAT`) — host check and repair after the dropped Samsung run

The Samsung run of 06:54 lost this drive's transport during `write verify` and
was force-stopped (not logged; no result). On the host:

| Check | Result |
|---|---|
| `fsck.exfat` | **ERROR** — `/BENCH_WRITE: cluster 0xd70633 is marked as free` |
| compare | only two earlier reports and `INDEX.txt` changed; FAT ~1 entry; boot region identical. `BENCH_WRITE` unreadable on the host (`Input/output error`). |
| repair | `fsck.exfat -y` truncated `BENCH_WRITE` → `clean. directories 20, files 20098` |
| re-verify | **CLEAN** |

`BENCH_WRITE` is the directory `write verify` was creating when the transport
died (`Failed to create exFAT directory: -5`). Its directory entry reached the
disk and the bitmap update did not — the torn write exFAT's lack of a journal
allows, in the worse of the two orders: bitmap-first would have leaked a cluster
harmlessly, entry-first leaves a cluster a later allocation can hand out twice.
Whether that order is libexfat's or the block cache's flush order is not yet
known. Reachable only when a drive drops mid-write. The baseline predates the
repair and is being retaken.

### Huawei P20 Lite (ANE-LX1) · Android 9 (SDK 28) · build 0.4.0 (46) commit b850696 — LUKS1 + ext4

Clean tree, installed immediately before the run, awake and unthrottled at both
ends, on battery at 82%. The first LUKS1+ext4 run on this device.

| Field | Value |
|---|---|
| Drive | PNY USB 3.2.1 FD — LUKS1 + ext4, `LUKS1EXT4` |
| Report | `otgbench-ANE-LX1-20260929-070630.txt` |
| Sections | free, block, dir, path, seq, random, opens, write, unaligned, correct, fixtures |
| block read | 1.96 / 5.30 / 7.73 / 8.29 MB/s (4 / 64 / 512 / 4096 KiB span) |
| seq read | 6.17 / 6.53 / 6.73 MB/s (32 / 128 / 512 KiB buf) |
| random read | 9.0 ms each, 110.6 IOPS |
| write | 16 MiB → 0.57 MB/s |
| write verify | **ALL PASSED** (cached, cache-dropped, remounted) |
| unaligned | **A + B PASS** |
| correctness | **ALL PASSED** (A–G) |
| fixtures | **ALL 5 MATCHED the host-computed hashes** |
| e2fsck | **CLEAN** — `LUKS1EXT4: 20040/3792896 files, 927644/15142400 blocks` |
| baseline compare | 2 reports added — this run's and `otgbench-Pixel_10_Pro_XL-20260928-212429.txt`, which postdates the baseline — and `INDEX.txt` modified. Free blocks 14214758 → 14214756, free inodes 3772858 → 3772856: one block and one inode per report. Superblock, group 0 and bitmap checksums changed with them. Nothing else. |

---

### OnePlus 7 (GM1901) · Android 16 (SDK 36) · build 0.4.0 (46) commit b850696 — drive D p1, VeraCrypt + ext4

Clean tree, installed immediately before the run, awake and unthrottled at both
ends, on battery at 91%. **Completed with failures — an app defect, not the card.**

| Field | Value |
|---|---|
| Drive | Realtek card reader, 11927 MiB — VeraCrypt (AES/SHA-512, PIM 1) + ext4, `VCEXT4` |
| Report | `Documents/otgbench-GM1901-20260929-070404.txt` on the phone only — nothing was mounted at the end, so no copy reached the card |
| Sections | free, block, dir, path, seq, random, opens, write, unaligned, correct, fixtures |
| block read | 1.81 / 15.43 / 26.10 / 23.07 MB/s |
| seq read | 20.82 / 21.39 / 20.99 MB/s |
| random read | 5.5 ms each, 180.6 IOPS |
| write | 16 MiB → 3.15 MB/s |
| write verify | **ALL PASSED** (cached, cache-dropped, remounted) |
| unaligned | **NOT VERIFIED** — the drive did not come back after unmounting |
| correctness | **could not run** — no live mount |
| fixtures | **FAILED** — `block device is closed (volume was unmounted)` |
| e2fsck / compare | pending |

What happened, from logcat. The runner's startup `unmountAll` took down the
plain p2 the app had auto-mounted, and the mount request that followed covers
encrypted candidates only, so the run started with p1 alone. `write verify`'s
remount re-probed the card, and the app auto-mounted p2 again. The next remount
(`unaligned`) therefore unmounted **two** drives on one USB device. The app
re-opened the card, and the mount request that followed was issued for **2
candidates** — the card has one encrypted partition. Reads on it failed with
`result == -1`, the signature of a USB connection that has been closed, and
nothing mounted. Every later section then had no drive.

The duplicate candidate points at a stale restored entry holding a closed
connection. Not yet confirmed; to be reproduced with instrumentation before
any fix. It bears directly on plain-partition coverage: a runner that also
remounts p2 would make every remount this two-drive case.

A Samsung M30 run on the same build, with VC+exFAT and VC+FAT32 attached
through a hub, was force-stopped and is not logged: the exFAT drive's transport
died during `write verify` (`MAX_RECOVERY_ATTEMPTS Exceeded`), and the run then
sat in `unaligned` against the dead handle for nine minutes. No result. That
drive is to be checked on the host before anything else touches it.

### Huawei P20 Lite (ANE-LX1) · Android 9 (SDK 28) · build 0.4.0 (46) commit 6c58cd4 — drive D p1, VeraCrypt + ext4

Clean tree, awake and unthrottled at both ends. The first run on this card after
`4ea6a49` made the report writer and `benchFixtures` resolve by tag rather than
by position — the A/B for the host result logged below it, where six reports had
landed on the wrong partition.

| Field | Value |
|---|---|
| Drive | Realtek card reader, 11927 MiB — VeraCrypt (AES/SHA-512, PIM 1) + ext4, `VCEXT4` |
| Report | `otgbench-ANE-LX1-20260929-063359.txt` |
| seq read | 6.61 / 6.72 / 6.71 MB/s (32 / 128 / 512 KiB buf) |
| write | 16 MiB → 1.62 MB/s |
| write verify | **ALL PASSED** (cached, cache-dropped, remounted) |
| unaligned | **A + B PASS** |
| correctness | **ALL PASSED** (A–G) |
| fixtures | **ALL 5 MATCHED the host-computed hashes** |

**Host check (laptop, after the run):**

| Partition | `e2fsck` / compare |
|---|---|
| p1 VeraCrypt + ext4 | **CLEAN**. Only this run's report added and `INDEX.txt` modified; free blocks 2368221 → 2368220, free inodes 743997 → 743996 — one file's worth. The script's verdict reads `CHANGES FOUND` because superblock and `dumpe2fs` differ, which the counters above account for. |
| p2 plain ext4 | **CLEAN**, compare **NO CHANGE**. Before `4ea6a49` this partition received every report; now it receives none. |
| p3 NTFS | `Android/data/` added (8 entries, incl. `.nomedia`, `com.huawei.appmarket/`, `com.huawei.systemmanager/`), all timestamped 06:25:00–01 — the moment the card was inserted, nine minutes before this report. **Written by EMUI, not OTG Master:** Huawei's Android 9 mounts NTFS natively and scaffolds `Android/data` on any volume it mounts. |

The p3 result limits what the NTFS baseline can show: on a phone whose OS
mounts NTFS itself, "p3 unchanged" cannot be used to prove the app never
writes NTFS. The app-side claim rests on the partition being refused — it is
filtered from the picker and never opened. The p3 baseline is not being
retaken: NTFS is unsupported, so future compares of p3 are skipped rather than
re-armed against the OS's own writes.

The report's placement is the result this run was for: it is on p1, the drive
measured, and not on p2. `4ea6a49` is confirmed on hardware.

---

## 2026-09-28

### Huawei P20 Lite (ANE-LX1) · Android 9 (SDK 28) · build 0.3.13 (46) commit dc29660 — VeraCrypt + FAT32

Awake and unthrottled at both ends. Full run completed successfully on the device.

| Field | Value |
|---|---|
| Drive | PNY USB 3.2.1 FD, 59151 MiB — VeraCrypt (AES/SHA-512, PIM 1) + FAT32, `VCFAT` |
| Report | `otgbench-ANE-LX1-20260928-223833.txt` |
| Sections | free, block, dir, path, seq, random, opens, write, unaligned, correct, fixtures |
| block read | 1.65 / 5.46 / 7.16 / 7.92 MB/s (4 / 64 / 512 / 4096 KiB span) |
| seq read | 1.49 / 3.74 / 3.80 MB/s (32 / 128 / 512 KiB buf) |
| random read | 11.2 ms each, 89.1 IOPS |
| dir listing | dense_short cold 1316.4 / warm 455.3 ms; dense_lfn cold 1643.5 / warm 441.3 ms |
| opens | short 452.5 ms each, lfn 464.8 ms each |
| write | 16 MiB → 0.98 MB/s |
| write verify | **ALL PASSED** (cached, cache-dropped, remounted) |
| unaligned | **A + B PASS** |
| correctness | **ALL PASSED** (A–G) |
| fixtures | **ALL 16 MATCHED the host-computed hashes** |
| fsck | **FAILED — "FATs differ" (exited 1)**; structurally intact, failure is solely due to the known mirror bug |
| baseline compare | **CLEAN** — exactly 2 files added (the reports), FAT0 changed by only 11 bytes, FAT1 identical. **Massive data corruption is completely gone.** |

---


### OnePlus 7 (GM1901) · Android 16 (SDK 36) · build 0.4.0 (46) commit 4ea6a49 — LUKS1/2 + FAT32/exFAT, manifests regenerated

Clean tree, awake and unthrottled. The first run of this drive after its
manifests were regenerated with the fixed `prepare_drive_a.sh` logic, and the
first with `benchFixtures` and the report writer resolving by tag.

| Partition | write verify | unaligned | correctness | fixtures |
|---|---|---|---|---|
| P1 LUKS1 + FAT32 | **ALL PASSED** | **A + B PASS** | **ALL PASSED** | **ALL 4 MATCHED** |
| P2 LUKS1 + exFAT | **ALL PASSED** | **A + B PASS** | **ALL PASSED** | **ALL 4 MATCHED** |
| P3 LUKS2 + FAT32 | **ALL PASSED** | **A + B PASS** | **ALL PASSED** | **ALL 4 MATCHED** |
| P4 LUKS2 + exFAT | **ALL PASSED** | **A + B PASS** | **ALL PASSED** | **ALL 4 MATCHED** |

Every section on every partition — against a drive that the day before gave a
result for its first partition only, and this morning failed fixtures on all
four.

What this run cannot show on its own: the four partitions carry identical
fixtures, so a pass does not say which partition each fixtures section read. The
host compare can, because the report writer now puts a copy on every drive the
run measured — each of the four should carry this run's report.

**Host check (laptop, after the run):**

| Partition | fsck | Compare against the pre-run baseline |
|---|---|---|
| P1 LUKS1 + FAT32 | **CLEAN** — 20025 files, 86191/4185352 clusters | report added, `INDEX.txt` modified; FAT[0] and FAT[1] **each 13 bytes at byte 558,685, identical** (V9); FSInfo 3 bytes. First attempt was lost to a script edited mid-run; re-run afterwards. |
| P2 LUKS1 + exFAT | **CLEAN** — 17 dirs, 20006 files | report added, `INDEX.txt` modified; boot region and FAT identical. The script printed `NO CHANGE` despite listing the two — the verdict bug fixed in `f8b8042`. |
| P3 LUKS2 + FAT32 | **CLEAN** — 20022 files, 86184/4182026 clusters | report + `INDEX.txt` added; FAT[0] and FAT[1] **each 17 bytes at byte 394,473, identical** (V9); FSInfo 3 bytes |
| P4 LUKS2 + exFAT | **CLEAN** — 17 dirs, 20006 files | report added, `INDEX.txt` modified; boot region identical; FAT ~4,081 entries changed |

The report landed on every partition the run measured (all four confirmed), so each partition's `fixtures` pass is now
attributable to it.

P4's FAT churn is consistent with the run, not with damage: the 16 MiB write
test is 4,096 × 4 KiB clusters, and exFAT only writes FAT entries for a
fragmented file. It leaves them stale when the file is freed, which `fsck.exfat`
accepts. The allocation bitmap was not captured, so this is plausible rather
than proven.

---

### OnePlus 7 (GM1901) · Android 16 (SDK 36) · build 0.4.0 (46) commit eb26e3e — VeraCrypt + FAT32

Clean tree, installed immediately before the run, awake and unthrottled at both
ends. FAT32 on a third device, after both FAT fixes.

| Field | Value |
|---|---|
| Drive | PNY 59151 MiB — VeraCrypt (AES/SHA-512, PIM 1) + FAT32, `VCFAT` |
| block read | 2.81 / 16.55 / 14.82 / 15.90 MB/s |
| seq read | 3.83 / 9.21 / 9.27 MB/s |
| random read | 10.8 ms each, 92.7 IOPS |
| write | 16 MiB → 1.28 MB/s |
| write verify | **ALL PASSED** (cached, cache-dropped, remounted) |
| unaligned | **A + B PASS** |
| correctness | **ALL PASSED** (A–G) |
| fixtures | **ALL 16 MATCHED the host-computed hashes** |
| fsck | **CLEAN** — `fsck.fat`, no "FATs differ" |
| baseline compare | 2 reports added (this run's and the Huawei run at 05:52, both since the baseline), `INDEX.txt` modified; **FAT[0] and FAT[1] each 14 bytes at byte 57,000,901, identical**; FSInfo 3 bytes |

V9 on a second device: both FAT copies written, byte for byte the same. The
compare spans two runs, so the 14 bytes are the two runs combined.

---

### SD card (drive D) — host verification after its Huawei runs

Checked on the laptop against baselines taken before the six Huawei runs of
2026-09-28 (21:02–21:44). Every run measured the VeraCrypt partition only.

| Partition | `e2fsck` | Compare |
|---|---|---|
| p1 VeraCrypt + ext4 | **CLEAN** | **no files changed**; free blocks and free inodes unchanged |
| p2 plain ext4 | **CLEAN** | 6 reports added, `INDEX.txt` modified; 6 blocks, 6 inodes |

Both partitions are healthy, and every change on each is accounted for — but the
reports are on the wrong one. The runner wrote each run's report to whichever
volume was first in the mount list at the end, and the app had re-mounted the
plain partition on its own. Fixed in the same commit as this row: reports now go
to the drive the run measured, resolved by tag.

On p1 the one metadata change is group 0's unused-inode count, 8107 → 8103, with
free inodes unchanged. `bg_itable_unused` is a high-water mark: allocating
inodes lowers it and freeing them does not raise it. The runs created and deleted
their scratch files, leaving exactly that trace, and `e2fsck` agrees it is
consistent.

Also: this card's earlier `fixtures: ALL 5 MATCHED` on the Huawei may have been
read from p2 rather than p1 — `benchFixtures` resolved by position until this
commit, and both partitions carry identical fixtures, so the pass does not say
which. p1's health is not in question; the compare above shows it untouched.

---

### Huawei P20 Lite (ANE-LX1) · Android 9 (SDK 28) · build 0.4.0 (46) commit 3b03bae — VeraCrypt + FAT32, mirror fix

**The hardware confirmation of V9 (`149b2d2`) — the first FAT32 drive written by
this app to pass `fsck.fat` outright.** Before the run the drive's two FATs had
been resynced with `fsck.fat -a` and a fresh baseline taken, so any divergence
afterwards could only come from this build.

Report label reads `3b03bae-dirty`; the dirt was an untracked report file in the
repo root, since ignored in `5f00fd0`. The code is exactly `3b03bae`, which
carries V8 and V9.

| Field | Value |
|---|---|
| Drive | PNY 59151 MiB — VeraCrypt (AES/SHA-512, PIM 1) + FAT32, `VCFAT` |
| block read | 2.05 / 6.95 / 7.95 / 8.20 MB/s |
| seq read | 1.51 / 3.77 / 3.73 MB/s |
| random read | 16.7 ms each, 59.9 IOPS |
| write | 16 MiB → 1.15 MB/s |
| write verify | **ALL PASSED** (cached, cache-dropped, remounted) |
| unaligned | **A + B PASS** |
| correctness | **ALL PASSED** (A–G) |
| fixtures | **ALL 16 MATCHED the host-computed hashes** |
| fsck | **CLEAN** — `fsck.fat`: 20087 files, 14241971/15113321 clusters, no "FATs differ" |

Against the baseline taken before the run:

| Region | Change |
|---|---|
| files | 1 added (the report), 1 modified (`INDEX.txt`) — both runner output |
| FAT[0] | 7 bytes at byte 57,000,901, ~1 entry |
| FAT[1] | **7 bytes at byte 57,000,901, ~1 entry — identical to FAT[0]** |
| reserved | 3 bytes at offset 1000 — the FSInfo free-cluster count |

The previous run on this drive, before V9, showed FAT[0] changed and FAT[1]
byte-identical to its baseline — never written. Here both change, by the same
bytes at the same offset.

Taken together with the run before it, both libaums FAT32 defects fixed in this
release are now confirmed on the hardware that exposed them: V8 (a corrupt chain
no longer wipes unrelated clusters) and V9 (every FAT copy is written).

---

### OnePlus 7 (GM1901) · Android 16 (SDK 36) · build 0.4.0 (46) commit 5f00fd0 — drive A, all four partitions

Clean tree, installed immediately before the run, awake and unthrottled at both
ends, battery 100%. All four partitions in one pass.

| Partition | seq read (512 KiB) | write verify | unaligned | correctness | fixtures |
|---|---|---|---|---|---|
| P1 LUKS1 + FAT32 | 11.58 MB/s | **ALL PASSED** | **A + B PASS** | **ALL PASSED** | 3 of 3 FAILED |
| P2 LUKS1 + exFAT | 11.06 MB/s | **ALL PASSED** | **A + B PASS** | **ALL PASSED** | 3 of 3 FAILED |
| P3 LUKS2 + FAT32 | 11.09 MB/s | **ALL PASSED** | **A + B PASS** | **ALL PASSED** | 3 of 3 FAILED |
| P4 LUKS2 + exFAT | 11.28 MB/s | **ALL PASSED** | **A + B PASS** | **ALL PASSED** | 3 of 3 FAILED |

**The multi-partition path works.** On the Samsung the day before, the same
drive gave a result for its first partition only; every section on partitions
2–4 failed with `block device is closed (volume was unmounted)`. That run is what
exposed the three runner defects fixed in `6df73db` (stale references across
drives), `d216ed8` (within one drive) and `dc29660` (the remount check racing the
app's own auto-mount). This is the A/B for all three on the drive that found them.

**The fixtures failures are a manifest bug, not the drive.** All four partitions
report byte-identical results — `dense_short/` and `dense_lfn/` "LISTING
DIFFERS" with the same expected and actual hashes, and
`nested/leaf_at_depth_10.dat missing`.

*Correction, same day:* the identical results are **not** evidence on their own.
`benchFixtures` still resolved its volume by position — the fix meant for it in
`6df73db` had landed in `benchUnaligned` instead — so all four sections read the
same partition and this is one comparison reported four times. The conclusion
survives because the cause was found independently in the script itself: the
cause is `prepare_drive_a.sh`, which wrote the
manifest two ways its siblings had already been corrected away from: it hashed
the directory listings including a trailing newline, where the benchmark joins
entries with `\n` as a separator, and it recorded the leaf as
`nested/leaf_at_depth_10.dat` when the file is ten directories deeper. Fixed in
the same commit as this row. The drive's manifests need regenerating before its
fixtures verdicts mean anything; its file contents have not been shown to be
wrong.

Host fsck on all four partitions is still outstanding — in particular the two
exFAT ones, which threw `EIO` on the Samsung and have never been checked since.

---

### Huawei P20 Lite (ANE-LX1) · Android 9 (SDK 28) · build 0.3.13 (46) commit dc29660 — VeraCrypt + FAT32, re-prepared drive

**The hardware confirmation of the FAT chain fix (V8, `f514eac`).** Same device
and same container/filesystem as the run that found the corruption, on a drive
re-prepared with `prepare_vc_fat32.sh --fill-to-free 4` and baselined before
the run.

| Field | Value |
|---|---|
| Drive | PNY 59151 MiB — VeraCrypt (AES/SHA-512, PIM 1) + FAT32, `VCFAT`, filled to ~4 GiB free |
| Report | `otgbench-ANE-LX1-20260928-223833.txt` |
| Sections | free, block, dir, path, seq, random, opens, write, unaligned, correct, fixtures |
| block read | 1.65 / 5.46 / 7.16 / 7.92 MB/s |
| seq read | 1.49 / 3.74 / 3.80 MB/s |
| random read | 11.2 ms each, 89.1 IOPS |
| write | 16 MiB → 0.98 MB/s |
| write verify | **ALL PASSED** (cached, cache-dropped, remounted) |
| unaligned | **A + B PASS** |
| correctness | **ALL PASSED** (A–G) |
| fixtures | **ALL 16 MATCHED the host-computed hashes** |
| fsck | exit 1 — `FATs differ but appear to be intact. Using first FAT.` Nothing else. |
| baseline compare | see below |

Against the baseline taken before the run:

| Region | Change | Accounted for by |
|---|---|---|
| files | 2 added, 0 modified, 0 removed | the report and `INDEX.txt` (fresh drive, so the index is new) |
| FAT[0] | 11 bytes, ~2 entries | the clusters for those two files |
| FAT[1] | identical | libaums never writes it |
| reserved sectors | 3 bytes at offset 1000 | FSInfo free-cluster count (sector 1, field at 488) |

Set against the run that found the damage — same device, same filesystem, the
full suite — FAT[0] went from roughly 131,636 changed entries to about 2, entry 0
from zeroed to untouched, and unexplained file changes from a destroyed 1 GiB
fixture to none. `fsck.fat` went from free clusters inside chains, a truncated
file and 720 MB reclaimed, to a single complaint.

That remaining complaint is the mirror defect, left open on purpose: fixing it
while the free path could still zero entries would have written the damage into
the only surviving good copy. With V8 now confirmed here, it is safe to fix, and
after it a FAT32 volume the app has written should be `fsck.fat`-clean outright.

D4-C1 is reinstated.

---

### OnePlus 7 (GM1901) · Android 16 (SDK 36) · build 0.3.13 (46) commit dc29660 — VeraCrypt + exFAT

APK sha256 `cd657759…`, clean tree, installed immediately before the run,
awake and unthrottled at both ends.

| Field | Value |
|---|---|
| Drive | PNY 59151 MiB — VeraCrypt (AES/SHA-512, PIM 1) + exFAT |
| Sections | free, block, dir, path, seq, random, opens, write, unaligned, correct, fixtures |
| seq read | 10.77 / 10.97 / 10.85 MB/s |
| write verify | **ALL PASSED** (cached, cache-dropped, remounted) |
| unaligned | **A + B PASS** |
| correctness | **ALL PASSED** (A–G) |
| fixtures | **ALL 16 MATCHED the host-computed hashes** |
| fsck / compare | pending |

Second device to exercise the remount race fix. The log shows the same shape as
on the Huawei — the unmount completes in 12 ms and the app re-mounts the drive
412 ms later — and the latched check caught the empty window, so correctness ran
and passed.

---

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
| fsck | **CLEAN** (host check 2026-09-29, after the drive's later runs): `e2fsck` clean; compare against the baseline shows only this run's report, the 2026-09-28 21:14 Pixel / 21:28 OnePlus pair, and `INDEX.txt`; free blocks and inodes down by exactly 2. |

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
