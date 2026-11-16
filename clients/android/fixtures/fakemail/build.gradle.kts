import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// TEST FIXTURE: a minimal email app so DroidHands's "send email" / "reply to email" flows can be
// exercised end-to-end on an emulator (no Google account needed). See ../README.md.
plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    // namespace == applicationId, so view ids resolve to "com.droidhands.fixture.mail:id/<name>".
    namespace = "com.droidhands.fixture.mail"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.droidhands.fixture.mail"
        minSdk = 30
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0-fixture"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
}

dependencies {
    // NotificationCompat, RemoteInput. No appcompat/material: framework Views only.
    implementation("androidx.core:core-ktx:1.15.0")
}
