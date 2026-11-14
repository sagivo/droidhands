package dev.phonebridge.android.update

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull

/**
 * A release version `MAJOR.MINOR.PATCH[-pre.release][+build]`, ordered by SemVer 2.0 precedence:
 * numbers compare numerically, a pre-release (`1.2.0-rc.1`) comes before its release (`1.2.0`),
 * and build metadata is ignored.
 */
data class ReleaseVersion(val major: Int, val minor: Int, val patch: Int, val preRelease: List<String> = emptyList()) :
    Comparable<ReleaseVersion> {
    override fun compareTo(other: ReleaseVersion): Int {
        compareValues(major, other.major).takeIf { it != 0 }?.let { return it }
        compareValues(minor, other.minor).takeIf { it != 0 }?.let { return it }
        compareValues(patch, other.patch).takeIf { it != 0 }?.let { return it }
        // A release outranks its pre-releases.
        if (preRelease.isEmpty() || other.preRelease.isEmpty()) return compareValues(other.preRelease.size, preRelease.size)
        for (i in 0 until minOf(preRelease.size, other.preRelease.size)) {
            comparePart(preRelease[i], other.preRelease[i]).takeIf { it != 0 }?.let { return it }
        }
        return compareValues(preRelease.size, other.preRelease.size)
    }

    /** "1.2.3" or "1.2.3-rc.1" (no leading "v", no build metadata). */
    override fun toString(): String = "$major.$minor.$patch" + if (preRelease.isEmpty()) "" else "-" + preRelease.joinToString(".")

    companion object {
        private val PATTERN = Regex("^[vV]?(0|[1-9]\\d{0,8})\\.(0|[1-9]\\d{0,8})\\.(0|[1-9]\\d{0,8})(?:-([0-9A-Za-z-]+(?:\\.[0-9A-Za-z-]+)*))?(?:\\+[0-9A-Za-z.-]*)?$")

        /** Parses "1.2.3", "v1.2.3" or "1.2.3-rc.1+build.5" (surrounding spaces ignored); null if it isn't a version. */
        fun parse(text: String): ReleaseVersion? {
            val match = PATTERN.matchEntire(text.trim()) ?: return null
            val (major, minor, patch, pre) = match.destructured
            return ReleaseVersion(major.toInt(), minor.toInt(), patch.toInt(), if (pre.isEmpty()) emptyList() else pre.split('.'))
        }

        /** Numeric identifiers sort before alphanumeric ones, and compare as numbers. */
        private fun comparePart(a: String, b: String): Int {
            val an = a.toLongOrNull()
            val bn = b.toLongOrNull()
            return when {
                an != null && bn != null -> compareValues(an, bn)
                an != null -> -1
                bn != null -> 1
                else -> a.compareTo(b)
            }
        }
    }
}

/**
 * Pure decisions of the in-app update check (unit-tested). PhoneBridge is installed from an APK,
 * not an app store, so it looks at the project's GitHub Releases itself; see [UpdateChecker].
 */
object UpdateLogic {
    /** At most one check a day. */
    const val CHECK_INTERVAL_MS: Long = 24 * 60 * 60 * 1000L

    /** The stable asset name the release workflow uploads next to `PhoneBridge-<version>.apk`. */
    const val APK_ASSET = "PhoneBridge.apk"

    private val REPO = Regex("^[A-Za-z0-9_.-]{1,100}/[A-Za-z0-9_.-]{1,100}$")
    private val json = Json { ignoreUnknownKeys = true }

    /** True for a GitHub "owner/name" (the build's `GITHUB_REPO`). */
    fun isValidRepo(repo: String): Boolean = REPO.matches(repo) && ".." !in repo

    /** GitHub REST endpoint of the newest published release (drafts and pre-releases excluded). */
    fun latestReleaseApiUrl(repo: String): String = "https://api.github.com/repos/$repo/releases/latest"

    /** Stable link to the newest release's APK, opened in the browser by the Download button. */
    fun downloadUrl(repo: String): String = "https://github.com/$repo/releases/latest/download/$APK_ASSET"

    /** True when the last attempt is a day old, never happened, or lies in the future (the clock was changed). */
    fun isDue(now: Long, lastCheckedAt: Long): Boolean =
        lastCheckedAt <= 0L || now < lastCheckedAt || now - lastCheckedAt >= CHECK_INTERVAL_MS

    /**
     * The version of a GitHub "latest release" response (its `tag_name`, e.g. "v1.3.0"), or null
     * for a draft, a pre-release, a tag that isn't a version, or unexpected JSON.
     */
    fun parseLatestRelease(body: String): ReleaseVersion? {
        val release = try {
            json.parseToJsonElement(body) as? JsonObject
        } catch (e: Exception) {
            null
        } ?: return null
        fun flag(name: String) = (release[name] as? JsonPrimitive)?.booleanOrNull == true
        if (flag("draft") || flag("prerelease")) return null
        val tag = (release["tag_name"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return null
        return ReleaseVersion.parse(tag)
    }

    /**
     * The version to offer on Home, e.g. "1.3.0": [latest] when it is newer than [current] and not
     * the version the user dismissed. Null otherwise, or when a version can't be read (an
     * unparsable build version never shows the card, rather than nagging forever).
     */
    fun availableUpdate(latest: String?, current: String, dismissed: String?): String? {
        val newest = latest?.let(ReleaseVersion::parse) ?: return null
        val installed = ReleaseVersion.parse(current) ?: return null
        if (newest <= installed) return null
        if (dismissed != null && ReleaseVersion.parse(dismissed) == newest) return null
        return newest.toString()
    }
}
