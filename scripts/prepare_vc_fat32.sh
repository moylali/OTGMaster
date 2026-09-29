#!/usr/bin/env bash
# Prepare a VeraCrypt + FAT32 benchmark drive, on Linux.
#
# Usage: sudo bash scripts/prepare_vc_fat32.sh /dev/sdX [--fill-to-free GIB]
#
# The equivalent of prepare_test_usb.sh --veracrypt --fs fat32, which only runs
# on macOS (newfs_msdos, diskutil). This is the Linux path, and it produces a
# drive with the same shape: one VeraCrypt volume over the whole disk, FAT32
# inside it with 4 KiB clusters, and the BENCH/ fixture tree the benchmark reads.
#
#   AES / SHA-512 / PIM 1, password password123 — matching every other fixture.
#   4 KiB clusters on purpose: small, so the FAT is large and the dense-directory
#   and allocation paths are actually stressed (docs/TEST_DATA.md §7.8).
#
# Unlike the older script it writes BENCH/MANIFEST.txt with real sha256 hashes,
# so the benchmark's `fixtures` section has something to check. Drives prepared
# by prepare_test_usb.sh carry a manifest with no hashes, and that section then
# reports "nothing to check" — which is how a corrupt drive passed every check
# it was given.
#
# --fill-to-free GIB writes FILL/ padding until only GIB gigabytes remain free.
# The original drive was filled to ~4 GiB free, which is what exercises
# allocation against a nearly-full volume. It is slow — tens of minutes at USB
# write speeds — so it is off by default and worth enabling for a drive that
# will be used to judge the allocator.
#
# DESTRUCTIVE. Validates the target first and asks before writing.

set -uo pipefail

DEV="${1:?Usage: sudo bash $0 /dev/sdX [--fill-to-free GIB]}"; shift || true
FILL_TO_FREE=""
while [[ $# -gt 0 ]]; do
    case "$1" in
        --fill-to-free) FILL_TO_FREE="$2"; shift 2 ;;
        *) echo "unknown option: $1"; exit 1 ;;
    esac
done

VC_PASS=password123
VC_PIM=1
VC_ENC="AES"
VC_HASH="SHA-512"
MNT=/mnt/otgVcFat

die() { echo "ERROR: $*" >&2; exit 1; }

[[ $EUID -eq 0 ]] || die "run with sudo."
for t in veracrypt mkfs.vfat parted wipefs partprobe sha256sum; do
    command -v "$t" >/dev/null || die "$t not found."
done

# ---------------------------------------------------------------------------
# Validate the target before touching it — same checks as verify/reformat
# ---------------------------------------------------------------------------
[[ -b "$DEV" ]] || die "$DEV is not a block device."
TYPE=$(lsblk -dno TYPE "$DEV" | tr -d ' ')
[[ "$TYPE" == "disk" ]] || die "$DEV is a '$TYPE'; pass the whole disk, not a partition."
TRAN=$(lsblk -dno TRAN "$DEV" | tr -d ' ')
[[ "$TRAN" == "usb" ]] || die "$DEV is TRAN='$TRAN', not usb. Refusing."

echo "=== Prepare VeraCrypt + FAT32 on $DEV ==="
lsblk -o NAME,SIZE,FSTYPE,LABEL,MODEL "$DEV"
echo
echo "This DESTROYS everything above."
read -rp "Type YES to continue: " CONFIRM
[[ "$CONFIRM" == "YES" ]] || { echo "Aborted."; exit 1; }

cleanup() {
    mountpoint -q "$MNT" 2>/dev/null && umount "$MNT"
    veracrypt -t -d "${DEV}1" --non-interactive >/dev/null 2>&1
    return 0
}
trap cleanup EXIT

# ---------------------------------------------------------------------------
echo
echo "[0] Releasing anything holding the device..."
for p in "${DEV}"[0-9]*; do
    [[ -b "$p" ]] || continue
    veracrypt -t -d "$p" --non-interactive >/dev/null 2>&1
    mount | grep -q "^$p " && umount "$p"
done

echo
echo "[1] Partitioning (single partition, whole disk)..."
wipefs -a "$DEV" >/dev/null
parted -s "$DEV" mklabel msdos
parted -s "$DEV" mkpart primary fat32 1MiB 100%
partprobe "$DEV"; sleep 2
[[ -b "${DEV}1" ]] || die "${DEV}1 did not appear."

echo
echo "[2] Creating VeraCrypt volume ($VC_ENC / $VC_HASH, PIM $VC_PIM)..."
# --quick matters: without it VeraCrypt encrypts every free byte of a 58 GB
# partition before a single fixture is written.
veracrypt -t -c "${DEV}1" \
    --volume-type=normal --encryption="$VC_ENC" --hash="$VC_HASH" \
    --filesystem=none --pim="$VC_PIM" --keyfiles="" \
    --random-source=/dev/urandom \
    --password="$VC_PASS" --quick --non-interactive \
    || die "VeraCrypt volume creation failed"

