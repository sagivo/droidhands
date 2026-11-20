package com.droidhands.android.ui.account

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.droidhands.android.R
import com.droidhands.android.account.SignInState
import com.droidhands.android.ui.MainViewModel
import com.droidhands.android.ui.components.Accents
import com.droidhands.android.ui.components.ConfirmDialog
import com.droidhands.android.ui.components.IconTile
import com.droidhands.android.ui.components.ListDivider
import com.droidhands.android.ui.components.ListRow
import com.droidhands.android.ui.components.ListSection
import com.droidhands.android.ui.components.Monogram
import com.droidhands.android.ui.components.Notice
import com.droidhands.android.ui.components.NoticeKind
import com.droidhands.android.ui.components.PrimaryButton
import com.droidhands.android.ui.components.SecondaryButton
import com.droidhands.core.account.AccountSession
import com.droidhands.core.transport.RelayStatus

/** Why the DroidHands relay asks for an account, in one line (shown next to the sign-in button). */
internal const val ACCOUNT_REASON =
    "The DroidHands relay needs a free DroidHands account. Sign in with Google: it takes a few seconds."

/** The relay this app comes with needs a sign-in before it can be used. */
@Composable
internal fun rememberSignInNeeded(main: MainViewModel): Boolean {
    val account by main.account.collectAsStateWithLifecycle()
    return main.relayNeedsAccount && account == null
}

/**
 * The button that puts the phone online with the default relay: "Continue with Google" while that needs a
 * sign-in (coming back signed in goes online by itself), else [label]. Full width, the step's one main action.
 */
@Composable
internal fun DefaultRelayButton(main: MainViewModel, label: String = "Go online") {
    if (rememberSignInNeeded(main)) {
        SignInWithGoogle(main)
    } else {
        PrimaryButton(label, onClick = main::useDefaultRelay, fullWidth = true)
    }
}

/** "Continue with Google", spinning while the sign-in finishes, with the last attempt's error below it. */
@Composable
internal fun SignInWithGoogle(main: MainViewModel, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val state by main.signInState.collectAsStateWithLifecycle()
    Column(modifier, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        PrimaryButton(
            "Continue with Google",
            onClick = { main.signIn(context) },
            leading = { GoogleLogo() },
            fullWidth = true,
            loading = state == SignInState.Finishing,
        )
        (state as? SignInState.Failed)?.let { Notice(it.message, kind = NoticeKind.DANGER) }
    }
}

/** The 4-color Google "G" on a crisp white circle so it reads cleanly against the brand button. */
@Composable
private fun GoogleLogo() {
    Box(
        Modifier
            .size(22.dp)
            .background(Color.White, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Image(
            painter = painterResource(R.drawable.ic_google),
            contentDescription = null,
            modifier = Modifier.size(15.dp),
        )
    }
}

/**
 * The relay refused this phone because nobody is signed in (or the session ended): say so, with the sign-in
 * button. Nothing for any other status.
 */
@Composable
internal fun AccountRequiredNotice(main: MainViewModel, relay: RelayStatus, modifier: Modifier = Modifier) {
    if ((relay as? RelayStatus.Error)?.code != RelayStatus.ACCOUNT_REQUIRED) return
    Notice(
        ACCOUNT_REASON,
        modifier = modifier,
        title = "Sign in to go online",
        kind = NoticeKind.WARNING,
        action = { SignInWithGoogle(main) },
    )
}

/**
 * Settings › Account (only in builds whose default relay needs one): who is signed in, Sign out and Delete
 * account, each confirmed first. Signed out: why an account is needed and Sign in.
 */
@Composable
internal fun AccountSection(
    session: AccountSession?,
    signIn: SignInState,
    busy: Boolean,
    onSignIn: () -> Unit,
    onSignOut: () -> Unit,
    onDelete: () -> Unit,
) {
    var confirm by rememberSaveable { mutableStateOf<String?>(null) }
    when (confirm) {
        SIGN_OUT -> ConfirmDialog(
            title = "Sign out?",
            text = "Agents can't reach this phone through the DroidHands relay until you sign in again. Tailscale and your " +
                "own relay keep working.",
            confirmLabel = "Sign out",
            onConfirm = onSignOut,
            onDismiss = { confirm = null },
        )
        DELETE -> ConfirmDialog(
            title = "Delete your account?",
            text = "Your DroidHands account (your name and email) is deleted for good, and this phone signs out. Agents " +
                "you added stay on this phone, but can't reach it through the DroidHands relay.",
            confirmLabel = "Delete account",
            destructive = true,
            onConfirm = onDelete,
            onDismiss = { confirm = null },
        )
    }
    if (session == null) {
        ListSection(title = "Account", footer = ACCOUNT_REASON) {
            ListRow(
                title = "Not signed in",
                subtitle = (signIn as? SignInState.Failed)?.message,
                leading = { IconTile(Icons.Filled.Person, Accents.Gray) },
                trailing = { SecondaryButton("Sign in", onClick = onSignIn, enabled = signIn != SignInState.Finishing) },
            )
        }
        return
    }
    val user = session.user
    ListSection(title = "Account", footer = "Signed in with Google. The DroidHands relay uses your account to let this phone connect.") {
        ListRow(
            title = user.name.ifBlank { user.email },
            subtitle = user.email.takeIf { it.isNotBlank() && user.name.isNotBlank() },
            leading = { Monogram(user.name.ifBlank { user.email }, size = 32.dp) },
        )
        ListDivider()
        ListRow(title = "Sign out", onClick = { confirm = SIGN_OUT }, enabled = !busy, showChevron = false, titleColor = MaterialTheme.colorScheme.primary)
        ListDivider()
        ListRow(title = "Delete account", onClick = { confirm = DELETE }, enabled = !busy, showChevron = false, titleColor = MaterialTheme.colorScheme.error)
    }
}

private const val SIGN_OUT = "sign-out"
private const val DELETE = "delete"
