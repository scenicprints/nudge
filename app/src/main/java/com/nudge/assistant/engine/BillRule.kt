package com.nudge.assistant.engine

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale

/**
 * "Xfinity: $82.14 due Nov 28."
 *
 * Reads the text of any notification and picks out payment-due notices. The source notification
 * already told the user once; the value added here is a second mention the morning before the
 * due date, and one list of everything that is owed.
 */
object BillRule {

    private val due = Regex("(?i)\\b(over|past )?due\\b(?!\\s+to\\b)")
    private val billWord = Regex(
        "(?i)\\b(payment|bill|statement|invoice|balance|amount|minimum|rent|premium|autopay)\\b"
    )
    private val amount = Regex("\\$\\s?(\\d{1,3}(,\\d{3})+|\\d+)(\\.\\d{2})?")
    private val months = listOf(
        "jan", "feb", "mar", "apr", "may", "jun", "jul", "aug", "sep", "oct", "nov", "dec"
    )
    private val monthDay = Regex(
        "(?i)\\b(jan|feb|mar|apr|may|jun|jul|aug|sep|oct|nov|dec)[a-z]*\\.?\\s+(\\d{1,2})\\b"
    )
    private val slashDate = Regex("\\b(\\d{1,2})/(\\d{1,2})(?:/(\\d{2,4}))?\\b")
    private val inDays = Regex("(?i)\\bin (\\d{1,2}) days?\\b")

    data class Parsed(val amount: String?, val dueDate: LocalDate?)

    /** Null when the text is not a payment-due notice. */
    fun parse(text: String, today: LocalDate): Parsed? {
        val d = due.find(text) ?: return null
        if (!billWord.containsMatchIn(text)) return null
        // A date after the word "due" is the due date. One before it is more often a
        // statement date, so it is only used when nothing follows.
        val tail = text.substring(d.range.first)
        val date = dateIn(tail, today) ?: dateIn(text, today)
        return Parsed(amount.find(text)?.value?.replace(" ", ""), date)
    }

    private fun dateIn(s: String, today: LocalDate): LocalDate? {
        if (Regex("(?i)\\btoday\\b").containsMatchIn(s)) return today
        if (Regex("(?i)\\btomorrow\\b").containsMatchIn(s)) return today.plusDays(1)
        inDays.find(s)?.let { return today.plusDays(it.groupValues[1].toLong()) }
        monthDay.find(s)?.let { m ->
            val month = months.indexOf(m.groupValues[1].lowercase()) + 1
            return resolve(today, month, m.groupValues[2].toInt(), null)
        }
        slashDate.find(s)?.let { m ->
            val year = m.groupValues[3].takeIf { it.isNotEmpty() }?.toInt()?.let { if (it < 100) 2000 + it else it }
            return resolve(today, m.groupValues[1].toInt(), m.groupValues[2].toInt(), year)
        }
        return null
    }

    /** A date with no year means the nearest one: "Jan 3" seen in December is next January. */
    private fun resolve(today: LocalDate, month: Int, day: Int, year: Int?): LocalDate? = runCatching {
        if (year != null) return@runCatching LocalDate.of(year, month, day)
        val thisYear = LocalDate.of(today.year, month, day)
        if (thisYear.isBefore(today.minusDays(60))) thisYear.plusYears(1) else thisYear
    }.getOrNull()

    fun idFor(pkg: String, p: Parsed) = "bill:$pkg:${p.amount ?: "?"}:${p.dueDate ?: "?"}"

    fun plan(
        pkg: String, source: String, title: String, text: String, now: Long, zone: ZoneId,
        locale: Locale = Locale.getDefault()
    ): Nudge? {
        val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
        val p = parse("$title. $text", today) ?: return null
        val dueDate = p.dueDate
        // Announce at 9 the morning before, or failing that the morning of. If both have
        // passed, the source notification is the announcement and this one stays on file.
        val fireAt = dueDate?.let { d ->
            listOf(d.minusDays(1), d)
                .map { it.atTime(9, 0).atZone(zone).toInstant().toEpochMilli() }
                .firstOrNull { it > now }
        } ?: NEVER
        val expiresAt = dueDate?.plusDays(2)?.atStartOfDay(zone)?.toInstant()?.toEpochMilli()
            ?: (now + 5 * Times.DAY)
        if (expiresAt <= now) return null
        val whenText = when (dueDate) {
            null -> "due"
            today -> "due today"
            today.plusDays(1) -> "due tomorrow"
            else -> "due " + Times.day(dueDate.atStartOfDay(zone).toInstant().toEpochMilli(), zone, locale)
        }
        return Nudge(
            id = idFor(pkg, p),
            kind = Kind.BILL,
            title = "$source: ${p.amount ?: "payment"} $whenText",
            body = listOf(title, text).filter { it.isNotBlank() }.joinToString(". ").take(200),
            fireAt = fireAt,
            expiresAt = expiresAt,
            stamp = now,
            pkg = pkg
        )
    }
}
