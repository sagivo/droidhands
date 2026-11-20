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

/** Rollup of runtime permissions (excluding background location) for the single "App permissions" row. */
data class RuntimeSummary(
    val granted: Int,
    val total: Int,
    val toRequest: List<String>,
    val blocked: List<SetupItem>,
    val essentialsDone: Boolean,
) {
    val allDone: Boolean get() = total > 0 && granted == total
}

/** Pure decisions of the onboarding wizard (unit-tested). */
object OnboardingFlow {
    /** Logical id of the bundled "App permissions" row in the simple permissions checklist. */
    const val APP_PERMISSIONS_ID = "app_permissions"

    /**
     * The steps the wizard walks. With the build's default relay in use, the Connect step has nothing to do
     * (the phone goes online by itself), so it is left out: Welcome → Permissions → Add an agent. It stays
     * reachable from the agent step ("More ways to connect").
     */
    fun steps(defaultRelayInUse: Boolean): List<OnboardingStep> =
        if (defaultRelayInUse) OnboardingStep.entries - OnboardingStep.CONNECT else OnboardingStep.entries

    /** The step after [step] in [steps]; a step outside [steps] (Connect, opened by link) returns to the agent step. */
    fun next(step: OnboardingStep, steps: List<OnboardingStep>): OnboardingStep? {
        val index = steps.indexOf(step)
        return if (index < 0) OnboardingStep.AGENT else steps.getOrNull(index + 1)
    }

    /** The step before [step] in [steps], with the same rule as [next] for a step outside them. */
    fun previous(step: OnboardingStep, steps: List<OnboardingStep>): OnboardingStep? {
        val index = steps.indexOf(step)
        return if (index < 0) OnboardingStep.AGENT else steps.getOrNull(index - 1)
    }

    /**
     * Show the wizard on first run only: not once it was finished or skipped, and not for people
     * who already set DroidHands up before the wizard existed (they have a connection or an agent).
     */
    fun shouldShow(completed: Boolean, hasTokens: Boolean, transportConfigured: Boolean): Boolean =
        !completed && !hasTokens && !transportConfigured

    /** Essential special-access rows shown in the simple onboarding checklist, in order. */
    val ESSENTIAL_SPECIAL_ORDER: List<String> = listOf(
        SetupLogic.ACCESSIBILITY,
        SetupLogic.NOTIFICATION_ACCESS,
        SetupLogic.BATTERY,
    )

    /** All special-access rows, in the order the custom view lists them: essentials first. */
    val SPECIAL_ACCESS_ORDER: List<String> = ESSENTIAL_SPECIAL_ORDER + listOf(
        SetupLogic.OVERLAY,
        SetupLogic.DND,
    )

    fun isSpecialAccess(item: SetupItem): Boolean = item.id in SPECIAL_ACCESS_ORDER

    /**
     * Runtime permissions "Allow" asks for in one go (Android then shows its dialogs one after
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

    /** Summary of the requestable runtime rows for the bundled "App permissions" onboarding row. */
    fun runtimeSummary(items: List<SetupItem>): RuntimeSummary {
        val runtime = items.filter { !isSpecialAccess(it) && it.id != SetupLogic.BACKGROUND_LOCATION }
        return RuntimeSummary(
            granted = runtime.count { it.status == SetupStatus.DONE },
            total = runtime.size,
            toRequest = runtimeToRequest(items),
            blocked = blockedRuntime(items),
            essentialsDone = runtime.filter { it.essential }.all { it.status == SetupStatus.DONE },
        )
    }

    /**
     * The single row in the simple permissions checklist that gets the primary button, guiding the
     * user top-to-bottom without competing primary actions: App permissions until essential runtime
     * permissions are granted, then Accessibility → Notification access → Battery, then null once
     * essentials are done (so the bottom bar's Continue takes over).
     */
    fun nextPermissionStepId(items: List<SetupItem>): String? {
        val runtime = runtimeSummary(items)
        if (!runtime.essentialsDone && (runtime.toRequest.isNotEmpty() || runtime.blocked.isNotEmpty())) {
            return APP_PERMISSIONS_ID
        }
        val byId = items.associateBy { it.id }
        return ESSENTIAL_SPECIAL_ORDER.firstOrNull { id ->
            val item = byId[id]
            item != null && item.status != SetupStatus.DONE
        }
    }

    /** True when nothing essential is missing (optional rows may still be open). */
    fun essentialsDone(items: List<SetupItem>): Boolean = SetupLogic.progress(items).essentialComplete

    /** "3 of 5 special accesses granted" style progress for the custom special-access list. */
    fun specialProgress(items: List<SetupItem>): Pair<Int, Int> {
        val special = items.filter(::isSpecialAccess)
        return special.count { it.status == SetupStatus.DONE } to special.size
    }
}
