import java.io.FileInputStream
import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
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

// versionCode tracks total commit count, so it increases automatically on every commit
// without ever needing a manual bump (requires full git history — CI must check out with
// fetch-depth: 0, not a shallow clone, or this silently collapses to 1).
// versionName tracks the latest git tag (e.g. "v0.2.0" -> "0.2.0"), so it only changes when
// a release is actually tagged. Falls back to "0.0.0" / commit count 1 outside a git repo.
fun gitCommitCount(): Int {
    return try {
        val process = ProcessBuilder("git", "rev-list", "--count", "HEAD")
            .directory(rootDir)
            .redirectErrorStream(true)
            .start()
        process.waitFor()
        process.inputStream.bufferedReader().readText().trim().toIntOrNull() ?: 1
    } catch (e: Exception) {
        1
    }
}

fun gitVersionName(): String {
    return try {
        val process = ProcessBuilder("git", "describe", "--tags", "--abbrev=0")
            .directory(rootDir)
            .redirectErrorStream(true)
            .start()
        process.waitFor()
        val tag = process.inputStream.bufferedReader().readText().trim()
        tag.removePrefix("v").ifEmpty { "0.0.0" }
    } catch (e: Exception) {
        "0.0.0"
    }
}

android {
    namespace = "app.fayaz.otgmaster"
    compileSdk = 34
    ndkVersion = "26.1.10909125"

    defaultConfig {
        applicationId = "app.fayaz.otgmaster"
        minSdk = 26
        targetSdk = 34
        versionCode = gitCommitCount()
        versionName = gitVersionName()
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
        release {
            isMinifyEnabled = false
            if (hasReleaseSigning) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

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
    
    buildFeatures {
        compose = true
    }
    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.14"
    }
}

dependencies {
    implementation(project(":libaums"))
    
    val composeBom = platform("androidx.compose:compose-bom:2024.04.01")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.ui:ui-tooling-preview")
    debugImplementation("androidx.compose.ui:ui-tooling")
    implementation("androidx.activity:activity-compose:1.9.0")

    testImplementation("junit:junit:4.13.2")

    androidTestImplementation("androidx.test.ext:junit:1.1.5")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.5.1")
    androidTestImplementation("androidx.test.uiautomator:uiautomator:2.3.0")
    androidTestImplementation(composeBom)
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
}
