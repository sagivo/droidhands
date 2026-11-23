import com.android.build.api.variant.impl.VariantOutputImpl
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

// ---- Build inputs (docs/RELEASING.md) ----
// Each value comes from a Gradle property (-Pname=value or ~/.gradle/gradle.properties) or an
// environment variable, so CI can set them without editing files. Blank counts as unset.

fun input(property: String, env: String? = null): String? =
    providers.gradleProperty(property).orNull?.trim()?.takeIf { it.isNotEmpty() }
        ?: env?.let { providers.environmentVariable(it).orNull?.trim()?.takeIf { value -> value.isNotEmpty() } }

/** A Java string literal for buildConfigField. */
fun literal(value: String): String = "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

val pbVersionName: String = input("droidhands.versionName") ?: "0.1.0"
val pbVersionCode: Int = input("droidhands.versionCode")?.let { raw ->
    raw.toIntOrNull()?.takeIf { it in 1..2_100_000_000 }
        ?: throw GradleException("droidhands.versionCode must be a positive integer, got \"$raw\"")
} ?: 1
if (!Regex("^\\d+\\.\\d+\\.\\d+([-+][0-9A-Za-z.+-]+)?$").matches(pbVersionName)) {
    throw GradleException("droidhands.versionName must look like 1.2.3 (optionally with a -suffix), got \"$pbVersionName\"")
}

/** Relay the app connects to on first run when the user has none configured (empty = no default relay). */
val defaultRelayUrl: String = (input("droidhands.defaultRelayUrl", "DROIDHANDS_DEFAULT_RELAY_URL") ?: "").trimEnd('/')
if (defaultRelayUrl.isNotEmpty() && !Regex("^https?://[^\\s/?#@\"\\\\]+(/[^\\s?#\"\\\\]*)?$").matches(defaultRelayUrl)) {
    throw GradleException(
        "droidhands.defaultRelayUrl / DROIDHANDS_DEFAULT_RELAY_URL must be an http(s) base URL such as " +
            "https://relay.example.com, got \"$defaultRelayUrl\"",
    )
}

/**
 * DroidHands API server (sign in with Google) for the default relay, e.g. https://api.droidhands.com
 * (empty = no accounts: the default relay needs no sign-in). Only the build's default relay gets the account.
 */
val apiUrl: String = (input("droidhands.apiUrl", "DROIDHANDS_API_URL") ?: "").trimEnd('/')
if (apiUrl.isNotEmpty() && !Regex("^https?://[^\\s/?#@\"\\\\]+(/[^\\s?#\"\\\\]*)?$").matches(apiUrl)) {
    throw GradleException(
        "droidhands.apiUrl / DROIDHANDS_API_URL must be an http(s) base URL such as " +
            "https://api.example.com, got \"$apiUrl\"",
    )
}

/** "owner/name" of the GitHub repository whose releases the in-app update check reads (empty = no check). */
val githubRepo: String = input("droidhands.githubRepo", "GITHUB_REPOSITORY") ?: ""
if (githubRepo.isNotEmpty() && !Regex("^[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+$").matches(githubRepo)) {
    throw GradleException("droidhands.githubRepo / GITHUB_REPOSITORY must be \"owner/name\", got \"$githubRepo\"")
}

/**
 * Release signing: all four values, or none. With none, release builds fall back to the debug key
 * (with a warning) so local sideloading keeps working. `-Pdroidhands.requireReleaseSigning=true`
 * (used by the release workflow) makes a missing key an error, so a published APK is never
 * debug-signed.
 */
val signingInputs: Map<String, String?> =
    listOf("PB_KEYSTORE_PATH", "PB_KEYSTORE_PASSWORD", "PB_KEY_ALIAS", "PB_KEY_PASSWORD").associateWith { input(it, it) }
val releaseSigningConfigured: Boolean = signingInputs.values.all { it != null }
if (!releaseSigningConfigured) {
    val missing = signingInputs.filterValues { it == null }.keys.joinToString()
    if (signingInputs.values.any { it != null }) {
        throw GradleException("Release signing is only partly configured. Also set: $missing (see docs/RELEASING.md)")
    }
    if (input("droidhands.requireReleaseSigning")?.toBoolean() == true) {
        throw GradleException("Release signing is required but not configured. Set $missing (see docs/RELEASING.md)")
    }
}

