# Rules that travel with :mount-core into every app that shrinks it (R8 reads
# META-INF/proguard/ from each jar on the classpath), so no app has to remember them.

# exfat/io.c, ntfs/NtfsNative.cpp and apfs/ApfsNative.cpp look up pread, pwrite and
# getDeviceSize with GetStaticMethodID, by a signature that names this interface:
# renamed, the first mount of that format fails with NoSuchMethodError.
-keep class app.fayaz.otgmaster.block.RawBlockDevice { *; }
