package dev.phonebridge.android.tools

import android.Manifest
import android.content.Context
import dev.phonebridge.android.tools.calls.CallMonitor
import dev.phonebridge.android.tools.calls.CallState
import dev.phonebridge.android.tools.calls.CallStates
import dev.phonebridge.android.tools.calls.IncomingNumber
import dev.phonebridge.android.tools.comms.ContactNameCache
import dev.phonebridge.android.tools.support.Perms
import dev.phonebridge.android.tools.system.putIfNotNull
import dev.phonebridge.core.tools.Risk
import dev.phonebridge.core.tools.Scopes
import dev.phonebridge.core.tools.Tool
import dev.phonebridge.core.tools.ToolException
import dev.phonebridge.core.tools.ToolResult
import dev.phonebridge.core.tools.tool
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.put

/** How long Android gets to answer or end a call before the result is checked. */
private const val SETTLE_MS = 3_000L

private const val NO_VOICE =
    "The user talks on the phone itself: PhoneBridge can't hear or speak on calls."

/** Tools: call_status, call_answer, call_end (docs/TOOLS.md "Messages"). Telecom API: works while the phone is locked. */
fun callControlTools(context: Context): List<Tool> = listOf(callStatus(context), callAnswer(context), callEnd(context))

private fun callStatus(context: Context) = tool(
    name = "call_status",
    title = "Phone call status",
    description = "Whether the phone is idle, ringing (incoming call) or in_call: {state,incoming_number?,contact_name?," +
        "call_waiting?,app_call?,note?}. For a ringing call, incoming_number (needs the Phone & call log permission) and " +
        "contact_name (needs Contacts) are best effort: Android doesn't always reveal the number, and withheld numbers " +
        "never appear. call_waiting=true: a call is ringing while another is active. app_call=true: the call is in a " +
        "calling app (WhatsApp, Telegram…) rather than on the SIM. Act on calls with call_answer and call_end.",
    scope = Scopes.CALLS,
    risk = Risk.READ,
    timeoutMs = 10_000,
    unavailable = { Perms.unavailableIfMissing(context, Manifest.permission.READ_PHONE_STATE) },
) { _, _ ->
    Perms.require(context, Manifest.permission.READ_PHONE_STATE)
    val snapshot = CallMonitor(context).snapshot()
    val number = if (snapshot.state == CallState.RINGING) IncomingNumber.read(context) else null
    val name = number?.let { withContext(Dispatchers.IO) { ContactNameCache(context).nameFor(it) } }
    ToolResult.json {
        put("state", snapshot.state.arg)
        putIfNotNull("incoming_number", number)
        putIfNotNull("contact_name", name)
        if (snapshot.callWaiting) put("call_waiting", true)
        if (snapshot.appCall) put("app_call", true)
        putIfNotNull("note", CallStates.statusNote(snapshot, numberKnown = number != null, canReadNumber = IncomingNumber.canRead(context)))
    }
}

private fun callAnswer(context: Context) = tool(
    name = "call_answer",
    title = "Answer the ringing call",
    description = "Answers the incoming call that is ringing now, like tapping Answer; works while the phone is locked. " +
        "$NO_VOICE If another call is active (call_waiting), Android usually puts it on hold. Fails if nothing is ringing " +
        "(check call_status first). Android 15 hides call notifications' Answer button from PhoneBridge, so use this " +
        "rather than notification_action. The user may have to approve it on the phone.",
    scope = Scopes.CALLS,
    risk = Risk.SENSITIVE,
    timeoutMs = 15_000,
    unavailable = { Perms.unavailableIfMissing(context, Manifest.permission.ANSWER_PHONE_CALLS, Manifest.permission.READ_PHONE_STATE) },
    describe = { "Answer the ringing phone call" },
) { _, _ ->
    Perms.require(context, Manifest.permission.ANSWER_PHONE_CALLS, Manifest.permission.READ_PHONE_STATE)
    val monitor = CallMonitor(context)
    CallStates.requireRinging(monitor.snapshot())
    monitor.answer()
    val after = monitor.await(SETTLE_MS) { it.state != CallState.RINGING }
    CallStates.requireAnswered(after)
    ToolResult.json {
        put("answered", true)
        put("state", after.state.arg)
        if (after.appCall) put("app_call", true)
        put("note", "$NO_VOICE Use call_end to hang up.")
    }
}

private fun callEnd(context: Context) = tool(
    name = "call_end",
    title = "End or reject a call",
    description = "Hangs up the current call, or rejects the incoming call if one is ringing (during call waiting the " +
        "ringing call is rejected and the active one continues). Fails if there is no call. Emergency calls can't be " +
        "ended, and calls in calling apps (app_call=true in call_status) may have to be ended in that app.",
    scope = Scopes.CALLS,
    risk = Risk.ACTION,
    timeoutMs = 15_000,
    unavailable = { Perms.unavailableIfMissing(context, Manifest.permission.ANSWER_PHONE_CALLS, Manifest.permission.READ_PHONE_STATE) },
    describe = { "End the current phone call (or reject the ringing one)" },
) { _, _ ->
    Perms.require(context, Manifest.permission.ANSWER_PHONE_CALLS, Manifest.permission.READ_PHONE_STATE)
    val monitor = CallMonitor(context)
    val before = monitor.snapshot()
    CallStates.requireCall(before)
    if (!monitor.end()) throw ToolException(CallStates.endFailedMessage(before))
    val after = monitor.await(SETTLE_MS) { it != before }
    ToolResult.json {
        put("ended", true)
        if (before.state == CallState.RINGING) put("rejected", true)
        put("was", before.state.arg)
        put("state", after.state.arg)
        if (after == before) {
            put("note", "Android accepted the request, but the call still shows as ${after.state.arg}. Check again with call_status.")
        }
    }
}
