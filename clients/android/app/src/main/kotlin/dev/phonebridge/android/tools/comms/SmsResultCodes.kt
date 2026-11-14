package dev.phonebridge.android.tools.comms

import android.app.Activity
import android.telephony.SmsManager

/**
 * Maps the result code of an SMS "sent" broadcast to an agent-facing explanation. Pure: uses only
 * compile-time constants, so it runs in JVM unit tests.
 */
internal object SmsResultCodes {
    const val OK = Activity.RESULT_OK

    fun describe(code: Int): String = when (code) {
        SmsManager.RESULT_ERROR_RADIO_OFF, SmsManager.RESULT_RADIO_NOT_AVAILABLE, SmsManager.RESULT_RIL_RADIO_NOT_AVAILABLE ->
            "the phone's radio is off (airplane mode?). Ask the user to turn airplane mode off, then retry."
        SmsManager.RESULT_ERROR_NO_SERVICE, SmsManager.RESULT_RIL_NETWORK_NOT_READY, SmsManager.RESULT_RIL_NO_NETWORK_FOUND ->
            "the phone has no cellular service right now. Retry when it has signal."
        SmsManager.RESULT_RIL_SIM_ABSENT, SmsManager.RESULT_RIL_NO_SUBSCRIPTION, SmsManager.RESULT_RIL_SUBSCRIPTION_NOT_AVAILABLE,
        SmsManager.RESULT_RIL_INVALID_SIM_STATE, SmsManager.RESULT_RIL_SIM_ERROR, SmsManager.RESULT_RIL_SIM_BUSY,
        SmsManager.RESULT_RIL_SIM_PIN2, SmsManager.RESULT_RIL_SIM_PUK2 ->
            "the SIM card is missing, locked or not ready. Ask the user to check the SIM."
        SmsManager.RESULT_ERROR_LIMIT_EXCEEDED, SmsManager.RESULT_RIL_REQUEST_RATE_LIMITED ->
            "Android's SMS rate limit was reached (too many messages in a short time). Wait a few minutes and retry; " +
                "Android may also ask the user on the phone to allow more messages."
        SmsManager.RESULT_ERROR_SHORT_CODE_NOT_ALLOWED, SmsManager.RESULT_ERROR_SHORT_CODE_NEVER_ALLOWED ->
            "Android blocked sending to this premium short code. The user must allow it on the phone or send it themselves."
        SmsManager.RESULT_ERROR_FDN_CHECK_FAILURE ->
            "the number is not allowed by the SIM's Fixed Dialing Numbers (FDN) list."
        SmsManager.RESULT_NETWORK_REJECT, SmsManager.RESULT_RIL_NETWORK_REJECT, SmsManager.RESULT_RIL_ACCESS_BARRED ->
            "the mobile network rejected the message. Check the number, and that the SIM plan allows SMS to it."
        SmsManager.RESULT_INVALID_SMSC_ADDRESS, SmsManager.RESULT_RIL_INVALID_SMSC_ADDRESS ->
            "the SIM's SMS center (SMSC) number is invalid. The user must fix it in their messaging app settings."
        SmsManager.RESULT_INVALID_ARGUMENTS, SmsManager.RESULT_RIL_INVALID_ARGUMENTS ->
            "the number or message was rejected as invalid. Check the number (international format, e.g. +14155550123)."
        SmsManager.RESULT_ERROR_NULL_PDU, SmsManager.RESULT_ENCODING_ERROR, SmsManager.RESULT_RIL_ENCODING_ERR,
        SmsManager.RESULT_INVALID_SMS_FORMAT, SmsManager.RESULT_RIL_INVALID_SMS_FORMAT ->
            "the message could not be encoded as SMS. Try removing unusual characters or shortening it."
        SmsManager.RESULT_RIL_SIMULTANEOUS_SMS_AND_CALL_NOT_ALLOWED, SmsManager.RESULT_RIL_BLOCKED_DUE_TO_CALL ->
            "SMS can't be sent during the current call on this network. Retry after the call."
        SmsManager.RESULT_SMS_BLOCKED_DURING_EMERGENCY ->
            "SMS is blocked while an emergency call/callback is active."
        SmsManager.RESULT_NO_DEFAULT_SMS_APP ->
            "Android requires a default SMS app to send messages. Ask the user to set one in Settings > Apps > Default apps."
        SmsManager.RESULT_USER_NOT_ALLOWED, SmsManager.RESULT_OPERATION_NOT_ALLOWED, SmsManager.RESULT_RIL_OPERATION_NOT_ALLOWED ->
            "Android does not allow sending SMS on this phone or user profile right now."
        SmsManager.RESULT_CANCELLED, SmsManager.RESULT_RIL_CANCELLED, SmsManager.RESULT_RIL_ABORTED ->
            "sending was cancelled by the system. Retry."
        SmsManager.RESULT_SMS_SEND_RETRY_FAILED, SmsManager.RESULT_RIL_SMS_SEND_FAIL_RETRY ->
            "the network did not accept the message after retries. Retry later."
        SmsManager.RESULT_ERROR_GENERIC_FAILURE ->
            "the radio reported a generic failure. Check that the phone has signal and SMS credit, then retry."
        else -> "the radio reported error code $code. Check that the phone has signal and an active SIM, then retry."
    }
}
