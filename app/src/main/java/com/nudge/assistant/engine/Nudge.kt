package com.nudge.assistant.engine

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

enum class Kind { REPLY, LEAVE, BILL }

/** A nudge that is kept on file but never announced. */
const val NEVER = Long.MAX_VALUE

/**
 * One thing worth telling the user about.
 *
 * Everything in this package is plain Kotlin with no Android imports. The engine decides, the
 * rest of the app fetches inputs and displays outputs. Behaviour changes go here, with a test.
 */
data class Nudge(
    /** Stable across re-evaluation, so the same message or event never produces two nudges. */
    val id: String,
    val kind: Kind,
    val title: String,
    val body: String,
    /** When to announce it, or [NEVER]. */
    val fireAt: Long,
    /** When it stops mattering and is dropped. */
    val expiresAt: Long,
    /** Version of the source. A newer stamp on the same id means there is something new to say. */
    val stamp: Long = 0,
    /** Package that posted the source notification. */
    val pkg: String? = null,
    /** Key of the source notification. */
    val sourceKey: String? = null,
    /** Where to navigate, for LEAVE. */
    val destination: String? = null,
    val announced: Boolean = false,
    /** Dismissed nudges stay on file until they expire so that they are not recreated. */
    val dismissed: Boolean = false
) {
    fun isDue(now: Long) = !announced && !dismissed && fireAt != NEVER && fireAt <= now
    fun isPending() = !announced && !dismissed && fireAt != NEVER
}

object Times {
    fun clock(ms: Long, zone: ZoneId, locale: Locale = Locale.getDefault()): String =
        DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT).withLocale(locale)
            .format(Instant.ofEpochMilli(ms).atZone(zone))

    fun day(ms: Long, zone: ZoneId, locale: Locale = Locale.getDefault()): String =
        DateTimeFormatter.ofPattern("MMM d", locale).format(Instant.ofEpochMilli(ms).atZone(zone))

    const val MINUTE = 60_000L
    const val HOUR = 60 * MINUTE
    const val DAY = 24 * HOUR
}

object Quiet {
    /**
     * If [now] falls inside quiet hours, the time they end. Otherwise null.
     * The window may wrap midnight (22 to 7) or not (1 to 6). Equal hours mean no quiet time.
     */
    fun until(now: Long, startHour: Int, endHour: Int, zone: ZoneId): Long? {
        if (startHour == endHour) return null
        val t = Instant.ofEpochMilli(now).atZone(zone)
        val h = t.hour
        val inside = if (startHour < endHour) h in startHour until endHour
        else h >= startHour || h < endHour
        if (!inside) return null
        var end = t.toLocalDate().atTime(endHour, 0).atZone(zone)
        if (!end.isAfter(t)) end = end.plusDays(1)
        return end.toInstant().toEpochMilli()
    }
}
