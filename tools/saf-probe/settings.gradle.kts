pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

// A standalone build, not a module of the OTG Master build: the root's unqualified
// tasks must not pick it up (architecture proposal, F15). Build it with
//   ./gradlew -p tools/saf-probe assembleDebug
rootProject.name = "saf-probe"
