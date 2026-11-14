package dev.phonebridge.android.ui.components

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonColors
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.phonebridge.android.ui.theme.TINT_ALPHA

/*
 * The app's four button styles. Every button in the app is one of these, so emphasis means the same
 * thing on every screen (docs/DESIGN.md):
 *  - PrimaryButton: the one main action of a screen or card (filled brand color).
 *  - SecondaryButton: other real actions next to it (tinted brand color).
 *  - TertiaryButton: low-emphasis actions such as Cancel, Not now, See all, Show (text only).
 *  - [destructive] on Primary/Secondary/Tertiary: revoke, delete, deny-for-good (red).
 * All are pills; [ButtonSize.LARGE] (52dp) is for full-width calls to action, the default (40dp) for
 * inline use. [loading] on a primary button shows a spinner and disables it while work is running.
 */

enum class ButtonSize(val height: Int, val padding: PaddingValues) {
    REGULAR(40, PaddingValues(horizontal = 18.dp)),
    LARGE(52, PaddingValues(horizontal = 24.dp)),
}

@Composable
fun PrimaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    enabled: Boolean = true,
    destructive: Boolean = false,
    size: ButtonSize = ButtonSize.REGULAR,
    fullWidth: Boolean = false,
    loading: Boolean = false,
) {
    val scheme = MaterialTheme.colorScheme
    val colors = if (destructive) {
        ButtonDefaults.buttonColors(containerColor = scheme.error, contentColor = scheme.onError)
    } else {
        ButtonDefaults.buttonColors()
    }
    PillButton(text, onClick, modifier, icon, enabled && !loading, size, fullWidth, colors, loading)
}

@Composable
fun SecondaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    enabled: Boolean = true,
    destructive: Boolean = false,
    size: ButtonSize = ButtonSize.REGULAR,
    fullWidth: Boolean = false,
) {
    val tint = if (destructive) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
    val colors = ButtonDefaults.buttonColors(
        containerColor = tint.copy(alpha = TINT_ALPHA),
        contentColor = tint,
        disabledContainerColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.06f),
    )
    PillButton(text, onClick, modifier, icon, enabled, size, fullWidth, colors)
}

@Composable
fun TertiaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    enabled: Boolean = true,
    destructive: Boolean = false,
) {
    TextButton(
        onClick = onClick,
        modifier = modifier,
        enabled = enabled,
        shape = CircleShape,
        colors = if (destructive) {
            ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
        } else {
            ButtonDefaults.textButtonColors()
        },
    ) {
        ButtonContent(text, icon)
    }
}

@Composable
private fun PillButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier,
    icon: ImageVector?,
    enabled: Boolean,
    size: ButtonSize,
    fullWidth: Boolean,
    colors: ButtonColors,
    loading: Boolean = false,
) {
    Button(
        onClick = onClick,
        modifier = (if (fullWidth) modifier.fillMaxWidth() else modifier).defaultMinSize(minHeight = size.height.dp),
        enabled = enabled,
        shape = CircleShape,
        colors = colors,
        elevation = null,
        contentPadding = size.padding,
    ) {
        if (loading) {
            CircularProgressIndicator(
                strokeWidth = 2.dp,
                color = LocalContentColor.current,
                modifier = Modifier
                    .size(18.dp)
                    .semantics { contentDescription = "In progress" },
            )
            Spacer(Modifier.width(8.dp))
        }
        ButtonContent(text, icon.takeUnless { loading })
    }
}

@Composable
private fun ButtonContent(text: String, icon: ImageVector?) {
    if (icon != null) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(8.dp))
    }
    Text(text, style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
}
