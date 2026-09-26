#!/usr/bin/env bash
#
# Prepare a USB drive as a fixture for OTG Master I/O benchmarking.
# See docs/IO_PERFORMANCE.md §5 for what each fixture measures.
#
# DESTRUCTIVE: repartitions and reformats the target disk. Safety checks refuse
# anything that is not an external, removable USB disk, but read the summary it
# prints before confirming.
#
# macOS only (uses diskutil / newfs_msdos / newfs_exfat).
#
# Usage:
#   scripts/prepare_test_usb.sh --disk disk4 --fs fat32
#   scripts/prepare_test_usb.sh --disk disk5 --fs exfat --free 4 --quick
#
set -euo pipefail

PROFILE="stress"
DISK=""
FS=""
LABEL=""
CLUSTER=4096          # bytes per cluster; small on purpose — inflates FAT / bitmap (§7.8)
FREE_GIB=4            # space to leave unallocated for write benchmarks
QUICK=0               # skip bulk fill and shrink fixtures, for fast iteration
ASSUME_YES=0

VERACRYPT=0           # wrap the partition in a VeraCrypt volume
VC_PASS="password123" # matches scripts/generate_testdata.sh so the E2E suite agrees
VC_PIM=1
VC_ENC="AES"          # AES or Serpent — both supported by the app
VC_HASH="SHA-512"
VC_DEV=""
VC_ATTACHED=0

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
SEED_DIR="${TMPDIR:-/tmp}/otg_prep_seed"

die()  { printf '\033[31merror:\033[0m %s\n' "$*" >&2; exit 1; }
info() { printf '\033[36m==>\033[0m %s\n' "$*"; }
warn() { printf '\033[33mwarn:\033[0m %s\n' "$*" >&2; }

usage() {
    sed -n '2,20p' "${BASH_SOURCE[0]}" | sed 's/^# \{0,1\}//'
    exit 0
}

while [ $# -gt 0 ]; do
    case "$1" in
        --profile) PROFILE="$2"; shift 2 ;;
        --disk)    DISK="$2";    shift 2 ;;
        --fs)      FS="$2";      shift 2 ;;
        --label)   LABEL="$2";   shift 2 ;;
        --cluster) CLUSTER="$2"; shift 2 ;;
        --free)    FREE_GIB="$2";shift 2 ;;
        --quick)   QUICK=1;      shift ;;
        --veracrypt) VERACRYPT=1;  shift ;;
        --vc-password)   VC_PASS="$2"; shift 2 ;;
        --vc-pim)        VC_PIM="$2";  shift 2 ;;
        --vc-encryption) VC_ENC="$2";  shift 2 ;;
        --vc-hash)       VC_HASH="$2"; shift 2 ;;
        --yes|-y)  ASSUME_YES=1; shift ;;
        -h|--help) usage ;;
        *) die "unknown argument: $1 (try --help)" ;;
    esac
done

[ -n "$DISK" ] || die "--disk is required (e.g. --disk disk4). Run 'diskutil list' first."
[ -n "$FS" ]   || die "--fs is required (fat32 or exfat)"
case "$FS" in fat32|exfat) ;; *) die "--fs must be fat32 or exfat" ;; esac
case "$PROFILE" in stress) ;; *) die "unknown profile: $PROFILE (only 'stress' so far)" ;; esac

DISK="${DISK#/dev/}"
DEV="/dev/$DISK"
SEED_DIR="${SEED_DIR}_${DISK}"   # per-disk: two drives can be prepared in parallel

if [ -z "$LABEL" ]; then
    if [ "$VERACRYPT" -eq 1 ]; then
        if [ "$FS" = "fat32" ]; then LABEL="VCFAT"; else LABEL="VCEXFAT"; fi
    else
        if [ "$FS" = "fat32" ]; then LABEL="STRESSFAT"; else LABEL="STRESSEXF"; fi
    fi
fi

# ---------------------------------------------------------------------------
# Safety. Refuse anything that is not an external, removable, USB disk.
# ---------------------------------------------------------------------------
[ -b "$DEV" ] || die "$DEV is not a block device"

INFO="$(diskutil info "$DISK" 2>/dev/null)" || die "diskutil cannot read $DISK"

