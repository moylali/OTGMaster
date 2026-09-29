#!/bin/bash
# Swap the inner filesystem of an existing LUKS partition to exFAT, in place.
#
# Usage: sudo ./scripts/reformat_to_exfat.sh /dev/sdX4 [/dev/sdY4 ...]
#
# Opens each LUKS container, runs mkfs.exfat inside it, repopulates the BENCH/
# tree and closes up. The container, its header and its keyslots are untouched,
# so the volume keeps the identity the app has already been tested against.
#
# Why it exists: exFAT-inside-LUKS was added to the matrix after the drives had
# been built, and rebuilding a prepared drive to change one inner filesystem
# throws away everything else on it for no reason.
#
# SAFETY
#
# This script used to name /dev/sdb4 and /dev/sdc4 in its last two lines, with no
# argument, no prompt and no check on what those names referred to. Device
# letters are assigned in enumeration order and move between boots, between
# machines, and whenever a drive is plugged in a different order — so a stale
# letter silently reformatted whatever now answered to it.
#
# Targets are now arguments, and each one must pass every check below before
# anything is written. A target that fails any check aborts the whole run, before
# the first mkfs, so a typo cannot destroy one drive on its way to another:
#
#   - it is a block device, and a partition rather than a whole disk;
#   - its parent disk is USB (TRAN=usb). This is the check that matters: it
#     refuses internal and system disks outright, whatever they are called today;
#   - it holds a LUKS container, which no drive outside this test set does;
#   - neither it nor its decrypted mapping is currently mounted.
#
# It then prints what it found — disk, model, size, LUKS version — and requires
# an uppercase YES. An EXIT trap unmounts and closes on any failure, so a crash
# part-way no longer leaves the device busy and needing
# scripts/cleanup_and_parallel.sh before the next attempt.
#
# PART OF THE SMALL-MEDIA LUKS FIXTURE SET
#
# Two families of test media exist in this project, for different questions:
#
#   scripts/prepare_drive_{a,b,c,d}.sh   64 GB physical drives, 2 GiB fixtures,
#                                        hash manifests. Answer "how fast, and
#                                        does it stay correct under load".
#   this set                             ~200 MB loopback images or small
#                                        partitions, tiny fixtures. Answer "does
#                                        the app parse and handle this format".
#
# The small set covers far more *combinations* than the big drives do — LUKS1 and
# LUKS2 over ext2, ext3, ext4 and FAT32, plus a mixed-container disk — and
# regenerates in seconds instead of an hour, which is what makes it the one to
# reach for when adding a container format or a filesystem. The big drives cannot
# cover that matrix; there are only four of them and each takes an hour to build.
#
# What this set deliberately does NOT do:
#   - no MANIFEST.txt, so the benchmark's `fixtures` section has nothing to check
#     against and will report "nothing to check". These images prove a volume is
#     *handled*, not that its bytes survived a write. Use a big drive plus
#     scripts/regen_manifest.sh for that.
#   - no throughput meaning. 50 MB partitions and 64 MB fixtures sit inside any
#     cache; numbers measured here are not comparable to anything in
#     docs/BENCHMARK_RESULTS.md and must not be quoted as figures.
#
# See docs/TEST_DATA.md for the per-case contract these images are consumed under.

set -euo pipefail

PASSWORD="password123"

die() { echo "ERROR: $*" >&2; exit 1; }

