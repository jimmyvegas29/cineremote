package com.cineremote.camera

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.hardware.camera2.CameraCharacteristics
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
 *  - histogram, media (record time left / storage) and audio meter panels along its bottom; tap the
 *    histogram for an RGB parade, the audio meter for a big meter with Audio Gain
 *  - a tool column (frame guides, autofocus, exposure, record, lens/zoom, stabilisation, flip) and a nav
 *    rail (Camera, Media, Remote, Settings) on the right
 * Tap a readout or tool to edit it in the editor panel at the bottom (rulers, preset buttons, AUTO, locks).
 * Geometry comes from a 2424x1080 reference screenshot: tool/nav sizes scale with screen height ([u]); the
 * overlays scale with the largest 16:9 frame that fits ([s], reference 1855 px wide), and the picture is
 * fitted inside that frame at the recording format's aspect ratio.
 */
class CameraHud(context: Context) : View(context) {

    lateinit var engine: CameraEngine
    lateinit var recorder: Recorder
    lateinit var prefs: Prefs
    var audio: AudioInput? = null
    lateinit var aids: MonitorAids
    var histogram: () -> Array<FloatArray>? = { null }
    var recording: () -> Boolean = { false }
    var plannedBitrate: () -> Int = { 20_000_000 }
    var onRecordToggle: () -> Unit = {}
    var onSettings: (page: String?) -> Unit = {}
    var onMedia: () -> Unit = {}
    var onRemote: () -> Unit = {}
    var onParamChanged: (Any?) -> Unit = {}
    var onGainChanged: (Int) -> Unit = {}
    lateinit var slate: Slate
    lateinit var presets: Presets
    /** The latest small (160x90) ARGB copy of the picture, for LUT thumbnails. */
    var smallFrame: () -> IntArray? = { null }
    var onLutChanged: (String?) -> Unit = {}
    var onGoodTake: (Boolean) -> Unit = {}
    /** The picture's rectangle in this view; MainActivity places the preview view there. */
    var onPreviewRect: (Rect) -> Unit = {}
    var connected = false
    var battery = 100
    var settingsOpen = false
    var captureAspect = 16f / 9f
        set(v) { if (abs(v - field) > 0.001f) { field = v; layoutGeometry() } }

    // ---- overlay state
    private var guidesOn = false            // tool-column guides button: shows the guides toolbar
    private var guideTool = GuideTool.FRAME
    private var frameGuide: String? = null  // "2.39:1" etc
    private var safeArea = 0                // percent, 0 = off
    private var grid = Grid.OFF
    private var panelsOn = true
    private var expanded: Expanded? = null  // a bottom panel opened up
    private var lutPanel = false            // LUT picker (tool column LUT button)
    private var presetsPanel = false        // presets (left handle)
    private var selectedPreset = -1
    private var sideMenu = false            // left handle: presets + slate buttons
    private var fnShown = false             // right handle: F1-F3
    private var cleanFeed = false           // function-button action: hide the overlays

    private enum class GuideTool { TORCH, FOCUS, FALSE, SAFE, FRAME, GRID, FACE, ZEBRA }
    private enum class Grid(val label: String) { OFF("Off"), THIRDS("Thirds"), CROSS("Crosshair"), DOT("Centre dot"), THIRDS_CROSS("Thirds +") }
    private enum class Expanded { AUDIO, HISTOGRAM }

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
    private fun centered(c: Canvas, t: String, cx: Float, baseline: Float) = c.drawText(t, cx - text.measureText(t) / 2, baseline, text)

