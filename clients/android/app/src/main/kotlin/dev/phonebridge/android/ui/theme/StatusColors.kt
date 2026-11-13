package dev.phonebridge.android.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance

/**
 * Semantic status colors that Material 3 lacks (success, warning). Tuned for >= 4.5:1 contrast on
 * the light and dark surfaces; error/primary/outline come from the active [MaterialTheme] scheme.
 */
@Immutable
data class StatusColors(
    val ok: Color,
    val pending: Color,
    val warning: Color,
    val error: Color,
    val off: Color,
)

/** Status colors for the current theme (dark detection follows the scheme, not the system flag). */
@Composable
fun statusColors(): StatusColors {
    val scheme = MaterialTheme.colorScheme
    val dark = scheme.surface.luminance() < 0.5f
    return StatusColors(
        ok = if (dark) Color(0xFF7DD99A) else Color(0xFF1B7A3A),
        pending = scheme.primary,
        warning = if (dark) Color(0xFFFFC46B) else Color(0xFF8F5200),
        error = scheme.error,
        off = scheme.outline,
    )
}
