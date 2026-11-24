package com.nudge.assistant.notify

import android.app.Notification
import android.app.PendingIntent
import android.os.Bundle
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import com.nudge.assistant.Brain
import com.nudge.assistant.data.Prefs
import com.nudge.assistant.engine.Ignore
import com.nudge.assistant.engine.Message
import java.util.concurrent.ConcurrentHashMap

/**
 * Handles kept from source notifications so a nudge can reply to, or open, the conversation it
 * is about. They live in memory only: a PendingIntent cannot be written to disk. After the
 * process restarts they are gone until the listener reconnects and re-reads what is showing.
 */
object Live {
    val reply = ConcurrentHashMap<String, Notification.Action>()
    val open = ConcurrentHashMap<String, PendingIntent>()

    fun forget(key: String) {
        reply.remove(key)
        open.remove(key)
    }
}

/**
 * Reads every notification on the phone and passes on the two kinds the rules care about:
 * messages that can be replied to, and anything that reads like a payment-due notice.
 *
 * Access is granted by hand in Settings, Notifications, Device and app notifications.
 */
class NudgeListener : NotificationListenerService() {

    override fun onListenerConnected() {
        // Clears anything an ignored app left on file before it was ignored.
        Brain.dropIgnored(this)
        runCatching { activeNotifications }.getOrNull()?.forEach(::consider)
        Brain.kick(this)
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        if (sbn == null) return
        consider(sbn)
        Brain.kick(this)
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?, rankingMap: RankingMap?, reason: Int) {
        if (sbn == null) return
        Brain.onRemoved(this, sbn.key, reason)
    }

    private fun consider(sbn: StatusBarNotification) {
        if (sbn.packageName == packageName) return
        // An ignored app is not looked at at all: no reply nudge, no bill nudge.
        if (Ignore.isIgnored(sbn.packageName, Prefs.settings(this).ignoredApps)) return
        val n = sbn.notification ?: return
        if (n.flags and Notification.FLAG_GROUP_SUMMARY != 0) return
        if (n.flags and Notification.FLAG_ONGOING_EVENT != 0) return
        val extras = n.extras ?: return

        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
        val text = (extras.getCharSequence(Notification.EXTRA_BIG_TEXT)
            ?: extras.getCharSequence(Notification.EXTRA_TEXT))?.toString().orEmpty()

        val replyAction = n.actions?.firstOrNull { a -> a.remoteInputs?.any { it.allowFreeFormInput } == true }
        val isMessage = n.category == Notification.CATEGORY_MESSAGE || extras.containsKey(Notification.EXTRA_MESSAGES)

        // Whether the notification offers inline reply decides whether the nudge can carry a
        // Reply button, and nothing else. It used to decide whether there was a nudge at all,
        // which silently dropped every message from an app that does not offer one.
        if (isMessage) {
            if (replyAction != null) Live.reply[sbn.key] = replyAction
            n.contentIntent?.let { Live.open[sbn.key] = it }
            val last = lastMessage(extras)
            Brain.onMessage(
                this,
                Message(
                    key = sbn.key,
                    pkg = sbn.packageName,
                    sender = extras.getCharSequence(Notification.EXTRA_CONVERSATION_TITLE)?.toString()
                        ?: title.ifBlank { appLabel(sbn.packageName) },
                    text = last?.text ?: text,
                    time = last?.time?.takeIf { it > 0 } ?: n.`when`.takeIf { it > 0 } ?: sbn.postTime,
                    isGroup = extras.getBoolean(Notification.EXTRA_IS_GROUP_CONVERSATION, false),
                    lastFromSelf = last?.fromSelf ?: false
                )
            )
        } else {
            Brain.onPossibleBill(this, sbn.packageName, appLabel(sbn.packageName), title, text)
        }
    }

    private class Last(val text: String, val time: Long, val fromSelf: Boolean)

    /**
     * The newest entry of a MessagingStyle notification. In that format a message with no
     * sender is the user's own, which is how a reply typed in the notification shade shows up.
     */
    private fun lastMessage(extras: Bundle): Last? {
        @Suppress("DEPRECATION")
        val arr = extras.getParcelableArray(Notification.EXTRA_MESSAGES) ?: return null
        val b = arr.lastOrNull() as? Bundle ?: return null
        @Suppress("DEPRECATION")
        val fromSelf = b.get("sender_person") == null && b.getCharSequence("sender") == null
        return Last(b.getCharSequence("text")?.toString().orEmpty(), b.getLong("time"), fromSelf)
    }

    private fun appLabel(pkg: String): String = runCatching {
        packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString()
    }.getOrDefault(pkg)
}
