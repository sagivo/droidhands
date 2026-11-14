package dev.phonebridge.android.ui.setup

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ExitToApp
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.Face
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.MailOutline
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import dev.phonebridge.android.ui.components.Accents
import dev.phonebridge.android.ui.components.CardPadding
import dev.phonebridge.android.ui.components.IconBadge
import dev.phonebridge.android.ui.components.IconRowInset
import dev.phonebridge.android.ui.components.IconTile
import dev.phonebridge.android.ui.components.ListRow
import dev.phonebridge.android.ui.components.Notice
import dev.phonebridge.android.ui.components.NoticeKind
import dev.phonebridge.android.ui.components.SecondaryButton
import dev.phonebridge.android.ui.components.StatusPill
import dev.phonebridge.android.ui.components.TertiaryButton
import dev.phonebridge.android.ui.theme.TINT_ALPHA
import dev.phonebridge.android.ui.theme.statusColors

/**
 * One checklist row inside a [dev.phonebridge.android.ui.components.ListSection]: icon tile, title,
 * a subtitle (why to grant it while missing, what it unlocks once done) and, trailing, a green check
 * when done or the action that fixes it. Warnings (blocked, connecting, restricted setting) sit
 * below the row text. TalkBack reads the status ("Not granted", "Done", …) as the row's state.
 */
@Composable
internal fun SetupRow(
    item: SetupItem,
    onAction: (SetupItem) -> Unit,
    onOpenAppInfo: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = statusColors()
    val statusText = SetupLogic.statusLabel(item.status, item.blocked)
    val done = item.status == SetupStatus.DONE
    val (icon, accent) = tileFor(item.id)
    Column(modifier.fillMaxWidth()) {
        ListRow(
            title = item.title,
            subtitle = if (done) item.unlocks else item.description,
            leading = { IconTile(icon, accent) },
            trailing = {
                val label = SetupLogic.actionLabel(item)
                when {
                    done -> IconBadge(Icons.Filled.Check, colors.ok, size = 28.dp)
                    label != null -> SecondaryButton(label, onClick = { onAction(item) })
                    else -> StatusPill(statusText, colors.off)
                }
            },
            modifier = Modifier.semantics(mergeDescendants = true) { stateDescription = statusText },
        )
        RowNotes(item, onOpenAppInfo)
    }
}

/** Warnings under a row, indented to the row text. Nothing when the row has none. */
@Composable
private fun RowNotes(item: SetupItem, onOpenAppInfo: () -> Unit) {
    val pending = item.id == SetupLogic.ACCESSIBILITY && item.status == SetupStatus.PENDING
    // The guided steps are their own card at the top of the screen (SetupScreen); the short help is for the rest.
    val help = item.restrictedSettingsHelp && !item.restrictedSettingsGuide
    if (!pending && !item.blocked && !help) return
    Column(
        Modifier
            .fillMaxWidth()
            .padding(start = IconRowInset, end = CardPadding, bottom = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (pending) {
            Notice(
                "Turned on in Settings, but Android hasn't connected the service yet. If this doesn't change in a few " +
                    "seconds, turn PhoneBridge off and on again in Accessibility settings.",
                kind = NoticeKind.WARNING,
            )
        }
        if (item.blocked) {
            Notice(
                "Android won't show the permission dialog again. Allow it in App info (Permissions or Notifications).",
                kind = NoticeKind.WARNING,
            )
        }
        if (help) RestrictedSettingsHelp(onOpenAppInfo)
    }
}

/**
 * The numbered "Allow restricted settings" steps for an app installed from an APK file on Android
 * 13+ ([RestrictedSettings.showGuide]), with Open App info. Step 1 refers to an "Open Accessibility"
 * button: pass [onOpenAccessibility] to show it here, or place the guide above the accessibility
 * row's own button. [unlocks] is what Android blocks until then. Content only: the caller supplies
 * the card; text takes the container's content color.
 */
@Composable
internal fun RestrictedSettingsGuide(
    onOpenAppInfo: () -> Unit,
    modifier: Modifier = Modifier,
    unlocks: String = "accessibility",
    onOpenAccessibility: (() -> Unit)? = null,
) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconBadge(Icons.Filled.Lock, statusColors().warning)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text("Installed from the web? One extra step", style = MaterialTheme.typography.titleMedium)
                Text(
                    "Android blocks $unlocks for apps installed outside an app store until you allow restricted settings:",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
        RestrictedSettings.GUIDE_STEPS.forEachIndexed { index, step -> GuideStep(index + 1, step) }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
            if (onOpenAccessibility != null) SecondaryButton("Open Accessibility", onClick = onOpenAccessibility)
            SecondaryButton("Open App info", onClick = onOpenAppInfo)
        }
    }
}

/** A numbered step: the number in a tinted brand circle, then the text. */
@Composable
private fun GuideStep(number: Int, text: String) {
    Row {
        val brand = MaterialTheme.colorScheme.primary
        Box(
            Modifier
                .size(24.dp)
                .background(brand.copy(alpha = TINT_ALPHA), CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Text("$number", style = MaterialTheme.typography.labelMedium, color = brand)
        }
        Spacer(Modifier.width(12.dp))
        Text(text, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
    }
}

/**
 * Android 13+ greys out accessibility and notification access for apps installed from a file
 * ("Restricted setting"); explains the App info > ⋮ > "Allow restricted settings" workaround.
 */
@Composable
internal fun RestrictedSettingsHelp(onOpenAppInfo: () -> Unit) {
    Notice(
        title = "Switch greyed out (\"Restricted setting\")?",
        text = "Android 13+ restricts this for apps installed outside an app store. Try turning it on once, " +
            "then open App info, tap ⋮ (top corner) > Allow restricted settings and confirm. Come back here " +
            "and turn PhoneBridge on again.",
        action = { TertiaryButton("Open App info", onClick = onOpenAppInfo) },
    )
}

/** Icon and tile color of a checklist row, so each permission is recognisable at a glance. */
private fun tileFor(id: String): Pair<ImageVector, Color> = when (id) {
    SetupLogic.NOTIFICATIONS -> Icons.Filled.Notifications to Accents.Purple
    SetupLogic.CONTACTS -> Icons.Filled.Person to Accents.Blue
    RestrictedSettings.SMS_ID -> Icons.Filled.Email to Accents.Teal
    PHONE_ID -> Icons.Filled.Call to Accents.Pink
    CALENDAR_ID -> Icons.Filled.DateRange to Accents.Blue
    SetupLogic.LOCATION -> Icons.Filled.LocationOn to Accents.Teal
    SetupLogic.BACKGROUND_LOCATION -> Icons.Filled.Place to Accents.Teal
    SetupLogic.ACCESSIBILITY -> Icons.Filled.Face to Accents.Indigo
    SetupLogic.NOTIFICATION_ACCESS -> Icons.Filled.MailOutline to Accents.Purple
    SetupLogic.BATTERY -> Icons.Filled.PlayArrow to Accents.Orange
    SetupLogic.OVERLAY -> Icons.AutoMirrored.Filled.ExitToApp to Accents.Pink
    SetupLogic.DND -> Icons.Filled.Notifications to Accents.Gray
    else -> Icons.Filled.Settings to Accents.Gray
}

// Runtime rows use PermissionCatalog's group ids.
private const val PHONE_ID = "phone"
private const val CALENDAR_ID = "calendar"
