# ── JNI: keep classes whose methods are called from native code ──────────────
-keepclasseswithmembernames class app.fayaz.otgmaster.veracrypt.VeraCryptNative {
    native <methods>;
}
-keepclasseswithmembernames class app.fayaz.otgmaster.exfat.ExFatNative {
    native <methods>;
}

# ExFatNode is constructed and read by JNI
-keep class app.fayaz.otgmaster.exfat.ExFatNode { *; }

# RawBlockDevice is passed into JNI (ExFatNative.mount)
-keep class app.fayaz.otgmaster.block.RawBlockDevice { *; }
-keep interface app.fayaz.otgmaster.block.RawBlockDevice { *; }

# ── USB / libaums: accessed reflectively by the USB stack ────────────────────
-keep class com.github.mjdev.libaums.** { *; }

# ── Standard Android keep rules (supplement android-optimize.txt) ────────────
-keepclassmembers class * implements android.os.Parcelable {
    public static final android.os.Parcelable$Creator CREATOR;
}

# ── Suppress missing annotation classes (compile-time only, not used at runtime) ─
-dontwarn com.google.errorprone.annotations.**
-dontwarn javax.annotation.**
-dontwarn javax.annotation.concurrent.**

# Preserve line numbers in crash stack traces
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
