# Preparing test media and test data

**This is the single reference for all test-media and test-case preparation** — E2E
emulator images, physical VeraCrypt benchmark drives, and the LUKS drives. Everything
needed to build a fixture from nothing is here or reachable from here; nothing else in
the repo should carry a second copy of these commands.

There are two kinds of test media, built for different questions, on different host
platforms, at different scales. Picking the wrong one wastes the most time.

| System | Script / section | Host | Size | Answers |
|---|---|---|---|---|
| **E2E volume images** | `scripts/generate_testdata.sh`, §2–§5 | Linux | 10 MB each | Does unlock + mount + basic I/O work? Is an unsupported choice *rejected*? |
| **APFS E2E images** | `scripts/make_apfs_images_macos.sh`, §6b | macOS | 64 MB each | Plain and encrypted APFS, case-sensitive and not (not yet run by the suite) |
| **VeraCrypt benchmark drive** | `scripts/prepare_test_usb.sh`, §7 | macOS | 62 GB | How fast is it, and does it stay correct under load? |
| **LUKS drives (3 of them)** | §8–§12, manual | Linux | 64 GB each | LUKS1/LUKS2 header parsing, Argon2 on a phone, ext4 detection |

The E2E images are the ones to add first for any new cipher, hash or filesystem: they
are cheap, they run on an emulator, and they cover the case that matters most for a new
format — that the app either handles it correctly or refuses it cleanly.

**If you are resuming this work, start at §13** — it is the checklist of what exists,
what is missing, and what to do next.

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

**Filesystems** — FAT32 (via the vendored libaums), exFAT (via vendored libexfat), ext4
(written here) and, from 0.4.1, NTFS (via vendored libntfs-3g). FAT16 exists as a test
case *specifically to prove it is refused*; the `ext4` and `ntfs` cases, which used to,
now mount.

**Containers** — VeraCrypt, LUKS1, LUKS2 and, from 0.4.1, BitLocker (version 2 —
Windows 7 and later; password, recovery key or suspended protection). §6a builds the
BitLocker fixtures.

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
PBKDF2 vs Argon2id, master-key digest verification.

`docs/LUKS_SUPPORT.md` is the design evaluation — it records seven gaps in the original
proposal and the recommended scope for a first version. **The preparation commands live
here**, in §8–§12, so there is one copy to keep correct.

## 6a. NTFS in VeraCrypt and in BitLocker, ext4 in VeraCrypt (E2E, no root)

`scripts/generate_noroot_testdata.sh` builds five E2E cases without sudo, unlike
`generate_testdata.sh`:

| Case | Container | Checks |
|---|---|---|
| `ext4` | VeraCrypt AES/SHA-512, PIM 1 | mount, flower.jpg through SAF (filled by `mkfs.ext4 -d`) |
| `ntfs` | VeraCrypt AES/SHA-512, PIM 1 | mount, flower.jpg through SAF |
| `ntfs_write` | VeraCrypt AES/SHA-512, PIM 1 | create/write/mkdir/delete, persisted over remounts |
| `bitlocker_ntfs` | BitLocker AES-CBC-128 | BITLOCKER tag, mount, flower.jpg |
| `bitlocker_ntfs_write` | BitLocker XTS-AES-128 | as `ntfs_write`; remount #2 uses the recovery key |

```sh
bash scripts/generate_noroot_testdata.sh
```

How each piece is made, and what vouches for it:

- **NTFS**: `mkntfs`, populated through a user-mode `ntfs-3g` FUSE mount with the same
  files the other mount cases carry.
- **VeraCrypt**: `veracrypt --create --filesystem=none` (no root), then
  `scripts/fill_veracrypt_volume.py` opens the header (PBKDF2-SHA512, AES-XTS) and
  XTS-encrypts the NTFS into the data area. Decrypting it back independently gives a
  clean NTFS with flower.jpg byte-identical.
- **BitLocker**: only Windows creates BitLocker volumes, and cryptsetup's sample
  images (vendored as test data, `app/src/test/resources/bitlk/`) have their unused
  ciphertext zeroed, so the NTFS inside them is mostly noise. So
  `scripts/make_bitlocker_image.py` builds one around a real NTFS, and `--verify`
  makes cryptsetup the judge: it must parse the metadata, release the same volume
  key for the password and the recovery key, and — through `scripts/bitlk_decrypt.py`,
  which uses cryptsetup's key and layout with Python's AES — decrypt to exactly the
  input. The generator keeps BitLocker's metadata in a tail after the NTFS (Windows
  keeps it inside, as reserved files); `ntfsfix` then looks for the backup boot sector
  in that zero tail, so cut a decrypted image to the NTFS size before running it.

