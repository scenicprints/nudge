package com.nudge.assistant.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.Locale

class EngineTest {

    private val zone = ZoneId.of("America/Los_Angeles")
    private fun at(y: Int, mo: Int, d: Int, h: Int, mi: Int = 0) =
        LocalDateTime.of(y, mo, d, h, mi).atZone(zone).toInstant().toEpochMilli()

    private val now = at(2026, 11, 22, 14)

    // ---- replies ----

    private fun msg(time: Long = now, self: Boolean = false, group: Boolean = false, text: String = "Dinner Friday?") =
        Message("k1", "com.chat", "Sam", text, time, group, self)

    @Test fun messageStartsAClock() {
        val n = ReplyRule.onMessage(null, msg(), now, 30, false)!!
        assertEquals(now + 30 * Times.MINUTE, n.fireAt)
        assertEquals("Sam", n.title)
        assertFalse(n.isDue(now))
        assertTrue(n.isDue(now + 31 * Times.MINUTE))
    }

    @Test fun ownReplyClearsIt() {
        val n = ReplyRule.onMessage(null, msg(), now, 30, false)
        assertNull(ReplyRule.onMessage(n, msg(time = now + 1000, self = true), now + 1000, 30, false))
    }

    @Test fun groupsAreOptIn() {
        assertNull(ReplyRule.onMessage(null, msg(group = true), now, 30, false))
        assertNotNull(ReplyRule.onMessage(null, msg(group = true), now, 30, true))
    }

    @Test fun secondMessageDoesNotRestartTheClock() {
        val first = ReplyRule.onMessage(null, msg(), now, 30, false)!!
        val later = now + 10 * Times.MINUTE
        val second = ReplyRule.onMessage(first, msg(time = later, text = "Or Saturday"), later, 30, false)!!
        assertEquals(first.fireAt, second.fireAt)
        assertEquals("Or Saturday", second.body)
    }

    @Test fun repostOfSameMessageDoesNotReannounce() {
        val first = ReplyRule.onMessage(null, msg(), now, 30, false)!!.copy(announced = true)
        val again = ReplyRule.onMessage(first, msg(), now + Times.HOUR, 30, false)!!
        assertTrue(again.announced)
    }

    @Test fun newMessageAfterAnnouncementStartsOver() {
        val first = ReplyRule.onMessage(null, msg(), now, 30, false)!!.copy(announced = true, dismissed = true)
        val later = now + 3 * Times.HOUR
        val next = ReplyRule.onMessage(first, msg(time = later), later, 30, false)!!
        assertFalse(next.announced)
        assertFalse(next.dismissed)
        assertEquals(later + 30 * Times.MINUTE, next.fireAt)
    }

    @Test fun ignoredAppsProduceNothingAndLoseWhatTheyHad() {
        val teams = ReplyRule.onMessage(null, msg().copy(key = "k2", pkg = Ignore.TEAMS), now, 30, false)!!
        val chat = ReplyRule.onMessage(null, msg(), now, 30, false)!!
        assertEquals(listOf(chat), Ignore.drop(listOf(teams, chat), Ignore.DEFAULTS))
        assertTrue(Ignore.isIgnored(Ignore.OUTLOOK, Ignore.DEFAULTS))
        assertFalse(Ignore.isIgnored("com.chat", Ignore.DEFAULTS))
        // A nudge with no source app, such as time to leave, is never ignored.
        assertFalse(Ignore.isIgnored(null, Ignore.DEFAULTS))
        // Turned back on in Settings: nothing is ignored.
        assertEquals(listOf(teams, chat), Ignore.drop(listOf(teams, chat), emptySet()))
    }

    @Test fun swipeIsNotHandledButTapIs() {
        assertTrue(ReplyRule.handledBy(1))   // click
        assertTrue(ReplyRule.handledBy(8))   // app withdrew it
        assertFalse(ReplyRule.handledBy(2))  // swiped
        assertFalse(ReplyRule.handledBy(3))  // clear all
    }

    // ---- leaving ----

    private fun event(begin: Long = at(2026, 11, 22, 16), location: String = "123 Main St, Springfield") =
        Event(7, "Dentist", begin, location, allDay = false, declined = false)

    @Test fun placesAndNotPlaces() {
        assertTrue(LeaveRule.isPlace("123 Main St"))
        assertTrue(LeaveRule.isPlace("Blue Bottle Coffee"))
        assertFalse(LeaveRule.isPlace("https://zoom.us/j/123"))
        assertFalse(LeaveRule.isPlace("Zoom"))
        assertFalse(LeaveRule.isPlace("Microsoft Teams Meeting"))
        assertFalse(LeaveRule.isPlace(""))
    }

    @Test fun distanceIsAboutRight() {
        // San Francisco to Oakland, roughly 13 km.
        val km = LeaveRule.km(37.7749, -122.4194, 37.8044, -122.2712)
        assertTrue(km in 12.0..14.5)
    }

    @Test fun travelEstimateIsSane() {
        assertNull(LeaveRule.travelMinutes(0.1))   // already there
        assertNull(LeaveRule.travelMinutes(400.0)) // a trip, not a drive
        assertTrue(LeaveRule.travelMinutes(2.0)!! in 6..14)
        assertTrue(LeaveRule.travelMinutes(15.0)!! in 20..35)
        assertTrue(LeaveRule.travelMinutes(100.0)!! in 85..125)
    }

