#!/usr/bin/env bash
# Drive D — 3 partitions: VeraCrypt+ext4, plain ext4, plain NTFS
# See docs/TEST_DATA.md §12 for full spec.
#
# Usage: sudo bash scripts/prepare_drive_d.sh /dev/sdX
#
# Partition layout (MBR, sizes scale with the device):
#   p1  VeraCrypt / ext4   label VCEXT4     40%   AES / SHA-512 / PIM 1
#   p2  plain      / ext4   label PLAINEXT4  30%   no encryption
#   p3  plain      / NTFS   label NTFSPLAIN  30%   no encryption
#
# Why this drive exists:
#   p1 exercises ext4 writes through the VeraCrypt crypto layer — the LUKS
#      drives (A/B/C) cover LUKS only, so the VeraCrypt+ext4 combination was
#      previously untested on hardware.
#   p2 is the control: identical ext4 fixtures with no crypto layer at all, so
#      a failure can be attributed to the ext4 code or to the cipher, not both.
#   p3 must be REFUSED by the app. NTFS is unsupported; this proves the refusal
#      is clean rather than a mis-detection that then corrupts the volume.
#
# p1 and p2 get byte-identical fixture trees (same sizes, same structure) so the
# benchmark figures are directly comparable with each other and with drive C.

set -euo pipefail

DEV="${1:?Usage: sudo bash $0 /dev/sdX}"
VC_PASS=password123     # matches scripts/generate_testdata.sh
VC_PIM=1                # 16,000 PBKDF2 iterations — 500,000 hangs a phone
VC_ENC="AES"
VC_HASH="SHA-512"
FIXTURE_DIR=/root/otg-luks-fixtures

BIG_MIB=${BIG_MIB:-2048}    # matches drive C — exercises the >=2 GiB read boundary
SMALL_MIB=${SMALL_MIB:-256}
DENSE_N=${DENSE_N:-10000}

# ---------------------------------------------------------------------------
# Safety checks
# ---------------------------------------------------------------------------
[[ $EUID -eq 0 ]] || { echo "ERROR: run with sudo."; exit 1; }

TRAN=$(lsblk -d -o TRAN --noheadings "$DEV" 2>/dev/null | tr -d ' ')
[[ "$TRAN" == "usb" ]] || { echo "ERROR: $DEV is not USB (TRAN=$TRAN). Aborting."; exit 1; }

SIZE_B=$(lsblk -d -o SIZE --noheadings --bytes "$DEV" | tr -d ' ')
# 40% of the device must hold BIG+SMALL plus ext4 overhead and write headroom.
MIN_B=$(( (BIG_MIB + SMALL_MIB + 2048) * 1048576 * 100 / 40 ))
(( SIZE_B > MIN_B )) || {
    echo "ERROR: $DEV is $((SIZE_B/1024/1024)) MiB; need > $((MIN_B/1024/1024)) MiB"
    echo "       for BIG_MIB=$BIG_MIB SMALL_MIB=$SMALL_MIB. Lower those to proceed."
    exit 1
}

for t in veracrypt mkfs.ext4 mkfs.ntfs parted wipefs; do
    command -v "$t" >/dev/null || { echo "ERROR: $t not found."; exit 1; }
done

echo "=== Drive D: $DEV ($((SIZE_B/1024/1024/1024)) GiB) ==="
lsblk "$DEV"
echo

# ---------------------------------------------------------------------------
# Stage 0: Release anything holding the device
# ---------------------------------------------------------------------------
echo "[0] Cleaning up existing mounts, mappers and VeraCrypt volumes..."
for part in "${DEV}"[0-9]*; do
    [[ -b "$part" ]] || continue
    veracrypt -t -d "$part" --non-interactive >/dev/null 2>&1 || true
