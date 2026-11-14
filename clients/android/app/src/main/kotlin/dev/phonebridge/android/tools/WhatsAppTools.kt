package dev.phonebridge.android.tools

import android.content.Context
import android.os.SystemClock
import dev.phonebridge.android.access.BridgeAccessibilityService
import dev.phonebridge.android.access.TextNorm
import dev.phonebridge.android.tools.screen.UiAutomation
import dev.phonebridge.android.tools.support.DeviceState
import dev.phonebridge.android.tools.whatsapp.WhatsAppApps
import dev.phonebridge.android.tools.whatsapp.WhatsAppAutomation
import dev.phonebridge.android.tools.whatsapp.WhatsAppLauncher
import dev.phonebridge.android.tools.whatsapp.WhatsAppLinks
import dev.phonebridge.android.tools.whatsapp.WhatsAppTarget
import dev.phonebridge.android.tools.whatsapp.WhatsAppTargets
import dev.phonebridge.core.tools.Risk
import dev.phonebridge.core.tools.Scopes
import dev.phonebridge.core.tools.Tool
import dev.phonebridge.core.tools.ToolException
import dev.phonebridge.core.tools.ToolResult
import dev.phonebridge.core.tools.objectSchema
import dev.phonebridge.core.tools.optBool
import dev.phonebridge.core.tools.optString
import dev.phonebridge.core.tools.reqString
import dev.phonebridge.core.tools.tool
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.put

private const val OPEN_CHAT_OBSERVE_MS = 8_000L
private const val SEND_LOCK_WAIT_MS = 10_000L

/**
 * Tools: whatsapp_send, whatsapp_open_chat (docs/TOOLS.md). Sending taps Send through the
 * accessibility service (see [WhatsAppAutomation]) and leaves WhatsApp open on the chat.
 */
fun whatsAppTools(context: Context): List<Tool> = listOf(whatsAppSendTool(context), whatsAppOpenChatTool(context))

private fun whatsAppSendTool(context: Context): Tool = tool(
    name = "whatsapp_send",
    title = "Send WhatsApp message",
    description = "Sends a WhatsApp message from the user's WhatsApp to a phone number (to, international format " +
        "preferred, e.g. +972541234567) or a contact (contact: a name, resolved like contacts_search; if several " +
        "contacts match you get the candidates and nothing is sent). The user may have to approve on the phone; the " +
        "prompt shows the resolved name and number. With PhoneBridge accessibility on, it opens the chat, taps Send " +
        "and confirms the message went out; WhatsApp stays open on that chat afterwards. Without accessibility, the " +
        "chat opens with the text prefilled and the result has sent=false: the user must tap Send. Fails if the phone " +
        "is locked. business=true uses WhatsApp Business. To answer a message that just arrived (also while locked), " +
        "notification_reply is an alternative.",
    scope = Scopes.MESSAGES_SEND,
    risk = Risk.SENSITIVE,
    schema = objectSchema {
        string("to", "Phone number, international format preferred (e.g. +972541234567)")
        string("contact", "Contact name (instead of to)")
        string("message", "Message text", required = true)
        boolean("business", "Send from WhatsApp Business instead of WhatsApp")
    },
    timeoutMs = 45_000,
    confirmsInternally = true,
    unavailable = { WhatsAppLauncher.unavailableReason(context) },
    describe = { args ->
        "Send WhatsApp to ${args.optString("contact") ?: args.optString("to") ?: "?"}: \"${TextNorm.truncate(args.optString("message") ?: "", 200)}\""
    },
) { args, ctx ->
    val message = args.reqString("message")
    if (message.length > WhatsAppLinks.MAX_MESSAGE_CHARS) {
        throw ToolException("The message is too long (${message.length} characters, max ${WhatsAppLinks.MAX_MESSAGE_CHARS}). Split it into several messages.")
    }
    val business = args.optBool("business") ?: false
    val target = WhatsAppTargets.resolve(context, args.optString("to"), args.optString("contact"))
    val pkg = withContext(Dispatchers.IO) { WhatsAppLauncher.choosePackage(context, business) }
    // Don't ask the user to approve something that cannot run anyway.
    if (BridgeAccessibilityService.instance != null) requireUsableScreen(context)

    ctx.requireConfirmation(WhatsAppLinks.confirmationSummary(target.displayName, target.digits, message, pkg == WhatsAppApps.BUSINESS))

    val service = BridgeAccessibilityService.instance
    if (service == null) {
        WhatsAppLauncher.openChat(context, pkg, target.digits, message, clearTask = false)
        return@tool ToolResult.json {
            put("sent", false)
            put("opened", true)
            recipient(target, pkg)
            put("note", manualSendNote(context))
        }
    }
    requireUsableScreen(context)
    // Short lock wait: the whole send must finish well within timeoutMs, since a timeout after the
    // Send tap would leave the agent unsure whether to retry.
    UiAutomation.exclusive(waitMs = SEND_LOCK_WAIT_MS) { WhatsAppAutomation.send(context, service, pkg, target, message) }
    ToolResult.json {
        put("sent", true)
        recipient(target, pkg)
    }
}

