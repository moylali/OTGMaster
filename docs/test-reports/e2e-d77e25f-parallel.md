# OTGMaster E2E Test Report (parallel, 3 emulators)

| Field | Value |
|-------|-------|
| Version | 0.4.1 |
| Commit | `d77e25f` |
| Date | 2026-10-01 23:33:39 |
| Shard logs | `e2e_parallel_20261001_230857/` |

## Results

| # | Test Case | Description | Result | Duration |
|---|-----------|-------------|--------|----------|
| 1 | `bitlocker_ntfs` | BitLocker AES-CBC-128 volume (Windows' default for removable drives) holding NTFS. Verifies the app recognises BitLocker, unlocks it with the password, mounts the NTFS and reads flower.jpg. | ✅ PASSED | 69s |
| 2 | `bitlocker_ntfs_write` | BitLocker XTS-AES-128 volume holding NTFS. Verifies create, write, nested directory, delete and their persistence across remounts through BitLocker. | ✅ PASSED | 84s |
| 3 | `exfat` | AES-encrypted VeraCrypt volume (10 MB) formatted as exFAT — the primary supported filesystem. | ✅ PASSED | 60s |
| 4 | `exfat_keyfile` | AES-encrypted VeraCrypt volume (10 MB) formatted as exFAT — the primary supported filesystem. Requires a keyfile (test.key); tests the SAF keyfile-picker UI flow. | ✅ PASSED | 64s |
| 5 | `exfat_write` | exFAT write support test: same lifecycle as fat32_write (create, persist across remount, delete, verify deletion persists) but using an exFAT-formatted VeraCrypt volume. | ✅ PASSED | 77s |
| 6 | `ext4` | AES-encrypted VeraCrypt volume formatted as ext4 (supported since 0.4.0; this case used to expect a refusal). Verifies the app unlocks it, mounts the ext4, and reads flower.jpg through the DocumentsProvider. | ✅ PASSED | 59s |
| 7 | `fat16` | AES-encrypted VeraCrypt volume formatted as FAT16 (not supported by the app). Verifies the app detects the filesystem type and shows 'Cannot mount' without mounting. | ✅ PASSED | 52s |
| 8 | `fat32` | AES-encrypted VeraCrypt volume (10 MB) formatted as FAT32, PIM=1. | ✅ PASSED | 80s |
| 9 | `fat32_keyfile` | AES-encrypted VeraCrypt volume (10 MB) formatted as FAT32, PIM=1. Requires a keyfile (test.key) in addition to the password. | ✅ PASSED | 63s |
| 10 | `fat32_keyfile_pim` | AES-encrypted VeraCrypt volume (10 MB) formatted as FAT32, PIM=123. Requires a keyfile (test.key) in addition to the password. Custom PIM tests that the key-derivation iteration count is honoured. | ✅ PASSED | 64s |
| 11 | `fat32_write` | FAT32 write support test: mounts an AES-encrypted FAT32 VeraCrypt volume, creates a file and directory via SAF, unmounts and remounts to verify persistence, deletes the file, then unmounts and remounts to verify the deletion was persisted. | ✅ PASSED | 75s |
| 12 | `m_bitlocker_exfat` | Support matrix: exFAT inside BitLocker (xts256). Unlock, flower.jpg byte for byte, create/write/mkdir, remount, delete, remount (second mount with the recovery key). | ✅ PASSED | 82s |
| 13 | `m_bitlocker_fat32` | Support matrix: FAT32 inside BitLocker (cbc128). Unlock, flower.jpg byte for byte, create/write/mkdir, remount, delete, remount (second mount with the recovery key). | ✅ PASSED | 83s |
| 14 | `m_bitlocker_ntfs` | Support matrix: NTFS inside BitLocker (xts128). Unlock, flower.jpg byte for byte, create/write/mkdir, remount, delete, remount (second mount with the recovery key). | ✅ PASSED | 82s |
| 15 | `m_luks1_exfat` | Support matrix: exFAT inside LUKS1 aes-xts-plain64 (PBKDF2). Unlock, flower.jpg byte for byte, create/write/mkdir, remount, delete, remount. | ✅ PASSED | 201s |
| 16 | `m_luks1_ext2` | Support matrix: ext2 inside LUKS1 aes-xts-plain64 (PBKDF2). Unlock, flower.jpg byte for byte, create/write/mkdir, remount, delete, remount. | ✅ PASSED | 212s |
| 17 | `m_luks1_ext3` | Support matrix: ext3 inside LUKS1 aes-xts-plain64 (PBKDF2). Unlock, flower.jpg byte for byte, create/write/mkdir, remount, delete, remount. | ✅ PASSED | 209s |
| 18 | `m_luks1_ext4` | Support matrix: ext4 inside LUKS1 aes-xts-plain64 (PBKDF2). Unlock, flower.jpg byte for byte, create/write/mkdir, remount, delete, remount. | ✅ PASSED | 186s |
| 19 | `m_luks1_fat32` | Support matrix: FAT32 inside LUKS1 aes-xts-plain64 (PBKDF2). Unlock, flower.jpg byte for byte, create/write/mkdir, remount, delete, remount. | ✅ PASSED | 202s |
| 20 | `m_luks1_ntfs` | Support matrix: NTFS inside LUKS1 aes-xts-plain64 (PBKDF2). Unlock, flower.jpg byte for byte, create/write/mkdir, remount, delete, remount. | ✅ PASSED | 197s |
| 21 | `m_luks2_4k_exfat` | Support matrix: exFAT inside LUKS2 aes-xts-plain64 (Argon2id, 4096-byte sectors). Unlock, flower.jpg byte for byte, create/write/mkdir, remount, delete, remount. | ✅ PASSED | 103s |
| 22 | `m_luks2_4k_ext2` | Support matrix: ext2 inside LUKS2 aes-xts-plain64 (Argon2id, 4096-byte sectors). Unlock, flower.jpg byte for byte, create/write/mkdir, remount, delete, remount. | ✅ PASSED | 101s |
| 23 | `m_luks2_4k_ext3` | Support matrix: ext3 inside LUKS2 aes-xts-plain64 (Argon2id, 4096-byte sectors). Unlock, flower.jpg byte for byte, create/write/mkdir, remount, delete, remount. | ✅ PASSED | 99s |
| 24 | `m_luks2_4k_ext4` | Support matrix: ext4 inside LUKS2 aes-xts-plain64 (Argon2id, 4096-byte sectors). Unlock, flower.jpg byte for byte, create/write/mkdir, remount, delete, remount. | ✅ PASSED | 99s |
| 25 | `m_luks2_4k_fat32` | Support matrix: FAT32 inside LUKS2 aes-xts-plain64 (Argon2id, 4096-byte sectors). Unlock, flower.jpg byte for byte, create/write/mkdir, remount, delete, remount. | ✅ PASSED | 101s |
| 26 | `m_luks2_4k_ntfs` | Support matrix: NTFS inside LUKS2 aes-xts-plain64 (Argon2id, 4096-byte sectors). Unlock, flower.jpg byte for byte, create/write/mkdir, remount, delete, remount. | ✅ PASSED | 101s |
| 27 | `m_luks2_exfat` | Support matrix: exFAT inside LUKS2 aes-xts-plain64 (Argon2id, 512-byte sectors). Unlock, flower.jpg byte for byte, create/write/mkdir, remount, delete, remount. | ✅ PASSED | 102s |
| 28 | `m_luks2_ext2` | Support matrix: ext2 inside LUKS2 aes-xts-plain64 (Argon2id, 512-byte sectors). Unlock, flower.jpg byte for byte, create/write/mkdir, remount, delete, remount. | ✅ PASSED | 100s |
| 29 | `m_luks2_ext3` | Support matrix: ext3 inside LUKS2 aes-xts-plain64 (Argon2id, 512-byte sectors). Unlock, flower.jpg byte for byte, create/write/mkdir, remount, delete, remount. | ✅ PASSED | 102s |
| 30 | `m_luks2_ext4` | Support matrix: ext4 inside LUKS2 aes-xts-plain64 (Argon2id, 512-byte sectors). Unlock, flower.jpg byte for byte, create/write/mkdir, remount, delete, remount. | ✅ PASSED | 101s |
| 31 | `m_luks2_fat32` | Support matrix: FAT32 inside LUKS2 aes-xts-plain64 (Argon2id, 512-byte sectors). Unlock, flower.jpg byte for byte, create/write/mkdir, remount, delete, remount. | ✅ PASSED | 100s |
| 32 | `m_luks2_ntfs` | Support matrix: NTFS inside LUKS2 aes-xts-plain64 (Argon2id, 512-byte sectors). Unlock, flower.jpg byte for byte, create/write/mkdir, remount, delete, remount. | ✅ PASSED | 99s |
| 33 | `m_vc_exfat` | Support matrix: exFAT inside VeraCrypt AES/SHA-512. Unlock, flower.jpg byte for byte, create/write/mkdir, remount, delete, remount. | ✅ PASSED | 77s |
| 34 | `m_vc_ext2` | Support matrix: ext2 inside VeraCrypt AES/SHA-512. Unlock, flower.jpg byte for byte, create/write/mkdir, remount, delete, remount. | ✅ PASSED | 75s |
| 35 | `m_vc_ext3` | Support matrix: ext3 inside VeraCrypt AES/SHA-512. Unlock, flower.jpg byte for byte, create/write/mkdir, remount, delete, remount. | ✅ PASSED | 77s |
| 36 | `m_vc_ext4` | Support matrix: ext4 inside VeraCrypt AES/SHA-512. Unlock, flower.jpg byte for byte, create/write/mkdir, remount, delete, remount. | ✅ PASSED | 77s |
| 37 | `m_vc_fat32` | Support matrix: FAT32 inside VeraCrypt AES/SHA-512. Unlock, flower.jpg byte for byte, create/write/mkdir, remount, delete, remount. | ✅ PASSED | 76s |
| 38 | `m_vc_ntfs` | Support matrix: NTFS inside VeraCrypt AES/SHA-512. Unlock, flower.jpg byte for byte, create/write/mkdir, remount, delete, remount. | ✅ PASSED | 75s |
| 39 | `ntfs` | AES-encrypted VeraCrypt volume formatted as NTFS. Verifies the app unlocks it, mounts the NTFS, and reads flower.jpg through the DocumentsProvider. | ✅ PASSED | 60s |
| 40 | `ntfs_write` | AES-encrypted VeraCrypt volume formatted as NTFS. Verifies create, write, nested directory, delete and their persistence across remounts. | ✅ PASSED | 77s |
| 41 | `partitioned_mbr` | Raw disk image (20 MB) with an MBR partition table. Partition 1 (1–5 MiB) is a plain FAT32 partition; partition 2 (5–15 MiB) is an AES-encrypted VeraCrypt volume formatted as FAT32. Tests MBR partition scanning, the candidate-picker UI, and mounting a VeraCrypt volume embedded in a partitioned disk. | ✅ PASSED | 67s |
| 42 | `serpent` | AES replaced by Serpent cipher; otherwise identical to the base fat32 case. | ✅ PASSED | 66s |
| 43 | `unsupported_cipher` | AES-encrypted FAT32 VeraCrypt volume, but the UI cipher picker is set to Twofish before mounting. The app must reject the attempt immediately (header cannot be decrypted) and show 'Cannot mount' — no I/O on the actual volume data occurs. | ✅ PASSED | 54s |

## Summary

| Metric | Value |
|--------|-------|
| Selected | 43 |
| Ran | 43 |
| Passed | 43 |
| Flaky (passed on retry, counted in Passed) | 0 |
| Failed | 0 |
| Not run (a shard died) | 0 |
| Overall | ✅ ALL PASSED |
