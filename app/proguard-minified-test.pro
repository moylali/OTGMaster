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
# Library code, with one exception. The app's own classes — and the native-bridge keep
# rules this build type exists to test — are shrunk and renamed exactly as in the
# release build, except:
#
# - OtgMasterState, which the disconnect case reaches into to find the mounted drive
#   and call detachRequest (E2EAutomatedTest.dropMountedDrive). R8 made the object's
#   members static and dropped its INSTANCE field, so the test failed on the minified
#   build with NoSuchFieldError: No field INSTANCE in u1.W0. Only the object itself and
#   the two members the test reads are kept, not renamed; MainActivity's detach code,
#   which the case exercises, is shrunk as in release.
-keep class androidx.tracing.** { *; }
-keep class kotlin.** { *; }
-keep class kotlinx.coroutines.** { *; }
-keep class app.fayaz.otgmaster.OtgMasterState {
    public static final app.fayaz.otgmaster.OtgMasterState INSTANCE;
    public final java.util.concurrent.CopyOnWriteArrayList getMountedDrives();
    public final kotlin.jvm.functions.Function1 getDetachRequest();
}
