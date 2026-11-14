package dev.phonebridge.android.tools.email

import dev.phonebridge.core.tools.ToolException

/** Which email app composes the message (pure). */
object EmailApps {
    const val GMAIL = "com.google.android.gm"

    /**
     * [requested] (the `app` argument) if it is installed and handles `mailto:`; else the default `mailto:`
     * handler ([defaultHandler]; a package outside [handlers] means Android would show a chooser); else Gmail when
     * it is one of the [handlers]; else the only handler. Several handlers without a default → ToolException
     * listing them, so the agent can ask the user and pass `app`. [label] gives user-visible app names.
     */
    fun choose(
        requested: String?,
        defaultHandler: String?,
        handlers: List<String>,
        isInstalled: (String) -> Boolean,
        label: (String) -> String,
    ): String {
        val apps = handlers.distinct()
        fun list() = apps.joinToString(", ") { "${label(it)} ($it)" }
        if (requested != null) {
            return when {
                requested in apps -> requested
                !isInstalled(requested) -> throw ToolException(
                    "The app '$requested' is not installed on the phone." +
                        if (apps.isEmpty()) " No installed app can send email." else " Email apps on the phone: ${list()}.",
                )
                else -> throw ToolException(
                    "${label(requested)} ($requested) can't compose emails (it doesn't handle mailto: links)." +
                        if (apps.isEmpty()) "" else " Email apps on the phone: ${list()}.",
                )
            }
        }
        return when {
            defaultHandler != null && defaultHandler in apps -> defaultHandler
            GMAIL in apps -> GMAIL
            apps.size == 1 -> apps.single()
            apps.isEmpty() -> throw ToolException(NO_EMAIL_APP)
            else -> throw ToolException(
                "Several email apps are installed and none is set as the default: ${list()}. Ask the user which one to use " +
                    "and pass its package as 'app'.",
            )
        }
    }

    const val NO_EMAIL_APP =
        "No app on the phone can send email (nothing handles mailto: links). Ask the user to install and set up an email app such as Gmail."
}
