# Restic-based Backups — Design Plan

Status: **proposal, not yet started.** Covers requirements 1–8 below.

| # | Requirement | Where it's handled |
|---|---|---|
| 1 | Back up a phone folder to a USB restic repo | §5 Backup engine, §4.3 `SafSourceFs` |
| 2 | USB → USB across a hub | §5, §7.2 (bus contention), §9 (powered hub) |
| 3 | Expose backup contents over SAF | §6 `DocumentSource` refactor + `ResticDocumentSource` |
| 4 | Back up a USB into the phone | §4.2 `LocalStore` / `SafStore` |
| 5 | VeraCrypt volumes as source *or* destination | §4.3/§4.2 sit on `UsbFile` — no new plumbing, but **§7.3 is a blocker** |
| 6 | Incremental backups | Free — restic parent-snapshot logic, §5.3 caveat on FAT mtime |
| 7 | Pick a point in time to browse | §6.2 snapshot-tree layout + §6.3 in-app picker |
| 8 | Backup reminders | §8 |

---

## 1. The constraint that drives everything

The app has **no kernel mount**. USB storage is read and written entirely in userspace:
`LibaumsRawBlockDevice` → (`SlicedBlockDevice` → `NativeDecryptedBlockDevice`) → libaums/libexfat →
a `UsbFile` tree. Nothing in that chain has a POSIX path.

Restic — the real program — assumes POSIX paths on both ends: a source directory to walk and a
repository directory to write. So the obvious approach, "ship the `restic` binary and exec it", is
dead on arrival for every requirement except a phone-folder → phone-folder backup:

- USB source or destination: no path exists, and never will without root.
- Phone folder as source: under scoped storage there is no readable path either, unless we take
  `MANAGE_EXTERNAL_STORAGE` (Play-restricted, and unavailable on some OEM builds).

Therefore the restic **engine** must run inside our process with **both its storage backend and its
source filesystem injected**, delegating actual I/O back to the Kotlin layer we already have.

Restic is structured for exactly this. Two interfaces are the seams:

- `backend.Backend` — how the repository is read/written. Its `local` implementation is a thin
  wrapper over `os.*`; we swap it for one that calls us.
- `fs.FS` — how the source tree is walked. Same story.

## 2. Recommendation

**Vendor restic as a Go module fork, add a small `mobile` package to it, build it with
`gomobile bind` into an `.aar`, and drive it from Kotlin.**

This is the only option that keeps **on-disk format interop** with desktop restic, which is the
entire point of choosing restic — a repo written by the phone must open with `restic -r … snapshots`
on a laptop, and vice versa.

Licensing is fine: restic is BSD-2-Clause, compatible with the app's GPL-3.0-or-later.

### Alternatives considered and rejected

| Option | Verdict |
|---|---|
| Ship the `restic` CLI binary in `jniLibs`, exec it | No POSIX path for USB or scoped storage. Dead. |
| Reimplement the restic repo format in Kotlin (Rabin CDC, AES-256-CTR + Poly1305-AES, pack/index/tree formats, zstd) | Large, and every subtle mismatch silently produces a repo desktop restic rejects. The format is stable and documented, so it's *possible* — but it buys nothing over reusing the reference implementation. |
| `go build -buildmode=c-shared` + hand-written JNI glue | Viable fallback. More control than gomobile, and this repo is already comfortable with JNI (`VeraCryptNative.cpp`, `ExFatNative.cpp`). Costs hand-written thread-attach and marshalling. **Use this if gomobile's type restrictions bite** (§3.2). |

## 3. Go layer

### 3.1 Vendoring

Follow the existing `vendor/` convention (`vendor/exfat/`, `vendor/libaums/` — `UPSTREAM` + `patches/`):

```
vendor/restic/UPSTREAM            # pinned restic tag + commit sha
vendor/restic/patches/0001-mobile-bridge.patch
app/src/main/go/                  # the fork checkout + our mobile package
```

The `mobile` package **must live inside the restic module** — `archiver`, `repository`, `backend`,
`fs`, `walker` are all under `internal/`, which Go refuses to import from another module. Adding
`github.com/restic/restic/mobile` to the fork is the cheapest way in, and it keeps our bridge in a
single reviewable patch against a pinned upstream tag.

### 3.2 Bridge interfaces (implemented in Kotlin, called from Go)

`gomobile bind` supports a restricted type set: signed ints, floats, `bool`, `string`, `[]byte`,
`error`, and exported structs/interfaces from the bound package. No `[]string`, no maps, no
`io.Reader`. Everything below stays inside that set.

```go
package mobile

// Store is the repository. Paths are restic's own repo-relative layout paths,
// e.g. "config", "keys/ab12…", "data/ab/ab12…", "snapshots/…". Keeping layout
// computation on the Go side is what guarantees desktop interop; Kotlin is a
// dumb path→bytes store.
type Store interface {
    Save(path string, data []byte) error
    Load(path string, offset int64, length int64) ([]byte, error) // length<=0 => to EOF
    Size(path string) (int64, error)                              // ErrNotExist if absent
    List(dir string) (string, error)                              // "\n"-joined basenames
    Remove(path string) error
    Mkdirs(dir string) error
}

// SourceFS is the tree being backed up. Paths are "/"-rooted within the chosen root.
type SourceFS interface {
    Stat(path string) (*NodeInfo, error)
    ReadDir(path string) (string, error)     // "\n"-joined basenames
    Open(path string) (SourceFile, error)
}

type SourceFile interface {
    ReadAt(off int64, n int32) ([]byte, error)
    Close() error
}

type NodeInfo struct {
    IsDir      bool
    Size       int64
    ModTimeMs  int64
    Mode       int32   // synthesized for FAT/exFAT/SAF: 0755 dir, 0644 file
    InodeHint  int64   // FAT first-cluster if available, else 0 (see §5.3)
}

type Progress interface {
    OnProgress(filesDone int64, bytesDone int64, bytesTotal int64, currentPath string)
    IsCancelled() bool
}
```

