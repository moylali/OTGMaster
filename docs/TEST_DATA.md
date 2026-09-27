# Generating test data for new ciphers, hashes and filesystems

There are **three** separate test-data systems in this repo, built for different
questions, on different host platforms, at different scales. Picking the wrong one
wastes the most time, so start here.

| System | Script | Host | Size | Answers |
|---|---|---|---|---|
| **E2E volume images** | `scripts/generate_testdata.sh` | Linux | 10 MB each | Does unlock + mount + basic I/O work? Is an unsupported choice *rejected*? |
| **Physical USB fixtures** | `scripts/prepare_test_usb.sh` | macOS | 62 GB | How fast is it, and does it stay correct under load? |
| **LUKS partitions** | `docs/LUKS_SUPPORT.md` §5 | Linux | 64 GB | Not implemented yet — prep steps only |

The E2E images are the ones to add first for any new cipher, hash or filesystem: they
are cheap, they run on an emulator, and they cover the case that matters most for a new
format — that the app either handles it correctly or refuses it cleanly.

## 1. What the app supports today

Adding test data for something the app cannot do is useful (that is the rejection
case), but you need to know which side of the line you are on. The authority is
`app/src/main/java/app/fayaz/otgmaster/veracrypt/VeraCryptCipher.kt`:

**Ciphers** — `isSupported` in `VeraCryptCipher`:

| Cipher | Supported | Key material |
|---|---|---|
| AES | **yes** | 64 bytes |
| Serpent | **yes** | 64 bytes |
| Twofish | no | 64 bytes |
| AES-Twofish | no | 128 bytes |
| Serpent-AES | no | 128 bytes |
| Twofish-Serpent | no | 128 bytes |
| AES-Twofish-Serpent | no | 192 bytes |
| Serpent-Twofish-AES | no | 192 bytes |

`keySizeBytes` is `components.size * 64` — VeraCrypt stores two 256-bit XTS keys per
cipher in the cascade, and the header parser depends on getting that length right.
`SingleCipher.TWOFISH` carries `nativeId = -1`, which is the marker for "no native
implementation"; AES is 0 and Serpent is 1, matching `CIPHER_SERPENT` in
`app/src/main/cpp/VeraCryptNative.cpp`.

**Hashes** — only `SHA512` has `isSupported = true`. SHA-256, Whirlpool and Streebog
are listed so the picker can reject them by name rather than mis-deriving a key.

**Filesystems** — FAT32 (via the vendored libaums) and exFAT (via vendored libexfat).
FAT16, NTFS and ext4 exist as test cases *specifically to prove they are refused*.

**PIM** matters more than it looks. `VeraCryptUnlocker` derives iterations as
`15000 + pim * 1000` when a PIM is set, against `500000` when it is not. Every fixture
uses **PIM 1 → 16,000 iterations**, because 500,000 iterations of PBKDF2-SHA512 on a
1,248 MHz phone turns an unlock into a multi-minute wait. If you generate a fixture
without `--pim`, expect the unlock to look hung rather than broken.

## 2. The per-case contract (E2E images)

`scripts/run_e2e_tests.sh` discovers cases by iterating `testdata/*/`, and reads these
files out of each directory. Only the first three are mandatory:

| File | Required | Meaning |
|---|---|---|
| `test.img` | **yes** | The raw image, `dd`'d onto the emulator's `/dev/block/sda` |
| `password.txt` | **yes** | Volume password |
| `pim.txt` | **yes** | PIM, as a bare integer |
| `cipher.txt` | no | Which cipher the **UI picker** selects — not necessarily the volume's real cipher |
| `description.txt` | no | Printed in the run log; this is what a later reader has to understand the case from |
| `test.key` | no | Keyfile; required by convention for any case named `*_keyfile` |
| `expects_error.txt` | no | Presence means the case **must fail to mount** |
| `write_test.txt` | no | Presence runs the create/persist/delete/persist lifecycle |

`cipher.txt` being independent of the volume is the mechanism behind the
`unsupported_cipher` case: the image is ordinary AES-FAT32, but the picker is set to
`Twofish`, so the app must refuse before touching volume data. That is the shape to
copy for any new unsupported cipher.

**One trap.** `ensure_testdata()` validates a **hardcoded list** of case names
(`run_e2e_tests.sh:48`) while the run loop iterates the directory. A new case you add
to `generate_testdata.sh` but not to that list will still *run* — it just will not be
checked for missing artefacts, so a half-generated fixture fails later and less
legibly. Add the name in both places.

