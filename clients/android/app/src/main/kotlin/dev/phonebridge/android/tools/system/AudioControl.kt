package dev.phonebridge.android.tools.system

import android.app.NotificationManager
import android.content.Context
import android.media.AudioManager
import android.os.SystemClock
import android.view.KeyEvent
import dev.phonebridge.core.tools.ToolException

/** Android stream constant for a public stream name. */
val VolumeStream.androidStream: Int
    get() = when (this) {
        VolumeStream.MUSIC -> AudioManager.STREAM_MUSIC
        VolumeStream.RING -> AudioManager.STREAM_RING
        VolumeStream.ALARM -> AudioManager.STREAM_ALARM
        VolumeStream.NOTIFICATION -> AudioManager.STREAM_NOTIFICATION
        VolumeStream.CALL -> AudioManager.STREAM_VOICE_CALL
    }

val RingerModeName.androidMode: Int
    get() = when (this) {
        RingerModeName.NORMAL -> AudioManager.RINGER_MODE_NORMAL
        RingerModeName.VIBRATE -> AudioManager.RINGER_MODE_VIBRATE
        RingerModeName.SILENT -> AudioManager.RINGER_MODE_SILENT
    }

fun ringerModeName(androidMode: Int): RingerModeName? = RingerModeName.entries.firstOrNull { it.androidMode == androidMode }

val MediaAction.keyCode: Int
    get() = when (this) {
        MediaAction.PLAY -> KeyEvent.KEYCODE_MEDIA_PLAY
        MediaAction.PAUSE -> KeyEvent.KEYCODE_MEDIA_PAUSE
        MediaAction.PLAY_PAUSE -> KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE
        MediaAction.NEXT -> KeyEvent.KEYCODE_MEDIA_NEXT
        MediaAction.PREVIOUS -> KeyEvent.KEYCODE_MEDIA_PREVIOUS
        MediaAction.STOP -> KeyEvent.KEYCODE_MEDIA_STOP
    }

/** Volume, ringer and media-key operations. AudioManager calls are binder IPC: use Dispatchers.IO. */
internal class AudioControl(private val context: Context) {
    private val audio: AudioManager =
        context.getSystemService(AudioManager::class.java) ?: throw ToolException("Audio service is not available on this phone.")

    data class StreamVolume(val index: Int, val min: Int, val max: Int) {
        val percent: Int get() = VolumeMath.indexToPercent(index, max)
    }

    fun volume(stream: VolumeStream): StreamVolume {
        val s = stream.androidStream
        return StreamVolume(audio.getStreamVolume(s), audio.getStreamMinVolume(s), audio.getStreamMaxVolume(s))
    }

    /** Sets [stream] to [percent]; returns the volume Android actually applied. */
    fun setVolume(stream: VolumeStream, percent: Int): StreamVolume {
        if (audio.isVolumeFixed) throw ToolException("This phone uses a fixed volume; it cannot be changed by apps.")
        val before = volume(stream)
        val index = VolumeMath.percentToIndex(percent, before.min, before.max)
        try {
            audio.setStreamVolume(stream.androidStream, index, 0)
        } catch (e: SecurityException) {
            throw ToolException(dndRequiredMessage("Changing the ${stream.arg} volume"))
        }
        return volume(stream)
    }

    fun ringerMode(): RingerModeName? = ringerModeName(audio.ringerMode)

    /** Sets the ringer mode; returns the mode Android actually applied. */
    fun setRingerMode(target: RingerModeName): RingerModeName? {
        val current = ringerMode()
        if (RingerModeName.changeNeedsDndAccess(current, target) && !hasDndAccess()) {
            throw ToolException(dndRequiredMessage("Switching the ringer ${if (target == RingerModeName.SILENT) "to" else "out of"} silent mode"))
        }
        try {
            audio.ringerMode = target.androidMode
        } catch (e: SecurityException) {
            throw ToolException(dndRequiredMessage("Changing the ringer mode"))
        }
        return ringerMode()
    }

    fun hasDndAccess(): Boolean =
        context.getSystemService(NotificationManager::class.java)?.isNotificationPolicyAccessGranted == true

    /** Sends a media key press (down + up) to the active media session, as headset buttons do. */
    fun pressMediaKey(action: MediaAction) {
        val now = SystemClock.uptimeMillis()
        audio.dispatchMediaKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_DOWN, action.keyCode, 0))
        audio.dispatchMediaKeyEvent(KeyEvent(now, SystemClock.uptimeMillis(), KeyEvent.ACTION_UP, action.keyCode, 0))
    }

    val isMusicActive: Boolean get() = audio.isMusicActive

    companion object {
        fun dndRequiredMessage(what: String) =
            "$what needs Do Not Disturb access, which PhoneBridge does not have. Ask the user to open the PhoneBridge app > " +
                "Setup and grant \"Do Not Disturb access\" (Android Settings > Notifications > Do Not Disturb access), then retry."
    }
}
