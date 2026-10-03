#!/usr/bin/env bash
# Device-matrix Drive 1, the Mac half (docs/TEST_DATA.md §16). macOS only.
#
# DESTRUCTIVE: erases the target disk. Makes a GPT with macOS's EFI partition and
# four APFS partitions of 5600 MiB, leaves the rest of the disk free, and fills each
# APFS volume with the matrix read set:
#
#   D1APFSCI    APFS, case-insensitive
#   D1APFSCS    APFS, case-sensitive
#   D1APFSECI   APFS (Encrypted), case-insensitive, password123
#   D1APFSECS   APFS (Encrypted), case-sensitive,   password123
#
# The six Linux partitions are added afterwards, in the free space, by
# write_matrix_drive.sh on Linux. So run this FIRST, then move the drive to Linux and
# never plug it into a Mac again: macOS writes its own files onto any volume it can
# mount, which would change partitions after their baselines are taken.
#
# Run from the repo root (it needs scripts/make_fixture_tree.py and python3):
#
#   diskutil list                                     # find the USB disk, e.g. disk4
#   bash scripts/prepare_matrix_drive_macos.sh --disk disk4
#
# Takes 15-40 minutes: 14 GB of files, 80,000 of them small, onto a USB stick. The
# manifests are also copied to matrix-mac/, for reference.
set -euo pipefail
cd "$(dirname "$0")/.."
[ "$(uname)" = Darwin ] || { echo "This script needs macOS (diskutil)."; exit 1; }

DISK=""
YES=0
while [ $# -gt 0 ]; do
    case "$1" in
        --disk) DISK="$2"; shift 2 ;;
        --yes)  YES=1; shift ;;
        *) echo "unknown argument: $1"; exit 1 ;;
    esac
done
[ -n "$DISK" ] || { echo "--disk is required (e.g. --disk disk4). Run 'diskutil list' first."; exit 1; }
DISK=${DISK#/dev/}

PASSWORD="password123"
PART_BYTES=5872025600            # 5600 MiB: scripts/matrix_layout.py PART_MIB
VOLUMES=(
    "D1APFSCI:APFS:"
    "D1APFSCS:Case-sensitive APFS:"
    "D1APFSECI:APFS:encrypted"
    "D1APFSECS:Case-sensitive APFS:encrypted"
)

command -v python3 >/dev/null && python3 -c 'import sys; assert sys.version_info >= (3, 8)' \
    || { echo "python3 3.8+ is needed: xcode-select --install"; exit 1; }
[ -f scripts/make_fixture_tree.py ] || { echo "run from the OTGMaster repo root"; exit 1; }

info() { diskutil info -plist "$1" | plutil -extract "$2" raw -o - - 2>/dev/null; }

# Safety: an external, physical, whole disk, big enough.
[ "$(info "$DISK" WholeDisk)" = true ] || { echo "$DISK is not a whole disk"; exit 1; }
[ "$(info "$DISK" Internal)" = false ] || { echo "$DISK is an internal disk — refusing"; exit 1; }
[ "$(info "$DISK" VirtualOrPhysical)" != Virtual ] || { echo "$DISK is a virtual disk — refusing"; exit 1; }
SIZE=$(info "$DISK" TotalSize)
NEED=$((10 * PART_BYTES + 300 * 1024 * 1024))
[ "$SIZE" -ge "$NEED" ] || { echo "$DISK holds $SIZE bytes; the drive needs $NEED"; exit 1; }

diskutil list "$DISK"
echo
echo "This ERASES $DISK ($(info "$DISK" MediaName), $SIZE bytes) and makes Drive 1's four APFS partitions."
if [ "$YES" != 1 ]; then
    read -r -p "Type the disk identifier ($DISK) to continue: " answer
    [ "$answer" = "$DISK" ] || { echo "Aborted."; exit 1; }
fi

# GPT, EFI (added by diskutil), four APFS partitions in this order, the rest free.
# Order matters: read_matrix_drive.sh on Linux identifies them by position.
args=()
for v in "${VOLUMES[@]}"; do
    args+=(APFS "${v%%:*}" "${PART_BYTES}B")
done
diskutil partitionDisk "$DISK" GPT "${args[@]}" "Free Space" FREE R

mkdir -p matrix-mac
for v in "${VOLUMES[@]}"; do
    name=${v%%:*}; rest=${v#*:}; personality=${rest%%:*}; encrypted=${rest#*:}
    echo "== $name ($personality${encrypted:+, encrypted})"
    container=$(info "$name" APFSContainerReference)
    [ -n "$container" ] || { echo "no APFS container found for $name"; exit 1; }
    # Recreated rather than converted: an encrypted volume created encrypted has no
    # background encryption to wait for and no half-encrypted state to capture.
    diskutil apfs deleteVolume "$name" >/dev/null
    if [ -n "$encrypted" ]; then
        diskutil apfs addVolume "$container" "$personality" "$name" -passphrase "$PASSWORD" >/dev/null
    else
        diskutil apfs addVolume "$container" "$personality" "$name" >/dev/null
    fi
    mnt=$(info "$name" MountPoint)
    [ -d "$mnt" ] || { echo "$name did not mount"; exit 1; }
    touch "$mnt/.metadata_never_index"        # ask Spotlight to stay out
    case_pair=()
    [ "$personality" = "Case-sensitive APFS" ] && case_pair=(--case-pair)
    # ${a[@]+...}: macOS's bash 3.2 treats an empty array as unbound under set -u.
    python3 scripts/make_fixture_tree.py "$mnt" --seed "$name" --size 3.5e9 ${case_pair[@]+"${case_pair[@]}"} \
        --manifest-out "matrix-mac/$name.manifest"
    # The volume must behave as named: on a case-insensitive one, CASE.txt is case.txt.
    if [ "$personality" = "Case-sensitive APFS" ]; then
        [ "$(cat "$mnt/BENCH/edge/case.txt")" = lower ] && [ "$(cat "$mnt/BENCH/edge/CASE.txt")" = UPPER ] \
            || { echo "$name is not case-sensitive"; exit 1; }
    else
        [ -e "$mnt/BENCH/EDGE/FILE WITH SPACES.TXT" ] || { echo "$name is not case-insensitive"; exit 1; }
    fi
    sync
    diskutil unmount "$name" >/dev/null
done

diskutil eject "$DISK"
echo
echo "Drive 1's APFS half is done. Move the drive to the Linux host, and do not plug it into"
echo "a Mac again. On Linux (docs/TEST_DATA.md §16): build Drive 1's other six partitions,"
echo "then write them with write_matrix_drive.sh."