## 3. Adding a new cipher

Work in this order. Steps 1–2 are app code; a fixture for an unimplemented cipher is
only ever a rejection case.

1. **Native.** Implement it in `app/src/main/cpp/VeraCryptNative.cpp` and give it a
   `#define CIPHER_<NAME>` id. Note `cryptSectorsInPlace` builds one key schedule per
   run — a new cipher must follow that, not schedule per sector.
2. **Kotlin.** Set `nativeId` on `SingleCipher`, flip `isSupported = true` on the
   `VeraCryptCipher` entry, and check `keySizeBytes` is right for a cascade.
3. **E2E fixture.** `create_fat32_volume` already takes the cipher as its third
   argument, so a standalone cipher is one line next to the existing Serpent case:

   ```bash
   create_fat32_volume "testdata/<name>" "false" "<VeraCryptCipherName>"
   ```

   The name must be what `veracrypt --encryption=` accepts. Add the directory to the
   `mkdir -p` block at the top and to `ensure_testdata`'s list.
4. **Rejection fixture**, if it is *not* implemented — copy the `unsupported_cipher`
   pattern: a working AES volume plus a `cipher.txt` naming the new cipher and an
   `expects_error.txt`. This is the more valuable of the two cases, because a cipher
   that is silently mis-decrypted looks like a corrupt drive to the user.
5. **Cascades** need an image whose header actually uses the cascade, so
   `--encryption="AES(Twofish)"`-style names must round-trip through VeraCrypt. Verify
   the header parses to the full `keySizeBytes` before trusting a mount result.
6. **Physical drive**, only once it mounts: `prepare_test_usb.sh --vc-encryption
   <name>`. That flag already exists and is documented as "AES or Serpent"; widen the
   comment when a third lands.

**Serpent is the cautionary example.** It has been `isSupported = true` and has had a
`testdata/serpent` fixture for some time, and **no benchmark run has ever exercised
it** — every measurement in `docs/BENCHMARK_RESULTS.md` is AES/SHA-512. Supported, with
a passing E2E case, is not the same as measured.

## 4. Adding a new hash

This one needs a script change first, which the cipher path does not.

`generate_testdata.sh` hardcodes `--hash=SHA-512` at every call site, and there is no
`hash.txt` in the per-case contract. To add a hash:

1. Implement it natively and flip `isSupported` on `VeraCryptHash`.
2. Add the iteration count to the `when (hash)` block in `VeraCryptUnlocker` — note
   every current branch returns `500000`, so a hash with a different default will be
   silently wrong if you rely on the `else`.
3. Parameterise the generator: add a `HASH` argument to `create_fat32_volume`, pass it
   to `--hash=`, and write it to `hash.txt`.
4. Teach `run_e2e_tests.sh` to read `hash.txt` and pass it through, following how
   `cipher.txt` is handled at line 213.
5. Only then generate the fixture.

Skipping step 4 produces a fixture that mounts because the app *defaults* to SHA-512
and the volume happens to be SHA-512 — a pass that proves nothing.

## 5. Adding a new filesystem

Two code paths matter: detection, and the driver.

1. **Detection.** `FilesystemDetector` decides what a decrypted volume is. It has JVM
   unit tests that need no device — add the boot-sector signature case there first,
   since it is the fastest feedback in the project.
2. **Driver.** FAT32 goes through vendored libaums, exFAT through vendored libexfat
   plus `ExFatNative.cpp`. A third means either another vendored library — which
   requires an entry in `docs/VENDOR_FIXES.md` **before** the vendoring commit lands,
   per `CLAUDE.md` — or new code implementing `UsbFile`.
3. **E2E fixture.** Two shapes exist, and the difference matters:
   - **VeraCrypt formats it**: `--filesystem=<fs>`, as `create_exfat_volume` does.
     Only works for filesystems VeraCrypt itself can create.
   - **You format it**: `--filesystem=none`, mount the container to get a
     `/dev/mapper/veracryptN` device, then `mkfs` onto that device, as
     `create_fat32_volume` does. This is the general path, and the only one that gives
     control over cluster size.

   `create_unsupported_volume` is the third shape, for filesystems that must be
   *refused* (`fat16`, `ntfs`, `ext4`). Note its ext4 branch skips file population —
   there is a commented-out block at line 141 explaining why.
