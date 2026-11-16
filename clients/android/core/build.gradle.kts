import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("org.jetbrains.kotlin.jvm")
    id("org.jetbrains.kotlin.plugin.serialization")
    `java-library`
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
}

dependencies {
    api("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")
    api("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
    api("com.squareup.okhttp3:okhttp:4.12.0")
    // App plugins (docs/PLUGINS.md): YAML 1.2 parsing with safe settings, see plugins/PluginYaml.kt.
    implementation("org.snakeyaml:snakeyaml-engine:2.10")

    testImplementation(kotlin("test"))
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
}

// The repository's plugins/ folder (docs/PLUGINS.md). :core:test validates every plugin in it and runs their
// examples (BundledPluginsTest), so a bad plugin fails the build.
val repoRoot: File = rootDir.resolve("../..").normalize()
val pluginsDir: File = repoRoot.resolve("plugins")

tasks.test {
    useJUnit()
    testLogging { events("failed"); exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL }
    systemProperty("droidhands.pluginsDir", pluginsDir.absolutePath)
    inputs.dir(pluginsDir).withPropertyName("plugins").withPathSensitivity(PathSensitivity.RELATIVE)
    // PluginScaffoldScriptTest runs it.
    inputs.file(repoRoot.resolve("scripts/plugin-scaffold.sh")).withPropertyName("scaffoldScript").withPathSensitivity(PathSensitivity.RELATIVE)
}

// Validates plugins and runs their examples, without a phone: all of plugins/, or -Ppath=plugins/<id>
// (a folder or a plugin.yaml; relative paths are from the repository root). Wrapped by scripts/plugin-check.sh.
tasks.register<JavaExec>("checkPlugins") {
    group = "verification"
    description = "Validates app plugins (docs/PLUGINS.md) and runs their examples. -Ppath=plugins/<id> checks one."
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("com.droidhands.core.plugins.PluginCheckKt")
    val target = providers.gradleProperty("path").orNull
        ?.let { p -> File(p).let { if (it.isAbsolute) it else repoRoot.resolve(p) } }
        ?: pluginsDir
    args(target.normalize().absolutePath, repoRoot.absolutePath)
}

// Rewrites the table of bundled plugins in plugins/README.md from the plugin files (-Pcheck=true only checks it).
// Wrapped by scripts/plugin-table.sh; PluginTableTest fails :core:test when the table is out of date.
tasks.register<JavaExec>("pluginTable") {
    group = "documentation"
    description = "Updates the plugin table in plugins/README.md (docs/PLUGINS.md). -Pcheck=true fails instead when it is stale."
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("com.droidhands.core.plugins.PluginTableKt")
    val check = providers.gradleProperty("check").orNull?.toBoolean() == true
    args(listOfNotNull(pluginsDir.absolutePath, if (check) "--check" else null))
}

// Headless demo device for cross-language E2E tests (src/test/kotlin/com/droidhands/core/demo/DemoDevice.kt).
// Env: DEMO_POLICY, DEMO_CONFIRM, DEMO_CONFIRM_TIMEOUT_MS, DEMO_LOCAL_PORT, DEMO_RELAY_URL, DEMO_ACCESS_KEY,
// DEMO_DEVICE_SECRET. Prints DEMO_TOKEN=... and DEMO_DEVICE_ID=... on stdout, then runs until killed.
tasks.register<JavaExec>("runDemoDevice") {
    group = "application"
    description = "Runs the DroidHands demo device for end-to-end tests."
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("com.droidhands.core.demo.DemoDeviceKt")
}
