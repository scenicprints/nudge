package com.nudge.assistant.engine

/** A message notification, reduced to what the rule needs. */
data class Message(
    val key: String,
    val pkg: String,
    val sender: String,
    val text: String,
    /** Time of the newest message in the conversation. */
    val time: Long,
    val isGroup: Boolean,
    /** True when the newest message in the conversation is the user's own. */
    val lastFromSelf: Boolean
)

/**
 * "You have not replied to Sam."
 *
 * A message starts a clock. If the conversation is still unanswered when the clock runs out,
 * the user is nudged once. The rule errs toward silence: anything that suggests the user dealt
 * with the message clears it.
 */
object ReplyRule {

    // NotificationListenerService.REASON_* values, repeated here to keep the engine off Android.
    private const val REASON_CLICK = 1
    private const val REASON_APP_CANCEL = 8
    private const val REASON_APP_CANCEL_ALL = 9

    fun idFor(key: String) = "reply:$key"

    /** Returns the nudge to keep for this conversation, or null to hold none. */
    fun onMessage(existing: Nudge?, m: Message, now: Long, delayMin: Int, includeGroups: Boolean): Nudge? {
        if (m.lastFromSelf) return null
        if (m.isGroup && !includeGroups) return null
        val body = m.text.trim().take(160)
        // The same message posted again (apps re-post to update a notification) changes nothing.
        if (existing != null && m.time <= existing.stamp) {
            return existing.copy(title = m.sender, body = body)
        }
        // A newer message while the clock is still running does not restart it: the
        // conversation has been waiting since the first unanswered message.
        if (existing != null && existing.isPending()) {
            return existing.copy(title = m.sender, body = body, stamp = m.time)
        }
        return Nudge(
            id = idFor(m.key),
            kind = Kind.REPLY,
            title = m.sender,
            body = body,
            fireAt = now + delayMin * Times.MINUTE,
            expiresAt = now + Times.DAY,
            stamp = m.time,
            pkg = m.pkg,
            sourceKey = m.key
        )
    }

    /**
     * Whether a removed notification means the conversation was handled.
     *
     * Tapping it, or the app withdrawing it (which is what messaging apps do once the thread
     * is read in the app), counts as handled. Swiping it away does not: that is exactly the
     * "I'll answer later" that gets forgotten.
     *
     * The app cannot see whether a reply was typed inside the messaging app, so "read in the
     * app but never answered" is deliberately not nudged. A wrong nudge costs more than a
     * missed one.
     */
    fun handledBy(reason: Int): Boolean =
        reason == REASON_CLICK || reason == REASON_APP_CANCEL || reason == REASON_APP_CANCEL_ALL
}