check_field() {
    local field="$1" want="$2"
    local got
    got="$(printf '%s\n' "$INFO" | grep -E "^ *$field:" | head -1 | sed 's/.*: *//' | sed 's/ *$//' || true)"
    [ "$got" = "$want" ] || die "$DISK has $field='$got', refusing (need '$want'). This does not look like a removable test USB drive."
}

check_field "Removable Media"   "Removable"
check_field "Protocol"          "USB"
check_field "Device Location"   "External"
check_field "Media Read-Only"   "No"

# Guard against a typo'd identifier pointing at something large and important.
BYTES="$(printf '%s\n' "$INFO" | grep -E "^ *Disk Size:" | head -1 | sed 's/.*(\([0-9]*\) Bytes).*/\1/' || true)"
case "$BYTES" in ''|*[!0-9]*) die "could not determine size of $DISK" ;; esac
MIN=$((4 * 1024 * 1024 * 1024))
MAX=$((2048 * 1024 * 1024 * 1024))
[ "$BYTES" -ge "$MIN" ] || die "$DISK is only $((BYTES / 1024 / 1024)) MB — too small to be the intended test drive"
[ "$BYTES" -le "$MAX" ] || die "$DISK is $((BYTES / 1024 / 1024 / 1024)) GB — larger than expected, refusing out of caution"

# Formatting the raw device needs root (/dev/diskNsM is root:operator 0640).
# Check this BEFORE partitioning, so a missing credential cannot leave the disk
# wiped but unformatted.
SUDO=""
if [ "$(id -u)" -ne 0 ]; then
    SUDO="sudo"
    if ! sudo -n true 2>/dev/null; then
        die "formatting /dev/${DISK}s1 needs root, and sudo has no cached credential.

  Run this once in your terminal, then re-run this script:

      sudo -v

  (the script itself stays unprivileged; only newfs_* is elevated)"
    fi
fi

MEDIA="$(printf '%s\n' "$INFO" | grep -E "^ *Device / Media Name:" | head -1 | sed 's/.*: *//' || true)"
GIB=$((BYTES / 1024 / 1024 / 1024))

cat <<EOF

  ┌─────────────────────────────────────────────────────────────┐
  │  THIS WILL ERASE THE DISK. Everything on it will be lost.   │
  └─────────────────────────────────────────────────────────────┘

    Disk        : $DEV
    Media       : $MEDIA
    Size        : ${GIB} GB
    Profile     : $PROFILE (single MBR partition)
    Filesystem  : $FS, ${CLUSTER}-byte clusters$([ "$VERACRYPT" -eq 1 ] && echo " (inside VeraCrypt)")
    Encryption  : $([ "$VERACRYPT" -eq 1 ] && echo "VeraCrypt $VC_ENC / $VC_HASH, PIM $VC_PIM, password '$VC_PASS'" || echo "none (plain volume)")
    Label       : $LABEL
    Leave free  : ${FREE_GIB} GiB (for write benchmarks)
    Mode        : $([ "$QUICK" -eq 1 ] && echo "QUICK — small fixtures, no bulk fill" || echo "full")

EOF

diskutil list "$DISK" || true
echo

if [ "$ASSUME_YES" -ne 1 ]; then
    printf 'Type the disk identifier (%s) to confirm, anything else to abort: ' "$DISK"
    read -r CONFIRM
    [ "$CONFIRM" = "$DISK" ] || die "aborted (got '$CONFIRM')"
fi

# ---------------------------------------------------------------------------
# Partition and format.
#
# diskutil lays down the MBR and sets the correct partition type byte, then we
# reformat the slice by hand because diskutil gives no control over cluster
# size — and an inflated FAT / allocation bitmap is the entire point (§7.8).
# ---------------------------------------------------------------------------
PART="${DISK}s1"

# Leaving a VeraCrypt volume attached after a failure would block the next run
# and hold the device open. Detach on any exit path.
cleanup() {
    if [ "$VC_ATTACHED" -eq 1 ]; then
        umount "${MNT:-}" 2>/dev/null || true
        $SUDO veracrypt -t -d "/dev/$PART" --non-interactive >/dev/null 2>&1 || true
        VC_ATTACHED=0
    fi
}
trap cleanup EXIT INT TERM

