// :app-common — screens and helpers both apps show the same way: Report issue (FeedbackActivity,
// FeedbackPayload, PathMask) and the system-bar appearance. Each app feeds Report issue through
// a FeedbackSource installed in its Application. Packages keep their app.fayaz.otgmaster names.
plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "app.fayaz.otgmaster.common"
    compileSdk = 36

    defaultConfig {
        minSdk = 26
    }

    buildTypes {
        create("minified") {
            initWith(getByName("release"))
            matchingFallbacks += "release"
        }
        create("benchmark") {
            initWith(getByName("release"))
            matchingFallbacks += "release"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
    }

    testOptions {
        unitTests {
            isReturnDefaultValues = true
        }
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2025.04.01")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.activity:activity-compose:1.9.0")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
}
