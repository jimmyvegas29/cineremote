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
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * The on-phone camera HUD, drawn over the preview in a Blackmagic-style dark layout: a top status row
 * (format, timecode, battery, storage), a bottom strip of tappable parameter tiles with a value ruler,
 * lens chips, a record button, an audio meter and a tap-to-focus reticle. All controls drive [engine].
 */
class CameraHud(context: Context) : View(context) {

    lateinit var engine: CameraEngine
    lateinit var recorder: Recorder
    var onRecordToggle: () -> Unit = {}
    var onSettings: () -> Unit = {}
    var deviceName = ""
    var connected = false
    var audioLevel = 0f                 // 0..1 for the meter

    private val d = resources.displayMetrics.density
    private fun dp(v: Float) = v * d

    private val bg = paint(Color.argb(150, 0, 0, 0))
    private val tile = paint(Color.argb(170, 20, 22, 26))
    private val tileSel = paint(Color.argb(230, 32, 46, 74))
    private val line = paint(Color.argb(60, 255, 255, 255)).apply { style = Paint.Style.STROKE; strokeWidth = dp(1f) }
    private val rec = paint(Color.rgb(0xE5, 0x32, 0x2D))
    private val white = textPaint(Color.WHITE, 15f, true)
    private val grey = textPaint(Color.argb(200, 180, 188, 198), 11f, false)
    private val valuePaint = textPaint(Color.WHITE, 20f, false, mono = true)
    private val labelPaint = textPaint(Color.argb(210, 150, 158, 168), 10.5f, true)
    private val amber = textPaint(Color.rgb(0x5B, 0x9B, 0xFF), 10.5f, true)
    private val tc = textPaint(Color.WHITE, 22f, false, mono = true)
    private val chip = textPaint(Color.WHITE, 13f, true)

