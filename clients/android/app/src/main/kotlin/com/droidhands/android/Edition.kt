package com.droidhands.android

/**
 * Which edition this build is (docs/EDITIONS.md). `full` (GitHub Releases, droidhands.com) has phone use: the
 * accessibility service and the screen_* / ui_* tools and screen recipes. `store` (Google Play) has none of it,
 * because Play's Accessibility API policy doesn't allow it for AI agents; everything else is the same.
 */
object Edition {
    /** The phone-use tools, screen recipes and the accessibility setup step exist. */
    val PHONE_USE: Boolean = BuildConfig.PHONE_USE

    /** Why a steps (screen) recipe can't run in the store edition. */
    const val NO_PHONE_USE =
        "This edition of DroidHands (the Google Play one) can't tap or read in other apps, so this action can't run here. " +
            "Ask the user to do it on the phone."

    /** "phone use (screen_observe, ui_tap)", or the user when this edition has none, for "…, use X instead." hints. */
    fun fallback(phoneUse: Boolean = PHONE_USE): String =
        if (phoneUse) "phone use (screen_observe, ui_tap)" else "the user (ask them to do it on the phone)"
}
