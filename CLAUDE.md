# OTG Master — working agreements

## Commit rules

These apply to every commit, without being asked.

1. **Update the Fastlane changelog with each commit.** The file is
   `fastlane/metadata/android/en-US/changelogs/<versionCode>.txt`, where
   `versionCode` is the current value in `app/build.gradle.kts`. It accumulates
   **all user-facing changes since the last tagged commit**, not just the change
   being committed — so each commit rewrites the whole file rather than appending
   one line. Check `git log <last-tag>..HEAD` to rebuild it.

   **The version must be bumped immediately after a tag**, so that value always
   names an *unreleased* build. This rule was followed literally while the version
   still said 44 after `v0.3.11` had shipped at 44, and a changelog describing
   unreleased work was written into the released version's file — where F-Droid and
   Play would have shown it against a build that did not contain any of it. Before
   editing a changelog, confirm `git describe --tags --abbrev=0` does **not** match
   the current `versionName`.

   Keep the established voice: `•` bullets, `New:` / `Fixed:` prefixes, phrased
   for an end user. Internal work (CI, refactors, benchmarks, docs) does not
   appear there.

   **Two files, two audiences.** F-Droid reads the Fastlane changelog above and has
   no length limit. Google Play caps release notes at **500 characters per
   language**, so it gets a separate condensed file at
   `distribution/whatsnew/whatsnew-en-US`, wired into `release.yml` via
   `whatsNewDirectory`. Update both, and check the Play one's length —
   `wc -c distribution/whatsnew/whatsnew-en-US` must be ≤ 500, or the upload is
   rejected.

2. **Every commit message carries a summary of changes.** A subject line alone is
   not enough. State what changed, and why — including the reasoning or evidence
   that justified it, so the commit stands on its own later.

3. **A tagging commit must attach a regression test report covering at least four
   devices.** No tag ships without it. Reports live in `docs/test-reports/` as
   `<tag>.md`, and each must name the device, Android version, filesystem, and the
   measurements or checks that were run.

## Benchmarking and verification

**To run a benchmark, follow "Standard procedure" at the top of
`docs/RUNNING_BENCHMARKS.md`** — host check and baseline, build and install,
device preparation, start, monitor, collect, host verification, record. Each
step is there because skipping it produced a wrong or unusable result.

Before changing anything in the I/O path — the block cache, the crypto layer, libaums,
libexfat, or the DocumentsProvider — read these:

- `docs/RUNNING_BENCHMARKS.md` — how to trigger runs by both paths, how reports are
  retrieved, and what makes a result trustworthy.
- `docs/BENCHMARK_RESULTS.md` — reference figures across four devices and both
  filesystems, what to validate, and the comparison rules.
- `docs/IO_PERFORMANCE.md` — the narrative, including three retracted claims and why
  each was wrong.

**A fix is not verified until a test that fails on the old code passes on the new one,
on hardware.** Build success and green unit tests are not sufficient for anything
touching the I/O path: several suites in this project have passed while real data
corruption sat in the code, every time because of the test's shape rather than its
logic.

**Any I/O path change must be validated on at least one physical device before the
commit is pushed to the remote.** "Validated" means a relevant E2E test or manual
exercise completes successfully on a real device with a real (or emulator-backed)
filesystem — not just a clean build and install. Do not push until this is done.

**Benchmark runs must cover at least four devices before a release is tagged.**
Commit rule 3 (the tagging regression report) is the gate; this reinforces it:
the four-device bar applies to benchmark figures, not just smoke checks.

**A content hash does not verify a filesystem write — run the filesystem's own
checker.** A write path must leave the volume structurally valid, and SHA-256 over
the bytes just written cannot see otherwise. The ext4 write support reported
`write verify: ALL PASSED` with matching hashes on all three passes (cached,
cache-dropped, remounted) while it was destroying the volume: the checksum seed was
read from the wrong superblock offset, so every superblock, inode, bitmap, group
descriptor and directory checksum it wrote was invalid. The drive ended up so
damaged that `blkid` could not identify it as ext4, and the only visible symptom
was one fixture directory reading back as a regular file.

So for any filesystem write change:

- keep a host-side test that drives the real write code against a loopback image
  and asserts the checker is clean — `Ext4WriteFsckTest` does this with
  `mkfs.ext4` + `e2fsck -fn`, and it reproduced in seconds what cost a drive;
