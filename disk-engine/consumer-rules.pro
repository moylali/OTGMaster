# Keep rules every app that shrinks :disk-engine applies (consumerProguardFiles).
# What must survive renaming is everything native code reaches by name: a missing rule is a
# NoSuchMethodError or ClassNotFoundException on the first mount of that format, not a
# build error. The E2E suite runs the minified build type to catch exactly that.

# ── JNI entry points ──────────────────────────────────────────────────────────
# Every `external fun` is bound by its mangled Java name (Java_app_fayaz_…): VeraCryptNative,
# ExFatNative, NtfsNative, ApfsNative, BitLockerNative. includedescriptorclasses also keeps
# the types in their signatures (RawBlockDevice).
-keepclasseswithmembernames,includedescriptorclasses class * {
    native <methods>;
}

# ── Callbacks native code looks up with GetStaticMethodID ────────────────────
# exfat/io.c, ntfs/NtfsNative.cpp, apfs/ApfsNative.cpp: pread, pwrite, getDeviceSize,
# with RawBlockDevice in the signature (kept by :mount-core's own rule).
-keep class app.fayaz.otgmaster.exfat.ExFatNative { *; }
-keep class app.fayaz.otgmaster.ntfs.NtfsNative { *; }
-keep class app.fayaz.otgmaster.apfs.ApfsNative { *; }

# ── Classes native code constructs (FindClass + GetMethodID "<init>") ─────────
-keep class app.fayaz.otgmaster.exfat.ExFatNode { *; }
-keep class app.fayaz.otgmaster.ntfs.NtfsNode { *; }
-keep class app.fayaz.otgmaster.apfs.ApfsNode { *; }
