package com.cineremote.camera

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.hardware.camera2.CameraCharacteristics
import android.view.MotionEvent
import android.view.View
import kotlin.math.roundToInt

/**
 * On-phone camera HUD in the Blackmagic Camera style: a single thin top row of small label+value readouts
 * (LENS · FPS · SHUTTER · [timecode] · ISO · WB · TINT · EV · FOCUS · ZOOM · battery), a compact record
 * button and settings on the right edge, and - only while editing a readout - a slim value ruler at the
 * bottom. Tap a readout to edit it; tap the picture to focus. All controls drive [engine].
 */
class CameraHud(context: Context) : View(context) {

    lateinit var engine: CameraEngine
    lateinit var recorder: Recorder
    var onRecordToggle: () -> Unit = {}
    var onSettings: () -> Unit = {}
    var deviceName = ""
    var connected = false
    var onParamChanged: (Any?) -> Unit = {}
    var gridOn = false
        private set

    private val d = resources.displayMetrics.density
    private fun dp(v: Float) = v * d

    private val scrim = paint(Color.argb(115, 0, 0, 0))
    private val accent = Color.rgb(0x4D, 0x9B, 0xFF)
    private val labelP = tp(Color.argb(190, 170, 176, 186), 8.5f, true)
    private val valueP = tp(Color.WHITE, 14f, false)
    private val valueSelP = tp(accent, 14f, true)
    private val tcP = tp(Color.WHITE, 17f, false, mono = true)
    private val smallP = tp(Color.argb(190, 170, 176, 186), 9.5f, false)
    private val chipP = tp(Color.WHITE, 11.5f, true)
    private val rulerCur = tp(Color.WHITE, 16f, true)
    private val rulerOff = tp(Color.argb(160, 190, 196, 206), 13f, false)

