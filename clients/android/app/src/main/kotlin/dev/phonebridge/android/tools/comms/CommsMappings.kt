package dev.phonebridge.android.tools.comms

import android.provider.CallLog
import android.provider.ContactsContract.CommonDataKinds.Phone
import android.provider.Telephony
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

// Pure mappings between Android provider codes and the names used in tool arguments/results.
// They reference Android *constants* only (inlined at compile time), so they run in JVM unit tests.

/** `box` argument and result field of sms_list (Telephony.Sms TYPE column). */
internal object SmsBoxes {
    val FILTERS = listOf("all", "inbox", "sent")

    /** Telephony.Sms TYPE values for a `box` filter, or null for "all". */
    fun typesFor(box: String): List<Int>? = when (box) {
        "inbox" -> listOf(Telephony.Sms.MESSAGE_TYPE_INBOX)
        "sent" -> listOf(Telephony.Sms.MESSAGE_TYPE_SENT)
        else -> null
    }

    fun name(type: Int): String = when (type) {
        Telephony.Sms.MESSAGE_TYPE_INBOX -> "inbox"
        Telephony.Sms.MESSAGE_TYPE_SENT -> "sent"
        Telephony.Sms.MESSAGE_TYPE_DRAFT -> "draft"
        Telephony.Sms.MESSAGE_TYPE_OUTBOX -> "outbox"
        Telephony.Sms.MESSAGE_TYPE_FAILED -> "failed"
        Telephony.Sms.MESSAGE_TYPE_QUEUED -> "queued"
        else -> "other"
    }
}

/** `type` argument and result field of call_log_list (CallLog.Calls TYPE column). */
internal object CallTypes {
    val FILTERS = listOf("all", "incoming", "outgoing", "missed")

    /**
     * CallLog.Calls TYPE values for a `type` filter, or null for "all". "incoming" includes calls
     * answered on another device; "missed" includes rejected, blocked and voicemail calls (the
     * result's `type` field keeps the precise kind).
     */
    fun typesFor(filter: String): List<Int>? = when (filter) {
        "incoming" -> listOf(CallLog.Calls.INCOMING_TYPE, CallLog.Calls.ANSWERED_EXTERNALLY_TYPE)
        "outgoing" -> listOf(CallLog.Calls.OUTGOING_TYPE)
        "missed" -> listOf(
            CallLog.Calls.MISSED_TYPE, CallLog.Calls.REJECTED_TYPE, CallLog.Calls.BLOCKED_TYPE, CallLog.Calls.VOICEMAIL_TYPE,
        )
        else -> null
    }

    fun name(type: Int): String = when (type) {
        CallLog.Calls.INCOMING_TYPE -> "incoming"
        CallLog.Calls.OUTGOING_TYPE -> "outgoing"
        CallLog.Calls.MISSED_TYPE -> "missed"
        CallLog.Calls.VOICEMAIL_TYPE -> "voicemail"
        CallLog.Calls.REJECTED_TYPE -> "rejected"
        CallLog.Calls.BLOCKED_TYPE -> "blocked"
        CallLog.Calls.ANSWERED_EXTERNALLY_TYPE -> "answered_elsewhere"
        else -> "other"
    }

    /** Replacement for an empty number, from NUMBER_PRESENTATION; null when the number was allowed. */
    fun hiddenNumberName(presentation: Int): String? = when (presentation) {
        CallLog.Calls.PRESENTATION_RESTRICTED -> "private"
        CallLog.Calls.PRESENTATION_PAYPHONE -> "payphone"
        CallLog.Calls.PRESENTATION_UNKNOWN, CallLog.Calls.PRESENTATION_UNAVAILABLE -> "unknown"
        else -> null
    }
}

/** Phone.TYPE/LABEL → the `type` of a contact phone ("mobile", "home", "work", "other" or the custom label). */
internal object PhoneTypes {
    const val MOBILE = "mobile"
    const val WORK_MOBILE = "work mobile"

    fun label(type: Int, customLabel: String?): String = when (type) {
        Phone.TYPE_MOBILE, Phone.TYPE_MMS -> MOBILE
        Phone.TYPE_WORK_MOBILE -> WORK_MOBILE
        Phone.TYPE_HOME -> "home"
        Phone.TYPE_WORK -> "work"
        Phone.TYPE_MAIN -> "main"
        Phone.TYPE_COMPANY_MAIN -> "company main"
        Phone.TYPE_FAX_HOME -> "home fax"
        Phone.TYPE_FAX_WORK -> "work fax"
        Phone.TYPE_OTHER_FAX -> "fax"
        Phone.TYPE_PAGER, Phone.TYPE_WORK_PAGER -> "pager"
        Phone.TYPE_CAR -> "car"
        Phone.TYPE_ASSISTANT -> "assistant"
        Phone.TYPE_CALLBACK -> "callback"
        Phone.TYPE_CUSTOM -> customLabel?.trim()?.takeIf { it.isNotEmpty() } ?: "other"
        else -> "other"
    }

    /** True for numbers that can receive SMS/WhatsApp: mobile and work mobile. */
    fun isMobile(type: String): Boolean = type == MOBILE || type == WORK_MOBILE
}

/** ISO-8601 timestamps for results: local time with offset, whole seconds ("2026-11-13T09:41:22+02:00"). */
internal object IsoTime {
    fun format(epochMillis: Long, zone: ZoneId = ZoneId.systemDefault()): String =
        OffsetDateTime.ofInstant(Instant.ofEpochMilli(epochMillis), zone)
            .truncatedTo(ChronoUnit.SECONDS)
            .format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)
}

/** SQL helpers for ContentResolver selections. */
internal object SqlText {
    /** Escape character for [escapeLike]; use with `LIKE ? ESCAPE '\'`. */
    const val LIKE_ESCAPE = '\\'

    /** Escapes LIKE wildcards (`%`, `_`) and the escape character itself. */
    fun escapeLike(text: String): String = buildString(text.length + 4) {
        for (c in text) {
            if (c == '%' || c == '_' || c == LIKE_ESCAPE) append(LIKE_ESCAPE)
            append(c)
        }
    }

    /** LIKE pattern for "contains [text]". */
    fun containsPattern(text: String): String = "%" + escapeLike(text) + "%"

    /** "?,?,?" for an IN (...) clause. */
    fun placeholders(count: Int): String = List(count) { "?" }.joinToString(",")
}
