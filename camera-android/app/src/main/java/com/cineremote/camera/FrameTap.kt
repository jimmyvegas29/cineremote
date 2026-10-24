package com.cineremote.camera

import android.graphics.Matrix
import android.graphics.ImageFormat
import android.graphics.YuvImage
import android.os.Handler
import android.util.Log
import android.view.Surface
import org.webrtc.EglBase
import org.webrtc.SurfaceTextureHelper
import org.webrtc.TextureBufferImpl
import org.webrtc.VideoFrame

/**
 * The camera's always-on GPU frame tap and its only output besides the recorder. The camera renders into
 * [surface]; every frame is turned upright and handed to [previewSink] (the screen) and [sink] (the live
 * stream, while one is set), and a few times a second a tiny copy is read back for
 * the RGB histogram.
 *
 * Why "turned upright": Camera2 bakes the sensor's mounting rotation (90 degrees on most phones) into a
 * SurfaceTexture's transform matrix, so the raw texture would reach the encoder rotated and squeezed into the
 * 16:9 buffer. [transform] returns (degrees, mirror) that undo that and correct for the display rotation -
 * the same correction WebRTC's own Camera2 capturer applies.
 */
class FrameTap(context: android.content.Context, private val transform: () -> Pair<Int, Boolean>) {
    init {
        // loads WebRTC's native library, which the GPU->CPU readback needs before any stream session exists
        org.webrtc.PeerConnectionFactory.initialize(
            org.webrtc.PeerConnectionFactory.InitializationOptions.builder(context.applicationContext).createInitializationOptions())
    }
    val egl: EglBase = EglBase.create()
    private val helper = SurfaceTextureHelper.create("camera-frames", egl.eglBaseContext)
    val surface = Surface(helper.surfaceTexture)
    /** The tap's GL thread: frames arrive here, so work that must not race a frame is posted here. */
    val handler: Handler get() = helper.handler

    @Volatile var sink: ((VideoFrame) -> Unit)? = null
    /** The on-screen preview renderer. */
    @Volatile var previewSink: org.webrtc.VideoSink? = null
    /** Latest histogram: R, G, B rows of [BINS] bins, each normalised to 0..1. */
    @Volatile var histogram: Array<FloatArray>? = null
    private var lastHistNs = 0L
    private var histFailed = false
    /** Debug: save the next upright frame (exactly what the stream encoder gets) to this file as a JPEG. */
    @Volatile var snapshotTo: java.io.File? = null
    private var statFrames = 0; private var statStartNs = 0L; private var statGaps = 0; private var lastNs = 0L

    init {
        helper.setTextureSize(1920, 1080)
        // Always drain the SurfaceTexture (an unconsumed one stalls the camera).
        helper.startListening { frame -> onFrame(frame) }
    }

    fun setSize(w: Int, h: Int) = helper.setTextureSize(w, h)

    private fun onFrame(frame: VideoFrame) {
        val buf = frame.buffer as? TextureBufferImpl ?: return
        val (degrees, mirror) = transform()
        val m = Matrix().apply {
            // rotate/mirror around the texture centre
            preTranslate(0.5f, 0.5f)
            if (mirror) preScale(-1f, 1f)
            preRotate(degrees.toFloat())
            preTranslate(-0.5f, -0.5f)
        }
        val upright = VideoFrame(buf.applyTransformMatrix(m, buf.width, buf.height), 0, frame.timestampNs)
        previewSink?.onFrame(upright)
        sink?.let { deliver ->
            // the stream stays within 1080p: bigger formats (4K, open gate) are scaled on the GPU, whole frame kept
            val ub = upright.buffer
            if (ub.width.toLong() * ub.height > STREAM_MAX_PIXELS) {
                val k = minOf(1920f / ub.width, 1080f / ub.height)
                val w = (ub.width * k).toInt() and 1.inv(); val h = (ub.height * k).toInt() and 1.inv()
                val scaled = VideoFrame(ub.cropAndScale(0, 0, ub.width, ub.height, w, h), 0, upright.timestampNs)
                deliver(scaled)
                scaled.release()
            } else deliver(upright)
        }
        stats(frame.timestampNs)
        if (frame.timestampNs - lastHistNs > 200_000_000L) {
            lastHistNs = frame.timestampNs
            try {
                histogram = computeHistogram(upright.buffer)
            } catch (e: Throwable) {
                if (!histFailed) { histFailed = true; Log.w(TAG, "histogram", e) }
            }
        }
        snapshotTo?.let { f -> snapshotTo = null; runCatching { snapshot(upright.buffer, f) }.onFailure { Log.w(TAG, "snapshot", it) } }
        upright.release()
    }

