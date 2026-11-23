package com.nudge.assistant.notify

import android.app.ActivityOptions
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.core.app.NotificationCompat
import androidx.core.app.RemoteInput
import com.nudge.assistant.Brain
import com.nudge.assistant.MainActivity
import com.nudge.assistant.R
import com.nudge.assistant.data.Store
import com.nudge.assistant.engine.Kind
import com.nudge.assistant.engine.Nudge
import com.nudge.assistant.engine.Times

object Notifier {
    private const val IMMUTABLE = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE

    fun channels(c: Context) {
        val nm = c.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannels(
            listOf(
                NotificationChannel("leave", "Time to leave", NotificationManager.IMPORTANCE_HIGH),
                NotificationChannel("reply", "Unanswered messages", NotificationManager.IMPORTANCE_DEFAULT),
                NotificationChannel("bill", "Bills due", NotificationManager.IMPORTANCE_DEFAULT)
            )
        )
    }

    private fun idOf(nudgeId: String) = nudgeId.hashCode()

    private fun uri(nudgeId: String) = Uri.fromParts("nudge", nudgeId, null)

    private fun broadcast(c: Context, action: String, nudgeId: String, mutable: Boolean = false): PendingIntent =
        PendingIntent.getBroadcast(
            c, 0,
            Intent(c, ActionReceiver::class.java).setAction(action).setData(uri(nudgeId)),
            if (mutable) PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE else IMMUTABLE
        )

    private fun activity(c: Context, intent: Intent, nudgeId: String): PendingIntent =
        PendingIntent.getActivity(c, idOf(nudgeId), intent, IMMUTABLE)

    fun post(c: Context, n: Nudge, note: String? = null) {
        val b = NotificationCompat.Builder(c, n.kind.name.lowercase())
            .setSmallIcon(R.drawable.ic_stat_nudge)
            .setContentTitle(
                when (n.kind) {
                    Kind.REPLY -> "You have not replied to ${n.title}"
                    else -> n.title
                }
            )
            .setContentText(note ?: n.body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(note ?: n.body))
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(activity(c, Actions.openIntent(c, n), n.id))
            // Deliberately no delete intent. Clearing the shade is housekeeping, not an answer,
            // so a swiped nudge stays on file and keeps showing in the app and the widget. Only
            // the Done button marks it done. This is the same reading the reply rule gives a
            // swiped message: see ReplyRule.handledBy.

        when (n.kind) {
            Kind.REPLY -> {
                b.setCategory(NotificationCompat.CATEGORY_REMINDER)
                // Inline reply only when the handle into the conversation is still held.
                if (note == null && n.sourceKey != null && Live.reply.containsKey(n.sourceKey)) {
                    val input = RemoteInput.Builder(ActionReceiver.KEY_TEXT).setLabel("Reply to ${n.title}").build()
                    b.addAction(
                        NotificationCompat.Action.Builder(0, "Reply", broadcast(c, ActionReceiver.REPLY, n.id, mutable = true))
                            .addRemoteInput(input)
                            .setAllowGeneratedReplies(false)
                            .build()
                    )
                }
                b.addAction(0, "In an hour", broadcast(c, ActionReceiver.LATER, n.id))
                b.addAction(0, "Done", broadcast(c, ActionReceiver.DONE, n.id))
            }
            Kind.LEAVE -> {
                b.setCategory(NotificationCompat.CATEGORY_EVENT)
                    .setPriority(NotificationCompat.PRIORITY_HIGH)
                    .setTimeoutAfter((n.expiresAt - System.currentTimeMillis()).coerceAtLeast(Times.MINUTE))
                b.addAction(0, "Navigate", activity(c, Actions.openIntent(c, n), n.id))
            }
            Kind.BILL -> {
                b.setCategory(NotificationCompat.CATEGORY_REMINDER)
                b.addAction(0, "Done", broadcast(c, ActionReceiver.DONE, n.id))
            }
        }
        runCatching { c.getSystemService(NotificationManager::class.java).notify(idOf(n.id), b.build()) }
    }

