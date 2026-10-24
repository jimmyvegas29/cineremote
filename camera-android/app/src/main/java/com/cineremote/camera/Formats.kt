package com.cineremote.camera

import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCharacteristics
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.media.MediaRecorder
import android.util.Log
import android.util.Size
import kotlin.math.abs

/** A recordable frame size, labelled the way the Blackmagic Camera app's Resolution menu does. */
data class FrameFormat(val width: Int, val height: Int, val bucket: String) {
    val size get() = Size(width, height)
    val key get() = "${width}x$height"
    val ratio get() = width.toFloat() / height
    val isOpenGate get() = bucket == "OG"

    val aspectName: String get() = when {
        near(16f / 9) -> "16:9"; near(4f / 3) -> "4:3"; near(17f / 9) -> "17:9"; near(2f) -> "2:1"
        near(1f) -> "1:1"; near(2.39f) -> "2.39:1"; near(21f / 9) -> "21:9"; near(3f / 2) -> "3:2"
        else -> "%.2f:1".format(ratio)
    }
    /** Menu label: "UHD 16:9", "1080p 16:9", "Open Gate 4:3", or just the ratio. */
    val label: String get() = when {
        isOpenGate -> "Open Gate $aspectName"
        width == 3840 && height == 2160 -> "UHD 16:9"
        width == 4096 && height == 2160 -> "DCI 17:9"
        width == 1920 && height == 1080 -> "1080p 16:9"
        width == 1280 && height == 720 -> "720p 16:9"
        else -> aspectName
    }
    /** Short resolution name for the format badge. */
    val badge: String get() = when (bucket) { "OG" -> "OG"; "HD" -> if (height <= 720) "720p" else "HD"; else -> bucket }

    private fun near(r: Float) = abs(ratio - r) < 0.02f
}

/**
 * Which formats a lens can record: sizes the camera can output both to a SurfaceTexture (our frame tap) and
 * to a video encoder, that the phone's hardware encoder accepts for the chosen codec, at 24 fps or more.
 * Grouped into the Blackmagic buckets by width; "Open Gate" is the largest 4:3 (whole-sensor) size.
 */
object Formats {
    val BUCKETS = listOf("OG" to "Open Gate", "4K" to "4K", "3K" to "3K", "2K" to "2K", "HD" to "HD")
    private val cache = HashMap<String, List<FrameFormat>>()

    fun mime(hevc: Boolean) = if (hevc) MediaFormat.MIMETYPE_VIDEO_HEVC else MediaFormat.MIMETYPE_VIDEO_AVC

    /** The hardware encoder's capabilities for a codec (software encoders can't keep up with 4K). */
    fun encoderCaps(hevc: Boolean): MediaCodecInfo.VideoCapabilities? {
        val type = mime(hevc)
        val infos = MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.filter { info ->
            info.isEncoder && info.supportedTypes.any { it.equals(type, ignoreCase = true) }
        }
        val hw = infos.firstOrNull { it.isHardwareAccelerated } ?: infos.firstOrNull()
        return hw?.getCapabilitiesForType(type)?.videoCapabilities
    }

    fun hevcSupported(): Boolean = encoderCaps(true) != null

    fun available(lens: CameraEngine.Lens, hevc: Boolean): List<FrameFormat> = synchronized(cache) {
        cache.getOrPut("${lens.id}/$hevc") { compute(lens, hevc) }
    }

    private fun compute(lens: CameraEngine.Lens, hevc: Boolean): List<FrameFormat> {
        val map = lens.chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP) ?: return listOf(FALLBACK)
        val caps = encoderCaps(hevc)
        val tap = map.getOutputSizes(SurfaceTexture::class.java)?.toSet() ?: emptySet()
        val rec = map.getOutputSizes(MediaRecorder::class.java)?.toSet() ?: emptySet()
        val sizes = tap.intersect(rec).filter { s ->
            val r = s.width.toFloat() / s.height
            s.width >= s.height && r in 0.99f..2.45f && s.width >= 1080 &&
                (caps?.isSizeSupported(s.width, s.height) ?: (s.width <= 1920)) &&
                map.getOutputMinFrameDuration(SurfaceTexture::class.java, s) <= 1_000_000_000L / 24
        }
        val out = ArrayList<FrameFormat>()
        for (s in sizes) bucketOf(s.width)?.let { out += FrameFormat(s.width, s.height, it) }
        sizes.filter { abs(it.width.toFloat() / it.height - 4f / 3) < 0.03f }.maxByOrNull { it.width.toLong() * it.height }
            ?.let { out += FrameFormat(it.width, it.height, "OG") }
        val sorted = out.sortedWith(compareBy({ b -> BUCKETS.indexOfFirst { it.first == b.bucket } }, { -it.width.toLong() * it.height }))
        Log.i(TAG, "lens ${lens.id} ${if (hevc) "HEVC" else "H.264"} formats: ${sorted.joinToString { "${it.bucket}:${it.key}" }}")
        return sorted.ifEmpty { listOf(FALLBACK) }
    }

    private fun bucketOf(w: Int): String? = when (w) {
        in 3800..4200 -> "4K"; in 2700..3799 -> "3K"; in 2000..2699 -> "2K"; in 1080..1999 -> "HD"; else -> null
    }

    /** The saved bucket/size if this lens has it, else the nearest sensible one (same size, same bucket, 1080p). */
    fun resolve(lens: CameraEngine.Lens, prefs: Prefs): FrameFormat {
        val all = available(lens, prefs.hevc)
        return all.firstOrNull { it.bucket == prefs.resolution && it.key == prefs.frameSize }
            ?: all.firstOrNull { it.key == prefs.frameSize }
            ?: defaultIn(prefs.resolution, all)
            ?: all.firstOrNull { it.width == 1920 && it.height == 1080 }
            ?: all.first()
    }

    /** A bucket's default size: the standard one (UHD, 1080p) if present, else its biggest 16:9, else its biggest. */
    fun defaultIn(bucket: String, all: List<FrameFormat>): FrameFormat? {
        val inB = all.filter { it.bucket == bucket }
        return inB.firstOrNull { (it.width == 3840 && it.height == 2160) || (it.width == 1920 && it.height == 1080) }
            ?: inB.firstOrNull { abs(it.ratio - 16f / 9) < 0.02f } ?: inB.firstOrNull()
    }

    /** Bit rate for a tier (0 Low .. 3 Max), scaled from 4K30 figures by pixel rate and capped to the encoder. */
    fun bitrate(f: FrameFormat, fps: Int, hevc: Boolean, tier: Int): Int {
        val base4k = if (hevc) intArrayOf(24, 36, 48, 120) else intArrayOf(32, 48, 64, 160)
        val scale = f.width.toDouble() * f.height * fps / (3840.0 * 2160 * 30)
        val bps = (base4k[tier.coerceIn(0, 3)] * 1_000_000 * scale).toInt().coerceAtLeast(4_000_000)
        val cap = encoderCaps(hevc)?.bitrateRange?.upper ?: bps
        return minOf(bps, cap)
    }

    fun tierName(tier: Int) = listOf("Low", "Medium", "High", "Max")[tier.coerceIn(0, 3)]

    private val FALLBACK = FrameFormat(1920, 1080, "HD")
    private const val TAG = "CRCam.Formats"
}
