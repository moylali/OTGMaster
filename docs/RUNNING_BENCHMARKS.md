# Running the benchmarks, and judging the results

How the I/O benchmark and correctness suites are actually driven, and what makes a
result trustworthy. Most of the pitfalls below were discovered by producing a wrong
answer first.

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
`random`, `opens`, `write`, `unaligned`, `correct`, `saf`, `fixtures`. Omit
`tests` for everything.

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

## What makes a result trustworthy

These are the checks that turn output into evidence. Each exists because its absence
produced a false result.

**Confirm the build.** Every report carries `build: <version> commit <sha>`, and
marks a dirty tree. An A/B was once invalidated by an un-rebuilt APK — the two files
were byte-identical. **Compare APK hashes before trusting a pre/post comparison.**

**Check the power state at both ends.** Reports record power and CPU MHz at the start
*and* the finish. With the screen off the CPU throttles and decryption is CPU-bound:
the block-layer control measured 7.27 MB/s asleep against 17.26 awake. A dozed run
looks exactly like a regression. The runner re-asserts wakefulness every 30 s and
flags any run that dozed as `*** CONTAMINATED ***`.

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
