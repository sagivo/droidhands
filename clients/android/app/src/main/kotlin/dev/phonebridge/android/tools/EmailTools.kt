package dev.phonebridge.android.tools

import android.content.Context
import dev.phonebridge.android.access.BridgeAccessibilityService
import dev.phonebridge.android.access.TextNorm
import dev.phonebridge.android.notify.AppLabels
import dev.phonebridge.android.tools.email.EmailAddresses
import dev.phonebridge.android.tools.email.EmailAutomation
import dev.phonebridge.android.tools.email.EmailDraft
import dev.phonebridge.android.tools.email.EmailLauncher
import dev.phonebridge.android.tools.email.EmailRecipients
import dev.phonebridge.android.tools.screen.ScreenAccess
import dev.phonebridge.android.tools.screen.UiAutomation
import dev.phonebridge.android.tools.support.DeviceState
import dev.phonebridge.core.tools.Risk
import dev.phonebridge.core.tools.Scopes
import dev.phonebridge.core.tools.Tool
import dev.phonebridge.core.tools.ToolException
import dev.phonebridge.core.tools.ToolResult
import dev.phonebridge.core.tools.objectSchema
import dev.phonebridge.core.tools.optString
import dev.phonebridge.core.tools.reqString
import dev.phonebridge.core.tools.tool
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.put

private const val SEND_LOCK_WAIT_MS = 10_000L
private const val SEND_WORK_MS = 45_000L

/**
 * Tools: email_send (docs/TOOLS.md). Sends through the user's own mail app: opens its compose screen filled in,
 * then (with the accessibility service) verifies the fields and taps Send (see [EmailAutomation]).
 */
fun emailTools(context: Context): List<Tool> = listOf(emailSendTool(context))

private fun emailSendTool(context: Context): Tool = tool(
    name = "email_send",
    title = "Send email",
    description = "Sends an email from the user's own email app. Recipients: to (email addresses, comma-separated) or " +
        "contact (a name, resolved to that contact's email address like contacts_search; if several contacts or " +
        "addresses fit you get the candidates and nothing is sent). cc: more addresses. body is required, subject " +
        "optional. app: package of the email app (default: the phone's default email app, preferring Gmail). The user " +
        "may have to approve on the phone; the prompt shows the resolved recipients. With PhoneBridge accessibility on, " +
        "it opens the app's compose screen filled in, checks the fields and taps Send, and returns sent=true once the " +
        "email left; if the phone is locked, the user is asked to unlock it (up to 60 s). Without accessibility, the " +
        "compose screen is left open and the result has sent=false: the user must tap Send. To reply to a received " +
        "email, prefer notification_reply on the mail notification.",
    scope = Scopes.MESSAGES_SEND,
    risk = Risk.SENSITIVE,
    schema = objectSchema {
        string("to", "Recipient email address(es), comma-separated")
        string("contact", "Contact name (instead of to), resolved to the contact's email address")
        string("cc", "Cc email address(es), comma-separated")
        string("subject", "Subject line")
        string("body", "Email text", required = true)
        string("app", "Package of the email app to use, e.g. com.google.android.gm (default: the default email app, preferring Gmail)")
    },
    timeoutMs = SEND_WORK_MS + UiAutomation.UNLOCK_WAIT_MS,
    confirmsInternally = true,
    unavailable = { EmailLauncher.unavailableReason(context) },
    describe = { args ->
        val who = args.optString("contact") ?: args.optString("to") ?: "?"
        "Send email to $who: \"${args.optString("subject").orEmpty()}\" — ${TextNorm.truncate(args.optString("body").orEmpty(), EmailDraft.BODY_PREVIEW_CHARS)}"
    },
) { args, ctx ->
    val draft = draftFrom(context, args)
    val pkg = withContext(Dispatchers.IO) { EmailLauncher.chooseApp(context, args.optString("app")) }
    val label = withContext(Dispatchers.IO) { AppLabels.of(context, pkg) }
    ctx.appAccess.require(pkg, label)
    // Don't ask the user to approve something that cannot run anyway.
    if (BridgeAccessibilityService.instance != null) {
        BridgeAccessibilityService.healthCached()?.let { throw ToolException("$it Nothing was sent.") }
    }

    ctx.requireConfirmation(draft.confirmationSummary())

    val service = BridgeAccessibilityService.instance
    if (service == null) {
        EmailLauncher.open(context, pkg, draft)
        return@tool ToolResult.json {
            put("sent", false)
            put("opened", true)
            summary(draft, pkg)
            put("note", manualSendNote(context, label))
        }
    }
    UiAutomation.awaitUsable(context, ctx.tokenName, draft.unlockAction(), "Nothing was sent.")
    val access = withContext(Dispatchers.IO) { ScreenAccess.of(context, ctx) }
    // Short lock wait: the whole send must finish well within timeoutMs, since a timeout after the Send tap
    // would leave the agent unsure whether to retry.
    UiAutomation.exclusive(waitMs = SEND_LOCK_WAIT_MS) {
        if (!UiAutomation.isUsable(context)) {
            throw ToolException("The phone was locked again before sending. Nothing was sent. Ask the user to keep it unlocked, then retry.")
        }
        EmailAutomation.send(context, service, access.visibility, pkg, label, draft)
    }
    ToolResult.json {
        put("sent", true)
        summary(draft, pkg)
    }
}

