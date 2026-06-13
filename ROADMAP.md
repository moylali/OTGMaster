# OTG Master Roadmap

This document outlines the planned features and enhancements for OTG Master, broken down into logical phases based on priority and user needs.

## Phase 1: Completed Core Features
- [x] Basic VeraCrypt USB detection and sector-level read access.
- [x] Password-based decryption of standard VeraCrypt headers.
- [x] Integration with `libaums` for FAT32 filesystem parsing.
- [x] Android Storage Access Framework (SAF) integration via `DocumentsProvider` to expose mounted drives system-wide.

## Phase 2: VeraCrypt Advanced Unlocking & Drive Management
- [ ] **Unmount Functionality**: Add a secure unmount option to lock the drive, clear encryption keys from memory, and detach the `DocumentsProvider`.
- [ ] **Advanced VeraCrypt Parameters**: Allow users to specify a Custom PIM (Personal Iterations Multiplier), use Keyfiles, and select hidden volumes during the unlocking process.

## Phase 3: Broader Filesystem & Write Support
- [ ] **Write Access**: Implement secure write operations to the VeraCrypt container (updating FAT32 tables, creating files/directories, deleting files) via the `libaums` write capabilities.
- [ ] **exFAT Support**: Integrate an exFAT parser to support drives formatted with exFAT (commonly used for larger USB drives and cross-platform compatibility).
- [ ] **NTFS Support**: Add read (and potentially write) support for NTFS formatted drives.

## Phase 4: Alternative Encryption Standards
- [ ] **LUKS Support**: Investigate and add support for LUKS (Linux Unified Key Setup) encrypted drives, which are widely used in the Linux ecosystem.
- [ ] **BitLocker To Go (Stretch Goal)**: Explore the feasibility of supporting Microsoft's BitLocker encrypted removable drives.
