# R8 rules for the instrumented-test APK built against the "minified" build type.
# androidx.test references Error Prone's annotations, which are compile-time only.
-dontwarn com.google.errorprone.annotations.**
