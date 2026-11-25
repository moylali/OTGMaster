// :mount-core — the contracts every mount is built from, as plain JVM types: the block
// device a filesystem reads, and the document tree the SAF provider serves (architecture
// proposal, Milestone 2). No Android or libaums imports, so it builds and tests on any JVM
// and both apps (OTG Master, Container Master) can share it.
//
// Fully qualified names stay as they were in :app (proposal F1): native code looks up
// app.fayaz.otgmaster.block.RawBlockDevice by its binary name, and moving the file between
// Gradle modules changes nothing in the class file.
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
    testImplementation("junit:junit:4.13.2")
}
