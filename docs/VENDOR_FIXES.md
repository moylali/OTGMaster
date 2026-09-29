# Vendored dependency registry

Every vendored dependency is recorded here: what it is pinned to, why it is vendored
at all, and every local patch applied to it. A patch that is not in this file will be
lost the next time upstream is pulled.

See `CLAUDE.md` for what a new entry must contain and how to record an upstream pull.

## Index

| Library | Upstream | Pinned commit | Vendored in |
|---|---|---|---|
| `libaums/` | [magnusja/libaums](https://github.com/magnusja/libaums) | `57fa482` | `b03705d` (last pull) |
| `app/src/main/cpp/exfat/` | [relan/exfat](https://github.com/relan/exfat) | see note below | pre-dates this registry |
| `app/src/main/cpp/` (VeraCrypt primitives, mbedtls, Serpent) | VeraCrypt / Mbed-TLS | see note below | pre-dates this registry |

**Licences** are listed in the table in `README.md`, which is authoritative — this
registry deliberately does not restate them, to avoid the two drifting apart.

**Note on the C trees.** Their upstream pins were not recorded when they were brought
in, so the exact commits are unknown. They should be established and written down the
next time either is touched; until then an upstream pull cannot be done safely,
because there is no baseline to diff against. This is precisely the gap the registry
exists to prevent, and it is the reason the rule in `CLAUDE.md` requires a full SHA
before a vendoring commit lands.

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
