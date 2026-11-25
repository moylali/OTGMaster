#!/usr/bin/env bash
# Container Master's E2E suite (architecture proposal, Milestone 3). Drives the real app on one
# device or emulator: the system picker, the unlock form, Lock and Lock all, and reads through
# tools/saf-probe, an independent SAF client with its own picker grants, so every read crosses
# Container Master's provider as another app's would. Results go to a Markdown report.
#
#   scripts/run_container_e2e.sh -s <adb serial> [--only <case>]...
#
# Fixtures: testdata/ext4 and testdata/ntfs, VeraCrypt AES/SHA-512 volumes made by desktop
# VeraCrypt and filled without root (scripts/generate_noroot_testdata.sh, docs/TEST_DATA.md).
# They are pushed to the device's Download folder as cm-ext4.hc and cm-ntfs.hc.
set -uo pipefail
cd "$(dirname "$0")/.."
S=""; ONLY=()
while [ $# -gt 0 ]; do case $1 in -s) S=$2; shift ;; --only) ONLY+=("$2"); shift ;; *) echo "Unknown option: $1"; exit 1 ;; esac; shift; done
[ -n "$S" ] || { echo "usage: $0 -s <adb serial> [--only <case>]"; exit 1; }
source scripts/container_e2e_lib.sh

COMMIT=$(git describe --always --dirty --abbrev=7 --match 'container-v[0-9]*')
TS=$(date +%Y%m%d_%H%M%S)
REPORT="e2e_container_report_${COMMIT}_${TS}.md"
FLOWER=$(sha256sum testdata/flower.jpg | cut -d' ' -f1)
SPACES=$(printf 'Hello World\n' | sha256sum | cut -d' ' -f1)
EMPTY=$(printf '' | sha256sum | cut -d' ' -f1)

for t in ext4 ntfs; do
  [ -f testdata/$t/test.img ] || { bash scripts/generate_noroot_testdata.sh || { echo "fixture generation failed"; exit 1; }; }
done
# A LUKS2 file, for the format the form follows; only its header is read, so 4 MiB will do.
if [ ! -f testdata/cm_luks/test.img ]; then
  mkdir -p testdata/cm_luks; truncate -s 4M testdata/cm_luks/test.img
  printf 'container-master-luks' | cryptsetup luksFormat --type luks2 -q --pbkdf pbkdf2 --pbkdf-force-iterations 1000 \
    --luks2-metadata-size 16k --luks2-keyslots-size 512k testdata/cm_luks/test.img - || { echo "LUKS fixture failed"; exit 1; }
fi
# The public ID Container Master must give each container: SHA-256 of "veracrypt-salt:" and the
# header's first 64 bytes, first 10 hex (ContainerSessions.idForSalt).
cid() { python3 -c 'import hashlib,sys; d=open(sys.argv[1],"rb").read(64); print(hashlib.sha256(b"veracrypt-salt:"+d).hexdigest()[:10])' "$1"; }
ID_EXT4=$(cid testdata/ext4/test.img); ID_NTFS=$(cid testdata/ntfs/test.img)

echo "Building Container Master and saf-probe..."
./gradlew -q :container-app:assembleDebug || exit 1
[ -f tools/saf-probe/local.properties ] || cp local.properties tools/saf-probe/ 2>/dev/null || true
./gradlew -q -p tools/saf-probe assembleDebug || exit 1
APK_COMMIT=$(grep -oE 'GIT_COMMIT = "[^"]*"' container-app/build/generated/source/buildConfig/debug/app/fayaz/containermaster/BuildConfig.java | cut -d'"' -f2)
adb -s "$S" install -r container-app/build/outputs/apk/debug/container-app-debug.apk >/dev/null || exit 1
adb -s "$S" install -r tools/saf-probe/build/outputs/apk/debug/saf-probe-debug.apk >/dev/null || exit 1
for t in ext4 ntfs luks; do
  [ $t = luks ] && src=testdata/cm_luks/test.img || src=testdata/$t/test.img
  want=$(sha256sum $src | cut -d' ' -f1)
  have=$(adb -s "$S" shell sha256sum /sdcard/Download/cm-$t.hc 2>/dev/null | cut -d' ' -f1)
  [ "$want" = "$have" ] || adb -s "$S" push $src /sdcard/Download/cm-$t.hc >/dev/null
done
adb -s "$S" shell input keyevent KEYCODE_WAKEUP
DEVICE="$(adb -s "$S" shell getprop ro.product.model | tr -d '\r') / Android $(adb -s "$S" shell getprop ro.build.version.release | tr -d '\r')"

RESULTS=(); PASS=0; FAIL=0
fail() { echo "  FAIL: $*"; WHY="$*"; return 1; }
run_case() {
  local name=$1 desc=$2; shift 2
  if [ ${#ONLY[@]} -gt 0 ] && [[ ! " ${ONLY[*]} " =~ " $name " ]]; then return; fi
  echo "== $name"; WHY=""; local t0=$SECONDS
  lock_all >/dev/null
  if "$@"; then RESULTS+=("| \`$name\` | $desc | ✅ PASSED | $((SECONDS - t0))s |"); PASS=$((PASS + 1))
  else RESULTS+=("| \`$name\` | $desc | ❌ FAILED: $WHY | $((SECONDS - t0))s |"); FAIL=$((FAIL + 1)); fi
  lock_all >/dev/null
}

open_and_grant() { # open_and_grant <file> <password> <pim> <expected id>; sets TREE
  cm_front; pick "$1" || { fail "could not pick $1"; return 1; }
  local out; out=$(unlock "$2" "$3"); [ "$out" = "opened $4" ] || { fail "unlock: got '$out', expected 'opened $4'"; return 1; }
  TREE=$(grant "$4"); [ -n "$TREE" ] || { fail "no grant on $4"; return 1; }
}

case_unlock_read() {
  open_and_grant cm-ext4.hc password123 1 "$ID_EXT4" || return 1
  local r
  r=$(read_sha "$TREE" flower.jpg); [ "$r" = "\"sha256\": \"$FLOWER\"" ] || { fail "flower.jpg: $r"; return 1; }
  r=$(read_sha "$TREE" "file with spaces.txt"); [ "$r" = "\"sha256\": \"$SPACES\"" ] || { fail "file with spaces.txt: $r"; return 1; }
  r=$(read_sha "$TREE" nested/very/deep/folder/empty_file.txt); [ "$r" = "\"sha256\": \"$EMPTY\"" ] || { fail "empty_file.txt: $r"; return 1; }
  probe read --es tree "'$TREE'" --es path flower.jpg | grep -q '"flags": "0"' || { fail "flower.jpg offered write, delete or rename"; return 1; }
}

case_ntfs() {
  open_and_grant cm-ntfs.hc password123 1 "$ID_NTFS" || return 1
  local r; r=$(read_sha "$TREE" flower.jpg); [ "$r" = "\"sha256\": \"$FLOWER\"" ] || { fail "flower.jpg: $r"; return 1; }
}

case_wrong_password() {
  cm_front; pick cm-ext4.hc || { fail "could not pick"; return 1; }
  local out; out=$(unlock not-the-password 1)
  [[ "$out" == message:* ]] || { fail "expected a refusal, got '$out'"; return 1; }
  [ -z "$(open_ids)" ] || { fail "something is open after a wrong password"; return 1; }
}

case_relock_grant() {
  open_and_grant cm-ext4.hc password123 1 "$ID_EXT4" || return 1
  local held=$TREE r
  lock_all
  r=$(read_sha "$held" flower.jpg); [[ "$r" == '"error"'* ]] || { fail "read after lock: $r"; return 1; }
  cm_front; pick cm-ext4.hc || { fail "could not pick again"; return 1; }
  local out; out=$(unlock password123 1); [ "$out" = "opened $ID_EXT4" ] || { fail "second unlock: $out"; return 1; }
  r=$(read_sha "$held" flower.jpg); [ "$r" = "\"sha256\": \"$FLOWER\"" ] || { fail "the held grant after a fresh unlock: $r"; return 1; }
}

case_already_open() {
  cm_front; pick cm-ext4.hc || { fail "could not pick"; return 1; }
  [ "$(unlock password123 1)" = "opened $ID_EXT4" ] || { fail "first unlock"; return 1; }
  cm_front; pick cm-ext4.hc || { fail "could not pick again"; return 1; }
  local out; out=$(unlock password123 1); [[ "$out" == message:* ]] || { fail "second open of the same file: '$out'"; return 1; }
  [ "$(open_ids | wc -l)" = 1 ] || { fail "not exactly one open"; return 1; }
}

case_lock_all_service() {
  local before; before=$(adb -s "$S" shell sha256sum /sdcard/Download/cm-ext4.hc | cut -d' ' -f1)
  cm_front; pick cm-ext4.hc || { fail "could not pick"; return 1; }
  [ "$(unlock password123 1)" = "opened $ID_EXT4" ] || { fail "unlock"; return 1; }
  [ "$(adb -s "$S" shell "dumpsys activity services $CM | grep -c ServiceRecord" | tr -d '\r')" -ge 1 ] || { fail "no foreground service while open"; return 1; }
  lock_all; sleep 2
  [ -z "$(open_ids)" ] || { fail "still open after Lock all"; return 1; }
  [ "$(adb -s "$S" shell "dumpsys activity services $CM | grep -c ServiceRecord" | tr -d '\r')" = 0 ] || { fail "the service kept running"; return 1; }
  [ "$(adb -s "$S" shell sha256sum /sdcard/Download/cm-ext4.hc | cut -d' ' -f1)" = "$before" ] || { fail "the container changed"; return 1; }
}

case_format_luks() {
  cm_front; pick cm-luks.hc || { fail "could not pick"; return 1; }
  local X; X=$(dump)
  echo "$X" | grep -q 'LUKS volume' || { fail "the format line does not name LUKS"; return 1; }
  echo "$X" | grep -q 'content-desc="password"' && { fail "the VeraCrypt form is shown for a LUKS file"; return 1; }
  echo "$X" | grep -q 'content-desc="unlock"' && { fail "Unlock is offered for a LUKS file"; return 1; }
  return 0
}

case_logs_report() {
  cm_front; pick cm-ext4.hc || { fail "could not pick"; return 1; }
  [ "$(unlock password123 1)" = "opened $ID_EXT4" ] || { fail "unlock"; return 1; }
  cm_front; local X; X=$(dump)
  echo "$X" | grep -q 'Unlocked and mounted' || { fail "the log does not show the unlock"; return 1; }
  echo "$X" | grep -q 'offered as VeraCrypt' || { fail "the log does not show the detected format"; return 1; }
  tap 'content-desc="report_issue"' || { fail "no Report issue"; return 1; }; sleep 3
  X=$(dump)
  echo "$X" | grep -q 'Report an issue' || { fail "Report issue did not open"; return 1; }
  echo "$X" | grep -q 'USB drive make' && { fail "Report issue offers a USB row in Container Master"; return 1; }
  echo "$X" | grep -q 'Container Master 0' || { fail "the report does not name Container Master's version"; return 1; }
  adb -s "$S" shell input keyevent KEYCODE_BACK; sleep 1
  return 0
}

set_idle() { # set_idle <minutes>: Settings, Lock after no use for
  cm_front; tap 'content-desc="settings"'; sleep 1; tap "content-desc=\"idle_$1\"" || { tap 'content-desc="close_settings"'; return 1; }
  sleep 1; tap 'content-desc="close_settings"'; sleep 1; }

case_idle_lock() {
  set_idle 1 || { fail "no 1-minute idle choice"; return 1; }
  open_and_grant cm-ext4.hc password123 1 "$ID_EXT4" || { set_idle 15; return 1; }
  local r; r=$(read_sha "$TREE" flower.jpg)
  [ "$r" = "\"sha256\": \"$FLOWER\"" ] || { fail "read before the timeout: $r"; set_idle 15; return 1; }
  sleep 75
  r=$(read_sha "$TREE" flower.jpg)
  [[ "$r" == '"error"'* ]] || { fail "a read after 75 s idle with a 1-minute timeout: $r"; set_idle 15; return 1; }
  [ -z "$(open_ids)" ] || { fail "still listed as open after the idle timeout"; set_idle 15; return 1; }
  cm_front; dump | grep -q 'idle for more than 1 min' || { fail "the log does not say it locked for idleness"; set_idle 15; return 1; }
  set_idle 15
}

run_case unlock_read "Pick a VeraCrypt file (ext4 inside), unlock, and read flower.jpg, a name with spaces and an empty file six folders down through saf-probe's grant, by SHA-256; no write, delete or rename offered." case_unlock_read
run_case ntfs "The same, with NTFS inside the container." case_ntfs
run_case wrong_password "A wrong password is refused with a message, and nothing opens." case_wrong_password
run_case relock_grant "Lock: a read through the held grant fails. Pick and unlock again: the same ID, and the same grant reads again." case_relock_grant
run_case already_open "The same file picked and unlocked a second time while open is refused; one stays open." case_already_open
run_case format_luks "A LUKS file is recognised and named, and no VeraCrypt form or Unlock is offered for it." case_format_luks
run_case logs_report "The log shows the detected format and the unlock; Report issue (shared with OTG Master) opens with Container Master's version and no USB row." case_logs_report
run_case idle_lock "Lock after 1 minute without use: a read works, then after 75 s with no I/O the held grant's read fails, nothing is open, and the log says it locked for idleness." case_idle_lock
run_case lock_all_service "The foreground service runs while a container is open; Lock all closes it and the service stops; the container file is unchanged." case_lock_all_service

{
  echo "# Container Master E2E Test Report"; echo
  echo "| Field | Value |"; echo "|-------|-------|"
  echo "| Commit | \`$COMMIT\` |"; echo "| APK | debug \`$APK_COMMIT\` |"
  echo "| Device | $DEVICE (\`$S\`) |"; echo "| Date | $(date '+%Y-%m-%d %H:%M:%S') |"; echo
  echo "## Results"; echo; echo "| Test Case | Description | Result | Duration |"; echo "|---|---|---|---|"
  printf '%s\n' "${RESULTS[@]}"; echo
  echo "## Summary"; echo; echo "| Metric | Value |"; echo "|---|---|"
  echo "| Ran | $((PASS + FAIL)) |"; echo "| Passed | $PASS |"; echo "| Failed | $FAIL |"
  echo "| Overall | $([ $FAIL = 0 ] && echo '✅ PASSED' || echo '❌ FAILED') |"
} > "$REPORT"
echo "Report saved to: $REPORT"; echo "Passed $PASS, failed $FAIL"
[ $FAIL = 0 ]
