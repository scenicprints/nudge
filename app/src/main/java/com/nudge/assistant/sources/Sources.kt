package com.nudge.assistant.sources

import android.Manifest
import android.annotation.SuppressLint
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.location.Geocoder
import android.location.Location
import android.location.LocationManager
import android.os.Build
import android.os.CancellationSignal
import android.provider.CalendarContract
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.nudge.assistant.data.Prefs
import com.nudge.assistant.engine.Event
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/** What the user has granted. Read fresh each time: any of it can be revoked in Settings. */
data class Perms(
    val notificationAccess: Boolean,
    val post: Boolean,
    val calendar: Boolean,
    val location: Boolean,
    val backgroundLocation: Boolean
) {
    companion object {
        private fun has(c: Context, p: String) =
            ContextCompat.checkSelfPermission(c, p) == PackageManager.PERMISSION_GRANTED

        fun read(c: Context) = Perms(
            notificationAccess = NotificationManagerCompat.getEnabledListenerPackages(c).contains(c.packageName),
            // Before Android 13 there is no such permission and posting is always allowed.
            post = Build.VERSION.SDK_INT < 33 || has(c, Manifest.permission.POST_NOTIFICATIONS),
            calendar = has(c, Manifest.permission.READ_CALENDAR),
            location = has(c, Manifest.permission.ACCESS_FINE_LOCATION) ||
                has(c, Manifest.permission.ACCESS_COARSE_LOCATION),
            backgroundLocation = has(c, Manifest.permission.ACCESS_BACKGROUND_LOCATION)
        )
    }
}

object CalendarSource {
    /** Events starting in the window, from every calendar the user has visible. */
    fun upcoming(c: Context, from: Long, to: Long): List<Event>? = runCatching {
        val uri = CalendarContract.Instances.CONTENT_URI.buildUpon().also {
            ContentUris.appendId(it, from)
            ContentUris.appendId(it, to)
        }.build()
        val cols = arrayOf(
            CalendarContract.Instances.EVENT_ID,
            CalendarContract.Instances.TITLE,
            CalendarContract.Instances.BEGIN,
            CalendarContract.Instances.EVENT_LOCATION,
            CalendarContract.Instances.ALL_DAY,
            CalendarContract.Instances.SELF_ATTENDEE_STATUS
        )
        val out = ArrayList<Event>()
        c.contentResolver.query(uri, cols, "${CalendarContract.Instances.VISIBLE} = 1", null, null)?.use { cur ->
            while (cur.moveToNext()) {
                out += Event(
                    id = cur.getLong(0),
                    title = cur.getString(1).orEmpty(),
                    begin = cur.getLong(2),
                    location = cur.getString(3).orEmpty(),
                    allDay = cur.getInt(4) != 0,
                    declined = cur.getInt(5) == CalendarContract.Attendees.ATTENDEE_STATUS_DECLINED
                )
            }
        }
        out
    }.getOrNull()
}

object Places {
    private const val FRESH_MS = 20 * 60_000L

    /**
     * Where the phone is now. Tries a recent fix, then a new one, then the last position this
     * app ever saw. Without "Allow all the time" a background call gets nothing new and falls
     * through to that last position.
     */
    @SuppressLint("MissingPermission")
    suspend fun here(c: Context): Pair<Double, Double>? {
        if (!Perms.read(c).location) return Prefs.lastPlace(c)
        val lm = c.getSystemService(LocationManager::class.java) ?: return Prefs.lastPlace(c)
        val last = lm.getProviders(true)
            .mapNotNull { runCatching { lm.getLastKnownLocation(it) }.getOrNull() }
            .maxByOrNull { it.time }
        val fix = if (last != null && System.currentTimeMillis() - last.time < FRESH_MS) last
        else fresh(c, lm) ?: last
        if (fix != null) Prefs.setLastPlace(c, fix.latitude, fix.longitude)
        return fix?.let { it.latitude to it.longitude } ?: Prefs.lastPlace(c)
    }

    @SuppressLint("MissingPermission")
    private suspend fun fresh(c: Context, lm: LocationManager): Location? {
        val provider = listOf(LocationManager.FUSED_PROVIDER, LocationManager.NETWORK_PROVIDER, LocationManager.GPS_PROVIDER)
            .firstOrNull { runCatching { lm.isProviderEnabled(it) }.getOrDefault(false) } ?: return null
        return withTimeoutOrNull(6_000) {
            suspendCancellableCoroutine { cont ->
                val cancel = CancellationSignal()
                cont.invokeOnCancellation { cancel.cancel() }
                runCatching {
                    lm.getCurrentLocation(provider, cancel, c.mainExecutor) { loc ->
                        if (cont.isActive) cont.resume(loc)
                    }
                }.onFailure { if (cont.isActive) cont.resume(null) }
            }
        }
    }

    /**
     * Coordinates for a calendar location string, via the platform geocoder. Answers are cached
     * for good: an address does not move. Failures are not cached, so they are retried.
     */
    suspend fun find(c: Context, name: String): Pair<Double, Double>? = withContext(Dispatchers.IO) {
        val cache = c.getSharedPreferences("geocode", Context.MODE_PRIVATE)
        val key = name.trim().lowercase()
        cache.getString(key, null)?.split(",")?.mapNotNull { it.toDoubleOrNull() }
            ?.takeIf { it.size == 2 }?.let { return@withContext it[0] to it[1] }
        if (!Geocoder.isPresent()) return@withContext null
        val hit = runCatching {
            @Suppress("DEPRECATION")
            Geocoder(c).getFromLocationName(name, 1)?.firstOrNull()
        }.getOrNull() ?: return@withContext null
        cache.edit().putString(key, "${hit.latitude},${hit.longitude}").apply()
        hit.latitude to hit.longitude
    }
}
