package com.nudge.assistant.engine

import java.time.ZoneId
import java.util.Locale
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

data class Event(
    val id: Long,
    val title: String,
    val begin: Long,
    val location: String,
    val allDay: Boolean,
    val declined: Boolean
)

/**
 * "Leave by 2:40 for Dentist."
 *
 * Travel time is an estimate from straight-line distance, not a routed time with traffic. That
 * needs a paid routing API and a key. The wording says "about" for that reason, and the buffer
 * setting is there to absorb the error.
 */
object LeaveRule {

    /** How long before the leave time the nudge is announced. */
    const val HEADS_UP_MIN = 10

    private val notAPlace = Regex(
        "(?i)https?://|\\b(zoom|google meet|meet\\.google|facetime|teams|webex|hangout|skype|discord|phone|call|online|virtual|remote|tbd)\\b"
    )

    fun idFor(e: Event) = "leave:${e.id}:${e.begin}"

    /** True when the location field looks like somewhere you travel to. */
    fun isPlace(location: String): Boolean {
        val s = location.trim()
        return s.length >= 3 && !notAPlace.containsMatchIn(s)
    }

    fun qualifies(e: Event, now: Long): Boolean =
        !e.allDay && !e.declined && e.begin > now && isPlace(e.location)

    /** Great-circle distance in kilometres. */
    fun km(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = sin(dLat / 2).pow(2) +
            cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLon / 2).pow(2)
        return 6371.0 * 2 * asin(sqrt(a))
    }

    /**
     * Estimated driving minutes for a straight-line distance, or null when no nudge makes
     * sense: already there, or far enough that this is a trip rather than a drive.
     */
    fun travelMinutes(km: Double): Int? {
        if (km < 0.3 || km > 250) return null
        val road = km * 1.35 // roads are not straight
        val kmh = when {
            road < 8 -> 28.0   // town
            road < 40 -> 50.0  // mixed
            else -> 80.0       // mostly highway
        }
        return (road / kmh * 60 + 4).roundToInt() // 4 minutes to get out the door and park
    }

    fun plan(
        e: Event, km: Double, now: Long, bufferMin: Int, zone: ZoneId,
        locale: Locale = Locale.getDefault()
    ): Nudge? {
        if (!qualifies(e, now)) return null
        val travel = travelMinutes(km) ?: return null
        val leaveAt = e.begin - (travel + bufferMin) * Times.MINUTE
        val name = e.title.ifBlank { "your event" }
        val title = if (leaveAt <= now) "Leave now for $name"
        else "Leave by ${Times.clock(leaveAt, zone, locale)} for $name"
        return Nudge(
            id = idFor(e),
            kind = Kind.LEAVE,
            title = title,
            body = "About $travel min to ${e.location.trim()}. Starts ${Times.clock(e.begin, zone, locale)}.",
            fireAt = leaveAt - HEADS_UP_MIN * Times.MINUTE,
            expiresAt = e.begin,
            stamp = leaveAt,
            destination = e.location.trim()
        )
    }

    /**
     * Folds a fresh plan into what is already on file. The estimate moves as the user moves,
     * so the text always updates, but a nudge that was already announced or dismissed stays so.
     */
    fun merge(existing: Nudge?, fresh: Nudge): Nudge =
        if (existing == null) fresh
        else fresh.copy(announced = existing.announced, dismissed = existing.dismissed)
}
