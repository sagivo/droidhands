package dev.phonebridge.android.notify

import android.annotation.SuppressLint
import android.content.res.Resources

/**
 * The text Android substitutes for notification content it hides from listeners (see
 * [HiddenContent]), in the device language. Read from the framework's own string resource, so it
 * matches whatever language the phone uses; English is always included as a fallback.
 */
internal object SystemPlaceholders {
    private const val RESOURCE_NAME = "redacted_notification_message"

    /** 0 when this Android version has no such string (before Android 15). */
    @get:SuppressLint("DiscouragedApi") // Framework-internal string: there is no public R constant.
    private val resourceId: Int by lazy {
        runCatching { Resources.getSystem().getIdentifier(RESOURCE_NAME, "string", "android") }.getOrDefault(0)
    }

    /** Current placeholder texts. Cheap after the first call; safe from any thread. */
    fun current(): Set<String> {
        val localized = resourceId.takeIf { it != 0 }
            ?.let { runCatching { Resources.getSystem().getString(it) }.getOrNull() }
            ?.takeIf { it.isNotBlank() }
        return setOfNotNull(HiddenContent.ENGLISH_PLACEHOLDER, localized)
    }
}
