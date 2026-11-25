# R8 keep rules for the release build (and the "minified" E2E build type).
#
# What must survive renaming is everything native code reaches by name. The E2E suite
# runs against the minified build type for exactly this reason: a missing rule is a
# NoSuchMethodError or ClassNotFoundException on the first mount of that format, not a
# build error.

# ── JNI entry points ──────────────────────────────────────────────────────────
# Every `external fun` is bound by its mangled Java name (Java_app_fayaz_…): the class
# and the method keep their names. Here for the app's own (ZstdNative for restic) and
# libaums' AndroidUsbCommunication; :disk-engine's consumer-rules.pro carries the same
# rule and the callbacks and constructed classes of the engine's drivers.
-keepclasseswithmembernames,includedescriptorclasses class * {
    native <methods>;
}

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