Password `password123`; BitLocker recovery key
`111111-222222-333333-444444-555555-666666-111111-222222` (each group a multiple of
11, and group/11 below 65536 — a random 48-digit string is not a valid key).

## 6b. APFS (E2E images, macOS)

`scripts/make_apfs_images_macos.sh` builds four raw images, each a GPT disk with one
APFS partition — the layout Disk Utility gives a USB stick:

| Case | Volume | Checks |
|---|---|---|
| `apfs_ci` | APFS, case-insensitive | mount, flower.jpg, case.txt and CASE.txt are one file |
| `apfs_cs` | APFS, case-sensitive | as above, but case.txt and CASE.txt are two files |
| `apfs_enc_ci` | APFS (Encrypted), case-insensitive | unlock with the password, then as `apfs_ci` |
| `apfs_enc_cs` | APFS (Encrypted), case-sensitive | unlock with the password, then as `apfs_cs` |

```sh
bash scripts/make_apfs_images_macos.sh     # on a Mac, from the repo root
```

macOS only: no other OS creates natively encrypted APFS, and filling APFS on Linux
needs the out-of-tree `linux-apfs-rw` module, which Secure Boot will not load unsigned.
No root needed. Images land in `testdata/apfs/<case>/`, 64 MiB each. They sit one
level below `testdata/` on purpose, so `run_e2e_tests.sh` does not pick them up until
the app mounts APFS.

**Unlike every other case, these are committed**, because Linux cannot rebuild them.
The script also packs all four cases into `testdata/apfs/apfs-images.tar.xz` (about
3 MB; the loose `test.img` files stay gitignored). `testdata/apfs/PROVENANCE.md`
records where it came from and its hashes. A Linux checkout needs no Mac:

```sh
tar -xJf testdata/apfs/apfs-images.tar.xz -C testdata/apfs   # or let the verifier do it
```

After rebuilding on a Mac, commit the new archive and update the hashes in
`PROVENANCE.md` in the same commit.

The encrypted volumes are created encrypted (`diskutil apfs addVolume -passphrase`),
not converted, so there is no background encryption to capture half-done. The script
checks each volume's case behaviour before saving it. Password `password123`.

**The images are not byte-reproducible.** Volume UUIDs, timestamps, the encryption keys
and the random `large_file.bin` change on every run, so compare contents, not image hashes.

**Verify on Linux, with code independent of Apple's and the app's:** run
`scripts/verify_apfs_images.py`. It unpacks the archive if the cases are not already
there, then runs
`apfsck` on the container, then mounts through `apfs-fuse` (decrypting with the
password) and checks flower.jpg byte for byte, the other files, and case sensitivity.

A quick check is possible on the Mac, but it has a gap. Attach the saved raw image
read-only (`hdiutil attach -readonly -nomount -imagekey diskimage-class=CRawDiskImage`),
run `fsck_apfs -n` on the `Apple_APFS` partition, unlock with `diskutil apfs
unlockVolume … -nomount` and mount with `mount_apfs -o rdonly`. `fsck_apfs` will not
check an **encrypted** container on an attached image — *"failed to enable crypto I/O
mode … Invalid argument"*, on read-only and writable attaches alike. The encrypted
images therefore get no structural check on macOS; only `apfsck` on Linux covers them.

First run, 2026-10-02, macOS 26.6.2 (arm64), script unchanged from `9f996ca`:

| Case | `fsck_apfs -n` | flower.jpg | case.txt / CASE.txt | files |
|---|---|---|---|---|
| `apfs_ci` | container OK | matches | UPPER / UPPER | 6 |
| `apfs_cs` | container OK | matches | lower / UPPER | 7 |
| `apfs_enc_ci` | cannot run (above) | matches, after unlock | UPPER / UPPER | 6 |
| `apfs_enc_cs` | cannot run (above) | matches, after unlock | lower / UPPER | 7 |

`verify_apfs_images.py` has not yet been run on these images.

## 7. The VeraCrypt benchmark drive (macOS, scripted)

This one is fully scripted. It is the drive every figure in
`docs/BENCHMARK_RESULTS.md` was measured on.