    // ------------------------------------------------------------------ geometry
    private var u = 1f                  // screen height / 1080
    private var s = 1f                  // overlay frame width / 1855
    private val frame = RectF()         // the 16:9 overlay frame
    private val pv = RectF()            // the picture (recording aspect, inside the frame)
    private var toolX = 0f; private var navX = 0f
    private fun x(rx: Float) = frame.left + rx * s
    private fun y(ry: Float) = frame.top + ry * s
    private val tx get() = toolX + 100 * u
    private val nx get() = navX + 78 * u

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) = layoutGeometry()

    private fun layoutGeometry() {
        if (width == 0 || height == 0) return
        u = height / 1080f
        navX = width - 194 * u
        toolX = navX - 202 * u
        fun fit(aspect: Float, out: RectF) {
            val maxH = height - 36 * u
            var w = toolX; var h = w / aspect
            if (h > maxH) { h = maxH; w = h * aspect }
            out.set((toolX - w) / 2, (height - h) / 2, (toolX + w) / 2, (height + h) / 2)
        }
        fit(16f / 9f, frame)
        s = frame.width() / 1855f
        // the picture fills the frame's width or height, whichever its aspect allows
        var pw = frame.width(); var ph = pw / captureAspect
        if (ph > frame.height()) { ph = frame.height(); pw = ph * captureAspect }
        pv.set(frame.centerX() - pw / 2, frame.centerY() - ph / 2, frame.centerX() + pw / 2, frame.centerY() + ph / 2)
        onPreviewRect(Rect(pv.left.roundToInt(), pv.top.roundToInt(), pv.right.roundToInt(), pv.bottom.roundToInt()))
        invalidate()
    }

    // ------------------------------------------------------------------ parameters
    private enum class P(val label: String, val rx: Float, val autoCapable: Boolean) {
        LENS("LENS", 38f, false), FPS("FPS", 205f, false), SHUTTER("SHUTTER", 340f, true), IRIS("IRIS", 568f, false),
        ISO("ISO", 1188f, true), WB("WB", 1319f, true), TINT("TINT", 1507f, false),
        // edited from the tool column, no top readout
        EV("EXPOSURE", -1f, true), FOCUS("FOCUS", -1f, true)
    }
    private val readouts = listOf(P.LENS, P.FPS, P.SHUTTER, P.IRIS, P.ISO, P.WB, P.TINT)
    private var selected: P? = null

    private val autoExposure get() = engine.isoAuto && engine.shutterAuto

    private fun value(p: P): String = when (p) {
        P.LENS -> "${(engine.lens.equivMm * engine.zoom).roundToInt()}mm"
        P.FPS -> when { engine.offSpeed -> "${engine.offSpeedFps}"; prefs.timelapse -> "TL"; else -> "${engine.fps}" }
        P.SHUTTER -> "1/${if (engine.shutterAuto) engine.shutterDenFromNs(engine.liveExposureNs) else engine.shutterDen}"
        P.IRIS -> engine.aperture.let { if (it > 0) "f%.1f".format(it) else "f--" }
        P.ISO -> "${if (engine.isoAuto) engine.isoStop(engine.liveIso) else engine.iso}"
        P.WB -> "${if (engine.wbAuto) engine.liveKelvin else engine.kelvin}K"
        P.TINT -> signed(engine.tint)
        P.EV -> engine.evStops.let { if (abs(it) < 0.05f) "0.0" else "%+.1f".format(it) }
        P.FOCUS -> if (engine.afAuto) "AF" else "%.2f".format(engine.focus01)
    }
    private fun signed(v: Int) = if (v > 0) "+$v" else "$v"
    private fun fmtX(z: Float) = (if (abs(z - z.roundToInt()) < 0.05f) "%.0f".format(z) else "%.1f".format(z)) + "x"

    private fun isAuto(p: P) = when (p) {
        P.SHUTTER -> engine.shutterAuto; P.ISO -> engine.isoAuto; P.WB -> engine.wbAuto
        P.EV -> autoExposure; P.FOCUS -> engine.afAuto; else -> false
    }

    private fun toggleAuto(p: P) {
        when (p) {
            P.ISO -> engine.isoAuto = !engine.isoAuto
            P.SHUTTER -> engine.shutterAuto = !engine.shutterAuto
            P.WB -> { engine.wbAuto = !engine.wbAuto; engine.awbLock = false }
            P.EV -> { val on = !autoExposure; engine.isoAuto = on; engine.shutterAuto = on; engine.aeLock = false }
            P.FOCUS -> { engine.afAuto = !engine.afAuto; engine.focusLockedByTap = false }
            else -> {}
        }
        changed()
    }

    private fun changed() { engine.apply(); onParamChanged(null); invalidate() }

    private class Item(val label: String, val on: Boolean, val act: () -> Unit)

    private fun ruler(p: P): Pair<List<Item>, Int>? {
        val cur = value(p)
        val items: List<Pair<String, () -> Unit>> = when (p) {
            P.FPS -> engine.fpsChoices.map { f -> "$f" to { engine.selectFps(f) } }
            P.SHUTTER -> SHUTTER_DENS.filter { it in engine.fps..8000 }.map { d -> "1/$d" to { engine.shutterAuto = false; engine.shutterDen = d } }
            P.WB -> (2500..10000 step 100).map { k -> "${k}K" to { engine.wbAuto = false; engine.kelvin = k } }
            P.TINT -> (-50..50 step 5).map { t -> signed(t) to { engine.tint = t } }
            P.EV -> (-30..30).map { i -> (if (i == 0) "0.0" else "%+.1f".format(i / 10f)) to { engine.evStops = i / 10f } }
            P.FOCUS -> (0..20).map { i -> "%.2f".format(i / 20f) to { engine.afAuto = false; engine.focusLockedByTap = false; engine.focus01 = i / 20f } }
            P.LENS -> { val r = engine.zoomRange; val n = ((r.upper - r.lower) / 0.1f).toInt().coerceIn(1, 120)
                (0..n).map { i -> val z = r.lower + i * 0.1f; fmtX(z) to { engine.zoom = z } } }
            else -> return null
        }
        if (items.isEmpty()) return null
        val idx = if (p == P.LENS) items.indexOfFirst { it.first == fmtX(engine.zoom) } else items.indexOfFirst { it.first == cur }
        return items.map { Item(it.first, false, it.second) } to (if (idx >= 0) idx else nearest(p, items.size))
    }

    /** Where the ruler sits when the live value isn't one of its steps (auto values drift). */
    private fun nearest(p: P, n: Int): Int = when (p) {
        P.SHUTTER -> SHUTTER_DENS.filter { it in engine.fps..8000 }.let { l ->
            val d = engine.shutterDenFromNs(engine.liveExposureNs); l.indices.minByOrNull { abs(l[it] - d) } ?: 0 }
        P.WB -> ((engine.liveKelvin - 2500) / 100).coerceIn(0, n - 1)
        else -> n / 2
    }

    // ------------------------------------------------------------------ draw
    private var focus: FloatArray? = null
    private var focusUntil = 0L
    private fun now() = System.currentTimeMillis()

    fun showFocusReticle(nx: Float, ny: Float) { focus = floatArrayOf(nx, ny); focusUntil = now() + 1200; invalidate() }

    override fun onDraw(c: Canvas) {
        if (!::engine.isInitialized || frame.isEmpty) return
        buttons.clear(); rulers.clear(); sliders.clear()
        drawBackground(c)
        drawOverlays(c)
        drawFaces(c)
        if (!cleanFeed) {
            drawReadouts(c)
            drawTimecode(c)
            drawFormatBadge(c)
            if (connected) drawRemoteIndicator(c)
        }
        drawEdgeHandles(c)
        if (sideMenu) drawSideMenu(c)
        if (fnShown && prefs.functionButtons) drawFunctionButtons(c)
        val sel = selected
        when {
            sel != null -> drawEditor(c, sel)
            guidesOn -> drawGuidesBar(c)
            lutPanel -> drawLutPanel(c)
            presetsPanel -> drawPresetsPanel(c)
            expanded == Expanded.AUDIO -> drawAudioExpanded(c)
            expanded == Expanded.HISTOGRAM -> drawParade(c)
            panelsOn && !cleanFeed -> {
                if (aids.falseColor) drawFalseColourKey(c)
                if (prefs.showHistogram) drawHistogramPanel(c)
                if (prefs.showStorage) drawMediaPanel(c)
                if (prefs.showAudioMeters) drawAudioPanel(c)
            }
        }
        if (recorder.isRecording && !engine.streamInSession) drawHighSpeedNotice(c)
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

    /** Frame guides (dimmed outside the guide ratio), safe area and grid, inside the picture. */
    private fun drawOverlays(c: Canvas) {
        val gA = (prefs.guidesOpacity * 255 / 100).coerceIn(30, 255)
        frameGuide?.let { g ->
            val r = GUIDE_RATIOS[g] ?: return@let
            val pr = pv.width() / pv.height()
            val inner = if (r > pr) { val h = pv.width() / r; RectF(pv.left, pv.centerY() - h / 2, pv.right, pv.centerY() + h / 2) }
                        else { val w = pv.height() * r; RectF(pv.centerX() - w / 2, pv.top, pv.centerX() + w / 2, pv.bottom) }
            fillC(Color.argb(gA * 3 / 4, 0, 0, 0))
            c.drawRect(pv.left, pv.top, pv.right, inner.top, fill); c.drawRect(pv.left, inner.bottom, pv.right, pv.bottom, fill)
            c.drawRect(pv.left, inner.top, inner.left, inner.bottom, fill); c.drawRect(inner.right, inner.top, pv.right, inner.bottom, fill)
            strokeC(Color.argb(gA, 255, 255, 255), 2 * s); c.drawRect(inner, stroke)
        }
        if (safeArea > 0) {
            val k = safeArea / 100f
            val w = pv.width() * k; val h = pv.height() * k
            strokeC(Color.argb(gA, 255, 255, 255), 2 * s)
            c.drawRect(pv.centerX() - w / 2, pv.centerY() - h / 2, pv.centerX() + w / 2, pv.centerY() + h / 2, stroke)
        }
        val grA = (prefs.gridsOpacity * 255 / 100).coerceIn(30, 255)
        strokeC(Color.argb(grA, 255, 255, 255), 1.5f * s)
        if (grid == Grid.THIRDS || grid == Grid.THIRDS_CROSS) {
            val w = pv.width() / 3; val h = pv.height() / 3
            for (i in 1..2) {
                c.drawLine(pv.left + w * i, pv.top, pv.left + w * i, pv.bottom, stroke)
                c.drawLine(pv.left, pv.top + h * i, pv.right, pv.top + h * i, stroke)
            }
        }
        if (grid == Grid.CROSS || grid == Grid.THIRDS_CROSS) {
            val k = 40 * s
            c.drawLine(pv.centerX() - k, pv.centerY(), pv.centerX() + k, pv.centerY(), stroke)
            c.drawLine(pv.centerX(), pv.centerY() - k, pv.centerX(), pv.centerY() + k, stroke)
        }
        if (grid == Grid.DOT) { fillC(Color.argb(grA, 255, 255, 255)); c.drawCircle(pv.centerX(), pv.centerY(), 6 * s, fill) }
    }

    private val readoutHit = HashMap<P, RectF>()

    private fun drawReadouts(c: Canvas) {
        readoutHit.clear()
        for (p in readouts) {
            val dim = p == P.IRIS
            val lx = x(p.rx)
            textC(Color.WHITE, 25 * s, latoBold); val labelW = text.measureText(p.label)
            textC(Color.WHITE, 43 * s, latoBold); val valueW = text.measureText(value(p))
            val w = maxOf(valueW, labelW + if (p.autoCapable) 31 * s else 0f)
            val box = RectF(lx - 12 * s, y(26f), lx + w + 12 * s, y(110f))
            val sel = selected == p
            if (sel) { fillC(accent); c.drawRoundRect(box, 8 * s, 8 * s, fill) }
            val labelColor = if (dim) Color.argb(150, 255, 255, 255) else Color.WHITE
            textC(labelColor, 25 * s, latoBold, shadow = !sel)
            c.drawText(p.label, lx, y(55f), text)
            if (isAuto(p)) drawABadge(c, lx + labelW + 11 * s, y(37f), 20 * s, outlined = sel)
            textC(if (dim) Color.argb(150, 255, 255, 255) else Color.WHITE, 43 * s, latoBold, shadow = !sel)
            c.drawText(value(p), lx, y(97f), text)
            if (!dim) readoutHit[p] = RectF(box.left - 6 * s, y(14f), box.right + 6 * s, y(118f))
        }
    }

    private fun drawABadge(c: Canvas, left: Float, top: Float, size: Float, outlined: Boolean = false) {
        val r = RectF(left, top, left + size, top + size)
        if (outlined) { strokeC(Color.WHITE, 1.8f * s); c.drawRoundRect(r, size * 0.16f, size * 0.16f, stroke) }
        else { fillC(accent); c.drawRoundRect(r, size * 0.16f, size * 0.16f, fill) }
        textC(Color.WHITE, size * 0.8f, latoBlack)
        c.drawText("A", left + (size - text.measureText("A")) / 2, top + size * 0.79f, text)
    }

    private fun timecode(): String {
        if (!recorder.isRecording) return "00:00:00:00"
        if (recorder.current?.timelapse == true) {        // timelapse: the clip's length so far, not wall time
            val f = recorder.framesWritten; val fps = engine.fps.coerceAtLeast(1)
            val sec = f / fps
            return "%02d:%02d:%02d:%02d".format(sec / 3600, (sec / 60) % 60, sec % 60, f % fps)
        }
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
        val mode = when {
            engine.offSpeed -> "OFF SPEED  ${engine.offSpeedFps} fps  \u2192  ${engine.fps}"
            prefs.timelapse -> "TIMELAPSE  1 frame every ${intervalLabel(prefs.timelapseSecs)}"
            else -> null
        }
        mode?.let { textC(accent, 19 * s, latoBold, shadow = true); centered(c, it, x(927f), y(120f)) }
    }

    private fun intervalLabel(secs: Int) = if (secs >= 60 && secs % 60 == 0) "${secs / 60} min" else "${secs}s"

    private var badgeRect = RectF()

    private fun drawFormatBadge(c: Canvas) {
        val f = engine.format
        val r = RectF(x(1636f), y(34f), x(1710f), y(98f))
        val rad = 9 * s
        val split = y(69f)
        fillC(Color.BLACK); c.drawRoundRect(r, rad, rad, fill)
        c.save(); c.clipRect(r.left, r.top, r.right, split)
        fillC(Color.WHITE); c.drawRoundRect(r, rad, rad, fill)
        c.restore()
        strokeC(Color.WHITE, 2.5f * s); c.drawRoundRect(r, rad, rad, stroke)
        textC(Color.BLACK, (if (f.badge.length > 2) 22 else 27) * s, latoBlack)
        centered(c, f.badge, r.centerX(), y(63f))
        textC(Color.WHITE, 17 * s, latoBold)
        centered(c, f.aspectName, r.centerX(), y(91f))
        badgeRect = RectF(r.left - 20 * s, r.top - 15 * s, r.right + 20 * s, r.bottom + 15 * s)
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
        val my = frame.centerY()
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

    /** Left handle: Presets and Slate buttons, like the Blackmagic app's. */
    private fun drawSideMenu(c: Canvas) {
        val cy = frame.centerY()
        for ((i, which) in listOf("presets", "slate").withIndex()) {
            val r = RectF(x(62f), cy + (i * 2 - 1) * 58 * s - 40 * s, x(142f), cy + (i * 2 - 1) * 58 * s + 40 * s)
            val on = which == "presets" && presetsPanel
            fillC(if (on) accent else Color.argb(150, 0, 0, 0)); c.drawRoundRect(r, 16 * s, 16 * s, fill)
            if (which == "presets") drawPresetsIcon(c, r.centerX(), r.centerY()) else drawSlateIcon(c, r.centerX(), r.centerY())
            buttons += Btn(r) {
                if (which == "presets") { val open = !presetsPanel; collapse(); presetsPanel = open; sideMenu = true }
                else { sideMenu = false; onSettings("slate") }
                invalidate()
            }
        }
    }

    private fun drawPresetsIcon(c: Canvas, cx: Float, cy: Float) {
        strokeC(Color.WHITE, 3 * s)
        c.drawRoundRect(RectF(cx - 15 * s, cy - 22 * s, cx + 15 * s, cy + 22 * s), 6 * s, 6 * s, stroke)
        for (i in -1..1) { c.drawLine(cx - 8 * s, cy + i * 10 * s, cx + 2 * s, cy + i * 10 * s, stroke); fillC(Color.WHITE); c.drawCircle(cx + 8 * s, cy + i * 10 * s, 3 * s, fill) }
    }

    private fun drawSlateIcon(c: Canvas, cx: Float, cy: Float) {
        strokeC(Color.WHITE, 3 * s)
        c.drawRoundRect(RectF(cx - 20 * s, cy - 6 * s, cx + 20 * s, cy + 20 * s), 3 * s, 3 * s, stroke)
        path.reset(); path.moveTo(cx - 20 * s, cy - 8 * s); path.lineTo(cx + 18 * s, cy - 20 * s); c.drawPath(path, stroke)
        for (i in 0..2) c.drawLine(cx - 12 * s + i * 11 * s, cy - 10 * s - i * 3.5f * s, cx - 6 * s + i * 11 * s, cy - 6 * s - i * 3.5f * s, stroke)
    }

    // ---- function buttons (right handle)
    private val fnRects = arrayOfNulls<RectF>(3)

    private fun drawFunctionButtons(c: Canvas) {
        val cy = frame.centerY()
        for (i in 0..2) {
            val r = RectF(x(1718f), cy + (i - 1) * 104 * s - 42 * s, x(1792f), cy + (i - 1) * 104 * s + 42 * s)
            val action = prefs.fn(i)
            val on = fnActive(action)
            fillC(if (on) accent else Color.argb(150, 0, 0, 0)); c.drawRoundRect(r, 14 * s, 14 * s, fill)
            strokeC(Color.argb(170, 255, 255, 255), 2 * s); c.drawRoundRect(r, 14 * s, 14 * s, stroke)
            textC(Color.WHITE, 24 * s, latoBlack); centered(c, "F${i + 1}", r.centerX(), r.centerY() + 2 * s)
            textC(Color.argb(220, 255, 255, 255), 13 * s, latoBold); centered(c, FN_SHORT[action] ?: "", r.centerX(), r.centerY() + 24 * s)
            fnRects[i] = r
        }
    }

    private fun fnActive(a: String): Boolean = when (a) {
        "RECORD" -> recorder.isRecording; "TORCH" -> engine.torch; "FOCUS" -> aids.focusAssist; "ZEBRA" -> aids.zebra != null
        "FALSE" -> aids.falseColor; "FACE" -> engine.faceDetect; "LUT" -> prefs.lut != null; "GUIDES" -> frameGuide != null
        "GRID" -> grid != Grid.OFF; "STAB" -> engine.stabilization; "AE" -> autoExposure; "AELOCK" -> engine.aeLock
        "AWBLOCK" -> engine.awbLock; "GOOD" -> slate.goodTake; "CLEAN" -> cleanFeed; else -> false
    }

    /** Run a function-button action (also used by nothing else, so all toggles live here). */
    private fun runFn(a: String) {
        when (a) {
            "RECORD" -> onRecordToggle()
            "TORCH" -> { engine.torch = !engine.torch; changed() }
            "FOCUS" -> setFocusAssist(!aids.focusAssist, aids.peakStyle)
            "ZEBRA" -> setZebra(if (aids.zebra == null) prefs.str("last_zebra", "95") else null)
            "FALSE" -> aids.falseColor = !aids.falseColor
            "FACE" -> engine.setFaceDetect(!engine.faceDetect)
            "LUT" -> if (prefs.lut != null) onLutChanged(null) else onLutChanged(prefs.str("last_lut", Luts.BUILT_IN.first()))
            "GUIDES" -> frameGuide = if (frameGuide == null) "2.39:1" else null
            "GRID" -> grid = if (grid == Grid.OFF) Grid.THIRDS else Grid.OFF
            "STAB" -> { engine.stabilization = !engine.stabilization; changed() }
            "AE" -> toggleAuto(P.EV)
            "AELOCK" -> { engine.aeLock = !engine.aeLock; if (engine.aeLock) { engine.isoAuto = true; engine.shutterAuto = true }; changed() }
            "AWBLOCK" -> { engine.awbLock = !engine.awbLock; if (engine.awbLock) engine.wbAuto = true; changed() }
            "FLIP" -> onTool(Tool.FLIP)
            "GOOD" -> onGoodTake(!slate.goodTake)
            "CLEAN" -> cleanFeed = !cleanFeed
        }
        invalidate()
    }

    /** A high-speed take records the encoder only (see CameraEngine.createSession): say why the picture froze. */
    private fun drawHighSpeedNotice(c: Canvas) {
        val r = RectF(x(560f), y(440f), x(1294f), y(600f))
        fillC(Color.argb(200, 0, 0, 0)); c.drawRoundRect(r, 16 * s, 16 * s, fill)
        textC(red, 34 * s, latoBlack); centered(c, "●  SLOW MOTION ${engine.offSpeedFps} fps", r.centerX(), r.top + 62 * s)
        textC(Color.WHITE, 21 * s, lato); centered(c, "The picture pauses while a ${engine.offSpeedFps} fps take records", r.centerX(), r.top + 110 * s)
    }

    /** Face boxes: corner brackets, the face that drives autofocus in yellow. */
    private fun drawFaces(c: Canvas) {
        if (!engine.faceDetect) return
        for ((i, f) in engine.faces.take(5).withIndex()) {
            val r = RectF(pv.left + f.left * pv.width(), pv.top + f.top * pv.height(), pv.left + f.right * pv.width(), pv.top + f.bottom * pv.height())
            strokeC(if (i == 0) Color.rgb(245, 197, 24) else Color.argb(200, 255, 255, 255), 3 * s)
            val k = minOf(r.width(), r.height()) * 0.25f
            path.reset()
            path.moveTo(r.left, r.top + k); path.lineTo(r.left, r.top); path.lineTo(r.left + k, r.top)
            path.moveTo(r.right - k, r.top); path.lineTo(r.right, r.top); path.lineTo(r.right, r.top + k)
            path.moveTo(r.right, r.bottom - k); path.lineTo(r.right, r.bottom); path.lineTo(r.right - k, r.bottom)
            path.moveTo(r.left + k, r.bottom); path.lineTo(r.left, r.bottom); path.lineTo(r.left, r.bottom - k)
            c.drawPath(path, stroke)
        }
    }

    /** False colour key above the bottom panels. */
    private fun drawFalseColourKey(c: Canvas) {
        val keys = MonitorAids.FALSE_KEY
        val w = 150 * s
        val left = x(927f) - w * keys.size / 2
        val r0 = RectF(left - 10 * s, y(812f), left + w * keys.size + 10 * s, y(856f))
        fillC(Color.argb(140, 0, 0, 0)); c.drawRoundRect(r0, 10 * s, 10 * s, fill)
        for ((i, k) in keys.withIndex()) {
            val (label, rgb) = k
            fillC(Color.rgb((rgb shr 16) and 0xFF, (rgb shr 8) and 0xFF, rgb and 0xFF))
            c.drawRoundRect(RectF(left + i * w + 6 * s, y(824f), left + i * w + 26 * s, y(844f)), 4 * s, 4 * s, fill)
            textC(Color.WHITE, 16 * s, latoBold); c.drawText(label, left + i * w + 32 * s, y(840f), text)
        }
    }

    // ---- LUT picker (tool column LUT button): live thumbnails of each look
    private var lutScroll = 0f
    private var lutCards = ArrayList<Pair<RectF, String?>>()
    private val thumbs = HashMap<String, android.graphics.Bitmap>()
    private var thumbsAt = 0L

    private fun refreshThumbs(names: List<String>) {
        if (now() - thumbsAt < 1500 && thumbs.size == names.size + 1) return
        val src = smallFrame() ?: return
        thumbsAt = now()
        thumbs["\u0000off"] = android.graphics.Bitmap.createBitmap(src, 160, 90, android.graphics.Bitmap.Config.ARGB_8888)
        for (n in names) {
            val lut = Luts.load(context, n) ?: continue
            val px = IntArray(src.size) { lut.apply(src[it]) }
            thumbs[n] = android.graphics.Bitmap.createBitmap(px, 160, 90, android.graphics.Bitmap.Config.ARGB_8888)
        }
    }

    private fun drawLutPanel(c: Canvas) {
        editorRect = panel(c, 27f, 1828f, 790f, 1017f, alpha = 170)
        val names = Luts.names(context)
        refreshThumbs(names)
        lutCards.clear()
        val cw = 220 * s
        val total = cw * (names.size + 1)
        val view = x(1811f) - x(44f)
        lutScroll = lutScroll.coerceIn(minOf(0f, view - total), 0f)
        c.save(); c.clipRect(x(44f), y(790f), x(1811f), y(1017f))
        for ((i, n) in (listOf<String?>(null) + names).withIndex()) {
            val l = x(44f) + lutScroll + i * cw
            if (l > x(1811f) || l + cw < x(44f)) continue
            val th = RectF(l + 12 * s, y(808f), l + cw - 12 * s, y(808f) + (cw - 24 * s) * 9 / 16)
            val on = (n == null && prefs.lut == null) || (n != null && n == prefs.lut)
            val bmp = thumbs[n ?: "\u0000off"]
            if (bmp != null) c.drawBitmap(bmp, null, th, fill.apply { color = Color.WHITE; isFilterBitmap = true })
            else { fillC(Color.argb(80, 255, 255, 255)); c.drawRect(th, fill) }
            strokeC(if (on) accent else Color.argb(90, 255, 255, 255), (if (on) 5f else 2f) * s); c.drawRect(th, stroke)
            textC(if (on) accent else Color.WHITE, 19 * s, latoBold)
            centered(c, n ?: "OFF", th.centerX(), th.bottom + 30 * s)
            lutCards += RectF(l, y(800f), l + cw, y(1010f)) to n
        }
        c.restore()
    }

    // ---- presets (left handle)
    private fun drawPresetsPanel(c: Canvas) {
        editorRect = panel(c, 27f, 1828f, 790f, 1017f, alpha = 170)
        textC(accent, 17 * s, latoBold); centered(c, "PRESETS", x(927f), y(815f))
        val names = presets.names()
        if (selectedPreset >= names.size) selectedPreset = -1
        if (names.isEmpty()) {
            textC(Color.argb(210, 255, 255, 255), 22 * s, lato)
            centered(c, "No presets yet. Save the current settings with  +", x(927f), y(862f))
        } else {
            presetRow(c, names.mapIndexed { i, n -> Item(n, i == selectedPreset) { selectedPreset = if (selectedPreset == i) -1 else i } },
                x(44f), minOf(x(1811f), x(44f) + names.size * 260 * s), 830f, 885f)
        }
        val sel = selectedPreset
        val acts = listOf<Triple<String, Boolean, () -> Unit>>(
            Triple("+  Save new", true) { presets.add("Preset ${names.size + 1}", engine, aids); selectedPreset = presets.names().size - 1 },
            Triple("Apply", sel >= 0) { presets.apply(sel, engine, aids, onLutChanged, onGainChanged); onParamChanged(null) },
            Triple("Update", sel >= 0) { presets.update(sel, engine, aids) },
            Triple("Rename", sel >= 0) { renamePreset(sel) },
            Triple("Delete", sel >= 0) { deletePreset(sel) },
        )
        val w = (x(1811f) - x(44f)) / acts.size
        for ((i, a) in acts.withIndex()) {
            val r = RectF(x(44f) + i * w + 10 * s, y(925f), x(44f) + (i + 1) * w - 10 * s, y(985f))
            val (label, enabled, act) = a
            strokeC(Color.argb(if (enabled) 200 else 60, 255, 255, 255), 2.5f * s); c.drawRoundRect(r, r.height() / 2, r.height() / 2, stroke)
            textC(Color.argb(if (enabled) 255 else 90, 255, 255, 255), 22 * s, latoBold); centered(c, label, r.centerX(), r.centerY() + 8 * s)
            if (enabled) buttons += Btn(r) { act(); invalidate() }
        }
    }

    private fun renamePreset(i: Int) {
        val input = android.widget.EditText(context).apply { setText(presets.names().getOrNull(i) ?: ""); setSingleLine() }
        android.app.AlertDialog.Builder(context, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle("Rename preset").setView(input)
            .setPositiveButton("Save") { _, _ -> input.text.toString().trim().takeIf { it.isNotEmpty() }?.let { presets.rename(i, it); invalidate() } }
            .setNegativeButton("Cancel", null).show()
    }

    private fun deletePreset(i: Int) {
        android.app.AlertDialog.Builder(context, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle("Delete \"${presets.names().getOrNull(i)}\"?")
            .setPositiveButton("Delete") { _, _ -> presets.delete(i); selectedPreset = -1; invalidate() }
            .setNegativeButton("Cancel", null).show()
    }

    private fun panel(c: Canvas, rx0: Float, rx1: Float, ry0: Float = 870f, ry1: Float = 1017f, alpha: Int = 115): RectF {
        val r = RectF(x(rx0), y(ry0), x(rx1), y(ry1))
        fillC(Color.argb(alpha, 0, 0, 0)); c.drawRoundRect(r, 14 * s, 14 * s, fill)
        return r
    }

    private fun panelTitle(c: Canvas, title: String, rx0: Float, rx1: Float, ry: Float = 870f) {
        textC(Color.WHITE, 19 * s, lato)
        c.drawText(title, x(rx0 + 17f), y(ry + 23f), text)
        strokeC(Color.argb(70, 255, 255, 255), 1.5f * s)
        c.drawLine(x(rx0 + 17f), y(ry + 33f), x(rx1 - 16f), y(ry + 33f), stroke)
    }

    private var histRect = RectF(); private var mediaRect = RectF(); private var audioRect = RectF()

    private fun drawHistogramPanel(c: Canvas) {
        histRect = panel(c, 27f, 393f)
        panelTitle(c, "Rec.709", 27f, 393f)
        val l = x(44f); val r = x(350f); val t = y(914f); val b = y(1000f)
        histogram()?.let { h ->
            val colors = intArrayOf(Color.argb(175, 235, 40, 40), Color.argb(175, 40, 215, 60), Color.argb(175, 50, 80, 255))
            for (ch in 0..2) drawHistCurve(c, h[ch], l, r, t, b, colors[ch])
        }
        strokeC(Color.argb(200, 255, 255, 255), 2 * s)
        c.drawLine(l, t, l, b, stroke); c.drawLine(l, b, r, b, stroke)
        strokeC(Color.argb(190, 255, 255, 255), 1.5f * s)
        val cl = RectF(x(364f), y(920f), x(376f), y(1000f))
        c.drawRect(cl, stroke)
        for (i in 1..2) c.drawLine(cl.left, cl.top + cl.height() * i / 3, cl.right, cl.top + cl.height() * i / 3, stroke)
    }

    private fun drawHistCurve(c: Canvas, bins: FloatArray, l: Float, r: Float, t: Float, b: Float, color: Int) {
        path.reset(); path.moveTo(l, b)
        for (i in bins.indices) path.lineTo(l + (r - l) * i / (bins.size - 1), b - bins[i] * (b - t) * 0.95f)
        path.lineTo(r, b); path.close()
        fillC(color); c.drawPath(path, fill)
    }

    /** Tap the histogram: an RGB parade (one channel per row) with legal-range marks. */
    private fun drawParade(c: Canvas) {
        histRect = panel(c, 27f, 1828f, 640f, 1017f, alpha = 200)
        panelTitle(c, "Rec.709 RGB parade", 27f, 1828f, 640f)
        val l = x(60f); val r = x(1795f)
        val rows = floatArrayOf(690f, 795f, 900f)
        val colors = intArrayOf(Color.argb(220, 235, 40, 40), Color.argb(220, 40, 215, 60), Color.argb(220, 60, 90, 255))
        val h = histogram()
        for (i in 0..2) {
            val t = y(rows[i]); val b = y(rows[i] + 95f)
            h?.let { drawHistCurve(c, it[i], l, r, t, b, colors[i]) }
            strokeC(Color.argb(colors[i].ushr(24), Color.red(colors[i]), Color.green(colors[i]), Color.blue(colors[i])), 1.5f * s)
            c.drawLine(l, b, r, b, stroke)
        }
        // 10-bit legal range marks: 64 and 940 of 1024
        strokeC(Color.argb(170, 240, 170, 60), 1.5f * s)
        textC(Color.argb(200, 240, 170, 60), 17 * s, lato)
        for ((v, lbl) in listOf(0 to "0", 64 to "64", 940 to "940", 1024 to "1024")) {
            val mx = l + (r - l) * v / 1024f
            c.drawLine(mx, y(690f), mx, y(995f), stroke)
            centered(c, lbl, mx, y(1012f))
        }
    }

    private var storage = Triple(0L, 0L, 0L)
    private var storageAt = 0L

    private fun drawMediaPanel(c: Canvas) {
        mediaRect = panel(c, 743f, 1110f)
        if (now() - storageAt > 2000) { storage = runCatching { recorder.storage(plannedBitrate()) }.getOrDefault(storage); storageAt = now() }
        val (total, free, runtime) = storage
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
        audioRect = panel(c, 1462f, 1828f)
        panelTitle(c, "Internal", 1462f, 1828f)
        drawMeters(c, x(1497f), x(1805f), 918f, 23f, 30f, 19f, 998f, x(1478f))
    }

    /** Two channel bars with zones, level, peak hold and the dB scale under them. */
    private fun drawMeters(c: Canvas, bl: Float, br: Float, top0: Float, barH: Float, pitch: Float, scaleSize: Float, scaleY: Float, numX: Float) {
        val bw = br - bl
        val a = audio
        val dim = intArrayOf(Color.rgb(26, 64, 25), Color.rgb(75, 75, 41), Color.rgb(75, 25, 32))
        val lit = intArrayOf(Color.rgb(64, 205, 64), Color.rgb(224, 214, 54), Color.rgb(235, 52, 52))
        val zones = floatArrayOf(0f, dbFrac(-10f), dbFrac(0f), 1f)
        for (ch in 0..1) {
            val top = y(top0 + ch * pitch); val bot = top + barH * s
            textC(Color.WHITE, scaleSize * 1.05f * s, latoBold)
            c.drawText("${ch + 1}", numX, bot - (barH * 0.18f) * s, text)
            val lvl = dbFrac(a?.level?.get(ch) ?: AudioInput.SILENCE)
            for (z in 0..2) {
                val z0 = bl + bw * zones[z]; val z1 = bl + bw * zones[z + 1]
                fillC(dim[z]); c.drawRect(z0, top, z1, bot, fill)
                val litTo = bl + bw * lvl
                if (litTo > z0) { fillC(lit[z]); c.drawRect(z0, top, minOf(z1, litTo), bot, fill) }
            }
            a?.peak?.get(ch)?.let { pk ->
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
        textC(Color.WHITE, scaleSize * s, latoBold)
        for ((db, _) in METER_SCALE) centered(c, "${db.toInt()}", bl + bw * dbFrac(db), y(scaleY))
    }

    /** Tap the audio meter: big meters plus the Audio Gain slider. */
    private fun drawAudioExpanded(c: Canvas) {
        audioRect = panel(c, 420f, 1435f, 640f, 1017f, alpha = 200)
        textC(Color.WHITE, 24 * s, latoBold)
        centered(c, "Internal", x(927f), y(678f))
        strokeC(Color.argb(70, 255, 255, 255), 1.5f * s)
        c.drawLine(x(440f), y(695f), x(1415f), y(695f), stroke)
        drawMeters(c, x(500f), x(1395f), 730f, 34f, 44f, 22f, 838f, x(462f))
        c.drawLine(x(440f), y(865f), x(1415f), y(865f), strokeC(Color.argb(70, 255, 255, 255), 1.5f * s))
        textC(Color.WHITE, 19 * s, latoBold)
        c.drawText("AUDIO GAIN", x(470f), y(905f), text)
        val gain = prefs.audioGain
        val track = RectF(x(470f), y(950f), x(1270f), y(956f))
        fillC(Color.rgb(90, 98, 110)); c.drawRoundRect(track, 3 * s, 3 * s, fill)
        val kx = track.left + track.width() * gain / 100f
        fillC(accent); c.drawRoundRect(RectF(track.left, track.top, kx, track.bottom), 3 * s, 3 * s, fill)
        fillC(Color.WHITE); c.drawCircle(kx, track.centerY(), 17 * s, fill)
        textC(Color.WHITE, 28 * s, lato)
        c.drawText("$gain%", x(1305f), y(963f), text)
        sliders += Slider(RectF(track.left - 25 * s, track.top - 40 * s, track.right + 25 * s, track.bottom + 40 * s), track) { pct ->
            onGainChanged(pct); invalidate()
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

    // ---- editor panel (replaces the bottom panels while a parameter is edited)
    private class Btn(val rect: RectF, val act: () -> Unit)
    private class RulerHit(val rect: RectF, val items: List<Item>, val cur: Int, val cx: Float, val spacing: Float)
    private class Slider(val hit: RectF, val track: RectF, val set: (Int) -> Unit)
    private val buttons = ArrayList<Btn>()
    private val rulers = ArrayList<RulerHit>()
    private val sliders = ArrayList<Slider>()

    private fun drawEditor(c: Canvas, p: P) {
        val twoRow = p == P.WB || p == P.LENS || p == P.ISO
        val top = if (twoRow && p != P.ISO) 790f else 890f
        val panelR = panel(c, 27f, 1828f, top, 1017f, alpha = 170)
        var left = x(44f)
        var right = x(1811f)
        // AUTO chip on the left
        if (p.autoCapable) {
            val r = RectF(left, y(925f), left + 120 * s, y(982f))
            chip(c, r, "AUTO", isAuto(p))
            buttons += Btn(r) { toggleAuto(p) }
            left = r.right + 26 * s
        }
        // lock on the right (auto exposure / auto white balance hold)
        if (p == P.SHUTTER || p == P.WB || p == P.TINT) {
            val locked = if (p == P.SHUTTER) engine.aeLock else engine.awbLock
            val r = RectF(right - 70 * s, y(918f), right, y(988f))
            fillC(if (locked) accent else Color.argb(60, 255, 255, 255)); c.drawRoundRect(r, 12 * s, 12 * s, fill)
            drawPadlock(c, r.centerX(), r.centerY(), s, locked)
            buttons += Btn(r) {
                if (p == P.SHUTTER) { engine.aeLock = !engine.aeLock; if (engine.aeLock) { engine.isoAuto = true; engine.shutterAuto = true } }
                else { engine.awbLock = !engine.awbLock; if (engine.awbLock) engine.wbAuto = true }
                changed()
            }
            right = r.left - 26 * s
        }
        if (p == P.FPS) {
            // TIME LAPSE on the left, OFF SPEED on the right, like the Blackmagic app's frame-rate panel
            val tl = RectF(left, y(925f), left + 190 * s, y(982f))
            chip(c, tl, "TIME LAPSE", prefs.timelapse)
            buttons += Btn(tl) { if (!recorder.isRecording) { prefs.timelapse = !prefs.timelapse; if (prefs.timelapse) engine.setOffSpeed(false); onParamChanged(null) } }
            val os = RectF(right - 190 * s, y(925f), right, y(982f))
            chip(c, os, "OFF SPEED", engine.offSpeed)
            buttons += Btn(os) { if (!recorder.isRecording) { val on = !engine.offSpeed; if (on) prefs.timelapse = false; engine.setOffSpeed(on); onParamChanged(null) } }
            left = tl.right + 26 * s; right = os.left - 26 * s
        }
        when (p) {
            P.FPS -> when {
                engine.offSpeed -> engine.offSpeedChoices.let { ch ->
                    drawRulerRow(c, ch.map { f -> Item("$f", false) { engine.setOffSpeed(true, f) } }, ch.indexOf(engine.offSpeedFps).coerceAtLeast(0), left, right, "OFF SPEED FPS") }
                prefs.timelapse -> TIMELAPSE_SECS.let { ch ->
                    drawRulerRow(c, ch.map { sec -> Item(intervalLabel(sec), false) { prefs.timelapseSecs = sec } },
                        ch.indexOf(prefs.timelapseSecs).coerceAtLeast(0), left, right, "1 FRAME EVERY") }
                else -> ruler(p)?.let { (items, cur) -> drawRulerRow(c, items, cur, left, right, "PROJECT FPS") }
            }
            P.ISO -> {
                // preset buttons at full stops, like the Blackmagic app
                val stops = listOf(50, 100, 200, 400, 800, 1600, 3200, 6400, 12800).filter { it in engine.isoRange.lower..engine.isoRange.upper }
                val cur = value(p).toIntOrNull()
                presetRow(c, stops.map { v -> Item("$v", !engine.isoAuto && cur == v) { engine.isoAuto = false; engine.iso = v } }, left, right, 925f, 982f)
            }
            P.WB -> {
                val k = if (engine.wbAuto) -1 else engine.kelvin
                iconRow(c, WB_PRESETS.map { (name, kelvin) -> Triple(name, k == kelvin) { engine.wbAuto = false; engine.awbLock = false; engine.kelvin = kelvin } },
                    x(44f) + 146 * s, x(1811f) - 96 * s, 805f, 870f)
                ruler(p)?.let { (items, cur) -> drawRulerRow(c, items, cur, left, right, p.label) }
            }
            P.LENS -> {
                lensRow(c, x(44f), x(1811f), 805f, 870f)
                ruler(p)?.let { (items, cur) -> drawRulerRow(c, items, cur, left, right, "ZOOM") }
            }
            else -> ruler(p)?.let { (items, cur) -> drawRulerRow(c, items, cur, left, right, p.label) }
        }
        editorRect = panelR
    }
    private var editorRect = RectF()

    private fun chip(c: Canvas, r: RectF, label: String, on: Boolean) {
        if (on) { fillC(accent); c.drawRoundRect(r, r.height() / 2, r.height() / 2, fill) }
        else { strokeC(Color.argb(170, 255, 255, 255), 2.5f * s); c.drawRoundRect(r, r.height() / 2, r.height() / 2, stroke) }
        textC(Color.WHITE, 22 * s, latoBlack)
        centered(c, label, r.centerX(), r.centerY() + 8 * s)
    }

    private fun drawRulerRow(c: Canvas, items: List<Item>, cur: Int, left: Float, right: Float, title: String) {
        val spacing = 130 * s
        val cx = (left + right) / 2
        textC(accent, 17 * s, latoBold)
        centered(c, title, cx, y(915f))
        c.save(); c.clipRect(left, y(890f), right, y(1017f))
        for (i in items.indices) {
            val ix = cx + (i - cur) * spacing
            if (ix < left - spacing || ix > right + spacing) continue
            val on = i == cur
            if (on) textC(Color.WHITE, 36 * s, latoBlack) else textC(Color.argb(150, 255, 255, 255), 25 * s, latoBold)
            centered(c, items[i].label, ix, y(970f))
            strokeC(Color.argb(if (on) 255 else 110, 255, 255, 255), 2 * s)
            c.drawLine(ix, y(990f), ix, y(1000f), stroke)
        }
        c.restore()
        fillC(accent)
        path.reset(); path.moveTo(cx - 9 * s, y(1010f)); path.lineTo(cx + 9 * s, y(1010f)); path.lineTo(cx, y(999f)); path.close()
        c.drawPath(path, fill)
        rulers += RulerHit(RectF(left, y(890f), right, y(1017f)), items, cur, cx, spacing)
    }

    private fun presetRow(c: Canvas, items: List<Item>, left: Float, right: Float, ry0: Float, ry1: Float) {
        if (items.isEmpty()) return
        val w = (right - left) / items.size
        for ((i, it) in items.withIndex()) {
            val r = RectF(left + i * w + 6 * s, y(ry0), left + (i + 1) * w - 6 * s, y(ry1))
            if (it.on) { fillC(accent); c.drawRoundRect(r, 10 * s, 10 * s, fill) }
            textC(Color.WHITE, 28 * s, if (it.on) latoBlack else latoBold)
            centered(c, it.label, r.centerX(), r.centerY() + 10 * s)
            buttons += Btn(r) { it.act(); changed() }
        }
    }

    private fun iconRow(c: Canvas, items: List<Triple<String, Boolean, () -> Unit>>, left: Float, right: Float, ry0: Float, ry1: Float) {
        val w = (right - left) / items.size
        for ((i, it) in items.withIndex()) {
            val (name, on, act) = it
            val r = RectF(left + i * w + 10 * s, y(ry0), left + (i + 1) * w - 10 * s, y(ry1))
            if (on) { fillC(accent); c.drawRoundRect(r, 10 * s, 10 * s, fill) }
            drawWbIcon(c, name, r.centerX(), r.centerY(), s)
            buttons += Btn(r) { act(); changed() }
        }
    }

    private fun lensRow(c: Canvas, left: Float, right: Float, ry0: Float, ry1: Float) {
        val lenses = engine.lenses.filter { it.facing == CameraCharacteristics.LENS_FACING_BACK }.sortedBy { it.equivMm } +
            engine.lenses.filter { it.facing == CameraCharacteristics.LENS_FACING_FRONT }.take(1)
        val w = minOf(180 * s, (right - left) / lenses.size)
        val start = (left + right) / 2 - w * lenses.size / 2
        for ((i, l) in lenses.withIndex()) {
            val r = RectF(start + i * w + 8 * s, y(ry0), start + (i + 1) * w - 8 * s, y(ry1))
            val on = l.id == engine.lens.id
            if (on) { fillC(accent); c.drawRoundRect(r, 10 * s, 10 * s, fill) }
            val front = l.facing == CameraCharacteristics.LENS_FACING_FRONT
            textC(Color.WHITE, 24 * s, latoBlack)
            centered(c, if (front) "Front" else fmtX(l.zoomVsMain), r.centerX(), r.centerY() - 2 * s)
            textC(Color.argb(200, 255, 255, 255), 17 * s, latoBold)
            centered(c, "${l.equivMm}mm", r.centerX(), r.centerY() + 22 * s)
            buttons += Btn(r) { engine.switchLens(l.id); onParamChanged(null); invalidate() }
        }
    }

    private fun drawPadlock(c: Canvas, cx: Float, cy: Float, k: Float, locked: Boolean) {
        strokeC(Color.WHITE, 3.5f * k)
        val body = RectF(cx - 14 * k, cy - 3 * k, cx + 14 * k, cy + 19 * k)
        fillC(Color.WHITE); c.drawRoundRect(body, 4 * k, 4 * k, fill)
        val shackle = RectF(cx - 9 * k, cy - 19 * k, cx + 9 * k, cy + 1 * k)
        if (locked) c.drawArc(shackle, 180f, 180f, false, stroke)
        else c.drawArc(RectF(shackle.left + 9 * k, shackle.top - 3 * k, shackle.right + 9 * k, shackle.bottom - 3 * k), 180f, 160f, false, stroke)
        c.drawLine(shackle.left + (if (locked) 0f else 9 * k), cy - 9 * k + (if (locked) 0f else -3 * k), shackle.left + (if (locked) 0f else 9 * k), cy - 3 * k, stroke)
        if (locked) c.drawLine(shackle.right, cy - 9 * k, shackle.right, cy - 3 * k, stroke)
    }

    private fun drawWbIcon(c: Canvas, name: String, cx: Float, cy: Float, k: Float) {
        strokeC(Color.WHITE, 3 * k)
        when (name) {
            "Cloudy" -> {
                path.reset()
                path.addCircle(cx - 9 * k, cy + 2 * k, 10 * k, Path.Direction.CW)
                path.addCircle(cx + 5 * k, cy - 4 * k, 13 * k, Path.Direction.CW)
                path.addCircle(cx + 16 * k, cy + 4 * k, 8 * k, Path.Direction.CW)
                c.save(); c.clipRect(cx - 30 * k, cy - 30 * k, cx + 30 * k, cy + 12 * k)
                c.drawPath(path, stroke); c.restore()
                c.drawLine(cx - 19 * k, cy + 12 * k, cx + 24 * k, cy + 12 * k, stroke)
            }
            "Shade" -> {
                path.reset()
                path.moveTo(cx - 16 * k, cy + 16 * k); path.lineTo(cx - 16 * k, cy - 2 * k); path.lineTo(cx, cy - 16 * k)
                path.lineTo(cx + 16 * k, cy - 2 * k); path.lineTo(cx + 16 * k, cy + 16 * k); path.close()
                c.drawPath(path, stroke)
                for (i in 0..2) c.drawLine(cx + 20 * k + i * 5 * k, cy + 16 * k, cx + 28 * k + i * 5 * k, cy + 4 * k, stroke)
            }
            "Fluorescent" -> {
                c.drawRoundRect(RectF(cx - 22 * k, cy - 5 * k, cx + 22 * k, cy + 5 * k), 5 * k, 5 * k, stroke)
                for (i in -1..1) c.drawLine(cx + i * 12 * k, cy - 11 * k, cx + i * 12 * k, cy - 17 * k, stroke)
                for (i in -1..1) c.drawLine(cx + i * 12 * k, cy + 11 * k, cx + i * 12 * k, cy + 17 * k, stroke)
            }
            "Tungsten" -> {
                c.drawCircle(cx, cy - 4 * k, 11 * k, stroke)
                c.drawLine(cx - 5 * k, cy + 11 * k, cx + 5 * k, cy + 11 * k, stroke)
                c.drawLine(cx - 4 * k, cy + 16 * k, cx + 4 * k, cy + 16 * k, stroke)
                for (i in 0 until 5) { val a = Math.PI * (1.1 + i * 0.2); c.drawLine(cx + (16 * k * cos(a)).toFloat(), cy - 4 * k + (16 * k * sin(a)).toFloat(), cx + (21 * k * cos(a)).toFloat(), cy - 4 * k + (21 * k * sin(a)).toFloat(), stroke) }
            }
            else -> {   // Daylight
                c.drawCircle(cx, cy, 9 * k, stroke)
                for (i in 0 until 8) { val a = Math.PI * 2 * i / 8; c.drawLine(cx + (14 * k * cos(a)).toFloat(), cy + (14 * k * sin(a)).toFloat(), cx + (19 * k * cos(a)).toFloat(), cy + (19 * k * sin(a)).toFloat(), stroke) }
            }
        }
    }

    // ---- guides toolbar (tool column guides button): torch, focus assist, safe area, frame guides, grid, zebra
    private fun drawGuidesBar(c: Canvas) {
        editorRect = panel(c, 27f, 1828f, 790f, 1017f, alpha = 170)
        val tools = listOfNotNull(if (engine.hasTorch) GuideTool.TORCH else null, GuideTool.FOCUS, GuideTool.FALSE, GuideTool.SAFE,
            GuideTool.FRAME, GuideTool.GRID, if (engine.faceDetectSupported) GuideTool.FACE else null, GuideTool.ZEBRA)
        val w = 110 * s
        val start = x(927f) - w * tools.size / 2
        for ((i, t) in tools.withIndex()) {
            val r = RectF(start + i * w + 10 * s, y(800f), start + (i + 1) * w - 10 * s, y(870f))
            val active = when (t) {
                GuideTool.TORCH -> engine.torch; GuideTool.FOCUS -> aids.focusAssist; GuideTool.SAFE -> safeArea > 0
                GuideTool.FALSE -> aids.falseColor; GuideTool.FACE -> engine.faceDetect
                GuideTool.FRAME -> frameGuide != null; GuideTool.GRID -> grid != Grid.OFF; GuideTool.ZEBRA -> aids.zebra != null
            }
            if (t == guideTool && t != GuideTool.TORCH) { fillC(accent); c.drawRoundRect(r, 10 * s, 10 * s, fill) }
            drawGuideIcon(c, t, r.centerX(), r.centerY(), if (active && t != guideTool) accent else Color.WHITE)
            buttons += Btn(r) {
                if (t == GuideTool.TORCH) { engine.torch = !engine.torch; changed() } else { guideTool = t; invalidate() }
            }
        }
        val left = x(44f); val right = x(1811f) - 120 * s
        val offR = RectF(right + 20 * s, y(925f), x(1811f), y(982f))
        when (guideTool) {
            GuideTool.SAFE -> {
                val opts = (70..100 step 5).toList()
                val cur = opts.indexOf(safeArea).let { if (it < 0) 3 else it }
                drawRulerRow(c, opts.map { v -> Item("$v%", false) { safeArea = v } }, cur, left, right, "SAFE AREA")
                chip(c, offR, "OFF", safeArea == 0); buttons += Btn(offR) { safeArea = 0; invalidate() }
            }
            GuideTool.FOCUS -> {
                // style buttons, then colour swatches (colour applies to Colored Lines)
                val styles = listOf(Item("Colored Lines", aids.focusAssist && !aids.peakStyle) { setFocusAssist(true, peak = false) },
                    Item("Peak", aids.focusAssist && aids.peakStyle) { setFocusAssist(true, peak = true) })
                presetRow(c, styles, left, left + 520 * s, 925f, 982f)
                val sw = 64 * s
                var sx = left + 580 * s
                for ((name, rgb) in MonitorAids.COLORS) {
                    val r = RectF(sx, y(925f), sx + 57 * s, y(982f))
                    val col = Color.rgb((rgb[0] * 255).toInt(), (rgb[1] * 255).toInt(), (rgb[2] * 255).toInt())
                    fillC(col); c.drawCircle(r.centerX(), r.centerY(), 20 * s, fill)
                    strokeC(if (aids.color == name) accent else Color.argb(150, 255, 255, 255), (if (aids.color == name) 4f else 2f) * s)
                    c.drawCircle(r.centerX(), r.centerY(), 25 * s, stroke)
                    buttons += Btn(r) { aids.color = name; prefs.focusAssistColor = name; setFocusAssist(true, peak = false) }
                    sx += sw + 16 * s
                }
                chip(c, offR, "OFF", !aids.focusAssist); buttons += Btn(offR) { setFocusAssist(false, aids.peakStyle) }
            }
            GuideTool.FALSE -> {
                presetRow(c, listOf(Item("Off", !aids.falseColor) { aids.falseColor = false }, Item("On", aids.falseColor) { aids.falseColor = true }),
                    left, left + 360 * s, 925f, 982f)
                val keys = MonitorAids.FALSE_KEY
                var kx = left + 420 * s
                for ((label, rgb) in keys) {
                    fillC(Color.rgb((rgb shr 16) and 0xFF, (rgb shr 8) and 0xFF, rgb and 0xFF))
                    c.drawRoundRect(RectF(kx, y(942f), kx + 24 * s, y(966f)), 4 * s, 4 * s, fill)
                    textC(Color.WHITE, 18 * s, latoBold); c.drawText(label, kx + 32 * s, y(961f), text)
                    kx += 40 * s + text.measureText(label) + 24 * s
                }
            }
            GuideTool.FACE -> {
                presetRow(c, listOf(Item("Off", !engine.faceDetect) { engine.setFaceDetect(false) }, Item("On", engine.faceDetect) { engine.setFaceDetect(true) }),
                    left, left + 360 * s, 925f, 982f)
                textC(Color.argb(210, 255, 255, 255), 20 * s, lato)
                c.drawText("Boxes on faces; with AF on AUTO, focus and exposure follow the largest face.", left + 400 * s, y(962f), text)
            }
            GuideTool.ZEBRA -> {
                val opts = MonitorAids.ZEBRA_LEVELS
                val cur = opts.indexOf(aids.zebra).let { if (it < 0) opts.indexOf("95") else it }
                drawRulerRow(c, opts.map { z -> Item(MonitorAids.zebraLabel(z), false) { setZebra(z) } }, cur, left, right, "ZEBRA")
                chip(c, offR, "OFF", aids.zebra == null); buttons += Btn(offR) { setZebra(null) }
            }
            GuideTool.FRAME -> {
                val opts = GUIDE_RATIOS.keys.toList()
                val cur = opts.indexOf(frameGuide).let { if (it < 0) opts.indexOf("2.39:1") else it }
                drawRulerRow(c, opts.map { g -> Item(g, false) { frameGuide = g } }, cur, left, right, "FRAME GUIDES")
                chip(c, offR, "OFF", frameGuide == null); buttons += Btn(offR) { frameGuide = null; invalidate() }
            }
            else -> {
                val opts = Grid.values().filter { it != Grid.OFF }
                presetRow(c, opts.map { g -> Item(g.label, grid == g) { grid = g } }, left, right, 925f, 982f)
                chip(c, offR, "OFF", grid == Grid.OFF); buttons += Btn(offR) { grid = Grid.OFF; invalidate() }
            }
        }
    }

    private fun setFocusAssist(on: Boolean, peak: Boolean) {
        aids.focusAssist = on; aids.peakStyle = peak
        prefs.focusAssist = on; prefs.focusAssistPeak = peak
        invalidate()
    }

    private fun setZebra(z: String?) { aids.zebra = z; prefs.zebra = z; z?.let { prefs.putStr("last_zebra", it) }; invalidate() }

    private fun drawGuideIcon(c: Canvas, t: GuideTool, cx: Float, cy: Float, color: Int) {
        when (t) {
            GuideTool.FALSE -> {
                val cols = intArrayOf(Color.rgb(122, 31, 160), Color.rgb(30, 91, 255), Color.rgb(47, 212, 74), Color.rgb(255, 230, 0), Color.rgb(255, 32, 32))
                for (i in cols.indices) { fillC(cols[i]); c.drawRect(cx - 24 * s + i * 9.6f * s, cy - 15 * s, cx - 24 * s + (i + 1) * 9.6f * s, cy + 15 * s, fill) }
                strokeC(color, 2.5f * s); c.drawRect(cx - 24 * s, cy - 15 * s, cx + 24 * s, cy + 15 * s, stroke)
            }
            GuideTool.FACE -> {
                strokeC(color, 3 * s)
                c.drawCircle(cx, cy - 6 * s, 9 * s, stroke)
                c.drawArc(RectF(cx - 17 * s, cy + 6 * s, cx + 17 * s, cy + 30 * s), 200f, 140f, false, stroke)
                val k = 8 * s
                for ((ax, ay) in listOf(-1 to -1, 1 to -1, 1 to 1, -1 to 1)) {
                    val px = cx + ax * 24 * s; val py = cy + ay * 19 * s
                    c.drawLine(px, py, px - ax * k, py, stroke); c.drawLine(px, py, px, py - ay * k, stroke)
                }
            }
            GuideTool.FOCUS -> {
                // a picture with a sharp mountain outline
                strokeC(color, 3 * s); c.drawRoundRect(RectF(cx - 24 * s, cy - 17 * s, cx + 24 * s, cy + 17 * s), 5 * s, 5 * s, stroke)
                path.reset(); path.moveTo(cx - 20 * s, cy + 12 * s); path.lineTo(cx - 8 * s, cy - 4 * s); path.lineTo(cx, cy + 5 * s)
                path.lineTo(cx + 8 * s, cy - 8 * s); path.lineTo(cx + 20 * s, cy + 12 * s)
                c.drawPath(path, stroke)
            }
            GuideTool.ZEBRA -> {
                strokeC(color, 3 * s); c.drawCircle(cx, cy, 19 * s, stroke)
                c.save(); path.reset(); path.addCircle(cx, cy, 19 * s, Path.Direction.CW); c.clipPath(path)
                strokeC(color, 2.5f * s)
                for (i in -3..3) c.drawLine(cx + i * 9 * s - 20 * s, cy + 20 * s, cx + i * 9 * s + 20 * s, cy - 20 * s, stroke)
                c.restore()
            }
            GuideTool.TORCH -> drawBolt(c, cx, cy, color, 0.8f * u)
            GuideTool.SAFE -> {
                strokeC(color, 3 * s); c.drawRect(cx - 24 * s, cy - 16 * s, cx + 24 * s, cy + 16 * s, stroke)
                strokeC(color, 2 * s); c.drawRect(cx - 15 * s, cy - 9 * s, cx + 15 * s, cy + 9 * s, stroke)
            }
            GuideTool.FRAME -> {
                strokeC(color, 3 * s); c.drawRect(cx - 24 * s, cy - 16 * s, cx + 24 * s, cy + 16 * s, stroke)
                c.drawLine(cx - 24 * s, cy - 7 * s, cx + 24 * s, cy - 7 * s, stroke); c.drawLine(cx - 24 * s, cy + 7 * s, cx + 24 * s, cy + 7 * s, stroke)
            }
            GuideTool.GRID -> {
                strokeC(color, 3 * s); c.drawRect(cx - 24 * s, cy - 16 * s, cx + 24 * s, cy + 16 * s, stroke)
                strokeC(color, 2 * s)
                for (i in -1..1 step 2) { c.drawLine(cx + i * 8 * s, cy - 16 * s, cx + i * 8 * s, cy + 16 * s, stroke); c.drawLine(cx - 24 * s, cy + i * 5.3f * s, cx + 24 * s, cy + i * 5.3f * s, stroke) }
            }
        }
    }

    private fun drawReticle(c: Canvas) {
        val f = focus ?: return
        if (now() > focusUntil) { focus = null; return }
        val cx = pv.left + f[0] * pv.width(); val cy = pv.top + f[1] * pv.height(); val r = 60 * s
        strokeC(Color.rgb(245, 197, 24), 3 * s)
        c.drawRoundRect(RectF(cx - r, cy - r, cx + r, cy + r), 6 * s, 6 * s, stroke)
    }

    // ---- tool column
    private enum class Tool { GUIDES, AF, EXPOSURE, RECORD, LENS, LUT, FLIP }
    private val toolHit = HashMap<Tool, RectF>()

    private fun toolRect(t: Tool, cx: Float, cy: Float, r: Float) { toolHit[t] = RectF(cx - r, cy - r, cx + r, cy + r) }

    private fun drawToolColumn(c: Canvas) {
        toolHit.clear()
        val cx = tx
        drawBrackets(c, cx, 64 * u, 67 * u, 54 * u, if (guidesOn) accent else Color.WHITE)
        toolRect(Tool.GUIDES, cx, 64 * u, 50 * u)
        drawAfIcon(c, cx, 185 * u, if (selected == P.FOCUS) accent else Color.WHITE)
        toolRect(Tool.AF, cx, 185 * u, 50 * u)
        drawExposureIcon(c, cx - 4 * u, 307 * u, selected == P.EV)
        if (autoExposure) { textC(Color.WHITE, 30 * u, latoBlack); c.drawText("A", cx + 38 * u, 319 * u, text) }
        toolRect(Tool.EXPOSURE, cx, 307 * u, 50 * u)
        val ry = 540 * u
        strokeC(Color.WHITE, 5 * u); c.drawCircle(cx, ry, 77.5f * u, stroke)
        fillC(if (recorder.isRecording) red else Color.rgb(102, 13, 12)); c.drawCircle(cx, ry, 37 * u, fill)
        toolRect(Tool.RECORD, cx, ry, 85 * u)
        val ly = 770 * u
        strokeC(if (selected == P.LENS) accent else Color.WHITE, 4 * u); c.drawCircle(cx, ly, 28 * u, stroke)
        fillC(Color.rgb(58, 62, 68)); c.drawCircle(cx, ly, 21 * u, fill)
        fillC(Color.rgb(150, 155, 162)); c.drawCircle(cx + 6 * u, ly - 7 * u, 5 * u, fill)
        val zoomLabel = if (engine.isFront) "F" else fmtX(engine.lens.zoomVsMain * engine.zoom)
        textC(Color.WHITE, 30 * u, lato)
        c.drawText(zoomLabel, cx + 63 * u - text.measureText(zoomLabel), 824 * u, text)
        toolRect(Tool.LENS, cx, ly, 50 * u)
        drawBadgeButton(c, cx - 2 * u, 888 * u, "LUT", prefs.lut != null || lutPanel)
        toolRect(Tool.LUT, cx, 888 * u, 45 * u)
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

    private fun drawExposureIcon(c: Canvas, cx: Float, cy: Float, active: Boolean) {
        val half = 27 * u
        val r = RectF(cx - half, cy - half, cx + half, cy + half)
        val fg = if (active) accent else Color.WHITE
        fillC(fg); c.drawRoundRect(r, 9 * u, 9 * u, fill)
        c.save()
        path.reset(); path.moveTo(r.left, r.top); path.lineTo(r.right, r.top); path.lineTo(r.left, r.bottom); path.close()
        c.clipPath(path)
        fillC(navy); c.drawRoundRect(RectF(r.left + 3 * u, r.top + 3 * u, r.right, r.bottom), 7 * u, 7 * u, fill)
        c.restore()
        strokeC(fg, 4 * u)
        c.drawLine(r.left + 10 * u, r.top + 18 * u, r.left + 26 * u, r.top + 18 * u, stroke)
        c.drawLine(r.left + 18 * u, r.top + 10 * u, r.left + 18 * u, r.top + 26 * u, stroke)
        strokeC(navy, 4 * u)
        c.drawLine(r.right - 26 * u, r.bottom - 16 * u, r.right - 10 * u, r.bottom - 16 * u, stroke)
    }

    private fun drawBolt(c: Canvas, cx: Float, cy: Float, color: Int, k: Float = u) {
        strokeC(color, 4 * k)
        path.reset()
        path.moveTo(cx + 6 * k, cy - 26 * k); path.lineTo(cx - 12 * k, cy + 3 * k); path.lineTo(cx + 2 * k, cy + 3 * k)
        path.lineTo(cx - 6 * k, cy + 26 * k); path.lineTo(cx + 12 * k, cy - 3 * k); path.lineTo(cx - 2 * k, cy - 3 * k); path.close()
        c.drawPath(path, stroke)
    }

    private fun drawBadgeButton(c: Canvas, cx: Float, cy: Float, label: String, on: Boolean) {
        val r = RectF(cx - 29 * u, cy - 20 * u, cx + 29 * u, cy + 20 * u)
        fillC(Color.rgb(88, 92, 98)); c.drawRoundRect(RectF(r.left + 6 * u, r.top + 6 * u, r.right + 6 * u, r.bottom + 6 * u), 8 * u, 8 * u, fill)
        fillC(navy); c.drawRoundRect(r, 8 * u, 8 * u, fill)
        val col = if (on) accent else Color.WHITE
        strokeC(col, 3.5f * u); c.drawRoundRect(r, 8 * u, 8 * u, stroke)
        textC(col, 17 * u, latoBlack)
        centered(c, label, r.centerX(), r.centerY() + 6 * u)
    }

    private fun drawFlipIcon(c: Canvas, cx: Float, cy: Float) {
        strokeC(Color.WHITE, 4 * u)
        val w = 60 * u; val h = 40 * u
        val r = RectF(cx - w / 2, cy - h / 2, cx + w / 2, cy + h / 2)
        path.reset()
        path.moveTo(r.left, cy - 6 * u); path.lineTo(r.left, r.top + 9 * u); path.quadTo(r.left, r.top, r.left + 9 * u, r.top)
        path.lineTo(r.right - 9 * u, r.top); path.quadTo(r.right, r.top, r.right, r.top + 9 * u)
        path.moveTo(r.right, cy + 6 * u); path.lineTo(r.right, r.bottom - 9 * u); path.quadTo(r.right, r.bottom, r.right - 9 * u, r.bottom)
        path.lineTo(r.left + 9 * u, r.bottom); path.quadTo(r.left, r.bottom, r.left, r.bottom - 9 * u)
        c.drawPath(path, stroke)
        c.drawCircle(cx, cy, 10 * u, stroke)
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
        val activeNav = if (settingsOpen) Nav.SETTINGS else Nav.CAMERA
        fillC(Color.rgb(0, 18, 38))
        c.drawRoundRect(RectF(navX + 18 * u, (activeNav.iconY - 63) * u, navX + 138 * u, (activeNav.labelY + 14) * u), 12 * u, 12 * u, fill)
        for (n in Nav.values()) {
            val iy = n.iconY * u
            val active = n == activeNav
            when (n) {
                Nav.CAMERA -> drawCamcorder(c, cx, iy, active)
                Nav.MEDIA -> drawFilmFrame(c, cx, iy)
                Nav.REMOTE -> {
                    drawPhoneWaves(c, cx, iy + 8 * u, u, Color.WHITE)
                    fillC(if (connected) Color.rgb(64, 205, 64) else Color.argb(90, 255, 255, 255))
                    c.drawCircle(cx + 24 * u, iy + 22 * u, 7 * u, fill)
                }
                Nav.SETTINGS -> drawGear(c, cx, iy, if (active) accent else Color.WHITE)
            }
            textC(if (active) navActive else navIdle, 25 * u, latoBold)
            centered(c, n.label, cx, n.labelY * u)
            navHit[n] = RectF(navX, iy - 70 * u, width.toFloat(), n.labelY * u + 25 * u)
        }
        if (prefs.showBattery) {
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
    }

    private fun drawCamcorder(c: Canvas, cx: Float, cy: Float, active: Boolean) {
        val body = RectF(cx - 42 * u, cy - 22 * u, cx + 12 * u, cy + 22 * u)
        val line = if (active) accent else Color.WHITE
        if (active) { fillC(Color.rgb(0, 71, 147)); c.drawRoundRect(body, 9 * u, 9 * u, fill) }
        strokeC(line, 4 * u); c.drawRoundRect(body, 9 * u, 9 * u, stroke)
        path.reset()
        path.moveTo(cx + 14 * u, cy - 6 * u); path.lineTo(cx + 40 * u, cy - 19 * u); path.lineTo(cx + 40 * u, cy + 19 * u)
        path.lineTo(cx + 14 * u, cy + 6 * u); path.close()
        if (active) { fillC(Color.rgb(0, 71, 147)); c.drawPath(path, fill) }
        c.drawPath(path, stroke)
        fillC(line)
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

    private fun drawGear(c: Canvas, cx: Float, cy: Float, color: Int) {
        strokeC(color, 4 * u)
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
    private var drag: RulerHit? = null
    private var dragSlider: Slider? = null
    private var downX = 0f
    private var dragging = false

    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (!::engine.isInitialized) return true
        val px = e.x; val py = e.y
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                drag = null; dragSlider = null; dragging = false; downX = px; lutDown = false; fnDown = -1
                fnRects.withIndex().firstOrNull { fnShown && prefs.functionButtons && it.value?.contains(px, py) == true }?.let {
                    fnDown = it.index; fnLong = false
                    postDelayed(fnLongPress, 600); return true
                }
                if (lutPanel && editorRect.contains(px, py)) { lutDown = true; lutDownScroll = lutScroll; return true }
                toolHit.entries.firstOrNull { it.value.contains(px, py) }?.let { onTool(it.key); invalidate(); return true }
                navHit.entries.firstOrNull { it.value.contains(px, py) }?.let { onNav(it.key); return true }
                buttons.firstOrNull { it.rect.contains(px, py) }?.let { it.act(); invalidate(); return true }
                sliders.firstOrNull { it.hit.contains(px, py) }?.let { dragSlider = it; slide(it, px); return true }
                rulers.firstOrNull { it.rect.contains(px, py) }?.let { drag = it; return true }
                readoutHit.entries.firstOrNull { it.value.contains(px, py) }?.let {
                    select(if (selected == it.key) null else it.key); return true }
                if (badgeRect.contains(px, py)) { onSettings("resolution"); return true }
                if (leftHandle.contains(px, py)) { sideMenu = !sideMenu; if (!sideMenu) presetsPanel = false; invalidate(); return true }
                if (rightHandle.contains(px, py)) {
                    if (prefs.functionButtons) fnShown = !fnShown else onSettings("fbuttons")
                    invalidate(); return true
                }
                if (selected == null && !guidesOn && expanded == null && panelsOn) {
                    if (prefs.showHistogram && histRect.contains(px, py)) { expanded = Expanded.HISTOGRAM; invalidate(); return true }
                    if (prefs.showAudioMeters && audioRect.contains(px, py)) { expanded = Expanded.AUDIO; invalidate(); return true }
                    if (prefs.showStorage && mediaRect.contains(px, py)) { onSettings("record"); return true }
                }
                if (expanded != null && (histRect.contains(px, py) || audioRect.contains(px, py))) {
                    if (expanded == Expanded.HISTOGRAM || !audioRect.contains(px, py) || py < y(865f)) { expanded = null; invalidate() }
                    return true
                }
                if (editorRect.contains(px, py) && (selected != null || guidesOn || presetsPanel)) return true
                if (pv.contains(px, py)) {
                    if (selected != null || guidesOn || expanded != null || lutPanel || presetsPanel) { collapse(); return true }
                    val fx = ((px - pv.left) / pv.width()).coerceIn(0f, 1f); val fy = ((py - pv.top) / pv.height()).coerceIn(0f, 1f)
                    engine.tapFocus(fx, fy); showFocusReticle(fx, fy); onParamChanged(null)
                }
            }
            MotionEvent.ACTION_MOVE -> {
                if (lutDown) {
                    if (abs(px - downX) > 12 * s) dragging = true
                    if (dragging) { lutScroll = lutDownScroll + (px - downX); invalidate() }
                    return true
                }
                dragSlider?.let { slide(it, px); return true }
                val d = drag ?: return true
                if (abs(px - downX) > 12 * s) dragging = true
                if (!dragging) return true
                val idx = (d.cur - ((px - downX) / d.spacing).roundToInt()).coerceIn(0, d.items.size - 1)
                d.items[idx].act(); changed()
                drag = RulerHit(d.rect, d.items, idx, d.cx, d.spacing); downX = px
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (fnDown >= 0) {
                    removeCallbacks(fnLongPress)
                    if (!fnLong && e.actionMasked == MotionEvent.ACTION_UP) runFn(prefs.fn(fnDown))
                    fnDown = -1; return true
                }
                if (lutDown) {
                    if (!dragging && e.actionMasked == MotionEvent.ACTION_UP)
                        lutCards.firstOrNull { it.first.contains(px, py) }?.let { (_, n) -> n?.let { prefs.putStr("last_lut", it) }; onLutChanged(n) }
                    lutDown = false; dragging = false; invalidate(); return true
                }
                val d = drag
                if (d != null && !dragging && e.actionMasked == MotionEvent.ACTION_UP) {
                    // tap an item on the ruler to jump to it
                    val idx = (d.cur + ((px - d.cx) / d.spacing).roundToInt()).coerceIn(0, d.items.size - 1)
                    d.items[idx].act(); changed()
                } else if (d != null) onParamChanged(null)
                drag = null; dragSlider = null; dragging = false
                invalidate()
            }
        }
        return true
    }

    private fun slide(sl: Slider, px: Float) {
        val pct = (((px - sl.track.left) / sl.track.width()) * 100).roundToInt().coerceIn(0, 100)
        sl.set(pct)
    }

    private var lutDown = false
    private var lutDownScroll = 0f
    private var fnDown = -1
    private var fnLong = false
    private val fnLongPress = Runnable { if (fnDown >= 0) { fnLong = true; onSettings("fbuttons") } }

    private fun select(p: P?) { collapse(); selected = p; invalidate() }
    private fun collapse() { selected = null; guidesOn = false; expanded = null; lutPanel = false; presetsPanel = false; invalidate() }

    private fun onTool(t: Tool) {
        when (t) {
            Tool.GUIDES -> { val on = !guidesOn; collapse(); guidesOn = on }
            Tool.AF -> select(if (selected == P.FOCUS) null else P.FOCUS)
            Tool.EXPOSURE -> select(if (selected == P.EV) null else P.EV)
            Tool.RECORD -> onRecordToggle()
            Tool.LENS -> select(if (selected == P.LENS) null else P.LENS)
            Tool.LUT -> { val open = !lutPanel; collapse(); lutPanel = open }
            Tool.FLIP -> {
                val target = if (engine.isFront) engine.lenses.firstOrNull { it.type == CameraEngine.LENS_MAIN }
                             else engine.lenses.firstOrNull { it.type == CameraEngine.LENS_FRONT }
                target?.let { engine.switchLens(it.id); onParamChanged(null) }
            }
        }
    }

    private fun onNav(n: Nav) {
        when (n) {
            Nav.CAMERA -> { collapse(); if (settingsOpen) onSettings(CLOSE) }
            Nav.MEDIA -> onMedia()
            Nav.REMOTE -> onRemote()
            Nav.SETTINGS -> onSettings(null)
        }
    }

    companion object {
        const val CLOSE = "close"
        val SHUTTER_DENS = listOf(24, 25, 30, 48, 50, 60, 96, 100, 120, 125, 160, 180, 200, 240, 250, 320, 360,
            400, 480, 500, 640, 800, 1000, 1250, 1600, 2000, 2500, 3200, 4000, 8000)
        /** Audio meter scale marks (dBFS) and where they sit along the bar, from the reference layout. */
        val METER_SCALE = listOf(-45f to 0f, -30f to 0.140f, -20f to 0.321f, -10f to 0.594f, -6f to 0.711f,
            -3f to 0.802f, 0f to 0.912f, 3f to 1f)
        val GUIDE_RATIOS = linkedMapOf("2.76:1" to 2.76f, "2.4:1" to 2.4f, "2.39:1" to 2.39f, "2.35:1" to 2.35f,
            "2:1" to 2f, "1.85:1" to 1.85f, "16:9" to 16f / 9, "14:9" to 14f / 9, "4:3" to 4f / 3, "1:1" to 1f)
        val TIMELAPSE_SECS = listOf(1, 2, 3, 5, 10, 15, 30, 60, 120, 300, 600)
        /** Function-button actions: id to the name shown in Settings. */
        val FN_ACTIONS = linkedMapOf("NONE" to "None", "RECORD" to "Record", "TORCH" to "Torch", "FOCUS" to "Focus Assist",
            "ZEBRA" to "Zebra", "FALSE" to "False Color", "FACE" to "Face Detection", "LUT" to "Display LUT",
            "GUIDES" to "Frame Guides", "GRID" to "Grid", "STAB" to "Stabilization", "AE" to "Auto Exposure",
            "AELOCK" to "Exposure Lock", "AWBLOCK" to "White Balance Lock", "FLIP" to "Flip Camera", "GOOD" to "Good Take",
            "CLEAN" to "Clean Feed")
        val FN_SHORT = mapOf("RECORD" to "REC", "TORCH" to "TORCH", "FOCUS" to "PEAK", "ZEBRA" to "ZEBRA", "FALSE" to "FALSE",
            "FACE" to "FACE", "LUT" to "LUT", "GUIDES" to "GUIDE", "GRID" to "GRID", "STAB" to "STAB", "AE" to "AUTO",
            "AELOCK" to "AE LK", "AWBLOCK" to "WB LK", "FLIP" to "FLIP", "GOOD" to "GOOD", "CLEAN" to "CLEAN")
        val WB_PRESETS = listOf("Cloudy" to 6500, "Shade" to 7500, "Fluorescent" to 4000, "Tungsten" to 3200, "Daylight" to 5600)
    }
}
