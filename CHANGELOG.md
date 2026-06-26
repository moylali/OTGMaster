# Changelog

## [v0.3.0] - 2026-06-26

### Added
- **exFAT Write Support**: Implemented comprehensive write capabilities for exFAT filesystems on VeraCrypt volumes.

### Fixed
- Fixed an `ActivityManager` freeze/IPC crash occurring during successive mounting operations, caused by a double free of `node->name` in the JNI layer.
- Fixed `SIGSEGV` crash triggered by the `FinalizerDaemon` during GC, caused by missing thread synchronization between `ExFatFile.close()` and `ExFatFileSystem.unmount()`.
- Fixed E2E test failures and implemented missing `partitioned_mbr` tests.