```sh
diskutil list                      # identify the disk FIRST
scripts/prepare_test_usb.sh --disk diskN --fs exfat --veracrypt --free 4
```

Flags that matter: `--fs fat32|exfat`, `--veracrypt`, `--vc-encryption AES|Serpent`,
`--vc-hash SHA-512`, `--vc-pim 1`, `--cluster 4096`, `--free 4`, `--quick` (small
fixtures, no bulk fill — for iterating on the script itself, never for measuring).

It refuses anything that is not an external, removable USB disk and has a size ceiling
to catch a typo'd identifier. **It repartitions the disk.** Read the summary it prints
before confirming, and per `CLAUDE.md` never paste it in the same block as a read-only
command.

Verify and clean are separate scripts: `scripts/verify_test_usb.sh`,
`scripts/clean_test_usb.sh`.

---

# LUKS drives

Three drives. None of this is scripted yet — these are manual Linux procedures, and
`scripts/prepare_test_usb.sh` is macOS-only so it cannot be reused.

| Drive | Layout | Tests |
|---|---|---|
| **A** | 4 partitions: LUKS1/LUKS2 × FAT32/exFAT | Both header formats, both KDFs, both supported filesystems, **and multi-drive** |
| **B** | 1 partition: LUKS1 + ext4 | PBKDF2 unlock, then ext4 detection |
| **C** | 1 partition: LUKS2 + ext4 | Argon2id unlock, then ext4 detection |

**Know what B and C can prove today.** The app does not implement ext4 — it is one of
the three filesystems (`fat16`, `ntfs`, `ext4`) that exist as cases *to be refused*. So
until ext4 support lands, B and C test that the app **unlocks the LUKS container and
then refuses the filesystem cleanly**, naming ext4 rather than reporting a corrupt
volume. That is a real and useful test: it separates a container-layer failure from a
filesystem-layer one. The fixtures are still worth writing so the drives are ready if
ext4 is implemented.

## 8. Prerequisites and safety

```sh
sudo apt install cryptsetup-bin dosfstools exfatprogs e2fsprogs parted
cryptsetup --version        # 2.4+ for reliable LUKS2 Argon2id support
```

Identify the device and **confirm it is the right one** — every command below destroys
data:

```sh
lsblk -o NAME,SIZE,TYPE,TRAN,MODEL,MOUNTPOINT
# Expect TRAN=usb and the size you expect. /dev/sdX below is a placeholder.
```

Two conventions used throughout, both deliberate:

- `PASS=password123` — matches the VeraCrypt fixtures and `generate_testdata.sh`, so
  one password fits every fixture in the project.
- **`--pbkdf-memory 65536` (64 MB) on every LUKS2 volume.** cryptsetup's desktop
  default is 1–4 GB of Argon2 memory, which a phone cannot allocate — a drive formatted
  with defaults tests nothing except the out-of-memory path. This is the single most
  important parameter on this page; see `docs/LUKS_SUPPORT.md` §2.1.

## 9. Drive A — four partitions, LUKS1/2 × FAT32/exFAT

MBR allows exactly four primary partitions, which is what makes this layout possible.
It is also **the only way to test multiple encrypted volumes on one physical device**.

| Partition | Container | Filesystem | Label | Path |
|---|---|---|---|---|
| `p1` | LUKS1 | FAT32 | `LUKS1FAT` | PBKDF2 |
| `p2` | LUKS1 | exFAT | `LUKS1EXF` | PBKDF2 |
| `p3` | LUKS2 | FAT32 | `LUKS2FAT` | Argon2id |
| `p4` | LUKS2 | exFAT | `LUKS2EXF` | Argon2id |

### 9.1 Partition

```sh
sudo wipefs -a /dev/sdX
sudo parted -s /dev/sdX mklabel msdos
sudo parted -s /dev/sdX mkpart primary 1MiB    16GiB
sudo parted -s /dev/sdX mkpart primary 16GiB   32GiB
sudo parted -s /dev/sdX mkpart primary 32GiB   48GiB
sudo parted -s /dev/sdX mkpart primary 48GiB   100%
sudo partprobe /dev/sdX
lsblk /dev/sdX          # expect sdX1..sdX4, ~15.5 GB each
```

### 9.2 Create the containers

