package com.cineremote.camera

import android.app.AlertDialog
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast

/**
 * The Settings screen, styled like the Blackmagic Camera app's: section headings over rounded cards of rows
 * (value + chevron, switch, slider, or a check-marked choice list on a sub-page). It writes [prefs] and tells
 * [host] about changes that need action. Only settings this app actually implements are listed.
 */
class SettingsPanel(context: Context, private val prefs: Prefs, private val engine: CameraEngine, private val aids: MonitorAids,
                    private val slate: Slate, private val host: Host) : FrameLayout(context) {

    interface Host {
        fun isRecording(): Boolean
        fun onFormatChanged()
        fun onAudioGainChanged(percent: Int)
        fun onRemoteChanged()
        fun onMonitorChanged()
        fun onLutChanged(name: String?)
        fun importLut()
        fun onSlateChanged()
        fun onGoodTake(good: Boolean)
        fun onClose()
    }

    private val lato = resources.getFont(R.font.lato_regular)
    private val latoBold = resources.getFont(R.font.lato_bold)
    private val accent = Color.rgb(49, 152, 252)
    private val card = Color.rgb(28, 28, 30)
    private val divider = Color.rgb(56, 56, 60)
    private val dim = Color.rgb(142, 142, 147)
    private val d = resources.displayMetrics.density
    private fun dp(v: Float) = (v * d).toInt()

    private val title = TextView(context)
    private val back = TextView(context)
    private val content = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
    private val scroll = ScrollView(context)
    private var page = "root"

    init {
        setBackgroundColor(Color.BLACK)
        isClickable = true                      // don't let touches fall through to the camera screen
        val header = FrameLayout(context)
        title.apply { setTextColor(Color.WHITE); typeface = latoBold; setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f); gravity = Gravity.CENTER }
        back.apply {
            text = "‹"; setTextColor(Color.WHITE); typeface = lato; setTextSize(TypedValue.COMPLEX_UNIT_SP, 34f)
            setPadding(dp(18f), 0, dp(24f), dp(4f)); setOnClickListener { show(parentOf(page)) }
        }
        header.addView(title, LayoutParams(-1, dp(52f)))
        header.addView(back, LayoutParams(-2, dp(52f), Gravity.START or Gravity.CENTER_VERTICAL))
        addView(header, LayoutParams(-1, dp(52f)))
        scroll.addView(content, LayoutParams(-1, -2))
        addView(scroll, LayoutParams(-1, -1).apply { topMargin = dp(52f) })
    }

    fun show(p: String) {
        page = p
        content.removeAllViews()
        back.visibility = if (p == "root") View.GONE else View.VISIBLE
        when (p) {
            "codec" -> codecPage()
            "resolution" -> resolutionPage()
            "stereo" -> choicePage("Audio", "RECORD AUDIO AS", listOf("Stereo", "Mono"), if (prefs.recordStereo) 0 else 1) {
                prefs.recordStereo = it == 0 }
            "focus" -> choicePage("Monitor", "FOCUS ASSIST", listOf("Off", "Colored Lines", "Peak"),
                if (!aids.focusAssist) 0 else if (aids.peakStyle) 2 else 1) {
                aids.focusAssist = it > 0; prefs.focusAssist = it > 0
                if (it > 0) { aids.peakStyle = it == 2; prefs.focusAssistPeak = it == 2 }
                host.onMonitorChanged() }
            "focuscolor" -> MonitorAids.COLORS.keys.toList().let { names ->
                choicePage("Monitor", "FOCUS ASSIST COLOR", names, names.indexOf(aids.color)) {
                    aids.color = names[it]; prefs.focusAssistColor = names[it]; host.onMonitorChanged() } }
            "zebra" -> (listOf<String?>(null) + MonitorAids.ZEBRA_LEVELS).let { levels ->
                choicePage("Monitor", "ZEBRA", levels.map { it?.let(MonitorAids::zebraLabel) ?: "Off" }, levels.indexOf(aids.zebra)) {
                    aids.zebra = levels[it]; prefs.zebra = levels[it]; host.onMonitorChanged() } }
            "timelapse" -> choicePage("Record", "CAPTURE 1 FRAME EVERY", CameraHud.TIMELAPSE_SECS.map { secsLabel(it) },
                CameraHud.TIMELAPSE_SECS.indexOf(prefs.timelapseSecs)) { prefs.timelapseSecs = CameraHud.TIMELAPSE_SECS[it]; host.onMonitorChanged() }
            "lut" -> (listOf<String?>(null) + Luts.names(context)).let { names ->
                choicePage("LUTs", "DISPLAY LUT", names.map { it ?: "Off" }, names.indexOf(prefs.lut)) { host.onLutChanged(names[it]) } }
            "luts" -> lutsPage()
            "fbuttons" -> fnPage()
            "fn0", "fn1", "fn2" -> p.last().digitToInt().let { i ->
                val ids = CameraHud.FN_ACTIONS.keys.toList()
                choicePage("Function Buttons", "F${i + 1}", ids.map { CameraHud.FN_ACTIONS.getValue(it) }, ids.indexOf(prefs.fn(i))) {
                    prefs.setFn(i, ids[it]); host.onMonitorChanged() } }
            "slate" -> slatePage()
            "light" -> choicePage("Slate", "INTERIOR / EXTERIOR", listOf("Not set", "Interior", "Exterior"), slate.light) {
                slate.light = it; host.onSlateChanged() }
            "tod" -> choicePage("Slate", "DAY / NIGHT", listOf("Not set", "Day", "Night"), slate.timeOfDay) {
                slate.timeOfDay = it; host.onSlateChanged() }
            "guides" -> choicePage("Monitor", "GUIDES OPACITY", OPACITIES.map { "$it%" }, OPACITIES.indexOf(prefs.guidesOpacity)) {
                prefs.guidesOpacity = OPACITIES[it]; host.onMonitorChanged() }
            "grids" -> choicePage("Monitor", "GRIDS OPACITY", OPACITIES.map { "$it%" }, OPACITIES.indexOf(prefs.gridsOpacity)) {
                prefs.gridsOpacity = OPACITIES[it]; host.onMonitorChanged() }
            else -> rootPage()
        }
        scroll.scrollTo(0, 0)
    }

    private fun parentOf(p: String) = when (p) {
        "fn0", "fn1", "fn2" -> "fbuttons"; "light", "tod" -> "slate"; else -> "root"
    }

    private fun secsLabel(secs: Int) = when {
        secs >= 60 && secs % 60 == 0 -> "${secs / 60} minute${if (secs > 60) "s" else ""}"
        else -> "$secs second${if (secs > 1) "s" else ""}"
    }

    // ------------------------------------------------------------------ pages
    private fun rootPage() {
        title.text = "Settings"
        val f = engine.format
        section("RECORD")
        cardOf(
            nav("Codec", if (prefs.hevc) "HEVC (H.265)" else "H.264") { show("codec") },
            nav("Resolution", "${Formats.BUCKETS.first { it.first == f.bucket }.second}  ${f.label}") { show("resolution") },
            nav("Bit Rate", "${Formats.tierName(prefs.bitrateTier)} (${Formats.bitrate(f, engine.fps, prefs.hevc, prefs.bitrateTier) / 1_000_000}Mbps)") { show("codec") },
            switch("Timelapse Recording", prefs.timelapse) { prefs.timelapse = it; if (it) engine.setOffSpeed(false); host.onMonitorChanged() },
            nav("Capture 1 Frame Every", secsLabel(prefs.timelapseSecs)) { show("timelapse") },
        )
        section("CAMERA")
        cardOf(
            switch("Lock White Balance on Record", prefs.lockWbOnRecord) { prefs.lockWbOnRecord = it },
            switch("Use Volume Button to Trigger Record", prefs.volumeKeyRecords) { prefs.volumeKeyRecords = it },
            switch("Video Stabilization", engine.stabilization) { engine.stabilization = it; engine.apply() },
            *(if (engine.faceDetectSupported) arrayOf(switch("Face Detection", engine.faceDetect) { engine.setFaceDetect(it); host.onMonitorChanged() }) else emptyArray()),
        )
        section("SLATE")
        cardOf(nav("Slate", "Scene ${slate.scene}  ·  Take ${slate.take}") { show("slate") })
        section("AUDIO")
        cardOf(
            slider("Audio Gain", prefs.audioGain) { prefs.audioGain = it; host.onAudioGainChanged(it) },
            nav("Record Audio as", if (prefs.recordStereo) "Stereo" else "Mono") { show("stereo") },
        )
        section("MONITOR")
        cardOf(
            nav("Focus Assist", if (!aids.focusAssist) "Off" else if (aids.peakStyle) "Peak" else "Colored Lines") { show("focus") },
            nav("Focus Assist Color", aids.color) { show("focuscolor") },
            nav("Zebra", aids.zebra?.let(MonitorAids::zebraLabel) ?: "Off") { show("zebra") },
            nav("Guides Opacity", "${prefs.guidesOpacity}%") { show("guides") },
            nav("Grids Opacity", "${prefs.gridsOpacity}%") { show("grids") },
            switch("Display Audio Meters", prefs.showAudioMeters) { prefs.showAudioMeters = it; host.onMonitorChanged() },
            switch("Display Histogram", prefs.showHistogram) { prefs.showHistogram = it; host.onMonitorChanged() },
            switch("Display Storage Status", prefs.showStorage) { prefs.showStorage = it; host.onMonitorChanged() },
            switch("Display Battery Indicator", prefs.showBattery) { prefs.showBattery = it; host.onMonitorChanged() },
        )
        section("LUTS")
        cardOf(
            nav("Display LUT", prefs.lut ?: "Off") { show("lut") },
            nav("LUTs Manager", "${Luts.names(context).size}") { show("luts") },
        )
        section("FUNCTION BUTTONS")
        cardOf(nav("Function Buttons", if (prefs.functionButtons) "On" else "Off") { show("fbuttons") })
        section("REMOTE CAMERA CONTROL")
        cardOf(
            switch("Remote Camera Control", prefs.remoteEnabled) { prefs.remoteEnabled = it; host.onRemoteChanged() },
            edit("Camera Name", prefs.deviceName, false) { prefs.deviceName = it; host.onRemoteChanged() },
            edit("Camera Name", prefs.slate, false) { prefs.slate = it; host.onRemoteChanged() },
            edit("Remote Password", prefs.password, true) { if (it.isNotBlank()) { prefs.password = it; host.onRemoteChanged() } },
        )
        section("ABOUT")
        val version = runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull() ?: "?"
        cardOf(info("App Version", version), info("Camera", engine.lens.let { "${it.equivMm}mm  ·  ${f.width}x${f.height}" }))
        spacer()
    }

    private fun codecPage() {
        title.text = "Record"
        val hevcOk = Formats.hevcSupported()
        section("CODEC")
        cardOf(*listOfNotNull(
            if (hevcOk) choice("HEVC (H.265)", null, prefs.hevc) { setCodec(true) } else null,
            choice("H.264", null, !prefs.hevc) { setCodec(false) },
        ).toTypedArray())
        section("BIT RATE")
        val f = engine.format
        cardOf(*(3 downTo 0).map { t ->
            choice("${Formats.tierName(t)} (${Formats.bitrate(f, engine.fps, prefs.hevc, t) / 1_000_000}Mbps)", null, prefs.bitrateTier == t) {
                prefs.bitrateTier = t; show("codec") }
        }.toTypedArray())
        note("Bit rates are for ${f.width}x${f.height} at ${engine.fps} fps.")
    }

    private fun setCodec(hevc: Boolean) {
        if (blockedByTake()) return
        prefs.hevc = hevc
        engine.setFormat()
        host.onFormatChanged()
        postDelayed({ show("codec") }, 150)
    }

    private fun resolutionPage() {
        title.text = "Record"
        val all = Formats.available(engine.lens, prefs.hevc)
        val f = engine.format
        section("RESOLUTION")
        cardOf(*Formats.BUCKETS.filter { b -> all.any { it.bucket == b.first } }.map { (b, name) ->
            choice(name, null, f.bucket == b) { setFormat(b, null) }
        }.toTypedArray())
        section("ASPECT RATIO")
        cardOf(*all.filter { it.bucket == f.bucket }.map { ff ->
            choice(ff.label, "${ff.width}x${ff.height}", ff.key == f.key) { setFormat(ff.bucket, ff.key) }
        }.toTypedArray())
        note("Sizes this lens and the phone's ${if (prefs.hevc) "HEVC" else "H.264"} encoder can record. The live stream to CineRemote stays at up to 1080p.")
    }

    private fun setFormat(bucket: String, key: String?) {
        if (blockedByTake()) return
        val k = key ?: Formats.defaultIn(bucket, Formats.available(engine.lens, prefs.hevc))?.key
        engine.setFormat(bucket, k)
        host.onFormatChanged()
        postDelayed({ show("resolution") }, 150)
    }

    private fun blockedByTake(): Boolean {
        if (!host.isRecording()) return false
        Toast.makeText(context, "Stop recording to change the format", Toast.LENGTH_SHORT).show()
        return true
    }

    private fun lutsPage() {
        title.text = "LUTs"
        section("BUILT IN")
        cardOf(*Luts.BUILT_IN.map { n -> choice(n, null, prefs.lut == n) { host.onLutChanged(n); show("luts") } }.toTypedArray())
        section("IMPORTED")
        val imported = Luts.imported(context)
        val rows = imported.map { n ->
            row(n).first.apply {
                addView(TextView(context).apply {
                    text = if (prefs.lut == n) "In use   " else ""; setTextColor(accent); typeface = lato; setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f) })
                addView(TextView(context).apply {
                    text = "Delete"; setTextColor(Color.rgb(255, 90, 80)); typeface = latoBold; setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
                    setPadding(dp(12f), dp(8f), dp(4f), dp(8f))
                    setOnClickListener { if (prefs.lut == n) host.onLutChanged(null); Luts.delete(context, n); show("luts") }
                })
                setOnClickListener { host.onLutChanged(n); show("luts") }
            }
        }
        cardOf(*(rows + nav("Import LUT (.cube)…", "") { host.importLut() }).toTypedArray())
        note("Display LUTs colour the phone's screen only; recordings and the CineRemote stream stay ungraded. Import 3D LUTs in .cube format (up to 65 points).")
    }

    private fun fnPage() {
        title.text = "Function Buttons"
        section("FUNCTION BUTTONS")
        cardOf(
            switch("Function Buttons", prefs.functionButtons) { prefs.functionButtons = it; host.onMonitorChanged() },
            *(0..2).map { i -> nav("F${i + 1}", CameraHud.FN_ACTIONS[prefs.fn(i)] ?: "None") { show("fn$i") } }.toTypedArray(),
        )
        note("Tap the dots on the picture's right edge to show F1–F3. Long-press a button to change what it does.")
    }

    private fun slatePage() {
        title.text = "Slate"
        section("CLIP")
        cardOf(
            stepper("Reel", slate.reel) { slate.reel = it; host.onSlateChanged() },
            edit("Scene", slate.scene, false) { slate.scene = it; host.onSlateChanged() },
            stepper("Take", slate.take) { slate.take = it; host.onSlateChanged() },
            switch("Auto-Increment Take", slate.autoIncrement) { slate.autoIncrement = it; host.onSlateChanged() },
            nav("Interior / Exterior", listOf("Not set", "Interior", "Exterior")[slate.light.coerceIn(0, 2)]) { show("light") },
            nav("Day / Night", listOf("Not set", "Day", "Night")[slate.timeOfDay.coerceIn(0, 2)]) { show("tod") },
            switch("Good Take (last clip)", slate.goodTake) { host.onGoodTake(it) },
        )
        section("PROJECT")
        cardOf(
            edit("Production", slate.production, false) { slate.production = it; host.onSlateChanged() },
            edit("Director", slate.director, false) { slate.director = it; host.onSlateChanged() },
            edit("Camera Operator", slate.operator, false) { slate.operator = it; host.onSlateChanged() },
            stepper("Clip Number", slate.clip) { slate.clip = it; host.onSlateChanged() },
        )
        note("Next clip: ${slate.nextFileName()}\n${slate.describe()}")
    }

    /** A number with − / + buttons. */
    private fun stepper(label: String, value: Int, onChange: (Int) -> Unit): View {
        val (r, _) = row(label)
        fun btn(t: String, d: Int) = TextView(context).apply {
            text = t; setTextColor(accent); typeface = latoBold; setTextSize(TypedValue.COMPLEX_UNIT_SP, 24f)
            gravity = Gravity.CENTER; setPadding(dp(14f), 0, dp(14f), 0)
            setOnClickListener { onChange((value + d).coerceIn(1, 999)); show(page) }
        }
        r.addView(btn("−", -1))
        r.addView(valueText("$value").apply { setTextColor(Color.WHITE); minWidth = dp(44f); gravity = Gravity.CENTER })
        r.addView(btn("+", 1))
        return r
    }

    private fun choicePage(t: String, heading: String, options: List<String>, cur: Int, pick: (Int) -> Unit) {
        title.text = t
        section(heading)
        cardOf(*options.mapIndexed { i, o -> choice(o, null, i == cur) { pick(i); show(page) } }.toTypedArray())
    }

    // ------------------------------------------------------------------ building blocks
    private fun section(t: String) {
        content.addView(TextView(context).apply {
            text = t; setTextColor(dim); typeface = lato; setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f); letterSpacing = 0.04f
            setPadding(dp(16f), dp(18f), dp(16f), dp(6f))
        }, column())
    }

    private fun note(t: String) {
        content.addView(TextView(context).apply {
            text = t; setTextColor(dim); typeface = lato; setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
            setPadding(dp(16f), dp(8f), dp(16f), 0)
        }, column())
    }

    private fun spacer() = content.addView(View(context), LinearLayout.LayoutParams(-1, dp(40f)))

    /** Content column: centred, at most ~640dp wide (the screen is landscape). */
    private fun column(): LinearLayout.LayoutParams {
        val w = minOf(resources.displayMetrics.widthPixels - dp(40f), dp(640f))
        return LinearLayout.LayoutParams(w, -2).apply { gravity = Gravity.CENTER_HORIZONTAL }
    }

    private fun cardOf(vararg rows: View) {
        val box = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply { setColor(card); cornerRadius = dp(12f).toFloat() }
        }
        rows.forEachIndexed { i, r ->
            if (i > 0) box.addView(View(context).apply { setBackgroundColor(divider) },
                LinearLayout.LayoutParams(-1, 1).apply { leftMargin = dp(16f) })
            box.addView(r, LinearLayout.LayoutParams(-1, -2))
        }
        content.addView(box, column())
    }

    private fun row(label: String, sub: String? = null, leftPad: Float = 16f): Pair<LinearLayout, LinearLayout> {
        val r = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(50f); setPadding(dp(leftPad), dp(6f), dp(14f), dp(6f))
        }
        val texts = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        texts.addView(TextView(context).apply { text = label; setTextColor(Color.WHITE); typeface = lato; setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f) })
        sub?.let { texts.addView(TextView(context).apply { text = it; setTextColor(dim); typeface = lato; setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f) }) }
        r.addView(texts, LinearLayout.LayoutParams(0, -2, 1f))
        return r to texts
    }

    private fun valueText(t: String) = TextView(context).apply {
        text = t; setTextColor(dim); typeface = lato; setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f); maxLines = 1
    }

    private fun nav(label: String, value: String, onClick: () -> Unit): View {
        val (r, _) = row(label)
        r.addView(valueText(value))
        r.addView(TextView(context).apply { text = "  ›"; setTextColor(dim); setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f) })
        r.setOnClickListener { onClick() }
        return r
    }

    private fun info(label: String, value: String): View = row(label).first.apply { addView(valueText(value)) }

    private fun choice(label: String, sub: String?, checked: Boolean, onClick: () -> Unit): View {
        val (r, _) = row(label, sub, leftPad = 0f)
        r.addView(TextView(context).apply {
            text = if (checked) "✓" else ""; setTextColor(accent); typeface = latoBold; setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
            gravity = Gravity.CENTER
        }, 0, LinearLayout.LayoutParams(dp(48f), -2))
        r.setOnClickListener { onClick() }
        return r
    }

    private fun switch(label: String, on: Boolean, onChange: (Boolean) -> Unit): View {
        val (r, _) = row(label)
        val sw = Switch(context).apply {
            isChecked = on
            thumbTintList = ColorStateList.valueOf(Color.WHITE)
            trackTintList = ColorStateList(arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()), intArrayOf(accent, Color.rgb(72, 72, 76)))
            setOnCheckedChangeListener { _, v -> onChange(v) }
        }
        r.addView(sw)
        r.setOnClickListener { sw.toggle() }
        return r
    }

    private fun slider(label: String, value: Int, onChange: (Int) -> Unit): View {
        val box = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16f), dp(10f), dp(14f), dp(8f)) }
        box.addView(TextView(context).apply { text = label; setTextColor(Color.WHITE); typeface = lato; setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f) })
        val line = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val pct = valueText("$value%").apply { setTextColor(Color.WHITE); minWidth = dp(52f); gravity = Gravity.END }
        val bar = SeekBar(context).apply {
            max = 100; progress = value
            progressTintList = ColorStateList.valueOf(accent); thumbTintList = ColorStateList.valueOf(Color.WHITE)
            progressBackgroundTintList = ColorStateList.valueOf(Color.rgb(90, 90, 96))
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar, p: Int, fromUser: Boolean) { pct.text = "$p%"; if (fromUser) onChange(p) }
                override fun onStartTrackingTouch(sb: SeekBar) {}
                override fun onStopTrackingTouch(sb: SeekBar) {}
            })
        }
        line.addView(bar, LinearLayout.LayoutParams(0, -2, 1f))
        line.addView(pct)
        box.addView(line)
        return box
    }

    private fun edit(label: String, value: String, secret: Boolean, onSave: (String) -> Unit): View {
        return nav(label, if (secret) "••••••••" else value) {
            val input = EditText(context).apply {
                setText(if (secret) "" else value)
                hint = if (secret) "New password" else label
                inputType = if (secret) InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD else InputType.TYPE_CLASS_TEXT
                setSingleLine()
            }
            val pad = dp(20f)
            val wrap = FrameLayout(context).apply { setPadding(pad, dp(8f), pad, 0); addView(input) }
            AlertDialog.Builder(context, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle(label).setView(wrap)
                .setPositiveButton("Save") { _, _ -> onSave(input.text.toString()); show(page) }
                .setNegativeButton("Cancel", null)
                .show()
        }
    }

    companion object { val OPACITIES = listOf(25, 50, 75, 100) }
}
