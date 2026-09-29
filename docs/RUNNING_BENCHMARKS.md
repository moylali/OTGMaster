# Running the benchmarks, and judging the results

How the I/O benchmark and correctness suites are actually driven, and what makes a
result trustworthy. Most of the pitfalls below were discovered by producing a wrong
answer first.

## Standard procedure — one run, start to finish

Every step below exists because skipping it produced a wrong or unusable result
at least once. Do them in order. `$A` is the device's adb address, `$P` the
partition on the host.

```sh
ADB=/home/fayaz/android-sdk-local/platform-tools/adb
A=192.168.1.9:33057        # from `adb devices -l`
```

### 1. Before the run — drive on the host

Check the volume is healthy, then baseline it if it has no baseline yet. A
baseline taken over damage records the damage as normal, so the check comes
first.

```sh
sudo bash scripts/verify_volume.sh $P                   # must print >>> CLEAN
sudo bash scripts/volume_baseline.sh snapshot $P        # once per drive
```

`snapshot` refuses if a baseline already exists; that is intended.

### 2. Build and install the current commit

Commit first, so the report's label names a real tree. Then install on the
device **every time**, whether or not it looks current.

```sh
git status --porcelain                                  # must be empty
./gradlew :app:assembleDebug -q
$ADB -s $A install -r app/build/outputs/apk/debug/app-debug.apk
```

A signature mismatch (`INSTALL_FAILED_UPDATE_INCOMPATIBLE`) means a debug build
from another keystore is installed. `adb uninstall app.fayaz.otgmaster` first;
only cached test credentials are lost.

### 3. Prepare the device

Plug the drive into the phone, then:

```sh
$ADB -s $A shell input keyevent KEYCODE_WAKEUP
$ADB -s $A shell am start -n app.fayaz.otgmaster/.MainActivity
$ADB -s $A shell settings put system screen_off_timeout 86400000
$ADB -s $A shell dumpsys deviceidle disable
$ADB -s $A shell dumpsys battery | grep -E 'level|powered'
$ADB -s $A logcat -d | grep 'VeraCryptUnlocker.*Candidate'
```

Check three things before going on:

- **The candidate line names the right container** (`VERACRYPT`, `LUKS1`,
  `LUKS2`). If there is none, a USB permission dialog or a BiometricPrompt is
  probably up — dismiss it on the phone.
- **Battery is charging, or above ~40%.** Two drives dropped off the bus today
  on phones at 21% and 26% running on battery; a powered hub fixes it.
- **The screen is on.** A run that starts with the screen off is flagged
  `THROTTLED` and its throughput is unusable, even if it recovers.

### 4. Start the run

```sh
$ADB -s $A logcat -c
$ADB -s $A shell am broadcast -a app.fayaz.otgmaster.RUN_BENCHMARK \
    -n app.fayaz.otgmaster/.bench.BenchmarkReceiver \
    --es password password123 --es pim 1 --es cipher AES --es hash SHA-512 \
    --es tests "free,block,dir,path,seq,random,opens,write,unaligned,correct,fixtures" \
    --es cache default --es remount true
```

**Name every section.** Omitting `--es tests` silently skips all five write
sections. For LUKS drives `pim`, `cipher` and `hash` are ignored and can be
dropped.

Within 30 seconds, read the header and confirm:

```sh
$ADB -s $A logcat -d | grep OTGBench | head -20
```

- `build: … commit <sha>` is the commit you just built — not an older one, not
  `-dirty`;
- `power : interactive=true … powerSave=false`;
- `mounted:` lists the drive and `volumeLabel` is the one you expect.

If any of those is wrong, stop and fix it now rather than after the run.

### 5. Monitor

The runner prints `still running: <section> (Ns in this step)` every 30 s.

```sh
$ADB -s $A logcat -d | grep OTGBench | tail -8
```

Expected durations: the whole run takes 5–10 minutes on the Pixel and OnePlus,
20–40 on the Samsung and Huawei, most of it in `fixtures` hashing the large
files.

What a problem looks like:

- **One section's counter climbing with no output for several minutes** — the
  drive has stopped responding. Check `dumpsys usb | grep product_name`; if the
  drive is gone, reattach it. The run is droppable.
- **`EIO` / `-5` in read or write errors** — a real device error. Let the run
  finish rather than pulling the drive mid-write, then `fsck` it on the host
  before anything else touches it.
- **`*** no mount handler installed ***`** — MainActivity is not alive. Press
  back, relaunch it, and broadcast again.

The run is done when `=== benchmark finished ===` appears.

### 6. Collect the results

```sh
$ADB -s $A exec-out cat \
  /storage/emulated/0/Android/data/app.fayaz.otgmaster/files/benchmark.txt
```

Read the verdict lines: `write verify`, `unaligned`, `correctness`, `fixtures`,
and the `power` line at both ends. `PARTIAL`, `NOT VERIFIED` and `FAILED` are
results and are recorded as themselves.

### 7. Verify on the host

Move the drive back to the laptop.

```sh
sudo bash scripts/verify_volume.sh $P
sudo bash scripts/volume_baseline.sh compare $P
```

