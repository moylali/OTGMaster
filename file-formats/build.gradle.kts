// :file-formats — readers for container files that are not disk images: ZIP first (architecture
// proposal, Milestone 4), ISO and AES Crypt later. Plain JVM, and it depends on :mount-core only:
// "No disk-driver dependency appears in the archive reader" is the milestone's own test.
plugins {
    id("org.jetbrains.kotlin.jvm")
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    api(project(":mount-core"))
    // 7z (and its LZMA/LZMA2 decoders): Apache Commons Compress, Apache-2.0; XZ for Java, 0BSD.
    // Pinned; listed in README.md's third-party table.
    implementation("org.apache.commons:commons-compress:1.27.1")
    implementation("org.tukaani:xz:1.10")
    testImplementation("junit:junit:4.13.2")
}