    private fun paint(c: Int) = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = c }
    private fun textPaint(c: Int, sp: Float, bold: Boolean, mono: Boolean = false) =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = c; textSize = sp * d
            typeface = Typeface.create(if (mono) Typeface.MONOSPACE else Typeface.SANS_SERIF,
                if (bold) Typeface.BOLD else Typeface.NORMAL)
        }

    // ---- parameters (tiles)
    private enum class P(val label: String, val auto: Boolean) {
        LENS("LENS", false), FPS("FPS", false), SHUTTER("SHUTTER", true), ISO("ISO", true),
        WB("WB", true), TINT("TINT", false), FOCUS("FOCUS", true), ZOOM("ZOOM", false)
    }
    private var selected: P? = null

    // interactive rects, recomputed each draw
    private val tileRects = HashMap<P, RectF>()
    private val lensChips = ArrayList<Pair<RectF, String>>()
    private var recRect = RectF()
    private var autoRect = RectF()          // the "A" toggle on the selected tile's ruler
    private var rulerRect = RectF()
    private var settingsRect = RectF()
    private var focusReticle: FloatArray? = null
    private var focusUntil = 0L

    init { setWillNotDraw(false) }

    private fun valueText(p: P): String = when (p) {
        P.LENS -> engine.lens.let { if (it.facing == CameraCharacteristics.LENS_FACING_FRONT) "Front" else "%.1fx".format(it.zoomVsMain) }
        P.FPS -> "${engine.fps}"
        P.SHUTTER -> "1/${if (engine.shutterAuto) engine.shutterDenFromNs(engine.liveExposureNs) else engine.shutterDen}"
        P.ISO -> "${if (engine.isoAuto) engine.isoStop(engine.liveIso) else engine.iso}"
        P.WB -> "${if (engine.wbAuto) engine.liveKelvin else engine.kelvin}K"
        P.TINT -> engine.tint.let { if (it > 0) "+$it" else "$it" }
        P.FOCUS -> if (engine.afAuto) "AF" else "%.2f".format(engine.focus01)
        P.ZOOM -> "%.1fx".format(engine.zoom)
    }

    private fun isAuto(p: P) = when (p) {
        P.SHUTTER -> engine.shutterAuto; P.ISO -> engine.isoAuto; P.WB -> engine.wbAuto; P.FOCUS -> engine.afAuto
        else -> false
    }

    private fun choices(p: P): List<Pair<String, () -> Unit>> = when (p) {
        P.FPS -> engine.fpsChoices.ifEmpty { listOf(30) }.map { f -> "$f" to { engine.selectFps(f) } }
        P.SHUTTER -> SHUTTER_DENS.filter { it in engine.fps..8000 }.map { den ->
            "1/$den" to { engine.shutterAuto = false; engine.shutterDen = den } }
        P.ISO -> CameraEngine.ISO_STOPS.filter { it in engine.isoRange.lower..engine.isoRange.upper }.map { v ->
            "$v" to { engine.isoAuto = false; engine.iso = v } }
        P.WB -> (2500..10000 step 100).map { k -> "${k}K" to { engine.wbAuto = false; engine.kelvin = k } }
        P.TINT -> (-50..50 step 5).map { t -> (if (t > 0) "+$t" else "$t") to { engine.tint = t } }
        P.FOCUS -> (0..20).map { i -> "%.2f".format(i / 20f) to { engine.afAuto = false; engine.focus01 = i / 20f } }
        P.ZOOM -> { val r = engine.zoomRange; val steps = ((r.upper - r.lower) / 0.1f).toInt().coerceIn(1, 100)
            (0..steps).map { i -> val z = r.lower + i * 0.1f; "%.1fx".format(z) to { engine.zoom = z } } }
        P.LENS -> emptyList()
    }

    private fun currentIndex(p: P, items: List<Pair<String, () -> Unit>>): Int {
        val cur = valueText(p)
        val i = items.indexOfFirst { it.first == cur }
        return if (i >= 0) i else items.size / 2
    }

    fun showFocusReticle(x: Float, y: Float) { focusReticle = floatArrayOf(x, y); focusUntil = now() + 1200; invalidate() }
    private fun now() = System.currentTimeMillis()

    // ------------------------------------------------------------------ draw
    override fun onDraw(c: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        drawTopBar(c, w)
        val stripH = dp(92f)
        val strip = RectF(0f, h - stripH, w, h)
        c.drawRect(strip, bg)
        val sel = selected
        if (sel != null && sel != P.LENS) drawRuler(c, w, strip.top, sel)
        drawTiles(c, w, strip)
        drawLensChips(c, strip)
        drawRecord(c, strip, w)
        drawFocusReticle(c)
    }

    private fun drawTopBar(c: Canvas, w: Float) {
        val barH = dp(44f)
        c.drawRect(0f, 0f, w, barH, bg)
        val fmt = "${engine.captureSize.width}×${engine.captureSize.height}  ${engine.fps}p"
        c.drawText(fmt, dp(16f), dp(28f), grey)
        val recording = recorder.isRecording
        val timecode = tcText()
        tc.color = if (recording) rec.color else Color.WHITE
        c.drawText(timecode, w / 2 - tc.measureText(timecode) / 2, dp(31f), tc)
        // battery + storage on the right
        val (_, _, runtime) = engine.let { runCatching { recorder.storage() }.getOrDefault(Triple(0L, 0L, 0L)) }
        val right = "${(runtime / 3600)}:${"%02d".format((runtime / 60) % 60)} left   ${deviceName.take(18)}  ⚙"
        c.drawText(right, w - dp(16f) - grey.measureText(right), dp(28f), grey)
        settingsRect = RectF(w - dp(16f) - grey.measureText(right), 0f, w, dp(44f))
        if (recording) { c.drawCircle(w / 2 - tc.measureText(timecode) / 2 - dp(14f), dp(24f), dp(5f), rec) }
        // connection dot
        val dot = if (connected) Color.rgb(0x8A, 0xE2, 0x8A) else Color.argb(120, 200, 200, 200)
        c.drawCircle(dp(8f), dp(22f), dp(3.5f), paint(dot))
    }

    private fun tcText(): String {
        val s = if (recorder.isRecording) ((now() - recorder.startWallMs) / 1000) else 0L
        return "%02d:%02d:%02d:00".format(s / 3600, (s / 60) % 60, s % 60)
    }

    private fun drawTiles(c: Canvas, w: Float, strip: RectF) {
        tileRects.clear()
        val params = P.values().filter { it != P.LENS }      // lens is chips, drawn separately
        val recW = dp(82f); val chipsW = dp(150f)
        val x0 = chipsW; val x1 = w - recW - dp(12f)
        val gap = dp(6f)
        val tw = (x1 - x0 - gap * (params.size - 1)) / params.size
        var x = x0
        val top = strip.top + dp(8f); val bot = strip.bottom - dp(8f)
        for (p in params) {
            val r = RectF(x, top, x + tw, bot)
            tileRects[p] = r
            val active = selected == p
            round(c, r, dp(8f), if (active) tileSel else tile)
            if (active) round(c, r, dp(8f), line)
            c.drawText(p.label, r.left + dp(10f), r.top + dp(16f), labelPaint)
            if (isAuto(p)) {
                val aw = amber.measureText("A")
                c.drawText("A", r.right - dp(10f) - aw, r.top + dp(16f), amber)
            }
            c.drawText(valueText(p), r.left + dp(10f), r.bottom - dp(12f), valuePaint)
            x += tw + gap
        }
        // settings gear tile (far left bottom corner handled by chips area) — add a small one
    }

    private fun drawLensChips(c: Canvas, strip: RectF) {
        lensChips.clear()
        val back = engine.lenses.filter { it.facing == CameraCharacteristics.LENS_FACING_BACK }.sortedBy { it.equivMm }
        val front = engine.lenses.firstOrNull { it.facing == CameraCharacteristics.LENS_FACING_FRONT }
        val items = back.map { it.id to "%.1fx".format(it.zoomVsMain).removeSuffix(".0x").let { s -> if (s.endsWith("x")) s else s + "x" } } +
                (front?.let { listOf(it.id to "Front") } ?: emptyList())
        val chipH = dp(30f); val y = strip.centerY() - chipH / 2
        var x = dp(10f)
        for ((id, lbl) in items) {
            val tw = chip.measureText(lbl) + dp(18f)
            val r = RectF(x, y, x + tw, y + chipH)
            val on = id == engine.lens.id
            round(c, r, chipH / 2, if (on) paint(Color.argb(235, 47, 124, 246)) else tile)
            c.drawText(lbl, r.centerX() - chip.measureText(lbl) / 2, r.centerY() + dp(4.5f), chip)
            lensChips.add(r to id)
            x += tw + dp(6f)
        }
    }

    private fun drawRuler(c: Canvas, w: Float, stripTop: Float, p: P) {
        val items = choices(p)
        if (items.isEmpty()) return
        val h = dp(48f)
        rulerRect = RectF(0f, stripTop - h, w, stripTop)
        c.drawRect(rulerRect, bg)
        // Auto toggle on the left for auto-capable params
        autoRect = RectF()
        var left = dp(8f)
        if (p.auto) {
            val aw = chip.measureText("AUTO") + dp(20f)
            val r = RectF(left, rulerRect.top + dp(9f), left + aw, rulerRect.bottom - dp(9f))
            val on = isAuto(p)
            round(c, r, r.height() / 2, if (on) paint(Color.argb(235, 47, 124, 246)) else tile)
            c.drawText("AUTO", r.centerX() - chip.measureText("AUTO") / 2, r.centerY() + dp(4.5f), chip)
            autoRect = r
            left = r.right + dp(10f)
        }
        // centered value ruler
        val cx = (left + w) / 2
        val cur = currentIndex(p, items)
        val spacing = dp(62f)
        for (i in items.indices) {
            val x = cx + (i - cur) * spacing
            if (x < left - spacing || x > w + spacing) continue
            val onCur = i == cur
            val tp = if (onCur) white else grey
            val t = items[i].first
            c.drawText(t, x - tp.measureText(t) / 2, rulerRect.centerY() + dp(5f), tp)
            if (onCur) c.drawRect(x - dp(1f), rulerRect.bottom - dp(6f), x + dp(1f), rulerRect.bottom, white)
        }
        rulerRect = RectF(left, rulerRect.top, w, rulerRect.bottom)   // draggable region excludes the AUTO chip
    }

    private fun drawRecord(c: Canvas, strip: RectF, w: Float) {
        val cxp = w - dp(46f); val cyp = strip.centerY(); val rad = dp(26f)
        recRect = RectF(cxp - rad, cyp - rad, cxp + rad, cyp + rad)
        c.drawCircle(cxp, cyp, rad, paint(Color.argb(255, 245, 245, 245)))
        val recording = recorder.isRecording
        if (recording) {
            val s = dp(13f)
            round(c, RectF(cxp - s / 2, cyp - s / 2, cxp + s / 2, cyp + s / 2), dp(2f), rec)
        } else {
            c.drawCircle(cxp, cyp, dp(18f), rec)
        }
    }

    private fun drawAudioMeter(c: Canvas, w: Float, h: Float) {
        val mh = dp(120f); val top = dp(60f)
        val x = w - dp(10f)
        val lit = (audioLevel.coerceIn(0f, 1f) * mh)
        val seg = dp(3f); val gap = dp(1.5f); var y = top + mh
        var i = 0
        while (y > top) {
            val frac = (top + mh - y) / mh
            val col = when { frac > 0.9f -> Color.rgb(0xEF, 0x44, 0x44); frac > 0.75f -> Color.rgb(0xF5, 0x9E, 0x0B); else -> Color.rgb(0x22, 0xC5, 0x5E) }
            val on = (top + mh - y) <= lit
            c.drawRect(x - dp(3f), y - seg, x, y, paint(if (on) col else Color.argb(40, col ushr 16 and 0xFF, col ushr 8 and 0xFF, col and 0xFF)))
            y -= seg + gap; i++
        }
    }

    private fun drawFocusReticle(c: Canvas) {
        val f = focusReticle ?: return
        if (now() > focusUntil) { focusReticle = null; return }
        val x = f[0] * width; val y = f[1] * height; val s = dp(34f)
        val p = paint(Color.rgb(0xF5, 0xC5, 0x18)).apply { style = Paint.Style.STROKE; strokeWidth = dp(1.5f) }
        c.drawRect(x - s, y - s, x + s, y + s, p)
        invalidate()
    }

    private fun round(c: Canvas, r: RectF, rad: Float, p: Paint) = c.drawRoundRect(r, rad, rad, p)

    // ------------------------------------------------------------------ touch
    private var dragParam: P? = null
    private var dragStartX = 0f
    private var dragStartIndex = 0

    override fun onTouchEvent(e: MotionEvent): Boolean {
        val x = e.x; val y = e.y
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                if (settingsRect.contains(x, y)) { onSettings(); return true }
                if (recRect.contains(x, y)) { onRecordToggle(); invalidate(); return true }
                lensChips.firstOrNull { it.first.contains(x, y) }?.let { engine.switchLens(it.second); invalidate(); return true }
                val s = selected
                if (s != null && s.auto && autoRect.contains(x, y)) { toggleAuto(s); invalidate(); return true }
                if (s != null && rulerRect.contains(x, y)) {
                    dragParam = s; dragStartX = x
                    dragStartIndex = currentIndex(s, choices(s)); return true
                }
                val hit = tileRects.entries.firstOrNull { it.value.contains(x, y) }?.key
                if (hit != null) { selected = if (selected == hit) null else hit; invalidate(); return true }
                // tap on preview area = focus
                if (y < height - dp(92f) - dp(48f)) {
                    engine.tapFocus((x / width).coerceIn(0f, 1f), (y / height).coerceIn(0f, 1f))
                    showFocusReticle(x / width, y / height)
                    return true
                }
            }
            MotionEvent.ACTION_MOVE -> {
                val p = dragParam ?: return true
                val items = choices(p)
                val spacing = dp(62f)
                val idx = (dragStartIndex - ((x - dragStartX) / spacing).roundToInt()).coerceIn(0, items.size - 1)
                items[idx].second()
                engine.apply()
                invalidate(); return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (dragParam != null) { dragParam = null; emitSelected(); return true }
            }
        }
        return true
    }

    private fun toggleAuto(p: P) {
        when (p) {
            P.ISO -> engine.isoAuto = !engine.isoAuto
            P.SHUTTER -> engine.shutterAuto = !engine.shutterAuto
            P.WB -> engine.wbAuto = !engine.wbAuto
            P.FOCUS -> { engine.afAuto = !engine.afAuto; engine.focusLockedByTap = false }
            else -> {}
        }
        engine.apply()
        emitSelected()
    }

    private fun emitSelected() { onParamChanged(selected) }
    var onParamChanged: (Any?) -> Unit = {}

    companion object {
        val SHUTTER_DENS = listOf(24, 25, 30, 48, 50, 60, 96, 100, 120, 125, 160, 180, 200, 240, 250, 320, 360,
            400, 480, 500, 640, 800, 1000, 1250, 1600, 2000, 2500, 3200, 4000, 8000)
    }
}
