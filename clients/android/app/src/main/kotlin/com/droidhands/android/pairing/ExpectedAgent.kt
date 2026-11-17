package com.droidhands.android.pairing

/**
 * The agent the user is adding on the Connect page ("Add to Grok Bot", or its guide picked), for a few
 * minutes after they chose it: the pairing request that follows is shown under that name
 * ([PairingRules.labeled]). Thread-safe.
 */
class ExpectedAgent(private val clock: () -> Long = System::currentTimeMillis) {
    private class Choice(val label: String, val at: Long)

    @Volatile
    private var choice: Choice? = null

    /** The user is adding [label] now. */
    fun set(label: String) {
        choice = Choice(label, clock())
    }

    /** The agent being added, or null when none was chosen in the last [WINDOW_MS]. */
    fun label(): String? = choice?.takeIf { clock() - it.at in 0..WINDOW_MS }?.label

    companion object {
        /** Long enough to send a message and sign in, short enough not to name a later, unrelated request. */
        const val WINDOW_MS = 10 * 60_000L
    }
}
