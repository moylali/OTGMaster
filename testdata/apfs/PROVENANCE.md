# apfs-images.tar.xz

The four APFS E2E cases (`apfs_ci`, `apfs_cs`, `apfs_enc_ci`, `apfs_enc_cs`), each
with its `test.img`, `password.txt`, `expected_fs.txt` and `description.txt`.
Committed because only a Mac can build them; see `docs/TEST_DATA.md` §6b.

- Built by `scripts/make_apfs_images_macos.sh`, unchanged from `9f996ca`, on
  2026-10-02, macOS 26.6.2 (arm64).
- SHA-256 of the archive: `097eca6f8b1e80b2e5e9d8ff246e6dec08a19bc3dd2ccfb4c9436a415d4ffd9b`
- SHA-256 of each `test.img`:
  - `apfs_ci`     `787a7b7e619523c6e767831e46db19c666a3560493deea4af3075f6ccc413541`
  - `apfs_cs`     `99ba8d358fb5ccd6ea50cd77e431b6714f505ccc8bb686ebff6709e1b3059e9e`
  - `apfs_enc_ci` `90cce1f74b0a264c6e94c9c3ee633e337a8462763773489f0ec278a8f330af2e`
  - `apfs_enc_cs` `0524020bd4a357a8ad8d567c3f3b6e215f71429e12733f92913bd47854d1fa23`
- Password `password123`. Test data only; not shipped in the APK.

Rebuilding produces different bytes (UUIDs, keys, timestamps, random
`large_file.bin`), so a new archive replaces this one wholesale. Update the hashes
above in the same commit.

Checked on macOS: contents and case behaviour on all four, `fsck_apfs -n` on the
two plain containers. Not yet checked with `scripts/verify_apfs_images.py`
(apfsck + apfs-fuse) on Linux, which is the only structural check of the encrypted
two.
