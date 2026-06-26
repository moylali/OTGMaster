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
- [ ] **ext2/ext3/ext4 Support**: Add read (and potentially write) support for Linux native filesystems. Currently detected and version-distinguished but rejected.
- [ ] **F2FS Support**: Add read support for the Flash-Friendly File System, common on Android and some Linux devices. Currently detected but rejected.
- [ ] **HFS+ Support**: Add read support for Apple's HFS+ (macOS Extended) filesystem. Currently detected but rejected.
- [ ] **APFS Support**: Add read support for Apple's APFS container format used on newer macOS drives. Currently detected but rejected.

## Phase 4: Alternative Encryption Standards
- [ ] **LUKS Support**: Add support for LUKS (Linux Unified Key Setup) encrypted drives, widely used by Linux distros for removable media.

  **Suggested strategy:**
  1. **Header parsing (native, alongside `VeraCryptNative.cpp`)**: Add a `LuksNative.cpp` that parses the on-disk header directly from the decrypted-but-unparsed block stream. LUKS1 has a fixed binary header (magic `LUKS\xba\xbe`, cipher/hash name strings, 8 key slots with AF-split material); LUKS2 replaces this with a JSON metadata area (still prefixed by the same magic) plus a binary keyslot area — start with LUKS1 since the format is far simpler and still common on Linux-formatted USB/flash media, then extend the parser to read LUKS2's JSON metadata for `cipher`, `keyslots`, and `digest` info.
  2. **Key derivation**: LUKS1 key slots are unlocked via PBKDF2-HMAC, identical in shape to what VeraCrypt header decryption already does — `mbedtls_pkcs5_pbkdf2_hmac` can be reused as-is, just with the iteration count/salt read from the LUKS key slot instead of the VeraCrypt header. LUKS2 defaults to Argon2i/Argon2id, which mbedtls does not provide — for LUKS2 we'd need to vendor a small Argon2 implementation (e.g. the reference `phc-winner-argon2` C library) the same way `exfat/` was vendored, gated so LUKS1-only support can ship without it.
  3. **Master key recovery**: Implement AF-splitter (anti-forensic split) merging to reassemble the master key from the decrypted key-slot material — this is a small, self-contained diffusion function (uses SHA-1 in LUKS1) with no external dependency.
  4. **Bulk decryption**: LUKS' default `aes-xts-plain64` cipher maps directly onto the existing `mbedtls_aes_crypt_xts` calls already used for VeraCrypt sectors — the same `RawBlockDevice`/`SlicedBlockDevice` plumbing can wrap a LUKS-decrypted device with only the sector-IV computation (plain64: IV = little-endian sector number) differing from VeraCrypt's data-unit numbering.
  5. **Inner filesystem**: Linux-formatted LUKS volumes are overwhelmingly ext4, so this work should be sequenced after (or alongside) the **ext2/ext3/ext4 Support** item in Phase 3 — LUKS unlocking is otherwise decryption-only effort with no filesystem payoff until ext4 read support lands.
  6. **Detection**: Add a LUKS magic check (`LUKS\xba\xbe` at sector 0) to `FilesystemDetector`'s VeraCrypt-equivalent entry point, distinct from the existing inner-filesystem checks, since LUKS replaces the VeraCrypt header step rather than the filesystem-detection step.
- [ ] **BitLocker To Go (Stretch Goal)**: Explore the feasibility of supporting Microsoft's BitLocker encrypted removable drives.

## Phase 5: E2E Test Coverage Gaps

Real-device testing surfaced several bugs that the existing e2e suite (`E2EAutomatedTest.kt`, QEMU-based) never exercised, because it only ever tested a single VeraCrypt volume occupying the *whole device* (no partition table) with a small, flat set of test files. Closing these gaps would have caught real regressions before they shipped:

- [x] **Multiple simultaneously-attached USB drives**: Configure the QEMU test harness to expose 2+ virtual USB mass-storage devices at once. Verify the device picker appears only with 2+ devices, shows correct friendly names, excludes already-mounted devices, and that mounting one automatically re-probes for the next.
- [x] **VeraCrypt volumes inside a partition, not just "whole device"**: Add test volumes on both MBR and GPT partitions at a variety of nonzero starting offsets. This is the exact class of bug that let the XTS tweak-offset miscalculation ship undetected for an entire release — the existing suite's offset-0 "whole device" volume happened to make the bug invisible (physical sector number and volume-relative sector number coincide only when the volume starts at sector 0).
- [x] **Mixed partition layouts**: A single disk with a non-VeraCrypt partition (e.g. an ISO9660/ESP boot partition, as found on hybrid bootable USB sticks) alongside a VeraCrypt partition, to exercise the "skip unsupported, find the real one" candidate-selection path.
- [x] **File/directory combinations inside the mounted volume**: nested/deeply-nested directories, empty directories, empty files, files spanning multiple clusters, many small files in one directory, filenames with spaces/unicode/special characters. The root-directory DocumentsProvider crash (`file.length`/`file.lastModified()` throwing for the root) specifically went unnoticed because no test ever actually queried the root document through the Files-app-facing provider path, only through the app's own internal mount flow.
- [x] **Mount → unmount → remount cycles**: Verify a device can be unlocked, unmounted, and successfully re-unlocked again without an app restart — this is the regression class for resource-leak bugs (e.g. the underlying USB connection never being released on unmount).
- [x] **Keyfile + PIM combinations**: Volumes created with a custom PIM, with one or more keyfiles, and with both together, since these multiply the password-derivation path independently of cipher/hash selection and have been a recurring source of "wrong password" false negatives during manual testing.