[[ $EUID -eq 0 ]] || die "run with sudo."
[[ $# -ge 1 ]] || die "usage: sudo $0 /dev/sdX4 [/dev/sdY4 ...]"

for t in cryptsetup mkfs.exfat lsblk; do
    command -v "$t" >/dev/null || die "$t not found."
done

# ---------------------------------------------------------------------------
# Validate every target before touching any of them
# ---------------------------------------------------------------------------
TARGETS=("$@")

for PART in "${TARGETS[@]}"; do
    [[ -b "$PART" ]] || die "$PART is not a block device."

    TYPE=$(lsblk -dno TYPE "$PART" 2>/dev/null | tr -d ' ')
    [[ "$TYPE" == "part" ]] || die "$PART is a '$TYPE', not a partition. Refusing."

    # The parent disk, resolved through sysfs rather than by trimming digits off
    # the name — /dev/nvme0n1p4 and /dev/mmcblk0p4 do not trim the same way.
    DISK=$(lsblk -no PKNAME "$PART" 2>/dev/null | head -1 | tr -d ' ')
    [[ -n "$DISK" ]] || die "could not determine the parent disk of $PART."

    TRAN=$(lsblk -dno TRAN "/dev/$DISK" 2>/dev/null | tr -d ' ')
    [[ "$TRAN" == "usb" ]] \
        || die "/dev/$DISK is TRAN='$TRAN', not usb. This script only reformats USB test media."

    cryptsetup isLuks "$PART" 2>/dev/null \
        || die "$PART is not a LUKS container. Refusing — this is not one of the prepared test partitions."

    mount | grep -q "^$PART " && die "$PART is mounted. Unmount it first."
done

# ---------------------------------------------------------------------------
# Show what was found, then require confirmation
# ---------------------------------------------------------------------------
echo "About to REFORMAT the inner filesystem of:"
echo
printf '  %-14s %-10s %-22s %-8s %s\n' PARTITION DISK MODEL SIZE LUKS
for PART in "${TARGETS[@]}"; do
    DISK=$(lsblk -no PKNAME "$PART" | head -1 | tr -d ' ')
    printf '  %-14s %-10s %-22s %-8s %s\n' \
        "$PART" "/dev/$DISK" \
        "$(lsblk -dno MODEL "/dev/$DISK" | sed 's/  *$//')" \
        "$(lsblk -dno SIZE "$PART" | tr -d ' ')" \
        "$(cryptsetup luksDump "$PART" 2>/dev/null | awk '/^Version:/ {print $2; exit}')"
done
echo
echo "Everything inside these containers will be destroyed. The LUKS headers and"
echo "keyslots are left alone, so the volumes keep their identity."
read -rp "Type YES to continue: " CONFIRM
[[ "$CONFIRM" == "YES" ]] || { echo "Aborted."; exit 1; }

# ---------------------------------------------------------------------------
# Fixture tree. Small on purpose — see the header.
# ---------------------------------------------------------------------------
populate_data() {
    local MNT=$1
    echo "  populating $MNT ..."
    local BENCH="$MNT/BENCH"
    mkdir -p "$BENCH/large" "$BENCH/dense_short" "$BENCH/dense_lfn" "$BENCH/nested"

    dd if=/dev/urandom of="$BENCH/large/seq_64m.bin" bs=1048576 count=64 2>/dev/null
    dd if=/dev/urandom of="$BENCH/large/seq_16m.bin" bs=1048576 count=16 2>/dev/null

    local LINE="0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcde"
    local BLOCK=""
    for i in {1..64}; do BLOCK="${BLOCK}${LINE}"; done
    for i in {1..50}; do
        echo -n "$BLOCK" > "$(printf '%s/dense_short/%05d.dat' "$BENCH" "$i")"
        echo -n "$BLOCK" > "$(printf '%s/dense_lfn/densefile_%05d_padding_for_lfn_entries.dat' "$BENCH" "$i")"
    done

    local DEEP="$BENCH/nested"
    for i in {1..10}; do
        DEEP="$DEEP/level_$(printf '%02d' $i)"
        mkdir -p "$DEEP"
        echo -n "$BLOCK" > "$DEEP/marker.dat"
    done
    echo -n "$BLOCK" > "$DEEP/leaf_at_depth_10.dat"
    sync
}

# ---------------------------------------------------------------------------
# Reformat, with the mount and the mapping released however we leave
# ---------------------------------------------------------------------------
CUR_MNT=""
CUR_MAP=""

release() {
    [[ -n "$CUR_MNT" ]] && mountpoint -q "$CUR_MNT" && umount "$CUR_MNT"
    [[ -n "$CUR_MNT" ]] && rm -rf "$CUR_MNT"
    [[ -n "$CUR_MAP" ]] && [[ -e "/dev/mapper/$CUR_MAP" ]] && cryptsetup luksClose "$CUR_MAP"
    CUR_MNT=""
    CUR_MAP=""
    return 0
}
trap release EXIT

reformat_luks_inner() {
    local part=$1
    echo "Reformatting inner FS of $part to exFAT..."

    CUR_MAP="luks_exfat_$$"
    echo -n "$PASSWORD" | cryptsetup luksOpen "$part" "$CUR_MAP" -

    local inner
    inner=$(blkid -o value -s TYPE "/dev/mapper/$CUR_MAP" 2>/dev/null || true)
    echo "  was: ${inner:-unrecognised}"

    mkfs.exfat "/dev/mapper/$CUR_MAP"

    CUR_MNT="/tmp/mnt_exfat_$$"
    mkdir -p "$CUR_MNT"
    mount "/dev/mapper/$CUR_MAP" "$CUR_MNT"
    populate_data "$CUR_MNT"

    release
    echo "  done: $part now exFAT"
}

for PART in "${TARGETS[@]}"; do
    reformat_luks_inner "$PART"
done

echo
echo "Reformatted ${#TARGETS[@]} partition(s) to exFAT: ${TARGETS[*]}"
