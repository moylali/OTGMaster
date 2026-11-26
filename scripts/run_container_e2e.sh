#!/usr/bin/env bash
# Container Master's E2E suite (architecture proposal, Milestone 3). Drives the real app on one
# device or emulator: the system picker, the unlock form, Lock and Lock all, and reads through
# tools/saf-probe, an independent SAF client with its own picker grants, so every read crosses
# Container Master's provider as another app's would. Results go to a Markdown report.
#
#   scripts/run_container_e2e.sh -s <adb serial> [--only <case>]...
#
# Fixtures it makes itself (ZIP, ISO, AES Crypt) go in testdata-container/, not testdata/: OTG's
# runner takes every folder under testdata/ for a case of its own (cm_luks was "not run" on 9b3342e).
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
# A ZIP made by Info-ZIP: the shared fixture files, and a 20 MiB entry, past the 16 MiB that is
# inflated whole, so its reads go through the streamed path.
if [ ! -f testdata-container/zip/test.zip ]; then
  t=$(mktemp -d); mkdir -p "$t/nested/very/deep/folder" testdata-container/zip
  cp testdata/flower.jpg "$t/"; printf 'Hello World\n' > "$t/file with spaces.txt"; : > "$t/nested/very/deep/folder/empty_file.txt"
  python3 -c 'import sys; open(sys.argv[1],"wb").write(bytes((i // 7) % 251 for i in range(20 << 20)))' "$t/big.bin"
  (cd "$t" && zip -q -r -6 "$OLDPWD/testdata-container/zip/test.zip" .) || { echo "ZIP fixture failed"; exit 1; }
  rm -rf "$t"
fi
# An ISO 9660 image with Joliet names: xorriso if installed, else pycdlib through uv.
if [ ! -f testdata-container/iso/test.iso ]; then
  t=$(mktemp -d); mkdir -p "$t/nested/very/deep/folder" testdata-container/iso
  cp testdata/flower.jpg "$t/"; printf 'Hello World\n' > "$t/file with spaces.txt"; : > "$t/nested/very/deep/folder/empty_file.txt"
  if command -v xorriso >/dev/null; then xorriso -as mkisofs -quiet -J -o testdata-container/iso/test.iso "$t" || exit 1
  else uv run --quiet --with pycdlib python - "$t" testdata-container/iso/test.iso <<'PY' || { echo "ISO fixture failed"; exit 1; }
import pycdlib, sys, os
src, out = sys.argv[1], sys.argv[2]
iso = pycdlib.PyCdlib(); iso.new(interchange_level=3, joliet=3)
for d in ['nested', 'nested/very', 'nested/very/deep', 'nested/very/deep/folder']:
    iso.add_directory('/' + d.upper(), joliet_path='/' + d)
for rel, short in [('flower.jpg', 'FLOWER.JPG'), ('file with spaces.txt', 'SPACES.TXT'), ('nested/very/deep/folder/empty_file.txt', 'NESTED/VERY/DEEP/FOLDER/EMPTY.TXT')]:
    iso.add_file(os.path.join(src, rel), '/' + short + ';1', joliet_path='/' + rel)
iso.write(out); iso.close()
PY
  fi
  rm -rf "$t"
fi
# An AES Crypt (version 2) file, made by pyAesCrypt through uv, holding flower.jpg.
if [ ! -f testdata-container/aes/flower.jpg.aes ]; then
  mkdir -p testdata-container/aes
  uv run --quiet --with pyAesCrypt python -c 'import pyAesCrypt,sys; pyAesCrypt.encryptFile(sys.argv[1], sys.argv[2], "password123")' \
    testdata/flower.jpg testdata-container/aes/flower.jpg.aes || { echo "AES Crypt fixture failed"; exit 1; }
