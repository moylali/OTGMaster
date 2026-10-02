#!/usr/bin/env bash
# Runs the E2E suite across several emulators at once and merges the results.
#
#   scripts/run_e2e_parallel.sh                 # 3 emulators, headless, every case
#   scripts/run_e2e_parallel.sh -j 2 --only fat32 --only m_vc_ntfs
#
# Each shard is scripts/run_e2e_tests.sh on its own emulator (ports 5554, 5556,
# 5558, ...), booted -read-only so the shards can share one AVD, with its own QEMU
# USB slot file, pushing only its own cases' fixtures. Fixtures and APKs are
# prepared once, up front. Shards run with --keep-going, so one run shows every
# failure rather than stopping at the first; FLAKY (failed, passed on retry) is
# carried through from the shards unchanged.
#
# Cases are spread by image size, largest first, onto the shard with the least
# data so far, which keeps the shards' push and dd time even.
set -uo pipefail
cd "$(dirname "$0")/.."

JOBS=3
WINDOW="--headless"
ONLY=()
while [[ $# -gt 0 ]]; do
    case $1 in
        -j) JOBS=$2; shift ;;
        --show) WINDOW="--show" ;;
        --headless) WINDOW="--headless" ;;
        --only) ONLY+=("$2"); shift ;;
        *) echo "Unknown option: $1"; exit 1 ;;
    esac
    shift
done

echo "Preparing fixtures and APKs once..."
bash scripts/run_e2e_tests.sh --prepare-only || { echo "Preparation failed"; exit 1; }

# The cases, as run_e2e_tests.sh would pick them: a directory with a test.img.
cases=()
for d in testdata/*/; do
    name=$(basename "$d")
    [ -f "$d/test.img" ] || continue
    if [ ${#ONLY[@]} -gt 0 ]; then
        printf '%s\n' "${ONLY[@]}" | grep -qx "$name" || continue
    fi
    cases+=("$name")
done
[ ${#cases[@]} -gt 0 ] || { echo "No cases selected"; exit 1; }
[ "$JOBS" -gt "${#cases[@]}" ] && JOBS=${#cases[@]}

# Largest image first onto the shard with the least data so far.
declare -a load shard_cases
for ((i = 0; i < JOBS; i++)); do load[$i]=0; shard_cases[$i]=""; done
while read -r size name; do
    best=0
    for ((i = 1; i < JOBS; i++)); do (( load[i] < load[best] )) && best=$i; done
    load[$best]=$(( load[best] + size ))
    shard_cases[$best]+=" $name"
done < <(for c in "${cases[@]}"; do echo "$(stat -c %s "testdata/$c/test.img") $c"; done | sort -rn)

stamp=$(date +%Y%m%d_%H%M%S)
outdir="e2e_parallel_$stamp"
mkdir -p "$outdir"
pids=()
for ((i = 0; i < JOBS; i++)); do
    port=$((5554 + 2 * i))
    args=()
    for c in ${shard_cases[$i]}; do args+=(--only "$c"); done
    echo "Shard $i (emulator-$port): ${shard_cases[$i]}"
    bash scripts/run_e2e_tests.sh $WINDOW --no-build --keep-going --read-only-avd \
        --port "$port" --slot "/tmp/otg_usb_slot_$port.img" \
        --report "$outdir/shard_$i.md" "${args[@]}" > "$outdir/shard_$i.log" 2>&1 &
    pids+=($!)
    sleep 20   # stagger the boots; three cold boots at once crowd adb and the CPU
done

echo "Waiting for ${#pids[@]} shards (logs in $outdir/)..."
for p in "${pids[@]}"; do wait "$p"; done

# Merge: every result row from the shard reports, renumbered, with totals.
APP_VERSION=$(grep 'versionName' app/build.gradle.kts | head -1 | grep -o '"[^"]*"' | tr -d '"')
GIT_COMMIT=$(git rev-parse --short HEAD)
report="e2e_report_v${APP_VERSION}_${GIT_COMMIT}_${stamp}_parallel.md"
rows=$(cat "$outdir"/shard_*.md 2>/dev/null | grep -E '^\| [0-9]+ \| `' | sort -t'`' -k2,2)
passed=$(grep -c '✅ PASSED' <<< "$rows")
flaky=$(grep -c '⚠️ FLAKY' <<< "$rows")
failed=$(grep -c '❌ FAILED' <<< "$rows")
ran=$(grep -c '' <<< "$rows")
missing=$(( ${#cases[@]} - ran ))
{
    echo "# OTGMaster E2E Test Report (parallel, $JOBS emulators)"
    echo
    echo "| Field | Value |"
    echo "|-------|-------|"
    echo "| Version | $APP_VERSION |"
    echo "| Commit | \`$GIT_COMMIT\` |"
    echo "| Date | $(date '+%Y-%m-%d %H:%M:%S') |"
    echo "| Shard logs | \`$outdir/\` |"
    echo
    echo "## Results"
    echo
    echo "| # | Test Case | Description | Result | Duration |"
    echo "|---|-----------|-------------|--------|----------|"
    n=0
    while IFS= read -r r; do
        [ -n "$r" ] || continue
        n=$((n + 1))
        echo "$r" | sed -E "s/^\| [0-9]+ \|/| $n |/"
    done <<< "$rows"
    echo
    echo "## Summary"
    echo
    echo "| Metric | Value |"
    echo "|--------|-------|"
    echo "| Selected | ${#cases[@]} |"
    echo "| Ran | $ran |"
    echo "| Passed | $((passed + flaky)) |"
    echo "| Flaky (passed on retry, counted in Passed) | $flaky |"
    echo "| Failed | $failed |"
    echo "| Not run (a shard died) | $missing |"
    if [ "$failed" -eq 0 ] && [ "$missing" -eq 0 ]; then
        echo "| Overall | ✅ ALL PASSED |"
    else
        echo "| Overall | ❌ FAILED |"
    fi
} > "$report"
echo "Report saved to: $report"
echo "Passed $((passed + flaky)) (flaky $flaky), failed $failed, not run $missing, of ${#cases[@]}"
[ "$failed" -eq 0 ] && [ "$missing" -eq 0 ]
