package com.whatsapp

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
 *     (`adb shell run-as com.whatsapp cat files/events.jsonl`).
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

/** An extra as a string, whatever type it was sent as (`--es`, `--ei`, `--el`...). */
@Suppress("DEPRECATION")
fun Intent.extraString(key: String): String? = extras?.get(key)?.toString()

/** Keeps ASCII digits only ("+972 54-123" -> "97254123"). */
fun digitsOf(value: String?): String = value.orEmpty().filter { it in '0'..'9' }
