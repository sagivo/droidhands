import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// PhoneBridge iOS host (docs/IOS.md): the shared core running on the Mac for one iPhone or simulator, with the app
// inventory from Apple's tools and the on-phone XCUITest runner for the screen. Run through scripts/ios-host.sh.

plugins {
    id("org.jetbrains.kotlin.jvm")
    id("org.jetbrains.kotlin.plugin.serialization")
    application
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
}

dependencies {
    implementation(project(":core"))

    testImplementation(kotlin("test"))
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
}

application {
    mainClass.set("dev.phonebridge.ios.IosHostKt")
    applicationName = "phonebridge-ios-host"
    // Screenshots are marked with java.awt off screen: never open a Dock icon or a window.
    applicationDefaultJvmArgs = listOf("-Djava.awt.headless=true", "-Dapple.awt.UIElement=true")
}

// `gradle :ios-host:run --args="..."` reads the terminal (Ctrl-C stops it) and runs from the repository root.
tasks.named<JavaExec>("run") {
    standardInput = System.`in`
    workingDir = rootDir.resolve("../..").normalize()
}

// The repository's reviewed app plugins (docs/PLUGINS.md), bundled as resources like the Android app's assets:
// phonebridge/plugins/<id>.yaml plus an index, since a jar can't be listed.
val repoPluginsDir: File = rootDir.resolve("../../plugins").normalize()
val bundledPlugins = layout.buildDirectory.dir("generated/plugins")

val bundlePlugins = tasks.register("bundlePlugins") {
    group = "build"
    description = "Copies the repository's app plugins into the iOS host's resources (docs/PLUGINS.md)."
    inputs.dir(repoPluginsDir).withPropertyName("plugins").withPathSensitivity(PathSensitivity.RELATIVE)
    outputs.dir(bundledPlugins)
    doLast {
        val out = bundledPlugins.get().asFile.resolve("phonebridge/plugins")
        out.deleteRecursively()
        out.mkdirs()
        val names = repoPluginsDir.listFiles().orEmpty()
            .filter { it.isDirectory && !it.name.startsWith("_") && !it.name.startsWith(".") && File(it, "plugin.yaml").isFile }
            .sortedBy { it.name }
            .map { folder ->
                File(folder, "plugin.yaml").copyTo(File(out, "${folder.name}.yaml"), overwrite = true)
                folder.name
            }
        File(out, "index.txt").writeText(names.joinToString("\n", postfix = "\n"))
    }
}

sourceSets["main"].resources.srcDir(files(bundledPlugins).builtBy(bundlePlugins))

tasks.test {
    useJUnit()
    systemProperty("java.awt.headless", "true")
    testLogging { events("failed"); exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL }
}
