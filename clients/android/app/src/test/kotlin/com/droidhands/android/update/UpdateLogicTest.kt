package com.droidhands.android.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateLogicTest {
    private fun v(text: String) = requireNotNull(ReleaseVersion.parse(text)) { text }

    @Test
    fun parsesVersionsAndTags() {
        assertEquals(ReleaseVersion(1, 2, 3), ReleaseVersion.parse("1.2.3"))
        assertEquals(ReleaseVersion(1, 2, 3), ReleaseVersion.parse("v1.2.3"))
        assertEquals(ReleaseVersion(1, 2, 3), ReleaseVersion.parse(" V1.2.3 "))
        assertEquals(ReleaseVersion(0, 10, 0, listOf("rc", "1")), ReleaseVersion.parse("0.10.0-rc.1"))
        assertEquals(ReleaseVersion(1, 0, 0), ReleaseVersion.parse("1.0.0+build.7"))
        assertEquals("1.2.3-beta.2", v("v1.2.3-beta.2+sha.abc").toString())
        listOf("", "1", "1.2", "1.2.3.4", "01.2.3", "1.2.x", "vv1.2.3", "1.2.3-", "1.2.3-rc..1", "release-1.2.3", "9999999999.0.0").forEach {
            assertNull(it, ReleaseVersion.parse(it))
        }
    }

    @Test
    fun comparesBySemverPrecedence() {
        // Numbers compare numerically, not as text.
        assertTrue(v("0.10.0") > v("0.9.9"))
        assertTrue(v("1.0.0") > v("0.99.99"))
        assertTrue(v("1.2.10") > v("1.2.9"))
        // A pre-release comes before its release, and after the previous release.
        assertTrue(v("1.2.0-rc.1") < v("1.2.0"))
        assertTrue(v("1.2.0-rc.1") > v("1.1.9"))
        // SemVer 2.0 §11 example order.
        val ordered = listOf("1.0.0-alpha", "1.0.0-alpha.1", "1.0.0-alpha.beta", "1.0.0-beta", "1.0.0-beta.2", "1.0.0-beta.11", "1.0.0-rc.1", "1.0.0")
        assertEquals(ordered, ordered.reversed().map(::v).sorted().map { it.toString() })
        // Build metadata doesn't count; a "v" doesn't either.
        assertEquals(0, v("1.0.0+a").compareTo(v("v1.0.0+b")))
    }

    @Test
    fun checksAtMostOnceADay() {
        val day = UpdateLogic.CHECK_INTERVAL_MS
        assertTrue("never checked", UpdateLogic.isDue(now = 1_000, lastCheckedAt = 0))
        assertFalse(UpdateLogic.isDue(now = 10 * day, lastCheckedAt = 10 * day - 1))
        assertFalse(UpdateLogic.isDue(now = 10 * day + day - 1, lastCheckedAt = 10 * day))
        assertTrue(UpdateLogic.isDue(now = 11 * day, lastCheckedAt = 10 * day))
        assertTrue("the clock went back", UpdateLogic.isDue(now = 5 * day, lastCheckedAt = 10 * day))
    }

    @Test
    fun readsTheLatestReleaseFromGitHub() {
        val body = """{"url":"…","tag_name":"v1.4.0","name":"DroidHands 1.4.0","draft":false,"prerelease":false,
            |"assets":[{"name":"DroidHands.apk","size":123}],"body":"Notes"}""".trimMargin()
        assertEquals(ReleaseVersion(1, 4, 0), UpdateLogic.parseLatestRelease(body))
        assertNull(UpdateLogic.parseLatestRelease("""{"tag_name":"v2.0.0","draft":true}"""))
        assertNull(UpdateLogic.parseLatestRelease("""{"tag_name":"v2.0.0-rc.1","prerelease":true}"""))
        assertNull(UpdateLogic.parseLatestRelease("""{"tag_name":"nightly"}"""))
        assertNull(UpdateLogic.parseLatestRelease("""{"tag_name":7}"""))
        assertNull(UpdateLogic.parseLatestRelease("""{"message":"Not Found"}"""))
        assertNull(UpdateLogic.parseLatestRelease("""[1,2]"""))
        assertNull(UpdateLogic.parseLatestRelease("<html>rate limited</html>"))
        assertNull(UpdateLogic.parseLatestRelease(""))
    }

    @Test
    fun offersOnlyNewerVersionsNotDismissed() {
        assertEquals("1.3.0", UpdateLogic.availableUpdate(latest = "1.3.0", current = "1.2.9", dismissed = null))
        assertEquals("1.3.0", UpdateLogic.availableUpdate(latest = "v1.3.0", current = "1.2.9", dismissed = "1.2.10"))
        assertNull("same version", UpdateLogic.availableUpdate(latest = "1.2.9", current = "1.2.9", dismissed = null))
        assertNull("older release (e.g. a newer local build)", UpdateLogic.availableUpdate(latest = "1.2.0", current = "1.2.9", dismissed = null))
        assertNull("dismissed", UpdateLogic.availableUpdate(latest = "1.3.0", current = "1.2.9", dismissed = "1.3.0"))
        assertNull("dismissed as a tag", UpdateLogic.availableUpdate(latest = "1.3.0", current = "1.2.9", dismissed = "v1.3.0"))
        assertEquals("a later release shows again", "1.4.0", UpdateLogic.availableUpdate(latest = "1.4.0", current = "1.2.9", dismissed = "1.3.0"))
        assertNull("nothing known yet", UpdateLogic.availableUpdate(latest = null, current = "1.2.9", dismissed = null))
        assertNull("unreadable build version", UpdateLogic.availableUpdate(latest = "1.3.0", current = "dev", dismissed = null))
        assertEquals("the release of a pre-release build", "1.3.0", UpdateLogic.availableUpdate(latest = "1.3.0", current = "1.3.0-rc.2", dismissed = null))
    }

    @Test
    fun repositoryAndUrls() {
        assertTrue(UpdateLogic.isValidRepo("droidhands/droidhands"))
        assertTrue(UpdateLogic.isValidRepo("some-user/Phone.Bridge_2"))
        listOf("", "droidhands", "a/b/c", "../x", "a/..", "a b/c", "a/b?x=1", "https://github.com/a/b").forEach {
            assertFalse(it, UpdateLogic.isValidRepo(it))
        }
        assertEquals("https://api.github.com/repos/o/r/releases/latest", UpdateLogic.latestReleaseApiUrl("o/r"))
        assertEquals("https://github.com/o/r/releases/latest/download/DroidHands.apk", UpdateLogic.downloadUrl("o/r"))
    }
}
