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

rootProject.name = "OTGMaster"
include(":app", ":libaums", ":mount-core", ":disk-engine", ":saf-provider", ":file-formats", ":app-common", ":container-app")