fi
BIG=$(python3 -c 'import hashlib; print(hashlib.sha256(bytes((i // 7) % 251 for i in range(20 << 20))).hexdigest())')
# The public ID Container Master must give each container: SHA-256 of "veracrypt-salt:" and the
# header's first 64 bytes, first 10 hex (ContainerSessions.idForSalt).
cid() { python3 -c 'import hashlib,sys; d=open(sys.argv[1],"rb").read(64); print(hashlib.sha256(b"veracrypt-salt:"+d).hexdigest()[:10])' "$1"; }
ID_EXT4=$(cid testdata/ext4/test.img); ID_NTFS=$(cid testdata/ntfs/test.img)
# A LUKS volume's ID: SHA-256 of "luks-uuid:" and the header's 40 bytes at 168 (its UUID).
lid() { python3 -c 'import hashlib,sys; d=open(sys.argv[1],"rb").read(208); print(hashlib.sha256(b"luks-uuid:"+d[168:208]).hexdigest()[:10])' "$1"; }
for t in m_luks2_ext4 m_luks1_ext4; do [ -f testdata/$t/test.img ] || { echo "testdata/$t is missing: sudo bash scripts/generate_testdata.sh makes it"; exit 1; }; done
ID_LUKS2=$(lid testdata/m_luks2_ext4/test.img); ID_LUKS1=$(lid testdata/m_luks1_ext4/test.img)

echo "Building Container Master and saf-probe..."
./gradlew -q :container-app:assembleDebug || exit 1
[ -f tools/saf-probe/local.properties ] || cp local.properties tools/saf-probe/ 2>/dev/null || true
./gradlew -q -p tools/saf-probe assembleDebug || exit 1
APK_COMMIT=$(grep -oE 'GIT_COMMIT = "[^"]*"' container-app/build/generated/source/buildConfig/debug/app/fayaz/containermaster/BuildConfig.java | cut -d'"' -f2)
adb -s "$S" install -r container-app/build/outputs/apk/debug/container-app-debug.apk >/dev/null || exit 1
adb -s "$S" install -r tools/saf-probe/build/outputs/apk/debug/saf-probe-debug.apk >/dev/null || exit 1
for t in ext4 ntfs luks2 luks1 zip iso; do
  case $t in luks2) src=testdata/m_luks2_ext4/test.img ;; luks1) src=testdata/m_luks1_ext4/test.img ;; zip) src=testdata-container/zip/test.zip ;; iso) src=testdata-container/iso/test.iso ;; *) src=testdata/$t/test.img ;; esac
  want=$(sha256sum $src | cut -d' ' -f1)
  have=$(adb -s "$S" shell sha256sum /sdcard/Download/cm-$t.hc 2>/dev/null | cut -d' ' -f1)
  [ "$want" = "$have" ] || adb -s "$S" push $src /sdcard/Download/cm-$t.hc >/dev/null
done
want=$(sha256sum testdata-container/aes/flower.jpg.aes | cut -d' ' -f1)
[ "$want" = "$(adb -s "$S" shell sha256sum /sdcard/Download/flower.jpg.aes 2>/dev/null | cut -d' ' -f1)" ] || adb -s "$S" push testdata-container/aes/flower.jpg.aes /sdcard/Download/flower.jpg.aes >/dev/null
adb -s "$S" shell input keyevent KEYCODE_WAKEUP
DEVICE="$(adb -s "$S" shell getprop ro.product.model | tr -d '\r') / Android $(adb -s "$S" shell getprop ro.build.version.release | tr -d '\r')"

