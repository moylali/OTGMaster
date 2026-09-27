#!/bin/bash
# Runs one benchmark/correctness case on a device over adb and captures its output.
#
#   scripts/run_benchmark.sh <serial> <label> <tests> <cache> <readahead|""> <out-file>
#   scripts/run_benchmark.sh 192.168.1.9:5555 "PIXEL FAT32" "unaligned,correct" default "" /tmp/r.txt
#
# See docs/RUNNING_BENCHMARKS.md for the section names, how results are judged, and
# the device-handling traps. The non-obvious parts of this script, each of which cost
# a wasted run to learn:
#
#   - MainActivity is started first: it owns the mount handler the benchmark calls
#     through, and a reinstall kills it.
#   - It never force-stops the app. That revokes the USB permission grant, and the
#     next mount fails with "no probed candidates" until someone taps Allow.
#   - The screen is kept awake and re-asserted every 30 s, because with the screen off
#     the CPU throttles and a dozed run reads as a regression.
#   - Completion is detected on "results written to", not "benchmark finished": the
#     early-return path when no drive is mounted skips the latter.
#   - The crash buffer is cleared up front and polled every 3 s, so a crash aborts
#     immediately with its trace instead of after the poll loop expires.
set -uo pipefail
S="$1"; LABEL="$2"; TESTS="$3"; CACHE="$4"; RA="${5:-}"
OUT="$6"

adb -s "$S" shell dumpsys deviceidle disable >/dev/null 2>&1
# Keep the screen on for the whole run. "deviceidle disable" stops Doze but does
# NOT stop clock throttling with the screen off, and decryption is CPU-bound: a
# screen-off run measures ~3x slow and reads as a regression. Verify with
# "dumpsys power | grep mWakefulness" -- it must say Awake, not Dozing.
adb -s "$S" shell settings put system screen_off_timeout 86400000 >/dev/null 2>&1
adb -s "$S" shell input keyevent KEYCODE_WAKEUP >/dev/null 2>&1

# MainActivity must be in the foreground: it owns the mount handler the benchmark
# calls through, and a reinstall kills it. Without this the run ends immediately
# with "no mount handler installed".
adb -s "$S" shell am start -n app.fayaz.otgmaster/.MainActivity >/dev/null 2>&1
for _ in $(seq 1 20); do
    sleep 1
    adb -s "$S" shell dumpsys activity activities 2>/dev/null \
        | grep -q "app.fayaz.otgmaster/.MainActivity.*Resumed\|ResumedActivity.*otgmaster" && break
done
sleep 2
adb -s "$S" logcat -c >/dev/null 2>&1
adb -s "$S" logcat -b crash -c >/dev/null 2>&1

EXTRAS=(--es password password123 --es pim 1 --es cipher AES --es hash SHA-512
        --es tests "$TESTS" --es cache "$CACHE" --es remount true)
[ -n "$RA" ] && EXTRAS+=(--es readahead "$RA")

adb -s "$S" shell am broadcast -a app.fayaz.otgmaster.RUN_BENCHMARK \
    -n app.fayaz.otgmaster/.bench.BenchmarkReceiver "${EXTRAS[@]}" >/dev/null 2>&1

{ echo "################ $LABEL ################"; } >> "$OUT"

# 5 consecutive pidof failures before declaring death: a single failure on a
# loaded device is a false positive that cost a 15-minute run once already.
misses=0; done_seen=0; dozed=0; tick=0
for _ in $(seq 1 600); do
    sleep 3
    # Re-assert wakefulness every 30s and record any lapse. A screen-off stretch
    # throttles the CPU ~2.4x (block-layer control: 7.25 vs 17.26 MB/s), which
    # reads as a cache regression, so a run that dozed is not comparable.
    tick=$((tick+1))
    if [ $((tick % 6)) -eq 0 ]; then
        adb -s "$S" shell input keyevent KEYCODE_WAKEUP >/dev/null 2>&1
        adb -s "$S" shell 'dumpsys power | grep -m1 mWakefulness=' 2>/dev/null \
            | grep -q Awake || dozed=1
    fi
    adb -s "$S" logcat -d -s OTGBench:I 2>/dev/null | sed -n 's/^.*OTGBench *: //p' > /tmp/bl.$$.txt
    if grep -q "results written to" /tmp/bl.$$.txt; then done_seen=1; break; fi
    # Crashes used to be found only after the loop gave up, minutes later. Check the
    # crash buffer every poll and stop immediately with the trace.
    CRASH=$(adb -s "$S" logcat -b crash -d 2>/dev/null | grep -m1 -A3 "FATAL EXCEPTION")
    if [ -n "$CRASH" ]; then
        { echo "*** CRASHED ***"; echo "$CRASH"; } >> "$OUT"
        rm -f /tmp/bl.$$.txt; exit 2
    fi
    if adb -s "$S" shell pidof app.fayaz.otgmaster 2>/dev/null | grep -q '[0-9]'; then
        misses=0
    else
        misses=$((misses+1))
        if [ "$misses" -ge 5 ]; then break; fi
    fi
done
adb -s "$S" logcat -d -s OTGBench:I 2>/dev/null | sed -n 's/^.*OTGBench *: //p' >> "$OUT"
[ "$dozed" -eq 1 ] && echo "*** CONTAMINATED: device dozed during this run, numbers not comparable ***" >> "$OUT"
if [ "$done_seen" -ne 1 ]; then
    if [ "$misses" -ge 5 ]; then echo "APP DIED" >> "$OUT"; else echo "TIMED OUT" >> "$OUT"; fi
    rm -f /tmp/bl.$$.txt; exit 1
fi
rm -f /tmp/bl.$$.txt; exit 0
