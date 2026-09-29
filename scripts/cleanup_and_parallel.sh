#!/bin/bash
set -e
# Cleanup any orphaned mounts and luks mappings
for mnt in /tmp/mnt_*; do
    if mountpoint -q "$mnt"; then
        umount "$mnt" || true
    fi
    rm -rf "$mnt"
done

for map in $(ls /dev/mapper/luks_test_* 2>/dev/null); do
    cryptsetup luksClose "$map" || true
done
