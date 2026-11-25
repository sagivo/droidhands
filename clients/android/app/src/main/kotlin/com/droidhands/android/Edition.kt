package com.droidhands.android

/**
 * Which edition this build is (docs/EDITIONS.md). `full` (GitHub Releases, droidhands.com) has everything. `store`
 * (Google Play) leaves out what Play's policies restrict: phone use (the accessibility service, the screen_* / ui_*
 * tools and screen recipes), SMS and call log, and background location.
 */
object Edition {
    /** The phone-use tools, screen recipes and the accessibility setup step exist. */
    val PHONE_USE: Boolean = BuildConfig.PHONE_USE

    /**
     * SMS and call-log permissions are declared: sms_list, call_log_list, sms_send through the SIM, and the caller's
     * number in call_status. Without them sms_send opens the SMS app with the message filled in.
     */
    val SMS_AND_CALL_LOG: Boolean = BuildConfig.SMS_AND_CALL_LOG

    /** ACCESS_BACKGROUND_LOCATION is declared. Without it location_get works only while DroidHands is on screen. */
    val BACKGROUND_LOCATION: Boolean = BuildConfig.BACKGROUND_LOCATION

    /** Why a steps (screen) recipe can't run in the store edition. */
    const val NO_PHONE_USE =
        "This edition of DroidHands (the Google Play one) can't tap or read in other apps, so this action can't run here. " +
            "Ask the user to do it on the phone."

    /** "phone use (screen_observe, ui_tap)", or the user when this edition has none, for "…, use X instead." hints. */
    fun fallback(phoneUse: Boolean = PHONE_USE): String =
        if (phoneUse) "phone use (screen_observe, ui_tap)" else "the user (ask them to do it on the phone)"
}
