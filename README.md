# OTG Master

Android app scaffold for opening VeraCrypt-encrypted USB mass-storage devices without root.

The app cannot perform a kernel mount on non-rooted Android. The planned path is:

1. Request USB Host permission for a mass-storage device.
2. Read raw sectors through a userspace USB Mass Storage adapter.
3. Probe MBR/GPT partitions to find VeraCrypt volume starts.
4. Unlock a VeraCrypt header and expose a decrypted block-device wrapper.
5. Feed the decrypted block device into a userspace filesystem reader.
6. Surface files through the app UI first, then a `DocumentsProvider`.

The current scaffold builds the app shell, USB attach/permission flow, and core block/partition interfaces. The libaums adapter and VeraCrypt crypto implementation are intentionally behind local interfaces so they can be added and tested independently.

## Build

```bash
gradle assembleDebug
```
