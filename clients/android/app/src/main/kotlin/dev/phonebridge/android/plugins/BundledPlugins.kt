package dev.phonebridge.android.plugins

import android.content.res.AssetManager
import dev.phonebridge.core.plugins.PluginFile

/**
 * The reviewed plugins shipped inside the app: the build copies every `plugins/<id>/plugin.yaml` of the
 * repository to `assets/plugins/<id>.yaml` (app/build.gradle.kts, BundlePluginsTask).
 */
object BundledPlugins {
    const val ASSET_DIR = "plugins"
    private const val SUFFIX = ".yaml"

    /** Every bundled plugin file. Blocking asset I/O: call off the main thread. */
    fun files(assets: AssetManager): List<PluginFile> =
        assets.list(ASSET_DIR).orEmpty().filter { it.endsWith(SUFFIX) }.sorted().map { name ->
            val text = assets.open("$ASSET_DIR/$name").use { it.readBytes().toString(Charsets.UTF_8) }
            PluginFile(repositoryPath(name), text)
        }

    /**
     * `spotify.yaml` → `plugins/spotify/plugin.yaml`: the path the file had in the repository, so messages
     * name it the same way and its id is checked against its folder name.
     */
    fun repositoryPath(assetName: String): String = "plugins/${assetName.removeSuffix(SUFFIX)}/plugin.yaml"
}