/** Validates the arguments and resolves recipients (contacts on Dispatchers.IO). */
private suspend fun draftFrom(context: Context, args: JsonObject): EmailDraft {
    val body = args.reqString("body")
    if (body.length > EmailDraft.MAX_BODY_CHARS) {
        throw ToolException("The body is too long (${body.length} characters, max ${EmailDraft.MAX_BODY_CHARS}). Shorten it or split it into several emails.")
    }
    val subject = EmailDraft.cleanSubject(args.optString("subject"))
    if (subject.length > EmailDraft.MAX_SUBJECT_CHARS) throw ToolException("The subject is too long (max ${EmailDraft.MAX_SUBJECT_CHARS} characters).")
    val to = EmailRecipients.resolve(context, addressArg(args, "to"), args.optString("contact"))
    val toKeys = to.map { it.key }.toSet()
    val cc = addressArg(args, "cc")?.let { EmailAddresses.parseList(it, "cc") }.orEmpty().filter { it.key !in toKeys }
    if (to.size + cc.size > EmailAddresses.MAX_RECIPIENTS) {
        throw ToolException("Too many recipients (${to.size + cc.size}, max ${EmailAddresses.MAX_RECIPIENTS}).")
    }
    return EmailDraft(to, cc, subject, body)
}

/** A comma-separated string, or (leniently) a JSON array of addresses joined with commas. */
private fun addressArg(args: JsonObject, key: String): String? = when (val v = args[key]) {
    is JsonArray -> v.mapNotNull { (it as? JsonPrimitive)?.content?.trim()?.takeIf(String::isNotEmpty) }.joinToString(", ").ifEmpty { null }
    else -> args.optString(key)?.trim()?.takeIf { it.isNotEmpty() }
}

/** `to`, `cc`, `subject` and `app` fields of both results. */
private fun JsonObjectBuilder.summary(draft: EmailDraft, pkg: String) {
    put("to", draft.toText)
    if (draft.cc.isNotEmpty()) put("cc", draft.ccText)
    put("subject", draft.subject)
    put("app", pkg)
}

private fun manualSendNote(context: Context, label: String): String = buildString {
    append("Not sent — the user must tap Send. The PhoneBridge accessibility service is off, so $label was opened with ")
    append("the email filled in. To send automatically, ask the user to enable it in PhoneBridge > Setup > Accessibility.")
    if (DeviceState.isLocked(context)) append(" The phone is locked: the compose screen shows once the user unlocks it.")
    EmailLauncher.backgroundLaunchNote(context)?.let { append(' ').append(it) }
}