private fun whatsAppOpenChatTool(context: Context): Tool = tool(
    name = "whatsapp_open_chat",
    title = "Open WhatsApp chat",
    description = "Opens the WhatsApp chat with a phone number (to) or contact (contact) on the phone, without " +
        "sending anything. business=true uses WhatsApp Business. With accessibility on (and the phone unlocked) it " +
        "also checks that the chat appeared (chat_open) and fails if the number is not on WhatsApp.",
    scope = Scopes.APPS,
    risk = Risk.ACTION,
    schema = objectSchema {
        string("to", "Phone number, international format preferred (e.g. +972541234567)")
        string("contact", "Contact name (instead of to)")
        boolean("business", "Use WhatsApp Business instead of WhatsApp")
    },
    timeoutMs = 30_000,
    unavailable = { WhatsAppLauncher.unavailableReason(context) },
    describe = { args -> "Open the WhatsApp chat with ${args.optString("contact") ?: args.optString("to") ?: "?"}" },
) { args, _ ->
    val business = args.optBool("business") ?: false
    val target = WhatsAppTargets.resolve(context, args.optString("to"), args.optString("contact"))
    val pkg = withContext(Dispatchers.IO) { WhatsAppLauncher.choosePackage(context, business) }
    val service = BridgeAccessibilityService.instance
    if (service == null || DeviceState.isLocked(context) || !DeviceState.isScreenOn(context)) {
        WhatsAppLauncher.openChat(context, pkg, target.digits, null, clearTask = false)
        val note = when {
            DeviceState.isLocked(context) || !DeviceState.isScreenOn(context) -> "The phone is locked or its screen is off: the chat shows once the user unlocks it."
            else -> WhatsAppLauncher.backgroundLaunchNote(context)
        }
        return@tool ToolResult.json {
            put("opened", true)
            recipient(target, pkg)
            note?.let { put("note", it) }
        }
    }
    UiAutomation.exclusive {
        val launchedAt = SystemClock.uptimeMillis()
        WhatsAppLauncher.openChat(context, pkg, target.digits, null, clearTask = false)
        val seen = WhatsAppAutomation.observeOpenedChat(context, service, pkg, target, launchedAt, OPEN_CHAT_OBSERVE_MS)
        ToolResult.json {
            put("opened", true)
            put("chat_open", seen.confirmed)
            recipient(target, pkg)
            seen.title?.let { put("chat_title", it) }
            seen.note?.let { put("note", it) }
        }
    }
}

/** `to`, `number` and `app` fields shared by both tools' results. */
private fun JsonObjectBuilder.recipient(target: WhatsAppTarget, pkg: String) {
    put("to", target.displayName)
    put("number", "+${target.digits}")
    put("app", pkg)
}

/** Automatic sending needs an unlocked phone with the screen on. */
private fun requireUsableScreen(context: Context) {
    DeviceState.requireUnlocked(context, "Sending a WhatsApp message")
    if (!DeviceState.isScreenOn(context)) {
        throw ToolException("The phone's screen is off. Ask the user to turn it on (and unlock it), then retry. Nothing was sent.")
    }
}

private fun manualSendNote(context: Context): String = buildString {
    append("The message was NOT sent. The PhoneBridge accessibility service is off, so WhatsApp was opened on the chat ")
    append("with the text prefilled and the user must tap Send. To send automatically, ask the user to enable it in ")
    append("PhoneBridge > Setup > Accessibility.")
    if (DeviceState.isLocked(context)) append(" The phone is locked: the chat shows once the user unlocks it.")
    WhatsAppLauncher.backgroundLaunchNote(context)?.let { append(' ').append(it) }
}
