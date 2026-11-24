package com.nudge.assistant.engine

/**
 * Apps Nudge never speaks up about.
 *
 * Some apps are somebody else's clock. A work chat or a work inbox fills with messages that
 * are answered on work's schedule, in work's tools, and a nudge about each one is noise that
 * teaches the user to ignore nudges. An ignored app produces no reply nudge and no bill nudge,
 * and anything already on file from it is dropped the moment it is ignored.
 *
 * "Time to leave" is not affected: that comes from the calendar, not from an app's
 * notifications, so a meeting in a work calendar still gets one.
 */
object Ignore {
    const val TEAMS = "com.microsoft.teams"
    const val OUTLOOK = "com.microsoft.office.outlook"

    /** Ignored unless the user turns them back on in Settings. */
    val DEFAULTS: Set<String> = setOf(TEAMS, OUTLOOK)

    /** A readable name for the apps offered in Settings, whether or not they are installed. */
    fun name(pkg: String): String = when (pkg) {
        TEAMS -> "Teams"
        OUTLOOK -> "Outlook"
        else -> pkg
    }

    fun isIgnored(pkg: String?, ignored: Set<String>): Boolean = pkg != null && pkg in ignored

    /** The list without anything that came from an ignored app. */
    fun drop(list: List<Nudge>, ignored: Set<String>): List<Nudge> =
        list.filterNot { isIgnored(it.pkg, ignored) }
}
