package com.droidhands.android.tools.system

import android.media.AudioManager
import android.view.KeyEvent
import com.droidhands.core.tools.ToolException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioLogicTest {
    @Test
    fun `stream names map to Android streams`() {
        assertEquals(AudioManager.STREAM_MUSIC, VolumeStream.parse("music").androidStream)
        assertEquals(AudioManager.STREAM_RING, VolumeStream.parse("ring").androidStream)
        assertEquals(AudioManager.STREAM_ALARM, VolumeStream.parse("alarm").androidStream)
        assertEquals(AudioManager.STREAM_NOTIFICATION, VolumeStream.parse("notification").androidStream)
        assertEquals(AudioManager.STREAM_VOICE_CALL, VolumeStream.parse("call").androidStream)
    }

    @Test
    fun `stream parsing is lenient about case and synonyms`() {
        assertEquals(VolumeStream.MUSIC, VolumeStream.parse(" Media "))
        assertEquals(VolumeStream.CALL, VolumeStream.parse("voice-call"))
        assertEquals(VolumeStream.RING, VolumeStream.parse("RING"))
        val e = assertThrows(ToolException::class.java) { VolumeStream.parse("bass") }
        assertTrue(e.message!!.contains("music|ring|alarm|notification|call"))
    }

    @Test
    fun `percent to index rounds to nearest step`() {
        assertEquals(0, VolumeMath.percentToIndex(0, 0, 15))
        assertEquals(15, VolumeMath.percentToIndex(100, 0, 15))
        assertEquals(8, VolumeMath.percentToIndex(50, 0, 15)) // 7.5 rounds half up
        assertEquals(6, VolumeMath.percentToIndex(40, 0, 15))
        assertEquals(4, VolumeMath.percentToIndex(57, 0, 7)) // 3.99
    }

    @Test
    fun `non-zero percent never mutes and min index is respected`() {
        assertEquals(1, VolumeMath.percentToIndex(1, 0, 15))
        assertEquals(1, VolumeMath.percentToIndex(0, 1, 7)) // alarm/call streams often have min 1
        assertEquals(0, VolumeMath.percentToIndex(50, 0, 0))
    }

    @Test
    fun `index and percent round-trip for every index`() {
        for (max in 1..100) {
            for (index in 0..max) {
                assertEquals("max=$max index=$index", index, VolumeMath.percentToIndex(VolumeMath.indexToPercent(index, max), 0, max))
            }
        }
    }

    @Test
    fun `percent is validated`() {
        assertEquals(0, VolumeMath.requirePercent(0))
        assertEquals(100, VolumeMath.requirePercent(100))
        assertThrows(ToolException::class.java) { VolumeMath.requirePercent(101) }
        assertThrows(ToolException::class.java) { VolumeMath.requirePercent(-1) }
    }

    @Test
    fun `ringer modes parse and only silent transitions need DND access`() {
        assertEquals(RingerModeName.SILENT, RingerModeName.parse("Silent"))
        assertEquals(RingerModeName.SILENT, RingerModeName.parse("mute"))
        assertEquals(RingerModeName.NORMAL, RingerModeName.parse("ring"))
        assertEquals(AudioManager.RINGER_MODE_VIBRATE, RingerModeName.VIBRATE.androidMode)
        assertEquals(RingerModeName.NORMAL, ringerModeName(AudioManager.RINGER_MODE_NORMAL))
        assertThrows(ToolException::class.java) { RingerModeName.parse("loudest") }

        assertTrue(RingerModeName.changeNeedsDndAccess(RingerModeName.NORMAL, RingerModeName.SILENT))
        assertTrue(RingerModeName.changeNeedsDndAccess(RingerModeName.SILENT, RingerModeName.VIBRATE))
        assertFalse(RingerModeName.changeNeedsDndAccess(RingerModeName.NORMAL, RingerModeName.VIBRATE))
        assertFalse(RingerModeName.changeNeedsDndAccess(RingerModeName.SILENT, RingerModeName.SILENT))
    }

    @Test
    fun `media actions map to media key codes`() {
        assertEquals(KeyEvent.KEYCODE_MEDIA_PLAY, MediaAction.parse("play").keyCode)
        assertEquals(KeyEvent.KEYCODE_MEDIA_PAUSE, MediaAction.parse("pause").keyCode)
        assertEquals(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, MediaAction.parse("play-pause").keyCode)
        assertEquals(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, MediaAction.parse("toggle").keyCode)
        assertEquals(KeyEvent.KEYCODE_MEDIA_NEXT, MediaAction.parse("next").keyCode)
        assertEquals(KeyEvent.KEYCODE_MEDIA_PREVIOUS, MediaAction.parse("prev").keyCode)
        assertEquals(KeyEvent.KEYCODE_MEDIA_STOP, MediaAction.parse("STOP").keyCode)
        assertThrows(ToolException::class.java) { MediaAction.parse("shuffle") }
    }
}