A clean result: `>>> CLEAN` from the checker, and in the comparison only the
runner's own report and `INDEX.txt` changed, with the allocation table moving by
a handful of entries. Anything under **"MODIFIED AND NOT EXPLAINED BY THE RUN"**,
or allocation churn far out of proportion to the ~20 MB a run writes, is a
finding — stop and investigate before re-running on that drive.

### 8. Record it

Add a row to `BENCHMARK_RUNS.md` in the same commit as the work it validates:
device, Android version, container, filesystem, commit, sections run, every
verdict, the `fsck` result, and the comparison result. Update the cell in
`docs/test-reports/COVERAGE.md`. A completed run is recorded whatever it says; a
run that produced no result can be dropped.

## Two ways to trigger a run

### Over adb (preferred)

```sh
adb shell am start -n app.fayaz.otgmaster/.MainActivity
adb shell am broadcast -a app.fayaz.otgmaster.RUN_BENCHMARK \
    -n app.fayaz.otgmaster/.bench.BenchmarkReceiver \
    --es password password123 --es pim 1 --es cipher AES --es hash SHA-512 \
    --es tests "unaligned,correct" --es cache default --es remount true
```

- **The component must be named** (`-n`). Manifest receivers have not received
  implicit broadcasts since Android 8; an action-only broadcast reports
  `result=0` and is silently dropped.
- **Start MainActivity first.** It installs the mount handler the benchmark calls
  through; without it a run ends with `no mount handler installed`.
- **Never `am force-stop` first.** That revokes the USB permission grant, and the
  next mount fails with `no probed candidates` until someone taps Allow on the
  device. This cost several dead-ended runs.

Sections available in `--es tests`: `free`, `block`, `dir`, `path`, `seq`,
`random`, `opens`, `write`, `unaligned`, `correct`, `saf`, `fixtures`.

**Omitting `tests` does not run everything.** It runs the read-only sections only —
`free`, `block`, `dir`, `path`, `seq`, `random`, `opens`. The five that write to the
drive or take minutes (`write`, `unaligned`, `correct`, `saf`, `fixtures`) are opt-in
and are skipped **silently**, with no line in the report saying so, so a default run
looks complete while proving nothing about the write path. Name them explicitly:

```sh
--es tests "free,block,dir,path,seq,random,opens,write,unaligned,correct,fixtures"
```

**`write`, `unaligned` and `correct` need credentials even on an unencrypted volume.**
Their verdicts are judged after a remount, and the only remount path the runner has is
`mountRequest`, which takes a password. Without `--es password` they report `PARTIAL`
or `NOT VERIFIED` rather than a pass — correct, but it means a plain partition's write
path cannot be fully judged from the device. Close that gap from the host instead, with
`e2fsck -fn` on the partition after the run.

`--es drive` narrows a multi-drive run: `0`, `VCFAT`, or `0,1`. Every mounted drive
runs in sequence by default.

### On the device, with no adb at all

For phones where adb over wifi is unavailable — Android 9 and earlier have no
Wireless Debugging, and legacy `adb tcpip` does not survive losing the USB
transport, which is the transport the drive needs.

1. Attach the drive, open **OTG Master**, unlock it there.
2. Open **OTG Bench** (its own launcher icon, debug builds only) and tap a section.

Order matters: the bench screen holds the foreground to keep the display on, which
leaves MainActivity unable to show a USB permission dialog. It detects an unmounted
drive and says so rather than sitting through a 90-second timeout.

**This flow cannot verify on-disk correctness.** Android destroys the backgrounded
MainActivity, which nulls the mount handlers, so no remount can be performed — and
the correctness cases are meaningless without one. They report `NOT VERIFIED` with
the reason. Writes and throughput are unaffected.

## Getting the results back

Every run writes three copies, and runs accumulate rather than overwrite:

| Destination | Notes |
|---|---|
| `BENCH/reports/otgbench-<model>-<stamp>.txt` on the drive | Travels with the drive; plus an appended `INDEX.txt`, one line per run |
| `Documents/` via MediaStore | MTP-visible. **API 29+ only** — below that a MediaStore insert needs `WRITE_EXTERNAL_STORAGE` |
| `Android/data/app.fayaz.otgmaster/files/` | `adb pull`, and MTP-readable before Android 11 |

`INDEX.txt` carries the model, Android version, commit, awake/DOZED and the
correctness and fixtures verdicts, so a set of runs can be read without opening each.

## Proving a run changed nothing it should not have

`scripts/volume_baseline.sh` records a volume's full state, so a later comparison
can show exactly what a run touched. It handles LUKS, VeraCrypt and plain
partitions, is read-only throughout, and works on ext4, exFAT and FAT32.

Take the baseline once, while the volume is believed good — freshly prepared, or
just checked:

```sh
sudo bash scripts/volume_baseline.sh snapshot /dev/sdX1
```

Compare after a run:

```sh
sudo bash scripts/volume_baseline.sh compare /dev/sdX1
```

