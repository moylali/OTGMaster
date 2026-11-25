import java.io.FileInputStream
import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// Release signing is optional for local builds: if keystore.properties is absent (the normal
// case for contributors), assembleRelease falls back to being unsigned rather than failing.
// CI provides keystore.properties by writing it from secrets before the release build (see
// .github/workflows/release.yml). Never commit keystore.properties or the keystore itself.
val keystorePropertiesFile = rootProject.file("keystore.properties")
val hasReleaseSigning = keystorePropertiesFile.exists()
val keystoreProperties = Properties().apply {
    if (hasReleaseSigning) load(FileInputStream(keystorePropertiesFile))
}

/** Short commit of the working tree, or "unknown" outside a git checkout. */
fun gitCommit(): String = runCatching {
    val p = ProcessBuilder("git", "rev-parse", "--short", "HEAD")
        .directory(rootDir).redirectErrorStream(true).start()
    p.inputStream.bufferedReader().readText().trim().ifEmpty { "unknown" }
}.getOrDefault("unknown")

fun gitDirty(): Boolean = runCatching {
    val p = ProcessBuilder("git", "status", "--porcelain")
        .directory(rootDir).redirectErrorStream(true).start()
    p.inputStream.bufferedReader().readText().isNotBlank()
}.getOrDefault(false)

android {
    namespace = "app.fayaz.otgmaster"
    compileSdk = 36
    ndkVersion = "26.1.10909125"

    defaultConfig {
        applicationId = "app.fayaz.otgmaster"
        minSdk = 26
        targetSdk = 36
        // Static literals, not computed at build time — F-Droid's checkupdates tool statically
        // parses this file's text per-tag and can't resolve a dynamic expression. Bump via
        // scripts/bump-version.sh as part of cutting a release, before tagging.
        versionCode = 54
        versionName = "0.5.5"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        if (hasReleaseSigning) {
            create("release") {
                storeFile = file(keystoreProperties["storeFile"] as String)
                storePassword = keystoreProperties["storePassword"] as String
                keyAlias = keystoreProperties["keyAlias"] as String
                keyPassword = keystoreProperties["keyPassword"] as String
            }
        }
    }

    buildTypes {
        debug {
            buildConfigField("String", "GIT_COMMIT", "\"${gitCommit()}${if (gitDirty()) "-dirty" else ""}\"")
            // The libexfat I/O counters (ExFatIoStats, OTG_IO_STATS) come from :disk-engine's
            // debug build type, which this one selects by name.
        }
        release {
            // R8 shrinks and obfuscates the release build: Play Console flagged 0.4.x at 0%
            // obfuscation. Everything native code reaches by name (JNI methods, the classes
            // it constructs, the callbacks it looks up) is kept in proguard-rules.pro and
            // :disk-engine's consumer-rules.pro — a
            // missing rule shows up as NoSuchMethodError on the first mount of that format.
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // The AAB then carries the native symbol tables, which Play asks for so it
            // can symbolicate native crashes. The .so files in the APK stay stripped.
            ndk { debugSymbolLevel = "SYMBOL_TABLE" }
            if (hasReleaseSigning) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
        // The release build's code — R8, release native flags — signed with the debug key,
        // so the E2E suite can install it beside its test APK and catch a missing keep rule
        // before a release does: E2E_BUILD_TYPE=minified scripts/run_e2e_parallel.sh.
        // Main sources only, like release: none of the debug-only receivers.
        create("minified") {
            initWith(getByName("release"))
            signingConfig = signingConfigs.getByName("debug")
            matchingFallbacks += "release"
            // The test APK is shrunk against the app's mapping; these rules are for it alone.
            testProguardFiles("proguard-test-rules.pro")
            // Libraries the test APK shares with the app are left out of the test APK and
            // must survive in the app; R8 strips the ones the app itself never calls.
            proguardFiles("proguard-minified-test.pro")
            buildConfigField("String", "GIT_COMMIT", "\"${gitCommit()}${if (gitDirty()) "-dirty" else ""}\"")
        }
        // The benchmark APK: release code plus the harness. Two things the debug APK
        // it replaces got wrong for measuring:
        //  - It measured code no user runs. A debuggable build compiles the native
        //    side as CMake "Debug", which passes no -O flag at all, so the crypto,
        //    libexfat and the other C code ran at -O0 against -O2 -DNDEBUG in release.
        //    initWith(release) keeps it non-debuggable and RelWithDebInfo.
        //  - It shared the store app's package, so installing it meant uninstalling
        //    the real app (and its saved drives) first. The suffix lets both coexist.
        // Debug stays as it was: E2E (run_e2e_tests.sh) installs and drives that APK.
        create("benchmark") {
            initWith(getByName("release"))
            applicationIdSuffix = ".bench"
            versionNameSuffix = "-bench"
            // Installable from any machine, like debug; never the release key.
            signingConfig = signingConfigs.getByName("debug")
            matchingFallbacks += listOf("release")
            buildConfigField("String", "GIT_COMMIT", "\"${gitCommit()}${if (gitDirty()) "-dirty" else ""}\"")
            // The harness reads the libexfat I/O counters: :disk-engine's benchmark build
            // type (selected by name, before the release fallback) compiles them, at a
            // counter increment per pread/pwrite.
        }
    }

    sourceSets {
        // The harness (BenchmarkReceiver, the E2E and dump receivers, ExFatIoStats) stays
        // in src/debug, and the benchmark build type compiles the same sources and
        // manifest. Release still contains none of it.
        getByName("benchmark") {
            java.srcDir("src/debug/java")
            aidl.srcDir("src/debug/aidl")
            manifest.srcFile("src/debug/AndroidManifest.xml")
        }
    }

    // Instrumented tests run against debug unless -Potg.testBuildType names another type
    // (the E2E scripts pass "minified").
    testBuildType = (project.findProperty("otg.testBuildType") as String?) ?: "debug"

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }
    
    dependenciesInfo {
        includeInApk = false
        includeInBundle = false
    }

    buildFeatures {
        compose = true
        buildConfig = true
        // Required from AGP 8 onward; without it .aidl files are ignored silently
        // and the generated Stub simply does not exist.
        aidl = true
    }
    testOptions {
        unitTests {
            // Host tests exercise classes that log through android.util.Log, which
            // throws "not mocked" by default. Returning defaults lets the probe and
            // parser tests run on the JVM instead of needing an instrumented run.
            isReturnDefaultValues = true
            // -PhostPreload=/path/libhardened_malloc.so runs the host tests, and with them the
            // native code they load, under GrapheneOS's hardened allocator, which aborts on heap
            // misuse that glibc's tolerates. Set here because Gradle does not pass the shell's
            // LD_PRELOAD on to the test process.
            providers.gradleProperty("hostPreload").orNull?.let { lib ->
                all { it.environment("LD_PRELOAD", lib) }
            }
        }
    }
}

