package com.nudge.assistant

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import com.nudge.assistant.data.Prefs
import com.nudge.assistant.data.Settings
import com.nudge.assistant.data.Store
import com.nudge.assistant.engine.BillRule
import com.nudge.assistant.engine.Kind
import com.nudge.assistant.engine.LeaveRule
import com.nudge.assistant.engine.Message
import com.nudge.assistant.engine.Nudge
import com.nudge.assistant.engine.Quiet
import com.nudge.assistant.engine.ReplyRule
import com.nudge.assistant.engine.Times
import com.nudge.assistant.notify.ActionReceiver
import com.nudge.assistant.notify.Live
import com.nudge.assistant.notify.Notifier
import com.nudge.assistant.sources.CalendarSource
import com.nudge.assistant.sources.Perms
import com.nudge.assistant.sources.Places
import com.nudge.assistant.widget.NudgeWidget
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.ZoneId

/**
 * The glue between Android and the engine. It gathers inputs (notifications, calendar,
 * position), hands them to the rules in `engine/`, stores what they return, announces what is
 * due, and sets an alarm for the next moment something could become due.
 *
 * It makes no decisions of its own. If you are about to add an `if` about when to nudge, it
 * belongs in a rule, with a test.
 */
object Brain {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val mutex = Mutex()

    /** How far ahead the calendar is read. Covers the longest drive the leave rule accepts. */
    private const val LOOKAHEAD = 5 * Times.HOUR

    /** How often the calendar and position are re-read while leave nudges are on. */
    private const val RESCAN = 30 * Times.MINUTE

    /** Re-evaluates everything. Safe to call from anywhere, as often as you like. */
    fun kick(c: Context, done: () -> Unit = {}) {
        val app = c.applicationContext
        scope.launch {
            try {
                mutex.withLock { tick(app) }
            } catch (_: Throwable) {
                // A failed pass is retried by the next alarm. Nothing here is worth a crash.
            } finally {
                done()
            }
        }
    }

    fun onMessage(c: Context, m: Message) {
        val s = Prefs.settings(c)
        if (!s.replyOn) return
        val id = ReplyRule.idFor(m.key)
        val now = System.currentTimeMillis()
        change(c) { list ->
            val next = ReplyRule.onMessage(list.firstOrNull { it.id == id }, m, now, s.replyDelayMin, s.includeGroups)
            list.filter { it.id != id } + listOfNotNull(next)
        }
    }

    fun onPossibleBill(c: Context, pkg: String, source: String, title: String, text: String) {
        if (!Prefs.settings(c).billOn) return
        val n = BillRule.plan(pkg, source, title, text, System.currentTimeMillis(), ZoneId.systemDefault()) ?: return
        // The same bill mentioned again is still one bill, and keeps its announced state.
        change(c) { list -> if (list.any { it.id == n.id }) list else list + n }
    }

    fun onRemoved(c: Context, key: String, reason: Int) {
        if (ReplyRule.handledBy(reason)) clear(c, ReplyRule.idFor(key))
    }

    fun clear(c: Context, id: String) = change(c) { list -> list.filter { it.id != id } }

    /** Applies a change to the store and withdraws the notification of anything it removed. */
    private fun change(c: Context, f: (List<Nudge>) -> List<Nudge>) {
        Store.load(c)
        val before = Store.flow.value
        Store.update(c, f)
        val now = Store.flow.value
        val after = now.map { it.id }.toSet()
        before.filter { it.id !in after }.forEach {
            Notifier.cancel(c, it.id)
            it.sourceKey?.let(Live::forget)
        }
        if (now != before) NudgeWidget.refresh(c)
    }

    private fun enabled(k: Kind, s: Settings) = when (k) {
        Kind.REPLY -> s.replyOn
        Kind.LEAVE -> s.leaveOn
        Kind.BILL -> s.billOn
    }

    private suspend fun tick(c: Context) {
        Store.load(c)
        val now = System.currentTimeMillis()
        val s = Prefs.settings(c)
        val zone = ZoneId.systemDefault()
        val perms = Perms.read(c)
        val watchCalendar = s.leaveOn && perms.calendar

        if (watchCalendar) planLeaves(c, s, now, zone)
        change(c) { list -> list.filter { it.expiresAt > now && enabled(it.kind, s) } }

        // Quiet hours hold everything back except leaving: the event is happening regardless.
        val quietEnd = if (s.quietOn) Quiet.until(now, s.quietStart, s.quietEnd, zone) else null
        val due = Store.flow.value.filter { it.isDue(now) && (quietEnd == null || it.kind == Kind.LEAVE) }
        if (due.isNotEmpty()) {
            if (perms.post) due.forEach { Notifier.post(c, it) }
            val ids = due.map { it.id }.toSet()
            change(c) { list -> list.map { if (it.id in ids) it.copy(announced = true) else it } }
        }

        val upcoming = Store.flow.value.filter { it.isPending() }.map {
            if (quietEnd != null && it.kind != Kind.LEAVE) maxOf(it.fireAt, quietEnd) else it.fireAt
        }
        var next = upcoming.minOrNull()
        if (watchCalendar) next = minOf(next ?: Long.MAX_VALUE, now + RESCAN)
        setAlarm(c, next?.coerceAtLeast(now + 15_000))

        // Catches what change() cannot see: a permission just granted, or a store edited
        // directly by a notification button.
        NudgeWidget.refresh(c)
    }

    private suspend fun planLeaves(c: Context, s: Settings, now: Long, zone: ZoneId) {
        // A failed read leaves what is on file alone rather than wiping it.
        val events = CalendarSource.upcoming(c, now, now + LOOKAHEAD) ?: return
        val candidates = events.filter { LeaveRule.qualifies(it, now) }
        val live = candidates.map(LeaveRule::idFor).toSet()
        val fresh = ArrayList<Nudge>()
        val here = if (candidates.isEmpty()) null else Places.here(c)
        if (here != null) {
            for (e in candidates) {
                val there = Places.find(c, e.location) ?: continue
                val km = LeaveRule.km(here.first, here.second, there.first, there.second)
                LeaveRule.plan(e, km, now, s.leaveBufferMin, zone)?.let { fresh += it }
            }
        }
        val freshIds = fresh.map { it.id }.toSet()
        change(c) { list ->
            // An event that was deleted or moved takes its nudge with it.
            val kept = list.filter { it.kind != Kind.LEAVE || it.id in live }
            val old = kept.associateBy { it.id }
            kept.filter { it.id !in freshIds } + fresh.map { LeaveRule.merge(old[it.id], it) }
        }
    }

    private fun setAlarm(c: Context, at: Long?) {
        val am = c.getSystemService(AlarmManager::class.java) ?: return
        val pi = PendingIntent.getBroadcast(
            c, 0,
            Intent(c, ActionReceiver::class.java).setAction(ActionReceiver.TICK),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        if (at == null) {
            am.cancel(pi)
            return
        }
        // Exact, because "leave by 2:40" arriving at 2:52 is worse than not arriving.
        runCatching {
            if (am.canScheduleExactAlarms()) am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
            else am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
        }
    }
}