Top-level API surface:

```go
func Open(store Store, password string, cacheDir string) (*Repo, error)
func Init(store Store, password string) error

func (r *Repo) Backup(src SourceFS, hostname string, tag string, p Progress) (string, error) // → snapshot id
func (r *Repo) SnapshotsJSON() (string, error)
func (r *Repo) ReadDirJSON(snapshotID string, path string) (string, error)
func (r *Repo) OpenFile(snapshotID string, path string) (*FileHandle, error)
func (r *Repo) Forget(keepLast int, keepDaily int, keepWeekly int, keepMonthly int, prune bool) error
func (r *Repo) Check(readData bool, p Progress) (string, error)
func (r *Repo) Close() error

func (h *FileHandle) Size() int64
func (h *FileHandle) ReadAt(off int64, n int32) ([]byte, error)
func (h *FileHandle) Close() error
```

### 3.3 Go-side adapters (the actual patch)

1. **`backend/callback`** — fork `backend/local` and replace its `os.*` calls with `Store` calls.
   It's a ~250-line file; the diff is mechanical and keeps restic's `layout` logic untouched.
   Set `Connections() = 1` (see §7.2).
2. **`fs.FS` impl** — a struct wrapping `SourceFS`, returning `fs.File` values whose `Read` is served
   by buffered `ReadAt` calls. Synthesize `syscall.Stat_t`-equivalent metadata from `NodeInfo`.
3. Wire `archiver.New(repo, fsImpl, opts)` with `opts.ReadConcurrency = 1`.
4. Point restic's `internal/cache` at `cacheDir` so index and snapshot files aren't re-fetched from
   the USB on every open.

### 3.4 Build integration

- `app/build.gradle.kts`: a `gomobileBind` task producing `app/libs/restic-mobile.aar`, wired as a
  dependency of `preBuild`. Gate it behind a property so contributors without a Go toolchain can
  still build if a prebuilt `.aar` is present.
- **ABIs**: restrict to `arm64-v8a` (+ `x86_64` for the QEMU E2E emulator). Dropping `armeabi-v7a`
  and `x86` is worth discussing — a gomobile `.so` with restic linked in is roughly 12–20 MB
  stripped *per ABI*, so this dominates APK size. Recommend enabling ABI splits / AAB delivery.
- **Reproducibility** (this repo already cares — see the `-ffile-prefix-map` block in
  `app/src/main/cpp/CMakeLists.txt`): pin the exact Go toolchain version, and build with
  `-trimpath -buildvcs=false -ldflags=-buildid=`. **F-Droid reproducible builds with a Go toolchain
  need verifying before committing to this** — see §9.
- CI: add Go setup to `.github/workflows/ci.yml`; add `go test ./mobile/...`.

## 4. Kotlin layer

New package `app.fayaz.otgmaster.backup`.

### 4.1 Store/SourceFs are the same three backings, used in both directions

That symmetry is what makes requirements 1/2/4/5 one feature instead of four:

| Backing | Store (destination) | SourceFs (source) |
|---|---|---|
| Mounted `UsbFile` tree — plain USB **or unlocked VeraCrypt volume** | `UsbFileStore` | `UsbFileSourceFs` |
| SAF tree (`ACTION_OPEN_DOCUMENT_TREE`) | `SafStore` | `SafSourceFs` |
| Shared phone storage via SAF (e.g. `Documents/OTGMasterBackups/`) | `SafStore` | — |
| App-private storage (`filesDir/repos/<id>`) | `LocalStore` | — |

**Default phone-side destinations to shared storage, not `filesDir`.** App-private storage is
invisible over MTP/USB file transfer, so a repo there cannot be opened by desktop restic without
first copying it off the phone — which defeats much of the point (§12). `LocalStore` stays available
for users who explicitly want the repo sandboxed, with that trade-off stated in the UI.

Requirement 5 needs **no new plumbing**: a VeraCrypt volume is already a `FileSystem` with a
`UsbFile` root in `MountedDrive`, so `UsbFileStore`/`UsbFileSourceFs` treat it identically to a plain
drive, and writes go down `NativeDecryptedBlockDevice.writeBlocks` (XTS encrypt), which already
exists and is exercised by `testdata/fat32_write` / `testdata/exfat_write`.

It does **not** mean requirement 5 is free. The existing path is correct but was built for
interactive file access, and backup traffic breaks two of its assumptions — see **§7.3**, which is a
hard prerequisite, not a nice-to-have.

### 4.2 `UsbFileStore` notes

- Repo root on a drive: `/OTGMasterBackups/<repo-name>/`.
- `List(dir)` must not be O(n) per lookup. libaums `listFiles()` returns the whole directory; cache
  per-directory listings with invalidation on `Save`/`Remove`.
- FAT32's 4 GiB file limit is not a problem (packs are ~16 MiB), and restic's `data/<2 hex>/`
  sharding keeps directory sizes sane.

### 4.3 `SafStore` / `SafSourceFs` notes