info "Unmounting $DISK"
diskutil unmountDisk force "$DISK" >/dev/null

if [ "$FS" = "fat32" ]; then
    DU_FS="MS-DOS FAT32"
else
    DU_FS="ExFAT"
fi

info "Creating MBR partition table with a single $FS partition"
diskutil partitionDisk "$DISK" MBR "$DU_FS" "$LABEL" 100% >/dev/null

# partitionDisk formats the new partition and DiskArbitration auto-mounts it.
# Everything below writes to the raw device, so it has to be unmounted first —
# VeraCrypt fails with "Resource busy: /dev/diskNsM" otherwise. DiskArbitration
# can re-mount behind us, so retry rather than unmounting once and hoping.
ensure_unmounted() {
    tries=0
    while [ "$tries" -lt 6 ]; do
        diskutil unmount force "/dev/$PART" >/dev/null 2>&1 || true
        diskutil unmountDisk force "$DISK"   >/dev/null 2>&1 || true
        if ! mount | grep -q "^/dev/$PART on "; then
            return 0
        fi
        tries=$((tries + 1))
        sleep 1
    done
    die "/dev/$PART keeps re-mounting; close anything using it and retry"
}

info "Unmounting the new partition"
ensure_unmounted

if [ "$VERACRYPT" -eq 1 ]; then
    # Device-hosted volume. --quick is essential: without it VeraCrypt encrypts
    # all 57 GiB of free space before we write a single fixture. Quick format
    # leaves stale patterns in unused areas, which is irrelevant for a fixture.
    info "Creating VeraCrypt volume on /dev/$PART ($VC_ENC / $VC_HASH, PIM $VC_PIM)"
    $SUDO veracrypt -t -c "/dev/$PART" \
        --volume-type=normal \
        --encryption="$VC_ENC" --hash="$VC_HASH" \
        --filesystem=none --pim="$VC_PIM" --keyfiles="" \
        --random-source=/dev/urandom \
        --password="$VC_PASS" --quick --non-interactive \
        || die "VeraCrypt volume creation failed"

    info "Attaching decrypted volume"
    $SUDO veracrypt -t --mount "/dev/$PART" \
        --password="$VC_PASS" --pim="$VC_PIM" --keyfiles="" \
        --protect-hidden=no --filesystem=none --non-interactive \
        || die "could not attach the VeraCrypt volume just created"

    VC_DEV="$($SUDO veracrypt -t -l 2>/dev/null | awk -v p="/dev/$PART" '$2 == p { print $3 }')"
    [ -n "$VC_DEV" ] || die "VeraCrypt attached but no device node reported"
    VC_ATTACHED=1
    TARGET_DEV="$VC_DEV"
    info "Decrypted device is $TARGET_DEV"
else
    TARGET_DEV="/dev/$PART"
fi

mkdir -p "$SEED_DIR"
FMT_LOG="$SEED_DIR/newfs.out"

info "Formatting $TARGET_DEV as $FS with ${CLUSTER}-byte clusters"

# Wipe any existing filesystem signature first. Apple's newfs_exfat detects an
# existing exFAT and refuses when the requested cluster size differs, printing
# "Cluster size differs from command line argument; skipping reformat" and
# exiting 0 — leaving diskutil's default 128 KiB clusters in place, which
# defeats the entire purpose of this fixture (§7.8).
# NOTE: this targets TARGET_DEV. For a VeraCrypt volume that is the *decrypted*
# device — zeroing /dev/$PART here would destroy the header we just wrote.
$SUDO dd if=/dev/zero of="$TARGET_DEV" bs=1048576 count=8 2>/dev/null || true

if [ "$FS" = "fat32" ]; then
    SPC=$((CLUSTER / 512))
    [ "$SPC" -ge 1 ] && [ "$SPC" -le 128 ] || die "cluster size $CLUSTER invalid for FAT32 (needs 512..65536 bytes)"
    $SUDO newfs_msdos -F 32 -c "$SPC" -v "$LABEL" "$TARGET_DEV" >"$FMT_LOG" 2>&1
