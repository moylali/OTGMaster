#!/usr/bin/env bash
#
# Verify a drive prepared by prepare_test_usb.sh. Read-only: attaches, inspects,
# detaches. Never formats or writes.
#
# Usage:
#   sudo scripts/verify_test_usb.sh --disk disk4 --fs fat32 --veracrypt
#   sudo scripts/verify_test_usb.sh --disk disk5 --fs exfat --veracrypt --expect-files 10000
#
set -uo pipefail   # deliberately NOT -e: this script reports problems, it does not abort on them

DISK=""; FS=""; VERACRYPT=0
VC_PASS="password123"; VC_PIM=1
EXPECT_FILES=10000
EXPECT_CLUSTER=4096

die()  { printf '\033[31merror:\033[0m %s\n' "$*" >&2; exit 1; }
info() { printf '\033[36m==>\033[0m %s\n' "$*"; }
ok()   { printf '    \033[32m✓\033[0m %s\n' "$*"; }
bad()  { printf '    \033[31m✗\033[0m %s\n' "$*"; FAILED=1; }
FAILED=0

while [ $# -gt 0 ]; do
    case "$1" in
        --disk) DISK="${2#/dev/}"; shift 2 ;;
        --fs) FS="$2"; shift 2 ;;
        --veracrypt) VERACRYPT=1; shift ;;
        --vc-password) VC_PASS="$2"; shift 2 ;;
        --vc-pim) VC_PIM="$2"; shift 2 ;;
        --expect-files) EXPECT_FILES="$2"; shift 2 ;;
        --expect-cluster) EXPECT_CLUSTER="$2"; shift 2 ;;
        *) die "unknown argument: $1" ;;
    esac
done
[ -n "$DISK" ] && [ -n "$FS" ] || die "need --disk and --fs"

PART="${DISK}s1"
ATTACHED=0; MNT=""

cleanup() {
    [ -n "$MNT" ] && umount "$MNT" 2>/dev/null
    [ "$ATTACHED" -eq 1 ] && veracrypt -t -d "/dev/$PART" --non-interactive >/dev/null 2>&1
    return 0
}
trap cleanup EXIT INT TERM

# --- attach ----------------------------------------------------------------
if [ "$VERACRYPT" -eq 1 ]; then
    info "Attaching VeraCrypt volume on /dev/$PART"
    diskutil unmount force "/dev/$PART" >/dev/null 2>&1
    veracrypt -t --mount "/dev/$PART" --password="$VC_PASS" --pim="$VC_PIM" \
        --keyfiles="" --protect-hidden=no --filesystem=none --non-interactive \
        || die "could not attach — wrong password/PIM, or not a VeraCrypt volume"
    ATTACHED=1
    DEV="$(veracrypt -t -l 2>/dev/null | awk -v p="/dev/$PART" '$2 == p { print $3 }')"
    [ -n "$DEV" ] || die "attached but no device node reported"
    ok "attached as $DEV"
    MNT="/Volumes/verify_$$"
    mkdir -p "$MNT"
    if [ "$FS" = "fat32" ]; then MT=msdos; else MT=exfat; fi
    mount -t "$MT" "$DEV" "$MNT" || die "could not mount $DEV as $FS"
else
    DEV="/dev/$PART"
    diskutil mount "$DEV" >/dev/null 2>&1
    MNT="$(diskutil info "$DEV" | grep -E "^ *Mount Point:" | head -1 | sed 's/.*: *//' || true)"
fi
[ -n "$MNT" ] && [ -d "$MNT" ] || die "no mount point"
info "Mounted at $MNT"

