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
    testImplementation("junit:junit:4.13.2")
}
