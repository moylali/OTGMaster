# BitLocker support: what is implemented, and the gaps

An audit of `app/src/main/java/app/fayaz/otgmaster/bitlocker/` and
`app/src/main/cpp/bitlocker/` against BitLocker's format as cryptsetup
(`lib/bitlk/bitlk.c`), dislocker and libbde document it, made for the 0.5.4
backlog on 2026-11-18. The code follows cryptsetup's layout; where Microsoft does
not document something, this list says which of those projects it rests on.

## Implemented

| Area | What the app does | Where |
|---|---|---|
| Format version | Version 2 (Windows 7 and later) | `BitLockerHeader.parse` |
| Volume kinds | Fixed and removable volumes ("-FVE-FS-"), and BitLocker To Go behind its FAT32 discovery volume ("MSWIN4.1" with the To Go GUID) | `hasSignature`, `parse` |
| Sector size | 512 and 4096 bytes | `parse` |
| Metadata | The three FVE metadata copies, using the first whose signature, version and CRC are valid; entry bounds checked; the validation block decrypted under the VMK and its SHA-256 compared with the metadata before the FVEK is trusted | `parse`, `BitLockerUnlocker` |
| Ciphers | AES-CBC 128 and 256, each with or without the Elephant diffuser (0x8000 to 0x8003), and XTS-AES 128 and 256 (0x8004, 0x8005) | `bitlocker_crypto.c` |
| Protectors | Password, 48-digit recovery password, and clear key (a volume whose protection Windows suspended) | `BitLockerUnlocker` |
| Layout | The relocated first sectors are read from where BitLocker moved them; the metadata areas and that relocation area read as zeros and refuse writes | `BitLockerBlockDevice` |
| Filesystems inside | NTFS, exFAT and FAT32, read and write | `MainActivity.attemptUnlock` |
| Format inside (0.5.4) | FAT32, exFAT or NTFS, laid out around the metadata areas and the moved first sectors, which become bad clusters: `0x0FFFFFF7` in both FATs, `0xFFFFFFF7` plus the allocation bitmap on exFAT, `$BadClus` on NTFS (mkntfs, VENDOR_FIXES N3, moving `$MFT`, `$MFTMirr` and `$LogFile` off them). Refused, before anything is written, where one lands in the filesystem's own structures (FAT32's FATs and root cluster, exFAT's bitmap, up-case table and root, NTFS's boot sector and its backup), and for ext4 | `DriveFormatter.reformat`, `BitLockerFormatTest` |
| Refusals with a reason | A volume mid-way through encryption or decryption, one paused in Windows' encrypt-on-write conversion, one with no FVEK or volume header entry, unaligned metadata, an unknown cipher, version 1 | `parse` (`unsupportedReason`) |

## Gaps

Ordered by what they would give a user, most first.

1. **Format inside a BitLocker volume.** Done in 0.5.4 for FAT32, exFAT and NTFS
   (above). Windows' own choice on FAT32 and exFAT, reserved files or bad
   clusters, is still unchecked against a volume Windows made; bad clusters keep
   every driver off them either way, and chkdsk keeps them. ext4 would need its
   bad-blocks inode and is not offered: Windows cannot read it anyway.
2. **External key (.BEK) protector.** Windows can protect a drive with a startup
   key or a recovery key saved as a `.BEK` file (`manage-bde -protectors -add
   -RecoveryKey`, or `-StartupKey` on a fixed drive). The app parses the protector
   (0x0200, `STARTUP_KEY`) but cannot use it. The key file holds the 32-byte key
   that unseals the VMK directly, so the unlock form's keyfile picker could supply
   it. A small change; cryptsetup reads `.BEK` files too, which gives a host check.
3. **Changing a password or adding a recovery password.** Not possible in the app;
   a computer with Windows is needed. It would rewrite all three metadata copies
   with a new VMK entry and CRC, which is where most of the risk lies.
4. **Encrypt as BitLocker.** Encrypt creates VeraCrypt and LUKS2 only. Creating
   BitLocker means writing the FVE metadata, the validation block and, for To Go,
   the discovery volume, none of which Microsoft documents. Large, and of little
   use, since Windows does it in a few clicks.
5. **A volume paused mid-conversion.** Refused, like cryptsetup does. Windows
   resumes the conversion when it sees the drive again.
6. **Version 1 (Windows Vista).** Refused. Vista's own BitLocker format differs
   throughout; such drives are rare now.

Not gaps, because they cannot work away from the computer that set them up: TPM
and TPM+PIN protectors (the key is sealed to that computer's TPM), auto-unlock
(the key is in that Windows installation's registry), and smart card protectors
(they need the card's private key, which a phone has no reader for).

## Recommendation

Gap 2 (`.BEK` files) is small and testable on the host. Gap 1 is done. The others
are not worth doing for a USB drive tool.
