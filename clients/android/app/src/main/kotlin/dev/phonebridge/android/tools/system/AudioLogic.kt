package dev.phonebridge.android.tools.system

import dev.phonebridge.core.tools.ToolException
import kotlin.math.roundToInt

/** Audio streams agents can control (`volume_set.stream`, `device_status.volumes`). [arg] is the public name. */
enum class VolumeStream(val arg: String) {
    MUSIC("music"),
    RING("ring"),
    ALARM("alarm"),
    NOTIFICATION("notification"),
    CALL("call"),
    ;

    companion object {
        val ARGS: List<String> = entries.map { it.arg }

        // Lenient synonyms LLMs commonly use; they never change what a documented name means.
        private val ALIASES = mapOf(
            "media" to MUSIC,
            "ringer" to RING,
            "ringtone" to RING,
            "voice_call" to CALL,
            "in_call" to CALL,
            "voice" to CALL,
        )

        fun parse(raw: String): VolumeStream {
            val key = normalizeEnumArg(raw)
            return entries.firstOrNull { it.arg == key } ?: ALIASES[key]
                ?: throw ToolException("Argument 'stream' must be one of ${ARGS.joinToString("|")}, got '$raw'.")
        }
    }
}

/** `ringer_mode_set.mode` values. */
enum class RingerModeName(val arg: String) {
    NORMAL("normal"),
    VIBRATE("vibrate"),
    SILENT("silent"),
    ;

    companion object {
        val ARGS: List<String> = entries.map { it.arg }

        fun parse(raw: String): RingerModeName = when (val key = normalizeEnumArg(raw)) {
            "ring", "sound", "loud", "on" -> NORMAL
            "vibration", "vibrate_only" -> VIBRATE
            "mute", "muted", "quiet" -> SILENT
            else -> entries.firstOrNull { it.arg == key }
                ?: throw ToolException("Argument 'mode' must be one of ${ARGS.joinToString("|")}, got '$raw'.")
        }

        /**
         * Android (N+) only lets apps with Do Not Disturb access make ringer changes that toggle DND,
         * which is any change into or out of silent mode (AudioService.wouldToggleZenMode).
         */
        fun changeNeedsDndAccess(current: RingerModeName?, target: RingerModeName): Boolean =
            current != target && (current == SILENT || target == SILENT)
    }
}

/** `media_control.action` values. */
enum class MediaAction(val arg: String) {
    PLAY("play"),
    PAUSE("pause"),
    PLAY_PAUSE("play_pause"),
    NEXT("next"),
    PREVIOUS("previous"),
    STOP("stop"),
    ;

    companion object {
        val ARGS: List<String> = entries.map { it.arg }

        fun parse(raw: String): MediaAction = when (val key = normalizeEnumArg(raw)) {
            "playpause", "toggle", "play/pause" -> PLAY_PAUSE
            "resume" -> PLAY
            "skip", "forward", "next_track" -> NEXT
            "prev", "back", "previous_track", "rewind" -> PREVIOUS
            else -> entries.firstOrNull { it.arg == key }
                ?: throw ToolException("Argument 'action' must be one of ${ARGS.joinToString("|")}, got '$raw'.")
        }
    }
}

/** Conversions between the public 0-100 % scale and Android's per-stream volume indices. */
object VolumeMath {
    fun requirePercent(percent: Int): Int {
        if (percent !in 0..100) throw ToolException("Argument 'percent' must be between 0 and 100, got $percent.")
        return percent
    }

    /**
     * Maps [percent] of the 0..[maxIndex] range to the nearest index (half up), clamped to
     * [minIndex]..[maxIndex]. A non-zero percent never rounds down to silence.
     */
    fun percentToIndex(percent: Int, minIndex: Int, maxIndex: Int): Int {
        if (maxIndex <= 0) return 0
        var index = (percent.coerceIn(0, 100) * maxIndex / 100.0).roundToInt()
        if (percent > 0 && index == 0) index = 1
        return index.coerceIn(minIndex.coerceIn(0, maxIndex), maxIndex)
    }

    /** Inverse of [percentToIndex]; round-trips exactly for every index when maxIndex <= 100. */
    fun indexToPercent(index: Int, maxIndex: Int): Int =
        if (maxIndex <= 0) 0 else (index * 100.0 / maxIndex).roundToInt().coerceIn(0, 100)
}
