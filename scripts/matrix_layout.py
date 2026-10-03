"""
The device-matrix drives: four 64 GB USB drives, ten partitions each, covering every
container x filesystem combination the app supports (docs/TEST_DATA.md §16).

The single source of truth for build_matrix_drive.py, write_matrix_drive.sh (through
the drive.json the build writes), verify_matrix_drive.py and
prepare_matrix_drive_macos.sh (which hard-codes Drive 1's four APFS partitions and
must agree with the APFS rows here).

Every combination, 37 in all:
    ext2/ext3/ext4     x LUKS1, LUKS2, LUKS2 (4 KiB sectors), VeraCrypt      12
    FAT32/exFAT/NTFS   x LUKS1, LUKS2, LUKS2 (4 KiB sectors), VeraCrypt,
                         BitLocker                                         15
    unencrypted        FAT32, exFAT, NTFS, ext4, ext2, ext3                   6
    APFS               plain/encrypted x case-insensitive/sensitive           4
The three spare slots carry the BitLocker ciphers not otherwise on a drive.
"""

from typing import Any, Dict, Optional, Tuple

PASSWORD = "password123"
PIM = 1
RECOVERY = "111111-222222-333333-444444-555555-666666-111111-222222"

MiB = 1 << 20
# Every partition is this size. Ten of them plus macOS's 200 MiB EFI partition and
# GPT overhead come to 59.1e9 bytes, which fits any drive sold as 64 GB (the
# smallest seen are about 61.5e9). Fixed rather than derived from the drive, so the
# Mac and Linux halves of Drive 1 agree without talking to each other.
PART_MIB = 5600
PART_BYTES = PART_MIB * MiB

READ_BYTES = 3.0e9         # read set on a writable partition; ~2.4 GB stays free for writes
READ_BYTES_RO = 3.5e9      # read-only partitions (APFS, ext2, ext3) need no write space

READ_ONLY_FS = ("apfs", "ext2", "ext3")


def P(label: str, container: str, fs: str, bl: Optional[str] = None,
      apfs: Optional[Tuple[bool, bool]] = None) -> Dict[str, Any]:
    assert len(label) <= 11, label        # FAT32's label limit
    return dict(label=label, container=container, fs=fs, bl=bl, apfs=apfs,
                read_bytes=READ_BYTES_RO if fs in READ_ONLY_FS else READ_BYTES)


# container: plain | vc | luks1 | luks2 | luks2_4k | bitlocker | apfs
# fs:        fat32 | exfat | ntfs | ext4 | ext2 | ext3 | apfs
# bl:        BitLocker cipher (make_bitlocker_image.py)
# apfs:      (case_sensitive, encrypted) — built on the Mac, not here
DRIVES = {
    1: [
        P("D1APFSCI", "apfs", "apfs", apfs=(False, False)),
        P("D1APFSCS", "apfs", "apfs", apfs=(True, False)),
        P("D1APFSECI", "apfs", "apfs", apfs=(False, True)),
        P("D1APFSECS", "apfs", "apfs", apfs=(True, True)),
        P("D1FAT32", "plain", "fat32"),
        P("D1EXFAT", "plain", "exfat"),
        P("D1NTFS", "plain", "ntfs"),
        P("D1EXT4", "plain", "ext4"),
        P("D1BLNTFS", "bitlocker", "ntfs", bl="xts128"),
        P("D1BLEXFAT", "bitlocker", "exfat", bl="xts256"),
    ],
    2: [
        P("D2VCFAT32", "vc", "fat32"),
        P("D2VCEXFAT", "vc", "exfat"),
        P("D2VCNTFS", "vc", "ntfs"),
        P("D2VCEXT4", "vc", "ext4"),
        P("D2L1FAT32", "luks1", "fat32"),
        P("D2L1EXT4", "luks1", "ext4"),
        P("D2L2EXFAT", "luks2", "exfat"),
        P("D2L4EXT4", "luks2_4k", "ext4"),
        P("D2L4NTFS", "luks2_4k", "ntfs"),
        P("D2BLFAT32", "bitlocker", "fat32", bl="cbc128"),
    ],
    3: [
        P("D3L1EXFAT", "luks1", "exfat"),
        P("D3L1NTFS", "luks1", "ntfs"),
        P("D3L2FAT32", "luks2", "fat32"),
        P("D3L2NTFS", "luks2", "ntfs"),
        P("D3L2EXT4", "luks2", "ext4"),
        P("D3L4FAT32", "luks2_4k", "fat32"),
        P("D3L4EXFAT", "luks2_4k", "exfat"),
        P("D3EXT2", "plain", "ext2"),
        P("D3EXT3", "plain", "ext3"),
        P("D3BLNTFS", "bitlocker", "ntfs", bl="cbc256"),
    ],
    4: [
        P("D4L1EXT2", "luks1", "ext2"),
        P("D4L1EXT3", "luks1", "ext3"),
        P("D4L2EXT2", "luks2", "ext2"),
        P("D4L2EXT3", "luks2", "ext3"),
        P("D4L4EXT2", "luks2_4k", "ext2"),
        P("D4L4EXT3", "luks2_4k", "ext3"),
        P("D4VCEXT2", "vc", "ext2"),
        P("D4VCEXT3", "vc", "ext3"),
        P("D4BLFAT32", "bitlocker", "fat32", bl="xts256"),
        P("D4BLEXFAT", "bitlocker", "exfat", bl="cbc128"),
    ],
}


for _n, _parts in DRIVES.items():
    assert len(_parts) == 10, f"drive {_n} has {len(_parts)} partitions"
assert len({p["label"] for ps in DRIVES.values() for p in ps}) == 40
