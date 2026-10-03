# OTGMaster E2E Test Report (parallel, 3 emulators)

| Field | Value |
|-------|-------|
| Version | 0.4.1 |
| Commit | `9e958fd` |
| Date | 2026-10-02 21:23:08 |
| Shard logs | `e2e_parallel_20261002_205644/` |

## Results

| # | Test Case | Description | Result | Duration |
|---|-----------|-------------|--------|----------|
| 1 | `apfs_ci` | APFS, case-insensitive (Disk Utility's default), on a GPT disk built on macOS. Auto-mounts with no password; flower.jpg byte for byte; case.txt and CASE.txt are one file; the volume is read-only. | ✅ PASSED | 67s |
| 2 | `apfs_cs` | Case-sensitive APFS on a GPT disk built on macOS. Auto-mounts with no password; flower.jpg byte for byte; case.txt and CASE.txt are two files; the volume is read-only. | ✅ PASSED | 66s |
| 3 | `apfs_enc_ci` | APFS (Encrypted), case-insensitive, built on macOS. Offered in the unlock form tagged APFS; unlocks with the password; flower.jpg byte for byte; case.txt and CASE.txt are one file; the volume is read-only. | ✅ PASSED | 70s |
| 4 | `apfs_enc_cs` | Case-sensitive APFS (Encrypted), built on macOS. Offered in the unlock form tagged APFS; unlocks with the password; flower.jpg byte for byte; case.txt and CASE.txt are two files; the volume is read-only. | ✅ PASSED | 66s |
| 5 | `bitlocker_ntfs` | BitLocker AES-CBC-128 volume (Windows' default for removable drives) holding NTFS. Verifies the app recognises BitLocker, unlocks it with the password, mounts the NTFS and reads flower.jpg. | ✅ PASSED | 68s |
| 6 | `bitlocker_ntfs_write` | BitLocker XTS-AES-128 volume holding NTFS. Verifies create, write, nested directory, delete and their persistence across remounts through BitLocker. | ✅ PASSED (host-verified) | 87s |
| 7 | `exfat` | AES-encrypted VeraCrypt volume (10 MB) formatted as exFAT — the primary supported filesystem. | ✅ PASSED | 59s |
| 8 | `exfat_keyfile` | AES-encrypted VeraCrypt volume (10 MB) formatted as exFAT — the primary supported filesystem. Requires a keyfile (test.key); tests the SAF keyfile-picker UI flow. | ✅ PASSED | 64s |
| 9 | `exfat_write` | exFAT write support test: same lifecycle as fat32_write (create, persist across remount, delete, verify deletion persists) but using an exFAT-formatted VeraCrypt volume. | ✅ PASSED (host-verified) | 81s |
| 10 | `ext4` | AES-encrypted VeraCrypt volume formatted as ext4 (supported since 0.4.0; this case used to expect a refusal). Verifies the app unlocks it, mounts the ext4, and reads flower.jpg through the DocumentsProvider. | ✅ PASSED | 60s |
| 11 | `fat16` | AES-encrypted VeraCrypt volume formatted as FAT16 (not supported by the app). Verifies the app detects the filesystem type and shows 'Cannot mount' without mounting. | ✅ PASSED | 52s |
| 12 | `fat32` | AES-encrypted VeraCrypt volume (10 MB) formatted as FAT32, PIM=1. | ✅ PASSED | 80s |
| 13 | `fat32_keyfile` | AES-encrypted VeraCrypt volume (10 MB) formatted as FAT32, PIM=1. Requires a keyfile (test.key) in addition to the password. | ✅ PASSED | 65s |
| 14 | `fat32_keyfile_pim` | AES-encrypted VeraCrypt volume (10 MB) formatted as FAT32, PIM=123. Requires a keyfile (test.key) in addition to the password. Custom PIM tests that the key-derivation iteration count is honoured. | ✅ PASSED | 65s |
| 15 | `fat32_write` | FAT32 write support test: mounts an AES-encrypted FAT32 VeraCrypt volume, creates a file and directory via SAF, unmounts and remounts to verify persistence, deletes the file, then unmounts and remounts to verify the deletion was persisted. | ✅ PASSED (host-verified) | 82s |
| 16 | `m_bitlocker_exfat` | Support matrix: exFAT inside BitLocker (xts256). Unlock, flower.jpg byte for byte, create/write/mkdir, remount, delete, remount (second mount with the recovery key). | ✅ PASSED (host-verified) | 87s |
| 17 | `m_bitlocker_fat32` | Support matrix: FAT32 inside BitLocker (cbc128). Unlock, flower.jpg byte for byte, create/write/mkdir, remount, delete, remount (second mount with the recovery key). | ✅ PASSED (host-verified) | 89s |
| 18 | `m_bitlocker_ntfs` | Support matrix: NTFS inside BitLocker (xts128). Unlock, flower.jpg byte for byte, create/write/mkdir, remount, delete, remount (second mount with the recovery key). | ✅ PASSED (host-verified) | 95s |
| 19 | `m_luks1_exfat` | Support matrix: exFAT inside LUKS1 aes-xts-plain64 (PBKDF2). Unlock, flower.jpg byte for byte, create/write/mkdir, remount, delete, remount. | ✅ PASSED (host-verified) | 194s |
| 20 | `m_luks1_ext2` | Support matrix: ext2 inside LUKS1 aes-xts-plain64 (PBKDF2). Unlock, read flower.jpg; the volume must mount READ-ONLY and refuse a create (the app cannot yet write ext2/ext3 without damaging it). | ✅ PASSED | 108s |
| 21 | `m_luks1_ext3` | Support matrix: ext3 inside LUKS1 aes-xts-plain64 (PBKDF2). Unlock, read flower.jpg; the volume must mount READ-ONLY and refuse a create (the app cannot yet write ext2/ext3 without damaging it). | ✅ PASSED | 105s |
| 22 | `m_luks1_ext4` | Support matrix: ext4 inside LUKS1 aes-xts-plain64 (PBKDF2). Unlock, flower.jpg byte for byte, create/write/mkdir, remount, delete, remount. | ✅ PASSED (host-verified) | 189s |
| 23 | `m_luks1_fat32` | Support matrix: FAT32 inside LUKS1 aes-xts-plain64 (PBKDF2). Unlock, flower.jpg byte for byte, create/write/mkdir, remount, delete, remount. | ✅ PASSED (host-verified) | 200s |
| 24 | `m_luks1_ntfs` | Support matrix: NTFS inside LUKS1 aes-xts-plain64 (PBKDF2). Unlock, flower.jpg byte for byte, create/write/mkdir, remount, delete, remount. | ✅ PASSED (host-verified) | 170s |
| 25 | `m_luks2_4k_exfat` | Support matrix: exFAT inside LUKS2 aes-xts-plain64 (Argon2id, 4096-byte sectors). Unlock, flower.jpg byte for byte, create/write/mkdir, remount, delete, remount. | ✅ PASSED (host-verified) | 121s |
| 26 | `m_luks2_4k_ext2` | Support matrix: ext2 inside LUKS2 aes-xts-plain64 (Argon2id, 4096-byte sectors). Unlock, read flower.jpg; the volume must mount READ-ONLY and refuse a create (the app cannot yet write ext2/ext3 without damaging it). | ✅ PASSED | 76s |
| 27 | `m_luks2_4k_ext3` | Support matrix: ext3 inside LUKS2 aes-xts-plain64 (Argon2id, 4096-byte sectors). Unlock, read flower.jpg; the volume must mount READ-ONLY and refuse a create (the app cannot yet write ext2/ext3 without damaging it). | ✅ PASSED | 76s |
| 28 | `m_luks2_4k_ext4` | Support matrix: ext4 inside LUKS2 aes-xts-plain64 (Argon2id, 4096-byte sectors). Unlock, flower.jpg byte for byte, create/write/mkdir, remount, delete, remount. | ✅ PASSED (host-verified) | 118s |
| 29 | `m_luks2_4k_fat32` | Support matrix: FAT32 inside LUKS2 aes-xts-plain64 (Argon2id, 4096-byte sectors). Unlock, flower.jpg byte for byte, create/write/mkdir, remount, delete, remount. | ✅ PASSED (host-verified) | 105s |
| 30 | `m_luks2_4k_ntfs` | Support matrix: NTFS inside LUKS2 aes-xts-plain64 (Argon2id, 4096-byte sectors). Unlock, flower.jpg byte for byte, create/write/mkdir, remount, delete, remount. | ✅ PASSED (host-verified) | 109s |
| 31 | `m_luks2_exfat` | Support matrix: exFAT inside LUKS2 aes-xts-plain64 (Argon2id, 512-byte sectors). Unlock, flower.jpg byte for byte, create/write/mkdir, remount, delete, remount. | ✅ PASSED (host-verified) | 115s |
| 32 | `m_luks2_ext2` | Support matrix: ext2 inside LUKS2 aes-xts-plain64 (Argon2id, 512-byte sectors). Unlock, read flower.jpg; the volume must mount READ-ONLY and refuse a create (the app cannot yet write ext2/ext3 without damaging it). | ✅ PASSED | 74s |
| 33 | `m_luks2_ext3` | Support matrix: ext3 inside LUKS2 aes-xts-plain64 (Argon2id, 512-byte sectors). Unlock, read flower.jpg; the volume must mount READ-ONLY and refuse a create (the app cannot yet write ext2/ext3 without damaging it). | ✅ PASSED | 80s |
| 34 | `m_luks2_ext4` | Support matrix: ext4 inside LUKS2 aes-xts-plain64 (Argon2id, 512-byte sectors). Unlock, flower.jpg byte for byte, create/write/mkdir, remount, delete, remount. | ✅ PASSED (host-verified) | 107s |
| 35 | `m_luks2_fat32` | Support matrix: FAT32 inside LUKS2 aes-xts-plain64 (Argon2id, 512-byte sectors). Unlock, flower.jpg byte for byte, create/write/mkdir, remount, delete, remount. | ✅ PASSED (host-verified) | 105s |
| 36 | `m_luks2_ntfs` | Support matrix: NTFS inside LUKS2 aes-xts-plain64 (Argon2id, 512-byte sectors). Unlock, flower.jpg byte for byte, create/write/mkdir, remount, delete, remount. | ✅ PASSED (host-verified) | 103s |
| 37 | `m_vc_exfat` | Support matrix: exFAT inside VeraCrypt AES/SHA-512. Unlock, flower.jpg byte for byte, create/write/mkdir, remount, delete, remount. | ✅ PASSED (host-verified) | 80s |
| 38 | `m_vc_ext2` | Support matrix: ext2 inside VeraCrypt AES/SHA-512. Unlock, read flower.jpg; the volume must mount READ-ONLY and refuse a create (the app cannot yet write ext2/ext3 without damaging it). | ✅ PASSED | 60s |
| 39 | `m_vc_ext3` | Support matrix: ext3 inside VeraCrypt AES/SHA-512. Unlock, read flower.jpg; the volume must mount READ-ONLY and refuse a create (the app cannot yet write ext2/ext3 without damaging it). | ✅ PASSED | 60s |
| 40 | `m_vc_ext4` | Support matrix: ext4 inside VeraCrypt AES/SHA-512. Unlock, flower.jpg byte for byte, create/write/mkdir, remount, delete, remount. | ✅ PASSED (host-verified) | 79s |
| 41 | `m_vc_fat32` | Support matrix: FAT32 inside VeraCrypt AES/SHA-512. Unlock, flower.jpg byte for byte, create/write/mkdir, remount, delete, remount. | ✅ PASSED (host-verified) | 81s |
| 42 | `m_vc_ntfs` | Support matrix: NTFS inside VeraCrypt AES/SHA-512. Unlock, flower.jpg byte for byte, create/write/mkdir, remount, delete, remount. | ✅ PASSED (host-verified) | 79s |
| 43 | `ntfs` | AES-encrypted VeraCrypt volume formatted as NTFS. Verifies the app unlocks it, mounts the NTFS, and reads flower.jpg through the DocumentsProvider. | ✅ PASSED | 60s |
| 44 | `ntfs_write` | AES-encrypted VeraCrypt volume formatted as NTFS. Verifies create, write, nested directory, delete and their persistence across remounts. | ✅ PASSED (host-verified) | 80s |
| 45 | `partitioned_mbr` | Raw disk image (20 MB) with an MBR partition table. Partition 1 (1–5 MiB) is a plain FAT32 partition; partition 2 (5–15 MiB) is an AES-encrypted VeraCrypt volume formatted as FAT32. Tests MBR partition scanning, the candidate-picker UI, and mounting a VeraCrypt volume embedded in a partitioned disk. | ✅ PASSED | 68s |
| 46 | `serpent` | AES replaced by Serpent cipher; otherwise identical to the base fat32 case. | ✅ PASSED | 68s |
| 47 | `unsupported_cipher` | AES-encrypted FAT32 VeraCrypt volume, but the UI cipher picker is set to Twofish before mounting. The app must reject the attempt immediately (header cannot be decrypted) and show 'Cannot mount' — no I/O on the actual volume data occurs. | ✅ PASSED | 54s |

## Summary

| Metric | Value |
|--------|-------|
| Selected | 47 |
| Ran | 47 |
| Passed | 47 |
| Flaky (passed on retry, counted in Passed) | 0 |
| Failed | 0 |
| Not run (a shard died) | 0 |
| Overall | ✅ ALL PASSED |

## Notes

- First run with APFS: the four `apfs_*` cases (images built on macOS 26.6.2,
  `testdata/apfs/PROVENANCE.md`). The plain two auto-mount with no form; the
  encrypted two are offered tagged APFS and unlock with the password. All four check
  flower.jpg byte for byte through the DocumentsProvider, the volume's case
  behaviour (case.txt / CASE.txt), the READ-ONLY tag and a refused create. APFS is
  read-only, so none of them is host-verified: there is no write to check.
- 23 write cases host-verified (decrypted independently, filesystem checker clean,
  files checked through the kernel's driver), as on `3c8942a`.
- An earlier run of only the APFS cases on `a4d965d` failed the plain two at the
  last step, "Unmount button not found": a plain drive has no Unmount button by
  design. Fixed in the test (`9e958fd`); on `9e958fd` the four passed on their own
  (`e2e_report_v0.4.1_9e958fd_20261002_205340_parallel.md`) and again here.
