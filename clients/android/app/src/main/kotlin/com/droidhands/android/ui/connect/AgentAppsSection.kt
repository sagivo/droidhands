package com.droidhands.android.ui.connect

import android.widget.Toast
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.droidhands.android.ui.apps.AppIcon
import com.droidhands.android.ui.components.CardPadding
import com.droidhands.android.ui.components.ListDivider
import com.droidhands.android.ui.components.ListRow
import com.droidhands.android.ui.components.ListSection
import com.droidhands.android.ui.components.LocalSnackbarHostState
import com.droidhands.android.ui.components.SecondaryButton
import com.droidhands.android.ui.components.TertiaryButton
import com.droidhands.android.ui.components.rememberCopier
import kotlinx.coroutines.launch

/** Where the text of a row led by a 40dp [AppIcon] starts. */
private val AppRowInset = CardPadding + 40.dp + 14.dp

/**
 * "On this phone": the agent apps installed here, each with one tap that adds DroidHands. Claude opens with
 * it filled in, Muse and Grok Bot open with a message to send, and ChatGPT and Grok open their connector
 * settings with the URL copied. Under the app tapped last ([added]): what's left there, then "Tap Allow when
 * this phone asks", spinning while [waiting].
 */
@Composable
internal fun AgentAppsSection(
    apps: List<AgentApp>,
    url: String,
    added: AgentClient?,
    waiting: Boolean,
    onAdded: (AgentClient) -> Unit,
) {
    val context = LocalContext.current
    val copy = rememberCopier()
    val snackbar = LocalSnackbarHostState.current
    val scope = rememberCoroutineScope()
    val tell: (String) -> Unit = { message -> scope.launch { snackbar.showSnackbar(message) } }
    val rows = remember(apps, url) {
        apps.mapNotNull { app ->
            val guide = ClientInstructions.guide(app.client, url)
            AgentApps.oneTap(app, guide)?.let { tap -> Triple(app, guide, tap) }
        }
    }
    ListSection(title = "On this phone", footer = "One tap opens the agent with DroidHands ready to add.") {
        rows.forEachIndexed { index, (app, guide, tap) ->
            val label = app.client.label
            if (index > 0) ListDivider(AppRowInset)
            ListRow(
                title = label,
                subtitle = AgentApps.summary(app, tap),
                leading = { AppIcon(app.packageName, label = label) },
                trailing = {
                    // Every row has an "Add": TalkBack says which agent it adds to.
                    SecondaryButton("Add", modifier = Modifier.semantics { contentDescription = "Add to $label" }, onClick = {
                        val opened = when (tap) {
                            is OneTap.Send -> when (AgentAppIntents.send(context, tap.packageName, tap.message)) {
                                AgentAppIntents.Sent.IN_CHAT -> true
                                // A toast, since the user is in the agent's app by now.
                                AgentAppIntents.Sent.COPIED -> true.also {
                                    Toast.makeText(context, "Message copied. Paste it in $label and send it.", Toast.LENGTH_LONG).show()
                                }
                                AgentAppIntents.Sent.FAILED -> false
                            }
                            is OneTap.Open -> {
                                if (tap.link.copyUrlFirst) copy("Connection URL", url, false)
                                AgentAppIntents.open(context, tap.link)
                            }
                        }
                        if (opened) onAdded(app.client) else tell("Couldn't open $label.")
                    })
                },
            )
            if (app.client == added) {
                Column(Modifier.padding(start = AppRowInset, end = CardPadding, bottom = 12.dp)) {
                    GuideSteps(guide.steps, waiting)
                    // An install link may land in an app that has no such screen yet: the browser always has it.
                    if (tap is OneTap.Open && !tap.link.inBrowser) {
                        TertiaryButton("Didn't open? Use the browser", onClick = {
                            if (!AgentAppIntents.open(context, tap.link, forceBrowser = true)) tell("No browser found.")
                        })
                    }
                }
            }
        }
    }
}
