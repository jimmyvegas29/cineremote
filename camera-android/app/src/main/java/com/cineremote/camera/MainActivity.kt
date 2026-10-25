package com.cineremote.camera

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.hardware.display.DisplayManager
import android.os.BatteryManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.TextView
import org.json.JSONObject
import org.webrtc.RendererCommon
import org.webrtc.SurfaceViewRenderer
import java.net.Inet4Address
import java.net.NetworkInterface

/**
 * CineRemote Camera. A Blackmagic-style on-phone camera (preview + [CameraHud] + [SettingsPanel]) that
 * CineRemote can also find and control over the network. The phone is usable on its own; a connected
 * controller shares control.
 */
class MainActivity : Activity(), RemoteServer.Listener {

    private lateinit var prefs: Prefs
    private lateinit var engine: CameraEngine
    private lateinit var recorder: Recorder
    private lateinit var takes: Takes
    private lateinit var audio: AudioInput
    private var server: RemoteServer? = null
    private lateinit var tap: FrameTap
    private lateinit var aids: FocusAids
    private var session: ControllerSession? = null

    private val ui = Handler(Looper.getMainLooper())
    private lateinit var root: FrameLayout
    private lateinit var preview: SurfaceViewRenderer
    private lateinit var hud: CameraHud
    private lateinit var permNote: TextView
    private var settings: SettingsPanel? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        hideSystemBars()
        prefs = Prefs(this)
        buildUi()
        requestPermissions()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemBars()
    }

    private fun hideSystemBars() {
        window.decorView.systemUiVisibility = (View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                or View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_LAYOUT_STABLE or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION)
    }

    private fun requestPermissions() {
        val need = listOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)
            .filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (need.isEmpty()) onReady() else requestPermissions(need.toTypedArray(), 1)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        if (grantResults.isNotEmpty() && grantResults.all { it == PackageManager.PERMISSION_GRANTED }) onReady()
        else permNote.text = "Camera and microphone permission are required."
    }

    private var started = false
    private fun onReady() {
        if (started) return
        started = true
        permNote.visibility = View.GONE
        recorder = Recorder(this)
        engine = CameraEngine(this, prefs, object : CameraEngine.Listener {
            override fun onCameraChanged(fields: Set<String>) { ui.post { session?.emitChanged(fields); hud.invalidate() } }
            override fun onCameraError(message: String) { ui.post { toast(message) } }
        })
        audio = AudioInput(prefs)
        takes = Takes(engine, recorder, audio, prefs) {
            ui.post { session?.emitChanged(setOf("recording_state", "active_storage")); hud.invalidate() }
        }
        trackDisplayRotation()
        // The camera renders only into the frame tap, which feeds the on-screen preview, the stream and the
        // histogram. A separate preview output would make three camera streams while recording, and the
        // S20 drops to ~26 fps with three; with two it holds 30.
        tap = FrameTap(this) { engine.streamTransform() }
        // zebras and focus assist are drawn by the preview renderer's shader: on screen only
        aids = FocusAids(prefs)
        preview.init(tap.egl.eglBaseContext, null, org.webrtc.EglBase.CONFIG_PLAIN, aids.drawer())
        preview.setScalingType(RendererCommon.ScalingType.SCALE_ASPECT_FIT)
        tap.previewSink = preview
        engine.setStreamSurface(tap.surface)
        applyCaptureSize(engine.captureSize.width, engine.captureSize.height)
        audio.start()
        if (applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE != 0) {
            // debug builds: `adb shell am broadcast -a com.cineremote.camera.SNAP` saves the stream's next frame
            registerReceiver(object : android.content.BroadcastReceiver() {
                override fun onReceive(c: android.content.Context, i: Intent) { tap.snapshotTo = java.io.File(getExternalFilesDir(null), "tap.jpg") }
            }, android.content.IntentFilter("com.cineremote.camera.SNAP"), RECEIVER_EXPORTED)
        }
        engine.onCaptureSizeChanged = { size -> ui.post { applyCaptureSize(size.width, size.height) } }
        hud.engine = engine
        hud.recorder = recorder
        hud.prefs = prefs
        hud.audio = audio
        hud.aids = aids
        hud.histogram = { tap.histogram }
        hud.plannedBitrate = { takes.config().bitrate }
        hud.onRecordToggle = { toggleRecord() }
        hud.onSettings = { page -> if (page == CameraHud.CLOSE) closeSettings() else openSettings(page) }
        hud.onMedia = { openMedia() }
        hud.onRemote = { remoteDialog() }
        hud.onGainChanged = { p -> setGain(p) }
        hud.onParamChanged = { ui.post { session?.emitChanged(CameraEngine.ALL_FIELDS) } }

        if (prefs.remoteEnabled) startServer()
        ui.post(refreshRunnable)
        hud.connected = false
        hud.invalidate()
    }

    /** New capture size: the frame tap matches it, and the HUD re-fits the picture rectangle (which places
     *  the preview view, see [placePreview]). */
    private fun applyCaptureSize(w: Int, h: Int) {
        tap.setSize(w, h)
        hud.captureAspect = w.toFloat() / h
    }

    private fun placePreview(r: android.graphics.Rect) {
        preview.layoutParams = FrameLayout.LayoutParams(r.width(), r.height()).apply { leftMargin = r.left; topMargin = r.top }
    }

    /** Landscape can be either way up (sensorLandscape): keep the engine told, so stream and recordings stay upright. */
    private fun trackDisplayRotation() {
        val dm = getSystemService(DISPLAY_SERVICE) as DisplayManager
        fun update() { engine.displayRotation = (display?.rotation ?: 1) * 90 }
        update()
        dm.registerDisplayListener(object : DisplayManager.DisplayListener {
            override fun onDisplayChanged(id: Int) { update() }
            override fun onDisplayAdded(id: Int) {}
            override fun onDisplayRemoved(id: Int) {}
        }, ui)
    }

    // ------------------------------------------------------------------ remote control
    private fun startServer() {
        if (server != null) return
        server = RemoteServer(this, prefs, this).also { it.start() }
    }

    private fun stopServer() {
        session?.close("remote camera control turned off")
        server?.stop()
        server = null
    }

    override fun onControllerAuthorized(link: RemoteServer.SignalingLink, controllerInfo: JSONObject): Boolean {
        if (session != null) return false
        val s = ControllerSession(this, link, engine, recorder, takes, prefs, tap, audio, ::batteryPct) { done ->
            ui.post { if (session === done) { session = null; hud.connected = false; hud.invalidate() } }
        }
        s.onIdentify = { ui.post { flashScreen() } }
        session = s
        ui.post { s.start(); hud.connected = true; hud.invalidate() }
        return true
    }

    override fun onSignalingMessage(link: RemoteServer.SignalingLink, type: String, data: JSONObject) {
        ui.post { session?.onSignaling(type, data) }
    }

    override fun onSignalingClosed(link: RemoteServer.SignalingLink) {
        ui.post { session?.close("controller disconnected") }
    }

    // ------------------------------------------------------------------ local control
    private fun toggleRecord() {
        if (!takes.toggle()) toast("Couldn't start recording")
        hud.invalidate()
    }

    private fun setGain(percent: Int) {
        prefs.audioGain = percent
        audio.gain = percent / 100f
    }

    /** Volume buttons start/stop a take (Settings > Camera > Use Volume Button to Trigger Record). */
    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (started && prefs.volumeKeyRecords && (keyCode == KeyEvent.KEYCODE_VOLUME_UP || keyCode == KeyEvent.KEYCODE_VOLUME_DOWN)) {
            if (event.repeatCount == 0) toggleRecord()
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean =
        if (started && prefs.volumeKeyRecords && (keyCode == KeyEvent.KEYCODE_VOLUME_UP || keyCode == KeyEvent.KEYCODE_VOLUME_DOWN)) true
        else super.onKeyUp(keyCode, event)

    @Deprecated("Activity back handling")
    override fun onBackPressed() {
        if (settings != null) closeSettings() else @Suppress("DEPRECATION") super.onBackPressed()
    }

    private val refreshRunnable = object : Runnable {
        override fun run() {
            if (started) { hud.battery = (batteryPct() * 100).toInt(); hud.invalidate(); session?.emitHousekeeping() }
            ui.postDelayed(this, if (recorder.isRecording) 200 else 1000)
        }
    }

    // ------------------------------------------------------------------ settings
    private fun openSettings(page: String?) {
        val panel = settings ?: SettingsPanel(this, prefs, engine, aids, object : SettingsPanel.Host {
            override fun isRecording() = takes.active
            override fun onFormatChanged() { ui.postDelayed({ session?.emitChanged(setOf("recording_format", "cam_framerate", "cam_frame_rate_range")); hud.invalidate() }, 300) }
            override fun onAudioGainChanged(percent: Int) = setGain(percent)
            override fun onRemoteChanged() {
                if (prefs.remoteEnabled) { startServer(); server?.refreshAdvertisement() } else stopServer()
                hud.invalidate()
            }
            override fun onMonitorChanged() { hud.invalidate() }
            override fun onClose() = closeSettings()
        }).also { p ->
            // covers the camera area; the nav rail stays visible (Camera closes settings)
            val railW = (194f * root.height / 1080f).toInt()
            root.addView(p, FrameLayout.LayoutParams(root.width - railW, -1))
            settings = p
        }
        panel.show(page ?: "root")
        hud.settingsOpen = true
        hud.invalidate()
    }

    private fun closeSettings() {
        settings?.let { root.removeView(it) }
        settings = null
        hud.settingsOpen = false
        hud.invalidate()
    }

    // ------------------------------------------------------------------ misc
    private fun flashScreen() {
        val flash = View(this).apply { setBackgroundColor(Color.WHITE) }
        root.addView(flash, FrameLayout.LayoutParams(-1, -1))
        ui.postDelayed({ root.removeView(flash) }, 120)
    }

    private fun batteryPct(): Float {
        val bm = getSystemService(BATTERY_SERVICE) as BatteryManager
        return bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY).coerceIn(0, 100) / 100f
    }

    private fun toast(msg: String) = android.widget.Toast.makeText(this, msg, android.widget.Toast.LENGTH_SHORT).show()

    /** Media: open the newest clip in the phone's player. */
    private fun openMedia() {
        val uri = contentResolver.query(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, arrayOf(MediaStore.Video.Media._ID),
            "${MediaStore.Video.Media.RELATIVE_PATH} LIKE ?", arrayOf("%Movies/CineRemote%"),
            "${MediaStore.Video.Media.DATE_ADDED} DESC")?.use { c ->
            if (c.moveToFirst()) android.content.ContentUris.withAppendedId(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, c.getLong(0)) else null
        }
        if (uri == null) { toast("No clips yet"); return }
        runCatching {
            startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, "video/mp4").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
        }.onFailure { toast("No video player found") }
    }

    /** Remote: how CineRemote finds this camera, and whether it is connected. */
    private fun remoteDialog() {
        val ips = runCatching {
            NetworkInterface.getNetworkInterfaces().toList().filter { it.isUp && !it.isLoopback }
                .flatMap { it.inetAddresses.toList() }.filterIsInstance<Inet4Address>().map { it.hostAddress }
        }.getOrDefault(emptyList())
        val msg = buildString {
            append("Camera: ${prefs.deviceName}\n")
            append("Address: ${ips.joinToString().ifEmpty { "no network" }}  (port 2222)\n\n")
            append(when {
                !prefs.remoteEnabled -> "Remote Camera Control is off (Settings)."
                session != null -> "Connected to CineRemote."
                else -> "Waiting for CineRemote. It finds this camera automatically on the same Wi-Fi."
            })
            if (prefs.password == Prefs.DEFAULT_PASSWORD) append("\n\nUsing the default password - change it in Settings.")
        }
        AlertDialog.Builder(this).setTitle("Remote").setMessage(msg).setPositiveButton("OK", null).show()
    }

    private fun buildUi() {
        // No opaque root background (it would paint over the SurfaceView hole-punch). The fullscreen theme
        // gives a black window, so the letterbox bars are black.
        root = FrameLayout(this)
        preview = SurfaceViewRenderer(this)
        root.addView(preview, FrameLayout.LayoutParams(1, 1))     // placed by the HUD's picture rectangle
        hud = CameraHud(this)
        hud.onPreviewRect = { r -> placePreview(r) }
        root.addView(hud, FrameLayout.LayoutParams(-1, -1))
        permNote = TextView(this).apply {
            text = "Starting camera…"; setTextColor(Color.WHITE); textSize = 14f; setPadding(40, 40, 40, 40)
        }
        root.addView(permNote, FrameLayout.LayoutParams(-2, -2, Gravity.CENTER))
        setContentView(root)
    }

    override fun onDestroy() {
        super.onDestroy()
        ui.removeCallbacksAndMessages(null)
        session?.close("activity destroyed")
        server?.stop()
        if (::recorder.isInitialized) { recorder.forceStopIfNeeded(); recorder.release() }
        if (::audio.isInitialized) audio.stop()
        if (::engine.isInitialized) engine.release()
        if (::tap.isInitialized) { tap.previewSink = null; preview.release(); tap.handler.post { tap.release() } }
    }
}
