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
import com.nudge.assistant.data.Store
import com.nudge.assistant.engine.Kind
import com.nudge.assistant.sources.Perms

/**
 * The home screen widget: what is waiting on you, without opening anything.
 *
 * Built with RemoteViews rather than Glance so that it adds no dependency, which keeps the
 * pinned-version list in `app/build.gradle.kts` as short as it is.
 *
 * It holds no logic of its own. It reads the store, groups it the way the screen does, and
 * draws at most [ROWS] lines. The one judgement it makes is in [bodyOf].
 */
class NudgeWidget : AppWidgetProvider() {

    override fun onUpdate(c: Context, manager: AppWidgetManager, ids: IntArray) {
        val views = build(c)
        ids.forEach { manager.updateAppWidget(it, views) }
    }

    companion object {
        /** More than this and it stops being a glance. The headline says how many are hidden. */
        private const val ROWS = 4

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
            // The same two groups the screen shows under "Now" and "Coming up".
            val needing = shown.filter { it.announced }.sortedBy { it.expiresAt }
            val coming = shown.filter { it.isPending() }.sortedBy { it.fireAt }
            // A failed read should not make the widget claim it is not watching.
            val watching = runCatching { Perms.read(c).notificationAccess }.getOrDefault(true)

            val v = RemoteViews(c.packageName, R.layout.widget)
            v.setOnClickPendingIntent(R.id.widget_root, open(c))

            val headline = when {
                needing.size == 1 -> "One thing needs you."
                needing.size > 1 -> "${needing.size} things need you."
                !watching -> "Not watching yet."
                else -> "Nothing needs you right now."
            }
            // Amber means this is waiting on you and is used for nothing else, so the headline
            // is two views and only the one that tells the truth is shown.
            val waiting = needing.isNotEmpty()
            v.setTextViewText(if (waiting) R.id.headline_now else R.id.headline_calm, headline)
            v.setViewVisibility(R.id.headline_now, if (waiting) View.VISIBLE else View.GONE)
            v.setViewVisibility(R.id.headline_calm, if (waiting) View.GONE else View.VISIBLE)

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

            val hidden = needing.size + coming.size - rows.size
            if (hidden > 0) {
                v.setTextViewText(R.id.more, if (hidden == 1) "and one more" else "and $hidden more")
            }
            v.setViewVisibility(R.id.more, if (hidden > 0) View.VISIBLE else View.GONE)
            return v
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
