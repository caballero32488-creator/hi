package com.tinymacro

import android.content.Context
import kotlinx.coroutines.*

object Player {
    private var job: Job? = null
    @Volatile var paused = false

    fun play(ctx: Context, list: List<Action>, recW: Int = 0, recH: Int = 0) {
        if (job?.isActive == true || list.isEmpty()) return
        val svc = MacroService.instance ?: return
        val dm = ctx.resources.displayMetrics
        val sx = if (Cfg.scaling && recW > 0) dm.widthPixels.toFloat() / recW else 1f
        val sy = if (Cfg.scaling && recH > 0) dm.heightPixels.toFloat() / recH else 1f
        val snapshot = list.map { it.copy() }
        Hub.state = "playing"; paused = false
        job = CoroutineScope(Dispatchers.Main).launch {
            try {
                var n = 0
                while (isActive && (Cfg.infinite || n < Cfg.loops.toInt())) {
                    snapshot.forEachIndexed { i, a ->
                        while (paused && isActive) delay(50)
                        Hub.progress = i
                        val spd = Cfg.speed.coerceAtLeast(0.1f)
                        if (a.type == "wait") delay((a.duration / spd).toLong()) else {
                            val sw = a.type == "swipe"
                            when {
                                sw && a.path.size >= 2 -> svc.gesturePath(a.path, sx, sy, (a.duration / spd).toLong())
                                a.type == "doubletap" -> {
                                    svc.gesture(a.x * sx, a.y * sy, a.x * sx, a.y * sy, 50)
                                    delay((a.duration / spd).toLong().coerceIn(30, 400))
                                    svc.gesture(a.x * sx, a.y * sy, a.x * sx, a.y * sy, 50)
                                }
                                else -> svc.gesture(a.x * sx, a.y * sy, (if (sw) a.ex else a.x) * sx, (if (sw) a.ey else a.y) * sy,
                                    if (a.type == "tap") 50L else (a.duration / spd).toLong())
                            }
                            if (Cfg.defaultDelay > 0) delay((Cfg.defaultDelay / spd).toLong())
                        }
                    }
                    n++
                    if (Cfg.infinite) delay(100) // guards against a hot loop on wait-free macros
                }
            } finally { Hub.state = "idle"; Hub.progress = -1 }
        }
    }
    fun stop() { job?.cancel(); paused = false }
    fun togglePause() { paused = !paused }
}