else
    $SUDO newfs_exfat -b "$CLUSTER" -v "$LABEL" "$TARGET_DEV" >"$FMT_LOG" 2>&1
fi

info "Mounting"
if [ "$VERACRYPT" -eq 1 ]; then
    MNT="/Volumes/$LABEL"
    $SUDO mkdir -p "$MNT"
    if [ "$FS" = "fat32" ]; then MOUNT_T=msdos; else MOUNT_T=exfat; fi
    $SUDO mount -t "$MOUNT_T" "$TARGET_DEV" "$MNT" || die "could not mount decrypted volume"
else
    diskutil mount "$TARGET_DEV" >/dev/null
    MNT="$(diskutil info "$TARGET_DEV" | grep -E "^ *Mount Point:" | head -1 | sed 's/.*: *//' || true)"
fi
[ -n "$MNT" ] && [ -d "$MNT" ] || die "could not determine mount point for $TARGET_DEV"
info "Mounted at $MNT"

# newfs_exfat can skip a reformat and still exit 0, so confirm rather than assume.
# Take the number from the formatter's own report — the only source that works
# for both filesystems here. The device node is unreadable for a VeraCrypt
# volume, and diskutil reports "File System: None" for a FAT32 mount point.
if [ "$FS" = "fat32" ]; then
    ACTUAL_CLUSTER="$(grep -oE '\([0-9]+ bytes/cluster\)' "$FMT_LOG" 2>/dev/null | grep -oE '[0-9]+' | head -1 || true)"
else
    ACTUAL_CLUSTER="$(grep -E '^Bytes per cluster' "$FMT_LOG" 2>/dev/null | grep -oE '[0-9]+' | head -1 || true)"
fi
if [ -z "$ACTUAL_CLUSTER" ] || [ "$ACTUAL_CLUSTER" != "$CLUSTER" ]; then
    warn "formatter output was:"
    sed 's/^/      /' "$FMT_LOG" >&2
    die "cluster size is '${ACTUAL_CLUSTER:-unreadable}', expected $CLUSTER — the reformat did not take"
fi
info "Cluster size confirmed: $ACTUAL_CLUSTER bytes"

# Spotlight would index 50+ GiB of fixtures in the background, writing to the
# drive throughout every benchmark run. That is pure measurement noise.
info "Disabling Spotlight indexing on the volume"
$SUDO mdutil -i off "$MNT" >/dev/null 2>&1 || warn "could not disable Spotlight"
$SUDO mdutil -E "$MNT" >/dev/null 2>&1 || true
$SUDO rm -rf "$MNT/.Spotlight-V100" "$MNT/.fseventsd" "$MNT/.Trashes" 2>/dev/null || true

# Stop the copy engine attaching xattrs that force AppleDouble companions.
export COPYFILE_DISABLE=1

# ---------------------------------------------------------------------------
# Fixtures. Each maps to a row of the benchmark matrix in IO_PERFORMANCE.md §5.
# ---------------------------------------------------------------------------
mkdir -p "$SEED_DIR"
BENCH="$MNT/BENCH"
mkdir -p "$BENCH/large" "$BENCH/dense_short" "$BENCH/dense_lfn" "$BENCH/nested"

if [ "$QUICK" -eq 1 ]; then
    LARGE_SPEC="seq_256m.bin:256 seq_64m.bin:64"
    DENSE_N=500
else
    LARGE_SPEC="seq_2g.bin:2048 seq_1g.bin:1024 seq_256m.bin:256"
    DENSE_N=10000
fi

# -- large files: sequential throughput and random 4 KiB reads ---------------
# FAT32 stores file size in a 32-bit field: 4 GiB - 1 byte is the hard maximum.
# Fail up front rather than truncating a file 4095 MiB in.
if [ "$FS" = "fat32" ]; then
    for spec in $LARGE_SPEC; do
        if [ "${spec##*:}" -ge 4096 ]; then
            die "${spec%%:*} is ${spec##*:} MiB — FAT32 cannot hold a file of 4096 MiB or more"
        fi
    done
fi

