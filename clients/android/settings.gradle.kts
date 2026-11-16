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
// E2E test fixtures (emulator only): fake WhatsApp (com.whatsapp) and a fake mail app. See fixtures/README.md.
include(":fixtures:fakewhatsapp", ":fixtures:fakemail")
// iOS host (docs/IOS.md): runs the core on the Mac for an iPhone or simulator, driving the on-phone runner.
include(":ios-host")
project(":ios-host").projectDir = file("../ios/host")
