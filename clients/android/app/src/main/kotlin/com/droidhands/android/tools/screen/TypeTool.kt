package com.droidhands.android.tools.screen

import android.content.Context
import android.view.accessibility.AccessibilityNodeInfo
import com.droidhands.android.access.BridgeAccessibilityService
import com.droidhands.android.access.EditableChoice
import com.droidhands.android.access.NodeCapture
import com.droidhands.android.access.NodeQueries
import com.droidhands.android.access.TextNorm
import com.droidhands.android.access.TreeSerializer
import com.droidhands.android.access.UiAction
import com.droidhands.core.tools.Risk
import com.droidhands.core.tools.Scopes
import com.droidhands.core.tools.Tool
import com.droidhands.core.tools.ToolException
import com.droidhands.core.tools.ToolResult
import com.droidhands.core.tools.objectSchema
import com.droidhands.core.tools.optBool
import com.droidhands.core.tools.optString
import com.droidhands.core.tools.tool
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** ui_type: sets the text of a field (focused one by default). */
internal fun uiTypeTool(context: Context): Tool = tool(
    name = "ui_type",
    title = "Type text",
    description = "Types text into a text field in any app (replaces its content; append=true adds to it). Target: " +
        "ref of an [editable] element, else the field that has input focus, else the only text field on screen. " +
        "An empty text clears the field. submit=true then presses the keyboard's action key (send/search/go). " +
        "Returns what the field shows afterwards. To tap a Send button instead, use ui_tap. If the phone is locked, the " +
        "user is asked to unlock it (up to 60 s). Refused in apps the user blocked for this agent. ${UiAutomation.APPROVAL_PAUSE}",
    scope = Scopes.SCREEN_CONTROL,
    risk = Risk.ACTION,
    schema = objectSchema {
        string("text", "Text to type (empty string clears the field)", required = true)
        string("ref", "Ref of the text field (default: the focused field)")
        boolean("append", "Add to the existing text instead of replacing it")
        boolean("submit", "Press the keyboard action key (IME enter) afterwards")
        boolean("observe", UiAutomation.OBSERVE_ARG)
    },
    timeoutMs = 30_000 + UiAutomation.UNLOCK_WAIT_MS,
    unavailable = { UiAutomation.actionUnavailableReason(context) },
    describe = { args -> "Type \"${TextNorm.truncate(rawText(args) ?: "", 80)}\" into ${args.optString("ref") ?: "the focused field"}" },
) { args, ctx ->
    val text = rawText(args) ?: throw ToolException("Missing required argument 'text'")
    val append = args.optBool("append") ?: false
    val submit = args.optBool("submit") ?: false
    val service = UiAutomation.service()
    val access = ScreenAccess.of(context, ctx)
    UiAutomation.awaitUsable(context, ctx, "type text on the screen")
    val message = UiAutomation.exclusive(access) {
        UiAutomation.requireUnlocked(context)
        UiAutomation.requireOperable(service, access)
        val field = withContext(Dispatchers.IO) { resolveField(context, service, access, args.optString("ref")?.trim()) }
        // Submitting can change the screen (a search runs, a message goes out): report what it did.
        val before = if (submit) UiAutomation.before(context, service, access) else null
        val typed = typeInto(context, access, field, text, append, submit)
        UiAutomation.report(service, access, typed.message, settleFirst = false, before = before)
    }
    UiAutomation.result(context, ctx, args.optBool("observe") ?: false, message)
}

/** What [typeInto] did: [message] for the agent; [submitted] whether the keyboard action key was pressed (null: not asked). */
internal class TypeOutcome(val message: String, val submitted: Boolean?)

/**
 * Sets [field]'s text to [text] (after its current text when [append]) and, if [submit], presses the keyboard's
 * action key; then settles and reads the field back. Shared by ui_type and app-action screen recipes. The field is
 * checked with [ScreenGuard] and [UiAutomation.beforeAction] runs right before each node action. The caller holds
 * the UI mutex ([UiAutomation.exclusive]).
 */
