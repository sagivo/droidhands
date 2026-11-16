package com.droidhands.android.ui.onboarding

import android.Manifest
import com.droidhands.android.ui.setup.SetupItem
import com.droidhands.android.ui.setup.SetupLogic
import com.droidhands.android.ui.setup.SetupStatus

/** Pages of the first-run wizard, in order. */
enum class OnboardingStep(val title: String) {
    WELCOME("Welcome"),
    PERMISSIONS("Permissions"),
    CONNECT("Connect"),
    AGENT("Add an agent"),
    ;

    val number: Int get() = ordinal + 1

    fun next(): OnboardingStep? = entries.getOrNull(ordinal + 1)

    fun previous(): OnboardingStep? = entries.getOrNull(ordinal - 1)

    companion object {
        fun fromName(name: String?): OnboardingStep = entries.firstOrNull { it.name == name } ?: WELCOME
    }
}

/** Pure decisions of the onboarding wizard (unit-tested). */
object OnboardingFlow {
    /**
     * Show the wizard on first run only: not once it was finished or skipped, and not for people
     * who already set DroidHands up before the wizard existed (they have a connection or an agent).
     */
    fun shouldShow(completed: Boolean, hasTokens: Boolean, transportConfigured: Boolean): Boolean =
        !completed && !hasTokens && !transportConfigured

    /** Special-access rows, in the order the wizard walks them: essentials first. */
    val SPECIAL_ACCESS_ORDER: List<String> = listOf(
        SetupLogic.ACCESSIBILITY,
        SetupLogic.NOTIFICATION_ACCESS,
        SetupLogic.BATTERY,
        SetupLogic.OVERLAY,
        SetupLogic.DND,
    )

    fun isSpecialAccess(item: SetupItem): Boolean = item.id in SPECIAL_ACCESS_ORDER

    /**
     * Runtime permissions "Grant all" asks for in one go (Android then shows its dialogs one after
     * another): every missing permission of rows Android will still prompt for. Background location
     * is left out: Android 11+ requires asking for it on its own, after foreground location.
     */
    fun runtimeToRequest(items: List<SetupItem>): List<String> = items
        .filter { it.permissions.isNotEmpty() && !it.blocked && it.status != SetupStatus.DONE && it.id != SetupLogic.BACKGROUND_LOCATION }
        .flatMap { it.permissions }
        .filterNot { it == Manifest.permission.ACCESS_BACKGROUND_LOCATION }
        .distinct()

    /** Runtime rows still missing that need the system App info page (Android stopped asking). */
    fun blockedRuntime(items: List<SetupItem>): List<SetupItem> =
        items.filter { !isSpecialAccess(it) && it.blocked && it.status != SetupStatus.DONE }

    /**
     * The special access the wizard highlights next: the first missing one in [SPECIAL_ACCESS_ORDER]
     * that the user didn't skip. When the user comes back from system settings and the row reads
     * DONE, this moves on by itself (auto-advance).
     */
    fun nextSpecialAccess(items: List<SetupItem>, skipped: Set<String>): SetupItem? {
        val byId = items.associateBy { it.id }
        return SPECIAL_ACCESS_ORDER.firstNotNullOfOrNull { id ->
            byId[id]?.takeIf { it.status != SetupStatus.DONE && id !in skipped }
        }
    }

    /** True when nothing essential is missing (optional rows may still be open). */
    fun essentialsDone(items: List<SetupItem>): Boolean = SetupLogic.progress(items).essentialComplete

    /** "3 of 5 special accesses granted" style progress for the special-access list. */
    fun specialProgress(items: List<SetupItem>): Pair<Int, Int> {
        val special = items.filter(::isSpecialAccess)
        return special.count { it.status == SetupStatus.DONE } to special.size
    }
}
