package dev.phonebridge.android.tools.comms

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NameMatchingTest {
    private fun score(query: String, name: String) = NameMatching.score(query, name)

    /** Strong enough to be picked as a recipient without asking. */
    private fun assertAutoPick(query: String, name: String) {
        val s = score(query, name)
        assertTrue("'$query' vs '$name' scored $s, expected >= ${NameMatching.AUTO_PICK}", s >= NameMatching.AUTO_PICK)
    }

    /** Listed as a candidate, but never picked on its own. */
    private fun assertCandidateOnly(query: String, name: String) {
        val s = score(query, name)
        assertTrue(
            "'$query' vs '$name' scored $s, expected in [${NameMatching.FUZZY_MIN}, ${NameMatching.AUTO_PICK})",
            s >= NameMatching.FUZZY_MIN && s < NameMatching.AUTO_PICK,
        )
    }

    private fun assertNoMatch(query: String, name: String) {
        val s = score(query, name)
        assertTrue("'$query' vs '$name' scored $s, expected no match", s < NameMatching.FUZZY_MIN)
    }

    @Test
    fun normalizeRemovesCaseAccentsPunctuationAndExtraSpaces() {
        assertEquals("jose obrien smith", NameMatching.normalize("  José  O'Brien-Smith "))
        assertEquals("francois muller", NameMatching.normalize("FRANÇOIS Müller"))
        assertEquals("mom", NameMatching.normalize("Mom ❤️"))
        assertEquals("dr j r ewing", NameMatching.normalize("Dr. J.R. Ewing"))
        // Hebrew niqqud and Arabic harakat are combining marks.
        assertEquals("טניה", NameMatching.normalize("טָנְיָה"))
        assertEquals("محمد", NameMatching.normalize("مُحَمَّد"))
        // Full-width letters fold to ASCII (NFKD).
        assertEquals("tania", NameMatching.normalize("Ｔａｎｉａ"))
    }

    @Test
    fun transliterationAndPhoneticKeys() {
        assertEquals("tanya", NameMatching.transliterate("Таня"))
        assertEquals("giorgos", NameMatching.transliterate("Γιώργος"))
        assertEquals("tnia", NameMatching.transliterate("טניה"))
        assertEquals("tania", NameMatching.phonetic("Tanya"))
        assertEquals("tania", NameMatching.phonetic("Tanja"))
        assertEquals("filip", NameMatching.phonetic("Phillip"))
        assertEquals("sara", NameMatching.phonetic("Sarah"))
    }

    @Test
    fun exactMatchesScoreOne() {
        assertEquals(1.0, score("tania cohen", "Tania Cohen"), 0.0)
        assertEquals(1.0, score("Jose", "José"), 0.0)
        assertEquals(1.0, score("JeanLuc", "Jean-Luc"), 0.0)
        assertEquals(1.0, score("o'brien", "OBrien"), 0.0)
        assertEquals(1.0, score("טניה", "טָנְיָה"), 0.0)
    }

    @Test
    fun structuralTiers() {
        assertEquals(0.90, score("Tan", "Tania Cohen"), 1e-9)
        assertEquals(0.90, score("Tania", "Tania Cohen"), 1e-9)
        assertEquals(0.90, score("Tania C", "Tania Cohen"), 1e-9)
        assertEquals(0.87, score("Cohen Tania", "Tania Cohen"), 1e-9)
        assertEquals(0.87, score("Cohen", "Tania Cohen"), 1e-9)
        assertEquals(0.85, score("Coh", "Tania Cohen"), 1e-9)
        assertEquals(0.85, score("T Coh", "Tania Cohen"), 1e-9)
        // A longer query that contains the whole name is a candidate, never an automatic pick.
        assertEquals(0.78, score("Tania Cohen", "Tania"), 1e-9)
    }

    @Test
    fun latinSpellingVariants() {
        assertEquals(0.95, score("Tanya", "Tania"), 1e-9)
        listOf(
            "Tanja" to "Tania", "Philip" to "Filip", "Phillip" to "Philip", "Catherine" to "Katherine",
            "Sarah" to "Sara", "Jenny" to "Jennie", "Tzvi" to "Zvi", "Mikhail" to "Mihail", "Yevgeny" to "Evgeny",
            "Tanya" to "Tania Cohen",
        ).forEach { (query, name) -> assertAutoPick(query, name) }
    }

    @Test
    fun hebrewNamesMatchLatinSpellings() {
        listOf(
            "Tania" to "טניה", "Tanya" to "טניה", "Tania" to "טניה כהן", "Tania Cohen" to "טניה כהן",
            "Moshe" to "משה", "Avi" to "אבי", "Michal" to "מיכל", "Yossi" to "יוסי", "Eitan" to "איתן",
            "Chaim" to "חיים", "Haim" to "חיים", "Yael" to "יעל", "Sara" to "שרה", "Jenny" to "ג׳ני",
            "Jonathan" to "יונתן", "David" to "דוד",
        ).forEach { (query, name) -> assertAutoPick(query, name) }
        // Hebrew query, Latin contact.
        assertAutoPick("טניה", "Tania Cohen")
        assertAutoPick("מיכל", "Michal")
    }

    @Test
    fun hebrewVowelLettersMustAgree() {
        // ו (o/u/v) in "Toni" can't stand for the 'a' of Tania; איתן starts with a vowel letter.
        assertNoMatch("Tania", "טוני")
        assertNoMatch("Tania", "איתן")
        assertNoMatch("Dana", "רונית")
    }

    @Test
    fun cyrillicNames() {
        assertEquals(0.97, score("Tanya", "Таня"), 1e-9)
        listOf(
            "Tania" to "Таня", "Sergey" to "Сергей", "Aleksandr" to "Александр", "Ivan Petrov" to "Иван Петров",
            "Elena" to "Елена", "Mikhail" to "Михаил", "Oksana" to "Оксана",
        ).forEach { (query, name) -> assertAutoPick(query, name) }
        assertAutoPick("Таня", "Tanya Cohen")
    }

    @Test
    fun greekNames() {
        listOf("Giorgos" to "Γιώργος", "Nikos" to "ΝΙΚΟΣ", "Dimitris" to "Δημήτρης", "Maria" to "Μαρία", "Bampis" to "Μπάμπης")
            .forEach { (query, name) -> assertAutoPick(query, name) }
        // Final sigma and capital sigma are the same letter.
        assertEquals(1.0, score("Νίκος", "ΝΙΚΟΣ"), 0.0)
    }

    @Test
    fun arabicNames() {
        listOf(
            "Mohammed" to "محمد", "Muhammad" to "محمد", "Mohamed" to "مُحَمَّد", "Fatima" to "فاطمة", "Ahmed" to "أحمد",
            "Omar" to "عمر", "Youssef" to "يوسف", "Ali" to "علي",
        ).forEach { (query, name) -> assertAutoPick(query, name) }
    }

    @Test
    fun fuzzyMatchesAreCandidatesOnly() {
        assertCandidateOnly("Daniela", "Daniel")
        assertCandidateOnly("Katherine", "Kathryn")
        assertCandidateOnly("Mikhail", "Michael")
        assertCandidateOnly("Aleksander", "Александр")
    }

    @Test
    fun unrelatedNamesDoNotMatch() {
        assertNoMatch("Tania", "Tamar")
        assertNoMatch("Tania", "Tina")
        assertNoMatch("Dan", "Ronit")
        assertNoMatch("Tania Cohen", "Tania Levi")
        assertNoMatch("Moshe", "Таня")
    }

    @Test
    fun nicknamesAndAlternativeNamesViaBestScore() {
        val query = NameKey.of("Mom")
        val names = listOf("Tania Cohen", "Cohen, Tania", "Mom").map(NameKey::of)
        assertEquals(1.0, NameMatching.bestScore(query, names), 0.0)
        assertEquals(1.0, NameMatching.bestScore(NameKey.of("cohen tania"), names), 0.0)
    }

    @Test
    fun emptyAndSymbolOnlyInputsScoreZero() {
        assertEquals(0.0, score("!!", "Tania"), 0.0)
        assertEquals(0.0, score("Tania", ""), 0.0)
        assertEquals(0.0, score("", ""), 0.0)
        assertTrue(NameKey.of("❤️ :)").isEmpty)
    }
}
