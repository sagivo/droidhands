package com.droidhands.android.tools

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.provider.AlarmClock
import com.droidhands.android.tools.support.onMain
import com.droidhands.android.tools.system.AudioControl
import com.droidhands.android.tools.system.CalendarTime
import com.droidhands.android.tools.system.ClockIntents
import com.droidhands.android.tools.system.ClockLogic
import com.droidhands.android.tools.system.DeviceStatusReader
import com.droidhands.android.tools.system.Flashlight
import com.droidhands.android.tools.system.LaunchNotes
import com.droidhands.android.tools.system.MediaAction
import com.droidhands.android.tools.system.RingerModeName
import com.droidhands.android.tools.system.Speech
import com.droidhands.android.tools.system.SpeechLogic
import com.droidhands.android.tools.system.VolumeMath
import com.droidhands.android.tools.system.VolumeStream
import com.droidhands.android.tools.system.describeOr
import com.droidhands.android.tools.system.putIfNotNull
import com.droidhands.android.tools.system.quoteForSummary
import com.droidhands.core.tools.Risk
import com.droidhands.core.tools.Scopes
import com.droidhands.core.tools.Tool
import com.droidhands.core.tools.ToolException
import com.droidhands.core.tools.ToolResult
import com.droidhands.core.tools.objectSchema
import com.droidhands.core.tools.optString
import com.droidhands.core.tools.optStringList
import com.droidhands.core.tools.reqBool
import com.droidhands.core.tools.reqInt
import com.droidhands.core.tools.reqString
import com.droidhands.core.tools.tool
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.put
import java.time.ZoneId
import java.time.ZonedDateTime

private const val MAX_CLIPBOARD_CHARS = 100_000

/** Tools: device_status, flashlight_set, volume_set, ringer_mode_set, media_control, clipboard_set, speak, alarm_set, timer_set (docs/TOOLS.md). */
fun deviceTools(context: Context): List<Tool> = listOf(
    deviceStatus(context),
    flashlightSet(context),
    volumeSet(context),
    ringerModeSet(context),
    mediaControl(context),
    clipboardSet(context),
    speak(context),
    alarmSet(context),
    timerSet(context),
)

private fun deviceStatus(context: Context) = tool(
    name = "device_status",
    title = "Device status",
    description = "Snapshot of the phone: battery % and charging, model and Android version, screen on/locked, network " +
        "(wifi/cellular, validated, metered), ringer mode and Do Not Disturb, volumes per stream (%), time zone and local " +
        "time, free storage, foreground app (needs accessibility), and which special accesses DroidHands has.",
    scope = Scopes.DEVICE,
    risk = Risk.READ,
) { _, _ ->
    ToolResult.json(withContext(Dispatchers.IO) { DeviceStatusReader(context).read() })
}

private fun flashlightSet(context: Context) = tool(
    name = "flashlight_set",
    title = "Turn flashlight on/off",
    description = "Turns the phone's flashlight (camera torch) on or off. Fails if the camera is in use by another app.",
    scope = Scopes.DEVICE,
    risk = Risk.ACTION,
    schema = objectSchema { boolean("on", "true to turn the flashlight on, false to turn it off", required = true) },
    unavailable = { Flashlight.unavailableReason(context) },
    describe = { args -> describeOr("Switch the flashlight") { "Turn the flashlight ${if (args.reqBool("on")) "on" else "off"}" } },
) { args, _ ->
    val on = args.reqBool("on")
    withContext(Dispatchers.IO) { Flashlight.set(context, on) }
    ToolResult.json { put("flashlight", if (on) "on" else "off") }
}

private fun volumeSet(context: Context) = tool(
    name = "volume_set",
    title = "Set volume",
    description = "Sets the volume of one audio stream in percent (0 mutes). Streams: music (media, also used by speak), " +
        "ring, alarm, notification, call. Setting ring/notification to 0 or changing them during Do Not Disturb may need " +
        "Do Not Disturb access. Returns the volume Android actually applied.",
    scope = Scopes.DEVICE,
    risk = Risk.ACTION,
    schema = objectSchema {
        string("stream", "Audio stream", required = true, enum = VolumeStream.ARGS)
        integer("percent", "Volume 0-100", required = true, minimum = 0, maximum = 100)
    },
    describe = { args ->
        describeOr("Change a volume") { "Set ${VolumeStream.parse(args.reqString("stream")).arg} volume to ${args.reqInt("percent")}%" }
    },
) { args, _ ->
    val stream = VolumeStream.parse(args.reqString("stream"))
    val percent = VolumeMath.requirePercent(args.reqInt("percent"))
    val applied = withContext(Dispatchers.IO) { AudioControl(context).setVolume(stream, percent) }
    ToolResult.json {
        put("stream", stream.arg)
        put("requested_percent", percent)
        put("percent", applied.percent)
        put("level", applied.index)
        put("max_level", applied.max)
        if (applied.index != VolumeMath.percentToIndex(percent, applied.min, applied.max)) {
            put("note", "Android applied a different level (safe-headphone-volume limit, Do Not Disturb, or a device restriction).")
        }
    }
}