**Do not use `DocumentFile`.** `DocumentFile.findFile()` is a linear scan issuing a
ContentResolver query per call and will make a restic repo unusably slow. Query
`DocumentsContract.buildChildDocumentsUriUsingTree()` directly, one cursor per directory,
and keep a path→documentId map.

For requirement 1, SAF tree picking is the default (no special permission, works on both F-Droid
and Play). `MANAGE_EXTERNAL_STORAGE` is optional for power users — backup apps are an accepted use
case in Play's policy, but it needs a declaration and review, so it should not be on the critical path.

### 4.4 Repo registry and passwords

- `BackupRepoStore`: persisted list of known repos — id, display name, backing kind + locator
  (drive `stableKey` + path, or SAF tree URI), last backup time, last snapshot id.
- Repo passwords go in the existing `CredentialStore` (EncryptedSharedPreferences), alongside
  VeraCrypt credentials. Same biometric gate.
- Note the **layering**: a restic repo *on* a VeraCrypt volume is encrypted twice. That's fine and
  arguably the point, but the UI should say so rather than let users think one password covers both.

## 5. Backup engine

### 5.1 Service

A foreground `BackupService`.

**Use `foregroundServiceType="connectedDevice"`** (permission `FOREGROUND_SERVICE_CONNECTED_DEVICE`)
for any backup touching USB. This matters: on Android 15+, `dataSync` foreground services are capped
at **6 hours per 24-hour period**, and a first full backup of a large USB drive over userspace mass
storage will exceed that. `connectedDevice` is both the honest classification (the work *is* I/O to
an attached USB device) and not subject to that cap. Phone-folder → phone-repo backups can use
`dataSync`; they're fast.

Also: hold a partial wake lock, and keep `MainActivity`'s USB detach handling authoritative — an
unplug mid-backup must abort cleanly, not corrupt the destination.

### 5.2 Flow

1. Resolve source + destination to `SourceFS` / `Store`.
2. `Init` if the repo doesn't exist; otherwise `Open` (scrypt KDF, ~1s — do it off the main thread).
3. `Backup(...)` with a `Progress` bridging to the notification and an in-app progress UI.
4. On success, update `BackupRepoStore`, notify `DocumentsContract.buildRootsUri(...)` so any
   mounted restic root refreshes.

Cancellation is `Progress.IsCancelled()`; restic leaves already-written packs in place, so a
cancelled or interrupted run resumes cheaply next time rather than restarting from zero.

### 5.3 Incremental (requirement 6) — one real caveat

Restic's incremental logic compares each file against the parent snapshot on
`name + size + mtime + inode`. Two things to get right:

- **No inodes on FAT/exFAT/SAF.** Report `InodeHint = 0` consistently so the inode comparison is
  always "equal" and the decision falls back to size + mtime. Reporting a *varying* fake inode would
  silently defeat incrementality and re-read the whole drive every run.
