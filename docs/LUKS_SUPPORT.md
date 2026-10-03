# LUKS support: evaluation, design, and test-data preparation

An assessment of `agy/luks_extension_proposal.md`, the gaps it leaves, and what is
needed to verify an implementation on real hardware.

## 1. What the proposal gets right

**Generalising `NativeDecryptedBlockDevice` into a `CryptoBlockDevice`** is the
correct shape. Everything above it — `CachedBlockDevice`, libaums, libexfat, the
DocumentsProvider — depends only on `RawBlockDevice`, so a new encryption format
inherits the readahead, the write-through patching and the SCSI serialisation for
free. That layering is also already load-bearing: the block cache took exFAT
directory listing from 164 s to 2 s, and none of it is format-specific.

**Parsing the headers in Kotlin rather than vendoring `libcryptsetup`.** Compiling
cryptsetup for Android would drag in device-mapper assumptions that make no sense in
userspace, plus popt, json-c, blkid and a large legacy surface. LUKS1's header is a
fixed big-endian struct; LUKS2's is a binary header plus a JSON document. Both are
tractable.

**A `VolumeUnlocker` interface with `identify()` first.** LUKS has magic bytes
(`LUKS\xba\xbe`), VeraCrypt has none and must blind-probe, so trying LUKS first is
both cheaper and deterministic.

**Reusing the existing PBKDF2.** `mbedtls_pkcs5_pbkdf2_hmac` is already vendored and
used for VeraCrypt headers.

## 2. Gaps in the proposal

These are the things I would not discover until the implementation failed on a real
drive.

### 2.1 Argon2 memory cost is a hard constraint on a phone — the biggest risk

LUKS2 defaults its Argon2id parameters to roughly *half the RAM of the machine that
formatted the volume*, capped around 4 GB. A container created on a 32 GB desktop can
legitimately request **1–4 GB of memory** to derive one key.

This app has been observed at 167–300 MB RSS. A 1 GB Argon2 allocation will be killed
by Android's Low Memory Killer on most devices, and on a 2018 phone it is hopeless.

Consequences the proposal does not draw:

- The Argon2 parameters must be **read from the LUKS2 JSON and checked against
  `ActivityManager.MemoryInfo` before allocating**, with a clear refusal rather than
  an OOM. "This volume needs 2 GB of memory to unlock; this device has 700 MB
  available" is a usable message. A dead process is not.
- Even a permitted derivation is slow. Argon2id at 1 GB takes seconds on a desktop
  and much longer on a phone. The unlock path needs progress reporting, and must not
  run on the main thread.
- **Some LUKS2 volumes will not be openable on some devices, and that is not a bug.**
  This should be stated in the UI and the README rather than presented as a failure.

`argon2kt` is a reasonable binding choice, but its licence must be checked against
this project's GPL-3.0-or-later grant and added to the table in `README.md` and to
`docs/VENDOR_FIXES.md` per `AGENTS.md`.

### 2.2 Key size and sector size are hardcoded today

`xtsCrypt` in `VeraCryptNative.cpp` calls `mbedtls_aes_xts_setkey_dec(&ctx, key64,
512)` — a fixed 512-bit key — and `cryptSectorsInPlace` assumes **512-byte sectors**
throughout.

LUKS breaks both:

- `aes-xts-plain64` is commonly 512-bit (two 256-bit halves) but **256-bit is legal**
  (two 128-bit halves). The key length must become a parameter.
- LUKS2 segments carry a `sector_size` of 512, 1024, 2048 or **4096**. The XTS tweak
  advances per *sector*, so a 4096-byte sector size changes the tweak sequence
  entirely. Getting this wrong decrypts to plausible-looking garbage rather than
  failing loudly.

### 2.3 Cipher modes other than XTS

LUKS1 defaulted to **`aes-cbc-essiv:sha256`** for years before moving to
`aes-xts-plain64`. Any older drive will be CBC-ESSIV, which is a different
construction (IV derived by encrypting the sector number with a hash of the key) and
is not reachable by the XTS path.

Decision: **support `aes-xts-plain64` only in the first version, and refuse anything
else by name.** "This volume uses aes-cbc-essiv, which is not supported" is far better
than mounting garbage. CBC-ESSIV can follow if real drives demand it.

### 2.4 The password digest check is missing, and it matters most

The proposal describes deriving the key and merging the AF stripes, but never
*verifies* the result. LUKS stores a digest of the master key for exactly this:

- **LUKS1:** `mk-digest` (PBKDF2 of the master key, with `mk-digest-salt` and
  `mk-digest-iter`).
- **LUKS2:** the `digests` section, binding keyslots to a segment.

Without that check a wrong password yields a wrong master key, which decrypts the
filesystem to noise. libexfat or libaums then reads that noise as metadata. Given
this codebase has just spent a long time fixing cases where corrupt metadata caused
crashes and cross-linked writes, **mounting with an unverified key is the single most
dangerous thing on this list** — and it is a write-capable mount.