private fun ringerModeSet(context: Context) = tool(
    name = "ringer_mode_set",
    title = "Set ringer mode",
    description = "Sets the ringer to normal, vibrate or silent. Switching into or out of silent needs Do Not Disturb " +
        "access (the error says how to grant it).",
    scope = Scopes.DEVICE,
    risk = Risk.ACTION,
    schema = objectSchema { string("mode", "Ringer mode", required = true, enum = RingerModeName.ARGS) },
    describe = { args -> describeOr("Change the ringer mode") { "Set ringer mode to ${RingerModeName.parse(args.reqString("mode")).arg}" } },
) { args, _ ->
    val target = RingerModeName.parse(args.reqString("mode"))
    val applied = withContext(Dispatchers.IO) { AudioControl(context).setRingerMode(target) }
    ToolResult.json {
        put("ringer_mode", applied?.arg ?: "unknown")
        if (applied != target) {
            put("note", "Android applied '${applied?.arg ?: "unknown"}' instead (for example, phones without a vibrator fall back to silent).")
        }
    }
}

private fun mediaControl(context: Context) = tool(
    name = "media_control",
    title = "Control media playback",
    description = "Sends a media button (play, pause, play_pause, next, previous, stop) to the app currently playing or " +
        "last playing audio, like headset buttons. Reports whether audio is playing afterwards.",
    scope = Scopes.DEVICE,
    risk = Risk.ACTION,
    schema = objectSchema { string("action", "Media action", required = true, enum = MediaAction.ARGS) },
    describe = { args -> describeOr("Control media playback") { "Media: ${MediaAction.parse(args.reqString("action")).arg}" } },
) { args, _ ->
    val action = MediaAction.parse(args.reqString("action"))
    val audio = withContext(Dispatchers.IO) { AudioControl(context).also { it.pressMediaKey(action) } }
    delay(500) // Let the player react before sampling the playback state.
    val playing = withContext(Dispatchers.IO) { runCatching { audio.isMusicActive }.getOrNull() }
    ToolResult.json {
        put("sent", action.arg)
        putIfNotNull("audio_playing", playing)
    }
}

private fun clipboardSet(context: Context) = tool(
    name = "clipboard_set",
    title = "Copy text to clipboard",
    description = "Puts text on the phone's clipboard so the user (or ui_type) can paste it. Android 13+ shows a brief " +
        "confirmation on screen.",
    scope = Scopes.DEVICE,
    risk = Risk.ACTION,
    schema = objectSchema { string("text", "Text to copy", required = true) },
    describe = { args -> describeOr("Copy text to the clipboard") { "Copy to clipboard: ${quoteForSummary(args.reqString("text"))}" } },
) { args, _ ->
    // Not reqString: whitespace-only text is a legitimate clipboard value.
    val text = args.optString("text") ?: throw ToolException("Missing required argument 'text'")
    if (text.length > MAX_CLIPBOARD_CHARS) throw ToolException("Text is too long for the clipboard (max $MAX_CLIPBOARD_CHARS characters).")
    onMain {
        val clipboard = context.getSystemService(ClipboardManager::class.java) ?: throw ToolException("Clipboard is not available on this phone.")
        clipboard.setPrimaryClip(ClipData.newPlainText("DroidHands", text))
    }
    ToolResult.json {
        put("copied", true)
        put("characters", text.length)
    }
}

private fun speak(context: Context) = tool(
    name = "speak",
    title = "Speak text aloud",
    description = "Speaks text out loud on the phone with text-to-speech (media volume) and returns when it has finished. " +
        "Max ${SpeechLogic.MAX_CHARS} characters per call; split longer text. Optional 'language' as BCP-47 (en-US, he, fr-FR).",
    scope = Scopes.DEVICE,
    risk = Risk.ACTION,
    schema = objectSchema {
        string("text", "Text to speak", required = true)
        string("language", "BCP-47 language tag, e.g. en-US or he (default: the phone's TTS language)")
    },
    // Engine start (<= 10 s) + speech (<= 60 s) + slack; stays inside the relay's 150 s even after a confirmation.
    timeoutMs = 75_000,
    unavailable = { Speech.unavailableReason(context) },
    describe = { args -> describeOr("Speak text aloud") { "Speak aloud: ${quoteForSummary(args.reqString("text"))}" } },
) { args, _ ->
    val text = SpeechLogic.requireText(args.reqString("text"))
    val locale = SpeechLogic.parseLanguage(args.optString("language"))
    val musicPercent = withContext(Dispatchers.IO) {
        runCatching { AudioControl(context).volume(VolumeStream.MUSIC).percent }.getOrNull()
    }
    Speech.speak(context, text, locale)
    ToolResult.json {
        put("spoken", true)
        put("characters", text.length)
        locale?.let { put("language", it.toLanguageTag()) }
        if (musicPercent == 0) put("note", "Media volume is 0%, so it was probably inaudible. Use volume_set stream=music first.")
    }
}

