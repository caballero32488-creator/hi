package com.tinymacro

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.*
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

/** type: tap | longpress | swipe | wait. Coordinates in recorded-screen pixels. */
data class Action(var type: String, var x: Int = 0, var y: Int = 0, var ex: Int = 0, var ey: Int = 0,
                  var duration: Long = 0, var timestamp: Long = 0,
                  var path: List<List<Int>> = emptyList()) { // path points: [x, y, msSinceDown]
    fun label() = when (type) {
        "tap" -> "Tap ($x, $y)"; "doubletap" -> "Double tap ($x, $y)"; "longpress" -> "Long press ($x, $y) ${duration}ms"
        "swipe" -> "Swipe ($x, $y) → ($ex, $ey) ${duration}ms" + if (path.size > 2) " · ${path.size} pts" else ""; else -> "Wait ${duration}ms"
    }
    fun toJson() = JSONObject().apply {
        put("type", type)
        when (type) {
            "tap", "longpress", "doubletap" -> { put("x", x); put("y", y); put("duration", duration); put("timestamp", timestamp) }
            "swipe" -> { put("startX", x); put("startY", y); put("endX", ex); put("endY", ey); put("duration", duration); put("timestamp", timestamp)
                if (path.size > 2) put("path", JSONArray().also { a -> path.forEach { p -> a.put(JSONArray(p)) } }) }
            else -> put("duration", duration)
        }
    }
    companion object {
        fun from(o: JSONObject): Action = when (val t = o.getString("type")) {
            "swipe" -> Action(t, o.getInt("startX"), o.getInt("startY"), o.getInt("endX"), o.getInt("endY"), o.optLong("duration", 300), o.optLong("timestamp"),
                o.optJSONArray("path")?.let { a -> (0 until a.length()).map { i -> a.getJSONArray(i).let { p -> listOf(p.getInt(0), p.getInt(1), p.getInt(2)) } } } ?: emptyList())
            "wait" -> Action(t, duration = o.optLong("duration"))
            else -> Action(t, o.getInt("x"), o.getInt("y"), duration = o.optLong("duration", when (t) { "tap" -> 50; "doubletap" -> 120; else -> 700 }), timestamp = o.optLong("timestamp"))
        }
    }
}

data class Macro(var id: String = UUID.randomUUID().toString(), var name: String, var description: String = "",
                 var folder: String = "", var width: Int = 0, var height: Int = 0, var orientation: String = "portrait",
                 var actions: List<Action> = emptyList()) {
    fun toJson() = JSONObject().apply {
        put("id", id); put("name", name); put("version", 1); put("description", description); put("folder", folder)
        put("screenWidth", width); put("screenHeight", height); put("orientation", orientation)
        put("actions", JSONArray().also { a -> actions.forEach { a.put(it.toJson()) } })
    }
    companion object {
        fun from(o: JSONObject) = Macro(o.optString("id", UUID.randomUUID().toString()), o.getString("name"),
            o.optString("description"), o.optString("folder"), o.optInt("screenWidth"), o.optInt("screenHeight"),
            o.optString("orientation", "portrait"),
            o.getJSONArray("actions").let { a -> (0 until a.length()).map { Action.from(a.getJSONObject(it)) } })
    }
}

object Store {
    private lateinit var dir: File
    val macros = mutableStateListOf<Macro>()
    fun init(c: Context) { dir = File(c.filesDir, "macros").apply { mkdirs() }; reload() }
    fun reload() {
        macros.clear()
        dir.listFiles()?.filter { it.extension == "json" }?.mapNotNull { runCatching { Macro.from(JSONObject(it.readText())) }.getOrNull() }
            ?.sortedBy { it.name.lowercase() }?.let { macros.addAll(it) }
    }
    fun save(m: Macro) { File(dir, "${m.id}.json").writeText(m.toJson().toString(2)); reload() }
    fun delete(m: Macro) { File(dir, "${m.id}.json").delete(); reload() }
    fun duplicate(m: Macro) = save(m.copy(id = UUID.randomUUID().toString(), name = m.name + " copy", actions = m.actions.map { it.copy() }))
    fun importJson(text: String): Boolean = runCatching { save(Macro.from(JSONObject(text)).copy(id = UUID.randomUUID().toString())) }.isSuccess
}

object Cfg {
    private lateinit var p: SharedPreferences
    var opacity by mutableFloatStateOf(0.9f); var overlaySize by mutableFloatStateOf(1f)
    var speed by mutableFloatStateOf(1f); var defaultDelay by mutableFloatStateOf(0f)
    var loops by mutableFloatStateOf(1f); var infinite by mutableStateOf(false)
    var touchViz by mutableStateOf(true); var autoSave by mutableStateOf(true)
    var scaling by mutableStateOf(true); var minGap by mutableFloatStateOf(50f) // recording quality: lower = keep finer waits
    fun init(c: Context) {
        p = c.getSharedPreferences("cfg", 0)
        opacity = p.getFloat("opacity", .9f); overlaySize = p.getFloat("size", 1f); speed = p.getFloat("speed", 1f)
        defaultDelay = p.getFloat("delay", 0f); loops = p.getFloat("loops", 1f); infinite = p.getBoolean("inf", false)
        touchViz = p.getBoolean("viz", true); autoSave = p.getBoolean("auto", true); scaling = p.getBoolean("scale", true)
        minGap = p.getFloat("gap", 50f)
    }
    fun save() = p.edit().putFloat("opacity", opacity).putFloat("size", overlaySize).putFloat("speed", speed)
        .putFloat("delay", defaultDelay).putFloat("loops", loops).putBoolean("inf", infinite).putBoolean("viz", touchViz)
        .putBoolean("auto", autoSave).putBoolean("scale", scaling).putFloat("gap", minGap).apply()
}

/** Shared state between overlay, player and UI. state: idle | recording | paused | playing */
object Hub {
    val actions = mutableStateListOf<Action>()
    var state by mutableStateOf("idle")
    var progress by mutableIntStateOf(-1)
    var recW = 0; var recH = 0
}
