# Extra keep rules for the "minified" build type only — never the release build.
#
# The instrumented-test APK omits every library the app already has, expecting to find it
# in the app at run time; R8 removes those the app does not use itself. AndroidJUnitRunner
# needs androidx.tracing before any test runs: without this the runner crashed in
# onCreate (ClassNotFoundException: androidx.tracing.Trace) and no test started.
-keep class androidx.tracing.** { *; }
