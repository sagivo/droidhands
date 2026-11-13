import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// TEST FIXTURE: impersonates WhatsApp's package name (com.whatsapp) so PhoneBridge's WhatsApp and
// notification tools can be exercised end-to-end on an emulator. Never install it on a real phone.
// See ../README.md.
plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    // namespace == applicationId, so view ids resolve to "com.whatsapp:id/<name>" exactly like real WhatsApp.
    namespace = "com.whatsapp"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.whatsapp"
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
    // NotificationCompat.MessagingStyle, Person, RemoteInput. No appcompat/material: framework Views only.
    implementation("androidx.core:core-ktx:1.15.0")
}
