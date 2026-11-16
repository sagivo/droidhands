package com.droidhands.android.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp

/**
 * Selectable monospace text (commands, URLs, JSON, tokens). Always laid out left-to-right, even
 * in RTL locales, because the content is code.
 */
@Composable
fun CodeBlock(text: String, modifier: Modifier = Modifier) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerHighest,
        shape = MaterialTheme.shapes.medium,
        modifier = modifier.fillMaxWidth(),
    ) {
        CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
            SelectionContainer {
                Text(
                    text = text,
                    fontFamily = FontFamily.Monospace,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                )
            }
        }
    }
}

/**
 * [CodeBlock] with Copy (and optionally Share) actions. [sensitive] marks the clipboard entry so
 * the system hides its preview. [onCopied] runs after a copy or a successful share.
 */
@Composable
fun CopyableCode(
    text: String,
    copyLabel: String,
    sensitive: Boolean,
    modifier: Modifier = Modifier,
    shareTitle: String? = null,
    onShareFailed: () -> Unit = {},
    onCopied: () -> Unit = {},
) {
    val copy = rememberCopier()
    val context = LocalContext.current
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        CodeBlock(text)
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (shareTitle != null) {
                TertiaryButton("Share", icon = Icons.Filled.Share, onClick = {
                    if (Clipboard.share(context, shareTitle, text)) onCopied() else onShareFailed()
                })
            }
            SecondaryButton("Copy", onClick = {
                copy(copyLabel, text, sensitive)
                onCopied()
            })
        }
    }
}
