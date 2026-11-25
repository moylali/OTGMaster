// :saf-provider — a DocumentsProvider over the mounted trees of a MountRegistry: SAF cursors
// and flags, the ID codec, checked names, bounded queries, proxy descriptors with one thread
// per tree, errno translation, and the session safety around them (one lock per tree, handles
// tied to the mount they were opened on). Each app subclasses TreeDocumentsProvider once and
// supplies its registry and its policy (architecture proposal, Milestone 2; docs/ENGINE_SPLIT.md).
// No filesystem code: trees come through :mount-core's DocumentSource.
plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "app.fayaz.otgmaster.saf"
    compileSdk = 36

    defaultConfig {
        minSdk = 26
    }

    // The app's extra build types, so they resolve by name like :disk-engine's.
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

    testOptions {
        unitTests {
            isReturnDefaultValues = true
        }
    }
}

dependencies {
    api(project(":mount-core"))
    testImplementation("junit:junit:4.13.2")
}
