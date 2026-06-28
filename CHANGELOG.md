# Changelog

## [v0.3.7] - 2026-06-28

### Fixed
- Restored hub batch auto-mount: all drives connected via a USB hub are now shown in a single biometric prompt again (regression introduced in v0.3.6).
- Cancelling the auto-mount biometric prompt no longer crashes; all devices remain listed in the dropdown.
- The "Opt out of Auto mount" switch no longer appears on the quick-unlock view (devices with cached credentials); it now only shows in the full credential form.
- After selecting keyfiles from the file picker, the keyboard no longer reopens on the PIM field.

### Added
- **Clear credentials per device**: mounted drive cards now show a "Clear credentials" button when that device has cached auto-mount credentials, allowing individual credential removal without clearing all devices.
- Mounted drive card buttons now wrap to a second row on narrow screens instead of clipping.

## [v0.3.6] - 2026-06-28

### Fixed
- Fixed opening files (PDFs, videos, documents) from mounted drives in third-party apps; the previous pipe-based implementation did not support seeking, causing apps like Google Drive and PDF viewers to fail.

## [v0.3.5] - 2026-06-27

### Fixed
- Bumped target SDK to 35 (required by Google Play).

## [v0.3.4] - 2026-06-27

### Added
- **Auto Mount**: Automatically mounts known VeraCrypt volumes on USB connect using biometric authentication and encrypted credential storage.
- **Hub support**: Multiple USB devices connected via a hub are now all auto-mounted in a single biometric prompt (400ms debounce window).

### Fixed
- Auto mount no longer re-triggers after manually unmounting a drive within the same session; the form pre-fills instead.
- Fixed drive corruption on unmount and USB hot-unplug in the exFAT layer.
- Fixed several post-unmount crash paths in the exFAT JNI layer.
- Serialized all ExFAT JNI calls to prevent concurrent reference-count corruption.
- Auto mount now correctly stores and replays the exact volume candidate (start block) across reconnects.
- Fixed `MainActivity` stacking on repeated USB device attach events.
- Fixed Files app opening at the correct mount root path.
- Clearing Auto Mount now also clears the cached credentials for that device.
- Fixed a race condition in QEMU disk probing that caused `StaleObjectException` in E2E write tests.

## [v0.3.0] - 2026-06-26

### Added
- **exFAT Write Support**: Implemented comprehensive write capabilities for exFAT filesystems on VeraCrypt volumes.

### Fixed
- Fixed an `ActivityManager` freeze/IPC crash occurring during successive mounting operations, caused by a double free of `node->name` in the JNI layer.
- Fixed `SIGSEGV` crash triggered by the `FinalizerDaemon` during GC, caused by missing thread synchronization between `ExFatFile.close()` and `ExFatFileSystem.unmount()`.
- Fixed E2E test failures and implemented missing `partitioned_mbr` tests.
