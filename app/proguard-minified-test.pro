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
# - OtgMasterState and MountedDrive, which the disconnect case reaches into to find the
#   mounted drive's key and call detachRequest (E2EAutomatedTest.dropMountedDrive). R8
#   made the object's members static and dropped its INSTANCE field (NoSuchFieldError:
#   No field INSTANCE in u1.W0), and with that kept, removed the getter the test calls
#   (NoSuchMethodError: getSourceDeviceName in u1.U0). Only those three members are
#   kept, not renamed — they are every app member the test touches; MainActivity's
#   detach code, which the case exercises, is shrunk as in release.
-keep class androidx.tracing.** { *; }
-keep class kotlin.** { *; }
-keep class kotlinx.coroutines.** { *; }
-keep class app.fayaz.otgmaster.OtgMasterState {
    public static final app.fayaz.otgmaster.OtgMasterState INSTANCE;
    public final java.util.concurrent.CopyOnWriteArrayList getMountedDrives();
    public final kotlin.jvm.functions.Function1 getDetachRequest();
}
-keep class app.fayaz.otgmaster.MountedDrive {
    public final java.lang.String getSourceDeviceName();
}
