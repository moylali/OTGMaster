#!/usr/bin/env bash
# format_test_drive.sh SERIAL [--full] [--label NAME]   format one spare test stick as exFAT
# format_test_drive.sh --check SERIAL                    read-only fsck.exfat of that stick
#
# For the spare USB sticks used in hand testing (2026-11-22). Granted passwordless in sudoers
# from its root-owned copy in /usr/local/lib/otgmaster/, so it refuses every drive that is not
# listed below: a drive is accepted only when its USB serial AND its exact size in bytes match,
# because the Netac's serial (0000000000003236) looks like a generic one another stick could share.
#
# A format wipes every signature, writes one MBR partition (type 7) over the whole stick and makes
# exFAT on it. --full first writes zeros over every sector.
set -euo pipefail
export PATH=/usr/sbin:/usr/bin:/sbin:/bin

declare -A SIZE=( [0000000000003236]=4008706048 [4C530102400301116182]=7900845056 )
declare -A NAME=( [0000000000003236]="Netac OnlyDisk U188" [4C530102400301116182]="SanDisk Cruzer Blade" )
declare -A LABEL=( [0000000000003236]=NETAC [4C530102400301116182]=SANDISK )

die() { echo "format_test_drive: $*" >&2; exit 1; }

check=0; full=0; serial=""; label=""
while [ $# -gt 0 ]; do
  case "$1" in
    --check) check=1 ;;
    --full) full=1 ;;
    --label) shift; label=${1:-} ;;
    -*) die "unknown option $1" ;;
    *) [ -z "$serial" ] || die "one serial only"; serial=$1 ;;
  esac
  shift
done
[ -n "$serial" ] || die "usage: format_test_drive.sh SERIAL [--full] [--label NAME] | --check SERIAL"
[ -n "${SIZE[$serial]:-}" ] || die "serial $serial is not one of the allowed test sticks: ${!SIZE[*]}"
label=${label:-${LABEL[$serial]}}
[[ "$label" =~ ^[A-Za-z0-9_-]{1,11}$ ]] || die "label must be 1-11 letters, digits, - or _"

# Exactly one USB disk with that serial, and it must be the size on record.
mapfile -t devs < <(lsblk -dnpo NAME,SERIAL,TRAN | awk -v s="$serial" '$2==s && $3=="usb"{print $1}')
[ ${#devs[@]} -eq 1 ] || die "expected one USB disk with serial $serial, found ${#devs[@]}"
D=${devs[0]}
[[ "$D" =~ ^/dev/sd[a-z]+$ ]] || die "unexpected device name $D"
bytes=$(lsblk -bdno SIZE "$D")
[ "$bytes" = "${SIZE[$serial]}" ] || die "$D is $bytes bytes, not the ${SIZE[$serial]} on record for ${NAME[$serial]}: refusing"
echo "${NAME[$serial]} ($serial) at $D, $bytes bytes"

if [ $check -eq 1 ]; then
  P=${D}1
  [ -b "$P" ] || die "$D has no partition 1"
  [ "$(blkid -o value -s TYPE "$P" || true)" = exfat ] || die "$P is not exFAT"
  if findmnt -rno TARGET -S "$P" >/dev/null; then die "$P is mounted; unmount it first"; fi
  fsck.exfat -n "$P"
  exit $?
fi

# Nothing on it may be in use.
while read -r part; do
  if findmnt -rno TARGET -S "$part" >/dev/null; then umount "$part"; echo "unmounted $part"; fi
done < <(lsblk -lnpo NAME "$D")
[ -z "$(ls -A /sys/block/${D#/dev/}/holders)" ] || die "$D is held by device-mapper; close it first"

wipefs -a "$D" >/dev/null
if [ $full -eq 1 ]; then
  echo "writing zeros over all $bytes bytes"
  dd if=/dev/zero of="$D" bs=4M oflag=direct status=progress 2>&1 | tr '\r' '\n' | tail -2 || true
  sync
fi
echo 'type=7' | sfdisk --quiet "$D"
udevadm settle
[ -b "${D}1" ] || die "partition ${D}1 did not appear"
mkfs.exfat -n "$label" "${D}1"
udevadm settle
lsblk -o NAME,SIZE,FSTYPE,LABEL,UUID "$D"