RESULTS=(); PASS=0; FAIL=0; SKIPPED=0
fail() { echo "  FAIL: $*"; WHY="$*"; return 1; }
run_case() {
  local name=$1 desc=$2; shift 2
  if [ ${#ONLY[@]} -gt 0 ] && [[ ! " ${ONLY[*]} " =~ " $name " ]]; then return; fi
  echo "== $name"; WHY=""; SKIP=0; local t0=$SECONDS
  lock_all >/dev/null
  if "$@"; then RESULTS+=("| \`$name\` | $desc | ✅ PASSED | $((SECONDS - t0))s |"); PASS=$((PASS + 1))
  elif [ "$SKIP" = 1 ]; then echo "  skipped: $WHY"; RESULTS+=("| \`$name\` | $desc | ⏭️ SKIPPED: $WHY | $((SECONDS - t0))s |"); SKIPPED=$((SKIPPED + 1))
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

luks_case() { # luks_case <file> <expected id>
  cm_front; pick "$1" || { fail "could not pick $1"; return 1; }
  local X; X=$(dump)
  echo "$X" | grep -q 'LUKS volume' || { fail "the format line does not name LUKS"; return 1; }
  echo "$X" | grep -q 'content-desc="pim"' && { fail "a PIM field for a LUKS volume"; return 1; }
  local out; out=$(unlock password123); [ "$out" = "opened $2" ] || { fail "unlock: got '$out', expected 'opened $2'"; return 1; }
  TREE=$(grant "$2"); [ -n "$TREE" ] || { fail "no grant on $2"; return 1; }
  local r; r=$(read_sha "$TREE" flower.jpg); [ "$r" = "\"sha256\": \"$FLOWER\"" ] || { fail "flower.jpg: $r"; return 1; }
}
case_luks2() { luks_case cm-luks2.hc "$ID_LUKS2"; }
case_luks1() { luks_case cm-luks1.hc "$ID_LUKS1"; }

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

# A held descriptor runs inside a saf-probe activity that stays open. Every probe is started in
# a task of its own (-f 0x18080000: NEW_TASK, MULTIPLE_TASK, NEW_DOCUMENT): otherwise Android
# delivered the next probe's intent to the holding instance on top, and its read never ran
# (zip_and_veracrypt, c390cfa and f2d1c4b).
case_held_fd_lock() {
  open_and_grant cm-ext4.hc password123 1 "$ID_EXT4" || return 1
  adb -s "$S" shell "rm -f $F/result-hold.json $F/result-hold-open.json; am start -f 0x18080000 -n app.fayaz.safprobe/.ProbeActivity --es cmd hold --es tree '$TREE' --es path flower.jpg --ei wait 25" >/dev/null
  local i; for i in $(seq 1 30); do adb -s "$S" shell "test -f $F/result-hold-open.json" && break; sleep 1; done
  adb -s "$S" shell cat $F/result-hold-open.json | grep -q '"firstRead":"65536 bytes"' || { fail "first read: $(adb -s "$S" shell cat $F/result-hold-open.json)"; return 1; }
  lock_all
  # Unlock the same container again while the old descriptor is still held, so a late read
  # resolving to the new session would succeed; it must not.
  cm_front; pick cm-ext4.hc || { fail "could not pick again"; return 1; }
  [ "$(unlock password123 1)" = "opened $ID_EXT4" ] || { fail "second unlock"; return 1; }
  for i in $(seq 1 60); do adb -s "$S" shell "test -f $F/result-hold.json" && break; sleep 1; done
  local r; r=$(adb -s "$S" shell cat $F/result-hold.json | tr -d '\n')
  echo "$r" | grep -q '"lateRead": "error' || { fail "the held descriptor read after the lock: $r"; return 1; }
  echo "$r" | grep -q '"closed": true' || { fail "the held descriptor did not close: $r"; return 1; }
  local r2; r2=$(read_sha "$TREE" flower.jpg); [ "$r2" = "\"sha256\": \"$FLOWER\"" ] || { fail "a fresh read after the relock: $r2"; return 1; }
}

open_zip_and_grant() { # sets TREE and ZID
  cm_front; pick cm-zip.hc || { fail "could not pick the ZIP"; return 1; }
  dump | grep -q 'opens without a password' || { fail "the ZIP was not recognised"; return 1; }
  dump | grep -q 'content-desc="password"' && { fail "a password form for a ZIP"; return 1; }
  tap 'content-desc="open_zip"' || { fail "no Open for the ZIP"; return 1; }
  local i; for i in $(seq 1 60); do sleep 1; on_screen 'content-desc="chosen_file"' || break; on_screen 'content-desc="message"' && break; done
  on_screen 'content-desc="message"' && { fail "the ZIP did not open: $(dump | grep -oE 'text="[^"]*"[^>]*content-desc="message"')"; return 1; }
  ZID=$(open_ids | tail -1); [ -n "$ZID" ] || { fail "the ZIP is not listed as open"; return 1; }
  TREE=$(grant "$ZID"); [ -n "$TREE" ] || { fail "no grant on the ZIP"; return 1; }
}

case_zip_read() {
  open_zip_and_grant || return 1
  local r
  r=$(read_sha "$TREE" flower.jpg); [ "$r" = "\"sha256\": \"$FLOWER\"" ] || { fail "flower.jpg: $r"; return 1; }
  r=$(read_sha "$TREE" "file with spaces.txt"); [ "$r" = "\"sha256\": \"$SPACES\"" ] || { fail "file with spaces.txt: $r"; return 1; }
  r=$(read_sha "$TREE" nested/very/deep/folder/empty_file.txt); [ "$r" = "\"sha256\": \"$EMPTY\"" ] || { fail "empty_file.txt: $r"; return 1; }
  r=$(read_sha "$TREE" big.bin); [ "$r" = "\"sha256\": \"$BIG\"" ] || { fail "big.bin (streamed): $r"; return 1; }
}

case_iso_read() {
  cm_front; pick cm-iso.hc || { fail "could not pick the ISO"; return 1; }
  dump | grep -q 'ISO 9660' || { fail "the ISO was not recognised"; return 1; }
  tap 'content-desc="open_zip"' || { fail "no Open for the ISO"; return 1; }
  local i; for i in $(seq 1 60); do sleep 1; on_screen 'content-desc="chosen_file"' || break; on_screen 'content-desc="message"' && break; done
  on_screen 'content-desc="message"' && { fail "the ISO did not open"; return 1; }
  local id; id=$(open_ids | tail -1); [ -n "$id" ] || { fail "the ISO is not listed as open"; return 1; }
  TREE=$(grant "$id"); [ -n "$TREE" ] || { fail "no grant on the ISO"; return 1; }
  local r
  r=$(read_sha "$TREE" flower.jpg); [ "$r" = "\"sha256\": \"$FLOWER\"" ] || { fail "flower.jpg: $r"; return 1; }
  r=$(read_sha "$TREE" "file with spaces.txt"); [ "$r" = "\"sha256\": \"$SPACES\"" ] || { fail "file with spaces.txt: $r"; return 1; }
  r=$(read_sha "$TREE" nested/very/deep/folder/empty_file.txt); [ "$r" = "\"sha256\": \"$EMPTY\"" ] || { fail "empty_file.txt: $r"; return 1; }
}

case_aescrypt() {
  cm_front; pick flower.jpg.aes || { fail "could not pick the AES Crypt file"; return 1; }
  dump | grep -q 'AES Crypt file' || { fail "not recognised as AES Crypt"; return 1; }
  dump | grep -q 'content-desc="pim"' && { fail "a PIM field for AES Crypt"; return 1; }
  local out; out=$(unlock not-the-password); [[ "$out" == message:* ]] || { fail "a wrong password: '$out'"; return 1; }
  out=$(unlock password123); [[ "$out" == opened\ * ]] || { fail "unlock: '$out'"; return 1; }
  local id=${out#opened }
  TREE=$(grant "$id"); [ -n "$TREE" ] || { fail "no grant"; return 1; }
  local r; r=$(read_sha "$TREE" flower.jpg); [ "$r" = "\"sha256\": \"$FLOWER\"" ] || { fail "flower.jpg: $r"; return 1; }
}

case_ui_killed() {
  open_zip_and_grant || return 1
  local engine; engine=$(adb -s "$S" shell pidof $CM | tr -d '\r')
  local ui; ui=$(adb -s "$S" shell pidof $CM:ui | tr -d '\r')
  [ -n "$engine" ] && [ -n "$ui" ] && [ "$engine" != "$ui" ] || { fail "expected an engine and a :ui process, got engine '$engine' ui '$ui'"; return 1; }
  adb -s "$S" shell "rm -f $F/result-hold.json $F/result-hold-open.json; am start -f 0x18080000 -n app.fayaz.safprobe/.ProbeActivity --es cmd hold --es tree '$TREE' --es path big.bin --ei wait 15" >/dev/null
  local i; for i in $(seq 1 30); do adb -s "$S" shell "test -f $F/result-hold-open.json" && break; sleep 1; done
  # The screen's process dies while another app holds a descriptor on the container.
  adb -s "$S" shell run-as $CM kill -9 "$ui"; sleep 2
  [ -z "$(adb -s "$S" shell pidof $CM:ui | tr -d '\r')" ] || { fail "the :ui process survived kill -9"; return 1; }
  [ "$(adb -s "$S" shell pidof $CM | tr -d '\r')" = "$engine" ] || { fail "the engine process changed when the UI died"; return 1; }
  for i in $(seq 1 40); do adb -s "$S" shell "test -f $F/result-hold.json" && break; sleep 1; done
  adb -s "$S" shell cat $F/result-hold.json | tr -d '\n' | grep -q '"lateRead": "65536 bytes"' || { fail "the held read after the UI died: $(adb -s "$S" shell cat $F/result-hold.json | tr -d '\n')"; return 1; }
  local r; r=$(read_sha "$TREE" big.bin); [ "$r" = "\"sha256\": \"$BIG\"" ] || { fail "a fresh read after the UI died: $r"; return 1; }
  [ "$(open_ids | tail -1)" = "$ZID" ] || { fail "the relaunched screen does not list the container"; return 1; }
}

case_zip_and_veracrypt() {
  open_and_grant cm-ext4.hc password123 1 "$ID_EXT4" || return 1
  local vtree=$TREE
  open_zip_and_grant || return 1
  local ztree=$TREE r
  # A descriptor held on the ZIP's streamed entry, seeking to 1 MiB after a wait, while the
  # VeraCrypt container is read: both through the same provider code.
  adb -s "$S" shell "rm -f $F/result-hold.json $F/result-hold-open.json; am start -f 0x18080000 -n app.fayaz.safprobe/.ProbeActivity --es cmd hold --es tree '$ztree' --es path big.bin --ei wait 8" >/dev/null
  r=$(read_sha "$vtree" flower.jpg); [ "$r" = "\"sha256\": \"$FLOWER\"" ] || { fail "VeraCrypt flower.jpg beside the ZIP: $r"; return 1; }
  r=$(read_sha "$ztree" flower.jpg); [ "$r" = "\"sha256\": \"$FLOWER\"" ] || { fail "ZIP flower.jpg beside VeraCrypt: $r"; return 1; }
  local i; for i in $(seq 1 40); do adb -s "$S" shell "test -f $F/result-hold.json" && break; sleep 1; done
  adb -s "$S" shell cat $F/result-hold.json | tr -d '\n' | grep -q '"lateRead": "65536 bytes"' || { fail "the ZIP seek: $(adb -s "$S" shell cat $F/result-hold.json | tr -d '\n')"; return 1; }
  [ "$(open_ids | wc -l)" = 2 ] || { fail "not both open"; return 1; }
}

case_screen_settings() {
  cm_front
  local X; X=$(dump)
  echo "$X" | grep -q 'content-desc="choose_file"' || { fail "no Choose a container file"; return 1; }
  echo "$X" | grep -q 'Its files then show up' && { fail "the intro text is still under the title"; return 1; }
  panel_open; on_screen 'text="Mounted containers"' || { fail "the panel is not called Mounted containers"; panel_close; return 1; }; panel_close
  tap 'content-desc="settings"' || { fail "no Settings"; return 1; }; sleep 1; X=$(dump)
  local d; for d in remember_passwords forget_passwords settings_report_issue donate_kofi version idle_15 lock_on_screen_off; do
    echo "$X" | grep -q "content-desc=\"$d\"" || { adb -s "$S" shell input swipe 500 1800 500 600 300; sleep 1; X=$(dump); }
    echo "$X" | grep -q "content-desc=\"$d\"" || { fail "Settings has no $d"; tap 'content-desc="close_settings"'; return 1; }
  done
  echo "$X" | grep -q 'content-desc="donate_support_link"' || { fail "Settings has no support link"; tap 'content-desc="close_settings"'; return 1; }
  echo "$X" | grep -q "text=\"Version [^\"]*$APK_COMMIT\"" || { fail "the version row does not name $APK_COMMIT"; tap 'content-desc="close_settings"'; return 1; }
  tap 'content-desc="close_settings"'
}

case_open_with_zip() {
  view_file cm-zip.hc application/zip || { fail "saf-probe could not hand the ZIP over"; return 1; }
  # A ZIP needs no password, so Open with mounts it at once and shows Mounted containers.
  local i; for i in $(seq 1 30); do sleep 1; on_screen 'content-desc="close_open_containers"' && break; on_screen 'content-desc="message"' && break; done
  on_screen 'content-desc="message"' && { fail "refused: $(dump | grep -oE 'text="[^"]*"[^>]*content-desc="message"')"; return 1; }
  on_screen 'content-desc="close_open_containers"' || { fail "Mounted containers did not open after the ZIP mounted"; return 1; }
  ZID=$(open_ids | tail -1); [ -n "$ZID" ] || { fail "the ZIP is not listed"; return 1; }
  TREE=$(grant "$ZID"); [ -n "$TREE" ] || { fail "no grant on the ZIP"; return 1; }
  local r; r=$(read_sha "$TREE" flower.jpg); [ "$r" = "\"sha256\": \"$FLOWER\"" ] || { fail "flower.jpg: $r"; return 1; }
}

case_open_with_veracrypt() {
  view_file cm-ext4.hc application/octet-stream || { fail "saf-probe could not hand the file over"; return 1; }
  # A VeraCrypt file needs its password: the form shows it, ready to unlock.
  local i; for i in $(seq 1 15); do sleep 1; on_screen 'text="File: cm-ext4.hc"' && break; done
  on_screen 'text="File: cm-ext4.hc"' || { fail "the handed-over file is not on the form"; return 1; }
  local out; out=$(unlock password123 1); [ "$out" = "opened $ID_EXT4" ] || { fail "unlock: got '$out', expected 'opened $ID_EXT4'"; return 1; }
}

# Needs a screen lock, which this sets (PIN 1234) and clears; on an emulator only, so a phone kept
# awake for the suite is never left behind a PIN.
case_remember_password() {
  [ "$(adb -s "$S" shell getprop ro.kernel.qemu | tr -d '\r')" = 1 ] || [ "$(adb -s "$S" shell getprop ro.boot.qemu | tr -d '\r')" = 1 ] || { WHY="not an emulator: skipped, it sets a PIN"; SKIP=1; return 1; }
  adb -s "$S" shell locksettings set-pin 1234 >/dev/null
  local rc=0; remember_inner || rc=1
  adb -s "$S" shell locksettings clear --old 1234 >/dev/null
  return $rc
}
remember_inner() {
  cm_front; tap 'content-desc="settings"'; sleep 1
  dump | grep -oE '<node[^>]*content-desc="remember_passwords"[^>]*' | grep -q 'checked="true"' || tap 'content-desc="remember_passwords"'; sleep 1
  tap 'content-desc="close_settings"'; sleep 1
  pick cm-ext4.hc || { fail "could not pick"; return 1; }
  local out; out=$(unlock password123 1); [ "$out" = "opened $ID_EXT4" ] || { fail "first unlock: $out"; return 1; }
  lock_all
  cm_front; pick cm-ext4.hc >/dev/null
  local i; for i in $(seq 1 10); do sleep 1; on_screen 'Open with the saved password' && break; done
  on_screen 'Open with the saved password' || { fail "no phone-lock prompt on re-picking the same file"; return 1; }
  adb -s "$S" shell input text 1234; adb -s "$S" shell input keyevent KEYCODE_ENTER
  for i in $(seq 1 60); do sleep 1; [ "$(open_ids | tail -1)" = "$ID_EXT4" ] && break; done
  [ "$(open_ids | tail -1)" = "$ID_EXT4" ] || { fail "the saved password did not open it"; return 1; }
  lock_all; cm_front; tap 'content-desc="settings"'; sleep 1; tap 'content-desc="forget_passwords"'; sleep 1
  tap 'content-desc="remember_passwords"'; sleep 1; tap 'content-desc="close_settings"'
}

run_case unlock_read "Pick a VeraCrypt file (ext4 inside), unlock, and read flower.jpg, a name with spaces and an empty file six folders down through saf-probe's grant, by SHA-256; no write, delete or rename offered." case_unlock_read
run_case ntfs "The same, with NTFS inside the container." case_ntfs
run_case wrong_password "A wrong password is refused with a message, and nothing opens." case_wrong_password
run_case relock_grant "Lock: a read through the held grant fails. Pick and unlock again: the same ID, and the same grant reads again." case_relock_grant
run_case already_open "The same file picked and unlocked a second time while open is refused; one stays open." case_already_open
run_case luks2_ext4 "A LUKS2 file (Argon2id, ext4 inside, made by cryptsetup) is recognised, asks for a password and no PIM, unlocks with the ID from its UUID, and flower.jpg reads by SHA-256." case_luks2
run_case luks1_ext4 "The same for a LUKS1 file (PBKDF2)." case_luks1
run_case logs_report "The log shows the detected format and the unlock; Report issue (shared with OTG Master) opens with Container Master's version and no USB row." case_logs_report
run_case idle_lock "Lock after 1 minute without use: a read works, then after 75 s with no I/O the held grant's read fails, nothing is open, and the log says it locked for idleness." case_idle_lock
run_case held_fd_lock "A client holds a descriptor across Lock and a fresh unlock of the same container: its late read fails rather than reaching the new session, it still closes, and a fresh open reads the file." case_held_fd_lock
run_case zip_read "A ZIP made by Info-ZIP opens with no password; flower.jpg, a name with spaces, an empty file six folders down and a 20 MiB streamed entry read by SHA-256 through saf-probe's grant." case_zip_read
run_case iso_read "An ISO 9660 image with Joliet names opens with no password; flower.jpg, a name with spaces and an empty file six folders down read by SHA-256 through saf-probe's grant." case_iso_read
run_case aescrypt "An AES Crypt file made by pyAesCrypt: a wrong password refused, the right one opens it, and the one file inside, flower.jpg, reads by SHA-256 through saf-probe's grant." case_aescrypt
run_case ui_killed "Milestone 2b: the screen's process (:ui) is killed while another app holds a descriptor on an open ZIP; the engine process lives on, the held read and a fresh read return the right bytes, and the relaunched screen lists the container." case_ui_killed
run_case zip_and_veracrypt "A ZIP and a VeraCrypt container open together; the same flower.jpg reads from both through the same provider code, while a descriptor held on the ZIP's streamed entry seeks to 1 MiB." case_zip_and_veracrypt
run_case screen_settings "The main screen has only the file button under the title; the panel is called Mounted containers; Settings has Remember passwords, Forget saved passwords, Report issue, Support (Ko-fi and the support page), the idle lock and the app version with this build's commit." case_screen_settings
run_case open_with_zip "Open with: a ZIP handed over by another app (saf-probe, ACTION_VIEW, a read grant) mounts at once, Mounted containers opens, and flower.jpg reads by SHA-256." case_open_with_zip
run_case open_with_veracrypt "Open with: a VeraCrypt file handed over as octet-stream lands on the form, and its password unlocks it under its usual ID." case_open_with_veracrypt
run_case remember_password "Remember passwords on, with a PIN set: the password is saved after an unlock; picking the same file again asks for the PIN and opens it. Emulator only." case_remember_password
run_case lock_all_service "The foreground service runs while a container is open; Lock all closes it and the service stops; the container file is unchanged." case_lock_all_service

{
  echo "# Container Master E2E Test Report"; echo
  echo "| Field | Value |"; echo "|-------|-------|"
  echo "| Commit | \`$COMMIT\` |"; echo "| APK | debug \`$APK_COMMIT\` |"
  echo "| Device | $DEVICE (\`$S\`) |"; echo "| Date | $(date '+%Y-%m-%d %H:%M:%S') |"; echo
  echo "## Results"; echo; echo "| Test Case | Description | Result | Duration |"; echo "|---|---|---|---|"
  printf '%s\n' "${RESULTS[@]}"; echo
  echo "## Summary"; echo; echo "| Metric | Value |"; echo "|---|---|"
  echo "| Ran | $((PASS + FAIL)) |"; echo "| Passed | $PASS |"; echo "| Failed | $FAIL |"; echo "| Skipped | $SKIPPED |"
  echo "| Overall | $([ $FAIL = 0 ] && echo '✅ PASSED' || echo '❌ FAILED') |"
} > "$REPORT"
echo "Report saved to: $REPORT"; echo "Passed $PASS, failed $FAIL, skipped $SKIPPED"
[ $FAIL = 0 ]
