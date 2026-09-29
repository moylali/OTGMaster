#!/usr/bin/env bash
# Drive A — 4 partitions: LUKS1/LUKS2 × FAT32/exFAT
# See docs/TEST_DATA.md §9 for full spec.
#
# Usage: sudo bash scripts/prepare_drive_a.sh /dev/sdX
#
# Partition layout:
#   p1  LUKS1 / FAT32   label LUKS1FAT   (PBKDF2)
#   p2  LUKS1 / exFAT   label LUKS1EXF   (PBKDF2)
#   p3  LUKS2 / FAT32   label LUKS2FAT   (Argon2id)
#   p4  LUKS2 / exFAT   label LUKS2EXF   (Argon2id)
#
# If all 4 LUKS containers already exist the wipe/partition/luksFormat
# stages are skipped automatically — the script resumes at open+mkfs.

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

echo "=== Drive A: $DEV ==="
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
# Stages 1–2: Partition + luksFormat — skipped if containers already exist
# ---------------------------------------------------------------------------
NEEDS_FORMAT=0
for p in 1 2 3 4; do
    cryptsetup isLuks "${DEV}${p}" 2>/dev/null || { NEEDS_FORMAT=1; break; }
done

if [[ $NEEDS_FORMAT -eq 1 ]]; then
    echo
    read -rp "No LUKS containers found — this will DESTROY all data on $DEV. Type YES to continue: " CONFIRM
    [[ "$CONFIRM" == "YES" ]] || { echo "Aborted."; exit 1; }

    echo
    echo "[1] Partitioning $DEV into 4 equal ~15 GiB partitions..."
    wipefs -a "$DEV"
    parted -s "$DEV" mklabel msdos
    parted -s "$DEV" mkpart primary  1MiB  16GiB
    parted -s "$DEV" mkpart primary 16GiB  32GiB
    parted -s "$DEV" mkpart primary 32GiB  48GiB
    parted -s "$DEV" mkpart primary 48GiB 100%
    partprobe "$DEV"
    sleep 1
    lsblk "$DEV"

    echo
    echo "[2] Creating LUKS containers (parallel — CPU-bound)..."

    _luks1() {
        echo -n "$PASS" | cryptsetup luksFormat --type luks1 \
            --cipher aes-xts-plain64 --key-size 512 --hash sha256 \
            --pbkdf-force-iterations 10000 --batch-mode "${DEV}${1}" -
        echo "  luks1: ${DEV}${1}"
    }
    _luks2() {
        echo -n "$PASS" | cryptsetup luksFormat --type luks2 \
            --cipher aes-xts-plain64 --key-size 512 --hash sha256 \
            --pbkdf argon2id --pbkdf-memory 65536 --pbkdf-parallel 4 \
            --pbkdf-force-iterations 4 --sector-size 512 \
            --batch-mode "${DEV}${1}" -
        echo "  luks2: ${DEV}${1}"
    }

    _luks1 1 & _luks1 2 & _luks2 3 & _luks2 4 &
    wait
else
    echo "  LUKS containers already present on all 4 partitions — skipping wipe/format."
fi

# ---------------------------------------------------------------------------
# Stage 3: Capture headers — ground truth for the parser
# ---------------------------------------------------------------------------
echo
echo "[3] Capturing luksDump headers → $FIXTURE_DIR..."
mkdir -p "$FIXTURE_DIR"
for p in 1 2 3 4; do
    cryptsetup luksDump "${DEV}${p}" > "$FIXTURE_DIR/luks-A-p${p}.txt"
    echo "  saved: luks-A-p${p}.txt"
done

# ---------------------------------------------------------------------------
# Stage 4: Open containers + format filesystems (parallel opens + parallel mkfs)
# ---------------------------------------------------------------------------
echo
echo "[4] Opening containers and formatting filesystems..."
for p in 1 2 3 4; do
    echo -n "$PASS" | cryptsetup open "${DEV}${p}" "otgA${p}" - &
