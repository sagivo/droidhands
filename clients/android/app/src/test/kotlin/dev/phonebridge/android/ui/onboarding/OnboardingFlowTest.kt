package dev.phonebridge.android.ui.onboarding

import android.Manifest
import dev.phonebridge.android.ui.setup.SetupItem
import dev.phonebridge.android.ui.setup.SetupLogic
import dev.phonebridge.android.ui.setup.SetupStatus
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
    fun grantAllSkipsGrantedBlockedAndBackgroundLocation() {
        assertEquals(
            listOf(Manifest.permission.POST_NOTIFICATIONS, Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION),
            OnboardingFlow.runtimeToRequest(items),
        )
        assertEquals(listOf("sms"), OnboardingFlow.blockedRuntime(items).map { it.id })
    }

    @Test
    fun walksSpecialAccessesInOrderAndAdvancesWhenGranted() {
        assertEquals(SetupLogic.NOTIFICATION_ACCESS, OnboardingFlow.nextSpecialAccess(items, emptySet())?.id)
        assertEquals(SetupLogic.BATTERY, OnboardingFlow.nextSpecialAccess(items, setOf(SetupLogic.NOTIFICATION_ACCESS))?.id)
        // The user came back with notification access granted: the walk moves on by itself.
        val granted = items.map { if (it.id == SetupLogic.NOTIFICATION_ACCESS) it.copy(status = SetupStatus.DONE) else it }
        assertEquals(SetupLogic.BATTERY, OnboardingFlow.nextSpecialAccess(granted, emptySet())?.id)
        assertNull(OnboardingFlow.nextSpecialAccess(granted, setOf(SetupLogic.BATTERY, SetupLogic.OVERLAY)))
        assertEquals(3 to 5, OnboardingFlow.specialProgress(granted))
    }

    @Test
    fun pendingAccessibilityIsNotDone() {
        val pending = items.map { if (it.id == SetupLogic.ACCESSIBILITY) it.copy(status = SetupStatus.PENDING) else it }
        assertEquals(SetupLogic.ACCESSIBILITY, OnboardingFlow.nextSpecialAccess(pending, emptySet())?.id)
    }

    @Test
    fun essentials() {
        assertFalse(OnboardingFlow.essentialsDone(items))
        assertTrue(OnboardingFlow.essentialsDone(items.map { it.copy(status = SetupStatus.DONE) }))
    }
}
