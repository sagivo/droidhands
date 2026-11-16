package dev.phonebridge.android.tools.comms

import dev.phonebridge.android.tools.support.PhoneEntry
import dev.phonebridge.android.tools.support.ContactAppEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ContactRankingTest {
    @Test
    fun starredBoostIsSmallAndNeverCreatesAnExactMatch() {
        assertEquals(0.91, ContactRanking.boost(0.9, starred = true), 1e-9)
        assertEquals(0.9, ContactRanking.boost(0.9, starred = false), 0.0)
        assertEquals(1.0, ContactRanking.boost(1.0, starred = true), 0.0)
        assertEquals(0.99, ContactRanking.boost(0.985, starred = true), 1e-9)
        // A capped fuzzy match stays below the automatic-pick threshold.
        assertEquals(true, ContactRanking.boost(NameMatching.FUZZY_CAP, starred = true) < NameMatching.AUTO_PICK)
    }

    @Test
    fun orderIsScoreThenStarredThenName() {
        data class Hit(val name: String, val score: Double, val starred: Boolean)
        val hits = listOf(Hit("bob", 0.9, false), Hit("Alice", 0.9, false), Hit("Zed", 0.9, true), Hit("Top", 1.0, false))
        val sorted = hits.sortedWith(ContactRanking.order({ it.score }, { it.starred }, { it.name }))
        assertEquals(listOf("Top", "Zed", "Alice", "bob"), sorted.map { it.name })
    }

    @Test
    fun phoneScores() {
        assertEquals(ContactRanking.PHONE_SAME_NUMBER, ContactRanking.phoneScore("054-123-4567", "+972 54 123 4567", null), 0.0)
        assertEquals(ContactRanking.PHONE_SAME_NUMBER, ContactRanking.phoneScore("0541234567", null, "+972541234567"), 0.0)
        assertEquals(ContactRanking.PHONE_PARTIAL, ContactRanking.phoneScore("4567", "054-123-4567", null), 0.0)
        assertEquals(ContactRanking.PHONE_PARTIAL, ContactRanking.phoneScore("054-12", null, "+972541234567"), 0.0)
        assertEquals(0.0, ContactRanking.phoneScore("456", "054-123-4567", null), 0.0) // too short to be partial
        assertEquals(0.0, ContactRanking.phoneScore("9999", "054-123-4567", "+972541234567"), 0.0)
    }

    @Test
    fun emailScores() {
        assertEquals(ContactRanking.EMAIL_EXACT, ContactRanking.emailScore("Tania@Example.com", "tania@example.com"), 0.0)
        assertEquals(ContactRanking.EMAIL_PREFIX, ContactRanking.emailScore("tania@", "tania@example.com"), 0.0)
        assertEquals(ContactRanking.EMAIL_OTHER, ContactRanking.emailScore("cohen@", "tania@example.com"), 0.0)
    }

    @Test
    fun duplicatePhonesAreMergedAndKeepTheSpecificType() {
        val phones = listOf(
            PhoneEntry("+972541234567", "+972541234567", "other"),
            PhoneEntry("054-123-4567", "+972541234567", "mobile"),
            PhoneEntry("03-1234567", null, "home"),
            PhoneEntry("03 123 4567", null, "work"),
        )
        assertEquals(
            listOf(PhoneEntry("+972541234567", "+972541234567", "mobile"), PhoneEntry("03-1234567", null, "home")),
            ContactRanking.dedupePhones(phones),
        )
    }

    @Test
    fun appRowsAreReadForAnyInstalledApp() {
        val rows = listOf(
            ContactAppRows.Row("com.chat", "vnd.android.cursor.item/vnd.com.chat.profile", "Chat", "Message +1 555 123 4567"),
            ContactAppRows.Row("com.chat", "vnd.android.cursor.item/vnd.com.chat.call", "Chat", "Voice call +1 555 123 4567"),
            ContactAppRows.Row("com.chat", "vnd.android.cursor.item/vnd.com.chat.profile", "Chat", "Message +1 555 123 4567"),
            ContactAppRows.Row("org.talk", "vnd.android.cursor.item/vnd.org.talk", "Talk", null),
            // Standard kinds and accounts that aren't installed apps (Google sync, OEM local storage) are not app entries.
            ContactAppRows.Row("com.chat", "vnd.android.cursor.item/phone_v2", "2", "+1 555 123 4567"),
            ContactAppRows.Row("com.google", "vnd.android.cursor.item/vnd.googleplus.profile", null, "Profile"),
            ContactAppRows.Row(null, "vnd.android.cursor.item/vnd.x", null, "x"),
        )
        val apps = ContactAppRows.entries(rows, isApp = { it != "com.google" }, label = { if (it == "com.chat") "Chat" else null })
        assertEquals(
            listOf(
                ContactAppEntry("com.chat", "Chat", listOf("Message +1 555 123 4567", "Voice call +1 555 123 4567")),
                ContactAppEntry("org.talk", null, listOf("Talk")),
            ),
            apps,
        )
    }
}
