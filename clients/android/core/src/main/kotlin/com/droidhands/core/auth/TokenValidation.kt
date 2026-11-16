package com.droidhands.core.auth

import com.droidhands.core.tools.Scopes

/** Validation of user-editable token settings (pure). Messages are meant for the app's UI. */
internal object TokenValidation {
    const val MINUTES_PER_DAY = 24 * 60

    /** @throws IllegalArgumentException naming the unknown scopes. */
    fun requireKnownScopes(scopes: Set<String>) {
        val unknown = scopes.filterNot { it in Scopes.ALL }
        require(unknown.isEmpty()) { "Unknown scopes: ${unknown.sorted().joinToString(", ")}" }
    }

    /**
     * @throws IllegalArgumentException if [hours] is not a usable window (see [ActiveHours] and
     *   [com.droidhands.core.policy.ActiveWindow.isValid]).
     */
    fun requireValid(hours: ActiveHours) {
        require(hours.startMinute in 0 until MINUTES_PER_DAY) { "Active hours start must be 00:00-23:59" }
        require(hours.endMinute in 0 until MINUTES_PER_DAY) { "Active hours end must be 00:00-23:59" }
        require(hours.startMinute != hours.endMinute) { "Active hours start and end must differ (turn active hours off for always)" }
    }

    /** Checks the user-editable fields of [record]. */
    fun requireValid(record: TokenRecord) {
        require(record.name.isNotBlank()) { "Token name must not be blank" }
        requireKnownScopes(record.scopes)
        record.activeHours?.let(::requireValid)
    }
}
