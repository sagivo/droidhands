package com.droidhands.android.tools.actions

import com.droidhands.core.plugins.ExtraValue
import com.droidhands.core.plugins.IntentRecipe
import com.droidhands.core.plugins.ParamValues
import com.droidhands.core.plugins.PluginAction
import com.droidhands.core.plugins.PluginRules
import com.droidhands.core.plugins.RecipeRenderer
import com.droidhands.core.plugins.RenderedIntent
import com.droidhands.core.tools.Risk
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DiscoveredActionsTest {
    private val pkg = "com.example.mail"
    private val exported = setOf("$pkg.Compose", "$pkg.Search", "$pkg.Inbox", "$pkg.Settings")
    private val isExported: (String) -> Boolean = { it in exported }

    private fun render(action: PluginAction, vararg params: Pair<String, String>): RenderedIntent {
        val values = ParamValues.validate(action.params, JsonObject(params.associate { (k, v) -> k to JsonPrimitive(v) }))
        return RecipeRenderer(pkg).render(action.android!!, values) as RenderedIntent
    }

    private fun shortcut(
        id: String = "compose",
        label: String? = "Compose",
        action: String = "android.intent.action.VIEW",
        targetPackage: String? = pkg,
        targetClass: String? = "$pkg.Compose",
        data: String? = null,
        extras: Map<String, String> = emptyMap(),
        enabled: Boolean = true,
    ) = DeclaredShortcut(id, enabled, label, null, DeclaredIntent(action, targetPackage, targetClass, data, extras = extras))

    @Test
    fun standardActionsInOrderAndRenderable() {
        val handlers = StandardHandlers(
            share = IntentHandler("$pkg.Share"),
            processText = IntentHandler("$pkg.Translate", "Translate"),
            playFromSearch = IntentHandler("$pkg.Play"),
            webSearch = IntentHandler("$pkg.WebSearch"),
            geo = IntentHandler("$pkg.Map"),
        )
        val actions = DiscoveredActions.build("Mail", pkg, "$pkg.Main", handlers, null, isExported = isExported)
        assertEquals(listOf("open", "share_text", "process_text", "play_from_search", "web_search", "show_on_map"), actions.map { it.id })
        assertTrue(actions.all { PluginRules.ACTION_ID.matches(it.id) && it.risk == Risk.ACTION && it.android is IntentRecipe })

        val open = render(actions[0])
        assertEquals("android.intent.action.MAIN", open.action)
        assertEquals(listOf("android.intent.category.LAUNCHER"), open.categories)
        assertEquals("$pkg.Main", open.className)
        assertTrue(actions[0].completesTask)

        val share = render(actions[1], "text" to "hello there")
        assertEquals("android.intent.action.SEND", share.action)
        assertEquals("text/plain", share.type)
        assertEquals("$pkg.Share", share.className)
        assertEquals(mapOf("android.intent.extra.TEXT" to ExtraValue.StringValue("hello there")), share.extras)
        assertFalse("share only opens the share screen", actions[1].completesTask)

        assertEquals("Translate text", actions[2].title)
        val translate = render(actions[2], "text" to "bonjour")
        assertEquals(ExtraValue.StringValue("bonjour"), translate.extras["android.intent.extra.PROCESS_TEXT"])
        assertEquals(ExtraValue.BooleanValue(true), translate.extras["android.intent.extra.PROCESS_TEXT_READONLY"])

        val play = render(actions[3], "query" to "lofi beats")
        assertEquals("android.media.action.MEDIA_PLAY_FROM_SEARCH", play.action)
        assertEquals(ExtraValue.StringValue("lofi beats"), play.extras["query"])

        assertEquals("android.intent.action.WEB_SEARCH", render(actions[4], "query" to "x").action)
        assertEquals("geo:0,0?q=Eiffel%20Tower", render(actions[5], "query" to "Eiffel Tower").data)
    }

    @Test
    fun processTextWithTheAppsOwnLabelGetsAGenericTitle() {
        val action = DiscoveredActions.processText("Mail", IntentHandler("$pkg.P", "Mail"))
        assertEquals("Process text", action.title)
    }

    @Test
    fun noLauncherNoOpen() {
        assertTrue(DiscoveredActions.build("Mail", pkg, null, StandardHandlers(), null, isExported = isExported).isEmpty())
    }

    @Test
    fun shortcutsKeepOnlySafeTargetsInTheApp() {
        val ok = DiscoveredActions.shortcut("Mail", pkg, shortcut(id = "newMessage", label = "New message"), isExported)
        assertNotNull(ok)
        assertEquals("shortcut_new_message", ok!!.id)
        assertEquals("New message", ok.title)
        assertEquals(Risk.ACTION, ok.risk)
        assertEquals("$pkg.Compose", render(ok).className)

        fun dropped(s: DeclaredShortcut) = assertNull(s.toString(), DiscoveredActions.shortcut("Mail", pkg, s, isExported))
        dropped(shortcut(enabled = false))
        dropped(shortcut(action = "android.intent.action.CALL"))
        dropped(shortcut(targetPackage = "com.other.app"))
        dropped(shortcut(targetClass = "$pkg.Hidden")) // not exported
        dropped(shortcut(targetClass = null))
        dropped(shortcut(data = "content://contacts/1"))
        dropped(shortcut(data = "no-scheme"))
        dropped(shortcut(extras = mapOf("x" to "{{text}}")))
        dropped(DeclaredShortcut("x", true, "X", null, null))
        dropped(shortcut(id = "1234"))
    }

    @Test
    fun shortcutRelativeClassDataAndExtrasAreKept() {
        val action = DiscoveredActions.shortcut(
            "Mail",
            pkg,
            shortcut(targetClass = ".Inbox", data = "mail://inbox?folder=all", extras = mapOf("folder" to "inbox")),
            isExported,
        )!!
        val rendered = render(action)
        assertEquals("$pkg.Inbox", rendered.className)
        assertEquals("mail://inbox?folder=all", rendered.data)
        assertEquals(mapOf("folder" to ExtraValue.StringValue("inbox")), rendered.extras)
    }

    @Test
    fun shortcutsThatSoundLikeSendingAreSensitive() {
        val action = DiscoveredActions.shortcut("Bank", pkg, shortcut(id = "money", label = "Send money"), isExported)!!
        assertEquals(Risk.SENSITIVE, action.risk)
        assertEquals("shortcut_money", action.id)
    }

    @Test
    fun capabilityWithUrlTemplate() {
        val capability = DeclaredCapability(
            "actions.intent.GET_THING",
            listOf(DeclaredIntent(targetPackage = pkg, targetClass = "$pkg.Search", urlTemplate = "mail://search{?q}", parameters = listOf(DeclaredParameter("thing.name", "q", required = true)))),
        )
        val action = DiscoveredActions.capability("Mail", pkg, capability, isExported)!!
        assertEquals("get_thing", action.id)
        assertEquals("Get thing", action.title)
        assertEquals(Risk.ACTION, action.risk)
        assertEquals(listOf("q"), action.params.map { it.name })
        assertTrue(action.params.single().required)
        val rendered = render(action, "q" to "hello world")
        assertEquals("android.intent.action.VIEW", rendered.action)
        assertEquals("mail://search?q=hello%20world", rendered.data)
        assertEquals("$pkg.Search", rendered.className)
    }

    @Test
    fun capabilityWithExtrasIsSensitiveForMessaging() {
        val capability = DeclaredCapability(
            "actions.intent.CREATE_MESSAGE",
            listOf(
                DeclaredIntent(targetClass = "$pkg.Hidden"), // not exported: the next intent is used
                DeclaredIntent(
                    action = "android.intent.action.SEND",
                    targetPackage = pkg,
                    targetClass = "$pkg.Compose",
                    parameters = listOf(DeclaredParameter("message.recipient.name", "recipientName"), DeclaredParameter("message.text", "messageText")),
                ),
            ),
        )
        val action = DiscoveredActions.capability("Mail", pkg, capability, isExported)!!
        assertEquals("create_message", action.id)
        assertEquals(Risk.SENSITIVE, action.risk)
        assertEquals(listOf("recipient_name", "message_text"), action.params.map { it.name })
        val rendered = render(action, "message_text" to "hi")
        assertEquals("android.intent.action.SEND", rendered.action)
        assertEquals(mapOf("messageText" to ExtraValue.StringValue("hi")), rendered.extras) // missing optional: left out
    }

    @Test
    fun capabilitiesWithoutASafeRecipeAreDropped() {
        fun none(vararg intents: DeclaredIntent, name: String = "actions.intent.GET_THING") =
            assertNull(DiscoveredActions.capability("Mail", pkg, DeclaredCapability(name, intents.toList()), isExported))
        none()
        none(DeclaredIntent(targetClass = "$pkg.Hidden"))
        none(DeclaredIntent(urlTemplate = "{@url}"))
        none(DeclaredIntent(urlTemplate = "{+base}/x"))
        none(DeclaredIntent(urlTemplate = "javascript:{q}"))
        none(DeclaredIntent(action = "android.intent.action.CALL", targetClass = "$pkg.Compose"))
        none(DeclaredIntent(targetPackage = "com.other", targetClass = "com.other.X"))
        none(DeclaredIntent(targetClass = "$pkg.Compose"), name = "actions.intent.123")
    }

    @Test
    fun urlTemplateVariablesWithoutParametersBecomeParameters() {
        val capability = DeclaredCapability("custom.actions.intent.OPEN_FEATURE", listOf(DeclaredIntent(urlTemplate = "mail://feature/{featureName}{?ref}")))
        val action = DiscoveredActions.capability("Mail", pkg, capability, isExported)!!
        assertEquals("open_feature", action.id)
        assertEquals(listOf("feature_name", "ref"), action.params.map { it.name })
        assertNull("no class: resolved within the app at run time", (action.android as IntentRecipe).className)
        assertEquals("mail://feature/inbox?ref=", render(action, "feature_name" to "inbox").data)
    }

    @Test
    fun rfc6570Conversion() {
        val names = mapOf("q" to "q", "a" to "a", "b" to "b", "thing.name" to "thing_name")
        fun convert(t: String) = DiscoveredActions.urlTemplate(t) { names[it] }
        assertEquals("app://s/{{q}}", convert("app://s/{q}"))
        assertEquals("app://s?a={{a}}&b={{b}}", convert("app://s{?a,b}"))
        assertEquals("app://s?x=1&a={{a}}", convert("app://s?x=1{&a}"))
        assertEquals("app://s?thing.name={{thing_name}}", convert("app://s{?thing.name}"))
        assertNull(convert("app://s/{+q}"))
        assertNull(convert("app://s/{#q}"))
        assertNull(convert("app://s/{unknown}"))
        assertNull(convert("app://s/{q"))
        assertNull(convert("app://s/q}"))
        assertEquals(listOf("a", "b", "q"), DiscoveredActions.templateVariables("x{?a,b}/{q}"))
    }

    @Test
    fun ids() {
        assertEquals("new_message", DiscoveredActions.sanitizeId("newMessage"))
        assertEquals("get_thing", DiscoveredActions.sanitizeId("GET_THING"))
        assertEquals("compose_v2", DiscoveredActions.sanitizeId("  compose-v2  "))
        assertEquals("a", DiscoveredActions.sanitizeId("1a"))
        assertNull(DiscoveredActions.sanitizeId("123"))
        assertNull(DiscoveredActions.sanitizeId("שלום"))
        assertEquals(40, DiscoveredActions.sanitizeId("x".repeat(80))!!.length)
    }

    @Test
    fun sensitiveCapabilities() {
        listOf(
            "actions.intent.CREATE_MESSAGE", "actions.intent.CREATE_CALL", "actions.intent.ORDER_MENU_ITEM",
            "actions.intent.CREATE_TAXI_RESERVATION", "actions.intent.CREATE_MONEY_TRANSFER", "actions.intent.CREATE_RESERVATION",
            "custom.actions.intent.PAY_BILL", "actions.intent.GET_ORDER",
        ).forEach { assertTrue(it, DiscoveredActions.isSensitiveCapability(it)) }
        listOf("actions.intent.GET_THING", "actions.intent.START_EXERCISE", "actions.intent.OPEN_APP_FEATURE", "actions.intent.GET_NEWS_ARTICLE")
            .forEach { assertFalse(it, DiscoveredActions.isSensitiveCapability(it)) }
    }

    @Test
    fun capabilityAndShortcutIdsNeverReplaceEarlierOnes() {
        val declared = ShortcutsDeclaration(
            shortcuts = listOf(shortcut(id = "compose"), shortcut(id = "compose", label = "Duplicate")),
            capabilities = listOf(DeclaredCapability("actions.intent.OPEN", listOf(DeclaredIntent(targetClass = "$pkg.Settings")))),
        )
        val actions = DiscoveredActions.build("Mail", pkg, "$pkg.Main", StandardHandlers(), declared, isExported = isExported)
        assertEquals(listOf("open", "shortcut_compose"), actions.map { it.id })
        assertEquals("Open the app", actions[0].title)
        assertEquals("Compose", actions[1].title)
    }
}