    /** Debug: log the delivered frame rate and missed frames every 5 s. */
    private fun stats(ns: Long) {
        if (lastNs != 0L && ns - lastNs > 50_000_000L) statGaps++
        lastNs = ns
        if (statStartNs == 0L) statStartNs = ns
        statFrames++
        if (ns - statStartNs >= 5_000_000_000L) {
            Log.d(TAG, "tap %.1f fps, %d gaps".format(statFrames * 1e9 / (ns - statStartNs), statGaps))
            statFrames = 0; statGaps = 0; statStartNs = ns
        }
    }

    /** Scale to 160x90 on the GPU, read back as I420, and bin BT.709 RGB. */
    private fun computeHistogram(b: VideoFrame.Buffer): Array<FloatArray>? {
        val small = b.cropAndScale(0, 0, b.width, b.height, 160, 90)
        val i420 = small.toI420()
        small.release()
        if (i420 == null) return null
        val bins = Array(3) { IntArray(BINS) }
        val y = i420.dataY; val u = i420.dataU; val v = i420.dataV
        for (row in 0 until i420.height) {
            for (col in 0 until i420.width) {
                val yy = ((y.get(row * i420.strideY + col).toInt() and 0xFF) - 16) * 1.164f
                val cb = (u.get((row / 2) * i420.strideU + col / 2).toInt() and 0xFF) - 128
                val cr = (v.get((row / 2) * i420.strideV + col / 2).toInt() and 0xFF) - 128
                val r = (yy + 1.793f * cr).toInt().coerceIn(0, 255)
                val g = (yy - 0.213f * cb - 0.533f * cr).toInt().coerceIn(0, 255)
                val bl = (yy + 2.112f * cb).toInt().coerceIn(0, 255)
                bins[0][r * BINS / 256]++; bins[1][g * BINS / 256]++; bins[2][bl * BINS / 256]++
            }
        }
        i420.release()
        // normalise each channel to its own peak, ignoring the clipped end bins so they don't flatten the rest
        return Array(3) { c ->
            val peak = (1 until BINS - 1).maxOf { bins[c][it] }.coerceAtLeast(1).toFloat()
            FloatArray(BINS) { (bins[c][it] / peak).coerceAtMost(1f) }
        }
    }

    private fun snapshot(b: VideoFrame.Buffer, f: java.io.File) {
        val small = b.cropAndScale(0, 0, b.width, b.height, 960, 540)
        val i = small.toI420() ?: return
        small.release()
        val w = i.width; val h = i.height
        val nv21 = ByteArray(w * h * 3 / 2)
        for (r in 0 until h) for (c in 0 until w) nv21[r * w + c] = i.dataY.get(r * i.strideY + c)
        var o = w * h
        for (r in 0 until h / 2) for (c in 0 until w / 2) {
            nv21[o++] = i.dataV.get(r * i.strideV + c); nv21[o++] = i.dataU.get(r * i.strideU + c)
        }
        i.release()
        f.outputStream().use { YuvImage(nv21, ImageFormat.NV21, w, h, null).compressToJpeg(android.graphics.Rect(0, 0, w, h), 90, it) }
        Log.i(TAG, "snapshot ${w}x$h -> $f")
    }

    fun release() {
        sink = null
        runCatching { helper.stopListening() }
        runCatching { surface.release() }
        runCatching { helper.dispose() }
        runCatching { egl.release() }
    }

    companion object {
        const val TAG = "CRCam.Tap"
        const val BINS = 64
        const val STREAM_MAX_PIXELS = 1920L * 1088
    }
}