    fun cancel(c: Context, nudgeId: String) {
        c.getSystemService(NotificationManager::class.java).cancel(idOf(nudgeId))
    }
}

/** What can be done to a nudge. Shared by the notification buttons and the in-app list. */
object Actions {

    /** Where tapping a nudge goes: directions for a leave nudge, the source app otherwise. */
    fun openIntent(c: Context, n: Nudge): Intent {
        val target = when {
            n.kind == Kind.LEAVE && n.destination != null -> Intent(
                Intent.ACTION_VIEW,
                Uri.parse("https://www.google.com/maps/dir/?api=1&destination=" + Uri.encode(n.destination))
            )
            n.pkg != null -> c.packageManager.getLaunchIntentForPackage(n.pkg)
            else -> null
        } ?: Intent(c, MainActivity::class.java)
        return target.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    /** Opens from inside the app, straight into the conversation when its handle is held. */
    fun open(c: Context, n: Nudge) {
        val direct = n.sourceKey?.let { Live.open[it] }
        val sent = direct != null && runCatching {
            val opts = ActivityOptions.makeBasic()
            if (android.os.Build.VERSION.SDK_INT >= 34) {
                opts.setPendingIntentBackgroundActivityStartMode(ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED)
            }
            direct.send(c, 0, null, null, null, null, opts.toBundle())
        }.isSuccess
        if (!sent) runCatching { c.startActivity(openIntent(c, n)) }
    }

    fun done(c: Context, id: String) {
        Notifier.cancel(c, id)
        // Kept on file, marked dismissed, so the same event or bill does not come back.
        Store.update(c) { list -> list.map { if (it.id == id) it.copy(dismissed = true, announced = true) else it } }
        Brain.kick(c)
    }

    fun later(c: Context, id: String) {
        Notifier.cancel(c, id)
        val now = System.currentTimeMillis()
        Store.update(c) { list ->
            list.map {
                if (it.id == id) it.copy(
                    announced = false, dismissed = false,
                    fireAt = now + Times.HOUR,
                    expiresAt = maxOf(it.expiresAt, now + 3 * Times.HOUR)
                ) else it
            }
        }
        Brain.kick(c)
    }

    /** Sends [text] into the original conversation. False when the handle is no longer held. */
    fun reply(c: Context, id: String, text: CharSequence): Boolean {
        val n = Store.get(id) ?: return false
        val action = n.sourceKey?.let { Live.reply[it] } ?: return false
        val inputs = action.remoteInputs ?: return false
        val sent = runCatching {
            val fill = Intent()
            val results = Bundle()
            inputs.forEach { results.putCharSequence(it.resultKey, text) }
            android.app.RemoteInput.addResultsToIntent(inputs, fill, results)
            action.actionIntent.send(c, 0, fill)
        }.isSuccess
        if (sent) Brain.clear(c, id)
        return sent
    }
}

class ActionReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, intent: Intent) {
        val id = intent.data?.schemeSpecificPart
        when (intent.action) {
            TICK -> {
                val pending = goAsync()
                Brain.kick(c) { pending.finish() }
            }
            DONE -> if (id != null) Actions.done(c, id)
            LATER -> if (id != null) Actions.later(c, id)
            REPLY -> {
                val text = RemoteInput.getResultsFromIntent(intent)?.getCharSequence(KEY_TEXT)
                if (id == null || text.isNullOrBlank()) return
                if (!Actions.reply(c, id, text)) {
                    // Replace the spinner with something true rather than leaving it turning.
                    Store.get(id)?.let { Notifier.post(c, it, "Could not send from here. Tap to open the app.") }
                }
            }
        }
    }

    companion object {
        const val TICK = "com.nudge.assistant.TICK"
        const val DONE = "com.nudge.assistant.DONE"
        const val LATER = "com.nudge.assistant.LATER"
        const val REPLY = "com.nudge.assistant.REPLY"
        const val KEY_TEXT = "text"
    }
}

/** After a reboot or an update there is no alarm set, so one pass sets it again. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED && intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        val pending = goAsync()
        Brain.kick(c) { pending.finish() }
    }
}
