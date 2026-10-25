package com.cineremote.camera

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Rect
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.TotalCaptureResult
import android.hardware.camera2.params.ColorSpaceTransform
import android.hardware.camera2.params.MeteringRectangle
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.RggbChannelVector
import android.hardware.camera2.params.SessionConfiguration
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.util.Log
import android.util.Range
import android.util.SizeF
import android.view.Surface
import java.util.concurrent.Executor
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Camera2 pipeline + every manual control CineRemote exposes. Outputs: on-screen preview, the WebRTC stream
 * texture, and (only while recording) the recorder's persistent surface - targeting it while the recorder
 * isn't consuming would stall the whole camera.
 */
class CameraEngine(context: Context, private val prefs: Prefs, private val listener: Listener) {

    interface Listener {
        /** Settings or live readings changed: the remote should be told about these property fields. */
        fun onCameraChanged(fields: Set<String>)
        fun onCameraError(message: String)
    }

    data class Lens(
        val id: String, val facing: Int, val focalMm: Float, val equivMm: Int, val zoomVsMain: Float,
        val type: Int, val chars: CameraCharacteristics,
    )

    private val manager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
    private val thread = HandlerThread("camera").apply { start() }
    val handler = Handler(thread.looper)
    private val executor = Executor { handler.post(it) }

    val lenses: List<Lens> = enumerateLenses()
    @Volatile var lens: Lens = lenses.firstOrNull { it.id == prefs.cameraId && it.facing == CameraCharacteristics.LENS_FACING_BACK }
        ?: lenses.firstOrNull { it.type == LENS_MAIN } ?: lenses.first { it.facing == CameraCharacteristics.LENS_FACING_BACK }
        private set

    // ---- requested settings (what the remote/user set)
    @Volatile var isoAuto = true; @Volatile var iso = 400
    @Volatile var shutterAuto = true; @Volatile var shutterDen = 50          // 1/x s
    @Volatile var wbAuto = true; @Volatile var kelvin = 5600; @Volatile var tint = 0
    @Volatile var evStops = 0f
    @Volatile var afAuto = true; @Volatile var focus01 = 0f; @Volatile var focusLockedByTap = false
    @Volatile var zoom = 1f
    @Volatile var fps = prefs.fps
    @Volatile var torch = false
    @Volatile var stabilization = true
    /** Auto-exposure / auto-white-balance hold (the lock buttons; WB is also held during takes if set). */
    @Volatile var aeLock = false
    @Volatile var awbLock = false
    @Volatile private var awbLockForTake = false
    fun lockWbForTake(on: Boolean) { awbLockForTake = on; apply() }

    // ---- live readings (from capture results)
    @Volatile var liveIso = 0; @Volatile var liveExposureNs = 0L; @Volatile var liveKelvin = 5600
    @Volatile var liveFocus01 = 0f
    private var liveGains: RggbChannelVector? = null
    private var liveCcm: ColorSpaceTransform? = null
    private var lastReportMs = 0L
    private var lastReported = IntArray(4)

    private var device: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private var streamSurface: Surface? = null
    private var recordSurface: Surface? = null
    @Volatile var recording = false
        private set

    fun handlerPost(block: () -> Unit) = handler.post(block)

    /** Set the frame rate and remember it. */
    fun selectFps(v: Int) { fps = v.coerceIn(1, 240); prefs.fps = fps }