VeraCrypt gets this for free because its decrypted header contains a magic value. LUKS
needs the digest comparison done explicitly.

### 2.5 Multiple keyslots

LUKS1 has 8 keyslots; LUKS2 has an arbitrary number. A password may match *any* of
them. The unlock path must iterate active keyslots and stop at the first whose derived
key satisfies the digest — not assume slot 0.

### 2.6 Authenticated encryption must be detected and refused

LUKS2 supports integrity-protected modes (`aes-gcm-random`, `aes-xts-plain64` with
`--integrity`), which pair the volume with dm-integrity metadata. That is not a
block-for-block transformation and cannot be handled by a `CryptoBlockDevice` at all.
Detect an `integrity` field in the segment and refuse clearly.

### 2.7 LUKS2 header handling is more involved than "read the JSON"

There are **two header copies** (primary and secondary), each with a **SHA-256
checksum** over the header and JSON area. A correct reader validates the checksum and
falls back to the secondary copy when the primary is damaged — which is precisely the
case a recovery-minded user will have.

### 2.8 Detached headers and keyfiles

Both are common in real use (`--header`, `--key-file`). Neither needs supporting
initially, but both must produce a comprehensible message rather than "not a LUKS
volume".

## 3. Recommended scope for a first version

| Supported | Refused, by name, with a clear message |
|---|---|
| LUKS1 and LUKS2 | Detached headers, keyfiles, LUKS2 tokens |
| `aes-xts-plain64` | `aes-cbc-essiv`, `aes-cbc-plain`, any non-XTS mode |
| 256-bit and 512-bit keys | Authenticated/integrity modes |
| 512 and 4096-byte sectors | Argon2 parameters exceeding available memory |
| PBKDF2 and Argon2id/i | |
| Any active keyslot | |
| **Mandatory digest verification before mounting** | |

## 4. Verification setup

**LUKS volumes cannot be created on macOS.** It is a Linux device-mapper construct;
there is no `cryptsetup` for macOS and no Homebrew formula. Docker Desktop cannot help
either, because it runs Linux in a VM with no raw USB passthrough — a privileged
container sees the VM's disks, not `/dev/disk4`.

One of these is required:

1. **A Linux VM with USB passthrough** (UTM is free and native on Apple Silicon).
   Preferred: the preparation stays scriptable and reproducible, and can be driven
   over ssh.
2. **A Linux machine or Raspberry Pi** on the same network.
3. **Manual execution** of §5 on any Linux host.

Reading and unlocking, once prepared, is entirely the app's job on the phone — no
Linux needed for the actual testing.

## 5. Test-data preparation

**Moved.** The preparation commands live in
[docs/TEST_DATA.md](TEST_DATA.md) §8–§12 — partitioning, `luksFormat` for both header
formats, the filesystems, the fixture tree, the host-computed manifest and teardown.

They were duplicated here and there is now one copy, because two copies of a
`cryptsetup` invocation diverge and the stale one gets pasted. That document also covers
the two single-partition ext4 drives, which this section never did.

The one parameter worth repeating, because everything else depends on it:
**`--pbkdf-memory 65536`** on every LUKS2 volume. cryptsetup's desktop default of
1–4 GB of Argon2 memory cannot be allocated on a phone, so a drive formatted with
defaults tests only the out-of-memory path. See §2.1.

## 6. What this drive tests beyond LUKS

Four encrypted partitions on one physical device is the configuration that has been
missing all along:

- **`driveTag` collision detection.** All four partitions share a
  `sourceDeviceName`, and `ExFatFileSystem.volumeLabel` is a hardcoded `"exFAT"`, so
  two of them are indistinguishable by label. The tag was changed to use the
  partition's start offset for exactly this case, and it has never been exercised
  against a real second partition. A collision means a remount-and-verify section
  could check the *wrong* volume and report a pass that means nothing.
- **The `drive` selector** (`--es drive 0,2`), likewise unexercised.
- **`saf par cross`** — cross-drive SAF concurrency, the last open question on whether
  per-drive handler threads would buy anything. Two mounted volumes are enough.
- **Per-partition `unmountDrive` behaviour** — the raw USB connection must be closed
  only when the *last* partition is unmounted. That branch exists and has never run.

## 7. Acceptance criteria

An implementation is verified when, on at least two devices:

1. All four partitions are identified correctly, with `luksDump` agreeing on cipher,
   key size, payload offset, sector size and KDF.
2. A wrong password is **refused by digest check**, not mounted.
3. All eleven correctness cases pass on each partition, judged after a proven remount.
4. The 16 MiB write verification passes all three passes on each partition.
5. `fixtures` **matches the host-computed hashes** — the first time this check will
   have established anything.
6. A LUKS2 volume formatted with desktop-default Argon2 memory is refused with a clear
   message on a low-memory device, rather than OOMing.
7. Two partitions mounted simultaneously produce distinct `driveTag` values and no
   collision warning.