veracrypt -t --mount "${DEV}1" \
    --password="$VC_PASS" --pim="$VC_PIM" --keyfiles="" \
    --protect-hidden=no --filesystem=none --non-interactive \
    || die "could not attach the volume just created"
VC_DEV=$(veracrypt -t -l 2>/dev/null | awk -v p="${DEV}1" '$2 == p { print $3 }')
[[ -b "$VC_DEV" ]] || die "attached but no device node"
echo "  decrypted device: $VC_DEV"

echo
echo "[3] mkfs.vfat -F 32, 4 KiB clusters..."
mkfs.vfat -F 32 -s 8 -n VCFAT "$VC_DEV" >/dev/null || die "mkfs.vfat failed"

mkdir -p "$MNT"
mount "$VC_DEV" "$MNT" || die "mount failed"

# ---------------------------------------------------------------------------
echo
echo "[4] Writing fixtures..."
B="$MNT/BENCH"
mkdir -p "$B/large" "$B/dense_short" "$B/dense_lfn" "$B/nested" "$B/reports"

for spec in "seq_2g.bin 2048" "seq_1g.bin 1024" "seq_256m.bin 256"; do
    set -- $spec
    echo "  large/$1 (${2} MiB)"
    dd if=/dev/urandom of="$B/large/$1" bs=1M count="$2" status=progress
done

echo "  dense_short/ and dense_lfn/, 10000 files each"
sh -c "cd '$B/dense_short' && for i in \$(seq -w 1 10000); do printf 'x' > f\$i.dat; done"
sh -c "cd '$B/dense_lfn' && for i in \$(seq -w 1 10000); do
    printf 'x' > \"a_file_with_a_deliberately_long_name_for_lfn_testing_\$i.dat\"
done"

echo "  nested/, depth 10"
D="$B/nested"
for l in $(seq -w 1 10); do
    D="$D/level_$l"; mkdir -p "$D"
    printf 'marker' > "$D/marker.dat"
done
dd if=/dev/urandom of="$D/leaf_at_depth_10.dat" bs=4k count=1 status=none
sync

if [[ -n "$FILL_TO_FREE" ]]; then
    echo
    echo "[4b] Filling until ${FILL_TO_FREE} GiB free (this is the slow part)..."
    mkdir -p "$MNT/FILL"
    n=0
    while :; do
        FREE_MIB=$(df -BM --output=avail "$MNT" | tail -1 | tr -dc '0-9')
        (( FREE_MIB > FILL_TO_FREE * 1024 )) || break
        n=$((n + 1))
        dd if=/dev/urandom of="$(printf '%s/FILL/fill_%04d.bin' "$MNT" "$n")" \
           bs=1M count=1024 status=none || break
        printf '\r  %d GiB written, %d MiB free   ' "$n" "$FREE_MIB"
    done
    echo
    sync
fi

# ---------------------------------------------------------------------------
echo
echo "[5] Writing MANIFEST.txt with real hashes..."
# Same format the benchmark parses. The directory records must end in "files" —
# the older format wrote "10 levels" there beside a file's hash, and the
# benchmark skips such lines rather than comparing them. The listing hash strips
# the trailing newline because the benchmark joins entries with \n as a
# separator, not a terminator.
{
    echo "# OTG Master benchmark fixture"
    echo "# generated: $(date -u +%Y-%m-%dT%H:%M:%SZ)"
    echo "# volume:    VCFAT (VeraCrypt $VC_ENC/$VC_HASH PIM $VC_PIM + FAT32)"
    echo
    printf '# path\tbytes\tsha256\n'
    for f in "$B"/large/*; do
        [[ -f "$f" ]] || continue
        printf 'large/%s\t%s\t%s\n' "$(basename "$f")" \
            "$(stat -c%s "$f")" "$(sha256sum "$f" | cut -d' ' -f1)"
    done
    for d in dense_short dense_lfn; do
        printf '%s/\t%s files\t%s\n' "$d" \
            "$(find "$B/$d" -maxdepth 1 -type f | wc -l | tr -d ' ')" \
            "$(cd "$B/$d" && for f in *; do
                if   [ -d "$f" ]; then printf '%s\tdir\n' "$f"
                elif [ -f "$f" ]; then printf '%s\t%s\n' "$f" "$(stat -c%s "$f")"
                fi
               done | LC_ALL=C sort | head -c -1 | sha256sum | cut -d' ' -f1)"
    done
    find "$B/nested" -type f | while read -r L; do
        printf '%s\t%s\t%s\n' "${L#"$B"/}" \
            "$(stat -c%s "$L")" "$(sha256sum "$L" | cut -d' ' -f1)"
    done
} > "$B/MANIFEST.txt"
sync

echo
cat "$B/MANIFEST.txt"
df -h "$MNT" | tail -1

echo
echo "=== Done ==="
echo "Password $VC_PASS, PIM $VC_PIM, $VC_ENC / $VC_HASH"
echo "Verify with: sudo bash scripts/verify_volume.sh ${DEV}1"