android {
    namespace = "com.droidhands.android"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.droidhands.android"
        minSdk = 30
        targetSdk = 36
        versionCode = pbVersionCode
        versionName = pbVersionName
        buildConfigField("String", "DEFAULT_RELAY_URL", literal(defaultRelayUrl))
        buildConfigField("String", "API_URL", literal(apiUrl))
    }

    // Two editions of the same app (docs/EDITIONS.md). Both have the application id com.droidhands.android, so a
    // phone has one or the other.
    flavorDimensions += "edition"
    productFlavors {
        // The APK on GitHub Releases and droidhands.com: everything, including phone use (the accessibility
        // service, src/full/AndroidManifest.xml) and the in-app update check.
        create("full") {
            dimension = "edition"
            buildConfigField("boolean", "PHONE_USE", "true")
            buildConfigField("String", "GITHUB_REPO", literal(githubRepo))
        }
        // Google Play: no accessibility service (Play's Accessibility API policy bars it for AI agents), so no
        // screen tools or screen recipes, and no update check (Play installs update through Play).
        create("store") {
            dimension = "edition"
            buildConfigField("boolean", "PHONE_USE", "false")
            buildConfigField("String", "GITHUB_REPO", literal(""))
        }
    }

    signingConfigs {
        if (releaseSigningConfigured) {
            create("release") {
                val path = signingInputs.getValue("PB_KEYSTORE_PATH")!!
                storeFile = file(path).also {
                    if (!it.isFile) throw GradleException("PB_KEYSTORE_PATH does not point to a keystore file: $path")
                }
                storePassword = signingInputs.getValue("PB_KEYSTORE_PASSWORD")
                keyAlias = signingInputs.getValue("PB_KEY_ALIAS")
                keyPassword = signingInputs.getValue("PB_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            // Without a release key (local builds) sign with the debug key, so `assembleRelease` stays
            // installable. Such an APK can't update, or be updated by, a published release.
            signingConfig = signingConfigs.getByName(if (releaseSigningConfigured) "release" else "debug")
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
            test.systemProperty("droidhands.fixturePluginsDir", fixturePluginsDir.absolutePath)
            test.inputs.dir(fixturePluginsDir).withPropertyName("fixturePlugins").withPathSensitivity(PathSensitivity.RELATIVE)
            // BundledPluginToolsTest checks the bundled plugins' tool recipes against the Android tools.
            test.systemProperty("droidhands.pluginsDir", repoPluginsDir.absolutePath)
            test.inputs.dir(repoPluginsDir).withPropertyName("plugins").withPathSensitivity(PathSensitivity.RELATIVE)
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

androidComponents {
    onVariants(selector().withBuildType("release")) { variant ->
        // DroidHands-1.2.3.apk (full, the file the release workflow publishes) or DroidHands-store-1.2.3.apk.
        val edition = if (variant.flavorName == "full") "" else "${variant.flavorName}-"
        variant.outputs.forEach { output ->
            (output as? VariantOutputImpl)?.outputFileName?.set("DroidHands-$edition$pbVersionName.apk")
        }
    }
}

if (!releaseSigningConfigured) {
    val warning = "WARNING: no release key configured (PB_KEYSTORE_PATH, PB_KEYSTORE_PASSWORD, PB_KEY_ALIAS, PB_KEY_PASSWORD), " +
        "so this release APK is signed with the DEBUG key. Fine for your own phone; never publish it. See docs/RELEASING.md."
    tasks.matching { it.name.startsWith("package") && it.name.endsWith("Release") }.configureEach {
        doFirst { logger.warn(warning) }
    }
}

dependencies {
    implementation(project(":core"))

    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-compose:1.9.3")
    // Sign-in opens the accounts page in a Custom Tab (Google refuses sign-in inside a WebView).
    implementation("androidx.browser:browser:1.8.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-service:2.8.7")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    // "Scan QR code" for relay setup: Google Play services' scanner UI, so the app needs no camera permission.
    implementation("com.google.android.gms:play-services-code-scanner:16.1.0")

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
