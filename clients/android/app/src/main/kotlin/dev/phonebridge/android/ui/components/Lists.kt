package dev.phonebridge.android.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.phonebridge.android.ui.theme.TINT_ALPHA

/*
 * Inset grouped lists (the iOS Settings / 1Password / Revolut pattern): a small header above a rounded
 * white group of rows separated by inset hairlines, with an optional footnote below. Every list-like
 * thing in the app (settings, agents, apps, activity, permissions) is built from these.
 */

/** Horizontal padding inside cards and list rows. */
val CardPadding = 16.dp

/** A white, borderless rounded container on the tinted background. The base of every card. */
@Composable
fun AppCard(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    color: Color = MaterialTheme.colorScheme.surfaceContainerLowest,
    contentPadding: PaddingValues = PaddingValues(CardPadding),
    content: @Composable ColumnScope.() -> Unit,
) {
    val body: @Composable () -> Unit = {
        Column(Modifier.padding(contentPadding), verticalArrangement = Arrangement.spacedBy(12.dp), content = content)
    }
    if (onClick != null) {
        Surface(onClick = onClick, modifier = modifier.fillMaxWidth(), shape = MaterialTheme.shapes.large, color = color, content = body)
    } else {
        Surface(modifier = modifier.fillMaxWidth(), shape = MaterialTheme.shapes.large, color = color, content = body)
    }
}

/**
 * A titled group of rows. [title] sits above the group, [footer] (an explanation) below it, both
 * aligned with the row text. [action] is an optional trailing control in the header, e.g. "See all".
 * Separate rows with [ListDivider].
 */
@Composable
fun ListSection(
    modifier: Modifier = Modifier,
    title: String? = null,
    footer: String? = null,
    action: (@Composable () -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (title != null || action != null) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .defaultMinSize(minHeight = 32.dp)
                    .padding(start = CardPadding),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                SectionTitle(title.orEmpty(), Modifier.weight(1f))
                action?.invoke()
            }
        }
        Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainerLowest, modifier = Modifier.fillMaxWidth()) {
            Column(content = content)
        }
        if (footer != null) {
            Text(
                footer,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = CardPadding),
            )
        }
    }
}

/** The small header above a [ListSection] or a free-standing card. Marked as a heading for TalkBack. */
@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier.semantics { heading() },
    )
}

/** Hairline between rows of a [ListSection], inset to line up with the row text. */
@Composable
fun ListDivider(inset: Dp = CardPadding) {
    HorizontalDivider(Modifier.padding(start = inset), thickness = 0.5.dp, color = MaterialTheme.colorScheme.outlineVariant)
}

/** Inset that makes a [ListDivider] start at the text of rows that have an [IconTile]. */
val IconRowInset = CardPadding + 32.dp + 14.dp

/**
 * The standard row: optional leading (usually an [IconTile]), title, subtitle, optional trailing
 * content, and a chevron when it navigates ([onClick] without [trailing]).
 */
@Composable
fun ListRow(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    leading: (@Composable () -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
    onClick: (() -> Unit)? = null,
    enabled: Boolean = true,
    titleColor: Color = Color.Unspecified,
    subtitleMaxLines: Int = Int.MAX_VALUE,
    showChevron: Boolean = onClick != null && trailing == null,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(enabled = enabled, onClick = onClick) else Modifier)
            .defaultMinSize(minHeight = 56.dp)
            .padding(horizontal = CardPadding, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (leading != null) {
            leading()
            Spacer(Modifier.width(14.dp))
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, color = titleColor)
            if (subtitle != null) {
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = subtitleMaxLines,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (trailing != null) {
            Spacer(Modifier.width(12.dp))
            trailing()
        }
        if (showChevron) {
            Spacer(Modifier.width(4.dp))
            Chevron()
        }
    }
}

/** Trailing "›" of a row that opens another screen. */
@Composable
fun Chevron() {
    Icon(
        Icons.AutoMirrored.Filled.KeyboardArrowRight,
        contentDescription = null,
        tint = MaterialTheme.colorScheme.outline,
        modifier = Modifier.size(22.dp),
    )
}

/** Trailing secondary text of a row, e.g. the current value of a setting. */
@Composable
fun RowValue(text: String) {
    Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
}

/**
 * A [ListRow] with a trailing switch. The whole row is one toggleable element, so TalkBack reads
 * title, subtitle and state together.
 */
@Composable
fun SwitchRow(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    enabled: Boolean = true,
    leading: (@Composable () -> Unit)? = null,
) {
    ListRow(
        title = title,
        subtitle = subtitle,
        leading = leading,
        modifier = modifier.toggleable(value = checked, enabled = enabled, role = Role.Switch, onValueChange = onCheckedChange),
        trailing = { Switch(checked = checked, onCheckedChange = null, enabled = enabled) },
    )
}

/** A colored rounded square with a white glyph, the leading icon of rows (iOS Settings style). */
@Composable
fun IconTile(icon: ImageVector, color: Color = MaterialTheme.colorScheme.primary, modifier: Modifier = Modifier, size: Dp = 32.dp) {
    Box(
        modifier
            .size(size)
            .clip(MaterialTheme.shapes.small)
            .background(color),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = null, tint = Color.White, modifier = Modifier.size(size * 0.6f))
    }
}

/** A softly tinted circle with a colored glyph, for status and empty states. */
@Composable
fun IconBadge(icon: ImageVector, color: Color, modifier: Modifier = Modifier, size: Dp = 40.dp) {
    Box(
        modifier
            .size(size)
            .clip(androidx.compose.foundation.shape.CircleShape)
            .background(color.copy(alpha = TINT_ALPHA)),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(size * 0.55f))
    }
}

/** A letter in a tinted rounded square: the stand-in when an app or agent has no icon. */
@Composable
fun Monogram(name: String, modifier: Modifier = Modifier, size: Dp = 40.dp) {
    val color = monogramColor(name)
    Box(
        modifier
            .size(size)
            .clip(MaterialTheme.shapes.medium)
            .background(color.copy(alpha = 0.16f)),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            name.trim().firstOrNull()?.uppercase() ?: "?",
            style = if (size >= 40.dp) MaterialTheme.typography.titleMedium else MaterialTheme.typography.labelLarge,
            color = color,
        )
    }
}

/** Stable accent color for [name], picked from [Accents]. */
fun monogramColor(name: String): Color = Accents.all[Math.floorMod(name.lowercase().hashCode(), Accents.all.size)]

/**
 * Category colors for [IconTile]s, the same hue for the same meaning everywhere: indigo for
 * PhoneBridge itself and connections, green for allowed/running, orange for warnings and limits,
 * red for danger, blue/teal/purple/pink to tell apart list entries.
 */
object Accents {
    val Indigo = Color(0xFF5B5BD6)
    val Blue = Color(0xFF2F80ED)
    val Teal = Color(0xFF0E9F9A)
    val Green = Color(0xFF22A355)
    val Orange = Color(0xFFF08A24)
    val Red = Color(0xFFE5484D)
    val Purple = Color(0xFF8E4EC6)
    val Pink = Color(0xFFD6409F)
    val Gray = Color(0xFF8B8D98)
    val all = listOf(Indigo, Blue, Teal, Green, Orange, Red, Purple, Pink)
}
