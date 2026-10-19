# Test builds

Not releases. Each file here is a one-off build for a specific issue, signed with a
debug key, so it will not install over the Play Store or F-Droid version: uninstall
that first (your drives are not touched; saved settings are).

| File | For | Built from | SHA-256 |
|---|---|---|---|
| `otgmaster-0.4.2-usbtest-0de1b76.apk` | [#71](https://github.com/moylali/OTGMaster/issues/71): first USB transfer fails on OPPO/realme with Android 16 | [`0de1b76`](https://github.com/moylali/OTGMaster/commit/0de1b76) on branch `test/usb-recovery` (0.4.2 main `4b5b630` + logging and USB reset) | `83cba69e3596726630d2bcae2e3b970410e05d3756613d25d5bc8ce7cb9a35a9` |
