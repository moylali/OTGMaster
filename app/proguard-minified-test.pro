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
# (Those three members are no longer all: the test now reads ejectedDevices,
# pendingEjects, isReadOnly and more, so both classes are kept whole, like the list below.)
-keep class app.fayaz.otgmaster.OtgMasterState { *; }
-keep class app.fayaz.otgmaster.MountedDrive { *; }

# - Every other app class the instrumented test names (E2EAutomatedTest, listed from its
#   compiled classes with javap, 2026-11-18). The runner reflects over the test class when it
#   starts, so one missing class failed every case before it ran (ClassNotFoundException:
#   app.fayaz.otgmaster.security.CredentialStore, on 0.5.3 as on 0.5.4). None of them is a
#   native bridge, so the JNI rules are still tested under renaming. When the test starts
#   using another app class, add it here.
-keep class app.fayaz.otgmaster.backup.BackupActionReceiver { *; }
-keep class app.fayaz.otgmaster.backup.BackupDelays { *; }
-keep class app.fayaz.otgmaster.backup.BackupExecutor { *; }
-keep class app.fayaz.otgmaster.backup.BackupExecutor$Op { *; }
-keep class app.fayaz.otgmaster.backup.BackupExecutor$Op$Backup { *; }
-keep class app.fayaz.otgmaster.backup.BackupExecutor$Op$Check { *; }
-keep class app.fayaz.otgmaster.backup.BackupExecutor$Op$Recreate { *; }
-keep class app.fayaz.otgmaster.backup.BackupExecutor$Op$Restore { *; }
-keep class app.fayaz.otgmaster.backup.BackupExecutor$RunState { *; }
-keep class app.fayaz.otgmaster.backup.BackupJob { *; }
-keep class app.fayaz.otgmaster.backup.BackupMounts { *; }
-keep class app.fayaz.otgmaster.backup.BackupNotifications { *; }
-keep class app.fayaz.otgmaster.backup.BackupPrompts { *; }
-keep class app.fayaz.otgmaster.backup.BackupPrompts$Due { *; }
-keep class app.fayaz.otgmaster.backup.BackupScheduler { *; }
-keep class app.fayaz.otgmaster.backup.BackupSecrets { *; }
-keep class app.fayaz.otgmaster.backup.Endpoint { *; }
-keep class app.fayaz.otgmaster.backup.Format { *; }
-keep class app.fayaz.otgmaster.backup.JobStore { *; }
-keep class app.fayaz.otgmaster.backup.JobStore$Companion { *; }
-keep class app.fayaz.otgmaster.backup.JobType { *; }
-keep class app.fayaz.otgmaster.backup.Retention { *; }
-keep class app.fayaz.otgmaster.backup.RunRecord { *; }
-keep class app.fayaz.otgmaster.backup.RunStats { *; }
-keep class app.fayaz.otgmaster.backup.RunStatus { *; }
-keep class app.fayaz.otgmaster.backup.Schedule { *; }
-keep class app.fayaz.otgmaster.backup.SnapshotSummary { *; }
-keep class app.fayaz.otgmaster.backup.VolumeInfo { *; }
-keep class app.fayaz.otgmaster.extension.ExtensionContract { *; }
-keep class app.fayaz.otgmaster.extension.ExtensionContract$DeviceKey { *; }
-keep class app.fayaz.otgmaster.MainActivityKt { *; }
-keep class app.fayaz.otgmaster.OtgMasterState$MountRequest { *; }
-keep class app.fayaz.otgmaster.R { *; }
-keep class app.fayaz.otgmaster.R$string { *; }
-keep class app.fayaz.otgmaster.security.CredentialStore { *; }
-keep class app.fayaz.otgmaster.security.LockedStore { *; }
-keep class app.fayaz.otgmaster.security.LockedStore$Keys { *; }
-keep class app.fayaz.otgmaster.security.LockedStore$Kv { *; }
-keep class app.fayaz.otgmaster.veracrypt.HiddenVolumeProtection { *; }
-keep class app.fayaz.otgmaster.veracrypt.VeraCryptCipher { *; }
-keep class app.fayaz.otgmaster.veracrypt.VeraCryptCipher$Companion { *; }
-keep class app.fayaz.otgmaster.veracrypt.VeraCryptHash { *; }
-keep class app.fayaz.otgmaster.veracrypt.VeraCryptHash$Companion { *; }
