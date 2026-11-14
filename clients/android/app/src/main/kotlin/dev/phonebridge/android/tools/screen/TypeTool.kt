package dev.phonebridge.android.tools.screen

import android.content.Context
import android.view.accessibility.AccessibilityNodeInfo
import dev.phonebridge.android.access.BridgeAccessibilityService
import dev.phonebridge.android.access.EditableChoice
import dev.phonebridge.android.access.NodeCapture
import dev.phonebridge.android.access.NodeQueries
import dev.phonebridge.android.access.TextNorm
import dev.phonebridge.android.access.TreeSerializer
import dev.phonebridge.android.access.UiAction
import dev.phonebridge.core.tools.Risk
import dev.phonebridge.core.tools.Scopes
import dev.phonebridge.core.tools.Tool
import dev.phonebridge.core.tools.ToolException
import dev.phonebridge.core.tools.ToolResult
import dev.phonebridge.core.tools.objectSchema
import dev.phonebridge.core.tools.optBool
import dev.phonebridge.core.tools.optString
import dev.phonebridge.core.tools.tool
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
        "user is asked to unlock it (up to 60 s). Refused in apps the user blocked for this agent.",
    scope = Scopes.SCREEN_CONTROL,
    risk = Risk.ACTION,
    schema = objectSchema {
        string("text", "Text to type (empty string clears the field)", required = true)
        string("ref", "Ref of the text field (default: the focused field)")
        boolean("append", "Add to the existing text instead of replacing it")
        boolean("submit", "Press the keyboard action key (IME enter) afterwards")
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
    UiAutomation.awaitUsable(context, ctx.tokenName, "type text on the screen")
    UiAutomation.exclusive {
        UiAutomation.requireUnlocked(context)
        UiAutomation.requireOperable(service, access)
        val field = withContext(Dispatchers.IO) { resolveField(context, service, access, args.optString("ref")?.trim()) }
        val node = field.fresh
        withContext(Dispatchers.IO) { ScreenGuard.checkNode(context, node, access) }
        val label = "${field.ref} ${TreeSerializer.label(node)}"
        if (append && node.password) {
            throw ToolException("Can't append to the password field $label (its content is hidden). Pass the full text with append=false.")
        }
        val existing = node.text?.takeUnless { it == node.hint }.orEmpty()
        val newText = if (append) existing + text else text
        val typed = withContext(Dispatchers.IO) {
            if (!node.focused) NodeActions.focus(node)
            NodeActions.setText(node, newText)
        }
        if (!typed) {
            throw ToolException("Field $label did not accept text. Tap it first (ui_tap), or check with screen_read that it is an [editable] field.")
        }
        var submitNote = ""
        if (submit) {
            delay(150)
            val ok = withContext(Dispatchers.IO) {
                val current = NodeActions.refresh(node) ?: node
                (UiAction.IME_ENTER in current.actions || current.focused) && NodeActions.imeEnter(current)
            }
            submitNote = if (ok) {
                " Pressed the keyboard action key."
            } else {
                " Submit FAILED: this field has no keyboard action; tap the app's Send/Search button instead (screen_find)."
            }
        }
        UiAutomation.settle()
        val shown = withContext(Dispatchers.IO) { NodeActions.refresh(node) }
        val shownNote = when {
            shown == null -> " The field is no longer on screen."
            node.password -> ""
            else -> " Field now shows: \"${TextNorm.truncate(TextNorm.clean(shown.text), 120)}\"."
        }
        ToolResult.text(UiAutomation.report(service, access, "Typed into $label.$shownNote$submitNote", settleFirst = false))
    }
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
        val fresh = NodeActions.refresh(inner) ?: throw ToolException("The text field inside ${live.ref} disappeared; call screen_read again.")
        return ScreenSession.Live(ScreenSession.refs.register(inner), inner, fresh)
    }
    val focused = try {
        service.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)?.let(NodeCapture::captureShallow)
    } catch (e: RuntimeException) {
        null
    }
    if (focused != null && focused.editable && access.allows(focused.packageName)) {
        return ScreenSession.Live(ScreenSession.refs.register(focused), focused, focused)
    }
    val state = ScreenCapture.capture(context, service, access.visibility)
    state.blockedForeground?.let { access.refuse(it) }
    return when (val choice = NodeQueries.chooseEditable(null, state.roots)) {
        is EditableChoice.Found -> {
            val fresh = NodeActions.refresh(choice.node) ?: throw ToolException("The text field disappeared; call screen_read again.")
            ScreenSession.Live(ScreenSession.refs.register(choice.node), choice.node, fresh)
        }
        is EditableChoice.Ambiguous -> throw ToolException(ambiguity("No field has input focus and there are", choice.candidates, noun = "text fields"))
        EditableChoice.None -> throw ToolException(
            "There is no text field on screen (app=${state.packageName ?: "unknown"}). Open the screen with the field first; screen_read shows [editable] elements.",
        )
    }
}
