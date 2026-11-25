# Engine split: modules and contracts as built

The architecture proposal ("Container Master and OTG Master — Final Architecture
Proposal") moves OTG Master's engine into modules a second app can share. This file
records what has been built, milestone by milestone, and the contracts between the
modules. `docs/ENGINE_BASELINE.md` is the reference behaviour each step is checked
against; `docs/BENCHMARK_RUNS.md` holds the hardware runs.

## Modules

| Module | Kind | Holds | Depends on |
|---|---|---|---|
| `:mount-core` | Kotlin/JVM | `core/DocumentSource` (the tree the provider serves), `core/EngineLog` (where engine code logs), `block/RawBlockDevice`, `SlicedBlockDevice`, `FileBlockDevice` | nothing |
| `:disk-engine` | Android library | partitions (MBR, GPT), the block cache and read-only guards, VeraCrypt, LUKS and BitLocker, the filesystem drivers (FAT32 through libaums, exFAT, ext, NTFS, APFS), the formatters, `disk/UsbFileDocumentSource`, and `libveracrypt-native.so` with all vendored C | `:mount-core`, `:libaums` (both as API) |
| `:libaums` | Android library, vendored | USB mass storage, FAT32 (`docs/VENDOR_FIXES.md`) | |
| `:app` | OTG Master | USB attach and permission, the UI, backups (restic), recovery, the DocumentsProvider, the harness | all of the above |

Packages kept their names through every move (proposal F1). Native code finds its
callbacks and the classes it constructs by binary name, so a move between Gradle
modules is invisible to it; the keep rules travel with the code they protect
(`mount-core.pro` in the jar, `disk-engine/consumer-rules.pro`).

`:disk-engine` declares the app's four build types by name, so each app variant gets
the native build it had (F2): debug and benchmark compile the libexfat I/O counters and
`ExFatIoStats`, release and minified carry neither.

Engine code logs through `EngineLog`; each app installs a sink. OTG Master's is
`OtgMasterState`, which keeps its wording and its limit on repeated failures. Lines
logged before the sink exists are queued (200) and delivered when it arrives.

## The provider split (next)

The provider is the last large piece in `:app` that Container Master needs. It mixes
three things:

- **SAF serving that any mounted tree needs.** Cursors, projections and flags, the ID
  codec (`DocumentIds`), checked names, ancestry, bounded queries and the last-known
  rows (`BoundedCalls`), root free space read off the binder thread (`RootFreeSpace`),
  proxy descriptors with one thread per drive, errno translation (`ProxyErrors`),
  short reads reported honestly.
- **Session safety that any mounted tree needs.** One lock per drive around every
  filesystem call (libaums has none); open handles tied to the filesystem they were
  opened on, failing with EIO once it is unmounted, formatted or encrypted (review
  finding 7), and not closed onto a filesystem that replaced it; writers registered
  under the lock so a delete sees every open (finding 8); waiting for writers before a
  delete; draining queued callbacks before an unmount; whether a drive is in use, which
  Eject and idle unmount ask.
- **OTG Master policy.** Read grants extended to "Open with" viewers and revoked on
  unmount, the restic backup filesystem served from its own thread (its reads go
  through this provider to another drive), the no-space toast, the launcher icon and
  the strings, the authority.

The first two move to `:saf-provider`; the third stays in the app. Three steps, each
behaviour-preserving, each with its own E2E run and hardware check:

1. **An open-file handle on `DocumentSource`.** `open(node, write, truncate)` returns a
   handle with `size()`, `read(offset, buffer, length)` returning the bytes actually
   read (0 at end of file), `write`, `flush` and `close`. `UsbFileDocumentSource`
   implements it over `UsbFile` with today's `readInto` semantics. The provider's
   callbacks use the handle, and its rows use `DocumentNode`, so nothing in the provider
   touches `UsbFile` any more.
2. **A registry of mounted trees.** The provider asks a registry instead of
   `OtgMasterState`: the mounted trees in order, one by public ID, and for each its
   name, read-only reason, `DocumentSource`, free space, and a session identity that
   changes with every mount. The identity is what `OpenFile.isLive`, the row cache key
   and `retire` compare today through `System.identityHashCode(drive.fileSystem)` and
   `===`. OTG Master's registry is a view of `OtgMasterState.mountedDrives`.
3. **The move.** An abstract provider in `:saf-provider` holds the SAF serving and the
   session safety and asks its subclass for the registry, the authority, the icon, the
   strings and the policy hooks (a read was served: extend the grant; which thread
   serves a tree's descriptors). `app.fayaz.otgmaster.provider.VeraCryptDocumentProvider`
   stays the manifest entry point (the upgrade contract) as that subclass. Its static
   helpers that the app calls today (`onDrive`, `drainCallbacks`, `driveInUse`,
   `retire`, the grant revocation) stay reachable from the same class.

What each step must show, beyond the full E2E run: the held grant on `BENCH/edge` still
reads the same bytes and is still confined, on the R8 build; the provider E2E cases
(`provider_*`, `eject_*`, `unmount_*`) unchanged; and for step 1, a write through the
Files app or the E2E write cases, followed by the host's filesystem checker, since the
handle carries writes.