```sh
PASS=password123

for p in 1 2; do
    echo -n "$PASS" | sudo cryptsetup luksFormat --type luks1 \
        --cipher aes-xts-plain64 --key-size 512 --hash sha256 \
        --pbkdf-force-iterations 10000 --batch-mode /dev/sdX$p -
done

for p in 3 4; do
    echo -n "$PASS" | sudo cryptsetup luksFormat --type luks2 \
        --cipher aes-xts-plain64 --key-size 512 --hash sha256 \
        --pbkdf argon2id --pbkdf-memory 65536 --pbkdf-parallel 4 \
        --pbkdf-force-iterations 4 --sector-size 512 \
        --batch-mode /dev/sdX$p -
done
```

**Capture the headers. This is the ground truth for the parser** — payload offset, key
size, cipher, sector size, KDF parameters, keyslot layout:

```sh
mkdir -p ~/otg-luks-fixtures
for p in 1 2 3 4; do
    sudo cryptsetup luksDump /dev/sdX$p | sudo tee ~/otg-luks-fixtures/luks-A-p$p.txt
done
```

Keep that directory. Without it there is nothing to check the app's header parsing
against except the app itself.

### 9.3 Open and format

```sh
for p in 1 2 3 4; do
    echo -n "$PASS" | sudo cryptsetup open /dev/sdX$p otgA$p -
done

# 4096-byte clusters, deliberately small, to inflate the FAT and the exFAT
# allocation bitmap — the same reasoning as prepare_test_usb.sh.
sudo mkfs.vfat -F 32 -s 8 -n LUKS1FAT /dev/mapper/otgA1
sudo mkfs.exfat -c 4096 -L LUKS1EXF  /dev/mapper/otgA2
sudo mkfs.vfat -F 32 -s 8 -n LUKS2FAT /dev/mapper/otgA3
sudo mkfs.exfat -c 4096 -L LUKS2EXF  /dev/mapper/otgA4

for p in 1 2 3 4; do
    sudo mkdir -p /mnt/otgA$p
    sudo mount /dev/mapper/otgA$p /mnt/otgA$p
done
```

`-s 8` is sectors-per-cluster: 8 × 512 = 4096 bytes. On exFAT, `-c` takes bytes
directly.

Then populate each with §12, using `SEQ_MB=256` — four partitions cannot each hold a
2 GiB file.

## 10. Drive B — LUKS1 + ext4

```sh
PASS=password123
sudo wipefs -a /dev/sdY
sudo parted -s /dev/sdY mklabel msdos
sudo parted -s /dev/sdY mkpart primary 1MiB 100%
sudo partprobe /dev/sdY

echo -n "$PASS" | sudo cryptsetup luksFormat --type luks1 \
    --cipher aes-xts-plain64 --key-size 512 --hash sha256 \
    --pbkdf-force-iterations 10000 --batch-mode /dev/sdY1 -

sudo cryptsetup luksDump /dev/sdY1 | sudo tee ~/otg-luks-fixtures/luks-B.txt
echo -n "$PASS" | sudo cryptsetup open /dev/sdY1 otgB -

# -m 0: no reserved blocks, so the free-space figure the app reports is comparable
#       to the FAT32/exFAT drives.
# lazy_*_init=0: write the inode tables and journal NOW rather than lazily in the
#       background. Without this, the filesystem keeps changing under the first few
#       reads and no fixture hash is reproducible.
sudo mkfs.ext4 -L LUKS1EXT4 -m 0 \
    -E lazy_itable_init=0,lazy_journal_init=0 /dev/mapper/otgB

sudo mkdir -p /mnt/otgB && sudo mount /dev/mapper/otgB /mnt/otgB
```

Populate with §12 using `SEQ_MB=2048` — this drive has the whole 62 GB.

**ext4 needs an ownership fix** that FAT32 and exFAT do not. ext4 stores real POSIX
permissions, and everything written under `sudo` lands root-owned. Do this before
unmounting or the fixtures are unreadable to a non-root reader:

```sh
sudo chown -R "$(id -u):$(id -g)" /mnt/otgB/BENCH
sudo chmod -R a+rX /mnt/otgB/BENCH
```

## 11. Drive C — LUKS2 + ext4

Identical to Drive B except the container. **Note `--pbkdf-memory 65536`.**

