package com.nudge.assistant.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.view.View
import android.widget.RemoteViews
import com.nudge.assistant.MainActivity
import com.nudge.assistant.R
import com.nudge.assistant.data.Prefs
import com.nudge.assistant.data.Settings
import com.nudge.assistant.data.Store
import com.nudge.assistant.engine.Kind
import com.nudge.assistant.engine.NEVER
import com.nudge.assistant.sources.Perms

/**
 * The home screen widget: what is waiting on you, without opening anything.
 *
 * Built with RemoteViews rather than Glance so that it adds no dependency, which keeps the
 * pinned-version list in `app/build.gradle.kts` as short as it is.
 *
 * It is in one of four states, and each of them says something true:
 *
 *  - something waiting: an amber count in the header, and a row for each
 *  - nothing waiting but something coming: a grey header, and a row for each
 *  - nothing on file at all: the companion walking, and what it is watching
 *  - not watching: the companion sat down, and what is missing
 *
 * The headline and the companion appear only in the last two. With rows on screen the headline
 * would restate them and there is no room for anything that is only there to be looked at.
 */
class NudgeWidget : AppWidgetProvider() {

    override fun onUpdate(c: Context, manager: AppWidgetManager, ids: IntArray) {
        val views = build(c)
        ids.forEach { manager.updateAppWidget(it, views) }
    }

    companion object {
        /** What fits two cells tall under the header. The header counts the rest. */
        private const val ROWS = 3

        /**
         * Redraws every placed widget. It costs nothing when none is placed, so [Brain] calls
         * it on every change rather than working out whether anyone is looking.
         */
        fun refresh(c: Context) {
            runCatching {
                val manager = AppWidgetManager.getInstance(c) ?: return@runCatching
                val ids = manager.getAppWidgetIds(ComponentName(c, NudgeWidget::class.java))
                if (ids.isEmpty()) return@runCatching
                val views = build(c)
                ids.forEach { manager.updateAppWidget(it, views) }
            }
        }

        private fun build(c: Context): RemoteViews {
            Store.load(c)
            val shown = Store.flow.value.filter { !it.dismissed }
            // The same three groups the screen shows under Now, Coming up and On file.
            val needing = shown.filter { it.announced }.sortedBy { it.expiresAt }
            val coming = shown.filter { it.isPending() }.sortedBy { it.fireAt }
            val filed = shown.filter { !it.announced && it.fireAt == NEVER }
            // Neither failure should make the widget claim something untrue about itself.
            val watching = runCatching { Perms.read(c).notificationAccess }.getOrDefault(true)
            val settings = runCatching { Prefs.settings(c) }.getOrNull()

            val v = RemoteViews(c.packageName, R.layout.widget)
            v.setOnClickPendingIntent(R.id.widget_root, open(c))

            // The count is the only amber on an otherwise grey widget, and it carries the same
            // meaning amber carries everywhere else here: this is waiting on you.
            val waiting = needing.isNotEmpty()
            val status = when {
                waiting -> "${needing.size} waiting"
                !watching -> "Not watching"
                // A reply nudge sits on a clock before it speaks up. Saying only "Watching"
                // while one is already on the list reads as though nothing were there.
                coming.isNotEmpty() -> "${coming.size} coming"
                else -> "Watching"
            }
            v.setTextViewText(if (waiting) R.id.status_now else R.id.status_calm, status)
            v.setViewVisibility(R.id.status_now, if (waiting) View.VISIBLE else View.GONE)
            v.setViewVisibility(R.id.status_calm, if (waiting) View.GONE else View.VISIBLE)

            v.removeAllViews(R.id.rows)
            val rows = (needing + coming).take(ROWS)
            for (n in rows) {
                val row = RemoteViews(c.packageName, R.layout.widget_row)
                row.setImageViewResource(
                    R.id.dot,
                    if (n.announced) R.drawable.widget_dot_now else R.drawable.widget_dot_coming
                )
                row.setTextViewText(R.id.row_title, n.title)
                val body = bodyOf(n.kind, n.body)
                row.setTextViewText(R.id.row_body, body)
                row.setViewVisibility(R.id.row_body, if (body.isBlank()) View.GONE else View.VISIBLE)
                v.addView(R.id.rows, row)
            }

            val empty = rows.isEmpty()
            v.setViewVisibility(R.id.rows, if (empty) View.GONE else View.VISIBLE)
            v.setViewVisibility(R.id.calm, if (empty) View.VISIBLE else View.GONE)
            if (empty) {
                v.setTextViewText(
                    R.id.headline,
                    if (watching) "Nothing needs you right now." else "Not watching yet."
                )
                v.setTextViewText(R.id.watching, asideFor(watching, filed.size, settings))
                // The companion paces about while the app is doing its job and sits down when
                // it is not. Which of the two is on screen is the whole of what it says.
                v.setViewVisibility(R.id.pal, if (watching) View.VISIBLE else View.GONE)
                v.setViewVisibility(R.id.pal_asleep, if (watching) View.GONE else View.VISIBLE)
            }

            val hidden = needing.size + coming.size - rows.size
            if (hidden > 0) {
                v.setTextViewText(R.id.more, if (hidden == 1) "and one more" else "and $hidden more")
            }
            v.setViewVisibility(R.id.more, if (hidden > 0) View.VISIBLE else View.GONE)
            return v
        }

        /**
         * The dim line under the headline, on a widget with nothing to list. Bills on file come
         * first because they are the one thing the app knows that is otherwise invisible until
         * the morning before they are due.
         */
        private fun asideFor(watching: Boolean, filed: Int, s: Settings?): String = when {
            !watching -> "Tap to finish setting up."
            filed == 1 -> "One bill on file."
            filed > 1 -> "$filed bills on file."
            s == null -> "Messages · Calendar · Bills"
            else -> {
                val on = buildList {
                    if (s.replyOn) add("Messages")
                    if (s.leaveOn) add("Calendar")
                    if (s.billOn) add("Bills")
                }
                if (on.isEmpty()) "Nothing switched on." else on.joinToString(" · ")
            }
        }

        /**
         * What goes under the title. The home screen is visible to anyone who can see the
         * phone, so a reply nudge shows who is waiting and not what they said. The nudge
         * itself and the app still carry the message.
         */
        private fun bodyOf(kind: Kind, body: String): String =
            if (kind == Kind.REPLY) "You have not replied" else body

        private fun open(c: Context): PendingIntent = PendingIntent.getActivity(
            c, 0,
            Intent(c, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }
}