- **after every device run, re-check that volume from the host** — not just after
  a run that looked wrong, and not just for ext4.
  `scripts/verify_volume.sh /dev/sdX1` opens whatever container is
  there (LUKS, VeraCrypt or plain), asks `blkid` what is inside, and runs that
  filesystem's own checker read-only. Hash-clean is not fsck-clean, and the
  device path goes through the crypto layer that host tests cannot cover.

  This is not optional diligence, it is the only check that can see the failure.
  **The app never verifies a checksum it reads** — `Ext4Crc` appears throughout
  the ext4 code and every use computes a value to *write*; there is no
  comparison anywhere. So a volume whose every superblock, inode, bitmap, group
  descriptor and directory checksum is invalid reads back perfectly through the
  app, passes `write verify` on all three passes, and passes `fixtures` against
  host-computed hashes. That is exactly the state the drive was in when it was
  destroyed. No amount of on-device checking can substitute, and the run log
  records for each run whether this was done;
- **baseline the drive before a run and compare after it.**
  `scripts/volume_baseline.sh snapshot /dev/sdX1` once, while the volume is
  believed good; `… compare /dev/sdX1` after each run. This is the only check
  that covers *every* file rather than a sample, and the only one that can diff
  an allocation table against a known-good copy.

  It exists because the two checks above both have blind spots that a real
  corruption walked straight through. `fsck` validates structure, not contents —
  no filesystem here carries data checksums, so a cluster holding the wrong bytes
  inside a valid chain is invisible to `e2fsck`, `fsck.exfat` and `fsck.vfat`
  alike. `fixtures` validates contents, but only for manifest entries: 14 files of
  20,096 on the exFAT drive. The FAT32 damage sat in that gap, in a `FILL/` file
  no entry named, which is why that drive reported `fixtures: ALL 16 MATCHED`
  while wrecked.

  What the comparison looks like when it is working, measured the same day on the
  same tool:

  | | exFAT, healthy | FAT32, corrupted |
  |---|---|---|
  | FAT bytes changed | 7 of 60,817,408 | 526,547 of 60,453,376 |
  | entries changed | ~1 | ~131,636 |
  | unexplained file changes | none | `FILL/fill_0023.bin` |

  Four orders of magnitude, from a check that takes minutes. Use
  `--metadata-only` for a quick pass after routine runs; it diffs the allocation
  tables in seconds and is the half that catches damage of that shape.

  Take the baseline when you have reason to believe the volume is good — freshly
  prepared, or just checked. A baseline taken over damage records the damage as
  normal, and the tool then passes forever. It refuses to overwrite an existing
  baseline for that reason.

- assert both: the content matches *and* the filesystem still validates.

Keep a second prepared drive untouched by the change. Drive C staying clean is what
proved the damage came from this code and not from the preparation script.

**Every benchmark run gets recorded in `docs/BENCHMARK_RUNS.md`, in the same commit
as the work it validates.** A run that exists only in logcat and on the drive did not
happen as far as the repo is concerned: logcat is cleared between runs, the on-drive
`BENCH/reports/` travels with the drive rather than the code, and neither can be
diffed against the commit it was meant to justify. One row per run, and the row names
the device, Android version, container, filesystem, the commit the APK was built from,
the sections that actually ran, and each verdict.

**Commit the code before building the APK you benchmark.** The report stamps itself
with `git describe` at build time, so an APK built from a dirty tree is labelled with
the commit it is *not* — it carries uncommitted work the label does not name. This
happened on the first day the log existed: a run was recorded against `6d33f88` while
the APK it measured contained the changes committed afterwards as `654ceda`, which
would have sent a later reader to the wrong diff to explain the numbers. A run whose
report ends in `-dirty` cannot be traced to a tree, so commit first, build, install,
then run. If a dirty run happens anyway, the row states what the APK actually
contained rather than repeating the label.

**Install the current build on the device before every run.** Not "if it looks
out of date" — before every run, and confirm it from the report's own `commit`
line rather than from memory of having installed it.

A device left on an older APK still produces a complete, plausible-looking row.
Nothing in the output says "this measured code you replaced an hour ago"; the
only tell is the commit, and by then the run has cost its full wall-clock. Two
separate hours were lost to this in one session: a fix was committed as
`d7c8fcd` but never rebuilt, so the APK installed to "verify" it was the build
from before it, and the run faithfully reproduced the bug it was meant to prove
fixed. Later, four devices sat on three different commits at once, which makes
their rows uncomparable with each other for any figure at all.

