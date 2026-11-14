import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.util.Locale

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
}

// App plugins (docs/PLUGINS.md): every plugins/<id>/plugin.yaml of the repository ships inside the app as
// assets/plugins/<id>.yaml; folders starting with "_" (such as _template) are left out. :core:test validates
// them all, so a bad plugin fails the build before it ships (the app still skips and logs a bad file).
val repoPluginsDir: File = rootDir.resolve("../../plugins").normalize()

// E2E test plugins (local plugins for the fixture apps), validated by FixturePluginsTest.
val fixturePluginsDir: File = rootDir.resolve("fixtures/plugins")

android {
    namespace = "dev.phonebridge.android"
    compileSdk = 35

    defaultConfig {
        applicationId = "dev.phonebridge.android"
        minSdk = 30
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            // Sideloaded personal build: sign release with the debug key so `assembleRelease` is installable.
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        resources.excludes += setOf("META-INF/AL2.0", "META-INF/LGPL2.1", "META-INF/LICENSE*", "META-INF/NOTICE*")
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
        unitTests.all { test ->
            test.systemProperty("phonebridge.fixturePluginsDir", fixturePluginsDir.absolutePath)
            test.inputs.dir(fixturePluginsDir).withPropertyName("fixturePlugins").withPathSensitivity(PathSensitivity.RELATIVE)
        }
    }
}

/** Copies plugins/<id>/plugin.yaml (folders not starting with "_") to `<outputDir>/plugins/<id>.yaml`. */
abstract class BundlePluginsTask : DefaultTask() {
    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val pluginsDir: DirectoryProperty

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @TaskAction
    fun bundle() {
        val out = outputDir.get().asFile
        out.deleteRecursively()
        val target = File(out, "plugins").apply { mkdirs() }
        val folders = pluginsDir.get().asFile.listFiles().orEmpty()
            .filter { it.isDirectory && !it.name.startsWith("_") && !it.name.startsWith(".") }
            .sortedBy { it.name }
        for (folder in folders) {
            val yaml = File(folder, "plugin.yaml")
            if (yaml.isFile) yaml.copyTo(File(target, "${folder.name}.yaml"), overwrite = true)
        }
    }
}

androidComponents {
    onVariants { variant ->
        val name = variant.name.replaceFirstChar { it.titlecase(Locale.ROOT) }
        val bundle = tasks.register<BundlePluginsTask>("bundle${name}Plugins") {
            group = "build"
            description = "Copies the repository's app plugins into the ${variant.name} assets (docs/PLUGINS.md)."
            pluginsDir.set(repoPluginsDir)
        }
        variant.sources.assets?.addGeneratedSourceDirectory(bundle, BundlePluginsTask::outputDir)
    }
}

kotlin {
    compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
}

dependencies {
    implementation(project(":core"))

    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-service:2.8.7")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")

    implementation(platform("androidx.compose:compose-bom:2024.12.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    debugImplementation("androidx.compose.ui:ui-tooling")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
    // An XmlPullParser for JVM tests of the shortcuts.xml parser (on the phone, Android's compiled-XML parser feeds it).
    testImplementation("net.sf.kxml:kxml2:2.3.0")
}
