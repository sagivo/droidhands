package com.droidhands.android.ui.apps

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.ZeroCornerSize
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.droidhands.android.ui.components.CardPadding
import com.droidhands.android.ui.components.EmptyState
import com.droidhands.android.ui.components.ListDivider
import com.droidhands.android.ui.components.ListRow
import com.droidhands.android.ui.components.Monogram
import com.droidhands.android.ui.components.SectionTitle

/**
 * Searchable app list with checkboxes, used by the per-agent app picker and the never-allow list.
 * Stateless: the caller owns [selected] and [query]. [lockedReason] explains locked rows.
 *
 * [contentPadding] carries no horizontal gutter (pass `padded(horizontal = 0.dp)`): the search field
 * and the app groups add the 16dp screen gutter themselves, and [header] items pad themselves.
 */
@Composable
internal fun AppPickerList(
    apps: List<AppInfo>?,
    selected: Set<String>,
    query: String,
    onQueryChange: (String) -> Unit,
    onToggle: (String) -> Unit,
    contentPadding: PaddingValues,
    modifier: Modifier = Modifier,
    locked: Set<String> = emptySet(),
    lockedSelected: Boolean = false,
    lockedReason: String = "",
    suggested: Set<String> = emptySet(),
    header: LazyListScope.() -> Unit = {},
) {
    val sections = remember(apps, selected, locked, suggested, query, lockedSelected) {
        apps?.let { AppSelection.sections(it, selected, locked, suggested, query, lockedSelected) }
    }
    LazyColumn(contentPadding = contentPadding, modifier = modifier) {
        header()
        item(key = "search") {
            SearchField(
                value = query,
                onValueChange = onQueryChange,
                placeholder = "Search apps",
                modifier = Modifier.padding(horizontal = GUTTER, vertical = 8.dp),
            )
        }
        when {
            sections == null -> item(key = "loading") { ListLoading() }
            sections.all { it.rows.isEmpty() } -> item(key = "empty") {
                EmptyState(
                    title = if (query.isBlank()) "No apps found" else "No matching apps",
                    message = if (query.isBlank()) "DroidHands couldn't list the installed apps." else "Try another name or package.",
                )
            }
            else -> sections.forEachIndexed { index, section ->
                lazyListSection(
                    key = "section-${section.key}",
                    rows = section.rows,
                    rowKey = { "${section.key}-${it.app.packageName}" },
                    title = section.title,
                    dividerInset = AppRowInset,
                    gutter = GUTTER,
                    spaceAbove = if (index == 0) 8.dp else SECTION_SPACING,
                ) { row -> AppRowItem(row, lockedReason, onToggle = { onToggle(row.app.packageName) }) }
            }
        }
    }
}

@Composable
private fun AppRowItem(row: AppRow, lockedReason: String, onToggle: () -> Unit) {
    val subtitle = when {
        row.locked -> lockedReason
        !row.installed -> "Not installed · tap to remove"
        else -> row.app.packageName
    }
    ListRow(
        title = row.app.label,
        subtitle = subtitle,
        subtitleMaxLines = 2,
        leading = { AppIcon(row.app.packageName, installed = row.installed, label = row.app.label) },
        trailing = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (row.locked) {
                    Icon(Icons.Filled.Lock, contentDescription = null, tint = MaterialTheme.colorScheme.outline, modifier = Modifier.size(18.dp))
                }
                Checkbox(checked = row.selected, onCheckedChange = null, enabled = !row.locked)
            }
        },
        modifier = Modifier
            .toggleable(value = row.selected, enabled = !row.locked, role = Role.Checkbox, onValueChange = { onToggle() })
            .semantics { if (row.locked) stateDescription = lockedReason },
    )
}

/**
 * The app's launcher icon at [size], loaded off the main thread and cached. Until it arrives the space
 * stays blank; apps without a drawable icon (or not installed) show a [Monogram] of [label] (the
 * package's last segment when no label is given).
 */
@Composable
internal fun AppIcon(
    packageName: String,
    installed: Boolean = true,
    modifier: Modifier = Modifier,
    label: String? = null,
    size: Dp = ICON_SIZE,
) {
    val context = LocalContext.current
    val sizePx = with(LocalDensity.current) { size.roundToPx() }
    var icon by remember(packageName, sizePx) { mutableStateOf<ImageBitmap?>(LaunchableApps.cachedIcon(packageName, sizePx)) }
    var missing by remember(packageName, sizePx, installed) { mutableStateOf(!installed) }
    if (icon == null && !missing) {
        LaunchedEffect(packageName, sizePx) {
            val loaded = LaunchableApps.icon(context, packageName, sizePx)
            if (loaded == null) missing = true else icon = loaded
        }
    }
    val bitmap = icon
    when {
        bitmap != null -> Image(bitmap = bitmap, contentDescription = null, modifier = modifier.size(size))
        missing -> Monogram(label ?: packageName.substringAfterLast('.'), modifier, size = size)
        else -> Box(modifier.size(size))
    }
}

