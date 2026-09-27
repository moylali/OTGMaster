#!/usr/bin/env bash
# Drive B — 1 partition: LUKS1 + ext4
# See docs/TEST_DATA.md §10 for full spec.
#
# Usage: sudo bash scripts/prepare_drive_b.sh /dev/sdX
#
# Proves: PBKDF2 unlock → ext4 detection (refused cleanly until ext4 lands).
#
# If a LUKS1 container already exists the wipe/partition/luksFormat stages
# are skipped automatically — the script resumes at open+mkfs.

set -euo pipefail

DEV="${1:?Usage: sudo bash $0 /dev/sdX}"
PASS=password123
FIXTURE_DIR=/root/otg-luks-fixtures

# ---------------------------------------------------------------------------
# Safety checks
# ---------------------------------------------------------------------------
[[ $EUID -eq 0 ]] || { echo "ERROR: run with sudo."; exit 1; }

TRAN=$(lsblk -d -o TRAN --noheadings "$DEV" 2>/dev/null | tr -d ' ')
[[ "$TRAN" == "usb" ]] || { echo "ERROR: $DEV is not USB (TRAN=$TRAN). Aborting."; exit 1; }

SIZE_B=$(lsblk -d -o SIZE --noheadings --bytes "$DEV" | tr -d ' ')
(( SIZE_B > 55000000000 )) || { echo "ERROR: $DEV is too small. Aborting."; exit 1; }

echo "=== Drive B: $DEV ==="
lsblk "$DEV"
echo

