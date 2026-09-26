#!/usr/bin/env bash
#
# Strip macOS metadata from an already-prepared fixture drive, without rebuilding it.
#
# prepare_test_usb.sh runs its cleanup before writing MANIFEST.txt, so the manifest
# write recreates a "._MANIFEST.txt" AppleDouble and macOS recreates ".fseventsd".
# Both live at the volume root and do not affect the measured directories, but this
# makes the fixture match what verify expects.
#
# Also drops a ".fseventsd/no_log" marker, which is the documented way to stop
# macOS logging filesystem events to the volume on future mounts.
#
# Usage:
#   sudo scripts/clean_test_usb.sh --disk disk4 --fs fat32 --veracrypt
#
set -uo pipefail

DISK=""; FS=""; VERACRYPT=0; VC_PASS="password123"; VC_PIM=1

die()  { printf '\033[31merror:\033[0m %s\n' "$*" >&2; exit 1; }
info() { printf '\033[36m==>\033[0m %s\n' "$*"; }
ok()   { printf '    \033[32m✓\033[0m %s\n' "$*"; }

while [ $# -gt 0 ]; do
    case "$1" in
        --disk) DISK="${2#/dev/}"; shift 2 ;;
        --fs) FS="$2"; shift 2 ;;
        --veracrypt) VERACRYPT=1; shift ;;
        --vc-password) VC_PASS="$2"; shift 2 ;;
        --vc-pim) VC_PIM="$2"; shift 2 ;;
        *) die "unknown argument: $1" ;;
    esac
done
[ -n "$DISK" ] && [ -n "$FS" ] || die "need --disk and --fs"

PART="${DISK}s1"; ATTACHED=0; MNT=""
cleanup() {
    [ -n "$MNT" ] && umount "$MNT" 2>/dev/null
    [ "$ATTACHED" -eq 1 ] && veracrypt -t -d "/dev/$PART" --non-interactive >/dev/null 2>&1
    [ -n "$MNT" ] && rmdir "$MNT" 2>/dev/null
    return 0
}
trap cleanup EXIT INT TERM

if [ "$VERACRYPT" -eq 1 ]; then
    info "Attaching /dev/$PART"
    diskutil unmount force "/dev/$PART" >/dev/null 2>&1
    veracrypt -t --mount "/dev/$PART" --password="$VC_PASS" --pim="$VC_PIM" \
        --keyfiles="" --protect-hidden=no --filesystem=none --non-interactive \
        || die "could not attach"
    ATTACHED=1
    DEV="$(veracrypt -t -l 2>/dev/null | awk -v p="/dev/$PART" '$2 == p { print $3 }')"
    [ -n "$DEV" ] || die "no device node reported"
    MNT="/Volumes/clean_$$"; mkdir -p "$MNT"
    [ "$FS" = "fat32" ] && MT=msdos || MT=exfat
    mount -t "$MT" "$DEV" "$MNT" || die "could not mount"
else
    DEV="/dev/$PART"
    diskutil mount "$DEV" >/dev/null 2>&1
    MNT="$(diskutil info "$DEV" | grep -E "^ *Mount Point:" | head -1 | sed 's/.*: *//' || true)"
fi
[ -n "$MNT" ] && [ -d "$MNT" ] || die "no mount point"
info "Mounted at $MNT"

before=$(find "$MNT" -name '._*' 2>/dev/null | wc -l | tr -d ' ')
info "Cleaning ($before AppleDouble files present)"

# Every write must happen BEFORE the strip, or it leaves fresh droppings --
# creating .fseventsd/ after stripping is what produced "._.fseventsd" and
# turned 1 stray file into 2.
rm -rf "$MNT/.Spotlight-V100" "$MNT/.Trashes" "$MNT/.fseventsd" 2>/dev/null
mkdir -p "$MNT/.fseventsd" 2>/dev/null && : > "$MNT/.fseventsd/no_log" 2>/dev/null
sync

# Now strip, and nothing writes after this point.
xattr -cr "$MNT" 2>/dev/null
dot_clean -m "$MNT" 2>/dev/null
find "$MNT" -name '._*' -delete 2>/dev/null
sync

after=$(find "$MNT" -name '._*' 2>/dev/null | wc -l | tr -d ' ')
inbench=$(find "$MNT/BENCH" -name '._*' 2>/dev/null | wc -l | tr -d ' ')
ok "AppleDouble: $before -> $after total, $inbench inside BENCH/"
[ -f "$MNT/.fseventsd/no_log" ] && ok ".fseventsd/no_log marker set"

info "Root contents:"
ls -a "$MNT" | sed 's/^/      /'
echo