private fun alarmSet(context: Context) = tool(
    name = "alarm_set",
    title = "Set an alarm",
    description = "Creates an alarm in the phone's clock app without opening it. 'hour' is 0-23 (24-hour clock), in the " +
        "phone's time zone. 'days' (mon..sun) makes it repeat; without days it rings once at the next occurrence of that " +
        "time. Returns {set,alarm,next_ring,rings_in,days?,label?,clock_app?,note?}: next_ring is when it will ring " +
        "(ISO-8601) and rings_in the countdown, to confirm to the user. set=true means the clock app received it (clock " +
        "apps don't report back); set=false means the user must first pick a clock app on the phone (see note).",
    scope = Scopes.DEVICE,
    risk = Risk.ACTION,
    unavailable = { ClockIntents.unavailableReason(context, AlarmClock.ACTION_SET_ALARM) },
    schema = objectSchema {
        integer("hour", "Hour 0-23", required = true, minimum = 0, maximum = 23)
        integer("minute", "Minute 0-59", required = true, minimum = 0, maximum = 59)
        string("label", "Alarm label")
        stringArray("days", "Repeat on these days", enum = listOf("mon", "tue", "wed", "thu", "fri", "sat", "sun"))
    },
    describe = { args -> describeOr("Set an alarm") { "Set alarm ${describeAlarm(args)}" } },
) { args, _ ->
    val hour = ClockLogic.requireHour(args.reqInt("hour"))
    val minute = ClockLogic.requireMinute(args.reqInt("minute"))
    val days = ClockLogic.parseDays(args.optStringList("days").orEmpty())
    val label = args.optString("label")?.trim()?.takeIf { it.isNotEmpty() }
    val handler = ClockIntents.send(context, ClockIntents.alarmIntent(hour, minute, days, label))
    val now = ZonedDateTime.now(ZoneId.systemDefault())
    val next = ClockLogic.nextRing(now, hour, minute, days)
    ToolResult.json {
        put("set", handler != null)
        put("alarm", ClockLogic.formatTime(hour, minute))
        put("next_ring", CalendarTime.format(next))
        put("rings_in", ClockLogic.formatUntil(now, next))
        if (days.isNotEmpty()) put("days", buildJsonArray { days.forEach { add(ClockLogic.dayName(it)) } })
        putIfNotNull("label", label)
        putIfNotNull("clock_app", handler)
        putIfNotNull("note", clockNote(context, handler))
    }
}

/** Chooser and background-launch caveats for alarm_set / timer_set; [handler] is null when Android shows a chooser. */
private fun clockNote(context: Context, handler: String?): String? = listOfNotNull(
    CLOCK_CHOOSER.takeIf { handler == null },
    LaunchNotes.afterLaunch(context),
).joinToString(" ").ifEmpty { null }

private const val CLOCK_CHOOSER =
    "Several clock apps are installed and none is the default, so Android is asking the user on the phone which one to " +
        "use; nothing is set until they pick one."

private fun describeAlarm(args: JsonObject): String = ClockLogic.describeAlarm(
    ClockLogic.requireHour(args.reqInt("hour")),
    ClockLogic.requireMinute(args.reqInt("minute")),
    ClockLogic.parseDays(args.optStringList("days").orEmpty()),
    args.optString("label"),
)

private fun timerSet(context: Context) = tool(
    name = "timer_set",
    title = "Start a timer",
    description = "Starts a countdown timer in the phone's clock app without opening it (1 s to 24 h). Returns " +
        "{set,timer,seconds,ends_at,label?,clock_app?,note?}; ends_at is ISO-8601. set=false means the user must first " +
        "pick a clock app on the phone (see note).",
    scope = Scopes.DEVICE,
    risk = Risk.ACTION,
    unavailable = { ClockIntents.unavailableReason(context, AlarmClock.ACTION_SET_TIMER) },
    schema = objectSchema {
        integer("seconds", "Timer length in seconds (1-86400)", required = true, minimum = 1, maximum = ClockLogic.MAX_TIMER_SECONDS.toLong())
        string("label", "Timer label")
    },
    describe = { args -> describeOr("Start a timer") { "Start a ${ClockLogic.formatDuration(ClockLogic.requireTimerSeconds(args.reqInt("seconds")))} timer" } },
) { args, _ ->
    val seconds = ClockLogic.requireTimerSeconds(args.reqInt("seconds"))
    val label = args.optString("label")?.trim()?.takeIf { it.isNotEmpty() }
    val handler = ClockIntents.send(context, ClockIntents.timerIntent(seconds, label))
    val endsAt = ZonedDateTime.now(ZoneId.systemDefault()).plusSeconds(seconds.toLong())
    ToolResult.json {
        put("set", handler != null)
        put("timer", ClockLogic.formatDuration(seconds))
        put("seconds", seconds)
        put("ends_at", CalendarTime.format(endsAt))
        putIfNotNull("label", label)
        putIfNotNull("clock_app", handler)
        putIfNotNull("note", clockNote(context, handler))
    }
}