The sequence is: commit, build, install, check the reported commit, run. It costs
a minute; skipping it costs a run and, worse, produces a row that has to be
withdrawn later rather than one that obviously failed.

**A run that did not complete can be dropped; a run that completed must be recorded.**
If the drive stopped responding, the USB handle died, the app crashed or the run was
interrupted, there is no result and nothing to log — delete it and start again. The
exemption is for runs that produced no result, **not** for runs that produced an
unwelcome one. A completed run with bad numbers, a `FAILED` section, a `PARTIAL`
verdict or a `*** CONTAMINATED ***` flag is a result and goes in the log with that
flag intact. Silently dropping those is how a log stops being evidence and starts
being a highlight reel, and it is the failure mode this file is most exposed to,
because the person deciding what counts as "didn't complete" is the same one whose
change is being judged.

Three things the row must be honest about, because each has produced a false result
before:

- **Which sections ran.** Omitting `--es tests` runs the read-only sections only and
  skips the five write sections silently. A row that does not say what ran cannot be
  distinguished from a row where the write path was never exercised.
- **`PARTIAL` and `NOT VERIFIED` are recorded as themselves**, never rounded up to a
  pass and never left out. They mean the suite could not support a verdict — which is
  information, not a gap to tidy away.
- **Whether the host `e2fsck` was run afterwards, and its result.** Hash-clean is not
  fsck-clean.

`BENCHMARK_RESULTS.md` stays what it is: the curated reference figures and the rules
for comparing them. `BENCHMARK_RUNS.md` is the raw append-only log every one of those
figures can be traced back to.

## Test media and test data

`docs/TEST_DATA.md` is the **single reference for preparing every kind of test media**:
the E2E emulator volume images, the scripted VeraCrypt benchmark drive, and the three
LUKS drives (4-partition LUKS1/2 × FAT32/exFAT, plus LUKS1+ext4 and LUKS2+ext4). It
covers the per-case file contract, how to add a cipher, hash, filesystem or container
format, the fixture tree and what each fixture stresses, and the host-computed manifest.

Preparation commands belong in that file and nowhere else. They were previously
duplicated between it and `docs/LUKS_SUPPORT.md`, which is how a stale `cryptsetup`
invocation gets pasted; `LUKS_SUPPORT.md` now holds the design evaluation only.

## Constraints

- **Do not push to mainline** unless explicitly asked. Commit to the working
  branch.
- Unless a tag is created, nothing reaches F-Droid or Google Play, so landing
  fixes on `main` is safe; tagging is the gate.

## Destructive commands

Never hand the user a destructive command bundled with a read-only one, and never
in the same code block. `scripts/prepare_test_usb.sh` repartitions a disk; keep it
visually separated from `verify`/`clean`, and prefer running it yourself over
giving it to the user to paste.

## Vendored code

`docs/VENDOR_FIXES.md` is the registry of every vendored dependency. Nothing is
vendored without an entry there.

**Adding a library.** Before the vendoring commit lands, add a section recording:

- the upstream repository URL;
- the exact upstream commit pinned, full SHA, not a tag or a branch;
- the date pinned, and the upstream version if it has one;
- **why** it is vendored rather than consumed as a dependency — this is the part
  that gets lost, and without it a later maintainer cannot judge whether the reason
  still holds;
- the licence, and whether it is compatible with this project's GPL-2.0-or-later
  grant (the licence table in `README.md` is the authoritative list — add the new
  entry there, and reference it from the registry rather than restating it);
- an empty patch table, ready for the first local change.

The commit that brings the code in states the same pin in its message, so the
pairing is visible from `git log` alone without opening the doc.

**Patching a vendored library.** Record it in that library's patch table (what,
why, and the commit), and mark the code itself:

```kotlin
// LOCAL PATCH (docs/VENDOR_FIXES.md V3): one line on what upstream does wrong.
```

The in-file marker matters more than it looks: it is what stops a future upstream
merge from silently reverting a fix. A patch with no marker will be lost.

**Pulling upstream.** Follow the shape of `b03705d`: name the old and new commits,
summarise the source changes that actually affect this project, and state explicitly
for each local patch whether it still applies, was absorbed upstream, or had to be
re-applied. Update the pin in the registry in the same commit.
