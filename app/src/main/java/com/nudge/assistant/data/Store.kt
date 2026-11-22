package com.nudge.assistant.data

import android.content.Context
import com.nudge.assistant.engine.Kind
import com.nudge.assistant.engine.Nudge
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Every nudge on file, in memory and mirrored to one JSON file. The list is small (tens of
 * entries), so it is rewritten whole on each change.
 */
object Store {
    private val _flow = MutableStateFlow<List<Nudge>>(emptyList())
    val flow: StateFlow<List<Nudge>> = _flow.asStateFlow()

    private var loaded = false

    private fun file(c: Context) = File(c.filesDir, "nudges.json")

    @Synchronized
    fun load(c: Context) {
        if (loaded) return
        loaded = true
        _flow.value = runCatching {
            val arr = JSONArray(file(c).readText())
            (0 until arr.length()).mapNotNull { runCatching { fromJson(arr.getJSONObject(it)) }.getOrNull() }
        }.getOrDefault(emptyList())
    }

    @Synchronized
    fun update(c: Context, change: (List<Nudge>) -> List<Nudge>) {
        load(c)
        val next = change(_flow.value)
        if (next == _flow.value) return
        _flow.value = next
        runCatching {
            val arr = JSONArray()
            next.forEach { arr.put(toJson(it)) }
            val tmp = File(c.filesDir, "nudges.json.tmp")
            tmp.writeText(arr.toString())
            tmp.renameTo(file(c))
        }
    }

    fun get(id: String): Nudge? = _flow.value.firstOrNull { it.id == id }

    /** Replaces the nudge with this id, adds it, or removes it when [next] is null. */
    fun put(c: Context, id: String, next: Nudge?) = update(c) { list ->
        val without = list.filter { it.id != id }
        if (next == null) without else without + next
    }

    private fun toJson(n: Nudge) = JSONObject().apply {
        put("id", n.id); put("kind", n.kind.name); put("title", n.title); put("body", n.body)
        put("fireAt", n.fireAt); put("expiresAt", n.expiresAt); put("stamp", n.stamp)
        putOpt("pkg", n.pkg); putOpt("sourceKey", n.sourceKey); putOpt("destination", n.destination)
        put("announced", n.announced); put("dismissed", n.dismissed)
    }

    private fun fromJson(o: JSONObject) = Nudge(
        id = o.getString("id"),
        kind = Kind.valueOf(o.getString("kind")),
        title = o.getString("title"),
        body = o.getString("body"),
        fireAt = o.getLong("fireAt"),
        expiresAt = o.getLong("expiresAt"),
        stamp = o.optLong("stamp"),
        pkg = o.optString("pkg").ifEmpty { null },
        sourceKey = o.optString("sourceKey").ifEmpty { null },
        destination = o.optString("destination").ifEmpty { null },
        announced = o.optBoolean("announced"),
        dismissed = o.optBoolean("dismissed")
    )
}