done
for mapper in /dev/mapper/*; do
    [[ -e "$mapper" ]] || continue
    name=$(basename "$mapper")
    [[ "$name" == "control" ]] && continue
    backing=$(dmsetup deps -o devname "$mapper" 2>/dev/null \
        | grep -o 'sd[a-z][0-9]*' | head -1 || true)
    if [[ -n "$backing" && "$backing" == "$(basename "$DEV")"* ]]; then
        echo "  closing mapper: $name"
        umount "$mapper" 2>/dev/null || true
        cryptsetup close "$name" 2>/dev/null || true
    fi
done
for part in "${DEV}"[0-9]*; do
    [[ -b "$part" ]] || continue
    mount | grep -q "^$part " && { echo "  unmounting: $part"; umount "$part"; }
done

# ---------------------------------------------------------------------------
# Confirmation — p1 is indistinguishable from random once written, so there is
# no way to detect "already prepared" and resume. Every run starts from zero.
# ---------------------------------------------------------------------------
echo
echo "Current contents of $DEV:"
lsblk -o NAME,SIZE,FSTYPE,LABEL "$DEV"
echo
echo "This DESTROYS everything above."
read -rp "Type YES to continue: " CONFIRM
[[ "$CONFIRM" == "YES" ]] || { echo "Aborted."; exit 1; }

# ---------------------------------------------------------------------------
# Stage 1: Partition
# ---------------------------------------------------------------------------
echo
echo "[1] Partitioning $DEV (MBR, 3 primary partitions)..."
wipefs -a "$DEV"
parted -s "$DEV" mklabel msdos
parted -s "$DEV" mkpart primary ext4 1MiB 40%
parted -s "$DEV" mkpart primary ext4 40%   70%
parted -s "$DEV" mkpart primary ntfs 70%   100%
partprobe "$DEV"
sleep 2
lsblk "$DEV"

for n in 1 2 3; do
    [[ -b "${DEV}${n}" ]] || { echo "ERROR: ${DEV}${n} did not appear."; exit 1; }
done

# ---------------------------------------------------------------------------
# Stage 2: VeraCrypt volume on p1
# ---------------------------------------------------------------------------
echo
echo "[2] Creating VeraCrypt volume on ${DEV}1 ($VC_ENC / $VC_HASH, PIM $VC_PIM)..."
# --quick is essential: without it VeraCrypt encrypts every free byte of the
# partition before a single fixture is written.
veracrypt -t -c "${DEV}1" \
    --volume-type=normal \
    --encryption="$VC_ENC" --hash="$VC_HASH" \
    --filesystem=none --pim="$VC_PIM" --keyfiles="" \
    --random-source=/dev/urandom \
    --password="$VC_PASS" --quick --non-interactive

echo "  attaching decrypted volume..."
veracrypt -t --mount "${DEV}1" \
    --password="$VC_PASS" --pim="$VC_PIM" --keyfiles="" \
    --protect-hidden=no --filesystem=none --non-interactive

VC_DEV=$(veracrypt -t -l 2>/dev/null | awk -v p="${DEV}1" '$2 == p { print $3 }')
[[ -n "$VC_DEV" && -b "$VC_DEV" ]] || { echo "ERROR: no VeraCrypt device node."; exit 1; }
echo "  decrypted device: $VC_DEV"

mkdir -p "$FIXTURE_DIR"
veracrypt -t --volume-properties "${DEV}1" --non-interactive \
    > "$FIXTURE_DIR/veracrypt-D.txt" 2>&1 || true
{
    echo "# drive D p1 VeraCrypt parameters"
    echo "encryption: $VC_ENC"
    echo "hash:       $VC_HASH"
    echo "pim:        $VC_PIM"
    echo "password:   $VC_PASS"
} >> "$FIXTURE_DIR/veracrypt-D.txt"
echo "  saved: $FIXTURE_DIR/veracrypt-D.txt"

# ---------------------------------------------------------------------------
# Stage 3: Filesystems
# ---------------------------------------------------------------------------
echo
echo "[3] Formatting filesystems..."
# Same flags as drive C so the ext4 geometry is comparable.
mkfs.ext4 -F -L VCEXT4 -m 0 \
    -E lazy_itable_init=0,lazy_journal_init=0 "$VC_DEV"
mkfs.ext4 -F -L PLAINEXT4 -m 0 \
    -E lazy_itable_init=0,lazy_journal_init=0 "${DEV}2"
mkfs.ntfs -f -L NTFSPLAIN "${DEV}3"

mkdir -p /mnt/otgD1 /mnt/otgD2 /mnt/otgD3
mount "$VC_DEV"  /mnt/otgD1
mount "${DEV}2"  /mnt/otgD2
if ! mount -t ntfs3 "${DEV}3" /mnt/otgD3 2>/dev/null; then
    mount -t ntfs-3g "${DEV}3" /mnt/otgD3
fi
echo "  mounted: /mnt/otgD1 /mnt/otgD2 /mnt/otgD3"

# ---------------------------------------------------------------------------
# Stage 4: Fixtures
# ---------------------------------------------------------------------------
# One function, called twice, so p1 and p2 cannot drift apart.
populate() {
    local B="$1/BENCH"
    mkdir -p "$B/large" "$B/dense_short" "$B/dense_lfn" "$B/nested" "$B/reports"

    dd if=/dev/urandom of="$B/large/seq_${BIG_MIB}m.bin"   bs=1M count="$BIG_MIB"   status=progress
    dd if=/dev/urandom of="$B/large/seq_${SMALL_MIB}m.bin" bs=1M count="$SMALL_MIB" status=none

    sh -c "cd '$B/dense_short' && for i in \$(seq -w 1 $DENSE_N); do
        printf 'x' > f\$i.dat
    done"
    sh -c "cd '$B/dense_lfn' && for i in \$(seq -w 1 $DENSE_N); do
        printf 'x' > \"a_file_with_a_deliberately_long_name_for_lfn_testing_\$i.dat\"
    done"

    local D="$B/nested"
    for l in $(seq -w 1 10); do D="$D/level_$l"; mkdir -p "$D"; done
    dd if=/dev/urandom of="$D/leaf_at_depth_10.dat" bs=4k count=1 status=none

    sync
    chmod -R a+rX "$1/BENCH"
}

# Identical to the drive C manifest format — the benchmark parses this shape.
manifest() {
    local B="$1/BENCH" tag="$2"
    {
        echo "# OTG Master benchmark fixture"
        echo "# generated: $(date -u +%Y-%m-%dT%H:%M:%SZ)"
        echo "# volume:    $tag"
        echo
        echo "# path	bytes	sha256"
        for f in "$B"/large/*.bin; do
            printf 'large/%s\t%s\t%s\n' "$(basename "$f")" \
                "$(stat -c%s "$f")" "$(sha256sum "$f" | cut -d' ' -f1)"
        done
        for d in dense_short dense_lfn; do
            # head -c -1 strips the trailing newline so the hash matches the
            # benchmark's joinToString("\n"), which uses \n as separator only.
            printf '%s/\t%s files\t%s\n' "$d" \
                "$(find "$B/$d" -maxdepth 1 -type f | wc -l | tr -d ' ')" \
                "$(cd "$B/$d" && for f in *; do
                    if   [ -d "$f" ]; then printf '%s\tdir\n' "$f"
                    elif [ -f "$f" ]; then printf '%s\t%s\n' "$f" "$(stat -c%s "$f")"
                    fi
                   done | LC_ALL=C sort | head -c -1 | sha256sum | cut -d' ' -f1)"
        done
        local L RELPATH
        L=$(find "$B/nested" -name leaf_at_depth_10.dat)
        RELPATH="${L#$B/}"
        printf '%s\t%s\t%s\n' "$RELPATH" \
            "$(stat -c%s "$L")" "$(sha256sum "$L" | cut -d' ' -f1)"
    } > "$B/MANIFEST.txt"
    echo "  manifest: $B/MANIFEST.txt"
}

echo
echo "[4] Populating p1 (VeraCrypt + ext4)..."
populate /mnt/otgD1
manifest  /mnt/otgD1 "VCEXT4 (VeraCrypt $VC_ENC/$VC_HASH PIM $VC_PIM + ext4)"

echo
echo "[5] Populating p2 (plain ext4)..."
populate /mnt/otgD2
manifest  /mnt/otgD2 "PLAINEXT4 (unencrypted ext4)"

# p3 is a rejection fixture: the app must refuse NTFS. A marker file is enough,
# and is what distinguishes "refused" from "mounted but read as empty".
echo
echo "[6] Marking p3 (NTFS — must be refused)..."
mkdir -p /mnt/otgD3/BENCH
printf 'This partition is NTFS. OTG Master must refuse to mount it.\n' \
    > /mnt/otgD3/BENCH/DO_NOT_MOUNT.txt
sync

# ---------------------------------------------------------------------------
# Teardown
# ---------------------------------------------------------------------------
echo
echo "Tearing down..."
umount /mnt/otgD3
umount /mnt/otgD2
umount /mnt/otgD1
veracrypt -t -d "${DEV}1" --non-interactive
sync

echo
echo "=== Drive D complete ==="
lsblk -o NAME,SIZE,FSTYPE,LABEL "$DEV"
echo
echo "p1  VeraCrypt + ext4   password '$VC_PASS', PIM $VC_PIM, $VC_ENC / $VC_HASH"
echo "p2  plain ext4         no credentials"
echo "p3  plain NTFS         must be refused by the app"
echo "VeraCrypt parameters:  $FIXTURE_DIR/veracrypt-D.txt"
veracrypt -t -l 2>/dev/null | grep -q "${DEV}1" \
    && echo "WARNING: VeraCrypt volume still attached" \
    || echo "All volumes detached."
