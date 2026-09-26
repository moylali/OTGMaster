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
