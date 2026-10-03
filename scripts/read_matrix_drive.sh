#!/usr/bin/env bash
# Copies every partition of a device-matrix drive to an image file, for
# verify_matrix_drive.py to check without root (docs/TEST_DATA.md §16).
# READ-ONLY: the drive is only ever read, and udisks is told to ignore it while
# this runs so a desktop session cannot auto-mount (and so modify) a partition.
#
#   sudo bash scripts/read_matrix_drive.sh /dev/sdX matrix/d2-read matrix/d2
#   python3 scripts/verify_matrix_drive.py --build matrix/d2 --images matrix/d2-read
#
# Partitions are found by GPT name (the label), except Drive 1's four APFS ones,
# which the Mac names after nothing: they are taken in disk order, which is the
# order prepare_matrix_drive_macos.sh creates them in. Holes are kept sparse, and
# the copies are handed to the invoking user. About 10-15 minutes per drive over
# USB 3.
{
set -euo pipefail

DEV="${1:?Usage: sudo bash $0 /dev/sdX OUT_DIR BUILD_DIR}"
OUT="${2:?Usage: sudo bash $0 /dev/sdX OUT_DIR BUILD_DIR}"
BUILD="${3:?Usage: sudo bash $0 /dev/sdX OUT_DIR BUILD_DIR}"
[[ $EUID -eq 0 ]] || { echo "ERROR: run with sudo."; exit 1; }
[[ -b "$DEV" ]] || { echo "ERROR: $DEV is not a block device."; exit 1; }
[[ "$(lsblk -dno TYPE "$DEV")" == disk ]] || { echo "ERROR: give the whole disk, not a partition."; exit 1; }
[[ -f "$BUILD/drive.json" ]] || { echo "ERROR: $BUILD/drive.json not found."; exit 1; }
if lsblk -no MOUNTPOINTS "$DEV" | grep -q .; then
    echo "ERROR: partitions of $DEV are mounted — a mounted partition may already have changed:"
    lsblk -o NAME,MOUNTPOINTS "$DEV"
    echo "Unmount them, and turn desktop auto-mount off before plugging the drive in:"
    echo "  gsettings set org.gnome.desktop.media-handling automount false"
    exit 1
fi

SERIAL=$(udevadm info --query=property --name="$DEV" | sed -n 's/^ID_SERIAL=//p')
# One file per disk: two drives can be written or read at once, and a shared file
# let the second run replace the first's rule and the first to finish delete both.
RULE=/run/udev/rules.d/99-otg-matrix-ignore-$(basename "$DEV").rules
mkdir -p /run/udev/rules.d
echo "ENV{ID_SERIAL}==\"$SERIAL\", ENV{UDISKS_IGNORE}=\"1\", ENV{UDISKS_AUTO}=\"0\"" > "$RULE"
cleanup() { rm -f "$RULE"; udevadm control --reload; }
trap cleanup EXIT
udevadm control --reload

LABELS=$(python3 -c 'import json,sys; print(" ".join(p["label"] for p in json.load(open(sys.argv[1]))["partitions"]))' "$BUILD/drive.json")
APFS_LABELS=$(python3 -c 'import json,sys; print(" ".join(p["label"] for p in json.load(open(sys.argv[1]))["partitions"] if p["container"] == "apfs"))' "$BUILD/drive.json")
APFS_TYPE=7c3457ef-0000-11aa-aa11-00306543ecac
mapfile -t APFS_PARTS < <(lsblk -lnpo NAME,PARTTYPE "$DEV" | awk -v t="$APFS_TYPE" 'tolower($2) == t {print $1}')
read -r -a WANT_APFS <<< "$APFS_LABELS"
[[ ${#APFS_PARTS[@]} == "${#WANT_APFS[@]}" ]] || {
    echo "ERROR: $DEV has ${#APFS_PARTS[@]} APFS partitions; drive.json expects ${#WANT_APFS[@]}."; exit 1; }

OWNER="${SUDO_USER:-root}"
sudo -u "$OWNER" mkdir -p "$OUT"
NEED=$(lsblk -bdno SIZE "$DEV")
FREE=$(df -B1 --output=avail "$OUT" | tail -1)
(( FREE > NEED )) || echo "WARNING: $OUT has $FREE bytes free; the drive is $NEED (sparse copies may still fit)."

i=0
for label in $LABELS; do
    if [[ " $APFS_LABELS " == *" $label "* ]]; then
        part=${APFS_PARTS[$i]}; i=$((i + 1))
    else
        part=$(lsblk -lnpo NAME,PARTLABEL "$DEV" | awk -v l="$label" '$2 == l {print $1}')
    fi
    [[ -b "$part" ]] || { echo "ERROR: no partition for $label on $DEV."; exit 1; }
    echo "== $label <- $part"
    dd if="$part" of="$OUT/$label.img" bs=16M iflag=direct conv=sparse status=progress
    chown "$OWNER": "$OUT/$label.img"
done
echo
echo "Read. Now, as yourself:"
echo "  python3 scripts/verify_matrix_drive.py --build $BUILD --images $OUT"
exit 0
}
