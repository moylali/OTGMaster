# Engine extraction: Milestone 0 baseline

The architecture proposal splits the storage engine out of OTG Master into shared
modules (`:mount-core`, `:disk-engine`, `:saf-provider`) so a second app, Container
Master, can open container files with the same engine. Milestone 0 records what OTG
Master does today, so every later milestone can be checked against it. Nothing in the
app changes in this milestone.

- **Shipped reference:** v0.5.4 (versionCode 53), tag `v0.5.4` on `1e628f5`.
- **Integration revision:** `b483c68` (main, 0.5.5 / 54 after the bump). The app code is
  the same as `1e628f5`; only the version literals differ.

## The shipped artifact

From `app-release.apk` attached to the v0.5.4 GitHub release.

| | Value |
|---|---|
| Package | `app.fayaz.otgmaster` |
| Version | 0.5.4 (53) |
| min / target / compile SDK | 26 / 36 / 36 |
| Signing certificate | `CN=moylali, OU=OTGMaster, O=OTGMaster, L=Unknown, ST=Unknown, C=US` |
| Certificate SHA-256 | `fa2a19b4549dae6c8f71362a0d74be396beda870d9c6fee327ee290976388f3a` |
| ABIs | arm64-v8a, armeabi-v7a, x86, x86_64 |
| Native libraries | `libveracrypt-native.so`, `libusb-lib.so`, `liberrno-lib.so` (and AndroidX's `libandroidx.graphics.path.so`) |
| ELF LOAD alignment | `0x4000` (16 KiB) for every segment of the three app libraries, on every ABI |

The benchmark build (`app.fayaz.otgmaster.bench`, authority
`app.fayaz.otgmaster.bench.documents`) installs beside it and is evidence for its own
build only.

## The provider contract other apps rely on

`provider/VeraCryptDocumentProvider.kt` and `extension/ExtensionContract.kt` at `b483c68`.

| | Shipped behaviour |
|---|---|
| Authority | `app.fayaz.otgmaster.documents` (`BuildConfig.APPLICATION_ID + ".documents"`) |
| Drive ID | First 10 hex digits of SHA-256 over `"<deviceKey>@<startBlock>"`; a hidden VeraCrypt volume appends `#hidden`, a later APFS volume `#apfs<n>`; a collision gets `-2`, `-3`, … |
| Device key | `"<vendorId>:<productId>:<serial>"`, or `"<vendorId>:<productId>:<deviceName>"` when the drive reports no serial |
| Root ID | `root_<driveId>` |
| Document ID | `<driveId>:<path>`, the root being `<driveId>:/`, for example `<driveId>:/Photos/a.jpg` |
| Root columns | root ID, MIME types `*/*`, flags, icon, title (the drive name), summary `Unlocked Volume`, document ID, available bytes |
| Root flags | `FLAG_SUPPORTS_IS_CHILD`, `FLAG_LOCAL_ONLY`, plus `FLAG_SUPPORTS_CREATE` when writable |
| Document columns | document ID, MIME type, display name, last modified, flags, size. Nothing else: v0.5.4 removed the `otgmaster_*` columns and the mount broadcasts (#165), and that removal is part of the contract |
| Document flags | none on a read-only volume; otherwise delete and rename, plus write for files and create for folders |
| MIME type | from the extension (`MimeTypeMap`), `application/octet-stream` when unknown |

## Native inventory

Exported JNI entry points in the shipped arm64-v8a libraries (the same count on every ABI):

| Library | Entry points |
|---|---|
| `libveracrypt-native.so` | 60: `apfs.ApfsNative` 12, `backup.restic` 1, `bitlocker.BitLockerNative` 6, `exfat.ExFatNative` 16, `ntfs.NtfsNative` 21, `veracrypt.VeraCryptNative` 4 (all under `app.fayaz.otgmaster`) |
| `libusb-lib.so` | 2: `me.jahnen.libaums.core.usb.AndroidUsbCommunication.clearHaltNative`, `resetUsbDeviceNative` |
| `liberrno-lib.so` | 2: `com.github.mjdev.libaums.ErrNo.getErrnoNative`, `getErrstrNative` |

Native code also reaches JVM code by name, so these names and signatures must survive any
module move (F1):

- `FindClass` on `app/fayaz/otgmaster/apfs/ApfsNode`, `exfat/ExFatNode`, `exfat/ExFatNative`,
  `ntfs/NtfsNode`, `ntfs/NtfsNative` and their `<init>(J Ljava/lang/String; …)` constructors.
- `GetStaticMethodID` for `pread`, `pwrite` and `getDeviceSize` taking
  `app/fayaz/otgmaster/block/RawBlockDevice`.
- libexfat still carries the older `com/otgmaster/...` names as a fallback lookup.

Keep rules hold native methods, the static callbacks and the constructed classes by
name. Since Milestone 2 they travel with the code they protect: `RawBlockDevice`'s in
`:mount-core` (`mount-core/src/main/resources/META-INF/proguard/mount-core.pro`, which
R8 reads from the jar), the drivers' in `:disk-engine` (`disk-engine/consumer-rules.pro`).
`app/proguard-rules.pro` keeps the native-method rule for the app's own `ZstdNative`
and libaums.

## Build baseline (F16)

min SDK 26, compile and target SDK 36 for `:app`, compile SDK 34 for the vendored
`:libaums`, NDK `26.1.10909125` for both, `dependenciesInfo` excluded from APK and
bundle, `-ffile-prefix-map` for the source, build and NDK paths, and
`-Wl,-z,max-page-size=16384` on `veracrypt-native`. `OTG_IO_STATS` is compiled into the
debug and benchmark builds only.

## Findings F1 to F21 at the integration revision

**Implemented** means the shipped code already does what the finding asks. **Partly**
means some of it, as described. **Planned** means nothing yet. **Second app** means it
only matters once Container Master exists.

| | Finding | State at `b483c68` | Evidence |
|---|---|---|---|
| F1 | JNI-visible names | Implemented, recorded above. Milestone 2 moved `RawBlockDevice` to `:mount-core` and the drivers, crypto layers, formatters and native code to `:disk-engine`, all under the same binary names | Native inventory above; `mount-core.pro`, `consumer-rules.pro`. Release and benchmark APKs before and after the move: the same 60 (release) and 62 (benchmark) `Java_` exports per ABI, all 16 `.so` files byte-identical, the same 18 class names left unrenamed by R8 |
| F2 | Native instrumentation per variant | Implemented, in `:disk-engine` since Milestone 2 | The library has the app's four build types by name: debug and benchmark pass `-DOTG_IO_STATS=ON` and compile `disk-engine/src/debug` (`ExFatIoStats`); release and minified do neither. The benchmark APK still exports the 2 counter symbols and carries `ExFatIoStats`; the release APK has neither |
| F3 | Explicit disk mounter instead of factory registration | Planned | `MainActivity` registers the exFAT, ext4, NTFS and APFS creators with libaums' `FileSystemFactory` and mounts through `createFileSystem` |
| N1 | Read-only guard on raw drivers | Partly | `ReadOnlyBlockDeviceDriver` guards the byte driver; there is no raw-block guard above the cache yet |
| F4 | Visible lifetime for a background mount | Partly, for OTG | `MountedDriveService` is a `connectedDevice` foreground service; idle relock and Lock all are second-app work |
| F5 | Session generations and held descriptors | Partly | Per-drive proxy threads (`4307067`), handles bound to their filesystem (`ProviderOpenLockTest`: "a handle from before Format can neither write nor close onto the new filesystem"); no explicit generation or admission count |
| F6 | Provider behaviour | Partly | Resolve and truncate under the drive lock (`ProviderOpenLockTest`). Gaps confirmed in code: create and rename pass the display name through unchecked; path lookup lists every directory along the path (`getFileForDocId`); `queryRoots` computes free space whatever the projection; proxy callbacks do not translate failures to errno values |
| F7 | Picker and Open with | Second app | |
| F8 | VeraCrypt header, extent, keyfiles | Partly | `VeraCryptHeader.unusableReason` refuses newer formats, system encryption, unfinished in-place encryption and a sector-size mismatch (`7ba1d7c`); `dataArea` bounds the device to the header's volume size, which leaves out the backup-header area; an unreadable keyfile fails before the KDF (`VeraCryptKeyfiles`); the PIM is not logged. Still to show with desktop fixtures: last valid and first out-of-range sector, nonzero partition offset |
| F9 | LUKS2 metadata and KDF budget | Partly | `Luks2ValidationTest` (both metadata copies, digests, segments, keyslot areas); Argon2i and Argon2id dispatch (`LuksArgon2iTest`); a slot whose Argon2 memory exceeds free memory is unsupported, not a wrong password (L08). The modelled 1 GiB request with zero native calls is not tested yet |
| F10 | AES Crypt | Second app | |
| F11 | ISO and UDF | Second app | |
| F12 | ZIP | Second app | |
| F13 | Release metadata per app | Second app | OTG's paths are the table's left column today |
| F14 | Tag lookups per product | Done for OTG in Milestone 2's first PR (`--match 'v[0-9]*'` in the E2E reporters and the AGENTS.md check); was: | The E2E reporters label builds with `git describe --always --dirty --abbrev=7` and `AGENTS.md`'s changelog check uses `git describe --tags --abbrev=0`, both without a tag pattern |
| F15 | Qualified build tasks | Done in Milestone 2's first PR (CI, release, E2E launcher and README use `:app:` tasks); was: | CI runs `:app:testDebugUnitTest` but unqualified `assembleDebug` and `assembleBenchmark`; the release workflow runs unqualified `assembleRelease bundleRelease`. Safe only while there is one app |
| F16 | Reproducibility, alignment, licensing | Implemented for OTG | Build baseline above; 16 KiB alignment measured on the shipped APK |
| F17 | Ancestry from the mounted tree | Planned | `isChildDocument` and `findDocumentPath` compare path strings |
| F18 | Cooperative KDF cancellation | Planned | No cancellation in `VeraCryptUnlocker` or `LuksUnlocker` |
| F19 | Generalised mount input | Planned | Input is a `RawBlockDevice` only |
| F20 | Image scope | Second app | |
| F21 | Shared algorithm capabilities | Planned | Cipher and hash lists live in the app (`VeraCryptCipher`) |

## The independent client

`tools/saf-probe` is a separate app (`app.fayaz.safprobe`, its own UID) that reaches OTG
Master only the way any app can: a folder grant from the picker. It is a standalone
Gradle build, so the root build's tasks do not include it:

    ./gradlew -p tools/saf-probe assembleDebug

Each command is one launch and leaves `result-<cmd>.json` in the app's external files
directory, logged under the tag `SAFPROBE`:

- `--es cmd grant`: pick a folder; its read grant is persisted.
- `--es cmd read --es path <a/b/c>`: walk the granted tree by display name, read the file,
  record its document ID, size, SHA-256 and every column the provider returned.
- `--es cmd outside --es docid <id>`: try to open an ID through the grant, to show the
  grant stops at its tree.
- `--es cmd grants`: list persisted grants.

## Recorded on hardware, 2026-11-24

OnePlus 7 (GM1901, LineageOS 23.2, Android 16, SDK 36), Drive 1 (`…6B31`), partition
D1EXFAT (plain exFAT, start block 57753640). The debug build was used because it has the
store package and authority (`app.fayaz.otgmaster`, `app.fayaz.otgmaster.documents`) and
can be upgraded in place with the local debug key; the signing certificate does not
change between builds, so the release certificate above still applies.

1. **The held grant.** On the debug build of `b483c68`, `saf-probe` took a grant through the
   picker on `BENCH/edge`: tree URI
   `content://app.fayaz.otgmaster.documents/tree/884fb1f107%3A%2FBENCH%2Fedge`. The drive ID
   `884fb1f107` is the first 10 hex digits of SHA-256 over `5451:4102:071C5C4E138E6B31@57753640`,
   as documented above. It read `884fb1f107:/BENCH/edge/size_1048577.bin`: 1,048,577 bytes,
   SHA-256 `31eb9165dca25d528334e6c4a4c471d3b0e06076a223f355072928acd4063ff4`, which is the
   fixture manifest's value. Columns returned: `document_id`, `mime_type`, `_display_name`,
   `last_modified`, `flags` (70: write, delete, rename) and `_size`, nothing else.
2. **Confinement.** Through that grant, a sibling folder on the same partition
   (`884fb1f107:/BENCH/large/seq_2100m.bin`) and the same path on D1FAT32
   (`357b690009:/BENCH/edge/size_1048577.bin`) were refused with `SecurityException: … is not
   a descendant`. `884fb1f107:/BENCH/edge/../large/seq_2100m.bin` was **not** refused as
   outside the tree: it passed the ancestry check and failed only at lookup
   (`FileNotFoundException`), because no driver lists a `..` entry.
3. **Upgrade (Milestone 1 acceptance).** D1 was ejected in the app, the debug build of
   `800ebf0` (Milestone 1, provider guards) was installed over it with `adb install -r`, and
   D1 mounted again. The same grant was still held and read the same document ID, bytes,
   SHA-256, columns and flags. The two refusals above were unchanged, and the `..` path is
   now refused with `SecurityException` like any document outside the tree.

## Still to record on hardware

Each needs a phone and a matrix drive, and goes in `BENCHMARK_RUNS.md` with the build it
ran on.

1. **Hidden volume confinement.** On a VeraCrypt partition with a hidden volume, a grant
   on the outer volume cannot reach the hidden one. Neither assigned drive has a hidden
   volume; the E2E case `hidden_unlock` covers the drive IDs on the emulator.
2. **Representative USB timings.** The v0.5.4 matrix runs in `BENCHMARK_RUNS.md`
   (2026-11-21 to 11-24) are the reference; Milestone 2 compares against the same
   partitions on the same phones.

Not covered by this baseline: Android 10, since no device here runs it.
