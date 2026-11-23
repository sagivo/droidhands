package com.droidhands.android.tools

import android.content.Context
import android.content.ContextWrapper
import com.droidhands.android.Edition
import com.droidhands.core.mcp.capabilitiesTool
import com.droidhands.core.plugins.ActionRequest
import com.droidhands.core.plugins.ActionResult
import com.droidhands.core.plugins.AppActionCatalog
import com.droidhands.core.plugins.AppActionsPlatform
import com.droidhands.core.plugins.AppResolution
import com.droidhands.core.plugins.InstalledApp
import com.droidhands.core.plugins.PluginAction
import com.droidhands.core.plugins.PluginCatalog
import com.droidhands.core.tools.AppAccess
import com.droidhands.core.tools.CallContext
import com.droidhands.core.tools.Scopes
import com.droidhands.core.tools.ToolNames
import com.droidhands.core.tools.ToolRegistry
import org.junit.Assert.assertEquals
import org.junit.Test

/** The Android registry offers exactly the platform-neutral catalog of docs/TOOLS.md ([ToolNames.ALL]), less phone use in the store edition. */
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
        val tools = AndroidToolCatalog.build(context, AppActionCatalog({ PluginCatalog.EMPTY }, NoApps, builtInTool = { null }))
        val names = tools.map { it.name }
        assertEquals("duplicate tool names: $names", names.size, names.toSet().size)
        // Registered like BridgeGraph does: bridge_capabilities first, then the Android catalog.
        val registry = ToolRegistry()
        registry.register(capabilitiesTool(registry))
        registry.registerAll(tools)
        // The store edition leaves out phone use (docs/EDITIONS.md).
        val expected = if (Edition.PHONE_USE) ToolNames.ALL else ToolNames.ALL - ToolNames.PHONE_USE
        assertEquals(expected, registry.all().map { it.name }.toSet())
        assertEquals(Edition.PHONE_USE, Scopes.hasPhoneUse(registry))
    }

    @Test
    fun appActionToolsFollowTheAppTools() {
        val names = AndroidToolCatalog.build(context, AppActionCatalog({ PluginCatalog.EMPTY }, NoApps, builtInTool = { null })).map { it.name }
        val urlOpen = names.indexOf("url_open")
        assertEquals(listOf(ToolNames.APP_ACTIONS_LIST, ToolNames.APP_ACTION_RUN), names.subList(urlOpen + 1, urlOpen + 3))
    }
}
