# bitlk-images.tar.xz

BitLocker volumes created on Windows, from cryptsetup's test suite.

- Source: https://gitlab.com/cryptsetup/cryptsetup, `tests/bitlk-images.tar.xz`
- Repository commit when copied: `ca4cc7a44e5794d8ad412f6d4f8463a81fe16d4a` (2026-09-22)
- SHA-256 of the archive: `68bf5669f777668112d497234ebe2166b5feaf0e33b206b095b917f82937bd30`
- Licence: cryptsetup's default, GPL-2.0-or-later (`README.licensing`: files
  without their own header fall under `COPYING`). Test data only; not shipped
  in the APK.

`images.conf` inside gives, per image, its password, recovery password and the
SHA-256 of the whole decrypted volume as cryptsetup maps it: the expected
answers `BitLockerCompatTest` checks against. Registered in `docs/VENDOR_FIXES.md`.

# win11-fat32-usb-head.bin

The first 128 KiB of a 64 MiB USB disk that Windows 11 Enterprise Evaluation
(dockurr/windows, 2026-11-25) encrypted with BitLocker: one MBR partition at
64 KiB, formatted FAT32 by Windows, files copied in, then `Enable-BitLocker
-EncryptionMethod Aes128 -PasswordProtector -UsedSpaceOnly` and a recovery
password. The disk was a QEMU `usb-storage` device with `removable=on`, so
Windows treated it as a USB stick. Made for #184, by this project.

- Contents: sector 0 is the MBR; sector 128 is the BitLocker header, "-FVE-FS-"
  with the FAT32 BPB kept ("FAT32   " at byte 82). No key material: the
  protectors' metadata is further into the volume.
- Password `password123`; recovery password
  `296901-002310-089980-319297-103444-580272-703208-497585`.
- The whole image decrypted (cryptsetup's key, `scripts/bitlk_decrypt.py`) to a
  FAT32 that `fsck.fat -n` passed, holding every file copied in, by SHA-256.
- SHA-256 of this file: `18e7d54d0c9a949e96df042d2f93a0572408826ae9343330558378a2e414b790`.
- Licence: made by this project; GPL-3.0-or-later like the rest of it.
