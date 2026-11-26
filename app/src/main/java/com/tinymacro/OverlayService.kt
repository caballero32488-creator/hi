package com.tinymacro

import android.app.*
import android.content.Context
import android.content.Intent
import android.graphics.*
import android.os.IBinder
import android.view.*
import android.widget.LinearLayout
import android.widget.TextView
import kotlinx.coroutines.*
import java.text.SimpleDateFormat
import java.util.*
import kotlin.math.abs
import kotlin.math.hypot

class OverlayService : Service() {
    private lateinit var wm: WindowManager
    private var panel: LinearLayout? = null
    private var capture: CaptureView? = null
    private var expanded = true
    private val scope = CoroutineScope(Dispatchers.Main)
    private var startTime = 0L
    private var lastEnd = 0L

    override fun onBind(i: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        val ch = "overlay"
        getSystemService(NotificationManager::class.java).createNotificationChannel(NotificationChannel(ch, "Macro overlay", NotificationManager.IMPORTANCE_LOW))
        val pi = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        startForeground(1, Notification.Builder(this, ch).setContentTitle("TinyMacro overlay active")
            .setSmallIcon(android.R.drawable.ic_media_play).setContentIntent(pi).build())
        buildPanel()
    }

    override fun onDestroy() { stopRecordingLayer(); panel?.let { runCatching { wm.removeView(it) } }; scope.cancel(); super.onDestroy() }

    private fun lp(w: Int, h: Int, flags: Int) = WindowManager.LayoutParams(w, h, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        flags or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE, PixelFormat.TRANSLUCENT)

    // ---------- floating control panel ----------
    private fun buildPanel() {
        val dp = resources.displayMetrics.density * Cfg.overlaySize
        val p = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; alpha = Cfg.opacity
            background = android.graphics.drawable.GradientDrawable().apply { setColor(0xEE171923.toInt()); cornerRadius = 28 * dp }
            setPadding((6 * dp).toInt(), (6 * dp).toInt(), (6 * dp).toInt(), (6 * dp).toInt())
        }
        fun btn(t: String, onClick: () -> Unit) = TextView(this).apply {
            text = t; textSize = 18f * Cfg.overlaySize; gravity = Gravity.CENTER; setTextColor(Color.WHITE)
            setPadding((10 * dp).toInt(), (6 * dp).toInt(), (10 * dp).toInt(), (6 * dp).toInt()); setOnClickListener { onClick() }
        }
        val handle = btn("⠿") {}
        val params = lp(WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS)
        params.gravity = Gravity.TOP or Gravity.START; params.x = 40; params.y = 300
        var dx = 0f; var dy = 0f; var ox = 0; var oy = 0
        handle.setOnTouchListener { _, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> { dx = e.rawX; dy = e.rawY; ox = params.x; oy = params.y }
                MotionEvent.ACTION_MOVE -> { params.x = ox + (e.rawX - dx).toInt(); params.y = oy + (e.rawY - dy).toInt(); wm.updateViewLayout(p, params) }
            }; true
        }
        val items = listOf(
            btn("⏺") { startRecording() }, btn("⏸") { pauseRecording() }, btn("⏹") { stopRecording() },
            btn("▶") { Player.play(this, if (Hub.actions.isNotEmpty()) Hub.actions.toList() else emptyList(), Hub.recW, Hub.recH) },
            btn("■") { Player.stop() }, btn("⚙") { startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) })
        val mini = btn("—") {}
        mini.setOnClickListener { expanded = !expanded; items.forEach { it.visibility = if (expanded) View.VISIBLE else View.GONE }; mini.text = if (expanded) "—" else "＋" }
        p.addView(handle); items.forEach { p.addView(it) }; p.addView(mini)
        wm.addView(p, params); panel = p
    }

    // ---------- recording ----------
    private fun startRecording() {
        if (Hub.state == "playing") return
        if (Hub.state == "idle") { Hub.actions.clear(); startTime = System.currentTimeMillis(); lastEnd = startTime }
        val dm = resources.displayMetrics; Hub.recW = dm.widthPixels; Hub.recH = dm.heightPixels
        lastEnd = System.currentTimeMillis().let { if (Hub.state == "paused") it else lastEnd }
        Hub.state = "recording"; addCapture()
    }
    private fun pauseRecording() { if (Hub.state == "recording") { removeCapture(); Hub.state = "paused" } }
    private fun stopRecording() {
        if (Hub.state != "recording" && Hub.state != "paused") return
        removeCapture(); Hub.state = "idle"
        if (Cfg.autoSave && Hub.actions.isNotEmpty()) {
            val dm = resources.displayMetrics
            Store.save(Macro(name = "Recording " + SimpleDateFormat("MMM d HH:mm", Locale.getDefault()).format(Date()),
                width = Hub.recW, height = Hub.recH, orientation = if (dm.widthPixels > dm.heightPixels) "landscape" else "portrait",
                actions = Hub.actions.map { it.copy() }))
        }
    }
    private fun stopRecordingLayer() = removeCapture()
    private fun addCapture() {
        if (capture != null) return
        val v = CaptureView(this) { a, begin, end -> onGesture(a, begin, end) }
        wm.addView(v, lp(-1, -1, WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS))
        capture = v
        panel?.let { wm.removeView(it); wm.addView(it, it.layoutParams) } // keep panel above capture layer
    }
    private fun removeCapture() { capture?.let { runCatching { wm.removeView(it) } }; capture = null }

    /** Called when a finger lifts: store the action, then re-inject it into the app underneath. */
    private fun onGesture(a: Action, begin: Long, end: Long) {
        val gap = begin - lastEnd
        var merged = false
        if (a.type == "tap" && gap < 300) { // second quick tap near the first → one double-tap action
            var i = Hub.actions.lastIndex
            if (i >= 0 && Hub.actions[i].type == "wait" && Hub.actions[i].duration < 300) i--
            val p = Hub.actions.getOrNull(i)
            if (p != null && p.type == "tap" && hypot((p.x - a.x).toFloat(), (p.y - a.y).toFloat()) < 60) {
                while (Hub.actions.lastIndex > i) Hub.actions.removeAt(Hub.actions.lastIndex)
                Hub.actions[i] = p.copy(type = "doubletap", duration = gap); merged = true
            }
        }
        if (!merged) {
            if (gap >= Cfg.minGap) Hub.actions.add(Action("wait", duration = gap))
            a.timestamp = begin - startTime; Hub.actions.add(a)
        }
        lastEnd = end
        val c = capture ?: return
        val lpar = c.layoutParams as WindowManager.LayoutParams
        lpar.flags = lpar.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE; wm.updateViewLayout(c, lpar)
        scope.launch {
            val sw = a.type == "swipe"
            if (sw && a.path.size >= 2) MacroService.instance?.gesturePath(a.path, 1f, 1f, a.duration)
            else MacroService.instance?.gesture(a.x.toFloat(), a.y.toFloat(), (if (sw) a.ex else a.x).toFloat(), (if (sw) a.ey else a.y).toFloat(), if (a.type == "tap") 50 else a.duration)
            capture?.let { lpar.flags = lpar.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv(); runCatching { wm.updateViewLayout(it, lpar) } }
        }
    }

    companion object {
        fun start(c: Context) = c.startForegroundService(Intent(c, OverlayService::class.java))
        fun stop(c: Context) = c.stopService(Intent(c, OverlayService::class.java))
    }
}