    @Test fun leavePlanCountsBackFromTheStart() {
        val e = event()
        val n = LeaveRule.plan(e, 15.0, now, 5, zone, Locale.US)!!
        val travel = LeaveRule.travelMinutes(15.0)!!
        val leaveAt = e.begin - (travel + 5) * Times.MINUTE
        assertEquals(leaveAt - LeaveRule.HEADS_UP_MIN * Times.MINUTE, n.fireAt)
        assertEquals(e.begin, n.expiresAt)
        assertTrue(n.title.startsWith("Leave by 3:"))
        assertTrue(n.title.endsWith("for Dentist"))
        assertEquals("123 Main St, Springfield", n.destination)
    }

    @Test fun lateMeansLeaveNow() {
        val e = event(begin = now + 10 * Times.MINUTE)
        val n = LeaveRule.plan(e, 15.0, now, 5, zone, Locale.US)!!
        assertEquals("Leave now for Dentist", n.title)
        assertTrue(n.isDue(now))
    }

    @Test fun noPlanForPastAllDayDeclinedOrVirtual() {
        assertNull(LeaveRule.plan(event(begin = now - 1), 15.0, now, 5, zone))
        assertNull(LeaveRule.plan(event().copy(allDay = true), 15.0, now, 5, zone))
        assertNull(LeaveRule.plan(event().copy(declined = true), 15.0, now, 5, zone))
        assertNull(LeaveRule.plan(event(location = "Google Meet"), 15.0, now, 5, zone))
    }

    @Test fun mergeKeepsAnnouncedAndDismissed() {
        val old = LeaveRule.plan(event(), 15.0, now, 5, zone)!!.copy(announced = true, dismissed = true)
        val fresh = LeaveRule.plan(event(), 20.0, now, 5, zone)!!
        val merged = LeaveRule.merge(old, fresh)
        assertTrue(merged.announced && merged.dismissed)
        assertEquals(fresh.body, merged.body)
    }

    // ---- bills ----

    private val today = LocalDate.of(2026, 11, 22)

    @Test fun parsesAmountAndDate() {
        val p = BillRule.parse("Your statement is ready. Payment of $82.14 is due on Nov 28.", today)!!
        assertEquals("$82.14", p.amount)
        assertEquals(LocalDate.of(2026, 11, 28), p.dueDate)
    }

    @Test fun parsesOtherDateForms() {
        assertEquals(today.plusDays(1), BillRule.parse("Your bill is due tomorrow", today)!!.dueDate)
        assertEquals(today.plusDays(3), BillRule.parse("Minimum payment due in 3 days", today)!!.dueDate)
        assertEquals(LocalDate.of(2026, 12, 1), BillRule.parse("Amount due 12/1: $1,240.00", today)!!.dueDate)
        assertEquals("$1,240.00", BillRule.parse("Amount due 12/1: $1,240.00", today)!!.amount)
        // No year, and the date has passed by more than two months: it means next year.
        assertEquals(LocalDate.of(2027, 1, 3), BillRule.parse("Rent payment due January 3", today)!!.dueDate)
    }

    @Test fun dueDateWinsOverStatementDate() {
        val p = BillRule.parse("Statement from Nov 1. Balance of $40.00 due Dec 5", today)!!
        assertEquals(LocalDate.of(2026, 12, 5), p.dueDate)
    }

    @Test fun ignoresThingsThatAreNotBills() {
        assertNull(BillRule.parse("Flight delayed due to weather", today))
        assertNull(BillRule.parse("Your payment was delayed due to a bank holiday", today))
        assertNull(BillRule.parse("Library book due Friday", today))
        assertNull(BillRule.parse("Your payment of $20.00 was received", today))
    }

    @Test fun billIsAnnouncedTheMorningBefore() {
        val n = BillRule.plan("com.bank", "Bank", "Statement ready", "Payment of $82.14 is due on Nov 28", now, zone, Locale.US)!!
        assertEquals(at(2026, 11, 27, 9), n.fireAt)
        assertEquals("Bank: $82.14 due Nov 28", n.title)
    }

    @Test fun billDueTomorrowAfterNineIsAnnouncedOnTheDay() {
        val n = BillRule.plan("com.bank", "Bank", "", "Your bill is due tomorrow", now, zone, Locale.US)!!
        assertEquals(at(2026, 11, 23, 9), n.fireAt)
        assertEquals("Bank: payment due tomorrow", n.title)
    }

    @Test fun billWithNoDateIsFiledNotAnnounced() {
        val n = BillRule.plan("com.bank", "Bank", "", "Your payment is past due", now, zone)!!
        assertEquals(NEVER, n.fireAt)
        assertFalse(n.isDue(now + 30 * Times.DAY))
    }

    @Test fun sameBillTwiceIsOneNudge() {
        val a = BillRule.plan("com.bank", "Bank", "", "Payment of $5.00 due Nov 28", now, zone)!!
        val b = BillRule.plan("com.bank", "Bank", "Reminder", "Payment of $5.00 due Nov 28", now + Times.DAY, zone)!!
        assertEquals(a.id, b.id)
    }

    // ---- quiet hours ----

    @Test fun quietHoursWrapMidnight() {
        assertNull(Quiet.until(at(2026, 11, 22, 14), 22, 7, zone))
        assertEquals(at(2026, 11, 23, 7), Quiet.until(at(2026, 11, 22, 23), 22, 7, zone))
        assertEquals(at(2026, 11, 23, 7), Quiet.until(at(2026, 11, 23, 3), 22, 7, zone))
        assertNull(Quiet.until(at(2026, 11, 23, 7), 22, 7, zone))
        assertNull(Quiet.until(now, 8, 8, zone))
    }
}
