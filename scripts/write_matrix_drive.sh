#!/usr/bin/env bash
# DESTRUCTIVE: partitions a USB drive and writes a device-matrix build onto it
# (docs/TEST_DATA.md §16). Everything else in the pipeline runs without root; this
# and read_matrix_drive.sh are the only two steps that touch the drive.
#
#   sudo bash scripts/write_matrix_drive.sh /dev/sdX matrix/d2
#
# Drives 2-4: the whole disk is repartitioned — a new GPT with the ten partitions of
#   drive.json, in order, each named after its label.
# Drive 1:    the Mac has already made a GPT holding the EFI partition and the four
#   APFS partitions (prepare_matrix_drive_macos.sh). Those are left alone; the six
#   Linux partitions are added in the free space after them.
#
# Then every built image is written into its partition with dd. Nothing is mounted
# at any point: udisks is told to ignore the disk while this runs, so a desktop
# session cannot auto-mount (and so modify) a partition between the write and the
# first verification.
#
# Safety: refuses anything that is not a USB disk, anything with a mounted
# partition, and a disk too small; prints the plan and asks for the device name to
# be typed back unless --yes is given.
{
set -euo pipefail

DEV="${1:?Usage: sudo bash $0 /dev/sdX BUILD_DIR [--yes]}"
BUILD="${2:?Usage: sudo bash $0 /dev/sdX BUILD_DIR [--yes]}"
YES="${3:-}"
[[ $EUID -eq 0 ]] || { echo "ERROR: run with sudo."; exit 1; }
[[ -b "$DEV" ]] || { echo "ERROR: $DEV is not a block device."; exit 1; }
[[ -f "$BUILD/drive.json" ]] || { echo "ERROR: $BUILD/drive.json not found."; exit 1; }
DISK=$(basename "$DEV")
[[ "$(lsblk -dno TYPE "$DEV")" == disk ]] || { echo "ERROR: $DEV is a partition; give the whole disk."; exit 1; }
[[ "$(lsblk -dno TRAN "$DEV")" == usb ]] || { echo "ERROR: $DEV is not a USB disk (TRAN=$(lsblk -dno TRAN "$DEV"))."; exit 1; }
if lsblk -no MOUNTPOINTS "$DEV" | grep -q .; then
    echo "ERROR: partitions of $DEV are mounted:"; lsblk -o NAME,MOUNTPOINTS "$DEV"
    echo "Unmount them (udisksctl unmount -b /dev/sdXN), and turn desktop auto-mount off first:"
    echo "  gsettings set org.gnome.desktop.media-handling automount false"
    exit 1
fi

# drive.json, as shell variables
eval "$(python3 - "$BUILD/drive.json" <<'PY'
import json, shlex, sys
m = json.load(open(sys.argv[1]))
print(f"DRIVE={m['drive']} PART_BYTES={m['part_bytes']} TEST_SCALE={int(m['test_scale'])}")
rows = []
for p in m["partitions"]:
    if p["container"] == "apfs":
        continue
    if not p["image"]:
        raise SystemExit(f"{p['label']} was not built")
    code = "8300" if p["container"].startswith("luks") or p["fs"].startswith("ext") else "0700"
    rows.append(f"{p['label']}:{code}")
print("PARTS=" + shlex.quote(" ".join(rows)))
PY
)"
[[ "$TEST_SCALE" == 0 ]] || { echo "ERROR: $BUILD is a --test-scale build."; exit 1; }
SECTORS=$((PART_BYTES / 512))
DISK_BYTES=$(blockdev --getsize64 "$DEV")
NEED=$(( (10 * PART_BYTES) + 300 * 1024 * 1024 ))
(( DISK_BYTES >= NEED )) || { echo "ERROR: $DEV holds $DISK_BYTES bytes; ten partitions need $NEED."; exit 1; }

APFS_TYPE=7c3457ef-0000-11aa-aa11-00306543ecac
if [[ "$DRIVE" == 1 ]]; then
    n_apfs=$(lsblk -lno PARTTYPE "$DEV" | grep -ci "$APFS_TYPE" || true)
    [[ "$n_apfs" == 4 ]] || { echo "ERROR: Drive 1 must first be prepared on the Mac (found $n_apfs APFS partitions, need 4)."; exit 1; }
    PLAN="keep the EFI and 4 APFS partitions; add 6 partitions of $PART_BYTES bytes after them"
else
    PLAN="ERASE THE WHOLE DISK; new GPT with 10 partitions of $PART_BYTES bytes"
fi

echo "== Drive $DRIVE onto $DEV"
lsblk -o NAME,SIZE,TRAN,MODEL,SERIAL,PARTLABEL,PARTTYPENAME "$DEV"
echo
echo "Plan: $PLAN"
for row in $PARTS; do echo "  ${row%%:*}  <- $BUILD/${row%%:*}.img"; done
if [[ "$YES" != --yes ]]; then
    read -r -p "Type the device name ($DISK) to continue: " answer
    [[ "$answer" == "$DISK" ]] || { echo "Aborted."; exit 1; }
fi

# udisks ignores the disk until this script ends.
SERIAL=$(udevadm info --query=property --name="$DEV" | sed -n 's/^ID_SERIAL=//p')
RULE=/run/udev/rules.d/99-otg-matrix-ignore.rules
mkdir -p /run/udev/rules.d
echo "ENV{ID_SERIAL}==\"$SERIAL\", ENV{UDISKS_IGNORE}=\"1\", ENV{UDISKS_AUTO}=\"0\"" > "$RULE"
cleanup() { rm -f "$RULE"; udevadm control --reload; udevadm trigger --name-match="$DEV" 2>/dev/null || true; }
trap cleanup EXIT
udevadm control --reload
udevadm trigger --name-match="$DEV"; udevadm settle

if [[ "$DRIVE" != 1 ]]; then
    wipefs -a -q "$DEV"
    sgdisk --zap-all "$DEV" >/dev/null
    sgdisk -o "$DEV" >/dev/null
fi
# Numbered explicitly after whatever is already there (nothing, or the Mac's five).
num=$(sgdisk -p "$DEV" | awk '/^ +[0-9]+ / {n = $1} END {print n + 0}')
args=()
for row in $PARTS; do
    num=$((num + 1))
    args+=(-n "$num:0:+$SECTORS" -t "$num:${row##*:}" -c "$num:${row%%:*}")
done
sgdisk "${args[@]}" "$DEV" >/dev/null
partprobe "$DEV"; udevadm settle
sgdisk -p "$DEV"

for row in $PARTS; do
    label=${row%%:*}
    part=$(lsblk -lnpo NAME,PARTLABEL "$DEV" | awk -v l="$label" '$2 == l {print $1}')
    [[ -b "$part" ]] || { echo "ERROR: no partition named $label appeared."; exit 1; }
    size=$(blockdev --getsize64 "$part")
    img="$BUILD/$label.img"
    [[ "$size" == "$(stat -c %s "$img")" ]] || { echo "ERROR: $part is $size bytes, $img is $(stat -c %s "$img")."; exit 1; }
    echo "== $label -> $part"
    dd if="$img" of="$part" bs=16M oflag=direct conv=fsync status=progress
done
sync
echo
echo "Written. Read it back and verify before the drive goes anywhere near a phone:"
echo "  sudo bash scripts/read_matrix_drive.sh $DEV $BUILD-read $BUILD"
echo "  python3 scripts/verify_matrix_drive.py --build $BUILD --images $BUILD-read --accept"
exit 0
}
