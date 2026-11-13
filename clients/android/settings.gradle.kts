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

rootProject.name = "phonebridge"

// Platform-neutral bridge core (pure Kotlin/JVM, no Android APIs).
include(":core")
// Android app: services, UI, and Android tool implementations.
include(":app")
