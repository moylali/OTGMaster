import java.io.FileInputStream
import java.util.Properties

// Container Master: opens container files the user picks (VeraCrypt first) and serves their
// contents through its own DocumentsProvider (architecture proposal, Milestone 3). Built on the
// same :disk-engine, :saf-provider and :mount-core as OTG Master, with its own package,
// authority, process, version and listing. Read-only in this first slice.
plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// The owner's decision (2026-11-24): Container Master is signed with OTG Master's keystore.
val keystorePropertiesFile = rootProject.file("keystore.properties")
val hasReleaseSigning = keystorePropertiesFile.exists()
val keystoreProperties = Properties().apply {
    if (hasReleaseSigning) load(FileInputStream(keystorePropertiesFile))
}

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
    namespace = "app.fayaz.containermaster"
    compileSdk = 36

    defaultConfig {
        applicationId = "app.fayaz.containermaster"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        buildConfigField("String", "GIT_COMMIT", "\"${gitCommit()}${if (gitDirty()) "-dirty" else ""}\"")
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
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            if (hasReleaseSigning) signingConfig = signingConfigs.getByName("release")
        }
    }

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
    }
}

dependencies {
    implementation(project(":mount-core"))
    implementation(project(":disk-engine"))
    implementation(project(":saf-provider"))
    implementation(project(":file-formats"))
    implementation(project(":app-common"))

    val composeBom = platform("androidx.compose:compose-bom:2025.04.01")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.activity:activity-compose:1.9.0")
    // The fingerprint or screen-lock prompt in front of a saved password; brings FragmentActivity.
    implementation("androidx.biometric:biometric:1.1.0")
    // biometric 1.1.0 brings fragment 1.2, whose FragmentActivity refuses activity 1.9's request codes
    // ("Can only use lower 16 bits for requestCode") and crashed the file picker; OTG Master gets a
    // newer fragment through appcompat.
    implementation("androidx.fragment:fragment-ktx:1.8.5")

    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.1.5")
    androidTestImplementation("androidx.test.uiautomator:uiautomator:2.3.0")
}