/** Transparent full-screen view: captures a gesture, classifies it, and draws numbered touch markers. */
class CaptureView(c: Context, private val cb: (Action, Long, Long) -> Unit) : View(c) {
    private val pts = ArrayList<List<Int>>()
    private var sx = 0f; private var sy = 0f; private var cx = 0f; private var cy = 0f; private var t0 = 0L; private var down = false
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 5f; color = 0xFF7C5CFF.toInt() }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x557C5CFF }
    private val txt = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textSize = 34f; textAlign = Paint.Align.CENTER }
    private val slop = 24 * resources.displayMetrics.density

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> { sx = e.rawX; sy = e.rawY; cx = sx; cy = sy; t0 = System.currentTimeMillis(); down = true; pts.clear(); pts.add(listOf(sx.toInt(), sy.toInt(), 0)) }
            MotionEvent.ACTION_MOVE -> { cx = e.rawX; cy = e.rawY
                val l = pts.last(); if (pts.size < 120 && hypot(cx - l[0], cy - l[1]) > 6) pts.add(listOf(cx.toInt(), cy.toInt(), (System.currentTimeMillis() - t0).toInt())) }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> if (down) {
                down = false; val t1 = System.currentTimeMillis(); val d = t1 - t0
                val moved = hypot(e.rawX - sx, e.rawY - sy) > slop
                val a = when {
                    moved -> Action("swipe", sx.toInt(), sy.toInt(), e.rawX.toInt(), e.rawY.toInt(), d.coerceAtLeast(50),
                        path = pts + listOf(listOf(e.rawX.toInt(), e.rawY.toInt(), d.toInt())))
                    d >= 500 -> Action("longpress", sx.toInt(), sy.toInt(), duration = d)
                    else -> Action("tap", sx.toInt(), sy.toInt(), duration = 50)
                }
                cb(a, t0, t1)
            }
        }
        invalidate(); return true
    }

    override fun onDraw(c: Canvas) {
        if (!Cfg.touchViz) return
        var n = 0
        for (a in Hub.actions) if (a.type != "wait") { n++
            c.drawCircle(a.x.toFloat(), a.y.toFloat(), 38f, fill); c.drawCircle(a.x.toFloat(), a.y.toFloat(), 38f, ring)
            c.drawText("$n", a.x.toFloat(), a.y + 12f, txt)
            if (a.type == "swipe") { val q = if (a.path.size >= 2) a.path else listOf(listOf(a.x, a.y), listOf(a.ex, a.ey))
                for (k in 1 until q.size) c.drawLine(q[k - 1][0].toFloat(), q[k - 1][1].toFloat(), q[k][0].toFloat(), q[k][1].toFloat(), ring) }
        }
        if (down) { c.drawCircle(cx, cy, 56f, ring); for (k in 1 until pts.size) c.drawLine(pts[k - 1][0].toFloat(), pts[k - 1][1].toFloat(), pts[k][0].toFloat(), pts[k][1].toFloat(), ring) }
    }
}
