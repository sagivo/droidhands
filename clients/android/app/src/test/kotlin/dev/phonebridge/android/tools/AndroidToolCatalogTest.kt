package dev.phonebridge.android.tools

import android.content.Context
import android.content.ContextWrapper
import dev.phonebridge.core.mcp.capabilitiesTool
import dev.phonebridge.core.plugins.ActionRequest
import dev.phonebridge.core.plugins.ActionResult
import dev.phonebridge.core.plugins.AppActionCatalog
import dev.phonebridge.core.plugins.AppActionsPlatform
import dev.phonebridge.core.plugins.AppResolution
import dev.phonebridge.core.plugins.InstalledApp
import dev.phonebridge.core.plugins.PluginAction
import dev.phonebridge.core.plugins.PluginCatalog
import dev.phonebridge.core.tools.AppAccess
import dev.phonebridge.core.tools.CallContext
import dev.phonebridge.core.tools.ToolNames
import dev.phonebridge.core.tools.ToolRegistry
import org.junit.Assert.assertEquals
import org.junit.Test

/** The Android registry offers exactly the platform-neutral catalog of docs/TOOLS.md ([ToolNames.ALL]). */
class AndroidToolCatalogTest {
    private object NoApps : AppActionsPlatform {
        override suspend fun installedApps(): List<InstalledApp> = emptyList()

        override suspend fun resolveApp(query: String, access: AppAccess): AppResolution = AppResolution.NotFound()

        override suspend fun discoveredActions(packageName: String): List<PluginAction> = emptyList()

        override fun unavailableReason(packageName: String, action: PluginAction): String? = null

        override suspend fun execute(request: ActionRequest, ctx: CallContext): ActionResult = ActionResult()
    }

    /** Tool factories only keep the context; nothing is called on it while building. */
    private val context: Context = object : ContextWrapper(null) {
        override fun getApplicationContext(): Context = this
    }

    @Test
    fun toolNamesEqualTheCoreCatalog() {
        val tools = AndroidToolCatalog.build(context, AppActionCatalog({ PluginCatalog.EMPTY }, NoApps) { null })
        val names = tools.map { it.name }
        assertEquals("duplicate tool names: $names", names.size, names.toSet().size)
        // Registered like BridgeGraph does: bridge_capabilities first, then the Android catalog.
        val registry = ToolRegistry()
        registry.register(capabilitiesTool(registry))
        registry.registerAll(tools)
        assertEquals(ToolNames.ALL, registry.all().map { it.name }.toSet())
    }

    @Test
    fun appActionToolsFollowTheAppTools() {
        val names = AndroidToolCatalog.build(context, AppActionCatalog({ PluginCatalog.EMPTY }, NoApps) { null }).map { it.name }
        val urlOpen = names.indexOf("url_open")
        assertEquals(listOf(ToolNames.APP_ACTIONS_LIST, ToolNames.APP_ACTION_RUN), names.subList(urlOpen + 1, urlOpen + 3))
    }
}
