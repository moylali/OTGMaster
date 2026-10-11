# R8 keep rules for the release build (and the "minified" E2E build type).
#
# What must survive renaming is everything native code reaches by name. The E2E suite
# runs against the minified build type for exactly this reason: a missing rule is a
# NoSuchMethodError or ClassNotFoundException on the first mount of that format, not a
# build error.

# ── JNI entry points ──────────────────────────────────────────────────────────
# Every `external fun` is bound by its mangled Java name (Java_app_fayaz_…): the class
# and the method keep their names. Covers VeraCryptNative, ExFatNative, NtfsNative,
# ApfsNative, BitLockerNative, libaums' AndroidUsbCommunication and argon2kt.
-keepclasseswithmembernames,includedescriptorclasses class * {
    native <methods>;
}

# ── Callbacks native code looks up with GetStaticMethodID ────────────────────
# exfat/io.c, ntfs/NtfsNative.cpp, apfs/ApfsNative.cpp: pread, pwrite, getDeviceSize,
# with RawBlockDevice in the signature.
-keep class app.fayaz.otgmaster.exfat.ExFatNative { *; }
-keep class app.fayaz.otgmaster.ntfs.NtfsNative { *; }
-keep class app.fayaz.otgmaster.apfs.ApfsNative { *; }
-keep class app.fayaz.otgmaster.block.RawBlockDevice { *; }

# ── Classes native code constructs (FindClass + GetMethodID "<init>") ─────────
-keep class app.fayaz.otgmaster.exfat.ExFatNode { *; }
-keep class app.fayaz.otgmaster.ntfs.NtfsNode { *; }
-keep class app.fayaz.otgmaster.apfs.ApfsNode { *; }

# ── argon2kt (LUKS2 Argon2): ships no consumer rules; its .so names
#    com/lambdapioneer/argon2kt/ByteBufferTarget and throws Argon2Exception ──────
-keep class com.lambdapioneer.argon2kt.** { *; }

# ── Standard Android keep rules (supplement proguard-android-optimize.txt) ────
-keepclassmembers class * implements android.os.Parcelable {
    public static final android.os.Parcelable$Creator CREATOR;
}

# ── Compile-time-only annotations ─────────────────────────────────────────────
-dontwarn com.google.errorprone.annotations.**
-dontwarn javax.annotation.**

# Line numbers in stack traces; decode them with the release's mapping.txt (attached
# to each GitHub release, and inside the AAB for Play).
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
