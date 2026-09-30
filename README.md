# OTG Master

[![CI](https://github.com/moylali/OTGMaster/actions/workflows/ci.yml/badge.svg)](https://github.com/moylali/OTGMaster/actions/workflows/ci.yml)
[![License: GPL v2 or later](https://img.shields.io/badge/license-GPL--2.0--or--later-blue.svg)](LICENSE)

Android app to open encrypted USB mass-storage devices for read/write without root. Supports **VeraCrypt**, **LUKS1**, and **LUKS2** encrypted volumes with **FAT32**, **exFAT**, and **ext4** filesystems.

<p>
  <a href="https://f-droid.org/packages/app.fayaz.otgmaster/">
    <img src="https://fdroid.gitlab.io/artwork/badge/get-it-on.png" alt="Get it on F-Droid" height="80">
  </a>
  <a href="https://f-droid.org/packages/app.fayaz.otgmaster/">
    <img src="https://api.qrserver.com/v1/create-qr-code/?size=80x80&data=https://f-droid.org/packages/app.fayaz.otgmaster/" alt="F-Droid QR Code" height="80">
  </a>
  &nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;
  <a href="https://play.google.com/store/apps/details?id=app.fayaz.otgmaster">
    <img src="https://play.google.com/intl/en_us/badges/static/images/badges/en_badge_web_generic.png" alt="Get it on Google Play" height="80">
  </a>
  <a href="https://play.google.com/store/apps/details?id=app.fayaz.otgmaster">
    <img src="https://api.qrserver.com/v1/create-qr-code/?size=80x80&data=https://play.google.com/store/apps/details?id=app.fayaz.otgmaster" alt="Google Play QR Code" height="80">
  </a>
</p>

### How it works

The app cannot perform a kernel mount on non-rooted Android. Instead it:

1. Requests USB Host permission for a mass-storage device.
2. Reads raw sectors through a userspace USB Mass Storage adapter ([libaums](libaums/)).
3. Probes MBR/GPT partitions and detects the encryption type (VeraCrypt, LUKS1, or LUKS2) on each.
4. Unlocks the volume header and exposes a decrypted block-device wrapper:
   - **VeraCrypt**: AES or Serpent cipher, SHA-512 / Whirlpool KDF
   - **LUKS1**: PBKDF2-based KDF, AES-XTS bulk decryption
   - **LUKS2**: Argon2id-based KDF, AES-XTS bulk decryption
5. Feeds the decrypted block device into a userspace filesystem reader (FAT32 via libaums, exFAT via a vendored native `libexfat`, ext4 via a pure-Kotlin driver).
6. Surfaces files through the app UI and a `DocumentsProvider`, so other apps (Files, Gallery, etc.) can browse the unlocked volume.

### Roadmap

- **NTFS** — read support (and write, if it can be made safe). Detected today but not mounted.
- **APFS** — read support for Apple's container format. Detected today but not mounted.
- **BitLocker** — unlock BitLocker-encrypted drives alongside VeraCrypt and LUKS.
- **Encryption** — encrypt unencrypted USB storage devices via the app.

### Known limitations

- **Unplugging an exFAT drive while files are being written to it can damage
  it.** libexfat marks newly used space in its allocation bitmap only when a file
  is closed or the drive is unmounted, but writes the directory entries and
  cluster links that point at that space straight away. A drive pulled in
  between can have a file or folder pointing at space still marked free, which a
  later write can then reuse. Unmount before unplugging. If a drive does drop
  mid-write, check it on a computer (`fsck.exfat`) before writing to it again.
  FAT32 and ext4 are not affected in this way. A fix is planned.

### Feedback

If you encounter any issues, have feature requests, or just want to share your thoughts, you can open an issue on our [GitHub Issues page](https://github.com/moylali/OTGMaster/issues). 

If you prefer to share your feedback privately or without a GitHub account, please use our [anonymous feedback form](https://otgmaster-feedback.moylali.workers.dev/).

### Build

```bash
./gradlew assembleDebug
```

A signed release build additionally requires a `keystore.properties` file at the repo root
(never commit this — see `.gitignore`) with `storeFile`, `storePassword`, `keyAlias`, and
`keyPassword` entries; without it, `assembleRelease` produces an unsigned APK.

```bash
./gradlew assembleRelease
```

### Testing

#### Unit tests

JVM unit tests (e.g. `FilesystemDetector`) run without a device:

```bash
./gradlew testDebugUnitTest
```

#### UI testing

The full device end-to-end suite (VeraCrypt/LUKS unlock + mount against generated test volumes,
run on a QEMU Android emulator) is documented in `scripts/run_e2e_tests.sh` and is not part of CI,
since it needs real USB-device emulation that isn't available on hosted runners.

**Generating test data** — including how to add a fixture for a new cipher, hash,
filesystem or container format, the per-case file contract, and the traps in the three
generators — is documented in **[docs/TEST_DATA.md](docs/TEST_DATA.md)**.

#### Read/Write tests

For measuring a drive's read/write performance and correctness, see [docs/RUNNING_BENCHMARKS.md](docs/RUNNING_BENCHMARKS.md)
and the reference figures in [docs/BENCHMARK_RESULTS.md](docs/BENCHMARK_RESULTS.md).





### CI/CD

- `.github/workflows/ci.yml` runs unit tests and a debug build on every push/PR to `main`.
- `.github/workflows/release.yml` builds and signs a release APK and publishes a GitHub
  Release whenever a tag matching `v*.*.*` is pushed. See that file for the repository
  secrets it requires.

### License

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
| [argon2kt](https://github.com/lambdapioneer/argon2kt) (`com.lambdapioneer.argon2kt:argon2kt:1.6.0`) | Apache-2.0 | Argon2id KDF for LUKS2 key derivation; ships native `.so` in the APK |

### Support

- Help with refinement and translations of the text shown in the app.
- Report issues via the [feedback form](https://otgmaster-feedback.moylali.workers.dev/) or [GitHub issues](https://github.com/moylali/OTGMaster/issues) to help make the app stable.
- Donate to support the app via Ko-fi: <a href="https://ko-fi.com/moylali"><img src="https://ko-fi.com/img/githubbutton_sm.svg" height="32" align="absmiddle" alt="Support Me on Ko-fi"></a>