4. **Benchmark fixture.** `prepare_test_usb.sh` validates `--fs` against a
   `fat32|exfat` allowlist and maps it to a `diskutil` filesystem name; both need the
   new case, plus a `newfs_*` invocation. Keep `--cluster 4096`: a small cluster size
   inflates the FAT and allocation bitmap on purpose, which is the whole point of the
   fixture.

## 6. Adding a new container format

A new *container* (LUKS1, LUKS2) is a larger change than a cipher, because it replaces
header parsing and key derivation rather than a block transform: AF-split/merge,
PBKDF2 vs Argon2id, master-key digest verification. `docs/LUKS_SUPPORT.md` evaluates
the proposal, records seven gaps, and gives complete Linux preparation commands in §5
for a 4-partition 64 GB drive covering LUKS1/2 × FAT32/exFAT, with a host-computed
manifest. Start there rather than from this document.

## 7. Verifying the fixture is real

A generated fixture is a claim about the disk, and the claims fail in both directions.

**Host-computed hashes are the only external ground truth.**
`prepare_test_usb.sh` writes `BENCH/MANIFEST.txt` with `path<TAB>bytes<TAB>sha256`,
hashed on the host with `shasum -a 256`. The benchmark's `fixtures` case compares
against it. Right now **it has never once run a real comparison**: every drive in use
was prepared by an older script and the suite reports `manifest has no hashes (old
prepare script?)`. Any drive you prepare for a new format should be prepared with the
current script so this check actually executes — it is the only thing in the suite that
can catch the app and the fixture generator being wrong in the same direction.

**Cross-device agreement.** Correctness case B prints a SHA-256 that has been
`ac3e360a187bfe9f…` on every device and both filesystems. A new filesystem fixture that
produces a *different* constant is not necessarily broken, but it must at least be
constant across devices — that is what separates "the results agree" from "each device
agrees with itself".

**Distrust a pass that should not be possible.** Every false pass in this project came
from the fixture's shape rather than the test's logic: aligned-only writes hid two
corruption bugs, 4–8 KiB fixtures hid a 2 GiB read overflow, single-threaded tests hid
a lock shipped wrong. When adding a fixture for a new format, confirm it **fails**
against the unimplemented state before you make it pass.

## 8. Practical traps

Collected from actually running these, in rough order of time lost.

- **`generate_testdata.sh` needs `sudo`** (it calls `veracrypt`, `mkfs`, loop devices),
  which leaves `testdata/` root-owned. The script ends with `chmod -R a+rX testdata/`
  for exactly this reason — keep that if you add steps after it.
- **`veracrypt -t -d` races the kernel** releasing the device mapper node. Both
  scripts fall back to `dmsetup`; copy that rather than retrying the veracrypt call.
- **`veracrypt -l` parsing is positional** — field 3 is the mapper device. It also
  produces no stdout under `--non-interactive`, which kills the script under `set -e`
  if you pipe it naively.
- **DiskArbitration re-mounts behind you** on macOS. `prepare_test_usb.sh` retries
  unmounting in a loop rather than unmounting once, because VeraCrypt fails with
  `Resource busy: /dev/diskNsM` otherwise.
- **Quick-format leaves stale patterns** in unused areas. That is fine for a fixture,
  but it means a read of unallocated space can return plausible-looking old data —
  which is precisely what the slack-leak correctness cases look for, so do not rely on
  "it returned zeros" as evidence of anything.
- **`newfs_exfat` can skip a reformat and still exit 0.** It detects an existing exFAT
  signature and declines, successfully. `prepare_test_usb.sh` wipes the signature first
  and then *confirms* the cluster size afterwards rather than assuming the format
  happened — without that, you get a drive that looks freshly prepared and is not.
- **Spotlight will index 50+ GiB** of fresh fixtures in the background and skew every
  measurement taken in the next hour. The script disables it on the volume.
- **The two generators use different passwords by convention.** `password123` in both,
  and `prepare_test_usb.sh` notes the match is deliberate so the E2E suite agrees;
  changing one silently breaks the other.
- **`prepare_test_usb.sh` is destructive** — it repartitions a disk. Per `CLAUDE.md`,
  it is never presented in the same block as a read-only command, and it refuses
  anything that is not an external removable USB disk, with a size ceiling to catch a
  typo'd identifier. Read the summary it prints before confirming.