info "Writing large files (sequential read + random read fixtures)"
for spec in $LARGE_SPEC; do
    name="${spec%%:*}"
    mb="${spec##*:}"
    printf '    %-14s %5s MiB ... ' "$name" "$mb"
    if ! dd if=/dev/urandom of="$BENCH/large/$name" bs=1048576 count="$mb" 2>"$SEED_DIR/dd.err"; then
        printf 'FAILED\n'
        sed 's/^/      /' "$SEED_DIR/dd.err" >&2
        die "could not write $name ($mb MiB) — see error above"
    fi
    printf 'done\n'
done

# -- dense directories: the O(n^2) path re-walk and directory-rewrite cases --
# Two variants isolate long-filename cost: short names fit 8.3 and use one
# directory entry; long names need LFN entries (~4x the directory bytes), which
# is what restic-style 64-hex filenames would hit (BACKUP_PLAN §7.4).
LINE="0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcde"
BLOCK=""
i=0
while [ $i -lt 64 ]; do BLOCK="$BLOCK$LINE"; i=$((i + 1)); done   # 4096 bytes

info "Writing $DENSE_N short-name files (8.3-compatible) to dense_short/"
i=0
while [ $i -lt "$DENSE_N" ]; do
    printf '%s' "$BLOCK" > "$(printf '%s/dense_short/%05d.dat' "$BENCH" "$i")"
    i=$((i + 1))
    [ $((i % 2000)) -eq 0 ] && printf '    %d/%d\n' "$i" "$DENSE_N"
done

info "Writing $DENSE_N long-name files (forces LFN entries) to dense_lfn/"
i=0
while [ $i -lt "$DENSE_N" ]; do
    printf '%s' "$BLOCK" > "$(printf '%s/dense_lfn/densefile_%05d_padding_for_lfn_entries.dat' "$BENCH" "$i")"
    i=$((i + 1))
    [ $((i % 2000)) -eq 0 ] && printf '    %d/%d\n' "$i" "$DENSE_N"
done

# -- nested tree: path re-walk depth (getFileForDocId re-lists every level) --
info "Writing nested directory tree (10 levels deep)"
DEEP="$BENCH/nested"
i=1
while [ $i -le 10 ]; do
    DEEP="$DEEP/level_$(printf '%02d' $i)"
    mkdir -p "$DEEP"
    printf '%s' "$BLOCK" > "$DEEP/marker.dat"
    i=$((i + 1))
done
printf '%s' "$BLOCK" > "$DEEP/leaf_at_depth_10.dat"

# ---------------------------------------------------------------------------
# Bulk fill, leaving FREE_GIB unallocated.
#
# A nearly-full volume is the realistic stress case: free-cluster search has to
# walk further, and the allocation bitmap / FAT is fully populated.
# ---------------------------------------------------------------------------
free_gib_now() {
    df -g "$MNT" | awk 'NR==2 {print $4}'
}

if [ "$QUICK" -eq 1 ]; then
    warn "QUICK mode — skipping bulk fill; volume will be mostly empty"
else
    info "Bulk filling to leave ${FREE_GIB} GiB free (this is the slow part)"
    mkdir -p "$SEED_DIR" "$MNT/FILL"
    SEED="$SEED_DIR/seed_1g.bin"
    if [ ! -f "$SEED" ]; then
        info "  generating 1 GiB random seed block (one time, on local disk)"
        dd if=/dev/urandom of="$SEED" bs=1048576 count=1024 2>/dev/null
    fi

    n=0
    while [ "$(free_gib_now)" -gt "$FREE_GIB" ]; do
        n=$((n + 1))
        dest="$(printf '%s/FILL/fill_%04d.bin' "$MNT" "$n")"
        if ! dd if="$SEED" of="$dest" bs=1048576 2>/dev/null; then
            rm -f "$dest"
            break
        fi
        printf '    %s written, %s GiB free\n' "$(basename "$dest")" "$(free_gib_now)"
        [ "$n" -gt 200 ] && { warn "fill loop exceeded 200 files, stopping"; break; }
    done
fi