/**
 * Full-width search box: a 12dp-rounded field on the card color with a leading search icon and a
 * clear button. The keyboard's search key just closes the keyboard; results update as you type.
 */
@Composable
internal fun SearchField(value: String, onValueChange: (String) -> Unit, placeholder: String, modifier: Modifier = Modifier) {
    val focusManager = LocalFocusManager.current
    val scheme = MaterialTheme.colorScheme
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        placeholder = { Text(placeholder) },
        leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
        trailingIcon = {
            if (value.isNotEmpty()) {
                IconButton(onClick = { onValueChange("") }) { Icon(Icons.Filled.Clear, contentDescription = "Clear search") }
            }
        },
        singleLine = true,
        shape = MaterialTheme.shapes.medium,
        colors = OutlinedTextFieldDefaults.colors(
            focusedContainerColor = scheme.surfaceContainerLowest,
            unfocusedContainerColor = scheme.surfaceContainerLowest,
            unfocusedBorderColor = scheme.outlineVariant,
        ),
        keyboardOptions = KeyboardOptions(autoCorrectEnabled = false, imeAction = ImeAction.Search),
        keyboardActions = KeyboardActions(onSearch = { focusManager.clearFocus() }),
        modifier = modifier.fillMaxWidth(),
    )
}

/**
 * A [com.droidhands.android.ui.components.ListSection] laid out as lazy items: the title, each row and
 * the footer are separate items of the surrounding LazyColumn, so long groups (every app on the phone, a
 * busy day of activity) only compose what is on screen. Rows share one rounded card and are separated by
 * [ListDivider] ([dividerInset]). [rowKey] must be unique in the whole list. [gutter] is the horizontal
 * screen padding for lists whose content padding has none; [spaceAbove] separates it from what is above.
 */
internal fun <T> LazyListScope.lazyListSection(
    key: String,
    rows: List<T>,
    rowKey: (T) -> Any,
    title: String? = null,
    footer: String? = null,
    dividerInset: Dp = CardPadding,
    gutter: Dp = 0.dp,
    spaceAbove: Dp = SECTION_SPACING,
    row: @Composable (T) -> Unit,
) {
    if (rows.isEmpty()) return
    item(key = "$key-header", contentType = "section-header") {
        Column(Modifier.padding(horizontal = gutter)) {
            Spacer(Modifier.height(spaceAbove))
            if (title != null) {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .defaultMinSize(minHeight = 32.dp)
                        .padding(start = CardPadding),
                    contentAlignment = Alignment.CenterStart,
                ) { SectionTitle(title) }
                Spacer(Modifier.height(6.dp))
            }
        }
    }
    itemsIndexed(rows, key = { _, item -> rowKey(item) }, contentType = { _, _ -> "section-row" }) { index, item ->
        Surface(
            shape = groupShape(first = index == 0, last = index == rows.lastIndex),
            color = MaterialTheme.colorScheme.surfaceContainerLowest,
            modifier = Modifier
                .padding(horizontal = gutter)
                .fillMaxWidth(),
        ) {
            Column {
                if (index > 0) ListDivider(dividerInset)
                row(item)
            }
        }
    }
    if (footer != null) {
        item(key = "$key-footer", contentType = "section-footer") {
            Text(
                footer,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = gutter + CardPadding, end = gutter + CardPadding, top = 6.dp),
            )
        }
    }
}

/** The corners of one row of a lazy section: the group's first and last rows carry its rounded corners. */
@Composable
private fun groupShape(first: Boolean, last: Boolean): Shape {
    val large = MaterialTheme.shapes.large
    return when {
        first && last -> large
        first -> large.copy(bottomStart = ZeroCornerSize, bottomEnd = ZeroCornerSize)
        last -> large.copy(topStart = ZeroCornerSize, topEnd = ZeroCornerSize)
        else -> RectangleShape
    }
}

/** Spinner shown while the app list loads, with an optional line of [text] under it. */
@Composable
internal fun ListLoading(modifier: Modifier = Modifier, text: String? = null) {
    Column(
        modifier
            .fillMaxWidth()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        CircularProgressIndicator()
        if (text != null) {
            Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

private val ICON_SIZE = 40.dp
private val GUTTER = 16.dp

/** Space between the sections of a screen (docs/DESIGN.md: 20dp between top-level blocks). */
internal val SECTION_SPACING = 20.dp

/** Divider inset that lines up with the text of rows led by a 40dp [AppIcon]. */
internal val AppRowInset = CardPadding + ICON_SIZE + 14.dp
