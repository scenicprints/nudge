package com.nudge.assistant.data

import android.content.Context

data class Settings(
    val replyOn: Boolean = true,
    val leaveOn: Boolean = true,
    val billOn: Boolean = true,
    /** Minutes a message may sit unanswered before the nudge. */
    val replyDelayMin: Int = 30,
    val includeGroups: Boolean = false,
    /** Minutes of slack added on top of the travel estimate. */
    val leaveBufferMin: Int = 5,
    val quietOn: Boolean = true,
    val quietStart: Int = 22,
    val quietEnd: Int = 7
)

object Prefs {
    private fun sp(c: Context) = c.getSharedPreferences("nudge", Context.MODE_PRIVATE)

    fun settings(c: Context): Settings {
        val p = sp(c)
        val d = Settings()
        return Settings(
            replyOn = p.getBoolean("replyOn", d.replyOn),
            leaveOn = p.getBoolean("leaveOn", d.leaveOn),
            billOn = p.getBoolean("billOn", d.billOn),
            replyDelayMin = p.getInt("replyDelayMin", d.replyDelayMin),
            includeGroups = p.getBoolean("includeGroups", d.includeGroups),
            leaveBufferMin = p.getInt("leaveBufferMin", d.leaveBufferMin),
            quietOn = p.getBoolean("quietOn", d.quietOn),
            quietStart = p.getInt("quietStart", d.quietStart),
            quietEnd = p.getInt("quietEnd", d.quietEnd)
        )
    }

    fun save(c: Context, s: Settings) {
        sp(c).edit()
            .putBoolean("replyOn", s.replyOn)
            .putBoolean("leaveOn", s.leaveOn)
            .putBoolean("billOn", s.billOn)
            .putInt("replyDelayMin", s.replyDelayMin)
            .putBoolean("includeGroups", s.includeGroups)
            .putInt("leaveBufferMin", s.leaveBufferMin)
            .putBoolean("quietOn", s.quietOn)
            .putInt("quietStart", s.quietStart)
            .putInt("quietEnd", s.quietEnd)
            .apply()
    }

    /** The last position seen, used when a fresh fix is not available in the background. */
    fun lastPlace(c: Context): Pair<Double, Double>? {
        val s = sp(c).getString("lastPlace", null) ?: return null
        val parts = s.split(",").mapNotNull { it.toDoubleOrNull() }
        return if (parts.size == 2) parts[0] to parts[1] else null
    }

    fun setLastPlace(c: Context, lat: Double, lon: Double) {
        sp(c).edit().putString("lastPlace", "$lat,$lon").apply()
    }
}