```sh
PASS=password123
sudo wipefs -a /dev/sdZ
sudo parted -s /dev/sdZ mklabel msdos
sudo parted -s /dev/sdZ mkpart primary 1MiB 100%
sudo partprobe /dev/sdZ

echo -n "$PASS" | sudo cryptsetup luksFormat --type luks2 \
    --cipher aes-xts-plain64 --key-size 512 --hash sha256 \
    --pbkdf argon2id --pbkdf-memory 65536 --pbkdf-parallel 4 \
    --pbkdf-force-iterations 4 --sector-size 512 \
    --batch-mode /dev/sdZ1 -

sudo cryptsetup luksDump /dev/sdZ1 | sudo tee ~/otg-luks-fixtures/luks-C.txt
echo -n "$PASS" | sudo cryptsetup open /dev/sdZ1 otgC -

sudo mkfs.ext4 -L LUKS2EXT4 -m 0 \
    -E lazy_itable_init=0,lazy_journal_init=0 /dev/mapper/otgC

sudo mkdir -p /mnt/otgC && sudo mount /dev/mapper/otgC /mnt/otgC
```

Populate with §12 (`SEQ_MB=2048`), then apply the same `chown`/`chmod` as §10.

## 11a. Drive D — VeraCrypt + ext4, plain ext4, plain NTFS

The only drive with a **mixed** partition table, and the only one covering VeraCrypt
over ext4. Drives A–C are LUKS, so before this one the VeraCrypt+ext4 combination had
never run on hardware.

| Part | Share | Contents | Credentials |
|---|---|---|---|
| p1 | 40% | VeraCrypt (AES / SHA-512 / PIM 1) → ext4, label `VCEXT4` | `password123`, PIM 1 |
| p2 | 30% | plain ext4, label `PLAINEXT4` | none |
| p3 | 30% | plain NTFS, label `NTFSPLAIN` | none |

**p2 is the control.** It carries a byte-identical fixture tree to p1, built by the same
function in the script, so the two differ only by the crypto layer. An ext4 failure that
appears on p1 and not on p2 is in the cipher path, not in the ext4 code — which is the
distinction that took a destroyed drive to make the first time.

**p3 must be refused.** NTFS is unsupported, so it carries only a marker file. It also
covers the classification path: a partition whose first sector reads `NTFS` cannot be a
VeraCrypt volume, and the app must not offer it in the unlock picker. It did, once,
tagged `VERACRYPT` — see `ContainerClassificationTest`.

Run it with the script rather than by hand; unlike A–C this one cannot detect an
already-prepared state, because a VeraCrypt volume is indistinguishable from random
data without the password, so every run starts from zero and prompts before wiping.

```sh
sudo bash scripts/prepare_drive_d.sh /dev/sdZ
```

`BIG_MIB`, `SMALL_MIB` and `DENSE_N` shrink the fixtures for a fast iteration, at the
cost of comparability with drive C — leave them alone for a run whose numbers you
intend to quote. The VeraCrypt parameters are written to
`/root/otg-luks-fixtures/veracrypt-D.txt`.

## 12. The dummy files — what goes inside, and why

Every drive gets the same `BENCH/` tree. **Each fixture exists to stress one specific
path**; a fixture of the wrong shape is how this project produced false passes before
(see §14).

| Fixture | Content | Stresses |
|---|---|---|
| `large/seq_<N>.bin` | Random bytes | Sequential throughput, random 4 KiB reads, the ≥2 GiB boundary |
| `dense_short/` | 10,000 files, 8.3 names | Directory listing, one directory entry per file |
| `dense_lfn/` | 10,000 files, long names | Listing with ~4 entries per file |
| `nested/` | 10 levels deep | Path resolution (every level is re-listed) |
| `reports/` | empty | Where the on-device harness writes results |

Set `SEQ_MB` per drive: **256** for Drive A's partitions, **2048** for B and C. A
2 GiB file is what exercises the 32-bit overflow that once made every ≥2 GiB read
return zero bytes while all ten correctness cases still passed.

