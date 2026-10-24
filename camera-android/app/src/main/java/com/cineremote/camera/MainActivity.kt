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
import android.view.MotionEvent
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import org.json.JSONObject

/**
 * CineRemote Camera: turns this phone into a camera CineRemote can find and control. Shows a live preview
 * and a small overlay (name, how to connect, record state); everything else is driven remotely.
 */
class MainActivity : Activity(), RemoteServer.Listener {

    private lateinit var prefs: Prefs
    private lateinit var engine: CameraEngine
    private lateinit var recorder: Recorder
    private lateinit var server: RemoteServer
    private var session: ControllerSession? = null

    private val ui = Handler(Looper.getMainLooper())
    private lateinit var preview: SurfaceView
    private lateinit var status: TextView
    private lateinit var recDot: TextView
    private lateinit var title: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        prefs = Prefs(this)
        buildUi()
        requestPermissions()
    }

    private fun requestPermissions() {
        val need = listOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)
            .filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (need.isEmpty()) onReady() else requestPermissions(need.toTypedArray(), 1)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        if (grantResults.isNotEmpty() && grantResults.all { it == PackageManager.PERMISSION_GRANTED }) onReady()
        else status.text = "Camera and microphone permission are required."
    }

    private var started = false
    private fun onReady() {
        if (started) return
        started = true
        recorder = Recorder(this)
        engine = CameraEngine(this, prefs, object : CameraEngine.Listener {
            override fun onCameraChanged(fields: Set<String>) { ui.post { session?.emitChanged(fields) } }
            override fun onCameraError(message: String) { ui.post { status.text = message } }
        })
        preview.holder.setFixedSize(1920, 1080)      // a camera-supported preview buffer size (16:9)
        preview.holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(h: SurfaceHolder) { engine.setPreviewSurface(h.surface) }
            override fun surfaceChanged(h: SurfaceHolder, f: Int, w: Int, ht: Int) {}
            override fun surfaceDestroyed(h: SurfaceHolder) { engine.setPreviewSurface(null) }
        })
        server = RemoteServer(this, prefs, this)
        server.start()
        ui.post(refreshRunnable)
        updateOverlay()
    }

    // ------------------------------------------------------------------ RemoteServer.Listener (any thread)
    override fun onControllerAuthorized(link: RemoteServer.SignalingLink, controllerInfo: JSONObject): Boolean {
        if (session != null) return false
        val s = ControllerSession(this, link, engine, recorder, prefs, ::batteryPct) { done ->
            ui.post { if (session === done) { session = null; updateOverlay() } }
        }
        s.onIdentify = { ui.post { flashScreen() } }
        session = s
        ui.post { s.start(); updateOverlay() }
        return true
    }

    override fun onSignalingMessage(link: RemoteServer.SignalingLink, type: String, data: JSONObject) {
        ui.post { session?.onSignaling(type, data) }
    }

    override fun onSignalingClosed(link: RemoteServer.SignalingLink) {
        ui.post { session?.close("controller disconnected") }
    }

    // ------------------------------------------------------------------ overlay + settings
    private fun updateOverlay() {
        title.text = "${prefs.deviceName}  ·  ${prefs.slate}"
        val connected = session != null
        status.text = if (connected) "Connected to CineRemote"
        else "Ready · CineRemote finds this camera automatically\nName “${prefs.deviceName}”   Password set in Settings"
        status.setTextColor(if (connected) 0xFF8AE28A.toInt() else Color.WHITE)
    }

    private val refreshRunnable = object : Runnable {
        override fun run() {
            recDot.visibility = if (recorder.isRecording) View.VISIBLE else View.GONE
            session?.emitHousekeeping()
            ui.postDelayed(this, 2000)
        }
    }

    private fun flashScreen() {
        val f = preview.rootView as FrameLayout
        val flash = View(this).apply { setBackgroundColor(Color.WHITE) }
        f.addView(flash, FrameLayout.LayoutParams(-1, -1))
        ui.postDelayed({ f.removeView(flash) }, 150)
        ui.postDelayed({ f.addView(flash); ui.postDelayed({ f.removeView(flash) }, 150) }, 300)
    }

    private fun batteryPct(): Float {
        val bm = getSystemService(BATTERY_SERVICE) as BatteryManager
        return bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY).coerceIn(0, 100) / 100f
    }

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
                server.refreshAdvertisement()
                updateOverlay()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun buildUi() {
        // No opaque root background: it would paint over the SurfaceView's hole-punch region (black preview).
        // The fullscreen theme already gives a black window, so the letterbox bars are black.
        val root = FrameLayout(this)

        preview = SurfaceView(this)
        // 16:9 preview, centered and letterboxed in the (taller/wider) screen.
        val dm = resources.displayMetrics
        val pw: Int; val ph: Int
        if (dm.widthPixels * 9 > dm.heightPixels * 16) { ph = dm.heightPixels; pw = ph * 16 / 9 }
        else { pw = dm.widthPixels; ph = pw * 9 / 16 }
        root.addView(preview, FrameLayout.LayoutParams(pw, ph, Gravity.CENTER))
        preview.setOnTouchListener { v, e ->
            if (e.action == MotionEvent.ACTION_UP) engine.tapFocus((e.x / v.width).coerceIn(0f, 1f), (e.y / v.height).coerceIn(0f, 1f))
            true
        }

        title = TextView(this).apply {
            setTextColor(Color.WHITE); textSize = 15f; setShadowLayer(4f, 0f, 1f, Color.BLACK)
            setPadding(24, 20, 24, 8)
        }
        status = TextView(this).apply {
            setTextColor(Color.WHITE); textSize = 13f; setShadowLayer(4f, 0f, 1f, Color.BLACK)
            setPadding(24, 0, 24, 20)
        }
        val topBar = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; addView(title); addView(status) }
        root.addView(topBar, FrameLayout.LayoutParams(-1, -2, Gravity.TOP or Gravity.START))

        recDot = TextView(this).apply {
            text = "● REC"; setTextColor(0xFFE5322D.toInt()); textSize = 15f
            setShadowLayer(4f, 0f, 1f, Color.BLACK); setPadding(24, 20, 24, 8); visibility = View.GONE
        }
        root.addView(recDot, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.END))

        val gear = Button(this).apply { text = "Settings"; setOnClickListener { settingsDialog() } }
        root.addView(gear, FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.END).apply { setMargins(0, 0, 24, 24) })

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
