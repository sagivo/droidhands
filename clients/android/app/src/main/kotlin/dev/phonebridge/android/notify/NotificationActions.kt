package dev.phonebridge.android.notify

import java.util.Locale

/**
 * One raw notification action before merging.
 *
 * @param identity the underlying operation (on Android the `PendingIntent`, whose `equals` matches
 *   copies of the same intent sender); used to drop duplicates. Null when unknown.
 * @param payload carried through untouched (the platform action that will be triggered).
 */
class ActionCandidate<out P>(
    val title: String,
    val canReply: Boolean,
    val requiresInput: Boolean,
    val semanticReply: Boolean,
    val identity: Any?,
    val payload: P,
)

/** A merged action: what agents see ([info]) plus what to trigger ([payload]). */
class IndexedAction<out P>(val info: NotificationActionInfo, val payload: P)

/** Combines a notification's action buttons with its WearableExtender actions (pure). */
object NotificationActions {
    /**
     * Returns [standard] actions in order, followed by [wearable] actions that aren't duplicates,
     * indexed 0..n-1. Indices are stable for an unchanged notification, so an index read from
     * `notifications_list` maps back to the same action at call time.
     *
     * A wearable action duplicates an already kept one when it triggers the same operation or has
     * the same (non-blank, case-insensitive) title, unless it adds a reply capability the kept one
     * lacks. Some apps (WhatsApp historically) only expose inline reply through the wearable
     * extender, behind a phone-side "Reply" button that just opens the app.
     */
    fun <P> merge(standard: List<ActionCandidate<P>>, wearable: List<ActionCandidate<P>>): List<IndexedAction<P>> {
        val kept = ArrayList<Pair<ActionCandidate<P>, Boolean>>(standard.size + wearable.size)
        standard.forEach { kept += it to false }
        for (candidate in wearable) {
            if (kept.none { (existing, _) -> isDuplicate(existing, candidate) }) kept += candidate to true
        }
        return kept.mapIndexed { index, (c, fromWearable) ->
            IndexedAction(
                NotificationActionInfo(
                    index = index,
                    title = c.title,
                    canReply = c.canReply,
                    requiresInput = c.requiresInput || c.canReply,
                    semanticReply = c.semanticReply,
                    fromWearable = fromWearable,
                ),
                c.payload,
            )
        }
    }

    internal fun isDuplicate(existing: ActionCandidate<*>, candidate: ActionCandidate<*>): Boolean {
        val sameOperation = existing.identity != null && existing.identity == candidate.identity
        val sameTitle = candidate.title.isNotBlank() && normalize(existing.title) == normalize(candidate.title)
        return (sameOperation || sameTitle) && (existing.canReply || !candidate.canReply)
    }

    internal fun normalize(title: String): String = title.trim().lowercase(Locale.ROOT)
}
