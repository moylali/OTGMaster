# Vendor patches to `libaums/`

`libaums/` is a vendored fork of [magnusja/libaums](https://github.com/magnusja/libaums),
pinned upstream and patched in-tree. Every local patch must be recorded here, so a
future `chore: pull libaums upstream` knows what to preserve or re-apply.

Current pin: upstream `57fa482` (`b03705d`).

## Existing patches

| Area | Patch | Commit |
|---|---|---|
| `ScsiBlockDevice` | Clamp oversized REQUEST SENSE response | `69fc0c1` (absorbed upstream) |
| `ByteBlockDevice` | V1 — read-modify-write the trailing sector | `f58d556` |
| `FatFile`, `ClusterChain` | V2 — sync `entry.startCluster` with the chain | `f58d556` |

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
