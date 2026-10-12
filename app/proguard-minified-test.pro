# Extra keep rules for the "minified" build type only — never the release build.
#
# The instrumented-test APK omits every library the app already has, expecting to find it
# in the app at run time; R8 removes or renames those the app does not use itself, the
# same way. Each rule below is a library the test side needs from the app:
#
# - androidx.tracing: AndroidJUnitRunner needs it in onCreate. Without it the runner
#   crashed (ClassNotFoundException: androidx.tracing.Trace) and no test started.
# - the Kotlin standard library: the test code calls it (lazy, collections, …). Without it
#   every case crashed before running (NoClassDefFoundError: kotlin.LazyKt).
#
# Library code only. The app's own classes — and the native-bridge keep rules this build
# type exists to test — are shrunk and renamed exactly as in the release build.
-keep class androidx.tracing.** { *; }
-keep class kotlin.** { *; }
-keep class kotlinx.coroutines.** { *; }
