// :disk-engine — everything between a block source and a mounted tree: MBR/GPT slices, the
// block cache and read-only guards, the VeraCrypt, LUKS and BitLocker layers, the filesystem
// drivers (FAT32 through libaums, exFAT, ext, NTFS, APFS) and the native library they share
// (architecture proposal, Milestone 2). Both apps build on it; neither USB attach nor any UI
// lives here.
//
// Packages keep the names they had in :app (proposal F1): native code finds its callbacks
// and the classes it constructs by binary name, and libveracrypt-native.so keeps its name.
plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "app.fayaz.otgmaster.engine"
    compileSdk = 36
    ndkVersion = "26.1.10909125"

    defaultConfig {
        minSdk = 26
        externalNativeBuild {
            cmake {
                cppFlags += "-std=c++17"
            }
        }
        // The keep rules native code needs travel with the classes it reaches.
        consumerProguardFiles("consumer-rules.pro")
    }

    // The app's build types, by name, so each app variant gets the native build it had
    // (proposal F2): debug and benchmark compile the libexfat I/O counters and carry
    // ExFatIoStats; release and minified carry neither. A library does not shrink itself;
    // each app's R8 run applies consumer-rules.pro.
    buildTypes {
        debug {
            externalNativeBuild {
                cmake {
                    arguments += "-DOTG_IO_STATS=ON"
                }
            }
        }
        release {
            isMinifyEnabled = false
        }
        create("minified") {
            initWith(getByName("release"))
            matchingFallbacks += "release"   // :libaums has debug and release only
        }
        // Not debuggable, like release, so CMake builds RelWithDebInfo: the benchmark
        // measures the optimised native code users run.
        create("benchmark") {
            initWith(getByName("release"))
            matchingFallbacks += "release"
            externalNativeBuild {
                cmake {
                    arguments += "-DOTG_IO_STATS=ON"
                }
            }
        }
    }

    sourceSets {
        // ExFatIoStats is in src/debug; benchmark compiles it too, as the app's harness does.
        getByName("benchmark") {
            java.srcDir("src/debug/java")
        }
    }

    externalNativeBuild {
        cmake {
            path("src/main/cpp/CMakeLists.txt")
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
            // Host tests exercise classes that log through android.util.Log, which
            // throws "not mocked" by default.
            isReturnDefaultValues = true
            // -PhostPreload=/path/libhardened_malloc.so: see :app.
            providers.gradleProperty("hostPreload").orNull?.let { lib ->
                all { it.environment("LD_PRELOAD", lib) }
            }
        }
    }
}

dependencies {
    // Both appear in the engine's own signatures (RawBlockDevice, DocumentSource,
    // BlockDeviceDriver, FileSystem, UsbFile), so they are part of its API.
    api(project(":mount-core"))
    api(project(":libaums"))

    // org.json ships in android.jar as a stub that throws under plain unit tests.
    testImplementation("org.json:json:20240303")
    testImplementation("junit:junit:4.13.2")
}
