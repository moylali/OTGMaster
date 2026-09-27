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
run on a QEMU Android emulator) is documented in `scripts/run_e2e_tests.sh` and is not part of CI,
since it needs real USB-device emulation that isn't available on hosted runners.

**Generating test data** — including how to add a fixture for a new cipher, hash,
filesystem or container format, the per-case file contract, and the traps in the three
generators — is documented in **[docs/TEST_DATA.md](docs/TEST_DATA.md)**. For measuring
a drive rather than testing it, see [docs/RUNNING_BENCHMARKS.md](docs/RUNNING_BENCHMARKS.md)
and the reference figures in [docs/BENCHMARK_RESULTS.md](docs/BENCHMARK_RESULTS.md).

### Keeping devices awake for benchmark runs

Decryption is CPU-bound, so a device that sleeps mid-run throttles its clocks and the
result reads as a regression — the block-layer control measured **7.27 MB/s asleep
against 17.26 MB/s awake** on the same device. `scripts/run_benchmark.sh` therefore
changes two settings per device, and **both persist after the run ends**:

| Setting | Run value | Normal value |
|---|---|---|
| `screen_off_timeout` | `86400000` (24 h) | typically `30000`–`60000` |
| `deviceidle` (Doze) | disabled | enabled |

Left in place they keep the screen on indefinitely and drain the battery, so revert
them on any device you are not actively measuring.

**Enable** (what the runner does automatically — only needed for a manual run):

```bash
adb -s <serial> shell settings put system screen_off_timeout 86400000
adb -s <serial> shell dumpsys deviceidle disable
adb -s <serial> shell input keyevent KEYCODE_WAKEUP
```

**Disable / restore** when you are done with a device:

```bash
adb -s <serial> shell settings put system screen_off_timeout 60000
adb -s <serial> shell dumpsys deviceidle enable
```

**Check the current state** of every attached device:

```bash
for d in $(adb devices | awk 'NR>1 && $2=="device" {print $1}'); do
  echo "$d  model=$(adb -s $d shell getprop ro.product.model | tr -d '\r')" \
       " timeout=$(adb -s $d shell settings get system screen_off_timeout | tr -d '\r')ms" \
       " doze_enabled=$(adb -s $d shell dumpsys deviceidle enabled | tr -d '\r')"
done
```

`timeout=86400000` with `doze_enabled=0` means that device is still in run
configuration. A finished run is also self-reporting: the harness records the power
state at both the start and the end of every report, and flags any run that dozed as
`*** CONTAMINATED ***`. The app additionally sets `FLAG_KEEP_SCREEN_ON` while a
benchmark is active, but that one is transient and needs no cleanup.

See [docs/RUNNING_BENCHMARKS.md](docs/RUNNING_BENCHMARKS.md) for the rest of the
device-handling traps.

### Running E2E Tests

The E2E tests run on a local Android emulator via QEMU, which allows us to emulate USB mass-storage devices.
To run the full suite:

```bash
./scripts/run_e2e_tests.sh
```

**Options:**
- `--headless`: Run the emulator in the background without a window.
- `--only <test_name>`: Run only a specific test directory (e.g., `--only fat32`).

### Adding New Test Cases

Test cases are defined dynamically based on the contents of the `testdata/` directory.

To add a new test case:
1. Update `scripts/generate_testdata.sh` to generate a new directory under `testdata/` (e.g., `testdata/my_new_case`).
2. The generator must create a `test.img` (the VeraCrypt volume) inside this directory.
3. The generator must also output the following configuration files into the directory to tell the test runner what to expect:
   - `password.txt` (Required): The decryption password.
   - `pim.txt` (Optional): The PIM value, defaults to empty if not present.
   - `test.key` (Optional): The keyfile.
   - `cipher.txt` (Optional): The cipher to select (e.g., `Serpent`), defaults to `AES`.
   - `expects_error.txt` (Optional): If present, the test expects the mount to fail (e.g., for unsupported filesystems).
   - `expected_fs.txt` (Optional): Used with `expects_error.txt` to verify the app correctly identified the filesystem before rejecting it.

### Verifying Test Failures

During the test run, each test case outputs its state to the terminal.
If a test fails, you can find the complete Android `logcat` dump for that specific test run in the root directory, named `logcat_<test_name>.txt` (e.g., `logcat_fat32.txt`). This allows you to inspect the exception stack traces or debug logs that caused the failure.

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
| [argon2kt](https://github.com/lambdapioneer/argon2kt) (`com.lambdapioneer.argon2kt:argon2kt:1.6.0`) | Apache-2.0 | Argon2id KDF for LUKS2 key derivation; ships native `.so` in the APK |
