# OTG Master Roadmap

This document outlines the planned features and enhancements for OTG Master, broken down into logical phases based on priority and user needs.

## Phase 1: Completed Core Features
- [x] Basic VeraCrypt USB detection and sector-level read access.
- [x] Password-based decryption of standard VeraCrypt headers.
- [x] Integration with `libaums` for FAT32 filesystem parsing.
- [x] Android Storage Access Framework (SAF) integration via `DocumentsProvider` to expose mounted drives system-wide.
- [x] `FilesystemDetector` to identify the inner filesystem of a decrypted volume and surface a clear "unsupported filesystem" error for types we don't yet mount.
- [x] **exFAT Support**: Read support for exFAT, validated via the e2e suite.

## Phase 2: VeraCrypt Advanced Unlocking & Drive Management
- [ ] **Unmount Functionality**: Add a secure unmount option to lock the drive, clear encryption keys from memory, and detach the `DocumentsProvider`.
- [ ] **Advanced VeraCrypt Parameters**: Allow users to specify a Custom PIM (Personal Iterations Multiplier), use Keyfiles, and select hidden volumes during the unlocking process.

## Phase 3: Broader Filesystem & Write Support
- [ ] **Write Access**: Implement secure write operations to the VeraCrypt container (updating FAT32 tables, creating files/directories, deleting files) via the `libaums` write capabilities.
- [ ] **NTFS Support**: Add read (and potentially write) support for NTFS formatted drives. Currently detected but rejected with an "unsupported filesystem" error.
- [ ] **FAT16 Support**: Add read support for legacy FAT16 volumes (common on smaller/older USB drives). Currently detected but rejected.
- [ ] **FAT12 Support**: Add read support for FAT12 volumes (legacy, very small media). Currently detected but rejected.
- [x] **ext2/ext3/ext4 Support**: Read support for Linux native filesystems via a pure-Kotlin ext4 driver. Validated on LUKS1/LUKS2 volumes.
- [ ] **F2FS Support**: Add read support for the Flash-Friendly File System, common on Android and some Linux devices. Currently detected but rejected.
- [ ] **HFS+ Support**: Add read support for Apple's HFS+ (macOS Extended) filesystem. Currently detected but rejected.
- [ ] **APFS Support**: Add read support for Apple's APFS container format used on newer macOS drives. Currently detected but rejected.

## Phase 4: Alternative Encryption Standards
- [x] **LUKS Support**: LUKS1 and LUKS2 encrypted drives are fully supported. Detection reads the `LUKS\xba\xbe` magic at the partition start; LUKS1 uses PBKDF2-HMAC key derivation via mbedTLS; LUKS2 uses Argon2id via the vendored `argon2kt` library. Both path through `aes-xts-plain64` bulk decryption using the existing mbedTLS XTS calls. Validated on LUKS1+FAT32, LUKS1+exFAT, LUKS1+ext4, LUKS2+FAT32, LUKS2+exFAT, and LUKS2+ext4 volumes.
- [ ] **BitLocker To Go (Stretch Goal)**: Explore the feasibility of supporting Microsoft's BitLocker encrypted removable drives.

## Phase 5: E2E Test Coverage Gaps

Real-device testing surfaced several bugs that the existing e2e suite (`E2EAutomatedTest.kt`, QEMU-based) never exercised, because it only ever tested a single VeraCrypt volume occupying the *whole device* (no partition table) with a small, flat set of test files. Closing these gaps would have caught real regressions before they shipped:

- [x] **Multiple simultaneously-attached USB drives**: Configure the QEMU test harness to expose 2+ virtual USB mass-storage devices at once. Verify the device picker appears only with 2+ devices, shows correct friendly names, excludes already-mounted devices, and that mounting one automatically re-probes for the next.
- [x] **VeraCrypt volumes inside a partition, not just "whole device"**: Add test volumes on both MBR and GPT partitions at a variety of nonzero starting offsets. This is the exact class of bug that let the XTS tweak-offset miscalculation ship undetected for an entire release — the existing suite's offset-0 "whole device" volume happened to make the bug invisible (physical sector number and volume-relative sector number coincide only when the volume starts at sector 0).
- [x] **Mixed partition layouts**: A single disk with a non-VeraCrypt partition (e.g. an ISO9660/ESP boot partition, as found on hybrid bootable USB sticks) alongside a VeraCrypt partition, to exercise the "skip unsupported, find the real one" candidate-selection path.
- [x] **File/directory combinations inside the mounted volume**: nested/deeply-nested directories, empty directories, empty files, files spanning multiple clusters, many small files in one directory, filenames with spaces/unicode/special characters. The root-directory DocumentsProvider crash (`file.length`/`file.lastModified()` throwing for the root) specifically went unnoticed because no test ever actually queried the root document through the Files-app-facing provider path, only through the app's own internal mount flow.
- [x] **Mount → unmount → remount cycles**: Verify a device can be unlocked, unmounted, and successfully re-unlocked again without an app restart — this is the regression class for resource-leak bugs (e.g. the underlying USB connection never being released on unmount).
- [x] **Keyfile + PIM combinations**: Volumes created with a custom PIM, with one or more keyfiles, and with both together, since these multiply the password-derivation path independently of cipher/hash selection and have been a recurring source of "wrong password" false negatives during manual testing.

## Phase 6: Pending E2E Testcases (Upcoming Support)

As new filesystem support is rolled out (see Phase 3 & 4), the E2E test suite must be expanded to cover these formats:

- [ ] **NTFS volumes**: Verify successful detection and mount of NTFS formatted volumes.
- [ ] **FAT16/FAT12 volumes**: Verify legacy FAT variants are mounted successfully.
- [ ] **ext2/ext3/ext4 volumes**: Add end-to-end tests for Linux native filesystems.
- [ ] **LUKS encrypted volumes**: Verify that standard LUKS volumes can be decrypted, mounted, and interact seamlessly alongside VeraCrypt volumes.
- [ ] **Write operations**: Once write access is enabled, add tests that write to FAT32/exFAT (create, delete, modify) and verify state persistence by reading it back from the host.