dependencies {
    implementation(project(":libaums"))
    implementation(project(":mount-core"))
    // Drivers, crypto layers and the native library (libveracrypt-native.so).
    implementation(project(":disk-engine"))
    
    val composeBom = platform("androidx.compose:compose-bom:2025.04.01")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.ui:ui-tooling-preview")
    debugImplementation("androidx.compose.ui:ui-tooling")
    implementation("androidx.activity:activity-compose:1.9.0")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.biometric:biometric:1.1.0")
    // Credential Manager without credentials-play-services-auth: no Google Play Services in
    // the APK (F-Droid), so it reaches password managers on Android 14+ only, where the
    // framework provides it. Older versions still get autofill in the password fields.
    implementation("androidx.credentials:credentials:1.5.0")
    implementation("androidx.security:security-crypto:1.1.0-alpha06")

    // Argon2id for LUKS2 key derivation. Apache 2.0 — compatible with GPL-3.0-or-later.

    // org.json ships in android.jar as a stub that throws under plain unit tests.

    // A real implementation on the test classpath lets JSON payloads be tested

    // without Robolectric.

    testImplementation("org.json:json:20240303")
    // Scheduled backups and their reminders run when the app is closed. Apache 2.0.
    implementation("androidx.work:work-runtime-ktx:2.9.1")


    testImplementation("junit:junit:4.13.2")

    androidTestImplementation("androidx.test.ext:junit:1.1.5")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.5.1")
    androidTestImplementation("androidx.test.uiautomator:uiautomator:2.3.0")
    androidTestImplementation(composeBom)
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
}
