package com.droidhands.android.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/**
 * The app's one chip style, for filters and segmented choices: a pill that is brand-filled when
 * [selected] and white with a hairline border otherwise. [DropdownSelector] uses the same look.
 */
@Composable
fun ChoiceChip(
    text: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    trailingIcon: ImageVector? = null,
    showCheck: Boolean = false,
) {
    val scheme = MaterialTheme.colorScheme
    Surface(
        onClick = onClick,
        enabled = enabled,
        shape = CircleShape,
        color = if (selected) scheme.primary else scheme.surfaceContainerLowest,
        contentColor = if (selected) scheme.onPrimary else scheme.onSurface,
        border = if (selected) null else BorderStroke(1.dp, scheme.outlineVariant),
        modifier = modifier
            .defaultMinSize(minHeight = 36.dp)
            .semantics {
                role = Role.Tab
                this.selected = selected
            },
    ) {
        Row(
            Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            if (showCheck && selected) Icon(Icons.Filled.Check, contentDescription = null, modifier = Modifier.size(16.dp))
            // A width cap rather than a weight, so the chip also measures inside a horizontally scrolling row.
            Text(text, style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 240.dp))
            if (trailingIcon != null) Icon(trailingIcon, contentDescription = null, modifier = Modifier.size(18.dp))
        }
    }
}