# --- cluster size ------------------------------------------------------------
# statvfs f_frsize is the fundamental block size = the cluster size, and is
# exact for both FAT32 and exFAT. Preferred over diskutil, which reports nothing
# until DiskArbitration has registered a manually-mounted volume, and over
# reading the BPB, which a VeraCrypt virtual device will not serve to dd.
# (f_bsize is the optimal I/O size — 65536 — not the cluster size.)
info "Cluster size"
CLUSTER="$(python3 -c "import os,sys; print(os.statvfs(sys.argv[1]).f_frsize)" "$MNT" 2>/dev/null || true)"
if [ -z "$CLUSTER" ]; then bad "could not read cluster size from $MNT"
elif [ "$CLUSTER" = "$EXPECT_CLUSTER" ]; then ok "cluster size $CLUSTER"
else bad "cluster size $CLUSTER, expected $EXPECT_CLUSTER"; fi

# --- fixtures ---------------------------------------------------------------
B="$MNT/BENCH"
info "Fixtures"
[ -d "$B" ] || bad "BENCH/ missing"
for d in dense_short dense_lfn; do
    n=$(ls -1 "$B/$d" 2>/dev/null | wc -l | tr -d ' ')
    if [ "$n" = "$EXPECT_FILES" ]; then ok "$d: $n files"; else bad "$d: $n files (expected $EXPECT_FILES)"; fi
done
for f in seq_2g.bin:2147483648 seq_1g.bin:1073741824 seq_256m.bin:268435456; do
    name="${f%%:*}"; want="${f##*:}"
    got=$(stat -f%z "$B/large/$name" 2>/dev/null)
    if [ "$got" = "$want" ]; then ok "large/$name: $got bytes"; else bad "large/$name: $got bytes (expected $want)"; fi
done
[ -f "$B/MANIFEST.txt" ] && ok "MANIFEST.txt present" || bad "MANIFEST.txt missing"
depth=$(find "$B/nested" -name 'leaf_at_depth_10.dat' 2>/dev/null | head -1)
[ -n "$depth" ] && ok "nested tree reaches depth 10" || bad "nested tree incomplete"

# --- pollution --------------------------------------------------------------
info "Contamination"
# Only AppleDouble files INSIDE BENCH/ matter — they inflate the dense-directory
# entry counts that the benchmarks measure. Root-level macOS metadata
# (._MANIFEST.txt, .fseventsd) sits outside every measured directory and is
# noise, not a defect.
stray=$(find "$MNT/BENCH" -name '._*' 2>/dev/null | wc -l | tr -d ' ')
[ "$stray" = "0" ] && ok "no AppleDouble files inside BENCH/" || bad "$stray AppleDouble (._*) files inside BENCH/"

root_junk=$(find "$MNT" -maxdepth 1 -name '._*' 2>/dev/null | wc -l | tr -d ' ')
[ "$root_junk" = "0" ] || printf '    \033[33m·\033[0m %s root-level ._* file(s) — cosmetic, not measured\n' "$root_junk"

[ -e "$MNT/.Spotlight-V100" ] && bad ".Spotlight-V100 present (Spotlight is indexing this volume)" || ok "no .Spotlight-V100"
if [ -e "$MNT/.fseventsd/no_log" ]; then ok ".fseventsd suppressed via no_log"
elif [ -e "$MNT/.fseventsd" ]; then printf '    \033[33m·\033[0m .fseventsd present without no_log — cosmetic\n'
else ok "no .fseventsd"; fi

# --- free space -------------------------------------------------------------
info "Capacity"
df -h "$MNT" | tail -1 | sed 's/^/    /'
freeg=$(df -g "$MNT" | awk 'NR==2 {print $4}')
if [ "$freeg" -ge 3 ] && [ "$freeg" -le 6 ]; then ok "${freeg} GiB free (target ~4)"
else bad "${freeg} GiB free (expected ~4 — bulk fill may not have run)"; fi

echo
if [ "$FAILED" -eq 0 ]; then printf '\033[32m  PASS\033[0m — fixture is good\n\n'
else printf '\033[31m  PROBLEMS FOUND\033[0m — see ✗ above\n\n'; fi