# ---------------------------------------------------------------------------
# Manifest — lets the Android side verify it read the right bytes, not just
# that it read them quickly.
# ---------------------------------------------------------------------------
# macOS writes a "._name" AppleDouble companion for every file on FAT/exFAT that
# carries an xattr (com.apple.provenance is added automatically on recent
# releases). That doubles every directory entry count and gives the "._" files
# long names of their own — so a 500-file dense_lfn fixture presents as 1000
# entries and the LFN measurement is meaningless. Strip them.
info "Removing AppleDouble companions and volume metadata"
dot_clean -m "$MNT" 2>/dev/null || true
find "$MNT" -name '._*' -delete 2>/dev/null || true
$SUDO rm -rf "$MNT/.Spotlight-V100" "$MNT/.fseventsd" "$MNT/.Trashes" 2>/dev/null || true

info "Verifying fixture counts"
VERIFY_FAIL=0
check_count() {
    local dir="$1" want="$2" got
    got=$(ls -1 "$dir" 2>/dev/null | wc -l | tr -d ' ')
    if [ "$got" != "$want" ]; then
        warn "$(basename "$dir"): expected $want files, found $got"
        VERIFY_FAIL=1
    else
        printf '    %-14s %s files OK\n' "$(basename "$dir")" "$got"
    fi
}
check_count "$BENCH/dense_short" "$DENSE_N"
check_count "$BENCH/dense_lfn"   "$DENSE_N"
STRAY=$(find "$MNT" -name '._*' 2>/dev/null | wc -l | tr -d ' ')
[ "$STRAY" = "0" ] || { warn "$STRAY AppleDouble files still present"; VERIFY_FAIL=1; }
[ "$VERIFY_FAIL" -eq 0 ] || warn "fixture verification reported problems — see above"

info "Writing manifest"
MANIFEST="$BENCH/MANIFEST.txt"
{
    echo "# OTG Master benchmark fixture"
    echo "# generated: $(date -u '+%Y-%m-%dT%H:%M:%SZ')"
    echo "# profile:   $PROFILE"
    echo "# fs:        $FS, ${CLUSTER}-byte clusters"
    if [ "$VERACRYPT" -eq 1 ]; then
        echo "# veracrypt: $VC_ENC / $VC_HASH, PIM $VC_PIM, password $VC_PASS"
    else
        echo "# veracrypt: none (plain volume)"
    fi
    echo "# label:     $LABEL"
    echo "# device:    $MEDIA (${GIB} GB)"
    echo
    echo "# path<TAB>bytes<TAB>sha256   (large files: size only, hashed on demand)"
    for spec in $LARGE_SPEC; do
        name="${spec%%:*}"
        f="$BENCH/large/$name"
        printf 'large/%s\t%s\t-\n' "$name" "$(stat -f%z "$f")"
    done
    printf 'dense_short/\t%s files\t-\n' "$DENSE_N"
    printf 'dense_lfn/\t%s files\t-\n' "$DENSE_N"
    printf 'nested/\t10 levels\t%s\n' "$(shasum -a 256 "$DEEP/leaf_at_depth_10.dat" | awk '{print $1}')"
} > "$MANIFEST"

sync

# ---------------------------------------------------------------------------
info "Done"
echo
df -h "$MNT" | sed 's/^/    /'
echo
echo "    Fixtures:"
echo "      BENCH/large/          sequential + random-read targets"
echo "      BENCH/dense_short/    $DENSE_N files, 8.3 names (1 dir entry each)"
echo "      BENCH/dense_lfn/      $DENSE_N files, long names (LFN, ~4 entries each)"
echo "      BENCH/nested/         10-level deep path"
echo "      BENCH/MANIFEST.txt    sizes for read verification"
[ "$QUICK" -eq 1 ] || echo "      FILL/                 bulk fill, ${FREE_GIB} GiB left free"
echo
if [ "$VERACRYPT" -eq 1 ]; then
    echo "    Unlock on the phone with:"
    echo "      password : $VC_PASS"
    echo "      PIM      : $VC_PIM"
    echo "      cipher   : $VC_ENC"
    echo "      hash     : $VC_HASH"
    echo
    info "Detaching VeraCrypt volume"
    $SUDO umount "$MNT" 2>/dev/null || true
    $SUDO veracrypt -t -d "/dev/$PART" --non-interactive >/dev/null 2>&1 || true
    VC_ATTACHED=0
fi
echo "    Eject with:  diskutil eject $DISK"
echo
