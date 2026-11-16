package dev.phonebridge.ios.apps

import dev.phonebridge.core.plugins.ActionRequest
import dev.phonebridge.core.plugins.ActionResult
import dev.phonebridge.core.plugins.AppActionsPlatform
import dev.phonebridge.core.plugins.AppResolution
import dev.phonebridge.core.plugins.DeviceOs
import dev.phonebridge.core.plugins.InstalledApp
import dev.phonebridge.core.plugins.IntegrationException
import dev.phonebridge.core.plugins.IntentRecipe
import dev.phonebridge.core.plugins.PluginAction
import dev.phonebridge.core.plugins.PluginRules
import dev.phonebridge.core.plugins.RecipeProbe
import dev.phonebridge.core.plugins.RecipeRenderer
import dev.phonebridge.core.plugins.RenderedIntent
import dev.phonebridge.core.plugins.RenderedSteps
import dev.phonebridge.core.plugins.RenderedToolCall
import dev.phonebridge.core.plugins.RenderedUrl
import dev.phonebridge.core.plugins.UrlRecipe
import dev.phonebridge.core.tools.AppAccess
import dev.phonebridge.core.tools.CallContext
import dev.phonebridge.core.tools.ToolException
import dev.phonebridge.ios.inventory.CachedInventory
import dev.phonebridge.ios.inventory.InventoryException
import dev.phonebridge.ios.inventory.IosApp
import dev.phonebridge.ios.runner.RunnerScreenDriver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * The iOS side of app actions (docs/IOS.md): installed apps from [inventory] (PhoneBridge's own runner left out),
 * names resolved like `app_open` ([AppMatcher]), discovered actions and `declares` from what each installed app
 * declares ([IosDeclarations]), links opened through [links] with app access before and after, and screen recipes run
 * by [runSteps] (core's [dev.phonebridge.core.screen.ScreenStepRunner] on the runner). Thread-safe.
 */
class IosAppActions(
    private val inventory: CachedInventory,
    private val links: LinkOpener,
    /** Null while the runner answers, else why it can't be used (fast; see [dev.phonebridge.ios.runner.RunnerMonitor]). */
    private val runnerProblem: () -> String?,
    private val runSteps: suspend (RenderedSteps, InstalledApp, CallContext) -> ActionResult,
    /** Bundle ids of PhoneBridge itself, besides those [isOwnBundle] recognizes. */
    private val ownBundles: Set<String> = emptySet(),
) : AppActionsPlatform {
    override val os: DeviceOs get() = DeviceOs.IOS

    /** Installed apps agents may know about, sorted by name. Blocking reads run on [Dispatchers.IO]. */
    suspend fun apps(): List<IosApp> = withContext(Dispatchers.IO) {
        try {
            inventory.apps().filter { !isOwn(it.bundleId) }
        } catch (e: InventoryException) {
            throw ToolException(e.message ?: "Couldn't list the iPhone's apps.")
        }
    }

    /** The installed app [bundleId], or null. */
    suspend fun app(bundleId: String): IosApp? = apps().firstOrNull { it.bundleId == bundleId }

    /** PhoneBridge's own bundles seen in the last inventory read, for the never-allow list. Never blocks. */
    fun ownBundlesSeen(): Set<String> = ownBundles + inventory.cached.orEmpty().map { it.bundleId }.filter(::isOwn)

    /** True for PhoneBridge's runner and any other bundle of its own. */
    fun isOwn(bundleId: String): Boolean = bundleId in ownBundles || isOwnBundle(bundleId)

    val limits get() = inventory.limits

    override suspend fun installedApps(): List<InstalledApp> = apps().map(::installed)

    override suspend fun resolveApp(query: String, access: AppAccess): AppResolution =
        AppMatcher.resolve(query, installedApps(), access::isAllowed)

    override suspend fun discoveredActions(packageName: String): List<PluginAction> =
        app(packageName)?.let(IosDeclarations::actions).orEmpty()

    override suspend fun declaredCapabilities(packageName: String): JsonObject? {
        val app = app(packageName) ?: return null
        return withContext(Dispatchers.IO) {
            val strings = runCatching { inventory.infoPlistStrings(app) }.getOrDefault(emptyMap())
            IosDeclarations.declared(app, inventory.limits, appIntentsFiles(app)) { strings[it] ?: it }
        }
    }

    /** Per app build and action: "" when the installed app declares the action's link, else why not. */
    private val linkChecks = ConcurrentHashMap<List<Any?>, String>()

    override fun unavailableReason(packageName: String, action: PluginAction): String? {
        runnerProblem()?.let { return it }
        return when (val recipe = action.recipeFor(DeviceOs.IOS)) {
            null -> "This action has no iPhone recipe (it was written for Android). Use phone use (screen_observe, ui_tap) instead."
            is IntentRecipe -> "Intent recipes run only on Android."
            is UrlRecipe -> {
                // Only checkable when the inventory reads Info.plist; otherwise the link is tried and the result says what opened.
                if (inventory.limits.infoPlist != null) return null
                val app = inventory.cached?.firstOrNull { it.bundleId == packageName } ?: return null
                linkChecks.getOrPut(listOf(packageName, app.version, app.build, action)) { checkLink(app, recipe, action) }.takeIf { it.isNotEmpty() }
            }
            else -> null
        }
    }

    private fun checkLink(app: IosApp, recipe: UrlRecipe, action: PluginAction): String = try {
        val rendered = RecipeRenderer(app.bundleId).render(recipe, RecipeProbe.sampleValues(action)) as RenderedUrl
        val link = IosLinks.check(rendered.url)
        if (IosLinks.declares(app, link)) "" else IosLinks.notDeclared(app, link) + " Use phone use (screen_observe, ui_tap) instead."
    } catch (e: Exception) {
        "" // Can't check (e.g. a link that needs real values): don't hide the action.
    }

    override suspend fun execute(request: ActionRequest, ctx: CallContext): ActionResult {
        val pkg = request.app.packageName
        return when (val recipe = request.recipe) {
            is RenderedUrl -> {
                if (recipe.packageName != pkg) throw ToolException(MISPINNED)
                val app = app(pkg) ?: throw ToolException("${request.app.label} is no longer installed on the iPhone.")
                val apps = apps()
                // Plugin links are tried even when the inventory can't read declarations: the app in front is checked after.
                val opened = links.open(IosLinks.check(recipe.url), app, apps, ctx.appAccess, ctx, verifyDeclared = inventory.limits.infoPlist == null)
                val front = opened.foreground
                if (front == RunnerScreenDriver.SPRINGBOARD) {
                    return ActionResult(
                        note = "The Home Screen is in front, not ${app.name}: iOS may be asking whether to open the link. Use screen_observe.",
                    )
                }
                if (front != null && front != pkg) {
                    val label = apps.firstOrNull { it.bundleId == front }?.name ?: front
                    throw IntegrationException(
                        "iOS opened the link in $label ($front), not in ${app.name}: the installed ${app.name} didn't take it. " +
                            "Use phone use (screen_observe, ui_tap) instead.",
                    )
                }
                ActionResult(
                    fields = buildJsonObject { front?.let { put("handled_by", it) } },
                    note = if (front == null) "The runner couldn't tell which app is in front; check with screen_observe." else null,
                )
            }
            is RenderedSteps -> {
                if (recipe.packageName != pkg) throw ToolException(MISPINNED)
                runSteps(recipe, request.app, ctx)
            }
            is RenderedIntent -> throw ToolException("Intent recipes run only on Android.")
            is RenderedToolCall -> throw ToolException("Internal error: '${request.action.id}' runs as the ${recipe.name} tool.")
        }
    }

    /** The App Intents metadata of [app]'s bundle and its extensions, when this Mac can read the bundle. */
    private fun appIntentsFiles(app: IosApp): List<ByteArray> {
        val dir = app.bundleDir ?: return emptyList()
        val bundles = listOf(dir) + listOf("PlugIns", "Extensions").flatMap { sub ->
            File(dir, sub).listFiles().orEmpty().filter { it.isDirectory && it.name.endsWith(".appex") }.sortedBy { it.name }
        }
        return bundles.mapNotNull { b ->
            File(b, IosDeclarations.APP_INTENTS_FILE).takeIf { it.isFile && it.length() in 1..MAX_METADATA_BYTES }?.let { f ->
                runCatching { f.readBytes() }.getOrNull()
            }
        }
    }

    companion object {
        private const val MAX_METADATA_BYTES = 4L * 1024 * 1024
        private const val MISPINNED = "Internal error: the action's recipe is not pinned to its app, so nothing was done."

        /**
         * PhoneBridge's own bundles: ids under `dev.phonebridge.` (PluginRules.BRIDGE_PACKAGE_PREFIX), and Xcode's UI test
         * runner apps (`*.xctrunner`), which host the runner and are never apps a user opens.
         */
        fun isOwnBundle(bundleId: String): Boolean =
            bundleId.startsWith(PluginRules.BRIDGE_PACKAGE_PREFIX + ".") || bundleId.endsWith(".xctrunner")

        fun installed(app: IosApp): InstalledApp {
            val numericBuild = app.build?.toLongOrNull()
            // Health records are kept per installed build: name a non-numeric build next to the version.
            val version = when {
                app.build == null || numericBuild != null || app.build == app.version -> app.version
                app.version == null -> app.build
                else -> "${app.version} (${app.build})"
            }
            return InstalledApp(app.bundleId, app.name, version, app.system, numericBuild)
        }
    }
}