```sh
# M=mountpoint, SEQ_MB=size of the sequential fixture in MiB
populate() {
    M="$1"; SEQ_MB="$2"
    B="$M/BENCH"
    sudo mkdir -p "$B/large" "$B/dense_short" "$B/dense_lfn" "$B/nested" "$B/reports"

    # --- large: sequential + random-read target -----------------------------
    # Random data, not zeros: a sparse or compressible file lets the drive's
    # controller cheat and the throughput figure becomes fiction.
    sudo dd if=/dev/urandom of="$B/large/seq_${SEQ_MB}m.bin" \
        bs=1M count="$SEQ_MB" status=progress
    # A 256 MiB file is also needed by the random-read case on every drive.
    if [ "$SEQ_MB" -ne 256 ]; then
        sudo dd if=/dev/urandom of="$B/large/seq_256m.bin" \
            bs=1M count=256 status=none
    fi

    # --- dense_short: 10,000 8.3-compatible names --------------------------
    sudo sh -c "cd '$B/dense_short' && for i in \$(seq -w 1 10000); do
        printf 'x' > f\$i.dat
    done"

    # --- dense_lfn: 10,000 long names (~4 dir entries each) ----------------
    sudo sh -c "cd '$B/dense_lfn' && for i in \$(seq -w 1 10000); do
        printf 'x' > \"a_file_with_a_deliberately_long_name_for_lfn_testing_\$i.dat\"
    done"

    # --- nested: 10 levels, one leaf ---------------------------------------
    D="$B/nested"
    for l in $(seq -w 1 10); do D="$D/level_$l"; sudo mkdir -p "$D"; done
    sudo dd if=/dev/urandom of="$D/leaf_at_depth_10.dat" bs=4k count=1 status=none

    sync
}

# Drive A
for p in 1 2 3 4; do populate "/mnt/otgA$p" 256; done
# Drives B and C
populate /mnt/otgB 2048
populate /mnt/otgC 2048
```

The dense loops take several minutes each on a slow stick — 20,000 file creations per
volume. `printf 'x'` rather than `truncate` keeps every file non-empty, because a
zero-length file skips the cluster-allocation path entirely.

### 12.1 The manifest — the only external ground truth

**Do not skip this.** `fixtures` has never once compared a host-computed hash on any
device, because every existing drive carries `-` placeholders and the suite reports
`nothing to check`. It is the only check that can catch the app and the fixture
generator being wrong in the same direction.

```sh
manifest() {
    M="$1"; LUKSREF="$2"
    B="$M/BENCH"
    {
        echo "# OTG Master benchmark fixture"
        echo "# generated: $(date -u +%Y-%m-%dT%H:%M:%SZ)"
        echo "# luks:      $LUKSREF"
        echo
        echo "# path<TAB>bytes<TAB>sha256"
        for f in "$B"/large/*.bin; do
            printf 'large/%s\t%s\t%s\n' "$(basename "$f")" \
                "$(stat -c%s "$f")" "$(sha256sum "$f" | cut -d' ' -f1)"
        done
        for d in dense_short dense_lfn; do
            # Entry format must match benchFixtures exactly: a regular file is
            # "name<TAB>size", a subdirectory is "name<TAB>dir".
            printf '%s/\t%s files\t%s\n' "$d" \
                "$(find "$B/$d" -maxdepth 1 -type f | wc -l | tr -d ' ')" \
                "$(cd "$B/$d" && for f in *; do
                    if [ -d "$f" ]; then printf '%s\tdir\n' "$f"
                    elif [ -f "$f" ]; then printf '%s\t%s\n' "$f" "$(stat -c%s "$f")"
                    fi
                   done | LC_ALL=C sort | sha256sum | cut -d' ' -f1)"
        done
        L=$(find "$B/nested" -name leaf_at_depth_10.dat)
        printf 'nested/leaf_at_depth_10.dat\t%s\t%s\n' \
            "$(stat -c%s "$L")" "$(sha256sum "$L" | cut -d' ' -f1)"
    } | sudo tee "$B/MANIFEST.txt" > /dev/null
}

for p in 1 2 3 4; do manifest "/mnt/otgA$p" "luks-A-p$p.txt"; done
manifest /mnt/otgB luks-B.txt
manifest /mnt/otgC luks-C.txt
```

**The directory-line format is a contract with `benchFixtures`** and all four parts of it
matter. Getting any one wrong produces a mismatch that looks exactly like data
corruption:

1. **The size field must end in `files`.** The app rejects anything else as an
   older-format manifest and reports `skipped — manifest predates this check` rather
   than comparing. That message is what every current drive prints.
2. **A regular file is `name<TAB>size`; a subdirectory is `name<TAB>dir`.** A
   subdirectory has no length the device can read — libaums throws
   `UnsupportedOperationException("This is a directory!")` — so the app substitutes the
   literal `dir`, and the host must do the same. `dense_short` and `dense_lfn` contain
   no subdirectories so it makes no difference there, but it will the moment a fixture
   with nested content is hashed.
