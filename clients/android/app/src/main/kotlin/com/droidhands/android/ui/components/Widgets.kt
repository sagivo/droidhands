package com.droidhands.android.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.droidhands.android.ui.home.StatusLevel
import com.droidhands.android.ui.theme.StatusColors
import com.droidhands.android.ui.theme.TINT_ALPHA
import com.droidhands.android.ui.theme.statusColors

/**
 * Section title above free-standing cards, aligned with the text inside them. Same look as a
 * [ListSection] title; marked as a heading for TalkBack navigation.
 */
@Composable
fun SectionHeader(text: String, modifier: Modifier = Modifier) {
    SectionTitle(text, modifier.padding(start = CardPadding, top = 8.dp))
}

/** Small filled circle in a status color. Decorative: pair it with text. */
@Composable
fun StatusDot(color: Color, modifier: Modifier = Modifier, size: Dp = 8.dp) {
    Box(
        modifier
            .size(size)
            .background(color, CircleShape),
    )
}

/** Color for a [StatusLevel] from [StatusColors]. */
fun StatusColors.forLevel(level: StatusLevel): Color = when (level) {
    StatusLevel.OK -> ok
    StatusLevel.PENDING -> pending
    StatusLevel.WARNING -> warning
    StatusLevel.ERROR -> error
    StatusLevel.OFF -> off
}

/** Saturated tile color for a [StatusLevel], for [IconTile]s (white glyphs need the darker hue in both themes). */
fun StatusLevel.accent(): Color = when (this) {
    StatusLevel.OK -> Accents.Green
    StatusLevel.PENDING -> Accents.Indigo
    StatusLevel.WARNING -> Accents.Orange
    StatusLevel.ERROR -> Accents.Red
    StatusLevel.OFF -> Accents.Gray
}

/**
 * The app's one badge style: a tinted pill with colored text, e.g. an outcome, "Active" or
 * "Revoked". [dot] adds a leading status dot for live states.
 */
@Composable
fun StatusPill(text: String, color: Color, modifier: Modifier = Modifier, dot: Boolean = false) {
    Surface(
        color = color.copy(alpha = TINT_ALPHA),
        contentColor = color,
        shape = CircleShape,
        modifier = modifier,
    ) {
        Row(
            Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            if (dot) StatusDot(color, size = 6.dp)
            Text(text = text, style = MaterialTheme.typography.labelSmall, maxLines = 1)
        }
    }
}

enum class NoticeKind { INFO, WARNING, DANGER }

/** Inline callout with an icon, used for warnings and explanations. [action] goes under the text. */
@Composable
fun Notice(
    text: String,
    modifier: Modifier = Modifier,
    kind: NoticeKind = NoticeKind.INFO,
    title: String? = null,
    action: (@Composable () -> Unit)? = null,
) {
    val colors = statusColors()
    val scheme = MaterialTheme.colorScheme
    val (tint, icon: ImageVector) = when (kind) {
        NoticeKind.INFO -> scheme.primary to Icons.Filled.Info
        NoticeKind.WARNING -> colors.warning to Icons.Filled.Warning
        NoticeKind.DANGER -> colors.error to Icons.Filled.Warning
    }
    Surface(
        color = tint.copy(alpha = 0.08f),
        shape = MaterialTheme.shapes.medium,
        modifier = modifier.fillMaxWidth(),
    ) {
        Row(Modifier.padding(14.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(20.dp))
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                if (title != null) Text(title, style = MaterialTheme.typography.titleSmall, color = scheme.onSurface)
                Text(text, style = MaterialTheme.typography.bodyMedium, color = scheme.onSurface)
                if (action != null) action()
            }
        }
    }
}

/** Centered message for empty lists, with an optional call to action below. */
@Composable
fun EmptyState(
    title: String,
    message: String,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    action: (@Composable () -> Unit)? = null,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 40.dp, horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (icon != null) {
            IconBadge(icon, MaterialTheme.colorScheme.primary, size = 64.dp)
            Spacer(Modifier.size(8.dp))
        }
        Text(title, style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center)
        Text(
            message,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        if (action != null) {
            Spacer(Modifier.size(8.dp))
            action()
        }
    }
}
