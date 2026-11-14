package dev.phonebridge.android.tools.email

import dev.phonebridge.android.access.TextNorm
import java.util.Locale

/** What to do about one field of the compose screen before tapping Send. */
sealed interface FieldCheck {
    val field: ComposeField

    /** Shows the approved content. */
    data class Ok(override val field: ComposeField) : FieldCheck

    /** Empty (or the app duplicated the body): put [text] in with ACTION_SET_TEXT. */
    data class Fill(override val field: ComposeField, val text: String) : FieldCheck

    /** Shows something else, so this isn't (only) our draft: don't send. [reason] is agent-facing. */
    data class Mismatch(override val field: ComposeField, val reason: String) : FieldCheck

    /** A field the draft needs isn't on screen. */
    data class Missing(override val field: ComposeField) : FieldCheck
}

/**
 * Compares a [ComposeScreen] with the approved [EmailDraft] (pure). Fields are only ever filled when empty (or the
 * body was duplicated by an app that read both the mailto: URI and the extras); anything unexpected stops the send,
 * so a user's own draft is never sent or overwritten.
 */
object ComposeChecks {
    /** Checks for To, Cc (only when the draft has cc), Subject and body, in that order. */
    fun check(screen: ComposeScreen, draft: EmailDraft): List<FieldCheck> = buildList {
        add(recipients(screen, ComposeField.TO, draft.to))
        if (draft.cc.isNotEmpty()) add(recipients(screen, ComposeField.CC, draft.cc))
        add(subject(screen, draft.subject))
        add(body(screen, draft.body))
    }

    /** True when every check is [FieldCheck.Ok]. */
    fun allOk(checks: List<FieldCheck>): Boolean = checks.all { it is FieldCheck.Ok }

    private fun recipients(screen: ComposeScreen, field: ComposeField, expected: List<EmailRecipient>): FieldCheck {
        if (field !in screen.fields) return FieldCheck.Missing(field)
        val shown = screen.shown[field].orEmpty()
        return when (val verdict = recipientsVerdict(shown, expected)) {
            RecipientsVerdict.Empty -> FieldCheck.Fill(field, expected.joinToString(", ") { it.address })
            RecipientsVerdict.Match -> FieldCheck.Ok(field)
            is RecipientsVerdict.Foreign -> FieldCheck.Mismatch(field, "it also shows ${verdict.addresses.joinToString(", ")}")
            is RecipientsVerdict.Incomplete -> FieldCheck.Mismatch(field, "it doesn't show ${verdict.missing.joinToString(", ")}")
            RecipientsVerdict.Other -> FieldCheck.Mismatch(field, "it shows \"${TextNorm.truncate(shown, 80)}\"")
        }
    }

    /** How a recipient field's [shown] text relates to the [expected] recipients. */
    sealed interface RecipientsVerdict {
        data object Empty : RecipientsVerdict

        /** Every expected recipient is shown (by address, or by name as a chip). */
        data object Match : RecipientsVerdict

        /** Addresses that are not ours. */
        data class Foreign(val addresses: List<String>) : RecipientsVerdict

        /** Some of ours are shown, these aren't. */
        data class Incomplete(val missing: List<String>) : RecipientsVerdict

        /** Text that is neither empty nor recognizably ours. */
        data object Other : RecipientsVerdict
    }

    fun recipientsVerdict(shown: String, expected: List<EmailRecipient>): RecipientsVerdict {
        val text = TextNorm.clean(shown).trim()
        if (text.isEmpty()) return RecipientsVerdict.Empty
        val found = EmailAddresses.FIND.findAll(text).map { it.value.lowercase(Locale.ROOT) }.toSet()
        val ours = expected.map { it.key }.toSet()
        val foreign = found.filter { it !in ours }
        if (foreign.isNotEmpty()) return RecipientsVerdict.Foreign(foreign.toList())
        val textKey = TextNorm.key(text)
        val missing = expected.filter { r -> r.key !in found && (r.name.isNullOrBlank() || !textKey.contains(TextNorm.key(r.name))) }
        return when {
            missing.isEmpty() -> RecipientsVerdict.Match
            missing.size < expected.size -> RecipientsVerdict.Incomplete(missing.map { it.address })
            else -> RecipientsVerdict.Other
        }
    }

    private fun subject(screen: ComposeScreen, expected: String): FieldCheck {
        val node = screen.fields[ComposeField.SUBJECT]
            ?: return if (expected.isBlank()) FieldCheck.Ok(ComposeField.SUBJECT) else FieldCheck.Missing(ComposeField.SUBJECT)
        val shown = normalize(screen.shown[ComposeField.SUBJECT] ?: EmailCompose.fieldText(node))
        val want = normalize(expected)
        return when {
            shown == want -> FieldCheck.Ok(ComposeField.SUBJECT)
            shown.isEmpty() -> FieldCheck.Fill(ComposeField.SUBJECT, expected)
            else -> FieldCheck.Mismatch(ComposeField.SUBJECT, "it shows \"${TextNorm.truncate(shown, 80)}\"")
        }
    }

    private fun body(screen: ComposeScreen, expected: String): FieldCheck {
        val want = normalize(expected)
        if (ComposeField.BODY !in screen.fields) {
            // Some apps render the body outside an editable (rich editors): accept it if it is shown anywhere.
            return if (occurrences(normalize(screen.allText), want) > 0) FieldCheck.Ok(ComposeField.BODY) else FieldCheck.Missing(ComposeField.BODY)
        }
        val shown = normalize(screen.shown[ComposeField.BODY].orEmpty())
        return when (occurrences(shown, want)) {
            1 -> FieldCheck.Ok(ComposeField.BODY)
            0 -> if (shown.isEmpty()) FieldCheck.Fill(ComposeField.BODY, expected) else FieldCheck.Mismatch(ComposeField.BODY, "it shows other text")
            // The app read the text from both the mailto: URI and the extras.
            else -> FieldCheck.Fill(ComposeField.BODY, expected)
        }
    }

    /** Invisible marks removed, whitespace runs collapsed, trimmed (apps re-wrap and trim text). */
    fun normalize(s: String): String = TextNorm.clean(s).replace(WHITESPACE, " ").trim()

    /** Non-overlapping occurrences of [needle] in [haystack] (0 for an empty needle). */
    fun occurrences(haystack: String, needle: String): Int {
        if (needle.isEmpty()) return 0
        var count = 0
        var from = 0
        while (true) {
            val at = haystack.indexOf(needle, from)
            if (at < 0) return count
            count++
            from = at + needle.length
        }
    }

    private val WHITESPACE = Regex("\\s+")

    /** Agent-facing explanation of the first blocking check, or null when nothing blocks. */
    fun problem(checks: List<FieldCheck>, appLabel: String): String? {
        val bad = checks.firstOrNull { it is FieldCheck.Mismatch || it is FieldCheck.Missing } ?: return null
        val name = bad.field.label
        return when (bad) {
            is FieldCheck.Mismatch -> "$appLabel's compose screen doesn't match the approved email ($name field: ${bad.reason}), so nothing was sent."
            else -> "Could not find the $name field on $appLabel's compose screen, so the approved email could not be verified and nothing was sent."
        }
    }
}