internal suspend fun typeInto(
    context: Context,
    access: ScreenAccess,
    field: ScreenSession.Live,
    text: String,
    append: Boolean,
    submit: Boolean,
): TypeOutcome {
    val node = field.fresh
    withContext(Dispatchers.IO) { ScreenGuard.checkNode(context, node, access) }
    val label = "${field.ref} ${TreeSerializer.label(node)}"
    if (append && node.password) {
        throw ToolException("Can't append to the password field $label (its content is hidden). Pass the full text with append=false.")
    }
    // Appending needs the real content: the agent's copy of the node may show one-time codes masked.
    val existing = if (append) {
        val raw = withContext(Dispatchers.IO) { NodeActions.refresh(node) }
            ?: throw ToolException("Field $label is no longer on screen; call screen_read again.")
        raw.text?.takeUnless { it == raw.hint }.orEmpty()
    } else {
        ""
    }
    val newText = existing + text
    UiAutomation.beforeAction(access)
    val typed = withContext(Dispatchers.IO) {
        if (!node.focused) NodeActions.focus(node)
        NodeActions.setText(node, newText)
    }
    if (!typed) {
        throw ToolException("Field $label did not accept text. Tap it first (ui_tap), or check with screen_read that it is an [editable] field.")
    }
    var submitNote = ""
    var submitted: Boolean? = null
    if (submit) {
        delay(150)
        UiAutomation.beforeAction(access)
        val ok = withContext(Dispatchers.IO) {
            val current = NodeActions.refresh(node) ?: node
            (UiAction.IME_ENTER in current.actions || current.focused) && NodeActions.imeEnter(current)
        }
        submitted = ok
        submitNote = if (ok) {
            " Pressed the keyboard action key."
        } else {
            " Submit FAILED: this field has no keyboard action; tap the app's Send/Search button instead (screen_find)."
        }
    }
    UiAutomation.settle()
    val shown = withContext(Dispatchers.IO) { ScreenSession.refresh(node, access) }
    val shownNote = when {
        shown == null -> " The field is no longer on screen."
        node.password -> ""
        else -> " Field now shows: \"${TextNorm.truncate(TextNorm.clean(shown.text), 120)}\"."
    }
    return TypeOutcome("Typed into $label.$shownNote$submitNote", submitted)
}

/** `text` allowing the empty string (clears the field); null when absent. */
private fun rawText(args: JsonObject): String? = (args["text"] as? JsonPrimitive)?.takeIf { it.isString }?.content
    ?: args.optString("text")

/** Picks the field: ref (or an editable inside it), else input focus, else the only editable on screen. */
private suspend fun resolveField(context: Context, service: BridgeAccessibilityService, access: ScreenAccess, ref: String?): ScreenSession.Live {
    if (ref != null) {
        val live = ScreenSession.resolve(ref, access)
        if (live.fresh.editable) return live
        val inner = NodeQueries.editableWithin(live.node)
            ?: throw ToolException("Element ${live.ref} (${TreeSerializer.label(live.fresh)}) is not a text field. Pass the ref of an [editable] element.")
        val fresh = ScreenSession.refresh(inner, access) ?: throw ToolException("The text field inside ${live.ref} disappeared; call screen_read again.")
        return ScreenSession.Live(ScreenSession.refs(access).register(inner), inner, fresh)
    }
    val focused = try {
        service.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)?.let(NodeCapture::captureShallow)?.let(access::redact)
    } catch (e: RuntimeException) {
        null
    }
    if (focused != null && focused.editable && access.allows(focused.packageName)) {
        return ScreenSession.Live(ScreenSession.refs(access).register(focused), focused, focused)
    }
    val state = ScreenCapture.capture(context, service, access)
    state.blockedForeground?.let { access.refuse(it) }
    return when (val choice = NodeQueries.chooseEditable(null, state.roots)) {
        is EditableChoice.Found -> {
            val fresh = ScreenSession.refresh(choice.node, access) ?: throw ToolException("The text field disappeared; call screen_read again.")
            ScreenSession.Live(ScreenSession.refs(access).register(choice.node), choice.node, fresh)
        }
        is EditableChoice.Ambiguous ->
            throw ToolException(ambiguity(access, "No field has input focus and there are", choice.candidates, noun = "text fields"))
        EditableChoice.None -> throw ToolException(
            "There is no text field on screen (app=${state.packageName ?: "unknown"}). Open the screen with the field first; screen_read shows [editable] elements.",
        )
    }
}
