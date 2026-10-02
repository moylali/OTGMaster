# bitlk-images.tar.xz

BitLocker volumes created on Windows, from cryptsetup's test suite.

- Source: https://gitlab.com/cryptsetup/cryptsetup, `tests/bitlk-images.tar.xz`
- Repository commit when copied: `ca4cc7a44e5794d8ad412f6d4f8463a81fe16d4a` (2026-09-22)
- SHA-256 of the archive: `68bf5669f777668112d497234ebe2166b5feaf0e33b206b095b917f82937bd30`
- Licence: cryptsetup's default, GPL-2.0-or-later (`README.licensing`: files
  without their own header fall under `COPYING`). Test data only; not shipped
  in the APK.

`images.conf` inside gives, per image, its password, recovery password and the
SHA-256 of the whole decrypted volume as cryptsetup maps it — the expected
answers `BitLockerCompatTest` checks against. Registered in `docs/VENDOR_FIXES.md`.