- **FAT32 mtime has 2-second granularity.** Acceptable, but means a file modified twice within the
  same 2-second window at an identical size can be missed. Offer a "verify contents" mode that
  disables the metadata shortcut (restic's `--force`) for users who want certainty.

Also: FAT/exFAT/SAF have no uid/gid/mode. Synthesize `0644`/`0755`, uid/gid `0`. Restored files on a
desktop then land with those; document it.

## 6. Browsing backups (requirements 3 and 7)

### 6.1 Refactor: generalize the DocumentsProvider

`VeraCryptDocumentProvider` currently reads `OtgMasterState.mountedDrives` and does
`drive.fileSystem.rootDirectory` walks inline. Introduce a source abstraction so restic snapshots
and mounted volumes share one provider and one authority (preserving persisted URI grants):

```kotlin
interface DocumentSource {
    val id: String; val title: String; val summary: String
    val isWritable: Boolean
    fun stat(path: String): DocEntry?
    fun list(path: String): List<DocEntry>
    fun openRead(path: String): SeekableReader
    // create/delete/rename/openWrite only when isWritable
}
```

- `UsbFileDocumentSource` — today's behaviour, extracted verbatim from the provider.
- `ResticDocumentSource` — backed by a Go `Repo` handle. **Read-only**: no
  `FLAG_SUPPORTS_CREATE`/`WRITE`/`DELETE`/`RENAME` on these roots.

`OtgMasterState.mountedDrives` becomes a list of `DocumentSource`. Doc IDs keep the existing
`sourceId:path` shape, so nothing else changes. Rename the class to `OtgMasterDocumentProvider`
(the manifest `android:authorities` stays identical, so grants survive).

`openDocument` for a restic path uses the same `openProxyFileDescriptor` pattern already in the
provider, with `onRead(offset, size)` served by `FileHandle.ReadAt`.

### 6.2 Snapshot tree = the time-travel UI (requirement 7)

One SAF root per *opened repo*, whose first level is the timeline:

```
Backups — Kingston (repo)
├── latest/                                   → alias for the newest snapshot
├── 2026-09-20 14-03 · a1b2c3d4/
│   └── …the backed-up tree…
├── 2026-09-19 22-10 · 9f8e7d6c/
└── 2026-09-14 09-11 · 4d5e6f70/
```

"Select a timeframe to go back to" then becomes plain navigation, works in the Files app and any
other SAF client, and needs no custom UI to function.

### 6.3 In-app picker

For a nicer version of requirement 7, add an in-app repo screen: snapshot list with date, size, file
count and tag, a date-range filter, and a **"Browse this restore point"** action that pins that one
snapshot as its own SAF root and opens the Files app straight at it (reusing the existing
`openFilesApp` BROWSE-intent path).

### 6.4 Caching

Random-access reads of a snapshot file mean fetching pack subsets from the destination. Without a
cache, scrubbing a video off a USB-hosted repo is brutal. Layer it:

- restic's own `internal/cache` in `cacheDir` for index/snapshot/config (§3.3).
- An LRU data-blob cache (in-memory, ~64 MiB, plus a disk tier in `cacheDir`).

### 6.5 A repo on USB is a mount stacked on a mount

This is the primary case, not an edge case: requirements 1, 2 and 5 all put the repo on a drive, and
requirement 3 says browse it via SAF. Nothing above changes — `ResticDocumentSource` wraps a Go
`Repo` handle and is indifferent to what backs it — but the **lifecycle coupling** needs designing.

The read path for one byte the Files app requests:

```
Files app → OtgMasterDocumentProvider → ProxyFileDescriptor.onRead(off, size)
  → FileHandle.ReadAt → repo blob → pack subset → Store.Load(path, off, len)
  → UsbFile.read → [NativeDecryptedBlockDevice XTS decrypt] → libaums → SCSI over USB
```

**Teardown must cascade.** A `ResticDocumentSource` built on `UsbFileStore` holds a live reference
to a `MountedDrive`. If the drive is unplugged or unmounted while the repo root is still registered,
the provider will serve reads against a dead USB connection — the same use-after-free class the
existing `drainCallbacks()` guard in `unmountDrive` was written for.

- Register restic sources as **dependents** of the `MountedDrive` they read through.
- `unmountDrive` (and the detach handler) closes dependents first → `drainCallbacks()` →
  then unmounts the filesystem and releases the USB connection.
- Locking a VeraCrypt volume must close any repo hosted on it and zero the repo key with it.

**Two roots per drive.** The drive's own volume root and the repo's snapshot root both appear in the
Files app. That's desirable — you can see the raw `OTGMasterBackups/<name>/` directory *and* the
browsable timeline — but title them distinctly, or users will not understand why one shows
`data/ab/3f9c…` opaque pack files and the other shows their photos.

**Restoring works while read-only.** The restic roots grant no write flags, but copying *out* of
them (Files app reads from us, writes elsewhere) is exactly how file-level restore should work, and
needs nothing extra.

**Open-on-attach.** Natural extension: when a drive is attached — and unlocked, for VeraCrypt — and
contains a known repo, offer to register its root immediately. Pairs with §8.

**Performance is worst here**, so §6.4's caching is load-bearing rather than optional. Note that
restic's own cache is keyed by repo ID and persists in `cacheDir`, so the index fetch is a one-time
cost per repo, not per attach — provided we don't wipe the cache on unmount.

**Concurrency**: browsing a repo on a drive that a backup is simultaneously writing to is a normal
thing for a user to do. The per-device lock in §7.2 is what makes it safe.

## 7. Prerequisites and risks

> **See [IO_PERFORMANCE.md](IO_PERFORMANCE.md)** for the fix options for §7.2–§7.4 and §7.8,
> which are current defects in the shipping app and are being addressed as their own workstream
> ahead of this plan.

Most of what follows is **not new risk introduced by backups** — it is pre-existing behaviour in
the shipping app that interactive use has been hiding. Backup traffic (sustained, long-running,
concurrent, whole-drive) is simply the first workload that makes it visible.

| § | Already affects the shipping app? | How it shows up today |
|---|---|---|
| 7.1 Write-path coverage | **Yes** | Every write to a mounted volume already takes this path |
| 7.2 Unsynchronized block I/O | **Yes, FAT32 only** | Live race; exFAT is incidentally protected (below) |
| 7.3 Per-sector XTS | **Yes** | Slow large-file copies, GC churn on any encrypted volume |
| 7.4 FAT32 directory rewrite | Minor | Only for directories with thousands of entries |
| 7.5 Tail zero-fill | Latent | Needs out-of-order writes to bite |
| 7.8 Large-drive metadata costs | **Yes** | Sluggish browsing, repeated bitmap scans |

That reframes Phase A: it is not a tax paid for backups, it is overdue maintenance that the backup
work forces into view. Each item is justified on the current app's own merits and is verifiable
against the existing E2E suite with no restic in the picture.

### 7.1 Write-path exposure (must address before shipping)

Every destination in this feature is a **write** path. Writes already exist end-to-end
(`NativeDecryptedBlockDevice.writeBlocks`, libaums FAT32, `ExFatNative.writeFile`, and the
provider's `createDocument`/`deleteDocument`), and `testdata/fat32_write` and `testdata/exfat_write`
cover some of it — but backup traffic is a far heavier, longer-running, more concurrent write load
than anything exercised so far. Writing to a **VeraCrypt volume** is the highest-consequence case:
a corrupted FAT on an encrypted volume is not recoverable by ordinary tools.

Actions: expand write E2E coverage substantially (§8) before enabling VeraCrypt destinations;
flush/sync aggressively at pack boundaries; show a prominent "do not unplug" state during backup;
handle detach-mid-write as a first-class abort path.

### 7.2 Concurrency — a real bug waiting to happen

`LibaumsRawBlockDevice.readBlocks`/`writeBlocks` are **not synchronized**, and SCSI
CBW/CSW exchanges must not interleave on one device. Today this is mostly latent because only one
thing touches a drive at a time. With a backup running, the Files app can read the same drive
concurrently through the DocumentsProvider.

**The exposure is asymmetric, and this is already true today.** The exFAT path wraps every
operation in `synchronized(fileSystem)` (11 sites in `ExFatFile.kt`), so exFAT volumes are
incidentally serialized. libaums' FAT32 implementation contains **no locking of any kind** — no
`synchronized`, no locks, not even `@Volatile`. So a **FAT32 volume is racy in the shipping app
right now**: `DocumentsProvider` methods run on binder threads (a pool), so two apps listing
directories, or one listing while `MainActivity` probes on `Dispatchers.IO`, can interleave SCSI
CBW/CSW exchanges on the same device. Only the `ProxyFileDescriptor` read/write callbacks are
serialized, and only against each other, by the single `proxyHandler` `HandlerThread`.

It is rare today because operations are short. On a large drive they are not short, which widens
the window considerably (§7.8).

**Add a per-`RawBlockDevice` lock** wrapping all block I/O, before anything else in this plan.

Related: restic's archiver defaults to many goroutines. Force backend connections and read
concurrency to 1 for USB-backed ends (§3.3) — parallelism over a single userspace mass-storage bus
costs throughput rather than gaining it.

For requirement 2 (USB → USB), the two drives are separate `UsbDeviceConnection`s so they don't
serialize against each other, but they **share the bus**; expect roughly half the single-drive
throughput.

### 7.3 The VeraCrypt I/O path is O(sector) in JNI calls and key schedules

**The one genuine blocker for requirement 5.**

#### What the code does today

`NativeDecryptedBlockDevice.readBlocks` / `writeBlocks` loop over the buffer **one 512-byte sector
at a time**:

```kotlin
for (i in 0 until sectorCount) {
    val sectorEncrypted = encryptedData.copyOfRange(i * 512, (i + 1) * 512)   // alloc
    val tweak = tweakDataOffsetSectors + startBlockSectors + i
    val sectorDecrypted = VeraCryptNative.decryptSector(                      // JNI crossing
        cipherNativeId, masterKey, tweak, sectorEncrypted)
    System.arraycopy(sectorDecrypted, 0, decryptedData, i * 512, 512)
}
```

and each JNI call bottoms out in `xtsCrypt` (`VeraCryptNative.cpp:33`), which builds and tears down
the cipher context **inside the call**:

```c
mbedtls_aes_xts_context xts_ctx;
mbedtls_aes_xts_init(&xts_ctx);
ret = mbedtls_aes_xts_setkey_enc(&xts_ctx, key64, 512);   // <- key schedule, every 512 bytes
ret = mbedtls_aes_crypt_xts(&xts_ctx, direction, length, dataUnit, input, output);
mbedtls_aes_xts_free(&xts_ctx);
```

#### Why the key schedule matters

AES does not encrypt with the raw key. It first *expands* it into per-round subkeys — for AES-256,
15 round keys, 240 bytes — and that expansion is meant to happen **once** and be reused across
gigabytes. XTS uses two keys (one for data, one for the tweak), so
`mbedtls_aes_xts_setkey_enc(ctx, key64, 512)` performs **two** AES-256 expansions.

Doing that per 512 bytes means the setup work is on the same order as the encryption it enables.
Serpent is materially worse: `serpent_set_key_256` runs twice per sector, and Serpent's schedule
(33 × 128-bit round keys via an affine recurrence plus S-box application) is considerably more
expensive than AES's.

#### Exact per-sector cost

Counted from the code, for every 512 bytes read or written:

| | Count |
|---|---|
| JNI round trips | 1 |
| AES-256 key expansions (or Serpent-256 schedules) | 2 |
| `GetByteArrayElements`/`Release` pairs (key + payload) | 2 |
| Java-heap allocations (Kotlin `copyOfRange` + JNI `NewByteArray`) | 2 × 512 B |

Scaled up:

| Workload | JNI calls | Key expansions | Allocation churn |
|---|---|---|---|
| One 16 MiB pack | 32,768 | 65,536 | ~32 MB |
| 100 GB first backup | ~210 M | ~420 M | ~200 GB |

Note `masterKey` is also re-marshalled across JNI on **every sector** — 64 bytes × 32,768 per pack.

This applies to **reads as well as writes**, so it is paid twice when both ends are VeraCrypt
volumes (requirement 2 with encrypted source and destination), and again on every SAF read of a
repo hosted on an encrypted volume (§6.5). It never mattered when the app only opened files
interactively; it is disqualifying for sustained backup throughput.

The counts above are exact. The resulting wall-clock throughput is **not** predicted here — measure
before and after on a real device (§11).

#### Fix, in two levels

**Level 1 — bulk range call.** Collapses the per-sector JNI and allocation costs and reduces the
schedule to one per call:

```c
JNIEXPORT jint JNICALL Java_..._xtsCryptRange(
    JNIEnv* env, jclass, jint cipher, jbyteArray jKey,
    jlong startSector, jint direction,
    jbyteArray jBuf, jint offset, jint length);
    // one key schedule; GetPrimitiveArrayCritical(jBuf) and transform IN PLACE;
    // loop sectors incrementing the data unit; no per-sector allocation.
```

Kotlin collapses to a single call that decrypts in place:

```kotlin
val data = encryptedDevice.readBlocks(startBlock + volumeDataOffset, blockCount)
val rc = VeraCryptNative.xtsCryptRange(
    cipherNativeId, masterKey, firstTweak, DECRYPT, data, 0, data.size)
if (rc != 0) throw IllegalStateException("XTS decrypt failed at sector $firstTweak")
return data
```

For a 16 MiB span that is 1 JNI call, 2 key expansions and **zero** extra allocations, down from
32,768 / 65,536 / 65,536.

`GetPrimitiveArrayCritical` pauses GC for its duration, so the loop must stay pure computation (no
JNI callbacks, no blocking) and long spans should be chunked — 1 MiB is a reasonable bound.

**Level 2 — persist the schedule across calls.** A native handle owned by the mount:

```c
jlong nativeOpenCipher(jint cipher, jbyteArray key);   // expands enc + dec schedules once
void  nativeXtsCryptRange(jlong h, jlong startSector, jint dir, jbyteArray buf, jint off, jint len);
void  nativeCloseCipher(jlong h);                      // zeroes and frees
```

`NativeDecryptedBlockDevice` holds the handle for the lifetime of the mount, so key setup happens
**once per unlock**.

This also **improves key hygiene**. Today the 64-byte master key sits in a long-lived Java
`ByteArray` and `close()` does `masterKey.fill(0)` — but a compacting GC may have relocated that
array, leaving copies `fill(0)` never reaches. Holding the expanded schedule in native memory that
`nativeCloseCipher` explicitly zeroes is strictly better than the status quo.

Keep the existing per-sector `decryptSector`/`encryptSector` for the header path, which is called
once per unlock attempt and is not hot.

#### Why this must land in Phase A

1. **Requirement 5 is unshippable without it** — every VeraCrypt source and destination, read and
   write, goes through this loop.
2. **It is verifiable in isolation.** A behaviour-preserving native change, provable byte-for-byte
   against the current implementation (see below) before any backup code exists to confuse the
   diagnosis. Retrofitting it later means debugging crypto and backup logic simultaneously.
3. **It consolidates the tweak arithmetic.** The long comment on `tweakDataOffset` in
   `NativeDecryptedBlockDevice` records a correctness bug that already shipped in exactly this
   calculation. Moving the sector loop into one native function keeps that computation in a single
   place instead of duplicating it into a second call site.

#### Proving it is equivalent

The change must be byte-identical to the current path. Cheap to establish:

- a test that runs the old per-sector loop and the new bulk call over the same random buffer and
  asserts equality — across AES and Serpent, both directions, several start sectors, and lengths
  that are 1, 2, 8 and 4096 sectors;
- boundary behaviour: non-512-multiple lengths rejected; the data unit continues to increment
  correctly across call boundaries (the case the shipped tweak bug turned on);
- the existing E2E suite (`fat32`, `exfat`, `serpent`, `partitioned_mbr`, `*_write`) passing
  unchanged, since it already covers nonzero volume start offsets.

### 7.4 FAT32 rewrites the whole directory on every file close

`FatFile.flush()` calls `parent.write()`, and `FatDirectory.write()`
(`FatDirectory.kt:268`) serializes **every entry in the directory** and writes it from offset 0.
`close()` calls `flush()`. Restic's 64-hex-character filenames need ~6 directory entries each
(5 LFN + 1 short) = ~192 bytes, so a directory holding *n* packs rewrites ~192·*n* bytes on every
single file creation — O(n²) write volume, and on a VeraCrypt volume every one of those bytes goes
through §7.3.

Writing *n* files into one directory therefore rewrites roughly `96n²` bytes in total. Restic's
default layout is what saves us — `data/` is sharded into 256 `<2 hex>` subdirectories, so *n* per
directory stays tiny:

| Layout | Packs per dir (100 GB backup) | Cumulative directory rewrite |
|---|---|---|
| Restic's sharded `data/ab/…` (default) | ~25 | **~15 MB** — noise against 100 GB |
| Hypothetical flat `data/…` | 6,400 | **~3.9 GB**, plus ~8M extra sector encryptions via §7.3 |

So on the default layout this costs nothing measurable. **The point of this section is that it stays
that way only if the layout is left alone** — do not flatten or customize it in the
`backend/callback` fork (§3.3).

One place it does grow without bound: **`snapshots/` is not sharded**, and holds one file per
snapshot forever. Daily backups for five years is 1,825 entries — a ~350 KB directory rewritten on
every backup, ~320 MB cumulative. `index/` has the same shape but stays in the low hundreds. This is
a concrete argument for shipping `forget` rather than deferring it (§9, open question 5).

**Prefer exFAT for destinations.** libexfat maintains its own node cache and does not rewrite the
parent directory per close, so it degrades far better under many-file workloads. Surface this as a
recommendation when a user picks a FAT32 destination.

### 7.5 `ByteBlockDevice.write` zero-fills unaligned tails

`ByteBlockDevice.write` read-modify-writes a *leading* partial block correctly, but for a *trailing*
partial block it allocates a rounded-up buffer and leaves the remainder **zero** before writing the
full sector — there is an upstream `TODO` acknowledging exactly this. Anything already on disk in
the tail of that last sector is destroyed.

Today this is benign: the tail always falls inside the file's own cluster (its slack space), or
inside a directory that `FatDirectory.write` has already zero-padded to a cluster boundary itself.
It stays benign only under one condition:

> **`Store.Save` must write each repo file sequentially from offset 0, in ascending contiguous
> chunks.** Out-of-order or sparse writes would leave zeroed gaps where a later leading-partial
> read-modify-write cannot recover them.

That is trivial to satisfy — restic hands us whole objects — but it should be an explicit assertion
in `UsbFileStore`, not an accident.

### 7.6 `MainActivity.kt` is 2005 lines

All mount/unmount/device state lives there. This feature roughly doubles the surface. Extract mount
state and the device lifecycle into a `MountManager` (or equivalent) *before* adding backups, or the
result will be unmaintainable.

### 7.7 APK size and build complexity

See §3.4. Adding a Go toolchain to a project that currently builds with Gradle + CMake only is a
genuine step up in build complexity, and the F-Droid reproducibility question (§9) is open.

### 7.8 Large, densely-packed drives stress the metadata path — today, without backups

Large capacity and large files exercise different weaknesses, and **both are current defects**,
independent of this feature. Large *files* hammer §7.3. Large, densely-packed *drives* hammer paths
that are not in the crypto loop at all:

#### `freeSpace` rescans the exFAT allocation bitmap on every `queryRoots`

`VeraCryptDocumentProvider.queryRoots` adds `COLUMN_AVAILABLE_BYTES` from
`drive.fileSystem.freeSpace` for every mounted drive, and Android queries roots often.

- **FAT32** is fine: `Fat32FileSystem.freeSpace` reads a cached `fsInfoStructure.freeClusterCount`.
- **exFAT is not.** `ExFatNative.getFreeSpace` calls `exfat_count_free_clusters(ef)`, a **full scan
  of the allocation bitmap**, every single call.

On a 2 TB exFAT volume with 128 KB clusters that is a ~2 MB bitmap read per `queryRoots` — and on a
VeraCrypt volume every byte of it goes through §7.3's per-sector path (~4,096 sectors, ~8,192 key
schedule rebuilds). Repeatedly, for a number the UI shows as a rounded size.

**Fix:** cache free-space per mount, invalidate on write. Cheap, and worth doing in Phase A.

#### The provider re-walks (and re-lists) the whole path on every operation

`getFileForDocId` starts at `drive.fileSystem.rootDirectory` and, for each path component, calls
`listFiles()` and linearly scans it:

```kotlin
var currentFile = drive.fileSystem.rootDirectory
for (part in parts) {
    for (child in currentFile.listFiles()) { if (child.name == part) { … } }
}
```

`listFiles()` materializes a fresh `UsbFile` object per directory entry, and the objects it returns
are new each call, so any parsing they cache dies immediately. Every `queryDocument`,
`queryChildDocuments` and `openDocument` pays this from the root.

Browsing a directory of 10,000 files therefore costs a full 10,000-entry listing **per file
opened** — so generating thumbnails while scrolling is O(n²) in directory size. On an encrypted
volume each of those listings is decrypted through §7.3.

**Fix:** a path → `UsbFile` cache on the `DocumentSource` (§6.1), invalidated on write and on
unmount. This lands naturally as part of the Phase A provider refactor.

#### `queryChildDocuments` materializes the entire directory in a `MatrixCursor`

A directory with tens of thousands of entries builds one in-memory cursor with a row each before
returning. Worth bounding, and worth checking against `CursorWindow` limits on a genuinely packed
drive.

#### Reads allocate twice the span

`LibaumsRawBlockDevice.readBlocks` allocates `blockCount * blockSize`, then
`NativeDecryptedBlockDevice.readBlocks` allocates a second full-size array for the plaintext, on top
of the two 512-byte allocations *per sector* from §7.3. Peak footprint is 2× the requested span plus
the churn. The §7.3 Level 1 fix (decrypt in place) removes the second copy and all the per-sector
garbage at once.

#### None of this needs backups to hurt

A user with a 2 TB VeraCrypt drive full of media is hitting all four paths in the shipping app. If
there are existing reports of sluggish browsing or slow large-file copies on big encrypted drives,
this section is the likely explanation — and §7.3 plus free-space caching are the two highest-value
fixes, both already scheduled for Phase A.

**Measure first.** The structural costs above are read off the code and are certain; their relative
wall-clock weight is not. Profile a large packed drive before and after Phase A (§11).

## 8. Reminders (requirement 8)

Two complementary triggers — neither should silently start a backup, since the target drive usually
isn't plugged in:

1. **Scheduled reminder.** `WorkManager` periodic work per repo (daily / weekly / monthly / custom),
   posting a notification: *"It's been 12 days since you backed up Photos. Plug in the Kingston
   drive."* Tapping opens the repo screen with the backup pre-staged. Needs `POST_NOTIFICATIONS`
   (Android 13+). No exact alarms — periodic work is the right fit and survives Doze better.
2. **Attach-triggered reminder.** `MainActivity` already handles `USB_DEVICE_ATTACHED` and has
   `UsbDeviceDescriber.stableKey`. When a drive that is a known backup destination is attached and
   its repo is overdue, prompt right then. This is the one that will actually get used.

Optionally, **auto-start on attach** as an opt-in per repo, reusing the existing auto-mount
(and quick-unlock) machinery for VeraCrypt destinations.

Store schedule + last-run in `BackupRepoStore`; reuse the existing notification channel setup.

## 9. Open questions — worth settling before coding

1. **F-Droid + Go reproducibility.** Does F-Droid's build server handle a gomobile `.aar` step
   reproducibly? Given the effort already spent on deterministic native output, this should be
   validated early — it could force the `c-shared` fallback, or a prebuilt-`.aar` arrangement.
2. **ABI set.** Drop `armeabi-v7a` and `x86`? Materially changes APK size and device reach.
3. **Repo version / compression.** Repo v2 with zstd is restic's default and saves USB space and
   write volume, at CPU cost. Recommend v2 with `auto` compression, but measure on a mid-range phone.
4. **Powered hub.** Requirement 2 needs one in practice — a passive hub often can't supply two
   drives, and some phones can't do host mode and charge simultaneously. This is a documentation
   and error-message problem, not a code one, but it will generate support requests.
5. **Scope of restic features.** `forget`/`prune`/`check` are sketched in §3.2 but not required by
   1–8. Without `forget`, repos grow forever — recommend shipping at least `forget` in Phase F.
6. **Restore.** Requirements only ask to *browse* backups (§6), which SAF makes sufficient for
   file-level recovery. A bulk "restore whole snapshot to X" action is a natural follow-up.

## 10. Phasing

| Phase | Content | Requirements |
|---|---|---|
| **A — Prereqs** | **Bulk XTS native entry point (§7.3)**; per-device I/O lock (§7.2); extract `MountManager` from `MainActivity` (§7.6); free-space + path caching (§7.8); `DocumentSource` refactor of the provider with `UsbFileDocumentSource` behaviourally identical (§6.1); expand write E2E (§7.1) | — |
| **B — Toolchain** | Vendor restic fork, `mobile` package, `backend/callback` + `fs.FS` adapters, gomobile Gradle task, CI, reproducibility check | — |
| **C — Vertical slice** | `SafSourceFs` + `UsbFileStore`; init/backup; `BackupService`; in-app snapshot list. Phone folder → plain USB | **1**, **6** |
| **D — Browse** | `ResticDocumentSource`, snapshot-tree root layout, blob cache, in-app restore-point picker | **3**, **7** |
| **E — All directions** | `UsbFileSourceFs`, `LocalStore`/`SafStore` destinations, two-device orchestration, VeraCrypt on either end | **2**, **4**, **5** |
| **F — Lifecycle** | Reminders, auto-backup-on-attach, `forget`/`prune`, `check` | **8** |

## 11. Testing

Extend the existing QEMU harness (`scripts/run_e2e_tests.sh`). Today it exposes **one**
`usb-storage` device (`usbdev0`) and swaps the backing image per test — requirement 2 needs a second
`-device usb-storage,...,id=usbdev1` with its own blockdev, plus a `testdata/` generator producing
source and destination images.

The decisive test is **host-side interop**: after the in-app backup completes, pull the destination
image and run *real* restic against it on the CI host —

```
restic -r <loopback-mounted repo> snapshots
restic -r … check --read-data
restic -r … restore latest --target /tmp/out && diff -r /tmp/out <source fixture>
```

If that passes, the format is right; if it doesn't, nothing else matters. Add cases for:

- incremental: back up twice with no changes → second snapshot adds ~no packs; touch one file →
  only that file's blobs are added;
- a snapshot browsed through the DocumentsProvider byte-for-byte matches the source;
- backup interrupted mid-run (simulate detach) → repo still opens, next run resumes;
- VeraCrypt volume as destination, then verified from the host after unlocking with the
  `veracrypt` CLI;
- FAT32 *and* exFAT on both ends.

Unit-testable without a device: `UsbFileStore`/`SafStore` against `FileBlockDevice`-backed images
(the existing JVM test pattern), and the Go `mobile` package against an in-memory `Store`.

---

## 12. Desktop interop — `restic mount` from a laptop

**Yes, and it's the load-bearing reason for §2.** The bridge replaces only two seams —
`backend.Backend` (path → bytes) and `fs.FS` (source walk). Everything that determines the bytes on
disk — `repository`, `archiver`, `pack`, `index`, `crypto`, the chunker, the `layout` code that
decides `data/<2 hex>/<id>` — is upstream restic, running unmodified. There is no second
implementation of the format to drift out of sync.

Requires desktop **restic ≥ 0.14** (repo v2), the repo password, and FUSE. Pin the vendored restic
tag and document the minimum in the UI.

### Reachability per destination

`restic mount` needs the repo as a path on the laptop. That, not the format, is the only thing that
varies:

| Repo lives on | Desktop access |
|---|---|
| Plain USB (FAT32/exFAT) | Plug the drive in: `restic -r /media/usb/OTGMasterBackups/<name> mount /mnt/x`. Works as-is. |
| Inside a VeraCrypt volume | Unlock first with VeraCrypt or `cryptsetup --type tcrypt`, then point restic at the mounted path. |
| Shared phone storage via SAF | Reachable over MTP/USB file transfer. MTP is slow and flaky for random access — copy the repo dir off, or expose it from a USB drive instead. |
| App-private `filesDir` | **Not reachable.** Invisible to MTP. This is why §4.1 defaults phone-side repos to shared storage. |

No `mount` on Windows (restic has no FUSE there) — use `restic restore` / `restic ls` / `restic dump`.

### Caveats that show up in the mount but don't break it

- **Ownership and modes are synthesized** (§5.3): FAT/exFAT/SAF carry no uid/gid/mode, so everything
  mounts as uid/gid 0 with `0644`/`0755`. Restores land with those.
- **mtimes are FAT-granular** (2 seconds).
- **No symlinks or hardlinks** from FAT/exFAT sources — nothing to preserve, nothing lost.
- **Stale locks**: an app crash or mid-backup unplug can leave a lock file; `restic unlock` clears it.

### It works in both directions

Desktop restic can also **write** to a repo the phone created — back up the laptop to the same USB
drive, and those snapshots appear in the app's timeline (§6.2) alongside the phone's. Nothing in the
design is phone-specific; the repo is just a restic repo.

### This is verified, not assumed

§11's host-side interop test is exactly this check, run in CI on every change: pull the destination
image, then `restic snapshots` / `check --read-data` / `restore` against it with the real binary. If
the vendored fork ever diverges, that test fails before the change lands.
