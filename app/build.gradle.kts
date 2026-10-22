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
        versionCode = 49
        versionName = "0.4.2"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        externalNativeBuild {
            cmake {
                cppFlags += "-std=c++17"
            }
        }
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
            // Compiles the libexfat I/O counters (ExFatIoStats / OTG_IO_STATS in
            // src/main/cpp/CMakeLists.txt). Debug only: release builds must not
            // carry instrumentation, and the Kotlin side lives in src/debug so a
            // release build cannot reference the missing native symbols.
            externalNativeBuild {
                cmake {
                    arguments += "-DOTG_IO_STATS=ON"
                }
            }
        }
        release {
            // R8 shrinks and obfuscates the release build: Play Console flagged 0.4.x at 0%
            // obfuscation. Everything native code reaches by name (JNI methods, the classes
            // it constructs, the callbacks it looks up) is kept in proguard-rules.pro — a
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
    }

    // Instrumented tests run against debug unless -Potg.testBuildType names another type
    // (the E2E scripts pass "minified").
    testBuildType = (project.findProperty("otg.testBuildType") as String?) ?: "debug"

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    externalNativeBuild {
        cmake {
            path("src/main/cpp/CMakeLists.txt")
        }
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
        }
    }
}

dependencies {
    implementation(project(":libaums"))
    
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
    // https://github.com/lambdapioneer/argon2kt
    implementation("com.lambdapioneer.argon2kt:argon2kt:1.6.0")

    // org.json ships in android.jar as a stub that throws under plain unit tests.

    // A real implementation on the test classpath lets JSON payloads be tested

    // without Robolectric.

    testImplementation("org.json:json:20240303")
    // restic compresses blobs and index files with zstd; pure Java, so the app and the host tests share one decoder.
    implementation("io.airlift:aircompressor:0.27")
    // Scheduled backups and their reminders run when the app is closed. Apache 2.0.
    implementation("androidx.work:work-runtime-ktx:2.9.1")


    testImplementation("junit:junit:4.13.2")

    androidTestImplementation("androidx.test.ext:junit:1.1.5")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.5.1")
    androidTestImplementation("androidx.test.uiautomator:uiautomator:2.3.0")
    androidTestImplementation(composeBom)
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
}