3. **`LC_ALL=C` is not optional.** The app sorts with Kotlin's natural String ordering,
   which is byte order for ASCII names; a locale-dependent host sort produces a
   different hash on a different machine.
4. **Entries are joined with `\n`, no trailing newline** — which is what
   `sha256sum` over the piped listing produces, since the final `printf` supplies the
   separator between lines only.

The count in the size field is computed rather than assumed: the app only checks the
suffix, so a hardcoded `10000 files` would still be accepted while being untrue.

### 12.2 Teardown

Always close the mappers, or the next run finds the device busy:

```sh
for p in 1 2 3 4; do sudo umount /mnt/otgA$p; sudo cryptsetup close otgA$p; done
sudo umount /mnt/otgB && sudo cryptsetup close otgB
sudo umount /mnt/otgC && sudo cryptsetup close otgC
sync
```

Confirm nothing is left open before unplugging:

```sh
sudo dmsetup ls        # should list no otg* entries
lsblk /dev/sdX         # no crypt children
```

## 12a. Giving a drive hashable fixtures

The `fixtures` section is the only check that can see silent data corruption in
the fixture tree, and it does nothing without hashes in `BENCH/MANIFEST.txt`.
Drives built by `prepare_drive_*.sh` carry them. Drives built by the older
`prepare_test_usb.sh` — the VeraCrypt FAT32 and exFAT benchmark drives — do not,
so their runs report `nothing to check — manifest has no hashes`.

Rebuilding a 60 GB drive to get hashes is not a reasonable price. Regenerate the
manifest in place instead:

```sh
sudo bash scripts/regen_manifest.sh /dev/sdX1
```

It takes the **partition**, detects the container itself (LUKS, VeraCrypt, or a
plain partition), mounts, rewrites `BENCH/MANIFEST.txt`, and unmounts. Nothing is
reformatted. Password defaults to `password123` and VeraCrypt PIM to 1; pass them
as the second and third arguments otherwise.

**Regenerate from a drive you believe is good.** The manifest records the drive as
it is now, so running it over a damaged drive blesses the damage as the new
baseline and the check goes quiet again. A fresh preparation, or a volume that has
just passed its filesystem checker, is the right moment. The benchmark's write
sections use their own scratch files and never touch the fixture tree, so
regenerating after a run is not unsound in itself — but a manifest generated right
after an unexplained failure proves nothing.

Two format details cost a debugging session each, and the script exists partly to
stop them being retyped:

- a directory record's size field **must** end in `files`. The older format wrote
  `10 levels` there, next to a *file's* hash rather than a listing hash; comparing
  those produces a mismatch indistinguishable from corruption, which is why the
  benchmark skips such lines instead of failing them.
- the directory hash is over the sorted `name<TAB>size` listing with the trailing
  newline stripped (`head -c -1`), because the benchmark joins entries with `\n`
  as a separator, not a terminator.

## 13. Resume checklist

For an agent picking this up cold.

**Already done, nothing to redo:**

- The VeraCrypt benchmark drive is scripted and working; four devices have full
  results in `docs/BENCHMARK_RESULTS.md`.
- E2E fixtures exist for FAT32, exFAT, keyfile and PIM variants, Serpent, an
  unsupported-cipher rejection, a partitioned MBR, and FAT16/NTFS/ext4 rejections.
- `docs/LUKS_SUPPORT.md` holds the design evaluation and the seven gaps.

**Not done:**

| Task | Blocker |
|---|---|
| Build drives A, B, C | Needs a Linux host and 3 USB sticks (~64 GB each) |
| LUKS implementation | Design agreed in `LUKS_SUPPORT.md`; no code |
| ext4 support | Unimplemented; B and C are rejection cases until then |
| A real `fixtures` comparison | Needs any drive built with §12.1 above |
| Serpent measurement | Supported and E2E-tested, never benchmarked |
| Multi-drive (`saf par cross`) | Needs two volumes on one device — Drive A provides this |

**Order to work in:** build Drive A first. It is the only drive that unblocks two
separate gaps at once (LUKS verification *and* multi-drive), and its four partitions
exercise both header formats against filesystems the app already supports — so a
failure is unambiguously in the LUKS layer rather than in a new filesystem driver. B and
C are worth less until ext4 exists.

**When a drive is built, record it**: add the `luksDump` output location and the drive's
label to this section, so the next reader knows the fixture exists without plugging it
in.

## 14. Verifying the fixture is real

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

## 15. Practical traps

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
