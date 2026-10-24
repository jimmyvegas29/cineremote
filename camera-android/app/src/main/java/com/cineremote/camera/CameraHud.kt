package com.cineremote.camera

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.view.MotionEvent
import android.view.View
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * The on-phone camera screen, laid out like the Blackmagic Camera app:
 *  - the picture letterboxed on the left; label/value readouts across its top (LENS FPS SHUTTER IRIS ·
 *    timecode · ISO WB TINT · format badge) with blue "A" badges for automatic values
 *  - histogram, media (record time left / storage) and audio meter panels along its bottom
 *  - a tool column (frame guides, autofocus, exposure, torch, record, lens/zoom, stabilisation, flip)
 *    and a nav rail (Camera, Media, Remote, Settings) on the right
 * Geometry comes from a 2424x1080 reference screenshot: tool/nav sizes scale with screen height ([u]),
 * everything on the picture scales with the picture width ([s], reference 1855 px).
 * Tap a readout (or the exposure / autofocus / lens tool) to edit it on a ruler; tap the picture to focus.
 */
class CameraHud(context: Context) : View(context) {

    lateinit var engine: CameraEngine
    lateinit var recorder: Recorder
    var meter: AudioMeter? = null
    var histogram: () -> Array<FloatArray>? = { null }
    var onRecordToggle: () -> Unit = {}
    var onSettings: () -> Unit = {}
    var onMedia: () -> Unit = {}
    var onRemote: () -> Unit = {}
    var onParamChanged: (Any?) -> Unit = {}
    /** The picture's rectangle in this view; MainActivity places the preview SurfaceView there. */
    var onPreviewRect: (Rect) -> Unit = {}
    var deviceName = ""
    var connected = false
    var battery = 100
    var captureAspect = 16f / 9f
        set(v) { if (abs(v - field) > 0.001f) { field = v; layoutGeometry() } }

    var guidesOn = false
        private set
    private var panelsOn = true

    // ------------------------------------------------------------------ look
    private val lato = resources.getFont(R.font.lato_regular)
    private val latoBold = resources.getFont(R.font.lato_bold)
    private val latoBlack = resources.getFont(R.font.lato_black)

