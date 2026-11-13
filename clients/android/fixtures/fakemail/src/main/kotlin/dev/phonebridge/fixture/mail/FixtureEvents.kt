package dev.phonebridge.fixture.mail

import android.content.Context
import android.content.Intent
import android.util.Log
import org.json.JSONObject
import java.io.File
import java.net.URLEncoder

/**
 * Records what the fixture observed, so E2E tests can assert on it from the host. Every event goes to:
 *  1. logcat, tag `FIXTURE`, one line `FIXTURE <EVENT> key=value ...` (values URL-encoded, space = %20);
 *  2. `files/events.jsonl`, one JSON object per line: `{"ts":<epoch ms>,"event":"<EVENT>",<key>:<value>...}`
 *     (`adb shell run-as dev.phonebridge.fixture.mail cat files/events.jsonl`).
 * Fields whose value is null are omitted.
 */
object FixtureEvents {
    const val TAG = "FIXTURE"
    const val FILE_NAME = "events.jsonl"

    @Synchronized
    fun record(context: Context, event: String, vararg fields: Pair<String, Any?>) {
        val line = StringBuilder("FIXTURE ").append(event)
        val json = JSONObject().put("ts", System.currentTimeMillis()).put("event", event)
        for ((key, value) in fields) {
            if (value == null) continue
            line.append(' ').append(key).append('=').append(encode(value.toString()))
            json.put(key, value)
        }
        Log.i(TAG, line.toString())
        try {
            File(context.applicationContext.filesDir, FILE_NAME).appendText(json.toString() + "\n")
        } catch (e: Exception) {
            Log.e(TAG, "FIXTURE WRITE_FAILED error=" + encode(e.toString()))
        }
    }

    private fun encode(value: String): String = URLEncoder.encode(value, "UTF-8").replace("+", "%20")
}

/** All values of an extra as strings: String, String[], ArrayList<String>, CharSequence... */
@Suppress("DEPRECATION")
fun Intent.extraValues(key: String): List<String> = when (val value = extras?.get(key)) {
    null -> emptyList()
    is Array<*> -> value.mapNotNull { it?.toString() }
    is Collection<*> -> value.mapNotNull { it?.toString() }
    else -> listOf(value.toString())
}

fun Intent.extraString(key: String): String? = extraValues(key).firstOrNull()

/** A boolean extra sent as `--ez key false` or as a string (`--es key false`). */
@Suppress("DEPRECATION")
fun Intent.extraBoolean(key: String, default: Boolean): Boolean = when (val value = extras?.get(key)) {
    null -> default
    is Boolean -> value
    else -> value.toString().trim().lowercase() !in setOf("false", "0", "no", "off")
}