done
wait

# -s 8 → 8×512 = 4096-byte clusters on FAT32; -c 4096 same on exFAT.
# Small clusters inflate the FAT/allocation bitmap on purpose.
mkfs.vfat  -F 32 -s 8 -n LUKS1FAT /dev/mapper/otgA1 &
mkfs.exfat -c 4096    -L LUKS1EXF /dev/mapper/otgA2 &
mkfs.vfat  -F 32 -s 8 -n LUKS2FAT /dev/mapper/otgA3 &
mkfs.exfat -c 4096    -L LUKS2EXF /dev/mapper/otgA4 &
wait

for p in 1 2 3 4; do
    mkdir -p "/mnt/otgA${p}"
    mount "/dev/mapper/otgA${p}" "/mnt/otgA${p}"
done

# ---------------------------------------------------------------------------
# Stage 5: Populate — sequential across partitions (same physical USB bus)
# ---------------------------------------------------------------------------
_populate() {
    local M="$1" SEQ_MB="$2"
    local B="$M/BENCH"
    mkdir -p "$B/large" "$B/dense_short" "$B/dense_lfn" "$B/nested" "$B/reports"

    dd if=/dev/urandom of="$B/large/seq_${SEQ_MB}m.bin" \
        bs=1M count="$SEQ_MB" status=progress

    sh -c "cd '$B/dense_short' && for i in \$(seq -w 1 10000); do
        printf 'x' > f\$i.dat
    done"
    sh -c "cd '$B/dense_lfn' && for i in \$(seq -w 1 10000); do
        printf 'x' > \"a_file_with_a_deliberately_long_name_for_lfn_testing_\$i.dat\"
    done"

    local D="$B/nested"
    for l in $(seq -w 1 10); do D="$D/level_$l"; mkdir -p "$D"; done
    dd if=/dev/urandom of="$D/leaf_at_depth_10.dat" bs=4k count=1 status=none
    sync
}

echo
echo "[5] Populating fixtures (256 MiB + 20k files per partition, sequential)..."
for p in 1 2 3 4; do
    echo "  p${p}..."
    _populate "/mnt/otgA${p}" 256
done

# ---------------------------------------------------------------------------
# Stage 6: Manifests — parallel (compute-heavy reads of already-written data)
# ---------------------------------------------------------------------------
_manifest() {
    local M="$1" LUKSREF="$2"
    local B="$M/BENCH"
    {
        echo "# OTG Master benchmark fixture"
        echo "# generated: $(date -u +%Y-%m-%dT%H:%M:%SZ)"
        echo "# luks:      $LUKSREF"
        echo
        echo "# path	bytes	sha256"
        for f in "$B"/large/*.bin; do
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
        local L
        L=$(find "$B/nested" -name leaf_at_depth_10.dat)
        RELPATH="${L#$B/}"
        printf '%s\t%s\t%s\n' "$RELPATH" \
            "$(stat -c%s "$L")" "$(sha256sum "$L" | cut -d' ' -f1)"
    } > "$B/MANIFEST.txt"
    echo "  manifest: $B/MANIFEST.txt"
}

echo
echo "[6] Writing manifests (parallel)..."
for p in 1 2 3 4; do
    _manifest "/mnt/otgA${p}" "luks-A-p${p}.txt" &
done
wait

# ---------------------------------------------------------------------------
# Teardown
# ---------------------------------------------------------------------------
echo
echo "Tearing down..."
for p in 1 2 3 4; do
    umount  "/mnt/otgA${p}"
    cryptsetup close "otgA${p}"
done
sync

echo
echo "=== Drive A complete ==="
echo "Headers: $FIXTURE_DIR/luks-A-p{1..4}.txt"
dmsetup ls 2>/dev/null | grep -q otgA \
    && echo "WARNING: some mappers still open" \
    || echo "All mappers closed."
