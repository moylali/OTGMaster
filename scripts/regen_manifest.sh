#!/usr/bin/env bash
# Regenerate MANIFEST.txt on an already-prepared fixture drive (B or C).
#
# Usage: sudo bash scripts/regen_manifest.sh /dev/sdX <password> <drive-letter>
#   e.g. sudo bash scripts/regen_manifest.sh /dev/sdb password123 B
#
# The script opens the LUKS container, recomputes the hashes with the correct
# format (no trailing newline before sha256sum, full path for nested file),
# overwrites MANIFEST.txt, syncs, then closes the container.
#
# Safe to run on drives whose files are correct — this only rewrites the manifest.

set -euo pipefail

DEV="${1:?Usage: sudo bash $0 /dev/sdX <password> <B|C>}"
PASS="${2:?Usage: sudo bash $0 /dev/sdX <password> <B|C>}"
DRIVE="${3:?Usage: sudo bash $0 /dev/sdX <password> <B|C>}"

[[ $EUID -eq 0 ]] || { echo "ERROR: run with sudo."; exit 1; }

MAPPER="otgRegen"
MNT="/mnt/otgRegen"

echo "=== Regenerating MANIFEST.txt on Drive $DRIVE ($DEV) ==="

PART="${DEV}1"

echo "Opening LUKS on $PART..."
echo "$PASS" | cryptsetup luksOpen "$PART" "$MAPPER"
mkdir -p "$MNT"
mount /dev/mapper/"$MAPPER" "$MNT"

B="$MNT/BENCH"
[[ -d "$B" ]] || { echo "ERROR: $MNT/BENCH not found — wrong drive?"; umount "$MNT"; cryptsetup close "$MAPPER"; exit 1; }

echo "Writing MANIFEST.txt..."
{
    echo "# OTG Master benchmark fixture"
    echo "# generated: $(date -u +%Y-%m-%dT%H:%M:%SZ)"
    echo "# luks:      luks-${DRIVE}.txt"
    echo
    echo "# path	bytes	sha256"
    for f in "$B"/large/*.bin; do
        printf 'large/%s\t%s\t%s\n' "$(basename "$f")" \
            "$(stat -c%s "$f")" "$(sha256sum "$f" | cut -d' ' -f1)"
    done
    for d in dense_short dense_lfn; do
        # head -c -1 strips the trailing newline so the hash matches
        # the benchmark's joinToString("\n") (separator, not terminator).
        printf '%s/\t%s files\t%s\n' "$d" \
            "$(find "$B/$d" -maxdepth 1 -type f | wc -l | tr -d ' ')" \
            "$(cd "$B/$d" && for f in *; do
                if   [ -d "$f" ]; then printf '%s\tdir\n' "$f"
                elif [ -f "$f" ]; then printf '%s\t%s\n' "$f" "$(stat -c%s "$f")"
                fi
               done | LC_ALL=C sort | head -c -1 | sha256sum | cut -d' ' -f1)"
    done
    # Record the full relative path from BENCH/ so bench.search() can find it.
    L=$(find "$B/nested" -name leaf_at_depth_10.dat)
    RELPATH="${L#$B/}"
    printf '%s\t%s\t%s\n' "$RELPATH" \
        "$(stat -c%s "$L")" "$(sha256sum "$L" | cut -d' ' -f1)"
} > "$B/MANIFEST.txt"

echo "  written: $B/MANIFEST.txt"
echo
cat "$B/MANIFEST.txt"

echo
echo "Syncing and closing..."
sync
umount "$MNT"
cryptsetup close "$MAPPER"

echo "=== Done. Plug Drive $DRIVE into the phone and re-run the fixture test. ==="