    private fun paint(c: Int) = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = c }
    private fun tp(c: Int, sp: Float, bold: Boolean, mono: Boolean = false) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = c; textSize = sp * d
        typeface = Typeface.create(if (mono) Typeface.MONOSPACE else Typeface.SANS_SERIF, if (bold) Typeface.BOLD else Typeface.NORMAL)
    }

    private enum class P(val label: String, val auto: Boolean) {
        LENS("LENS", false), FPS("FPS", false), SHUTTER("SHUTTER", true), ISO("ISO", true),
        WB("WB", true), TINT("TINT", false), EV("EV", false), FOCUS("FOCUS", true), ZOOM("ZOOM", false)
    }
    private val leftGroup = listOf(P.LENS, P.FPS, P.SHUTTER, P.ISO, P.WB)
    private val rightGroup = listOf(P.TINT, P.EV, P.FOCUS, P.ZOOM)
    private var selected: P? = null

    private val hit = HashMap<P, RectF>()
    private var recRect = RectF()
    private var setRect = RectF()
    private var autoRect = RectF()
    private var rulerRect = RectF()
    private var focus: FloatArray? = null
    private var focusUntil = 0L

    init { setWillNotDraw(false) }

    private fun value(p: P): String = when (p) {
        P.LENS -> engine.lens.let { if (it.facing == CameraCharacteristics.LENS_FACING_FRONT) "Front" else fmtX(it.zoomVsMain) }
        P.FPS -> "${engine.fps}"
        P.SHUTTER -> "1/${if (engine.shutterAuto) engine.shutterDenFromNs(engine.liveExposureNs) else engine.shutterDen}"
        P.ISO -> "${if (engine.isoAuto) engine.isoStop(engine.liveIso) else engine.iso}"
        P.WB -> "${if (engine.wbAuto) engine.liveKelvin else engine.kelvin}K"
        P.TINT -> engine.tint.let { if (it > 0) "+$it" else "$it" }
        P.EV -> engine.evStops.let { if (kotlin.math.abs(it) < 0.05f) "0.0" else "%+.1f".format(it) }
        P.FOCUS -> if (engine.afAuto) "AF" else "%.2f".format(engine.focus01)
        P.ZOOM -> fmtX(engine.zoom)
    }
    private fun fmtX(z: Float) = (if (z % 1f == 0f) "%.0f".format(z) else "%.1f".format(z)) + "x"

    private fun isAuto(p: P) = when (p) { P.SHUTTER -> engine.shutterAuto; P.ISO -> engine.isoAuto
        P.WB -> engine.wbAuto; P.FOCUS -> engine.afAuto; else -> false }

    private fun choices(p: P): List<Pair<String, () -> Unit>> = when (p) {
        P.LENS -> (engine.lenses.filter { it.facing == CameraCharacteristics.LENS_FACING_BACK }.sortedBy { it.equivMm } +
                engine.lenses.filter { it.facing == CameraCharacteristics.LENS_FACING_FRONT }).map { l ->
            (if (l.facing == CameraCharacteristics.LENS_FACING_FRONT) "Front" else fmtX(l.zoomVsMain)) to { engine.switchLens(l.id) } }
        P.FPS -> engine.fpsChoices.ifEmpty { listOf(30) }.map { f -> "$f" to { engine.selectFps(f) } }
        P.SHUTTER -> SHUTTER_DENS.filter { it in engine.fps..8000 }.map { den -> "1/$den" to { engine.shutterAuto = false; engine.shutterDen = den } }
        P.ISO -> CameraEngine.ISO_STOPS.filter { it in engine.isoRange.lower..engine.isoRange.upper }.map { v -> "$v" to { engine.isoAuto = false; engine.iso = v } }
        P.WB -> (2500..10000 step 100).map { k -> "${k}K" to { engine.wbAuto = false; engine.kelvin = k } }
        P.TINT -> (-50..50 step 5).map { t -> (if (t > 0) "+$t" else "$t") to { engine.tint = t } }
        P.EV -> (-30..30).map { i -> val e = i / 10f; (if (kotlin.math.abs(e) < 0.05f) "0.0" else "%+.1f".format(e)) to { engine.evStops = e } }
        P.FOCUS -> (0..20).map { i -> "%.2f".format(i / 20f) to { engine.afAuto = false; engine.focus01 = i / 20f } }
        P.ZOOM -> { val r = engine.zoomRange; val n = ((r.upper - r.lower) / 0.1f).toInt().coerceIn(1, 120)
            (0..n).map { i -> val z = r.lower + i * 0.1f; fmtX(z) to { engine.zoom = z } } }
    }
    private fun curIndex(p: P, items: List<Pair<String, () -> Unit>>) = value(p).let { cur ->
        items.indexOfFirst { it.first == cur }.let { if (it >= 0) it else items.size / 2 } }

    fun showFocusReticle(nx: Float, ny: Float) { focus = floatArrayOf(nx, ny); focusUntil = now() + 1200; invalidate() }
    private fun now() = System.currentTimeMillis()

    // ------------------------------------------------------------------ draw
    override fun onDraw(c: Canvas) {
        val w = width.toFloat()
        val barH = dp(36f)
        c.drawRect(0f, 0f, w, barH, scrim)
        hit.clear()

        // left group
        var x = dp(12f)
        for (p in leftGroup) x = drawReadout(c, p, x, barH, alignLeft = true)
        // right group (ends before battery + settings)
        val railX = w - dp(52f)
        var rx = railX - dp(8f)
        // battery + remaining record time, small, right-most
        val runtime = runCatching { recorder.storage().third }.getOrDefault(0L)
        val info = "$battery%   ${runtime / 3600}:${"%02d".format((runtime / 60) % 60)}"
        val infoW = smallP.measureText(info)
        c.drawText(info, rx - infoW, dp(23f), smallP)
        rx -= infoW + dp(14f)
        // recording format: resolution + aspect (what you're recording in)
        rx = drawFormat(c, rx)
        for (p in rightGroup.reversed()) rx = drawReadoutRight(c, p, rx, barH)

        // timecode centered
        val tc = tcText()
        tcP.color = if (recorder.isRecording) Color.rgb(0xE5, 0x32, 0x2D) else Color.WHITE
        val tcx = w / 2 - tcP.measureText(tc) / 2
        if (recorder.isRecording) c.drawCircle(tcx - dp(12f), dp(16f), dp(4f), paint(Color.rgb(0xE5, 0x32, 0x2D)))
        c.drawText(tc, tcx, dp(24f), tcP)

        // connection dot (far top-left)
        c.drawCircle(dp(6f), dp(14f), dp(3f), paint(if (connected) Color.rgb(0x8A, 0xE2, 0x8A) else Color.argb(110, 200, 200, 200)))

        if (gridOn) drawGrid(c, w)
        drawRightRail(c, w)
        selected?.let { drawRuler(c, w, it) }
        drawReticle(c)
    }

    /** A small label-over-value readout; returns the next x. Tap target spans the whole bar height. */
    private fun drawReadout(c: Canvas, p: P, x: Float, barH: Float, alignLeft: Boolean): Float {
        val v = value(p)
        val vp = if (selected == p) valueSelP else valueP
        val wv = maxOf(vp.measureText(v), labelP.measureText(p.label))
        c.drawText(p.label, x, dp(13f), labelP)
        c.drawText(v, x, dp(28f), vp)
        if (isAuto(p)) c.drawText("A", x + vp.measureText(v) + dp(3f), dp(19f), tp(accent, 8f, true))
        hit[p] = RectF(x - dp(5f), 0f, x + wv + dp(10f), barH)
        return x + wv + dp(18f)
    }

    /** Non-tappable format readout: resolution (1080p / 2160p) + aspect ratio, like the Blackmagic app. */
    private fun drawFormat(c: Canvas, rightX: Float): Float {
        val s = engine.captureSize
        val res = when { s.height >= 2160 -> "4K"; s.height >= 1440 -> "${s.height}p"; else -> "${s.height}p" }
        val value = "$res  ${aspect(s.width, s.height)}"
        val wv = maxOf(valueP.measureText(value), labelP.measureText("FORMAT"))
        val x = rightX - wv
        c.drawText("FORMAT", x, dp(13f), labelP)
        c.drawText(value, x, dp(28f), valueP)
        return x - dp(18f)
    }
    private fun aspect(w: Int, h: Int): String {
        val r = w.toFloat() / h
        return when { kotlin.math.abs(r - 16f / 9) < 0.03f -> "16:9"; kotlin.math.abs(r - 4f / 3) < 0.03f -> "4:3"
            kotlin.math.abs(r - 2.39f) < 0.03f -> "2.39:1"; kotlin.math.abs(r - 17f / 9) < 0.03f -> "17:9"; else -> "%.2f:1".format(r) }
    }

    private fun drawReadoutRight(c: Canvas, p: P, rightX: Float, barH: Float): Float {
        val v = value(p)
        val vp = if (selected == p) valueSelP else valueP
        val wv = maxOf(vp.measureText(v), labelP.measureText(p.label))
        val x = rightX - wv
        c.drawText(p.label, x, dp(13f), labelP)
        c.drawText(v, x, dp(28f), vp)
        hit[p] = RectF(x - dp(6f), 0f, rightX + dp(4f), barH)
        return x - dp(16f)
    }

    private val funcRects = HashMap<String, RectF>()

    private fun drawRightRail(c: Canvas, w: Float) {
        val cx = w - dp(28f)
        val cy = height / 2f
        funcRects.clear()
        // record button, vertically centered
        val rad = dp(22f)
        recRect = RectF(cx - rad, cy - rad, cx + rad, cy + rad)
        c.drawCircle(cx, cy, rad, paint(Color.argb(60, 0, 0, 0)))
        c.drawCircle(cx, cy, rad, paint(Color.WHITE).apply { style = Paint.Style.STROKE; strokeWidth = dp(2.5f) })
        val red = Color.rgb(0xE5, 0x32, 0x2D)
        if (recorder.isRecording) { val s = dp(11f); c.drawRoundRect(RectF(cx - s / 2, cy - s / 2, cx + s / 2, cy + s / 2), dp(2f), dp(2f), paint(red)) }
        else c.drawCircle(cx, cy, dp(15f), paint(red))
        // function toggles above the record button
        val gap = dp(44f)
        funcButton(c, cx, cy - rad - gap, "GRID", gridOn)
        funcButton(c, cx, cy - rad - gap * 2, "STAB", engine.stabilization)
        if (engine.hasTorch) funcButton(c, cx, cy - rad - gap * 3, "FLASH", engine.torch)
        // settings gear below the record button
        val gy = cy + rad + gap
        funcRects["SET"] = RectF(cx - dp(18f), gy - dp(18f), cx + dp(18f), gy + dp(18f))
        c.drawText("⚙", cx - dp(9f), gy + dp(7f), tp(Color.argb(230, 230, 230, 230), 20f, false))
        setRect = funcRects["SET"]!!
    }

    private fun funcButton(c: Canvas, cx: Float, cy: Float, label: String, active: Boolean) {
        val r = dp(18f)
        val rect = RectF(cx - r, cy - r, cx + r, cy + r)
        c.drawCircle(cx, cy, r, paint(if (active) Color.argb(230, 0x4D, 0x9B, 0xFF) else Color.argb(110, 24, 26, 30)))
        val lp = tp(if (active) Color.WHITE else Color.argb(220, 210, 214, 220), 8.5f, true)
        c.drawText(label, cx - lp.measureText(label) / 2, cy + dp(3f), lp)
        funcRects[label] = rect
    }

    private fun tcText(): String {
        val s = if (recorder.isRecording) ((now() - recorder.startWallMs) / 1000) else 0L
        return "%02d:%02d:%02d:00".format(s / 3600, (s / 60) % 60, s % 60)
    }
    var battery = 100          // set by MainActivity

    private fun drawRuler(c: Canvas, w: Float, p: P) {
        val items = choices(p)
        if (items.isEmpty()) return
        val h = dp(46f)
        val top = height - h
        c.drawRect(0f, top, w - dp(52f), height.toFloat(), scrim)
        var left = dp(10f)
        autoRect = RectF()
        if (p.auto) {
            val aw = chipP.measureText("AUTO") + dp(18f)
            val r = RectF(left, top + dp(9f), left + aw, height - dp(9f))
            c.drawRoundRect(r, r.height() / 2, r.height() / 2, paint(if (isAuto(p)) Color.argb(235, 0x4D, 0x9B, 0xFF) else Color.argb(150, 40, 44, 52)))
            c.drawText("AUTO", r.centerX() - chipP.measureText("AUTO") / 2, r.centerY() + dp(4f), chipP)
            autoRect = r
            left = r.right + dp(12f)
        }
        val cx = (left + (w - dp(52f))) / 2
        val cur = curIndex(p, items)
        val spacing = dp(58f)
        for (i in items.indices) {
            val x = cx + (i - cur) * spacing
            if (x < left - spacing || x > w - dp(40f)) continue
            val onCur = i == cur
            val tpn = if (onCur) rulerCur else rulerOff
            c.drawText(items[i].first, x - tpn.measureText(items[i].first) / 2, top + h / 2 + dp(5f), tpn)
        }
        c.drawRect(cx - dp(0.8f), height - dp(8f), cx + dp(0.8f), height.toFloat(), paint(accent))
        rulerRect = RectF(left, top, w - dp(52f), height.toFloat())
    }

    private fun drawGrid(c: Canvas, w: Float) {
        val g = paint(Color.argb(70, 255, 255, 255)).apply { style = Paint.Style.STROKE; strokeWidth = dp(1f) }
        val h = height.toFloat()
        c.drawLine(w / 3, 0f, w / 3, h, g); c.drawLine(2 * w / 3, 0f, 2 * w / 3, h, g)
        c.drawLine(0f, h / 3, w, h / 3, g); c.drawLine(0f, 2 * h / 3, w, 2 * h / 3, g)
    }

    private fun drawReticle(c: Canvas) {
        val f = focus ?: return
        if (now() > focusUntil) { focus = null; return }
        val x = f[0] * width; val y = f[1] * height; val s = dp(30f)
        c.drawRect(x - s, y - s, x + s, y + s, paint(Color.rgb(0xF5, 0xC5, 0x18)).apply { style = Paint.Style.STROKE; strokeWidth = dp(1.5f) })
        invalidate()
    }

    // ------------------------------------------------------------------ touch
    private var drag: P? = null
    private var dragX = 0f
    private var dragIdx = 0

    override fun onTouchEvent(e: MotionEvent): Boolean {
        val x = e.x; val y = e.y
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                if (funcRects["SET"]?.contains(x, y) == true) { onSettings(); return true }
                if (recRect.contains(x, y)) { onRecordToggle(); invalidate(); return true }
                funcRects["GRID"]?.let { if (it.contains(x, y)) { gridOn = !gridOn; invalidate(); return true } }
                funcRects["STAB"]?.let { if (it.contains(x, y)) { engine.stabilization = !engine.stabilization; engine.apply(); onParamChanged(null); invalidate(); return true } }
                funcRects["FLASH"]?.let { if (it.contains(x, y)) { engine.torch = !engine.torch; engine.apply(); onParamChanged(null); invalidate(); return true } }
                val s = selected
                if (s != null && s.auto && autoRect.contains(x, y)) { toggleAuto(s); invalidate(); return true }
                if (s != null && rulerRect.contains(x, y)) { drag = s; dragX = x; dragIdx = curIndex(s, choices(s)); return true }
                hit.entries.firstOrNull { it.value.contains(x, y) }?.let {
                    selected = if (selected == it.key) null else it.key; invalidate(); return true }
                if (y < height - dp(46f)) {
                    engine.tapFocus((x / width).coerceIn(0f, 1f), (y / height).coerceIn(0f, 1f))
                    showFocusReticle(x / width, y / height); onParamChanged(null); return true
                }
            }
            MotionEvent.ACTION_MOVE -> {
                val p = drag ?: return true
                val items = choices(p)
                val idx = (dragIdx - ((x - dragX) / dp(58f)).roundToInt()).coerceIn(0, items.size - 1)
                items[idx].second(); engine.apply(); invalidate(); return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> if (drag != null) { drag = null; onParamChanged(null); return true }
        }
        return true
    }

    private fun toggleAuto(p: P) {
        when (p) { P.ISO -> engine.isoAuto = !engine.isoAuto; P.SHUTTER -> engine.shutterAuto = !engine.shutterAuto
            P.WB -> engine.wbAuto = !engine.wbAuto; P.FOCUS -> { engine.afAuto = !engine.afAuto; engine.focusLockedByTap = false }; else -> {} }
        engine.apply(); onParamChanged(null)
    }

    companion object {
        val SHUTTER_DENS = listOf(24, 25, 30, 48, 50, 60, 96, 100, 120, 125, 160, 180, 200, 240, 250, 320, 360,
            400, 480, 500, 640, 800, 1000, 1250, 1600, 2000, 2500, 3200, 4000, 8000)
    }
}
