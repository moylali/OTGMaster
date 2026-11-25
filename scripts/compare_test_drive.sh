#!/usr/bin/env bash
# compare_test_drive.sh IMAGE   compare the SanDisk spare stick's first bytes with IMAGE, read-only
#
# After an image is written to the spare SanDisk Cruzer Blade for a hand test (the Windows 11
# BitLocker stick for #184), this checks every byte of the image against the stick. Granted
# passwordless in sudoers from its root-owned copy in /usr/local/lib/otgmaster/, so it accepts
# exactly one drive, the SanDisk, by USB serial AND exact size, and only images under
# /mnt/otgscratch/bitlk/. It opens the stick read-only and changes nothing.
set -euo pipefail
export PATH=/usr/sbin:/usr/bin:/sbin:/bin

SERIAL=4C530102400301116182
SIZE=7900845056
IMAGES=/mnt/otgscratch/bitlk

die() { echo "compare_test_drive: $*" >&2; exit 2; }

[ $# -eq 1 ] || die "usage: compare_test_drive.sh IMAGE (under $IMAGES)"
img=$(realpath -e -- "$1") || die "no such file: $1"
[[ "$img" == "$IMAGES"/* ]] || die "$img is not under $IMAGES"
[ -f "$img" ] && [ ! -L "$1" ] || die "$1 is not a regular file"
len=$(stat -c %s -- "$img")

mapfile -t devs < <(lsblk -dnpo NAME,SERIAL,TRAN | awk -v s="$SERIAL" '$2==s && $3=="usb"{print $1}')
[ ${#devs[@]} -eq 1 ] || die "expected one USB disk with serial $SERIAL, found ${#devs[@]}"
D=${devs[0]}
[[ "$D" =~ ^/dev/sd[a-z]+$ ]] || die "unexpected device name $D"
bytes=$(lsblk -bdno SIZE "$D")
[ "$bytes" = "$SIZE" ] || die "$D is $bytes bytes, not the $SIZE on record for the SanDisk Cruzer Blade: refusing"
[ "$len" -le "$bytes" ] || die "$img ($len bytes) is larger than the stick"
echo "SanDisk Cruzer Blade ($SERIAL) at $D, $bytes bytes; comparing the first $len bytes with $img"

if cmp -n "$len" -- "$img" "$D"; then
  echo "MATCH: all $len bytes"
else
  echo "DIFFERENT (cmp shows the first differing byte above)"
  exit 1
fi
