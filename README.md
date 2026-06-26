# OTG Master

[![CI](https://github.com/moylali/OTGMaster/actions/workflows/ci.yml/badge.svg)](https://github.com/moylali/OTGMaster/actions/workflows/ci.yml)
[![License: GPL v2 or later](https://img.shields.io/badge/license-GPL--2.0--or--later-blue.svg)](LICENSE)

Android app for opening VeraCrypt-encrypted USB mass-storage devices without root.

The app cannot perform a kernel mount on non-rooted Android. Instead it:

1. Requests USB Host permission for a mass-storage device.
2. Reads raw sectors through a userspace USB Mass Storage adapter ([libaums](libaums/)).
3. Probes MBR/GPT partitions to find VeraCrypt volume starts.
4. Unlocks a VeraCrypt header (AES or Serpent, SHA-512) and exposes a decrypted block-device wrapper.
5. Feeds the decrypted block device into a userspace filesystem reader (FAT32 via libaums, exFAT via a vendored native `libexfat`).
6. Surfaces files through the app UI and a `DocumentsProvider`, so other apps (Files, Gallery, etc.) can browse the unlocked volume.

See [ROADMAP.md](docs/ROADMAP.md) for planned filesystem and encryption-algorithm support.

## Build

```bash
./gradlew assembleDebug
```

A signed release build additionally requires a `keystore.properties` file at the repo root
(never commit this — see `.gitignore`) with `storeFile`, `storePassword`, `keyAlias`, and
`keyPassword` entries; without it, `assembleRelease` produces an unsigned APK.

```bash
./gradlew assembleRelease
```

## Testing

JVM unit tests (e.g. `FilesystemDetector`) run without a device:

```bash
./gradlew testDebugUnitTest
```

The full device end-to-end suite (VeraCrypt unlock + mount against generated test volumes,
run on a QEMU Android emulator) is documented in `run_e2e_tests.sh` and is not part of CI,
since it needs real USB-device emulation that isn't available on hosted runners.

## CI/CD

- `.github/workflows/ci.yml` runs unit tests and a debug build on every push/PR to `main`.
- `.github/workflows/release.yml` builds and signs a release APK and publishes a GitHub
  Release whenever a tag matching `v*.*.*` is pushed. See that file for the repository
  secrets it requires.

## License

GPL-2.0-or-later — see [LICENSE](LICENSE). This is required by the vendored `libexfat`
component (GPL-2.0-or-later), which is statically linked into the app; the whole
distributed binary therefore must be licensed under terms compatible with the GPL.

### Third-party components

| Component | License | Notes |
|---|---|---|
| [libaums](libaums/) | Apache-2.0 | USB mass-storage + FAT32 filesystem driver |
| [mbedTLS](app/src/main/cpp/mbedtls/) | Apache-2.0 OR GPL-2.0-or-later | AES, PBKDF2, SHA-512 primitives |
| `libexfat` (`app/src/main/cpp/exfat/`) | GPL-2.0-or-later | exFAT filesystem driver |
| Serpent reference implementation (`app/src/main/cpp/serpent/`) | Public domain | See [PROVENANCE.md](app/src/main/cpp/serpent/PROVENANCE.md) for the exact source and the one portability fix applied |
