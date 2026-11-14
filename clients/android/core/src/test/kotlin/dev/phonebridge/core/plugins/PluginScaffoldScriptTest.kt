package dev.phonebridge.core.plugins

import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * `scripts/plugin-scaffold.sh` against a fake `adb` that answers `dumpsys package` with a recorded output: the
 * skeleton it prints must be a valid plugin whose examples pass, so the script can't drift from the format.
 */
class PluginScaffoldScriptTest {
    @get:Rule
    val temp = TemporaryFolder()

    private val repoRoot: File = (System.getProperty("phonebridge.pluginsDir")?.let(::File) ?: File("../../../plugins")).absoluteFile.parentFile
    private val script = File(repoRoot, "scripts/plugin-scaffold.sh")
    private val bash = listOf("/bin/bash", "/usr/bin/bash").map(::File).firstOrNull { it.canExecute() }

    private class Run(val exit: Int, val out: String, val err: String)

    private fun run(dumpsys: String, vararg args: String): Run {
        assumeTrue("needs bash", bash != null)
        assertTrue(script.isFile, "missing ${script.path}")
        val dump = temp.newFile().apply { writeText(dumpsys) }
        val adb = File(temp.newFolder(), "adb").apply {
            writeText(
                """
                #!/bin/sh
                if [ "${'$'}1" = "-s" ]; then shift 2; fi
                case "${'$'}1 ${'$'}2 ${'$'}3" in
                  "devices  ") printf 'List of devices attached\nemulator-5554\tdevice\n' ;;
                  "shell dumpsys package") cat "${dump.absolutePath}" ;;
                  *) exit 1 ;;
                esac
                """.trimIndent() + "\n",
            )
            setExecutable(true)
        }
        val process = ProcessBuilder(listOf(bash!!.path, script.path) + args)
            .apply {
                environment()["ADB"] = adb.absolutePath
                // No SDK: the label is not read from the APK.
                environment()["ANDROID_HOME"] = temp.root.absolutePath
                environment().remove("ANDROID_SDK_ROOT")
                environment()["HOME"] = temp.root.absolutePath
            }
            .start()
        val out = process.inputStream.bufferedReader().readText()
        val err = process.errorStream.bufferedReader().readText()
        if (!process.waitFor(60, TimeUnit.SECONDS)) {
            process.destroyForcibly()
            fail("plugin-scaffold.sh did not finish")
        }
        return Run(process.exitValue(), out, err)
    }

    private val recorded: String by lazy {
        javaClass.getResource("/scaffold/dumpsys-package.txt")?.readText() ?: fail("missing scaffold/dumpsys-package.txt")
    }

    @Test
    fun theSkeletonIsAValidPluginWithPassingExamples() {
        val run = run(recorded, "--name", "Example Music", "com.example.music")
        assertEquals(0, run.exit, run.err)
        val result = PluginLoader.load(run.out, PluginSource.BUNDLED, folder = "example_music")
        assertTrue(result.isValid, result.errors.joinToString("\n") + "\n" + run.out)
        val plugin = result.plugin!!
        assertEquals("Example Music", plugin.name)
        assertEquals(listOf("share_text", "process_text", "play_from_search"), plugin.actions.map { it.id })
        assertEquals(".share.ShareActivity", (plugin.actions[0].android as IntentRecipe).className)
        // A handler outside the package can't be named: left out, with a note.
        assertEquals(null, (plugin.actions[1].android as IntentRecipe).className)
        assertTrue("org.lib.translate.TranslateActivity, which a plugin can't name" in run.out)
        // The receiver's WEB_SEARCH filter is not an activity: no web_search action.
        assertTrue("web_search" !in run.out)
        // Links the app opens are listed for the contributor.
        assertTrue("#   examplemusic:..." in run.out && "#   https://open.music.example.com/..." in run.out, run.out)
        assertTrue("version 8.9.0" in run.out)
    }

    @Test
    fun withOnlyALauncherTheSkeletonOpensTheApp() {
        val launcherOnly = """
            Activity Resolver Table:
              Non-Data Actions:
                  android.intent.action.MAIN:
                    3c7d1e5 com.example.calc/.Calculator filter 1a2b3c4
                      Action: "android.intent.action.MAIN"
                      Category: "android.intent.category.LAUNCHER"

            Packages:
              Package [com.example.calc] (4f3e2d1):
                versionName=1.0
        """.trimIndent()
        val run = run(launcherOnly, "com.example.calc")
        assertEquals(0, run.exit, run.err)
        assertTrue("Using \"Calc\" as the app's name" in run.err, run.err)
        val result = PluginLoader.load(run.out, PluginSource.BUNDLED, folder = "calc")
        assertTrue(result.isValid, result.errors.joinToString("\n") + "\n" + run.out)
        assertEquals(".Calculator", (result.plugin!!.actions.single().android as IntentRecipe).className)
    }

    @Test
    fun refusesMissingAppsAndPhoneBridgeItself() {
        val missing = run("Unable to find package: com.example.nope\n", "com.example.nope")
        assertNotEquals(0, missing.exit)
        assertTrue("com.example.nope is not installed" in missing.err, missing.err)
        val bridge = run(recorded, "dev.phonebridge.android")
        assertNotEquals(0, bridge.exit)
        assertTrue("can't target PhoneBridge itself" in bridge.err, bridge.err)
        val notAPackage = run(recorded, "spotify")
        assertNotEquals(0, notAPackage.exit)
        assertTrue("is not a package name" in notAPackage.err, notAPackage.err)
    }
}