    private val navy = Color.rgb(7, 14, 24)
    private val accent = Color.rgb(49, 152, 252)
    private val red = Color.rgb(229, 50, 45)
    private val navActive = Color.rgb(0, 123, 255)
    private val navIdle = Color.rgb(106, 137, 189)

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG)
    private val path = Path()

    private fun fillC(c: Int) = fill.apply { color = c }
    private fun strokeC(c: Int, w: Float) = stroke.apply { color = c; strokeWidth = w }
    private fun textC(c: Int, size: Float, face: Typeface, shadow: Boolean = false) = text.apply {
        color = c; textSize = size; typeface = face
        if (shadow) setShadowLayer(size * 0.12f, 0f, size * 0.04f, Color.argb(150, 0, 0, 0)) else clearShadowLayer()
    }

    // ------------------------------------------------------------------ geometry
    private var u = 1f                  // screen height / 1080
    private var s = 1f                  // picture width / 1855
    private val pv = RectF()            // picture rectangle
    private var toolX = 0f; private var navX = 0f
    private fun x(rx: Float) = pv.left + rx * s
    private fun y(ry: Float) = pv.top + ry * s
    private val tx get() = toolX + 100 * u
    private val nx get() = navX + 78 * u

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) = layoutGeometry()

    private fun layoutGeometry() {
        if (width == 0 || height == 0) return
        u = height / 1080f
        navX = width - 194 * u
        toolX = navX - 202 * u
        val maxH = height - 36 * u
        var pw = toolX
        var ph = pw / captureAspect
        if (ph > maxH) { ph = maxH; pw = ph * captureAspect }
        val left = (toolX - pw) / 2
        val top = (height - ph) / 2
        pv.set(left, top, left + pw, top + ph)
        s = pw / 1855f
        onPreviewRect(Rect(pv.left.roundToInt(), pv.top.roundToInt(), pv.right.roundToInt(), pv.bottom.roundToInt()))
        invalidate()
    }

    // ------------------------------------------------------------------ parameters
    private enum class P(val label: String, val rx: Float, val autoCapable: Boolean) {
        LENS("LENS", 38f, false), FPS("FPS", 205f, false), SHUTTER("SHUTTER", 340f, true), IRIS("IRIS", 568f, false),
        ISO("ISO", 1188f, true), WB("WB", 1319f, true), TINT("TINT", 1507f, false),
        // edited from the tool column, no top readout
        EV("EXPOSURE", -1f, true), FOCUS("FOCUS", -1f, true), ZOOM("ZOOM", -1f, false)
    }
    private val readouts = listOf(P.LENS, P.FPS, P.SHUTTER, P.IRIS, P.ISO, P.WB, P.TINT)
    private var selected: P? = null

    private val autoExposure get() = engine.isoAuto && engine.shutterAuto

    private fun value(p: P): String = when (p) {
        P.LENS -> "${(engine.lens.equivMm * engine.zoom).roundToInt()}mm"
        P.FPS -> "${engine.fps}"
        P.SHUTTER -> "1/${if (engine.shutterAuto) engine.shutterDenFromNs(engine.liveExposureNs) else engine.shutterDen}"
        P.IRIS -> engine.aperture.let { if (it > 0) "f%.1f".format(it) else "f--" }
        P.ISO -> "${if (engine.isoAuto) engine.isoStop(engine.liveIso) else engine.iso}"
        P.WB -> "${if (engine.wbAuto) engine.liveKelvin else engine.kelvin}K"
        P.TINT -> signed(engine.tint)
        P.EV -> engine.evStops.let { if (abs(it) < 0.05f) "0.0" else "%+.1f".format(it) }
        P.FOCUS -> if (engine.afAuto) "AF" else "%.2f".format(engine.focus01)
        P.ZOOM -> fmtX(engine.zoom)
    }
    private fun signed(v: Int) = if (v > 0) "+$v" else "$v"
    private fun fmtX(z: Float) = (if (abs(z - z.roundToInt()) < 0.05f) "%.0f".format(z) else "%.1f".format(z)) + "x"

    private fun isAuto(p: P) = when (p) {
        P.SHUTTER -> engine.shutterAuto; P.ISO -> engine.isoAuto; P.WB -> engine.wbAuto
        P.EV -> autoExposure; P.FOCUS -> engine.afAuto; else -> false
    }

    private fun choices(p: P): List<Pair<String, () -> Unit>> = when (p) {
        P.LENS -> engine.lenses.map { l ->
            (if (l.facing == android.hardware.camera2.CameraCharacteristics.LENS_FACING_FRONT) "Front ${l.equivMm}mm"
             else "${l.equivMm}mm") to { engine.switchLens(l.id) } }
        P.FPS -> engine.fpsChoices.ifEmpty { listOf(30) }.map { f -> "$f" to { engine.selectFps(f) } }
        P.SHUTTER -> SHUTTER_DENS.filter { it in engine.fps..8000 }.map { d -> "1/$d" to { engine.shutterAuto = false; engine.shutterDen = d } }
        P.ISO -> CameraEngine.ISO_STOPS.filter { it in engine.isoRange.lower..engine.isoRange.upper }.map { v -> "$v" to { engine.isoAuto = false; engine.iso = v } }
        P.WB -> (2500..10000 step 100).map { k -> "${k}K" to { engine.wbAuto = false; engine.kelvin = k } }
        P.TINT -> (-50..50 step 5).map { t -> signed(t) to { engine.tint = t } }
        P.EV -> (-30..30).map { i -> val e = i / 10f; (if (i == 0) "0.0" else "%+.1f".format(e)) to { engine.evStops = e } }
        P.FOCUS -> (0..20).map { i -> "%.2f".format(i / 20f) to { engine.afAuto = false; engine.focusLockedByTap = false; engine.focus01 = i / 20f } }
        P.ZOOM -> { val r = engine.zoomRange; val n = ((r.upper - r.lower) / 0.1f).toInt().coerceIn(1, 120)
            (0..n).map { i -> val z = r.lower + i * 0.1f; fmtX(z) to { engine.zoom = z } } }
        P.IRIS -> emptyList()
    }
    private fun curIndex(p: P, items: List<Pair<String, () -> Unit>>): Int {
        val cur = if (p == P.LENS) engine.lens.let { l -> engine.lenses.indexOf(l) } else items.indexOfFirst { it.first == value(p) }
        return if (cur >= 0) cur else items.size / 2
    }

    private fun toggleAuto(p: P) {
        when (p) {
            P.ISO -> engine.isoAuto = !engine.isoAuto
            P.SHUTTER -> engine.shutterAuto = !engine.shutterAuto
            P.WB -> engine.wbAuto = !engine.wbAuto
            P.EV -> { val on = !autoExposure; engine.isoAuto = on; engine.shutterAuto = on }
            P.FOCUS -> { engine.afAuto = !engine.afAuto; engine.focusLockedByTap = false }
            else -> {}
        }
        engine.apply(); onParamChanged(null)
    }

    // ------------------------------------------------------------------ draw
    private var focus: FloatArray? = null
    private var focusUntil = 0L
    private fun now() = System.currentTimeMillis()

    fun showFocusReticle(nx: Float, ny: Float) { focus = floatArrayOf(nx, ny); focusUntil = now() + 1200; invalidate() }

    override fun onDraw(c: Canvas) {
        if (!::engine.isInitialized || pv.isEmpty) return
        drawBackground(c)
        if (guidesOn) drawGuides(c)
        drawReadouts(c)
        drawTimecode(c)
        drawFormatBadge(c)
        if (connected) drawRemoteIndicator(c)
        drawEdgeHandles(c)
        val sel = selected
        if (sel != null) drawRuler(c, sel)
        else if (panelsOn) { drawHistogramPanel(c); drawMediaPanel(c); drawAudioPanel(c) }
        drawReticle(c)
        drawToolColumn(c)
        drawNavRail(c)
        // the timecode needs every frame while recording; meters/histogram ~15x a second otherwise
        if (recorder.isRecording) postInvalidateOnAnimation() else postInvalidateDelayed(66)
    }

    private fun drawBackground(c: Canvas) {
        fillC(navy)
        c.drawRect(0f, 0f, navX, pv.top, fill)
        c.drawRect(0f, pv.bottom, navX, height.toFloat(), fill)
        c.drawRect(0f, pv.top, pv.left, pv.bottom, fill)
        c.drawRect(pv.right, pv.top, navX, pv.bottom, fill)
        fillC(Color.BLACK)
        c.drawRect(navX, 0f, width.toFloat(), height.toFloat(), fill)
    }

    private fun drawGuides(c: Canvas) {
        strokeC(Color.argb(110, 255, 255, 255), 1.5f * s)
        val w = pv.width() / 3; val h = pv.height() / 3
        for (i in 1..2) {
            c.drawLine(pv.left + w * i, pv.top, pv.left + w * i, pv.bottom, stroke)
            c.drawLine(pv.left, pv.top + h * i, pv.right, pv.top + h * i, stroke)
        }
    }

    private val readoutHit = HashMap<P, RectF>()

    private fun drawReadouts(c: Canvas) {
        readoutHit.clear()
        for (p in readouts) {
            val dim = p == P.IRIS
            val lx = x(p.rx)
            val labelColor = if (dim) Color.argb(150, 255, 255, 255) else Color.WHITE
            textC(labelColor, 25 * s, latoBold, shadow = true)
            c.drawText(p.label, lx, y(55f), text)
            val labelW = text.measureText(p.label)
            if (isAuto(p)) drawABadge(c, lx + labelW + 11 * s, y(37f), 20 * s)
            val v = value(p)
            val vColor = when { dim -> Color.argb(150, 255, 255, 255); selected == p -> accent; else -> Color.WHITE }
            textC(vColor, 43 * s, latoBold, shadow = true)
            c.drawText(v, lx, y(97f), text)
            val w = maxOf(text.measureText(v), labelW + 31 * s)
            if (!dim) readoutHit[p] = RectF(lx - 16 * s, y(18f), lx + w + 16 * s, y(115f))
        }
    }

    private fun drawABadge(c: Canvas, left: Float, top: Float, size: Float) {
        fillC(accent)
        c.drawRoundRect(RectF(left, top, left + size, top + size), size * 0.16f, size * 0.16f, fill)
        textC(Color.WHITE, size * 0.8f, latoBlack)
        c.drawText("A", left + (size - text.measureText("A")) / 2, top + size * 0.79f, text)
    }

    private fun timecode(): String {
        if (!recorder.isRecording) return "00:00:00:00"
        val ms = (now() - recorder.startWallMs).coerceAtLeast(0)
        val sec = ms / 1000
        val frames = (ms % 1000) * engine.fps / 1000
        return "%02d:%02d:%02d:%02d".format(sec / 3600, (sec / 60) % 60, sec % 60, frames)
    }

    private fun drawTimecode(c: Canvas) {
        val tc = timecode()
        textC(if (recorder.isRecording) red else Color.WHITE, 70 * s, latoBold, shadow = true)
        // fixed-width digit cells so the running timecode doesn't jitter
        val cell = "0123456789".maxOf { text.measureText(it.toString()) }
        val colon = text.measureText(":") + 4 * s
        val total = tc.sumOf { (if (it == ':') colon else cell).toDouble() }.toFloat()
        var cx = x(927f) - total / 2
        for (ch in tc) {
            val w = if (ch == ':') colon else cell
            val g = ch.toString()
            c.drawText(g, cx + (w - text.measureText(g)) / 2, y(88f), text)
            cx += w
        }
    }

    private fun drawFormatBadge(c: Canvas) {
        val r = RectF(x(1636f), y(34f), x(1710f), y(98f))
        val rad = 9 * s
        val split = y(69f)
        fillC(Color.BLACK); c.drawRoundRect(r, rad, rad, fill)
        c.save(); c.clipRect(r.left, r.top, r.right, split)
        fillC(Color.WHITE); c.drawRoundRect(r, rad, rad, fill)
        c.restore()
        strokeC(Color.WHITE, 2.5f * s); c.drawRoundRect(r, rad, rad, stroke)
        val sz = engine.captureSize
        val res = when { sz.height >= 2160 -> "4K"; sz.height >= 1440 -> "2.5K"; sz.height >= 1080 -> "HD"; else -> "${sz.height}p" }
        textC(Color.BLACK, 27 * s, latoBlack)
        c.drawText(res, r.centerX() - text.measureText(res) / 2, y(63f), text)
        val asp = aspect(sz.width, sz.height)
        textC(Color.WHITE, 17 * s, latoBold)
        c.drawText(asp, r.centerX() - text.measureText(asp) / 2, y(91f), text)
    }

    private fun aspect(w: Int, h: Int): String {
        val r = w.toFloat() / h
        return when {
            abs(r - 16f / 9) < 0.03f -> "16:9"; abs(r - 4f / 3) < 0.03f -> "4:3"
            abs(r - 17f / 9) < 0.03f -> "17:9"; abs(r - 2.39f) < 0.03f -> "2.39:1"; else -> "%.2f:1".format(r)
        }
    }

    /** "Controlled by CineRemote" indicator under LENS: a phone with signal arcs. */
    private fun drawRemoteIndicator(c: Canvas) {
        fillC(Color.argb(140, 0, 0, 0))
        c.drawRoundRect(RectF(x(26f), y(128f), x(130f), y(228f)), 22 * s, 22 * s, fill)
        drawPhoneWaves(c, x(78f), y(190f), s, Color.WHITE)
    }

    private fun drawPhoneWaves(c: Canvas, cx: Float, cy: Float, k: Float, color: Int) {
        strokeC(color, 4 * k)
        c.drawRoundRect(RectF(cx - 15 * k, cy - 22 * k, cx + 15 * k, cy + 22 * k), 8 * k, 8 * k, stroke)
        stroke.strokeWidth = 3.5f * k
        for (rad in listOf(13f, 22f)) {
            val r = rad * k
            c.drawArc(RectF(cx - r, cy - 32 * k - r, cx + r, cy - 32 * k + r), 235f, 70f, false, stroke)
        }
    }

    private var leftHandle = RectF(); private var rightHandle = RectF()

    private fun drawEdgeHandles(c: Canvas) {
        val my = pv.centerY()
        fun handle(cx: Float): RectF {
            val r = RectF(cx - 15 * s, my - 40 * s, cx + 15 * s, my + 40 * s)
            fillC(Color.argb(110, 0, 0, 0)); c.drawRoundRect(r, 15 * s, 15 * s, fill)
            fillC(Color.WHITE)
            for (i in -1..1) c.drawCircle(cx, my + i * 24 * s, 6.5f * s, fill)
            return RectF(r.left - 25 * s, r.top - 30 * s, r.right + 25 * s, r.bottom + 30 * s)
        }
        leftHandle = handle(x(29f))
        rightHandle = handle(x(1812f))
    }

    private fun panel(c: Canvas, rx0: Float, rx1: Float, ry0: Float = 870f, ry1: Float = 1017f, alpha: Int = 115): RectF {
        val r = RectF(x(rx0), y(ry0), x(rx1), y(ry1))
        fillC(Color.argb(alpha, 0, 0, 0)); c.drawRoundRect(r, 14 * s, 14 * s, fill)
        return r
    }

    private fun panelTitle(c: Canvas, title: String, rx0: Float, rx1: Float) {
        textC(Color.WHITE, 19 * s, lato)
        c.drawText(title, x(rx0 + 17f), y(893f), text)
        strokeC(Color.argb(70, 255, 255, 255), 1.5f * s)
        c.drawLine(x(rx0 + 17f), y(903f), x(rx1 - 16f), y(903f), stroke)
    }

    private fun drawHistogramPanel(c: Canvas) {
        panel(c, 27f, 393f)
        panelTitle(c, "Rec.709", 27f, 393f)
        val l = x(44f); val r = x(350f); val t = y(914f); val b = y(1000f)
        histogram()?.let { h ->
            val colors = intArrayOf(Color.argb(175, 235, 40, 40), Color.argb(175, 40, 215, 60), Color.argb(175, 50, 80, 255))
            for (ch in 0..2) {
                val bins = h[ch]
                path.reset(); path.moveTo(l, b)
                for (i in bins.indices) path.lineTo(l + (r - l) * i / (bins.size - 1), b - bins[i] * (b - t) * 0.95f)
                path.lineTo(r, b); path.close()
                fillC(colors[ch]); c.drawPath(path, fill)
            }
        }
        strokeC(Color.argb(200, 255, 255, 255), 2 * s)
        c.drawLine(l, t, l, b, stroke); c.drawLine(l, b, r, b, stroke)
        // clipping indicator column
        strokeC(Color.argb(190, 255, 255, 255), 1.5f * s)
        val cl = RectF(x(364f), y(920f), x(376f), y(1000f))
        c.drawRect(cl, stroke)
        for (i in 1..2) c.drawLine(cl.left, cl.top + cl.height() * i / 3, cl.right, cl.top + cl.height() * i / 3, stroke)
    }

    private var storage = Triple(0L, 0L, 0L)
    private var storageAt = 0L

    private fun drawMediaPanel(c: Canvas) {
        panel(c, 743f, 1110f)
        if (now() - storageAt > 2000) { storage = runCatching { recorder.storage() }.getOrDefault(storage); storageAt = now() }
        val (total, free, runtime) = storage
        // phone (internal storage) icon
        strokeC(Color.WHITE, 4.5f * s)
        c.drawRoundRect(RectF(x(776f), y(908f), x(822f), y(982f)), 9 * s, 9 * s, stroke)
        fillC(Color.WHITE)
        c.drawRoundRect(RectF(x(792f), y(916f), x(806f), y(920f)), 2 * s, 2 * s, fill)
        textC(Color.WHITE, 31 * s, latoBold)
        c.drawText("%d:%02d:%02d".format(runtime / 3600, (runtime / 60) % 60, runtime % 60), x(868f), y(927f), text)
        val bar = RectF(x(868f), y(941f), x(1098f), y(948f))
        fillC(Color.rgb(23, 64, 109)); c.drawRoundRect(bar, 3.5f * s, 3.5f * s, fill)
        val used = if (total > 0) (1f - free.toFloat() / total).coerceIn(0f, 1f) else 0f
        fillC(accent); c.drawRoundRect(RectF(bar.left, bar.top, bar.left + bar.width() * used, bar.bottom), 3.5f * s, 3.5f * s, fill)
        textC(Color.WHITE, 20 * s, latoBold)
        c.drawText("${((1f - used) * 100).roundToInt()}%", x(868f), y(982f), text)
        val gb = "${free / 1_000_000_000L}GB"
        c.drawText(gb, x(1098f) - text.measureText(gb), y(982f), text)
    }

    private fun drawAudioPanel(c: Canvas) {
        panel(c, 1462f, 1828f)
        panelTitle(c, "Internal", 1462f, 1828f)
        val bl = x(1497f); val br = x(1805f); val bw = br - bl
        val m = meter
        for (ch in 0..1) {
            val top = y(918f + ch * 30f); val bot = top + 23 * s
            textC(Color.WHITE, 20 * s, latoBold)
            c.drawText("${ch + 1}", x(1478f), bot - 4 * s, text)
            val lvl = dbFrac(m?.level?.get(ch) ?: AudioMeter.SILENCE)
            val zones = floatArrayOf(0f, dbFrac(-10f), dbFrac(0f), 1f)
            val dim = intArrayOf(Color.rgb(26, 64, 25), Color.rgb(75, 75, 41), Color.rgb(75, 25, 32))
            val lit = intArrayOf(Color.rgb(64, 205, 64), Color.rgb(224, 214, 54), Color.rgb(235, 52, 52))
            for (z in 0..2) {
                val z0 = bl + bw * zones[z]; val z1 = bl + bw * zones[z + 1]
                fillC(dim[z]); c.drawRect(z0, top, z1, bot, fill)
                val litTo = bl + bw * lvl
                if (litTo > z0) { fillC(lit[z]); c.drawRect(z0, top, minOf(z1, litTo), bot, fill) }
            }
            m?.peak?.get(ch)?.let { pk ->
                val f = dbFrac(pk)
                if (f > 0.01f) {
                    fillC(if (pk >= 0f) lit[2] else if (pk >= -10f) lit[1] else lit[0])
                    c.drawRect(bl + bw * f - 2 * s, top, bl + bw * f + 1 * s, bot, fill)
                }
            }
            strokeC(Color.BLACK, 2 * s); c.drawRect(bl, top, br, bot, stroke)
            fillC(Color.WHITE)
            for ((db, _) in METER_SCALE) {
                if (db <= -45f || db >= 3f) continue
                val mx = bl + bw * dbFrac(db)
                c.drawRect(mx - 1 * s, bot - 5 * s, mx + 1 * s, bot, fill)
            }
        }
        textC(Color.WHITE, 19 * s, latoBold)
        for ((db, _) in METER_SCALE) {
            val t = "${db.toInt()}"
            c.drawText(t, bl + bw * dbFrac(db) - text.measureText(t) / 2, y(998f), text)
        }
    }

    /** Position on the meter (0..1) of a dBFS level: piecewise-linear through the scale marks. */
    private fun dbFrac(db: Float): Float {
        if (db <= METER_SCALE.first().first) return 0f
        for (i in 1 until METER_SCALE.size) {
            val (d1, f1) = METER_SCALE[i]
            if (db <= d1) { val (d0, f0) = METER_SCALE[i - 1]; return f0 + (f1 - f0) * (db - d0) / (d1 - d0) }
        }
        return 1f
    }

    // ---- value ruler (replaces the bottom panels while a parameter is being edited)
    private var rulerRect = RectF(); private var autoRect = RectF()
    private var rulerCx = 0f
    private val rulerSpacing get() = 130 * s

    private fun drawRuler(c: Canvas, p: P) {
        val items = choices(p)
        rulerRect = panel(c, 27f, 1828f, 890f, 1017f, alpha = 160)
        var left = x(44f)
        autoRect = RectF()
        if (p.autoCapable) {
            val r = RectF(left, y(925f), left + 120 * s, y(982f))
            if (isAuto(p)) { fillC(accent); c.drawRoundRect(r, r.height() / 2, r.height() / 2, fill) }
            else { strokeC(Color.argb(170, 255, 255, 255), 2.5f * s); c.drawRoundRect(r, r.height() / 2, r.height() / 2, stroke) }
            textC(Color.WHITE, 22 * s, latoBlack)
            c.drawText("AUTO", r.centerX() - text.measureText("AUTO") / 2, r.centerY() + 8 * s, text)
            autoRect = r
            left = r.right + 30 * s
        }
        rulerCx = (left + x(1811f)) / 2
        textC(accent, 17 * s, latoBold)
        c.drawText(p.label, rulerCx - text.measureText(p.label) / 2, y(915f), text)
        if (items.isEmpty()) return
        val cur = curIndex(p, items)
        c.save(); c.clipRect(left, rulerRect.top, x(1811f), rulerRect.bottom)
        for (i in items.indices) {
            val ix = rulerCx + (i - cur) * rulerSpacing
            if (ix < left - rulerSpacing || ix > x(1811f) + rulerSpacing) continue
            val on = i == cur
            if (on) textC(Color.WHITE, 36 * s, latoBlack) else textC(Color.argb(150, 255, 255, 255), 25 * s, latoBold)
            val label = items[i].first
            c.drawText(label, ix - text.measureText(label) / 2, y(970f), text)
            strokeC(Color.argb(if (on) 255 else 110, 255, 255, 255), 2 * s)
            c.drawLine(ix, y(990f), ix, y(1000f), stroke)
        }
        c.restore()
        fillC(accent)
        path.reset(); path.moveTo(rulerCx - 9 * s, y(1010f)); path.lineTo(rulerCx + 9 * s, y(1010f)); path.lineTo(rulerCx, y(999f)); path.close()
        c.drawPath(path, fill)
    }

    private fun drawReticle(c: Canvas) {
        val f = focus ?: return
        if (now() > focusUntil) { focus = null; return }
        val cx = pv.left + f[0] * pv.width(); val cy = pv.top + f[1] * pv.height(); val r = 60 * s
        strokeC(Color.rgb(245, 197, 24), 3 * s)
        c.drawRoundRect(RectF(cx - r, cy - r, cx + r, cy + r), 6 * s, 6 * s, stroke)
    }

    // ---- tool column
    private enum class Tool { GUIDES, AF, EXPOSURE, TORCH, RECORD, LENS, STAB, FLIP }
    private val toolHit = HashMap<Tool, RectF>()

    private fun toolRect(t: Tool, cx: Float, cy: Float, r: Float) { toolHit[t] = RectF(cx - r, cy - r, cx + r, cy + r) }

    private fun drawToolColumn(c: Canvas) {
        toolHit.clear()
        val cx = tx
        // frame guides: corner brackets
        drawBrackets(c, cx, 64 * u, 67 * u, 54 * u, if (guidesOn) accent else Color.WHITE)
        toolRect(Tool.GUIDES, cx, 64 * u, 50 * u)
        // autofocus: rounded frame open at the sides, dot in the middle
        drawAfIcon(c, cx, 185 * u, if (engine.afAuto) accent else Color.WHITE)
        toolRect(Tool.AF, cx, 185 * u, 50 * u)
        // exposure +/- with A when automatic
        drawExposureIcon(c, cx - 4 * u, 307 * u)
        if (autoExposure) { textC(Color.WHITE, 30 * u, latoBlack); c.drawText("A", cx + 38 * u, 319 * u, text) }
        toolRect(Tool.EXPOSURE, cx, 307 * u, 50 * u)
        // torch, when this lens has one
        if (engine.hasTorch) {
            drawBolt(c, cx, 412 * u, if (engine.torch) accent else Color.WHITE)
            toolRect(Tool.TORCH, cx, 412 * u, 45 * u)
        }
        // record
        val ry = 540 * u
        strokeC(Color.WHITE, 5 * u); c.drawCircle(cx, ry, 77.5f * u, stroke)
        fillC(if (recorder.isRecording) red else Color.rgb(102, 13, 12)); c.drawCircle(cx, ry, 37 * u, fill)
        toolRect(Tool.RECORD, cx, ry, 85 * u)
        // lens / zoom
        val ly = 770 * u
        strokeC(Color.WHITE, 4 * u); c.drawCircle(cx, ly, 28 * u, stroke)
        fillC(Color.rgb(58, 62, 68)); c.drawCircle(cx, ly, 21 * u, fill)
        fillC(Color.rgb(150, 155, 162)); c.drawCircle(cx + 6 * u, ly - 7 * u, 5 * u, fill)
        val zoomLabel = if (engine.isFront) "F" else fmtX(engine.lens.zoomVsMain * engine.zoom)
        textC(Color.WHITE, 30 * u, lato)
        c.drawText(zoomLabel, cx + 63 * u - text.measureText(zoomLabel), 824 * u, text)
        toolRect(Tool.LENS, cx, ly, 50 * u)
        // stabilisation badge (in the Blackmagic LUT button's style)
        drawBadgeButton(c, cx - 2 * u, 888 * u, "STAB", engine.stabilization)
        toolRect(Tool.STAB, cx, 888 * u, 45 * u)
        // front/back flip
        drawFlipIcon(c, cx, 1013 * u)
        toolRect(Tool.FLIP, cx, 1013 * u, 50 * u)
    }

    private fun drawBrackets(c: Canvas, cx: Float, cy: Float, w: Float, h: Float, color: Int) {
        strokeC(color, 4 * u)
        val l = cx - w / 2 + 2 * u; val r = cx + w / 2 - 2 * u; val t = cy - h / 2 + 2 * u; val b = cy + h / 2 - 2 * u
        val k = 16 * u; val rad = 10 * u
        path.reset()
        path.moveTo(l, t + k); path.lineTo(l, t + rad); path.quadTo(l, t, l + rad, t); path.lineTo(l + k, t)
        path.moveTo(r - k, t); path.lineTo(r - rad, t); path.quadTo(r, t, r, t + rad); path.lineTo(r, t + k)
        path.moveTo(r, b - k); path.lineTo(r, b - rad); path.quadTo(r, b, r - rad, b); path.lineTo(r - k, b)
        path.moveTo(l + k, b); path.lineTo(l + rad, b); path.quadTo(l, b, l, b - rad); path.lineTo(l, b - k)
        c.drawPath(path, stroke)
    }

    private fun drawAfIcon(c: Canvas, cx: Float, cy: Float, color: Int) {
        strokeC(color, 4 * u)
        val w = 62 * u; val h = 50 * u; val rad = 13 * u
        val l = cx - w / 2; val r = cx + w / 2; val t = cy - h / 2; val b = cy + h / 2
        path.reset()
        path.moveTo(l, cy - 7 * u); path.lineTo(l, t + rad); path.quadTo(l, t, l + rad, t); path.lineTo(r - rad, t)
        path.quadTo(r, t, r, t + rad); path.lineTo(r, cy - 7 * u)
        path.moveTo(r, cy + 7 * u); path.lineTo(r, b - rad); path.quadTo(r, b, r - rad, b); path.lineTo(l + rad, b)
        path.quadTo(l, b, l, b - rad); path.lineTo(l, cy + 7 * u)
        c.drawPath(path, stroke)
        c.drawCircle(cx, cy, 10 * u, stroke)
    }

    private fun drawExposureIcon(c: Canvas, cx: Float, cy: Float) {
        val half = 27 * u
        val r = RectF(cx - half, cy - half, cx + half, cy + half)
        fillC(Color.WHITE); c.drawRoundRect(r, 9 * u, 9 * u, fill)
        // dark upper-left triangle
        c.save()
        path.reset(); path.moveTo(r.left, r.top); path.lineTo(r.right, r.top); path.lineTo(r.left, r.bottom); path.close()
        c.clipPath(path)
        fillC(navy); c.drawRoundRect(RectF(r.left + 3 * u, r.top + 3 * u, r.right, r.bottom), 7 * u, 7 * u, fill)
        c.restore()
        strokeC(Color.WHITE, 4 * u)
        c.drawLine(r.left + 10 * u, r.top + 18 * u, r.left + 26 * u, r.top + 18 * u, stroke)
        c.drawLine(r.left + 18 * u, r.top + 10 * u, r.left + 18 * u, r.top + 26 * u, stroke)
        strokeC(navy, 4 * u)
        c.drawLine(r.right - 26 * u, r.bottom - 16 * u, r.right - 10 * u, r.bottom - 16 * u, stroke)
    }

    private fun drawBolt(c: Canvas, cx: Float, cy: Float, color: Int) {
        strokeC(color, 4 * u)
        path.reset()
        path.moveTo(cx + 6 * u, cy - 26 * u); path.lineTo(cx - 12 * u, cy + 3 * u); path.lineTo(cx + 2 * u, cy + 3 * u)
        path.lineTo(cx - 6 * u, cy + 26 * u); path.lineTo(cx + 12 * u, cy - 3 * u); path.lineTo(cx - 2 * u, cy - 3 * u); path.close()
        c.drawPath(path, stroke)
    }

    private fun drawBadgeButton(c: Canvas, cx: Float, cy: Float, label: String, on: Boolean) {
        val r = RectF(cx - 29 * u, cy - 20 * u, cx + 29 * u, cy + 20 * u)
        fillC(Color.rgb(88, 92, 98)); c.drawRoundRect(RectF(r.left + 6 * u, r.top + 6 * u, r.right + 6 * u, r.bottom + 6 * u), 8 * u, 8 * u, fill)
        fillC(navy); c.drawRoundRect(r, 8 * u, 8 * u, fill)
        val col = if (on) accent else Color.WHITE
        strokeC(col, 3.5f * u); c.drawRoundRect(r, 8 * u, 8 * u, stroke)
        textC(col, 17 * u, latoBlack)
        c.drawText(label, r.centerX() - text.measureText(label) / 2, r.centerY() + 6 * u, text)
    }

    private fun drawFlipIcon(c: Canvas, cx: Float, cy: Float) {
        strokeC(Color.WHITE, 4 * u)
        val w = 60 * u; val h = 40 * u
        val r = RectF(cx - w / 2, cy - h / 2, cx + w / 2, cy + h / 2)
        // body with gaps on the left and right where the turn arrows sit
        path.reset()
        path.moveTo(r.left, cy - 6 * u); path.lineTo(r.left, r.top + 9 * u); path.quadTo(r.left, r.top, r.left + 9 * u, r.top)
        path.lineTo(r.right - 9 * u, r.top); path.quadTo(r.right, r.top, r.right, r.top + 9 * u)
        path.moveTo(r.right, cy + 6 * u); path.lineTo(r.right, r.bottom - 9 * u); path.quadTo(r.right, r.bottom, r.right - 9 * u, r.bottom)
        path.lineTo(r.left + 9 * u, r.bottom); path.quadTo(r.left, r.bottom, r.left, r.bottom - 9 * u)
        c.drawPath(path, stroke)
        c.drawCircle(cx, cy, 10 * u, stroke)
        // turn arrows: down at the right gap, up at the left gap
        path.reset()
        path.moveTo(r.right - 7 * u, cy - 2 * u); path.lineTo(r.right, cy + 6 * u); path.lineTo(r.right + 7 * u, cy - 2 * u)
        path.moveTo(r.left - 7 * u, cy + 2 * u); path.lineTo(r.left, cy - 6 * u); path.lineTo(r.left + 7 * u, cy + 2 * u)
        c.drawPath(path, stroke)
    }

    // ---- nav rail
    private enum class Nav(val label: String, val iconY: Float, val labelY: Float) {
        CAMERA("Camera", 153f, 225f), MEDIA("Media", 402f, 475f), REMOTE("Remote", 652f, 725f), SETTINGS("Settings", 902f, 975f)
    }
    private val navHit = HashMap<Nav, RectF>()

    private fun drawNavRail(c: Canvas) {
        navHit.clear()
        val cx = nx
        fillC(Color.rgb(0, 18, 38))
        c.drawRoundRect(RectF(navX + 18 * u, 90 * u, navX + 138 * u, 239 * u), 12 * u, 12 * u, fill)
        for (n in Nav.values()) {
            val iy = n.iconY * u
            val active = n == Nav.CAMERA
            when (n) {
                Nav.CAMERA -> drawCamcorder(c, cx, iy)
                Nav.MEDIA -> drawFilmFrame(c, cx, iy)
                Nav.REMOTE -> {
                    drawPhoneWaves(c, cx, iy + 8 * u, u, Color.WHITE)
                    fillC(if (connected) Color.rgb(64, 205, 64) else Color.argb(90, 255, 255, 255))
                    c.drawCircle(cx + 24 * u, iy + 22 * u, 7 * u, fill)
                }
                Nav.SETTINGS -> drawGear(c, cx, iy)
            }
            textC(if (active) navActive else navIdle, 25 * u, latoBold)
            c.drawText(n.label, cx - text.measureText(n.label) / 2, n.labelY * u, text)
            navHit[n] = RectF(navX, iy - 70 * u, width.toFloat(), n.labelY * u + 25 * u)
        }
        // battery, bottom of the rail
        val by = 1040 * u
        strokeC(navIdle, 2.5f * u)
        val body = RectF(cx - 38 * u, by - 12 * u, cx - 6 * u, by + 4 * u)
        c.drawRoundRect(body, 3 * u, 3 * u, stroke)
        fillC(navIdle)
        c.drawRect(body.right + 1 * u, by - 7 * u, body.right + 4 * u, by - 1 * u, fill)
        c.drawRect(body.left + 3 * u, body.top + 3 * u, body.left + 3 * u + (body.width() - 6 * u) * battery / 100f, body.bottom - 3 * u, fill)
        textC(navIdle, 20 * u, latoBold)
        c.drawText("$battery%", cx + 2 * u, by + 3 * u, text)
    }

    private fun drawCamcorder(c: Canvas, cx: Float, cy: Float) {
        val body = RectF(cx - 42 * u, cy - 22 * u, cx + 12 * u, cy + 22 * u)
        fillC(Color.rgb(0, 71, 147)); c.drawRoundRect(body, 9 * u, 9 * u, fill)
        strokeC(accent, 4 * u); c.drawRoundRect(body, 9 * u, 9 * u, stroke)
        path.reset()
        path.moveTo(cx + 14 * u, cy - 6 * u); path.lineTo(cx + 40 * u, cy - 19 * u); path.lineTo(cx + 40 * u, cy + 19 * u)
        path.lineTo(cx + 14 * u, cy + 6 * u); path.close()
        fillC(Color.rgb(0, 71, 147)); c.drawPath(path, fill)
        c.drawPath(path, stroke)
        fillC(accent)
        for (i in 0..2) c.drawRoundRect(RectF(cx - 34 * u + i * 13 * u, cy - 14 * u, cx - 26 * u + i * 13 * u, cy - 7 * u), 2 * u, 2 * u, fill)
    }

    private fun drawFilmFrame(c: Canvas, cx: Float, cy: Float) {
        val r = RectF(cx - 34 * u, cy - 23 * u, cx + 34 * u, cy + 23 * u)
        strokeC(Color.WHITE, 4 * u); c.drawRoundRect(r, 7 * u, 7 * u, stroke)
        fillC(Color.WHITE)
        // sprocket holes down both sides
        for (i in 0..3) {
            val hy = r.top + 9 * u + i * 9.5f * u
            for (hx in listOf(r.left + 9 * u, r.right - 9 * u)) c.drawRect(hx - 2.5f * u, hy - 2.5f * u, hx + 2.5f * u, hy + 2.5f * u, fill)
        }
        path.reset(); path.moveTo(cx - 8 * u, cy - 12 * u); path.lineTo(cx + 12 * u, cy); path.lineTo(cx - 8 * u, cy + 12 * u); path.close()
        c.drawPath(path, fill)
    }

    private fun drawGear(c: Canvas, cx: Float, cy: Float) {
        strokeC(Color.WHITE, 4 * u)
        path.reset()
        val teeth = 8
        for (i in 0 until teeth * 4) {
            val a = Math.PI * 2 * i / (teeth * 4) - Math.PI / 2
            val rr = (if ((i % 4) == 1 || (i % 4) == 2) 29f else 22f) * u
            val px = cx + (rr * cos(a)).toFloat(); val py = cy + (rr * sin(a)).toFloat()
            if (i == 0) path.moveTo(px, py) else path.lineTo(px, py)
        }
        path.close()
        c.drawPath(path, stroke)
        c.drawCircle(cx, cy, 9 * u, stroke)
    }

    // ------------------------------------------------------------------ touch
    private var dragging = false
    private var downX = 0f
    private var downIdx = 0
    private var rulerDown = false

    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (!::engine.isInitialized) return true
        val px = e.x; val py = e.y
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                rulerDown = false
                toolHit.entries.firstOrNull { it.value.contains(px, py) }?.let { onTool(it.key); invalidate(); return true }
                navHit.entries.firstOrNull { it.value.contains(px, py) }?.let { onNav(it.key); return true }
                val sel = selected
                if (sel != null && autoRect.contains(px, py)) { toggleAuto(sel); invalidate(); return true }
                if (sel != null && rulerRect.contains(px, py)) {
                    rulerDown = true; dragging = false; downX = px; downIdx = curIndex(sel, choices(sel)); return true
                }
                readoutHit.entries.firstOrNull { it.value.contains(px, py) }?.let {
                    selected = if (selected == it.key) null else it.key; invalidate(); return true }
                if (leftHandle.contains(px, py) || rightHandle.contains(px, py)) { panelsOn = !panelsOn; invalidate(); return true }
                if (pv.contains(px, py)) {
                    if (selected != null) { selected = null; invalidate(); return true }
                    val fx = ((px - pv.left) / pv.width()).coerceIn(0f, 1f); val fy = ((py - pv.top) / pv.height()).coerceIn(0f, 1f)
                    engine.tapFocus(fx, fy); showFocusReticle(fx, fy); onParamChanged(null)
                }
            }
            MotionEvent.ACTION_MOVE -> {
                val sel = selected ?: return true
                if (!rulerDown) return true
                if (abs(px - downX) > 12 * s) dragging = true
                if (!dragging) return true
                val items = choices(sel)
                if (items.isEmpty()) return true
                val idx = (downIdx - ((px - downX) / rulerSpacing).roundToInt()).coerceIn(0, items.size - 1)
                if (idx != curIndex(sel, items)) { items[idx].second(); engine.apply(); invalidate() }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                val sel = selected
                if (sel != null && rulerDown && !dragging && e.actionMasked == MotionEvent.ACTION_UP) {
                    // tap an item on the ruler to jump to it
                    val items = choices(sel)
                    val idx = (downIdx + ((px - rulerCx) / rulerSpacing).roundToInt()).coerceIn(0, maxOf(0, items.size - 1))
                    if (items.isNotEmpty()) { items[idx].second(); engine.apply() }
                }
                if (rulerDown) onParamChanged(null)
                rulerDown = false; dragging = false
                invalidate()
            }
        }
        return true
    }

    private fun onTool(t: Tool) {
        when (t) {
            Tool.GUIDES -> guidesOn = !guidesOn
            Tool.AF -> selected = if (selected == P.FOCUS) null else P.FOCUS
            Tool.EXPOSURE -> selected = if (selected == P.EV) null else P.EV
            Tool.TORCH -> { engine.torch = !engine.torch; engine.apply(); onParamChanged(null) }
            Tool.RECORD -> onRecordToggle()
            Tool.LENS -> selected = if (selected == P.ZOOM) null else P.ZOOM
            Tool.STAB -> { engine.stabilization = !engine.stabilization; engine.apply(); onParamChanged(null) }
            Tool.FLIP -> {
                val target = if (engine.isFront) engine.lenses.firstOrNull { it.type == CameraEngine.LENS_MAIN }
                             else engine.lenses.firstOrNull { it.type == CameraEngine.LENS_FRONT }
                target?.let { engine.switchLens(it.id); onParamChanged(null) }
            }
        }
    }

    private fun onNav(n: Nav) {
        when (n) {
            Nav.CAMERA -> { selected = null; invalidate() }
            Nav.MEDIA -> onMedia()
            Nav.REMOTE -> onRemote()
            Nav.SETTINGS -> onSettings()
        }
    }

    companion object {
        val SHUTTER_DENS = listOf(24, 25, 30, 48, 50, 60, 96, 100, 120, 125, 160, 180, 200, 240, 250, 320, 360,
            400, 480, 500, 640, 800, 1000, 1250, 1600, 2000, 2500, 3200, 4000, 8000)
        /** Audio meter scale marks (dBFS) and where they sit along the bar, from the reference layout. */
        val METER_SCALE = listOf(-45f to 0f, -30f to 0.140f, -20f to 0.321f, -10f to 0.594f, -6f to 0.711f,
            -3f to 0.802f, 0f to 0.912f, 3f to 1f)
    }
}
