package com.cineremote.camera

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.BatteryManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.Gravity
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.view.WindowManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import org.json.JSONObject

/**
 * CineRemote Camera. A Blackmagic-style on-phone camera (preview + [CameraHud]) that CineRemote can also
 * find and control over the network. The phone is usable on its own; a connected controller shares control.
 */
class MainActivity : Activity(), RemoteServer.Listener {

    private lateinit var prefs: Prefs
    private lateinit var engine: CameraEngine
    private lateinit var recorder: Recorder
    private lateinit var server: RemoteServer
    private var session: ControllerSession? = null

    private val ui = Handler(Looper.getMainLooper())
    private lateinit var root: FrameLayout
    private lateinit var preview: SurfaceView
    private lateinit var hud: CameraHud
    private lateinit var permNote: TextView

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
        engine.onCaptureSizeChanged = { size -> ui.post { applyCaptureSize(size.width, size.height) } }
        hud.engine = engine
        hud.recorder = recorder
        hud.deviceName = prefs.deviceName
        hud.onRecordToggle = { toggleRecordLocal() }
        hud.onSettings = { settingsDialog() }
        hud.onParamChanged = { ui.post { session?.emitChanged(CameraEngine.ALL_FIELDS) } }

        preview.holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(h: SurfaceHolder) {
                applyCaptureSize(engine.captureSize.width, engine.captureSize.height)
                engine.setPreviewSurface(h.surface)
            }
            override fun surfaceChanged(h: SurfaceHolder, f: Int, w: Int, ht: Int) {}
            override fun surfaceDestroyed(h: SurfaceHolder) { engine.setPreviewSurface(null) }
        })
        // If the surface already exists (created before this callback was added), start now.
        if (preview.holder.surface?.isValid == true) {
            applyCaptureSize(engine.captureSize.width, engine.captureSize.height)
            engine.setPreviewSurface(preview.holder.surface)
        }
        server = RemoteServer(this, prefs, this)
        server.start()
        ui.post(refreshRunnable)
        hud.connected = false
        hud.invalidate()
    }

    /** Size the preview SurfaceView to the capture aspect, letterboxed and centered (no stretch). */
    private fun applyCaptureSize(w: Int, h: Int) {
        preview.holder.setFixedSize(w, h)
        val dm = resources.displayMetrics
        val vw: Int; val vh: Int
        if (dm.widthPixels * h > dm.heightPixels * w) { vh = dm.heightPixels; vw = vh * w / h }
        else { vw = dm.widthPixels; vh = vw * h / w }
        preview.layoutParams = FrameLayout.LayoutParams(vw, vh, Gravity.CENTER)
        session?.setStreamSize(w, h)
    }

    // ------------------------------------------------------------------ RemoteServer.Listener
    override fun onControllerAuthorized(link: RemoteServer.SignalingLink, controllerInfo: JSONObject): Boolean {
        if (session != null) return false
        val s = ControllerSession(this, link, engine, recorder, prefs, ::batteryPct) { done ->
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
    private fun toggleRecordLocal() {
        if (recorder.isRecording) {
            engine.setRecordSurface(null) { recorder.stop(); ui.post { session?.emitChanged(setOf("recording_state")); hud.invalidate() } }
        } else {
            val surface = recorder.prepare(engine.fps, engine.captureSize) ?: run { toast("couldn't start recording"); return }
            engine.setRecordSurface(surface) { recorder.start(); ui.post { session?.emitChanged(setOf("recording_state")); hud.invalidate() } }
        }
    }

    private val refreshRunnable = object : Runnable {
        override fun run() {
            if (started) { hud.battery = (batteryPct() * 100).toInt(); hud.invalidate(); session?.emitHousekeeping() }
            ui.postDelayed(this, if (recorder.isRecording) 200 else 1000)
        }
    }

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

    private fun settingsDialog() {
        val pad = (16 * resources.displayMetrics.density).toInt()
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(pad, pad, pad, 0) }
        fun field(label: String, value: String, pw: Boolean = false): EditText {
            box.addView(TextView(this).apply { text = label })
            return EditText(this).apply {
                setText(value)
                if (pw) inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
                box.addView(this)
            }
        }
        val name = field("Camera name", prefs.deviceName)
        val slate = field("Camera letter", prefs.slate)
        val pass = field("Remote password", prefs.password, pw = true)
        AlertDialog.Builder(this)
            .setTitle("CineRemote Camera")
            .setView(box)
            .setPositiveButton("Save") { _, _ ->
                prefs.deviceName = name.text.toString()
                prefs.slate = slate.text.toString()
                prefs.password = pass.text.toString().ifBlank { prefs.password }
                hud.deviceName = prefs.deviceName
                if (::server.isInitialized) server.refreshAdvertisement()
                hud.invalidate()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun buildUi() {
        // No opaque root background (it would paint over the SurfaceView hole-punch). The fullscreen theme
        // gives a black window, so the letterbox bars are black.
        root = FrameLayout(this)
        preview = SurfaceView(this)
        root.addView(preview, FrameLayout.LayoutParams(1, 1, Gravity.CENTER))   // resized in applyCaptureSize
        hud = CameraHud(this)
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
        if (::server.isInitialized) server.stop()
        if (::recorder.isInitialized) { recorder.forceStopIfNeeded(); recorder.release() }
        if (::engine.isInitialized) engine.release()
    }
}
