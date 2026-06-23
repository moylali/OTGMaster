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