    // ------------------------------------------------------------------ lenses
    private fun enumerateLenses(): List<Lens> {
        val raw = manager.cameraIdList.mapNotNull { id ->
            val c = manager.getCameraCharacteristics(id)
            val caps = c.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES) ?: intArrayOf()
            if (CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_BACKWARD_COMPATIBLE !in caps) return@mapNotNull null
            val focal = c.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)?.firstOrNull() ?: 4f
            val size = c.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE) ?: SizeF(6.4f, 4.8f)
            val equiv = (focal * 43.27f / sqrt(size.width * size.width + size.height * size.height)).roundToInt()
            Triple(id, c, equiv)
        }
        val back = raw.filter { it.second.get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK }
        val mainEquiv = (back.firstOrNull { it.first == "0" } ?: back.minByOrNull { abs(it.third - 26) })?.third ?: 26
        val list = raw.map { (id, c, equiv) ->
            val facing = c.get(CameraCharacteristics.LENS_FACING) ?: CameraCharacteristics.LENS_FACING_BACK
            val factor = ((equiv.toFloat() / mainEquiv) * 10).roundToInt() / 10f
            val type = when {
                facing == CameraCharacteristics.LENS_FACING_FRONT -> LENS_FRONT
                factor < 0.85f -> LENS_ULTRA_WIDE
                factor > 1.4f -> LENS_TELE
                else -> LENS_MAIN
            }
            Lens(id, facing, c.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)?.firstOrNull() ?: 0f,
                 equiv, factor, type, c)
        }.distinctBy { "${it.facing}/${it.equivMm}" }       // Samsung lists some sensors twice
        list.forEach { Log.i(TAG, "lens ${it.id}: facing=${it.facing} ${it.focalMm}mm eq ${it.equivMm}mm x${it.zoomVsMain} type=${it.type}") }
        return list.sortedWith(compareBy({ it.facing == CameraCharacteristics.LENS_FACING_FRONT }, { it.equivMm }))
    }

    /** The recording format (Settings > Record > Resolution) resolved for the current lens; the camera
     *  outputs exactly this size to the frame tap and the recorder, so nothing is ever stretched. */
    @Volatile var format: FrameFormat = Formats.resolve(lens, prefs)
        private set
    val captureSize: android.util.Size get() = format.size

    /** Choose a resolution bucket and frame size (or re-resolve after a codec change). Not during a take. */
    fun setFormat(bucket: String? = null, key: String? = null) = handler.post {
        if (recording) return@post
        bucket?.let { prefs.resolution = it }
        key?.let { prefs.frameSize = it }
        val f = Formats.resolve(lens, prefs)
        if (f == format) return@post
        format = f
        if (fps !in fpsChoices) selectFps(fpsChoices.lastOrNull { it <= 30 } ?: fpsChoices.firstOrNull() ?: 30)
        onCaptureSizeChanged?.invoke(captureSize)
        reopen()
        listener.onCameraChanged(ALL_FIELDS + "recording_format")
    }

    val chars get() = lens.chars
    val isoRange: Range<Int> get() = chars.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE) ?: Range(50, 3200)
    val exposureRange: Range<Long> get() = chars.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE) ?: Range(100_000L, 100_000_000L)
    val zoomRange: Range<Float> get() = chars.get(CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE) ?: Range(1f, 1f)
    val minFocusDiopters: Float get() = chars.get(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE) ?: 0f
    val evRange: Pair<Float, Float> get() {
        val r = chars.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_RANGE) ?: Range(0, 0)
        val step = chars.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_STEP)?.toFloat() ?: 1f
        return r.lower * step to r.upper * step
    }
    /** Frame rates the lens, the current format's size and the encoder all support. */
    val fpsChoices: List<Int> get() {
        val ranges = chars.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES) ?: arrayOf(Range(30, 30))
        val minFrameNs = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            ?.getOutputMinFrameDuration(android.graphics.SurfaceTexture::class.java, captureSize) ?: 0L
        val caps = Formats.encoderCaps(prefs.hevc)
        return listOf(24, 25, 30, 48, 50, 60).filter { f ->
            ranges.any { it.lower <= f && it.upper >= f } && (minFrameNs <= 0 || minFrameNs <= 1_000_000_000L / f) &&
                (caps?.areSizeAndRateSupported(captureSize.width, captureSize.height, f.toDouble()) ?: true)
        }.ifEmpty { listOf(30) }
    }
    // ---- off-speed (slow motion): the sensor runs faster than the project frame rate
    @Volatile var offSpeed = false
    @Volatile var offSpeedFps = prefs.offSpeedFps
    /** The rate the sensor actually runs at. */
    val sensorFps: Int get() = if (offSpeed) offSpeedFps else fps
    /** High-speed rates (120 fps etc.) this lens offers at the current frame size (constrained high-speed mode). */
    private val highSpeedFps: List<Int> get() {
        val map = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP) ?: return emptyList()
        val caps = chars.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES) ?: return emptyList()
        if (CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_CONSTRAINED_HIGH_SPEED_VIDEO !in caps) return emptyList()
        if (map.highSpeedVideoSizes.none { it == captureSize }) return emptyList()
        // the hardware encoder must keep up too (the camera may advertise rates it can't record)
        val enc = Formats.encoderCaps(prefs.hevc)
        return map.getHighSpeedVideoFpsRangesFor(captureSize).filter { it.lower == it.upper }.map { it.upper }.distinct().sorted()
            .filter { f -> enc?.areSizeAndRateSupported(captureSize.width, captureSize.height, f.toDouble()) ?: false }
            .filter { it !in failedHighSpeed }
    }
    /** Off-speed rates above the project rate: normal-session rates, then high-speed ones. */
    val offSpeedChoices: List<Int> get() = (fpsChoices.filter { it > fps } + highSpeedFps.filter { it > fps }).distinct().sorted()
    /** True when the current off-speed rate needs the constrained high-speed session. */
    val highSpeed: Boolean get() = offSpeed && offSpeedFps > (fpsChoices.maxOrNull() ?: 30) && offSpeedFps in highSpeedFps

    fun setOffSpeed(on: Boolean, rate: Int? = null) = handler.post {
        if (recording) return@post
        val wasHigh = highSpeed
        rate?.let { offSpeedFps = it; prefs.offSpeedFps = it }
        offSpeed = on && offSpeedChoices.isNotEmpty()
        if (offSpeed && offSpeedFps !in offSpeedChoices) offSpeedFps = offSpeedChoices.firstOrNull { it >= 60 } ?: offSpeedChoices.last()
        Log.i(TAG, "off-speed $offSpeed at $offSpeedFps (choices $offSpeedChoices, project $fps, normal $fpsChoices)")
        if (wasHigh != highSpeed) reopen() else applyRepeating()
        listener.onCameraChanged(setOf("cam_framerate"))
    }

    /** High-speed rates that failed on this phone (camera error): not offered again this run. */
    private val failedHighSpeed = HashSet<Int>()
    private var errorReopens = 0
    private var hsEmpty = 0
    /** False while a high-speed take runs with the encoder as the only output (see [createSession]). */
    @Volatile var streamInSession = true
        private set

    /** High-speed [rate] doesn't really work on this phone: stop offering it and go back to normal speed. */
    fun markHighSpeedFailed(rate: Int) = handler.post {
        if (rate in failedHighSpeed) return@post
        Log.w(TAG, "high-speed $rate fps doesn't work here")
        failedHighSpeed += rate
        if (offSpeed && offSpeedFps == rate) { offSpeed = false; if (!recording) reopen() }
        listener.onCameraError("$rate fps isn't available on this phone")
        listener.onCameraChanged(setOf("cam_framerate"))
    }

    /** The camera errored or a session failed: drop a failing high-speed rate, then reopen (a few tries). */
    private fun recover(why: String) {
        Log.w(TAG, "recovering: $why")
        if (highSpeed) { failedHighSpeed += offSpeedFps; offSpeed = false; listener.onCameraError("${offSpeedFps} fps isn't available here") }
        if (errorReopens++ < 3) handler.postDelayed({ reopen() }, 800) else listener.onCameraError(why)
    }

    // ---- timelapse: the recorder gets one frame every [timelapseMs] instead of every frame
    @Volatile var timelapseMs = 0L
        private set

    // ---- face detection (boxes on the preview; autofocus/exposure follow the largest face when AF is auto)
    val faceDetectSupported: Boolean get() =
        (chars.get(CameraCharacteristics.STATISTICS_INFO_AVAILABLE_FACE_DETECT_MODES) ?: intArrayOf()).any { it != 0 }
    @Volatile var faceDetect = prefs.faceDetect
        private set
    /** Detected faces as rectangles in picture coordinates (0..1), largest first. */
    @Volatile var faces: List<android.graphics.RectF> = emptyList()
        private set
    private var faceRegion: Rect? = null
    private var faceRegionAt = 0L

    /** Fixed lens aperture (phones have no iris), shown greyed out like the Blackmagic app's IRIS. */
    val aperture: Float get() = chars.get(CameraCharacteristics.LENS_INFO_AVAILABLE_APERTURES)?.firstOrNull() ?: 0f

    // ---- orientation
    /** Display rotation in degrees (90 or 270 in landscape); MainActivity keeps it current. */
    @Volatile var displayRotation = 90
    val isFront: Boolean get() = lens.facing == CameraCharacteristics.LENS_FACING_FRONT
    private val sensorOrientation: Int get() = chars.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 90
    /** Clockwise degrees the raw sensor image must turn to stand upright (0, or 180 with the phone flipped). */
    val uprightDegrees: Int get() =
        if (isFront) (sensorOrientation + displayRotation) % 360 else (sensorOrientation - displayRotation + 360) % 360
    /** (degrees, mirror) for [FrameTap]: undo the sensor rotation (and the front camera's mirror) that Camera2
     *  puts in a SurfaceTexture's transform, then stand the picture upright - matching the recorded file. */
    fun streamTransform(): Pair<Int, Boolean> = Pair(-sensorOrientation + uprightDegrees, isFront)

    val hasTorch: Boolean get() = hasTorchFor(lens)
    fun hasTorchFor(l: Lens): Boolean = l.chars.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true

    // ------------------------------------------------------------------ open / close
    private var pendingOnConfigured: (() -> Unit)? = null

    fun setStreamSurface(s: Surface?) = handler.post { streamSurface = s; reopen() }

    /** Add/remove the recorder surface and rebuild the session; [onReady] runs once it is configured.
     *  With [timelapseMs] > 0 the recorder isn't in the repeating request: it gets one capture per interval. */
    fun setRecordSurface(s: Surface?, timelapseMs: Long = 0, onReady: () -> Unit = {}) = handler.post {
        recordSurface = s
        recording = s != null
        this.timelapseMs = if (s != null) timelapseMs else 0
        handler.removeCallbacks(timelapseTick)
        pendingOnConfigured = {
            onReady()
            if (recording && this.timelapseMs > 0) handler.post(timelapseTick)
        }
        reopen()
    }

    private val timelapseTick = object : Runnable {
        override fun run() {
            val s = session; val rs = recordSurface
            if (!recording || timelapseMs <= 0 || s == null || rs == null) return
            buildRequest()?.let { b ->
                b.addTarget(rs)
                runCatching { s.capture(b.build(), results, handler) }.onFailure { Log.w(TAG, "timelapse capture", it) }
            }
            handler.postDelayed(this, timelapseMs)
        }
    }

    /** Notified (on the camera thread) when the capture size changes, so preview/stream can be resized. */
    var onCaptureSizeChanged: ((android.util.Size) -> Unit)? = null

    fun switchLens(id: String) = handler.post {
        val l = lenses.firstOrNull { it.id == id } ?: return@post
        if (l.id == lens.id) return@post
        lens = l
        if (l.facing == CameraCharacteristics.LENS_FACING_BACK) prefs.cameraId = id
        zoom = 1f
        format = Formats.resolve(l, prefs)
        if (fps !in fpsChoices) fps = fpsChoices.lastOrNull { it <= 30 } ?: 30
        onCaptureSizeChanged?.invoke(captureSize)
        reopen()
        listener.onCameraChanged(ALL_FIELDS)
    }

    @SuppressLint("MissingPermission")
    private fun reopen() {
        if (streamSurface == null) return
        closeDevice()
        try {
            manager.openCamera(lens.id, object : CameraDevice.StateCallback() {
                override fun onOpened(d: CameraDevice) { device = d; createSession() }
                override fun onDisconnected(d: CameraDevice) { d.close(); if (device == d) device = null }
                override fun onError(d: CameraDevice, error: Int) {
                    d.close(); if (device == d) device = null
                    recover("camera error $error")
                }
            }, handler)
        } catch (e: Exception) {
            listener.onCameraError("can't open camera: ${e.message}")
        }
    }

    private fun closeDevice() {
        runCatching { session?.close() }
        session = null
        runCatching { device?.close() }
        device = null
    }

    fun close() = handler.post { closeDevice() }

    private fun createSession() {
        val d = device ?: return
        // A high-speed take feeds only the encoder: Samsung's HAL can't build its preview+video high-frame-rate
        // pipeline (autofocus stage fails), so the screen and stream pause for the take.
        streamInSession = !(highSpeed && recordSurface != null)
        val outs = listOfNotNull(if (streamInSession) streamSurface else null, recordSurface).map { OutputConfiguration(it) }
        val type = if (highSpeed) SessionConfiguration.SESSION_HIGH_SPEED else SessionConfiguration.SESSION_REGULAR
        val cfg = SessionConfiguration(type, outs, executor,
            object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(s: CameraCaptureSession) {
                    session = s; errorReopens = 0; applyRepeating()
                    pendingOnConfigured?.let { it(); pendingOnConfigured = null }
                }
                override fun onConfigureFailed(s: CameraCaptureSession) = recover("camera session failed")
            })
        try {
            d.createCaptureSession(cfg)
        } catch (e: Exception) {
            listener.onCameraError("camera session: ${e.message}")
        }
    }

    // ------------------------------------------------------------------ requests
    /** Re-issue the repeating request with the current settings (call after any change). */
    fun apply() = handler.post { applyRepeating() }

    fun setRecordingTarget(on: Boolean) = handler.post { recording = on; applyRepeating() }

    private fun buildRequest(template: Int = CameraDevice.TEMPLATE_RECORD): CaptureRequest.Builder? {
        val d = device ?: return null
        val b = d.createCaptureRequest(template)
        if (streamInSession) streamSurface?.let { b.addTarget(it) }
        if (recording && timelapseMs <= 0) recordSurface?.let { b.addTarget(it) }
        b.set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO)
        b.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, Range(sensorFps, sensorFps))
        if (highSpeed) {
            // constrained high-speed mode honours only automatic 3A, regions and zoom
            b.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
            val step = chars.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_STEP)?.toFloat() ?: 1f
            b.set(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION, (evStops / step).roundToInt())
            b.set(CaptureRequest.CONTROL_AWB_MODE, CaptureRequest.CONTROL_AWB_MODE_AUTO)
            b.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO)
            b.set(CaptureRequest.CONTROL_ZOOM_RATIO, zoom.coerceIn(zoomRange.lower, zoomRange.upper))
            return b
        }
        val frameNs = 1_000_000_000L / sensorFps
        if (!isoAuto || !shutterAuto) {
            // Camera2 has no "half manual" exposure: freeze the automatic half at its last value
            val s = if (isoAuto) (if (liveIso > 0) liveIso else iso) else iso
            val e = if (shutterAuto) (if (liveExposureNs > 0) liveExposureNs else frameNs / 2) else 1_000_000_000L / shutterDen
            b.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF)
            b.set(CaptureRequest.SENSOR_SENSITIVITY, s.coerceIn(isoRange.lower, isoRange.upper))
            b.set(CaptureRequest.SENSOR_EXPOSURE_TIME, e.coerceIn(exposureRange.lower, minOf(exposureRange.upper, frameNs)))
            b.set(CaptureRequest.SENSOR_FRAME_DURATION, frameNs)
        } else {
            b.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
            val step = chars.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_STEP)?.toFloat() ?: 1f
            b.set(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION, (evStops / step).roundToInt())
            b.set(CaptureRequest.CONTROL_AE_LOCK, aeLock)
        }
        if (wbAuto) {
            b.set(CaptureRequest.CONTROL_AWB_MODE, CaptureRequest.CONTROL_AWB_MODE_AUTO)
            b.set(CaptureRequest.CONTROL_AWB_LOCK, awbLock || awbLockForTake)
        } else {
            b.set(CaptureRequest.CONTROL_AWB_MODE, CaptureRequest.CONTROL_AWB_MODE_OFF)
            b.set(CaptureRequest.COLOR_CORRECTION_MODE, CaptureRequest.COLOR_CORRECTION_MODE_TRANSFORM_MATRIX)
            b.set(CaptureRequest.COLOR_CORRECTION_GAINS, Wb.gains(kelvin, tint))
            liveCcm?.let { b.set(CaptureRequest.COLOR_CORRECTION_TRANSFORM, it) }   // keep the sensor's own matrix
        }
        when {
            focusLockedByTap -> b.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_AUTO)
            afAuto -> b.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO)
            else -> {
                b.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_OFF)
                b.set(CaptureRequest.LENS_FOCUS_DISTANCE, focus01 * minFocusDiopters)
            }
        }
        b.set(CaptureRequest.CONTROL_ZOOM_RATIO, zoom.coerceIn(zoomRange.lower, zoomRange.upper))
        if (faceDetect && faceDetectSupported) {
            val modes = chars.get(CameraCharacteristics.STATISTICS_INFO_AVAILABLE_FACE_DETECT_MODES) ?: intArrayOf()
            b.set(CaptureRequest.STATISTICS_FACE_DETECT_MODE, modes.maxOrNull() ?: 0)
            val fr = faceRegion
            if (fr != null && afAuto && !focusLockedByTap) {
                val region = arrayOf(MeteringRectangle(fr, MeteringRectangle.METERING_WEIGHT_MAX - 1))
                b.set(CaptureRequest.CONTROL_AF_REGIONS, region)
                b.set(CaptureRequest.CONTROL_AE_REGIONS, region)
            }
        }
        b.set(CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE,
              if (stabilization) CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_ON else CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_OFF)
        b.set(CaptureRequest.FLASH_MODE, if (torch) CaptureRequest.FLASH_MODE_TORCH else CaptureRequest.FLASH_MODE_OFF)
        return b
    }

    private fun applyRepeating() {
        val s = session ?: return
        val b = buildRequest() ?: return
        try {
            if (s is android.hardware.camera2.CameraConstrainedHighSpeedCaptureSession)
                s.setRepeatingBurst(s.createHighSpeedRequestList(b.build()), results, handler)
            else s.setRepeatingRequest(b.build(), results, handler)
        } catch (e: Exception) {
            Log.w(TAG, "repeating request", e)
        }
    }

    /** One-shot AF/AE at a point (0..1 in the picture as streamed, landscape). Leaves focus locked there. */
    fun tapFocus(x: Float, y: Float) = handler.post {
        val s = session ?: return@post
        if (highSpeed) return@post                          // high-speed mode takes no one-shot captures
        val active = chars.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE) ?: return@post
        val pr = pictureRegion(active)
        // the picture is the sensor image turned by uprightDegrees (0 or 180 in landscape): map back
        val sx = if (uprightDegrees == 180) 1f - x else x; val sy = if (uprightDegrees == 180) 1f - y else y
        val cx = pr.left + sx * pr.width(); val cy = pr.top + sy * pr.height()
        val half = (active.width() * 0.05f).toInt()
        val r = Rect((cx - half).toInt(), (cy - half).toInt(), (cx + half).toInt(), (cy + half).toInt())
        r.intersect(active)
        val region = arrayOf(MeteringRectangle(r, MeteringRectangle.METERING_WEIGHT_MAX - 1))
        focusLockedByTap = true
        afAuto = false
        val b = buildRequest() ?: return@post
        b.set(CaptureRequest.CONTROL_AF_REGIONS, region)
        b.set(CaptureRequest.CONTROL_AE_REGIONS, region)
        b.set(CaptureRequest.CONTROL_AF_TRIGGER, CaptureRequest.CONTROL_AF_TRIGGER_START)
        try {
            s.capture(b.build(), results, handler)
        } catch (e: Exception) {
            Log.w(TAG, "tap focus", e)
        }
        listener.onCameraChanged(setOf("cam_focus", "cam_point_of_interest"))
    }

    /** The part of the sensor's coordinate space the picture shows. Regions and faces use active-array
     *  coordinates for the zoomed field of view (CONTROL_ZOOM_RATIO), so only the output aspect crops it. */
    private fun pictureRegion(active: Rect): android.graphics.RectF {
        var w = active.width().toFloat(); var h = active.height().toFloat()
        val aspect = captureSize.width.toFloat() / captureSize.height
        if (w / h > aspect) w = h * aspect else h = w / aspect
        return android.graphics.RectF(active.centerX() - w / 2, active.centerY() - h / 2, active.centerX() + w / 2, active.centerY() + h / 2)
    }

    private fun onFaces(list: Array<android.hardware.camera2.params.Face>?) {
        val active = chars.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE) ?: return
        val fs = (list ?: emptyArray()).filter { it.score >= 30 }.sortedByDescending { it.bounds.width() * it.bounds.height() }
        val pr = pictureRegion(active)
        faces = fs.map { f ->
            val b = f.bounds
            var l = (b.left - pr.left) / pr.width(); var t = (b.top - pr.top) / pr.height()
            var r = (b.right - pr.left) / pr.width(); var bt = (b.bottom - pr.top) / pr.height()
            if (uprightDegrees == 180) { val l0 = l; l = 1 - r; r = 1 - l0; val t0 = t; t = 1 - bt; bt = 1 - t0 }
            android.graphics.RectF(l, t, r, bt)
        }
        // AF/AE follow the largest face; re-issue the request at most twice a second, and only on real moves
        val target = fs.firstOrNull()?.bounds
        val old = faceRegion
        val moved = (target == null) != (old == null) || (target != null && old != null &&
            (abs(target.centerX() - old.centerX()) > active.width() / 20 || abs(target.width() - old.width()) > active.width() / 20))
        val now = SystemClock.elapsedRealtime()
        if (moved && now - faceRegionAt > 500) {
            faceRegion = target?.let { Rect(it).apply { intersect(active) } }
            faceRegionAt = now
            if (afAuto && !focusLockedByTap) applyRepeating()
        }
    }

    fun setFaceDetect(on: Boolean) = handler.post {
        faceDetect = on; prefs.faceDetect = on
        if (!on) { faces = emptyList(); faceRegion = null }
        applyRepeating()
    }

    private val results = object : CameraCaptureSession.CaptureCallback() {
        override fun onCaptureCompleted(s: CameraCaptureSession, r: CaptureRequest, result: TotalCaptureResult) {
            if (faceDetect) onFaces(result.get(CaptureResult.STATISTICS_FACES))
            if (highSpeed) {
                // some advertised high-speed rates run but deliver black frames with empty metadata: give up on those
                if (result.get(CaptureResult.SENSOR_FRAME_DURATION) == null) {
                    if (++hsEmpty > 2 * offSpeedFps) { hsEmpty = 0; markHighSpeedFailed(offSpeedFps) }
                } else hsEmpty = 0
            }
            result.get(CaptureResult.SENSOR_SENSITIVITY)?.let { liveIso = it }
            result.get(CaptureResult.SENSOR_EXPOSURE_TIME)?.let { liveExposureNs = it }
            result.get(CaptureResult.LENS_FOCUS_DISTANCE)?.let {
                liveFocus01 = if (minFocusDiopters > 0) (it / minFocusDiopters).coerceIn(0f, 1f) else 0f
            }
            if (wbAuto) {
                result.get(CaptureResult.COLOR_CORRECTION_GAINS)?.let { liveGains = it; liveKelvin = Wb.kelvinFromGains(it) }
                result.get(CaptureResult.COLOR_CORRECTION_TRANSFORM)?.let { liveCcm = it }
            }
            reportLive()
        }
    }

    /** Auto values drift every frame: tell the remote at most ~3x a second, and only on visible change. */
    private fun reportLive() {
        val now = SystemClock.elapsedRealtime()
        if (now - lastReportMs < 333) return
        val cur = intArrayOf(isoStop(liveIso), shutterDenFromNs(liveExposureNs), (liveKelvin / 50) * 50, (liveFocus01 * 100).toInt())
        val changed = HashSet<String>()
        if (isoAuto && cur[0] != lastReported[0]) changed += "cam_sensibility"
        if (shutterAuto && cur[1] != lastReported[1]) changed += "cam_shutter"
        if (wbAuto && cur[2] != lastReported[2]) changed += "cam_white_balance"
        if (afAuto && cur[3] != lastReported[3]) changed += "cam_focus"
        if (changed.isNotEmpty()) {
            lastReported = cur
            lastReportMs = now
            listener.onCameraChanged(changed)
        }
    }

    fun shutterDenFromNs(ns: Long): Int = if (ns <= 0) shutterDen else (1_000_000_000.0 / ns).roundToInt().coerceAtLeast(1)

    fun isoStop(v: Int): Int = if (v <= 0) iso else ISO_STOPS.minByOrNull { abs(ln(it.toDouble() / v)) } ?: v

    fun release() {
        handler.post { closeDevice() }
        thread.quitSafely()
    }

    companion object {
        const val TAG = "CRCam.Camera"
        // CineRemote's LensType numbers
        const val LENS_ULTRA_WIDE = 1
        const val LENS_MAIN = 2
        const val LENS_TELE = 3
        const val LENS_FRONT = 4
        val ISO_STOPS = listOf(25, 32, 40, 50, 64, 80, 100, 125, 160, 200, 250, 320, 400, 500, 640, 800, 1000, 1250,
            1600, 2000, 2500, 3200, 4000, 5000, 6400, 8000, 10000, 12800)
        val ALL_FIELDS = setOf("active_cam", "available_cams", "cam_framerate", "cam_frame_rate_range", "cam_shutter",
            "cam_shutter_range", "cam_sensibility", "cam_sensibility_range", "cam_white_balance", "cam_white_balance_range",
            "cam_tint", "cam_tint_range", "cam_exposure_comp", "cam_exposure_comp_range", "cam_focus", "cam_focus_range",
            "cam_zoom", "cam_stabilization", "cam_stabilization_values", "cam_light")
    }
}

