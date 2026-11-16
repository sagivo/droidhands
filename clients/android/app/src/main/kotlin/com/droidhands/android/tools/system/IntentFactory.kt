package com.droidhands.android.tools.system

import android.content.Intent
import android.net.Uri

/** Builds the Android [Intent] for a validated [IntentSpec]. */
internal object IntentFactory {
    /** URI-permission grant flags. intent_start never forwards DroidHands's own URI access to another app. */
    const val GRANT_FLAGS: Int = Intent.FLAG_GRANT_READ_URI_PERMISSION or
        Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
        Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION or
        Intent.FLAG_GRANT_PREFIX_URI_PERMISSION

    fun build(spec: IntentSpec): Intent {
        val intent = Intent(spec.action)
        val uri = spec.data?.let(Uri::parse)
        when {
            uri != null && spec.mimeType != null -> intent.setDataAndTypeAndNormalize(uri, spec.mimeType)
            uri != null -> intent.setDataAndNormalize(uri)
            spec.mimeType != null -> intent.setTypeAndNormalize(spec.mimeType)
        }
        spec.packageName?.let { intent.setPackage(it) }
        if (spec.packageName != null && spec.className != null) intent.setClassName(spec.packageName, spec.className)
        spec.categories.forEach { intent.addCategory(it) }
        for ((key, value) in spec.extras) {
            when (value) {
                is ExtraValue.Str -> intent.putExtra(key, value.value)
                is ExtraValue.Bool -> intent.putExtra(key, value.value)
                is ExtraValue.IntValue -> intent.putExtra(key, value.value)
                is ExtraValue.LongValue -> intent.putExtra(key, value.value)
                is ExtraValue.DoubleValue -> intent.putExtra(key, value.value)
                is ExtraValue.StrList -> intent.putStringArrayListExtra(key, ArrayList(value.values))
            }
        }
        // Defense in depth: nothing above sets them, and no ClipData/selector is attached.
        intent.removeFlags(GRANT_FLAGS)
        return intent
    }
}
