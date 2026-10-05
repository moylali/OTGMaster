#!/usr/bin/env bash
# DESTRUCTIVE: partitions a USB drive and writes a device-matrix build onto it
# (docs/TEST_DATA.md §16). Everything else in the pipeline runs without root; this
# and read_matrix_drive.sh are the only two steps that touch the drive.
#
#   sudo bash scripts/write_matrix_drive.sh /dev/sdX matrix/d2
#   sudo bash scripts/write_matrix_drive.sh /dev/sdX matrix/d2 --only D2VCFAT32,D2L1FAT32
#
# --only restores just the named partitions (rewrite mode only, below), leaving the
# others as they are: after a run that damaged some partitions and not the rest.
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
# set -e alone exits without a word; a failed --only lookup did exactly that.
trap 'echo "ERROR: $0 stopped at line $LINENO: $BASH_COMMAND" >&2' ERR

USAGE="Usage: sudo bash $0 /dev/sdX BUILD_DIR [--only LABEL,...] [--yes]"
DEV="${1:?$USAGE}"
BUILD="${2:?$USAGE}"
shift 2
YES=""; ONLY=""
while (( $# )); do
    case "$1" in
        --yes) YES=--yes ;;
        --only) ONLY="${2:?$USAGE}"; shift ;;
        *) echo "$USAGE"; exit 1 ;;
    esac
    shift
done
[[ $EUID -eq 0 ]] || { echo "ERROR: run with sudo."; exit 1; }
# GNU dd where it is installed as gnudd: uutils' dd (Ubuntu's default since 25.10)
# fails iflag=direct with "IO error: Invalid input", which stopped the first real
# read-back at its first partition.
DD=$(command -v gnudd || command -v dd)
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
# Rewrite mode: every built partition already exists, named by its label. Then the
# images go back into those partitions and nothing is repartitioned: restoring a
# drive to its built state after runs (or damage) without touching anything else,
# such as Drive 1's Mac-made APFS partitions.
REWRITE=1
for row in $PARTS; do
    lsblk -lno PARTLABEL "$DEV" | grep -qx "${row%%:*}" || { REWRITE=0; break; }
done
if [[ -n "$ONLY" ]]; then
    [[ "$REWRITE" == 1 ]] || { echo "ERROR: --only rewrites existing partitions; $DEV does not have them all."; exit 1; }
    kept=""
    for label in ${ONLY//,/ }; do
        row=""
        for r in $PARTS; do [[ "${r%%:*}" != "$label" ]] || row=$r; done
        [[ -n "$row" ]] || { echo "ERROR: --only $label: not a built partition of Drive $DRIVE."; exit 1; }
        kept="$kept $row"
    done
    PARTS="${kept# }"
fi
if [[ "$REWRITE" == 1 ]]; then
    PLAN="REWRITE the $(echo $PARTS | wc -w) existing partitions named below with their built images; no repartitioning"
elif [[ "$DRIVE" == 1 ]]; then
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
# One file per disk: two drives can be written or read at once, and a shared file
# let the second run replace the first's rule and the first to finish delete both.
RULE=/run/udev/rules.d/99-otg-matrix-ignore-$(basename "$DEV").rules
mkdir -p /run/udev/rules.d
echo "ENV{ID_SERIAL}==\"$SERIAL\", ENV{UDISKS_IGNORE}=\"1\", ENV{UDISKS_AUTO}=\"0\"" > "$RULE"
# No udevadm trigger on the way out: re-triggering is what would hand the new
# partitions to the desktop's auto-mounter. They stay ignored until the next plug-in.
cleanup() { rm -f "$RULE"; udevadm control --reload; }
trap cleanup EXIT
udevadm control --reload
udevadm trigger --name-match="$DEV"; udevadm settle

if [[ "$REWRITE" == 1 ]]; then
    :   # partitions already exist
elif [[ "$DRIVE" != 1 ]]; then
    wipefs -a -q "$DEV"
    sgdisk --zap-all "$DEV" >/dev/null
    sgdisk -o "$DEV" >/dev/null
fi
if [[ "$REWRITE" != 1 ]]; then
    # Numbered explicitly after whatever is already there (nothing, or the Mac's five).
    num=$(sgdisk -p "$DEV" | awk '/^ +[0-9]+ / {n = $1} END {print n + 0}')
    args=()
    for row in $PARTS; do
        num=$((num + 1))
        args+=(-n "$num:0:+$SECTORS" -t "$num:${row##*:}" -c "$num:${row%%:*}")
    done
    sgdisk "${args[@]}" "$DEV" >/dev/null
    partprobe "$DEV"; udevadm settle
fi
sgdisk -p "$DEV"

for row in $PARTS; do
    label=${row%%:*}
    part=$(lsblk -lnpo NAME,PARTLABEL "$DEV" | awk -v l="$label" '$2 == l {print $1}')
    [[ -b "$part" ]] || { echo "ERROR: no partition named $label appeared."; exit 1; }
    size=$(blockdev --getsize64 "$part")
    img="$BUILD/$label.img"
    [[ "$size" == "$(stat -c %s "$img")" ]] || { echo "ERROR: $part is $size bytes, $img is $(stat -c %s "$img")."; exit 1; }
    echo "== $label -> $part"
    "$DD" if="$img" of="$part" bs=16M oflag=direct conv=fsync status=progress
done
sync
echo
echo "Written. Read it back and verify before the drive goes anywhere near a phone:"
echo "  sudo bash scripts/read_matrix_drive.sh $DEV $BUILD-read $BUILD"
echo "  python3 scripts/verify_matrix_drive.py --build $BUILD --images $BUILD-read --accept"
exit 0
}
