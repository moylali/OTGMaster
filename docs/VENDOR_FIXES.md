# Vendored dependency registry

Every vendored dependency is recorded here: what it is pinned to, why it is vendored
at all, and every local patch applied to it. A patch that is not in this file will be
lost the next time upstream is pulled.

See `AGENTS.md` for what a new entry must contain and how to record an upstream pull.

## Index

| Library | Upstream | Pinned commit | Vendored in |
|---|---|---|---|
| `libaums/` | [magnusja/libaums](https://github.com/magnusja/libaums) | `57fa482` | `b03705d` (last pull) |
| `app/src/main/cpp/exfat/` | [relan/exfat](https://github.com/relan/exfat) | see note below | pre-dates this registry |
| `app/src/main/cpp/ntfs-3g/` | [tuxera/ntfs-3g](https://github.com/tuxera/ntfs-3g) | `7f0f841` (2026.9.28) | see the section below |
| `app/src/main/cpp/libfsapfs/` | [libyal/libfsapfs](https://github.com/libyal/libfsapfs) + 16 libyal libraries | `f63c83b` (20260921) | see the section below |
| `app/src/test/resources/bitlk/bitlk-images.tar.xz` (test data) | [cryptsetup](https://gitlab.com/cryptsetup/cryptsetup) | `ca4cc7a` | see the section below |
| `app/src/main/cpp/` (VeraCrypt primitives, mbedtls, Serpent) | VeraCrypt / Mbed-TLS | see note below | pre-dates this registry |

**Licences** are listed in the table in `README.md`, which is authoritative — this
registry deliberately does not restate them, to avoid the two drifting apart.

**Note on the C trees.** Their upstream pins were not recorded when they were brought
in, so the exact commits are unknown. They should be established and written down the
next time either is touched; until then an upstream pull cannot be done safely,
because there is no baseline to diff against. This is precisely the gap the registry
exists to prevent, and it is the reason the rule in `AGENTS.md` requires a full SHA
before a vendoring commit lands.

---

# `app/src/main/cpp/ntfs-3g/`

The library half of [tuxera/ntfs-3g](https://github.com/tuxera/ntfs-3g): NTFS read
and write, used by `app/src/main/cpp/ntfs/NtfsNative.cpp`.

- **Pinned upstream:** `7f0f841fc52cf719106c5c93bafe465004e36816`, tagged
  `2026.9.28` (upstream's version number), pinned 2026-10-01.
- **What was taken:** `libntfs-3g/*.c` and `include/ntfs-3g/*.h`, byte for byte, plus
  `COPYING`, `COPYING.LIB`, `AUTHORS`, `CREDITS` and `README`. Left out: the FUSE
  driver (`src/`), `libfuse-lite/`, `ntfsprogs/`, the build system, and two library
  files — `unix_io.c` (device I/O on a path, which Android apps cannot open; the app
  supplies `ntfs_device_operations` instead) and `win32_io.c`.
- **Why vendored rather than a dependency:** there is no Android build of libntfs-3g
  to depend on — upstream ships autotools sources only, and its `configure` cannot run
  against the NDK. It is compiled by `app/src/main/cpp/CMakeLists.txt` against the
  hand-written `app/src/main/cpp/ntfs/config.h`. Why ntfs-3g at all rather than a
  driver written here: NTFS writes (index B+trees, `$Bitmap`, `$MFT` allocation,
  update-sequence fixups, attribute lists) are where a filesystem driver destroys
  volumes, and ntfs-3g's write path has had fifteen years of use on Linux. The ext4
  driver written in this project is the counter-example: it passed every content hash
  while invalidating every checksum on a drive.
- **Licence:** GPL-2.0-or-later, per-file headers (see the table in `README.md`).
  `realpath.c`/`realpath.h` carry no header and fall under the package `COPYING`
  (GPL-2.0). `COPYING.LIB` ships upstream but no vendored file is under it.
- **Portability without patches:** bionic does not declare `ffs()` outside
  `<strings.h>` and lacks the `S_IEXEC`/`S_IWRITE` aliases. Both are supplied in
  `ntfs/config.h`, which is not a vendored file, so the tree stays identical to
  upstream and a pull is a plain copy.

## Patches

| Area | Patch | Commit |
|---|---|---|
| — | none | — |

---

# `app/src/main/cpp/libfsapfs/`

[libyal/libfsapfs](https://github.com/libyal/libfsapfs): read-only APFS, plain and
natively encrypted, used by `app/src/main/cpp/apfs/ApfsNative.cpp`.

- **Pinned upstream:** `f63c83b462275214fc5e4b0919540d892f50b467` (2026-09-23), the
  commit behind release `20260921` (upstream's version number); pinned 2026-10-02.
  libfsapfs pulls its libyal dependencies in with `synclibs.sh`, each at the latest tag
  of its own repository (`https://github.com/libyal/<name>`). Those were:

  | Library | Tag | Commit |
  |---|---|---|
  | `libbfio` | 20260623 | `9603beb63808f194447cf7529f2e8f558f99b7ae` |
  | `libcaes` | 20260905 | `2d670a685f2565ee4a71f3385ca6dd155d06d674` |
  | `libcdata` | 20260703 | `d8bd16d44110182060c8988e7338b5ee29554d8f` |
  | `libcerror` | 20260703 | `3c27e720a6cfe2a07b2c40a36971249c3479f6de` |
  | `libcfile` | 20260704 | `e2274ecced9f233d4a388d1a8d1666036b1d8cbd` |
  | `libclocale` | 20260703 | `dbcc7ab34e6be8f5ad09d7e12e66999c7e9e0513` |
  | `libcnotify` | 20260703 | `4068933706632dbf5fd77c02300c8c851acee646` |
  | `libcpath` | 20260703 | `6003e68102f4c1b238646b4a1501f287e0891dce` |
  | `libcsplit` | 20260703 | `1d91226e21ef3300f89b61fd1c6f6d6fb96e6071` |
  | `libfcache` | 20260520 | `90c637e9ce24b51cdec84f71ec5d2df18486dc28` |
  | `libfdata` | 20260521 | `ac8fc28ad0c9ca7054906e8dfbf0576787bdb130` |
  | `libfdatetime` | 20260521 | `13ef0ac2c66e27349efdec8301d168edefbdfc28` |
  | `libfguid` | 20260521 | `c8f80bf1880ee04052324c58686a00ce1bd591d2` |
  | `libfmos` | 20260520 | `ebbd2e73854ed75ec75f5c86f391bffa8908b825` |
  | `libhmac` | 20260522 | `056f50f329b8e86657b393f90b14030da40216cc` |
  | `libuna` | 20260602 | `ce9d128085a2637a2a2a568b5b8a8f9ad95a9687` |

- **What was taken:** the `.c` and `.h` files of `libfsapfs/`, `common/`, `include/`
  and the 16 libraries above, plus `COPYING`, `COPYING.LESSER` and `AUTHORS`. They are
  byte-identical to the release tarball `libfsapfs-experimental-20260921.tar.gz`
  (SHA-256 `e3466ff83f0cf79ce830070de506eb5661bd6ba254e146383da3cf1279d95b5a`) and to
  the pinned commits, which was checked with `diff -r` before vendoring. Left out: the
  tools (`fsapfstools/`), the Python bindings, tests, documentation, the build system,
  and `libcthreads`, which is only used with libyal's multi-threading support (off
  here; `ApfsFileSystem` serialises every call).
- **Generated headers.** Seven files are produced by `./configure` from `.h.in`
  templates: `common/config.h`, `common/types.h`, `include/libfsapfs.h`,
  `include/libfsapfs/{definitions,features,types}.h` and
  `libfsapfs/libfsapfs_definitions.h`. They were generated by the release tarball's
  `configure` run against the NDK r26 clang for all four Android ABIs, with
  `--disable-shared --enable-static --without-libfuse --without-zlib --without-openssl
  --disable-nls --disable-python --enable-multi-threading-support=no`. The four
  `config.h` outputs differed only in `SIZEOF_LONG`, `SIZEOF_SIZE_T` and
  `_FILE_OFFSET_BITS`, so the checked-in one is the aarch64 output with those three
  made conditional on `__LP64__`. No zlib: libfsapfs has its own deflate; no OpenSSL:
  libcaes and libhmac have their own AES and SHA.
- **Why vendored rather than a dependency:** there is no Android build of libfsapfs to
  depend on. Upstream ships autotools sources, and the previous attempt (PR #20)
  linked prebuilt `.a` files that were not in the repository, so CI could not build
  it. Why libfsapfs rather than a reader written here: it is the only maintained
  open-source APFS reader that decrypts natively encrypted volumes and is plain C,
  and APFS has no public on-disk specification beyond Apple's reference, which leaves
  out what macOS actually writes (see the keybag layout in `docs/TEST_DATA.md` §6b).
- **Licence:** LGPL-3.0-or-later (every vendored file's header, and `COPYING.LESSER`).
  It is why the project moved from GPL-2.0-or-later to GPL-3.0-or-later: LGPL-3.0 code
  cannot be combined into a binary distributed under GPL-2.0. See `README.md`.

## Patches

| Area | Patch | Commit |
|---|---|---|
| `libfsapfs_volume.c`, `libfsapfs_volume_superblock.[ch]` | V-APFS1 — `libfsapfs_volume_get_size` is an unimplemented stub that always fails; it now returns the volume's allocated blocks × block size, parsed from the superblock | the commit after `d7d3fe4` (APFS read support) |

---

# `app/src/test/resources/bitlk/bitlk-images.tar.xz`

BitLocker volumes made on Windows, from cryptsetup's `tests/` directory: the
known-answer set `BitLockerCompatTest` decrypts and compares against.

- **Pinned upstream:** cryptsetup `ca4cc7a44e5794d8ad412f6d4f8463a81fe16d4a`
  (2026-09-22); archive SHA-256
  `68bf5669f777668112d497234ebe2166b5feaf0e33b206b095b917f82937bd30`.
- **Why vendored:** BitLocker volumes can only be created by Windows. These are the
  only independent ones available, and each comes with cryptsetup's SHA-256 of the
  whole decrypted volume, so they test the app's decryption byte for byte. Test
  data only; not in the APK. Their unused ciphertext is zeroed upstream to keep the
  archive small, so the NTFS inside mostly decrypts to noise — they cannot carry
  filesystem tests, which use volumes built by `scripts/make_bitlocker_image.py`
  instead.
- **Licence:** GPL-2.0-or-later (cryptsetup's default per its `README.licensing`).
  See `app/src/test/resources/bitlk/PROVENANCE.md`.

## Patches

| Area | Patch | Commit |
|---|---|---|
| — | none | — |

---

# `app/src/main/cpp/exfat/`

libexfat ([relan/exfat](https://github.com/relan/exfat)), with the device I/O in
`io.c` replaced by JNI calls into `ExFatNative.pread`/`pwrite`. Its upstream pin
pre-dates this registry (see the note under the index).

## Patches

| Area | Patch | Commit |
|---|---|---|
| `io.c` | E1 — `exfat_pread`/`exfat_pwrite` check `NewByteArray` and the Kotlin callback for a pending exception, and return -1 instead of making the next JNI call with it pending, which aborts the app (seen on the emulator: an `OutOfMemoryError` during a benchmark write became SIGABRT in `SetByteArrayRegion`) | the commit adding this row |

---

# `libaums/`

Vendored fork of [magnusja/libaums](https://github.com/magnusja/libaums).

- **Pinned upstream:** `57fa482`, brought in by `b03705d`.
- **Why vendored:** local patches are required for correctness on real hardware —
  six of them are below, and several are defects upstream has not fixed. The FAT32
  layer also needed changes specific to reading a VeraCrypt volume through a
  userspace block device rather than a kernel-mounted one.
- **Licence:** Apache-2.0 (per-file headers; see the table in `README.md`).

## Patches

| Area | Patch | Commit |
|---|---|---|
| `ScsiBlockDevice` | Clamp oversized REQUEST SENSE response | `69fc0c1` (absorbed upstream) |
| `ByteBlockDevice` | V1 — read-modify-write the trailing sector | `f58d556` |
| `FatFile`, `ClusterChain` | V2 — sync `entry.startCluster` with the chain | `f58d556` |
| `FatFile` | V3 — bound reads to the file length | see below |
| `FatFile` | V4 — only flush on close if the handle changed something | see below |
| `ByteBlockDevice` | V5 — no `array()`, so direct buffers work | see below |
| `ClusterChain` | V6 — coalesce consecutive clusters on read | see below |
| `FatDirectory` | V7 — refuse an implausible directory size instead of OOM | see below |
| `FAT` | V8 — stop a corrupt chain instead of following it out of the FAT | see below |
| `FAT` | V9 — write every FAT copy, clamped to the FAT's own length | see below |
| `ScsiBlockDevice` | V10 — retry a medium-changed unit attention during init | see below |
| `ScsiBlockDevice` | V11 — restart every retry from the caller's buffer window | see below |
| `ScsiBlockDevice` | V12 — Reset Recovery before retrying a failed transfer | see below |
| `usb.c`, `ScsiBlockDevice` | V13 — native clear-halt and reset resolve; a missing native cannot crash | see below |
| `FAT` | V14 — allocation wraps at the last data cluster instead of running into the table's padding | see below |
| `FatDirectory` | V15 — a move gives the entry a short name unique in its new directory, and a moved directory's `..` names its new parent | see below |
| `FatDirectory` | V16 — a new file owns no cluster until it is written | see below |
| `FatDirectory` | V17 — `listFiles` reads the weak handle cache once, so a GC cannot null it mid-listing | see below |

## V15 — moves left duplicate short names and a stale `..`

`FatDirectory.move` (files) and `FatDirectory.moveTo` (directories) took the entry out
of one directory and added it to another as it was. Two things in it belonged to the
old directory:

- **The 8.3 short name.** It is generated to be unique among its siblings, and
  libaums' generator gives many names the same one (`f32.bin` and `f33.bin` are both
  `F30000~0.BIN`). Moved next to a sibling holding that name, the directory had two
  entries with the same short name. fsck.fat reports "Duplicate directory entry" and
  renames one to `FSCK0000.000`; Windows' chkdsk does the same. Creating and renaming
  already generated against the directory's names, so only moves did this.
- **A moved directory's `..`.** It kept the old parent's cluster. fsck.fat reports
  "Invalid '..' entry in the second slot".

Found on hardware: the device-matrix run 7 (OnePlus 7) wrote the `bigwrite` tree,
which moves 20 files between directories, onto all four FAT32 partitions. Every file
read back correctly, on the phone after a remount and on the host against its
recipe, and fsck.fat failed all four on duplicate entries (four on D1FAT32, whose
full output was checked; the encrypted three report only the tail of fsck's output,
which shows two or three each). No other check could see it: contents were right,
the structure was not.

Fix: the destination generates a new short name if the entry's collides
(`adoptEntry`), and a moved directory's `..` is set to the new parent's start
cluster (0 for the root) and written. `Fat32MoveFsckTest` fails on the old code for
both, with the same fsck.fat messages as the drives.

## V17 — a GC mid-listing threw NullPointerException

`FatDirectory.listFiles` looked each entry up in `fs.fileCache` twice:
`fileCache[path] != null -> fileCache[path]!!`. The cache is a
`WeakHashMap<String, UsbFile>` keyed by the path string, which nothing else holds, so
any garbage collection can clear an entry. One landing between the two reads made the
second null, and `!!` threw. Every `search` lists its directory, so lookups failed
the same way.

It was intermittent and only on the phone, where heavy I/O keeps the collector busy:
device-matrix run 8 (OnePlus 7, D2BLFAT32) failed "dense opens" with a bare
`java.lang.NullPointerException` right after 2.1 GB of sequential reads. The benchmark
harness had guarded against a "null name" since it was written, without the cause
being known. The same lookups on the host, on a FAT32 image holding the same fixture
tree, did not fail.

Fix: read the cache once into a local. `Fat32ListGcRaceTest` reproduces it on the
host: a thread churning the heap while a 1000-entry directory is listed repeatedly.
The old code threw at `FatDirectory.kt:434` within 10 s. The new code listed 331,582
times in 15 s without failing. The harness now also logs the stack trace of every
failed section, which would have named this line the first time.

## V16 — an empty file owned a cluster

`createFile` allocated one cluster for every new file. A file that was never written
was then 0 bytes with a one-cluster chain: fsck.fat reports "File size is 0 bytes,
cluster chain length is > 0 bytes" and a wrong free-cluster count, and the cluster
is lost until it does. Truncating to 0 already freed the whole chain (V2 writes start
cluster 0 back), so only creation did this.

Found alongside V15: the `bigwrite` tree's first file is created 0 bytes, and fsck.fat
flagged it on D1FAT32 (`renamed_f0.bin`) and D2BLFAT32 (`d4/e1/f0.bin`); whether the
other two had it is outside the part of fsck's output the verifier keeps.

Fix: a new file has start cluster 0. `FAT.getChain(0)` is the empty chain, and the
first write allocates and V2 records the start cluster. `Fat32MoveFsckTest` fails on
the old code; after the fix an empty file is fsck-clean, and the same file written to
10,000 bytes later reads back and stays clean.

## V14 — allocation ran past the end of the data area

`FAT.alloc` searched for free clusters by counting upward from the FSInfo hint with
no upper bound and no wrap. A FAT32 table is sized in whole sectors, so it nearly
always has entries past the last data cluster — 744 of them on the device-matrix
drives' 5600 MiB partitions — and they are zero, which reads as free. On a volume
whose hint sat near the end, `alloc` handed those out, then went on into whatever
followed the table, and the clusters were written past the end of the partition.

Found on hardware: the OnePlus 7 run on device-matrix D1FAT32 (nearly full) failed
`bigwrite` with "Write exceeds slice bounds" — `SlicedBlockDevice` refusing a write
past the partition. On a stick with one unsliced partition nothing would refuse it.

Fix: `lastDataCluster` from the boot sector (data sectors / sectors per cluster + 1,
capped at the table's extent); the scan wraps from there to cluster 2, a hint past it
is ignored, and after one full pass `alloc` throws `IOException` (volume full) rather
than looping. `Fat32AllocBoundsTest` reproduces it: a 64 MiB image (128 padding
entries), the hint moved 8 clusters before the end, a 64-cluster file. Before the fix
the image grew by 29,184 bytes (57 clusters written past the end); after it, the image
keeps its size, `fsck.vfat -n` is clean, and the file reads back.

## V13 — the native USB helpers never resolved

**What upstream does wrong.** `src/c/usb.c` names its JNI functions
`Java_me_jahnen_libaums_usb_AndroidUsbCommunication_*`, for a package
`me.jahnen.libaums.usb`. The class is `me.jahnen.libaums.core.usb.AndroidUsbCommunication`
— the `core` segment was added upstream without renaming the C side. `libusb-lib.so`
loads, but neither `clearHaltNative` nor `resetUsbDeviceNative` ever resolves, so
`clearFeatureHalt()` and `resetDevice()` throw `UnsatisfiedLinkError`.

**Why it mattered.** Upstream only reached `clearFeatureHalt()` from its pipe-error
and phase-error paths, so it lay dormant. V12 made Reset Recovery run after any
failed transfer, and a card reader's first command fails routinely (V10). OnePlus 7,
SD card in a Realtek reader, build `a25bdc0`: the app crashed on every plug-in —

```
UnsatisfiedLinkError: No implementation found for ... AndroidUsbCommunication.clearHaltNative(int, int)
  at AndroidUsbCommunication.clearFeatureHalt
  at ScsiBlockDevice.bulkOnlyMassStorageReset
  at ScsiBlockDevice.transferCommand
  at ScsiBlockDevice.init
```

V12 caught `Exception`; `UnsatisfiedLinkError` is an `Error`, so it went through.
On the Samsung the reset had failed one step earlier, with an ordinary
`IOException` from the control transfer, which is why V12's validation run did
not show it. V12 was pushed but never tagged; no release carried it.

**The patch.** The two C functions are renamed to the `core.usb` path — verified
in the built APK with `nm -D`. And V12's catch also takes `LinkageError`, so a
native method that fails to resolve can fail a reset but never the app.

`src/c/errno.c` has the same stale naming (`com_github_mjdev_libaums_ErrNo_*`)
and is **not** changed here: `ErrNo` is read on every failed transfer and has
never thrown on-device, reporting `errno 0 null` instead, which is not explained
yet. Left alone until it is.

**Test.** None on the host: the defect is JNI resolution on Android. Verified on
the device that crashed.

## V12 — one failed transfer left the drive out of step for good

**What upstream does wrong.** `transferCommand` retries an `IOException` by
sending the command again, and nothing else. It only sends Reset Recovery —
`bulkOnlyMassStorageReset()`, the class reset plus clearing halt on both
endpoints — for a `PipeException` or a phase error. But a transfer that fails
part-way leaves the rest of its data and its CSW queued in the device. The retry
reads that stale CSW, gets `wrong csw tag!`, retries again the same way, and
reaches `MAX_RECOVERY_ATTEMPTS`. The next command then starts one status behind,
and so does every command after it. BOT 1.0 §5.3.4 requires Reset Recovery after
an invalid CSW.

**Why it mattered.** Found by V11's hardware validation. Samsung M30, VeraCrypt +
exFAT, `a192b12`, during `fixtures`:

```
08:16:23.179  Could not read from device, result == -1 errno 0 null, retrying...
08:16:23.286  wrong csw tag!, retrying...      (x5, then MAX_RECOVERY_ATTEMPTS)
08:16:23.865  wrong csw tag!, retrying...      (the next command: the same)
```

V11 had done its part — no `IllegalArgumentException`, nothing misplaced — but
the drive was unusable to the app from that point until replugged; the report
write that followed failed too. A single brief transfer error became the loss of
the drive.

**The patch.** The `IOException` branch sends Reset Recovery before the retry. A
reset that itself fails is logged and the retry proceeds, so the outcome is never
worse than before. The retried command is re-sent whole (V11), which is safe for
READ(10) and WRITE(10).

**Test.** `ScsiResetRecoveryTest`, against a fake that behaves like the device: a
failed command's leftover data and CSW stay queued until the class reset
(`0x21`/`0xFF`) arrives.

| Case | Upstream + V11 | V12 |
|---|---|---|
| read fails part-way | FAIL — `MAX_RECOVERY_ATTEMPTS Exceeded`, the Samsung's error | pass, exactly one reset |
| the command after a failure | FAIL — `MAX_RECOVERY_ATTEMPTS Exceeded` | pass |
| no fault | pass | pass, no reset |

The fake needs real `UsbInterface` / `UsbEndpoint` objects, since the reset reads
the interface id and clears both endpoints. Their constructors are hidden; the
test calls the package-private ones by reflection, which the mockable
`android.jar` allows.

## V11 — a retried transfer read into, or wrote from, the wrong place

**What upstream does wrong.** `transferCommand` retries a failed command up to
`MAX_RECOVERY_ATTEMPTS` times, and `transferOneCommand` starts each attempt's data
phase at `inBuffer.position()`. But a transfer that fails part-way has already
moved the position: `JellyBeanMr2Communication` advances it by whatever a partial
`bulkTransfer` moved before a later call returns `-1`. The retry then takes the
advanced position as its start and sets `limit = position + transferLength`.

**Why it mattered.** Two outcomes, depending on the buffer's shape:

- **The limit overruns the capacity** and `ByteBuffer.limit()` throws
  `IllegalArgumentException`. That is not an `IOException`, so it escapes the retry
  loop: a hiccup the loop exists to absorb becomes a hard failure. Observed on a
  Samsung M30 (VeraCrypt + FAT32, `a03da36`): a read returned `-1`, the retry got
  `wrong csw tag!`, the next threw, and `fixtures` failed; the report write after
  it failed too.
- **The limit fits**, and the retry silently transfers from the wrong offset. This
  is the common case, not the exception: `LibaumsRawBlockDevice` passes each chunk
  as `ByteBuffer.wrap(array, offset, length)`, whose capacity is the whole array,
  so every chunk but the last has room. A read then lands shifted and returns
  wrong bytes; **a write sends bytes from partway into the buffer — including the
  next chunk's data — to the chunk's starting block.** No error is raised either
  way. The Samsung failure threw only because its partial read had got far
  enough; a shorter one would have been silent.

**The patch.** `transferCommand` records the buffer's position and limit once and
restores both before every attempt, including the retry after a successful
REQUEST SENSE. Re-sending a whole READ(10) or WRITE(10) is safe: both are
idempotent at the block level.

**Test.** `ScsiRetryPositionTest` drives the real `ScsiBlockDevice` against a fake
reader that delivers half of one data phase and then fails the transfer the way
`JellyBeanMr2Communication` does on `-1` — position already advanced, then an
`IOException`.

| Case | Upstream | V11 |
|---|---|---|
| read cut short, exact-size buffer | FAIL — `newLimit > capacity: (6144 > 4096)`, the Samsung's error | pass |
| read cut short, chunk of a larger array | FAIL — wrong bytes from offset 2048, no error | pass |
| write cut short, chunk of a larger array | FAIL — the device received the wrong bytes, no error | pass |
| no fault | pass | pass |

The write case's data must not repeat within the chunk. The first version used
`0xA5 xor i`, whose 256-byte period made the misplaced bytes identical to the
right ones, and it passed on upstream.

## V10 — a USB card reader failed its first open

**What upstream does wrong.** `ScsiBlockDevice.init()` retries `InitRequired` and
`NotReadyTryAgain`, and `checkResponseForError` already maps two unit-attention
codes — reset occurred (0x29) and commands cleared (0x2F) — onto the retryable
`NotReadyTryAgain`. It does not map 0x28, *not ready to ready change, medium may
have changed*, which is what a card reader reports on the first command after a
card goes in. That surfaced as `UnitAttention` and `init()` let it out.

**Why it mattered.** Observed on a Huawei P20 Lite with an SD card in a Realtek
USB reader: `Could not open RawBlockDevice via libaums` with
`UnitAttention (ASC: 40, ASCQ: 0)` — 40 decimal is 0x28 — on every first plug-in,
twice in a row as the reader re-enumerated. SCSI reports a unit attention once
and then clears it, so tapping Scan USB Devices got through; the first open never
did. USB sticks have no removable medium and never raise it, which is why a fleet
of stick-based drives never showed it.

**The patch.** `init()` catches `UnitAttention` and retries it when, and only
when, the code is 0x28. It is deliberately **not** added to
`checkResponseForError`: that function serves every command, and mid-transfer a
changed medium means the card really was swapped — retrying there would carry on
reading a different card as though it were the same one. During init nothing has
been read yet, so a changed medium is the expected state.

**Test.** `CardReaderUnitAttentionTest` drives the real `ScsiBlockDevice` against a
fake bulk-only reader whose first TEST UNIT READY fails and whose REQUEST SENSE
then reports unit attention 0x28, once.

| Case | Upstream | V10 |
|---|---|---|
| medium-changed reported once | FAIL — `Unit attention (ASC: 40, ASCQ: 0)`, the device's own error | pass |
| medium-changed never clears | FAIL — throws on the first attempt | pass — gives up at `MAX_RECOVERY_ATTEMPTS` |
| unrelated unit attention (0x3F) | pass | pass — still thrown |

The third row passes on both by design: it fails if the retry is ever widened
past the one code a card insertion produces.

**Confirmed on hardware.** Same Huawei P20 Lite and Realtek reader, build
`f8b8042`, card unplugged and reinserted with no rescan:

```
06:38:22.921  W ScsiBlockDevice: Unit attention (ASC: 40, ASCQ: 0)
06:38:22.921  I ScsiBlockDevice: medium changed (unit attention 0x28) during init, retrying
06:38:23.027  I ScsiBlockDevice: Block size: 512
06:38:23.057  I VeraCryptUnlocker: Candidate 'Partition 1': VERACRYPT
```

The same condition that failed the open twice earlier that morning, retried once
and opened 106 ms later.

## V9 — the second FAT was never written

**What upstream does wrong.** `FAT` computes `fatOffset[]` for every copy when
the volume is mirrored, and logs that it knows — `"fat is mirrored, fat count:
2"` — then writes only `fatOffset[0]`. Its own comment above the first write:
`// TODO we should write in in all FATs when they are mirrored!`

So FAT[1] stayed exactly as `mkfs` left it, and `fsck.fat` reported `FATs differ`
on every FAT32 volume this app had written to. On the re-prepared benchmark
drive, after one run and with the V8 fix in place, that was the *only* thing
`fsck.fat` found.

**The patch.** Every write in `FAT.kt` — seven sites across `alloc` and `free` —
goes through `writeFat`, which writes the FAT[0] buffer and then the same bytes
at each other copy's offset.

The mirror is **clamped to the FAT's own length**. Writes go in two-block
buffers, and a FAT with an odd sector count ends half-way through its last
buffer, which therefore overhangs into FAT[1]. Replaying the whole buffer at
FAT[1]'s offset writes its second half past FAT[1]'s end — onto cluster 2, the
root directory. The benchmark drive's FAT is 118,073 sectors, so this was a
live path, not a theoretical one.

**Ordering.** V9 was held back deliberately until V8 was confirmed on hardware.
While the free path could still zero entries it did not own, FAT[1] was the only
record of the truth — it is what allowed the FAT32 damage to be diagnosed at all.
Mirroring then would have written the damage into both copies.

**Test.** `Fat32MirrorTest`, verified against three builds:

| Build | Plain write | Write in the FAT's last, overhanging buffer |
|---|---|---|
| upstream, no mirror | FAIL — FATs differ | FAIL — FATs differ |
| mirror, unclamped | pass | **FAIL — root directory overwritten** |
| V9 | pass | pass |

The overhang test places its file with the FSInfo next-free hint, on an image
built with `mkfs.vfat -a` so the FAT keeps an odd sector count, and writes into a
subdirectory. An earlier version wrote into the root and passed on the
*unclamped* build: closing the file rewrites the root directory from memory,
straight over the damage. On a real drive the writes land in subdirectories and
nothing repairs it, so the test was rewritten until the unclamped build failed.

**Existing volumes** keep whatever divergence they already have — V9 mirrors new
writes, it does not copy history. Resync one with `fsck.fat -a` on the decrypted
device, but only after confirming FAT[0] is the correct copy (a baseline compare
showing only expected changes is enough). Doing it over a damaged FAT[0] copies
the damage into the only good copy.

## V8 — a corrupt chain destroyed the rest of the volume

**What upstream does wrong.** `FAT.getChain` follows any cluster below
`FAT32_EOF_CLUSTER` (`0x0FFFFFF8`), testing only the upper end:

```kotlin
currentCluster = (buffer.getInt(offsetInBlock.toInt()) and 0x0FFFFFFF).toLong()
} while (currentCluster < FAT32_EOF_CLUSTER)
```

Cluster 0 is below that marker, so a single zero in the middle of a chain does
not end the walk. The loop appends 0, reads FAT entry 0 — the media descriptor,
`0x0FFFFFF0` — finds that below the marker too, and continues to cluster
268,435,440, whose byte offset is about 1.07 GB into a volume whose FAT is
57 MiB. From there it reads the **data area** as though it were FAT entries and
keeps walking. `free()` is then handed the whole fabricated chain and faithfully
writes zeros to every offset in it.

Upstream already refuses `startCluster == 0` in the same function — it knows 0
is not a cluster — but never applies the test mid-walk.

**Why it mattered.** Found by running `fsck.fat` on a benchmark drive after a
run that passed every on-device check: write verify on all three passes,
unaligned A and B, correctness A–G, and `fixtures` against host-computed hashes.
The drive had FAT entry 0 zeroed and roughly 514 MB of an unrelated file's chain
wiped — `/FILL/fill_0023.bin`, a fixture no part of the benchmark writes to.

It stayed invisible because the app reads the same FAT it had corrupted. Only a
byte-level comparison of the two FAT copies exposed it, and that comparison was
only possible because of a *second* upstream defect: libaums writes
`fatOffset[0]` and never mirrors, so FAT[1] still held the correct chains. That
defect is still open, deliberately — fixing it first would have copied the
corruption into the only intact record and made the drive unrecoverable.

**The patch.** Bound the cluster at both ends, against the FAT's own extent:

```kotlin
private fun isValidCluster(cluster: Long): Boolean =
    cluster >= 2 && cluster <= maxValidCluster   // fatSizeBytes / 4 - 1
```

applied in `getChain` (break, with a warning, and do not cache a chain cut short
by corruption) and again in `free` (skip the entry). The check is repeated at the
destructive site on purpose: guarding only the producer leaves any other caller
able to destroy the volume, and `free` is where the zeros are written.

A lower bound alone is not sufficient. Any value in `[2, 0x0FFFFFF7]` past the
end of the table computes an offset outside the FAT and lands in user data;
`0x0FFFFFF0` is itself such a value. The upper bound is what turns "writes zeros
into user data" into "leaks a cluster".

**Test.** `Fat32CorruptChainTest` builds a FAT32 image, creates a file, plants
one zeroed entry mid-chain, deletes the file, and asserts that FAT entry 0 and a
byte in the data area are untouched. It fails on the unpatched code with
`FAT entry 0 … expected:<-8> but was:<0>` — the media descriptor `0xF8` zeroed by
a delete — and passes on the patched code.

The assertions deliberately are not "is `fsck` happy". `fsck.fat` passed the
damaged drive by reading FAT[1], which libaums never writes and which was
therefore fine; a clean checker would not have caught this.

## V1 and V2 — reproduced on hardware, then fixed

Both were **silent data corruption** with no crash and no error returned. Neither
was fixable from app code, because the defect is inside libaums' own write path.

Both were reproduced before fixing, on a Pixel 10 Pro XL (Android 17) against a
FAT32 VeraCrypt volume, using the benchmark's `unaligned` section. The pre-fix and
post-fix APKs were confirmed to differ by SHA-256 before each install — an earlier
attempt reported a false pass because the APK had not been rebuilt.

| | Pre-fix | Post-fix |
|---|---|---|
| V1 sector tail | FAIL — a 10-byte write zeroed 502 bytes | PASS |
| V2 truncate+rewrite | FAIL — read back freed content; entry claimed 178 MB for a 2 KiB file | PASS |

exFAT passes both either way: it goes through libexfat's own pread/pwrite, so these
defects are FAT32-only. That bounds the blast radius.

The wider `correct` section (neighbour-file integrity, unaligned offset *and* length,
100 odd-sized appends, recycled-cluster leakage) passes on both filesystems, with
identical SHA-256 across both devices.

### V1. `ByteBlockDevice.write` zero-fills the tail of the final sector

`libaums/src/main/java/me/jahnen/libaums/core/driver/ByteBlockDevice.kt`

A write whose length is not a multiple of `blockSize` allocates a rounded-up
buffer, copies only the source bytes into it, and writes the whole thing — so the
remainder of the last sector is overwritten with zeros instead of the data already
on disk.

```kotlin
if (src.remaining() % blockSize != 0) {
    val rounded = blockSize - src.remaining() % blockSize + src.remaining()
    buffer = ByteBuffer.allocate(rounded)     // zero-filled
    buffer.limit(rounded)
    // TODO: instead of just writing 0s at the end of the buffer do we need to read
    // what is currently on the disk and save that then?     <-- upstream's own TODO
    System.arraycopy(src.array(), src.position(), buffer.array(), 0, src.remaining())
    src.position(src.limit())
}
targetBlockDevice.write(devOffset, buffer)
```

The *leading* partial sector is handled correctly a few lines above — it does a
read, overlays, and writes back. Only the trailing sector is wrong, and upstream's
own TODO comment names the missing step.

**Impact.** Any modification with an unaligned tail destroys up to `blockSize - 1`
bytes past the end of the written range. For an in-place edit of an existing file
that is real user data loss, not merely a stale tail.

**Fix.** Read the tail sector before overlaying — the same read-modify-write the
leading-sector branch already performs.

**Why our block cache did not hide it.** `CachedBlockDevice` sits *below* this, so
it faithfully writes whatever zero-padded buffer it is handed.

### V2. `entry.startCluster` is never synchronised with the cluster chain

`libaums/src/main/java/me/jahnen/libaums/core/fs/fat32/FatFile.kt`,
`ClusterChain.kt`

`ClusterChain` replaces its whole `chain` array when growing or shrinking:

```kotlin
chain = if (newNumberOfClusters > oldNumberOfClusters)
    fat.alloc(chain, newNumberOfClusters - oldNumberOfClusters)
else
    fat.free(chain, oldNumberOfClusters - newNumberOfClusters)
```

so the chain's **first** cluster can change. Nothing propagates that back to
`entry.startCluster`, and `FatFile.flush()` writes the stale entry to disk via
`parent!!.write()`.

The damaging sequence is truncate-then-rewrite, which SAF performs routinely —
`VeraCryptDocumentProvider` sets `file.length = 0` for mode `"t"`:

1. `length = 0` frees every cluster; `chain` becomes empty.
2. A later write allocates a **new** start cluster.
3. `entry.startCluster` still names the original, now-freed cluster.
4. The directory entry is flushed pointing at freed space.

**Impact.** After remount the file reads as garbage, or cross-links into whatever
has since been allocated that cluster — corruption that spreads to *other* files.

**Fix.** Expose the chain's current first cluster from `ClusterChain` (the `chain`
array is `private`, so this needs a vendor-side accessor) and assign it to
`entry.startCluster` whenever the chain is allocated or freed — including setting
it to `0` when the file is truncated to zero length.

**Note on scope.** This cannot be worked around in app code: both `chain` and
`entry` are internal to libaums, and the flush happens inside `FatFile`.

## Not vendor issues

For completeness, the defects found in the same review that live in **our** code,
and so are not listed above: the exFAT `node.size` desynchronisation and the
swallowed native `-1` read error (`ExFatFile.kt`), and the globally serialised
`ProxyFileDescriptorThread` (`VeraCryptDocumentProvider.kt`).

## V3 — `FatFile.read` leaked cluster slack

`FatFile.read` handed the caller's buffer straight to `ClusterChain.read`, which
fills whatever it is given, cluster by cluster, with no reference to `fileSize`.

On an encrypted volume this is information disclosure, not untidiness: the slack
holds the **decrypted plaintext** of whatever previously occupied the cluster.
Reading at or past EOF also indexed past the end of the `chain` array.

Reproduced on a Pixel 10 Pro XL, FAT32 VeraCrypt volume — fill 64 KiB with `0xDD`,
delete it, write a 100-byte file into the recycled clusters, then ask for 4096
bytes:

| | Pre-fix | Post-fix |
|---|---|---|
| Over-long read of a 100-byte file | 4096 bytes returned, 25 of them the freed `0xDD` marker | bounded to 100 bytes |
| Read 8 bytes past EOF | 512 bytes returned | clean no-op |

**Note on the fix itself.** The first version computed
`(length - offset).toInt()`, which overflows to negative at exactly 2 GiB, so every
read of a file that size or larger returned zero bytes. The correctness suite passed
all ten cases regardless, because its fixtures are 4–8 KiB; only the SAF section,
which reads `seq_2g.bin`, was large enough to notice. The `min` is now taken in
`Long` before narrowing.

## V4 — every close rewrote the parent directory

`close()` called `flush()` unconditionally, and `flush()` calls `parent!!.write()`,
which serialises and rewrites the **entire** parent directory table. `read()` also
touched the access time, so purely reading a file dirtied the entry. Opening 100
files in a 1,000-entry directory rewrote that directory 100 times — write
amplification on a bus measured at single-digit MB/s, plus needless flash wear.

Measured with a device-write counter: a read-only open and close wrote **8 blocks
(32 KiB)** before the fix and **nothing** after.

Fixed with a `dirty` flag, the same pattern `ExFatFile` has used since `a492960`.

## V5 — `array()` crashed on direct buffers

`ByteBlockDevice` called `src.array()` / `dest.array()` at four sites.
`ByteBuffer.allocateDirect` and NDK shared memory have no backing array, so
`array()` throws `UnsupportedOperationException`. Replaced with buffer-API copies,
which work for both kinds.

## V6 — `ClusterChain.read` issued one command per cluster

`write()` already coalesced up to four consecutive clusters; `read()` read exactly
one at a time, unconditionally. A 512 KiB read at 4 KiB clusters became 128 separate
SCSI commands.

The signature was throughput that ignored the caller's buffer size — 4.53, 4.99 and
4.97 MB/s for 32, 128 and 512 KiB buffers, because each cluster was its own command
regardless. After coalescing it scales: 6.82, 15.23, 14.59 MB/s. That change of
*shape* is the evidence; the absolute figures are cross-session and not sound enough
on their own (see §5.7 for why that distinction is enforced here).

The cap is 32 clusters rather than `write()`'s 4. That lower cap existed because
oversized spans used to reach the USB stack directly, and some stacks reject them —
but `LibaumsRawBlockDevice` now splits anything larger into 120 KiB transfers, so the
original hazard is handled a layer below. 32 clusters is 128 KiB at 4 KiB clusters,
past the knee of the block layer's size/throughput curve.

## V7 — a corrupt directory chain OOM'd the process

`FatDirectory` read a directory with `ByteBuffer.allocate(chain.length.toInt())`,
sized from the cluster chain and unbounded. A corrupt chain — circular, or one whose
length overflows an `Int` — reports an enormous size, and the allocation then throws
`OutOfMemoryError`, killing the process merely for opening the directory.

Not hypothetical: during this work a corrupt FAT32 entry reported 178 MB for a 2 KiB
file and OOM'd the app mid-test.

Now bounded at 32 MiB, which is far above anything legitimate — a directory of 10,000
long-name entries is about 1.2 MB — and refused with an `IOException` the caller can
report instead of a process death. Verified on both devices: 10,000-entry directories
still list normally.
