package com.droidhands.android.tools.system

import com.droidhands.core.tools.ToolException

/** Provider choice, time budgets and address formatting for `location_get` (pure). */
object LocationLogic {
    // Same values as LocationManager.*_PROVIDER.
    const val FUSED = "fused"
    const val GPS = "gps"
    const val NETWORK = "network"
    const val PASSIVE = "passive"

    const val DEFAULT_TIMEOUT_SECONDS = 20
    const val MAX_TIMEOUT_SECONDS = 60

    enum class Accuracy(val arg: String) {
        HIGH("high"),
        BALANCED("balanced"),
        ;

        companion object {
            fun parse(raw: String?): Accuracy = when (raw?.let(::normalizeEnumArg)) {
                null, "", "balanced", "medium", "low", "coarse", "approximate" -> BALANCED
                "high", "fine", "precise", "best", "gps" -> HIGH
                else -> throw ToolException("Argument 'accuracy' must be high or balanced, got '$raw'.")
            }
        }
    }

    fun timeoutSeconds(raw: Int?): Int = (raw ?: DEFAULT_TIMEOUT_SECONDS).coerceIn(1, MAX_TIMEOUT_SECONDS)

    /**
     * Providers to ask for a fresh fix, best first, restricted to [enabled] ones. The fused
     * provider (API 31+) already blends GPS/Wi-Fi/cell, so it comes first; otherwise GPS for high
     * accuracy, network for balanced. Before API 31 the gps provider needs fine location.
     */
    fun providerOrder(accuracy: Accuracy, sdkInt: Int, enabled: Set<String>, hasFine: Boolean): List<String> {
        val order = buildList {
            if (sdkInt >= 31) add(FUSED)
            if (accuracy == Accuracy.HIGH) addAll(listOf(GPS, NETWORK)) else addAll(listOf(NETWORK, GPS))
        }
        return order.filter { it in enabled && (it != GPS || hasFine || sdkInt >= 31) }
    }

    /**
     * Time for the next attempt. All but the last attempt get two thirds of what is left, so a
     * provider that never answers (GPS indoors) still leaves time for the next one.
     */
    fun attemptTimeoutMs(remainingMs: Long, attemptsLeft: Int): Long =
        if (attemptsLeft <= 1) remainingMs.coerceAtLeast(0) else (remainingMs * 2 / 3).coerceAtLeast(0)

    /** Freshest item by [elapsedNanos] (monotonic clock); ties go to the better [accuracyM]. */
    fun <T> freshest(items: List<T>, elapsedNanos: (T) -> Long, accuracyM: (T) -> Float?): T? =
        items.maxWithOrNull(compareBy<T> { elapsedNanos(it) }.thenByDescending { accuracyM(it) ?: Float.MAX_VALUE })

    /**
     * One-line address: the geocoder's own formatted lines when present, otherwise assembled from
     * parts ("12 Main St, Springfield, IL 62701, USA"). Null when nothing is known.
     */
    fun formatAddress(
        lines: List<String?>,
        street: String? = null,
        number: String? = null,
        locality: String? = null,
        adminArea: String? = null,
        postalCode: String? = null,
        country: String? = null,
    ): String? {
        val formatted = lines.mapNotNull { it?.trim()?.takeIf(String::isNotEmpty) }
        if (formatted.isNotEmpty()) return formatted.joinToString(", ")
        val streetLine = listOfNotNull(number?.trim(), street?.trim()).filter { it.isNotEmpty() }.joinToString(" ")
        val region = listOfNotNull(adminArea?.trim(), postalCode?.trim()).filter { it.isNotEmpty() }.joinToString(" ")
        return listOf(streetLine, locality?.trim().orEmpty(), region, country?.trim().orEmpty())
            .filter { it.isNotEmpty() }
            .distinct()
            .joinToString(", ")
            .ifEmpty { null }
    }
}