# ---------------------------------------------------------------------------
# Stage 0: Unmount anything using this device
# ---------------------------------------------------------------------------
echo "[0] Cleaning up existing mounts and mappers..."
for mapper in /dev/mapper/*; do
    [[ -e "$mapper" ]] || continue
    name=$(basename "$mapper")
    [[ "$name" == "control" ]] && continue
    backing=$(dmsetup deps -o devname "$mapper" 2>/dev/null \
        | grep -o 'sd[a-z][0-9]*' | head -1 || true)
    if [[ "$backing" == "$(basename "$DEV")"* ]]; then
        echo "  closing mapper: $name"
        umount "$mapper" 2>/dev/null || true
        cryptsetup close "$name" 2>/dev/null || true
    fi
done
for part in "${DEV}"[0-9]*; do
    [[ -b "$part" ]] || continue
    if mount | grep -q "^$part "; then
        echo "  unmounting: $part"
        umount "$part"
    fi
done

# ---------------------------------------------------------------------------
# Stages 1–2: Partition + luksFormat — skipped if container already exists
# ---------------------------------------------------------------------------
if cryptsetup isLuks "${DEV}1" 2>/dev/null; then
    echo "  LUKS1 container already present — skipping wipe/format."
else
    echo
    read -rp "No LUKS container found — this will DESTROY all data on $DEV. Type YES to continue: " CONFIRM
    [[ "$CONFIRM" == "YES" ]] || { echo "Aborted."; exit 1; }

    echo
    echo "[1] Partitioning $DEV (single partition, full disk)..."
    wipefs -a "$DEV"
    parted -s "$DEV" mklabel msdos
    parted -s "$DEV" mkpart primary 1MiB 100%
    partprobe "$DEV"
    sleep 1
    lsblk "$DEV"

    echo
    echo "[2] Creating LUKS1 container..."
    echo -n "$PASS" | cryptsetup luksFormat --type luks1 \
        --cipher aes-xts-plain64 --key-size 512 --hash sha256 \
        --pbkdf-force-iterations 10000 --batch-mode "${DEV}1" -
    echo "  luks1: ${DEV}1"
fi

# ---------------------------------------------------------------------------
# Stage 3: Capture header
# ---------------------------------------------------------------------------
echo
echo "[3] Capturing luksDump header → $FIXTURE_DIR..."
mkdir -p "$FIXTURE_DIR"
cryptsetup luksDump "${DEV}1" > "$FIXTURE_DIR/luks-B.txt"
echo "  saved: luks-B.txt"

# ---------------------------------------------------------------------------
# Stage 4: Open + mkfs
# ---------------------------------------------------------------------------
echo
echo "[4] Opening container and formatting ext4..."
echo -n "$PASS" | cryptsetup open "${DEV}1" otgB -

# -m 0: no reserved blocks — free space comparable to FAT32/exFAT drives.
# lazy_*_init=0: write inode tables and journal now so the filesystem is
# stable immediately — without this, background init changes the volume
# under the first reads and fixture hashes are not reproducible.
mkfs.ext4 -L LUKS1EXT4 -m 0 \
    -E lazy_itable_init=0,lazy_journal_init=0 /dev/mapper/otgB

mkdir -p /mnt/otgB
mount /dev/mapper/otgB /mnt/otgB

# ---------------------------------------------------------------------------
# Stage 5: Populate
# ---------------------------------------------------------------------------
echo
echo "[5] Populating fixtures (2048 MiB — exercises the ≥2 GiB read boundary)..."

B=/mnt/otgB/BENCH
mkdir -p "$B/large" "$B/dense_short" "$B/dense_lfn" "$B/nested" "$B/reports"

dd if=/dev/urandom of="$B/large/seq_2048m.bin" bs=1M count=2048 status=progress
dd if=/dev/urandom of="$B/large/seq_256m.bin"  bs=1M count=256  status=none

sh -c "cd '$B/dense_short' && for i in \$(seq -w 1 10000); do
    printf 'x' > f\$i.dat
done"
sh -c "cd '$B/dense_lfn' && for i in \$(seq -w 1 10000); do
    printf 'x' > \"a_file_with_a_deliberately_long_name_for_lfn_testing_\$i.dat\"
done"

D="$B/nested"
for l in $(seq -w 1 10); do D="$D/level_$l"; mkdir -p "$D"; done
dd if=/dev/urandom of="$D/leaf_at_depth_10.dat" bs=4k count=1 status=none

sync

# ext4 stores real POSIX permissions; everything above was written as root.
# Make world-readable so a non-root process can access the fixtures.
chmod -R a+rX /mnt/otgB/BENCH

# ---------------------------------------------------------------------------
# Stage 6: Manifest
# ---------------------------------------------------------------------------
echo
echo "[6] Writing manifest..."
{
    echo "# OTG Master benchmark fixture"
    echo "# generated: $(date -u +%Y-%m-%dT%H:%M:%SZ)"
    echo "# luks:      luks-B.txt"
    echo
    echo "# path	bytes	sha256"
    for f in "$B"/large/*.bin; do
        printf 'large/%s\t%s\t%s\n' "$(basename "$f")" \
            "$(stat -c%s "$f")" "$(sha256sum "$f" | cut -d' ' -f1)"
    done
    for d in dense_short dense_lfn; do
        # head -c -1 strips the trailing newline so the hash matches
        # the benchmark's joinToString("\n") which uses \n as separator only.
        printf '%s/\t%s files\t%s\n' "$d" \
            "$(find "$B/$d" -maxdepth 1 -type f | wc -l | tr -d ' ')" \
            "$(cd "$B/$d" && for f in *; do
                if   [ -d "$f" ]; then printf '%s\tdir\n' "$f"
                elif [ -f "$f" ]; then printf '%s\t%s\n' "$f" "$(stat -c%s "$f")"
                fi
               done | LC_ALL=C sort | head -c -1 | sha256sum | cut -d' ' -f1)"
    done
    # Record the full relative path so bench.search() can find it.
    L=$(find "$B/nested" -name leaf_at_depth_10.dat)
    RELPATH="${L#$B/}"
    printf '%s\t%s\t%s\n' "$RELPATH" \
        "$(stat -c%s "$L")" "$(sha256sum "$L" | cut -d' ' -f1)"
} > "$B/MANIFEST.txt"
echo "  manifest: $B/MANIFEST.txt"

# ---------------------------------------------------------------------------
# Teardown
# ---------------------------------------------------------------------------
echo
echo "Tearing down..."
umount /mnt/otgB
cryptsetup close otgB
sync

echo
echo "=== Drive B complete ==="
echo "Header: $FIXTURE_DIR/luks-B.txt"
dmsetup ls 2>/dev/null | grep -q otgB \
    && echo "WARNING: mapper still open" \
    || echo "All mappers closed."
