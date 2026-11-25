package app.fayaz.otgmaster.fs

import java.io.IOException

/**
 * A write that the volume has no room for. Each filesystem used to say so in its own
 * words, or not at all: exFAT as a bare `-28`, ext4 as "out of usable blocks", NTFS as
 * "ENOSPC" inside a longer message, FAT32 (libaums) as "volume is full". The 0.5.3
 * matrix met it as `exFAT write failed at offset 0 (0 of 2012930 bytes written): -28`,
 * which tells a user nothing.
 */
class NoSpaceException(message: String, cause: Throwable? = null) : IOException(message, cause)