Add `--metadata-only` for a routine check: it skips the file hashes and diffs
only the allocation tables, taking seconds instead of the ~10 minutes a full
compare needs on a 54 GB drive.

**What it adds over `fsck` and `fixtures`.** `fsck` validates structure, not
contents — no filesystem here carries data checksums, so a cluster holding the
wrong bytes inside a structurally valid chain is invisible to it. `fixtures`
validates contents, but only for the manifest's entries: 14 files of 20,096 on
the exFAT drive. The FAT32 corruption found on 2026-09-28 lived in exactly that
gap — in a `FILL/` file no manifest entry covered — so that drive reported
`fixtures: ALL 16 MATCHED` while its allocation table was wrecked.

**Reading the output.** Paths the benchmark owns — `BENCH/reports/`,
`BENCH_UNALIGNED`, its scratch files — are listed as expected and do not count as
findings. The signal is a **modified** file the run never wrote, and allocation
churn out of proportion to what was written. For scale, both measured with this
tool on the same day:

| | exFAT, healthy | FAT32, corrupted |
|---|---|---|
| FAT bytes changed | 7 of 60,817,408 | 526,547 of 60,453,376 |
| entries changed | ~1 | ~131,636 |
| boot region | identical | entry 0 zeroed |
| unexplained file changes | none | `FILL/fill_0023.bin` |

**Where baselines live.** `baselines/` in the workspace, gitignored. They are not
kept on the drive itself, deliberately: a volume that corrupts its own data can
corrupt its own baseline, which is precisely how the FAT32 drive kept a
valid-looking `MANIFEST.txt` while its allocation table was destroyed. `snapshot`
refuses to overwrite an existing baseline, so a later and possibly damaged state
cannot silently become the reference.

## What makes a result trustworthy

These are the checks that turn output into evidence. Each exists because its absence
produced a false result.

**Install the current build before every run, and confirm it from the report.**
Every report carries `build: <version> commit <sha>`. Read that line at the start
of each run rather than trusting that you installed it — a device on an older APK
produces a complete, plausible row with nothing in it to say so. Devices also
drift apart from each other: four on three different commits makes every
cross-device figure meaningless, and that state is invisible until you compare
the commit lines.

**Confirm the build.** Every report carries `build: <version> commit <sha>`, and
marks a dirty tree. An A/B was once invalidated by an un-rebuilt APK — the two files
were byte-identical. **Compare APK hashes before trusting a pre/post comparison.**

**Check the power state at both ends.** Reports record power and CPU MHz at the start
*and* the finish. With the screen off the CPU throttles and decryption is CPU-bound:
the block-layer control measured 7.27 MB/s asleep against 17.26 awake. A dozed run
looks exactly like a regression. The runner re-asserts wakefulness every 30 s and
flags any run that dozed as `*** CONTAMINATED ***`.

Both settings the runner changes (`screen_off_timeout` to 24 h and `deviceidle`
disabled) **persist after the run**. Restore them on any device you are no longer
measuring — README's "Keeping devices awake for benchmark runs" has the enable,
disable and check commands.

**Use the block layer as the control.** It reads the uncached device with no
filesystem involved, so it should be unchanged between two arms of the same A/B. If
it differs, the arms are not comparable and nothing else in the run means anything.

**Compare within one session.** Cross-session comparison has produced two retracted
claims in `IO_PERFORMANCE.md`. Run both arms back to back, or state plainly that the
figure is not sound.

**Compare within one device.** A third retraction came from generalising a ratio
measured on one phone. Same code and same drive does not make two devices equivalent.

**Check that a remount actually happened.** Correctness cases are judged after an
unmount/remount, which is what discards in-memory metadata. `remountAndProve`
requires the mount list to empty *and* the drive identity to change. Before it
existed, a null mount handler meant nothing was remounted and the suite still printed
`ALL PASSED` — an entire device's result was vacuous.

**Distrust a suite that passes when it should not be able to.** Every false pass
today came from the test's shape, not its logic: aligned-only writes hid two
corruption bugs, 4–8 KiB fixtures hid a 2 GiB overflow, single-threaded tests hid a
lock shipped wrong. When a fix lands, confirm the new test **fails on the old code**.

## Operating the devices

**Wireless debugging ports rotate**, and stale mDNS records linger beside the live
one. Try every advertised port rather than the newest:

```sh
for A in $(adb mdns services | grep -oE "192\.168\.[0-9.]+:[0-9]+" | sort -u); do
    adb connect "$A"
done
```

**A drive that stops responding usually needs reattaching.** Two failures in one
session — one inside `ScsiBlockDevice.init`, one at USB enumeration — both cleared on
a replug with no code change. That is the discriminating test for transport faults.

**Release builds contain no harness.** `BenchmarkReceiver`, `BenchLauncherActivity`
and the native I/O counters are debug-only, so a Play-signed install cannot run any of
this — and a debug APK cannot replace it without uninstalling first.

**Watch for crashes as the run goes.** The runner clears the crash buffer, polls every
three seconds, and aborts immediately with the trace. It previously only noticed a
dead app after the poll loop expired, wasting the rest of a long run.