/** White balance: Kelvin + tint <-> per-channel gains (black-body approximation, G = 1). */
object Wb {
    private fun rgb(k: Int): Triple<Double, Double, Double> {
        val t = k / 100.0
        val r = if (t <= 66) 255.0 else (329.698727446 * (t - 60).pow(-0.1332047592)).coerceIn(0.0, 255.0)
        val g = if (t <= 66) (99.4708025861 * ln(t) - 161.1195681661).coerceIn(0.0, 255.0)
                else (288.1221695283 * (t - 60).pow(-0.0755148492)).coerceIn(0.0, 255.0)
        val b = when { t >= 66 -> 255.0; t <= 19 -> 0.0; else -> (138.5177312231 * ln(t - 10) - 305.0447927307).coerceIn(0.0, 255.0) }
        return Triple(r.coerceAtLeast(1.0), g.coerceAtLeast(1.0), b.coerceAtLeast(1.0))
    }

    fun gains(kelvin: Int, tint: Int): RggbChannelVector {
        val (r, g, b) = rgb(kelvin)
        val gr = (g / r).toFloat(); val gb = (g / b).toFloat()
        val green = (1f - tint / 200f).coerceIn(0.6f, 1.4f)   // +tint = magenta (less green)
        return RggbChannelVector(gr, green, green, gb)
    }

    /** Inverse: the Kelvin whose gains have the same red/blue balance as the camera's auto white balance. */
    fun kelvinFromGains(v: RggbChannelVector): Int {
        val target = v.red / v.blue
        var best = 5600; var err = Double.MAX_VALUE
        var k = 2000
        while (k <= 10000) {
            val gg = gains(k, 0)
            val e = abs(ln((gg.red / gg.blue).toDouble() / target))
            if (e < err) { err = e; best = k }
            k += 50
        }
        return best
    }
}
