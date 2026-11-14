package dev.phonebridge.android.ui.tokens

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.phonebridge.android.ui.MainViewModel

/**
 * The manual token path ("Use a token instead"): the "New token" form, then the one-time display
 * of the token with client configs. Shown full screen above everything while active.
 */
@Composable
fun TokenFlow(vm: TokensViewModel, main: MainViewModel) {
    val screen by vm.screen.collectAsStateWithLifecycle()
    when (val s = screen) {
        TokenScreen.List -> Unit
        TokenScreen.Editor -> TokenEditorScreen(
            draft = vm.draft,
            creating = vm.creating,
            onChange = vm::updateDraft,
            onCancel = vm::cancelCreate,
            onCreate = vm::create,
        )
        is TokenScreen.Created -> TokenCreatedScreen(token = s.token, main = main, onDone = vm::finish)
    }
}
