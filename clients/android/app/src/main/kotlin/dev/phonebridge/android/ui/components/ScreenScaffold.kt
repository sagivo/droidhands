package dev.phonebridge.android.ui.components

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberTopAppBarState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** App-wide snackbar host, provided by the root scaffold so any screen can post messages. */
val LocalSnackbarHostState = staticCompositionLocalOf<SnackbarHostState> { error("No SnackbarHostState provided") }

/**
 * Per-screen scaffold: top app bar + optional FAB / bottom bar. Content padding includes the top
 * bar, safe-drawing insets (cutouts, nav bar when the root has no bottom bar) and the IME.
 *
 * Tabs pass [large] for a big title that collapses on scroll (the iOS / One UI pattern); pushed
 * pages pass [onBack] for a standard back arrow and a regular title. The bar shares the screen's
 * background so content cards float on one continuous surface.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScreenScaffold(
    title: String,
    modifier: Modifier = Modifier,
    large: Boolean = false,
    onBack: (() -> Unit)? = null,
    navigationIcon: @Composable () -> Unit = { if (onBack != null) BackButton(onBack) },
    actions: @Composable RowScope.() -> Unit = {},
    floatingActionButton: @Composable () -> Unit = {},
    bottomBar: @Composable () -> Unit = {},
    content: @Composable (PaddingValues) -> Unit,
) {
    val background = MaterialTheme.colorScheme.background
    val barColors = TopAppBarDefaults.topAppBarColors(containerColor = background, scrolledContainerColor = background)
    val scrollBehavior = if (large) {
        TopAppBarDefaults.exitUntilCollapsedScrollBehavior(rememberTopAppBarState())
    } else {
        TopAppBarDefaults.pinnedScrollBehavior()
    }
    Scaffold(
        modifier = modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = background,
        topBar = {
            if (large) {
                LargeTopAppBar(
                    title = { Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    navigationIcon = navigationIcon,
                    actions = actions,
                    colors = barColors,
                    scrollBehavior = scrollBehavior,
                )
            } else {
                TopAppBar(
                    title = { Text(title, style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    navigationIcon = navigationIcon,
                    actions = actions,
                    colors = barColors,
                    scrollBehavior = scrollBehavior,
                )
            }
        },
        floatingActionButton = floatingActionButton,
        bottomBar = bottomBar,
        contentWindowInsets = WindowInsets.safeDrawing,
        content = content,
    )
}

/** The standard back arrow of a pushed page. */
@Composable
fun BackButton(onClick: () -> Unit) {
    IconButton(onClick = onClick) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
}

/**
 * [this] plus [horizontal]/[vertical] padding on each side (RTL-aware). [extraBottom] keeps the
 * last list item clear of a floating action button.
 */
@Composable
@ReadOnlyComposable
fun PaddingValues.padded(horizontal: Dp = 16.dp, vertical: Dp = 8.dp, extraBottom: Dp = 16.dp): PaddingValues {
    val dir = LocalLayoutDirection.current
    return PaddingValues(
        start = calculateStartPadding(dir) + horizontal,
        top = calculateTopPadding() + vertical,
        end = calculateEndPadding(dir) + horizontal,
        bottom = calculateBottomPadding() + vertical + extraBottom,
    )
}
