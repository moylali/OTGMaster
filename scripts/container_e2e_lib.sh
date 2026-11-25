# Device helpers for scripts/run_container_e2e.sh: drive Container Master's screen and the system
# picker over adb (uiautomator dumps and taps), and read through tools/saf-probe, the independent
# SAF client. Source with S=<adb serial>. Pickers differ between DocumentsUI builds (a search box,
# a search icon, a search left open from last time; "Use this folder" or "USE THIS FOLDER"), and
# each helper tries the forms seen so far.
F=/sdcard/Android/data/app.fayaz.safprobe/files
CM=app.fayaz.containermaster
dump() { adb -s "$S" exec-out uiautomator dump /dev/tty 2>/dev/null; }
center() { echo "$1" | grep -oE "$2[^>]*bounds=\"\[[0-9]+,[0-9]+\]\[[0-9]+,[0-9]+\]\"" | head -1 | grep -oE '\[[0-9]+,[0-9]+\]\[[0-9]+,[0-9]+\]' | sed -E 's/\[([0-9]+),([0-9]+)\]\[([0-9]+),([0-9]+)\]/\1 \2 \3 \4/' | awk '{print int(($1+$3)/2), int(($2+$4)/2)}'; }
on_screen() { dump | grep -q "$1"; }
tap() { local xy; xy=$(center "$(dump)" "$1"); [ -n "$xy" ] || return 1; adb -s "$S" shell input tap $xy; }
tap_any() { local p; for p in "$@"; do tap "$p" && return 0; done; echo "none on screen: $*" >&2; return 1; }
cm_front() { adb -s "$S" shell am start -n $CM/.MainActivity >/dev/null 2>&1; sleep 2; on_screen 'Allow Container Master to send' && tap_any 'text="Allow"' 'text="ALLOW"'; sleep 1; }
# pick <file name>: Choose a container file, search the picker for it, take it.
pick() {
  tap 'content-desc="choose_file"' || return 1; sleep 4
  local X; X=$(dump)
  if echo "$X" | grep -q 'content-desc="Clear query"'; then tap 'content-desc="Clear query"'
  elif echo "$X" | grep -q 'text="Search this device"'; then tap 'text="Search this device"'
  else tap 'content-desc="Search"'; fi; sleep 1
  adb -s "$S" shell input text "${1%.*}"; adb -s "$S" shell input keyevent KEYCODE_ENTER; sleep 4
  tap "text=\"$1\"" || return 1; sleep 3
  on_screen "text=\"File: $1\""; }
# unlock <password> [pim]: prints "opened <id>" or "message: <text>".
# clear_field: empties the focused field (end of text, then 64 deletes); the form keeps what was
# typed after a refused unlock, and typing after it made the next case's password wrong.
clear_field() { adb -s "$S" shell input keyevent KEYCODE_MOVE_END; adb -s "$S" shell input keyevent $(printf '67 %.0s' $(seq 64)); }
unlock() {
  tap 'content-desc="password"'; sleep 1; clear_field; adb -s "$S" shell input text "$1"; adb -s "$S" shell input keyevent KEYCODE_BACK; sleep 1
  tap 'content-desc="pim"'; sleep 1; clear_field
  [ -n "${2:-}" ] && adb -s "$S" shell input text "$2"
  adb -s "$S" shell input keyevent KEYCODE_BACK; sleep 1
  tap 'content-desc="unlock"' || return 1
  # Done when a message shows (refused) or the form clears the chosen file (opened).
  local X i; for i in $(seq 1 120); do sleep 1; X=$(dump)
    echo "$X" | grep -q 'content-desc="message"' && break
    echo "$X" | grep -q 'content-desc="chosen_file"' || break; done
  if echo "$X" | grep -q 'content-desc="message"'; then
    echo "message: $(echo "$X" | grep -oE 'text="[^"]*"[^>]*content-desc="message"' | sed -E 's/text="([^"]*)".*/\1/')"
  else echo "opened $(open_ids | tail -1)"; fi; }
# The open containers are in the panel behind the top bar's icon, as OTG Master's drives are.
panel_open() { cm_front; tap 'content-desc="open_containers"'; sleep 1; }
panel_close() { tap 'content-desc="close_open_containers"'; sleep 1; }
open_ids() { panel_open; dump | grep -oE 'content-desc="open_[0-9a-f]+"' | grep -v 'open_containers' | sed -E 's/.*open_([0-9a-f]+)"/\1/'; panel_close; }
lock_all() { panel_open; on_screen 'content-desc="lock_all"' && tap 'content-desc="lock_all"'; sleep 3; panel_close; }
probe() { # probe <cmd> <extras...>: run saf-probe and print its result JSON on one line
  local cmd=$1; shift
  adb -s "$S" shell "rm -f $F/result-$cmd.json; am start -W -f 0x18080000 -n app.fayaz.safprobe/.ProbeActivity --es cmd $cmd $*" >/dev/null
  local i; for i in $(seq 1 30); do adb -s "$S" shell "test -f $F/result-$cmd.json" && break; sleep 1; done
  adb -s "$S" shell cat $F/result-$cmd.json 2>/dev/null | tr -d '\n' | tr -s ' '; }
# grant <id>: saf-probe takes a picker grant on the container's root; prints the tree URI.
grant() {
  adb -s "$S" shell "rm -f $F/result-grant.json; am start -W -f 0x18080000 -n app.fayaz.safprobe/.ProbeActivity --es cmd grant --es initial 'content://$CM.documents/document/$1%3A%2F'" >/dev/null; sleep 5
  tap_any 'text="Use this folder"' 'text="USE THIS FOLDER"' || return 1; sleep 2
  tap_any 'text="Allow"' 'text="ALLOW"' || return 1; sleep 3
  adb -s "$S" shell cat $F/result-grant.json | grep -oE '"tree": "[^"]*"' | sed -E 's/"tree": "(.*)"/\1/; s#\\/#/#g'; }
read_sha() { probe read --es tree "'$1'" --es path "'$2'" | grep -oE '"sha256": "[0-9a-f]+"|"error": "[^"]*"' | head -1; }
