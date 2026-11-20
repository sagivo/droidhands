package com.droidhands.android.ui.onboarding

import android.Manifest
import com.droidhands.android.ui.setup.SetupItem
import com.droidhands.android.ui.setup.SetupLogic
import com.droidhands.android.ui.setup.SetupStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OnboardingFlowTest {
    private fun item(
        id: String,
        status: SetupStatus,
        permissions: List<String> = emptyList(),
        blocked: Boolean = false,
        essential: Boolean = id in SetupLogic.ESSENTIAL_IDS,
    ) = SetupItem(id, id, "", "", essential, status, permissions, blocked)

    private val items = listOf(
        item(SetupLogic.NOTIFICATIONS, SetupStatus.MISSING, listOf(Manifest.permission.POST_NOTIFICATIONS)),
        item(SetupLogic.CONTACTS, SetupStatus.DONE, listOf(Manifest.permission.READ_CONTACTS)),
        item("sms", SetupStatus.MISSING, listOf(Manifest.permission.READ_SMS, Manifest.permission.SEND_SMS), blocked = true),
        item(SetupLogic.LOCATION, SetupStatus.PARTIAL, listOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)),
        item(SetupLogic.BACKGROUND_LOCATION, SetupStatus.MISSING, listOf(Manifest.permission.ACCESS_BACKGROUND_LOCATION)),
        item(SetupLogic.ACCESSIBILITY, SetupStatus.DONE),
        item(SetupLogic.NOTIFICATION_ACCESS, SetupStatus.MISSING),
        item(SetupLogic.BATTERY, SetupStatus.MISSING),
        item(SetupLogic.OVERLAY, SetupStatus.MISSING),
        item(SetupLogic.DND, SetupStatus.DONE),
    )

    @Test
    fun showsOnlyOnAFreshInstall() {
        assertTrue(OnboardingFlow.shouldShow(completed = false, hasTokens = false, transportConfigured = false))
        assertFalse(OnboardingFlow.shouldShow(completed = true, hasTokens = false, transportConfigured = false))
        assertFalse(OnboardingFlow.shouldShow(completed = false, hasTokens = true, transportConfigured = false))
        assertFalse(OnboardingFlow.shouldShow(completed = false, hasTokens = false, transportConfigured = true))
    }

    @Test
    fun stepsInOrder() {
        assertEquals(OnboardingStep.PERMISSIONS, OnboardingStep.WELCOME.next())
        assertNull(OnboardingStep.AGENT.next())
        assertNull(OnboardingStep.WELCOME.previous())
        assertEquals(OnboardingStep.CONNECT, OnboardingStep.AGENT.previous())
        assertEquals(OnboardingStep.WELCOME, OnboardingStep.fromName("bogus"))
        assertEquals(OnboardingStep.CONNECT, OnboardingStep.fromName("CONNECT"))
        assertEquals(4, OnboardingStep.AGENT.number)
    }

    @Test
    fun theDefaultRelayLeavesOutTheConnectStep() {
        val short = OnboardingFlow.steps(defaultRelayInUse = true)
        assertEquals(listOf(OnboardingStep.WELCOME, OnboardingStep.PERMISSIONS, OnboardingStep.AGENT), short)
        assertEquals(OnboardingStep.AGENT, OnboardingFlow.next(OnboardingStep.PERMISSIONS, short))
        assertEquals(OnboardingStep.PERMISSIONS, OnboardingFlow.previous(OnboardingStep.AGENT, short))
        assertNull(OnboardingFlow.next(OnboardingStep.AGENT, short))
        // Connect opened from the agent step ("More ways to connect") leads back to it either way.
        assertEquals(OnboardingStep.AGENT, OnboardingFlow.next(OnboardingStep.CONNECT, short))
        assertEquals(OnboardingStep.AGENT, OnboardingFlow.previous(OnboardingStep.CONNECT, short))

        val full = OnboardingFlow.steps(defaultRelayInUse = false)
        assertEquals(OnboardingStep.entries, full)
        assertEquals(OnboardingStep.CONNECT, OnboardingFlow.next(OnboardingStep.PERMISSIONS, full))
        assertEquals(OnboardingStep.CONNECT, OnboardingFlow.previous(OnboardingStep.AGENT, full))
    }

    @Test
    fun grantAllSkipsGrantedBlockedAndBackgroundLocation() {
        assertEquals(
            listOf(Manifest.permission.POST_NOTIFICATIONS, Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION),
            OnboardingFlow.runtimeToRequest(items),
        )
        assertEquals(listOf("sms"), OnboardingFlow.blockedRuntime(items).map { it.id })
    }

    @Test
    fun runtimeSummaryCountsRequestableRowsExcludingBackgroundLocation() {
        val summary = OnboardingFlow.runtimeSummary(items)
        assertEquals(1, summary.granted)
        assertEquals(4, summary.total)
        assertFalse(summary.allDone)
        assertFalse(summary.essentialsDone)

        val essentialsGranted = items.map {
            if (it.id == SetupLogic.NOTIFICATIONS) it.copy(status = SetupStatus.DONE) else it
        }
        val partialSummary = OnboardingFlow.runtimeSummary(essentialsGranted)
        assertEquals(2, partialSummary.granted)
        assertFalse(partialSummary.allDone)
        assertTrue(partialSummary.essentialsDone)

        val allRuntimeGranted = items.map {
            if (!OnboardingFlow.isSpecialAccess(it) && it.id != SetupLogic.BACKGROUND_LOCATION) it.copy(status = SetupStatus.DONE) else it
        }
        assertTrue(OnboardingFlow.runtimeSummary(allRuntimeGranted).allDone)
    }

    @Test
    fun nextPermissionStepHighlightsOneEssentialAtATime() {
        // Essential runtime permission (Notifications) is still missing -> App permissions first.
        assertEquals(OnboardingFlow.APP_PERMISSIONS_ID, OnboardingFlow.nextPermissionStepId(items))

        // Once essential runtime permissions are granted (even if optional Location/SMS stay missing), move to special access.
        val runtimeEssentialsDone = items.map {
            if (it.id == SetupLogic.NOTIFICATIONS) it.copy(status = SetupStatus.DONE) else it
        }
        // Accessibility is DONE in `items`, so next is Notification access.
        assertEquals(SetupLogic.NOTIFICATION_ACCESS, OnboardingFlow.nextPermissionStepId(runtimeEssentialsDone))

        // Pending accessibility isn't done yet, so it comes before Notification access.
        val pendingAccessibility = runtimeEssentialsDone.map {
            if (it.id == SetupLogic.ACCESSIBILITY) it.copy(status = SetupStatus.PENDING) else it
        }
        assertEquals(SetupLogic.ACCESSIBILITY, OnboardingFlow.nextPermissionStepId(pendingAccessibility))

        // Once Notification access is granted, next is Battery.
        val notifDone = runtimeEssentialsDone.map {
            if (it.id == SetupLogic.NOTIFICATION_ACCESS) it.copy(status = SetupStatus.DONE) else it
        }
        assertEquals(SetupLogic.BATTERY, OnboardingFlow.nextPermissionStepId(notifDone))

        // Once Battery is also granted, nothing else is highlighted (optional Overlay stays optional).
        val allEssentialsDone = notifDone.map {
            if (it.id == SetupLogic.BATTERY) it.copy(status = SetupStatus.DONE) else it
        }
        assertNull(OnboardingFlow.nextPermissionStepId(allEssentialsDone))
        assertEquals(4 to 5, OnboardingFlow.specialProgress(allEssentialsDone))
    }

    @Test
    fun essentials() {
        assertFalse(OnboardingFlow.essentialsDone(items))
        assertTrue(OnboardingFlow.essentialsDone(items.map { it.copy(status = SetupStatus.DONE) }))
    }
}
