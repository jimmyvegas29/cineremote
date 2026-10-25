package com.cineremote.camera

import android.content.ContentValues
import android.content.Context
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import android.os.Environment
import android.os.ParcelFileDescriptor
import android.os.StatFs
import android.provider.MediaStore
import android.util.Log
import android.view.Surface
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Records takes to MP4 files in Movies/CineRemote: the hardware video encoder (H.264 or HEVC, at the chosen
 * size and bit rate) reads frames from its input surface, which the camera targets only while a take runs,
 * and AAC audio comes from [AudioInput] (already gained) via [onAudio]. Both are muxed on their own drain
 * threads. Both are stamped on the monotonic clock (the camera service converts frame timestamps to it for
 * video encoders, even where the camera itself reports boot time), so they line up; the file starts at the
 * first video frame.
 *
 * Off-speed takes stretch the video timestamps (sensor rate / project rate) so they play back as slow
 * motion; timelapse takes space their sparse frames one project frame apart. Both are picture-only.
 */
class Recorder(private val context: Context) {
    data class Config(val width: Int, val height: Int, val fps: Int, val hevc: Boolean, val bitrate: Int,
                      val rotation: Int, val stereo: Boolean,
                      /** Rate frames arrive at (the sensor rate; above [fps] when off-speed). */
                      val captureFps: Int = fps,
                      val timelapse: Boolean = false,
                      val fileName: String = "", val description: String = "") {
        val audio get() = !timelapse && captureFps == fps
    }

    private var video: MediaCodec? = null
    private var audio: MediaCodec? = null
    private var muxer: MediaMuxer? = null
    private var pfd: ParcelFileDescriptor? = null
    private var uri: Uri? = null
    private var cfg: Config? = null
    var inputSurface: Surface? = null
        private set

    @Volatile var startWallMs = 0L
        private set
    @Volatile var lastClipSecs = -1.0
        private set
    val isRecording get() = startWallMs > 0
    /** Video frames written so far in this take (timelapse shows the clip length from this). */
    @Volatile var framesWritten = 0L
        private set
    /** The finished clip (for the slate's Good Take). */
    @Volatile var lastUri: Uri? = null
        private set
    /** Size and bit rate of the take in progress (or the last one). */
    @Volatile var current: Config? = null
        private set

    // muxing state (guarded by [lock])
    private val lock = Object()
    private var videoTrack = -1; private var audioTrack = -1
    private var muxerStarted = false
    private var firstVideoUs = -1L
    private val lastPts = LongArray(2) { -1 }
    private val pending = ArrayList<Pending>()
    private class Pending(val video: Boolean, val data: ByteBuffer, val info: MediaCodec.BufferInfo)

    @Volatile private var accepting = false
    private var drains = emptyList<Thread>()

    /** Create the encoders and the file; returns the surface the camera must target before [start]. */
    fun prepare(c: Config): Surface? {
        release()
        val name = c.fileName.ifBlank { "CineRemote_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date()) + ".mp4" }
        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, name)
            if (c.description.isNotBlank()) put(MediaStore.Video.Media.DESCRIPTION, c.description)
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_MOVIES + "/CineRemote")
            put(MediaStore.Video.Media.IS_PENDING, 1)
        }
        return try {
            val u = context.contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)!!
            uri = u
            val fd = context.contentResolver.openFileDescriptor(u, "rw")!!
            pfd = fd
            val mime = Formats.mime(c.hevc)
            val vf = MediaFormat.createVideoFormat(mime, c.width, c.height).apply {
                setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
                setInteger(MediaFormat.KEY_BIT_RATE, c.bitrate)
                setInteger(MediaFormat.KEY_FRAME_RATE, c.captureFps)
                if (c.captureFps > 60) {
                    // high-speed takes: ask for a real-time encoder clocked for the full rate, or it falls behind
                    setInteger(MediaFormat.KEY_OPERATING_RATE, c.captureFps)
                    setInteger(MediaFormat.KEY_PRIORITY, 0)
                }
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
            }
            video = MediaCodec.createEncoderByType(mime).apply {
                configure(vf, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                inputSurface = createInputSurface()
            }
            val ch = if (c.stereo) 2 else 1
            val af = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, 48000, ch).apply {
                setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
                setInteger(MediaFormat.KEY_BIT_RATE, if (c.stereo) 256_000 else 128_000)
                setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16384)
            }
            audio = if (!c.audio) null else MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC).apply {
                configure(af, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            }
            muxer = MediaMuxer(fd.fileDescriptor, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4).apply { setOrientationHint(c.rotation) }
            cfg = c
            Log.i(TAG, "prepared $name ${c.width}x${c.height}@${c.captureFps}/${c.fps} ${if (c.hevc) "HEVC" else "H.264"} " +
                "${c.bitrate / 1_000_000} Mb/s${if (c.timelapse) " timelapse" else ""}")
            inputSurface
        } catch (e: Exception) {
            Log.e(TAG, "prepare recording", e)
            release()
            discardFile()
            null
        }
    }

    /** Begin encoding (call once the camera is targeting [inputSurface]). */
    fun start(): Boolean {
        val v = video ?: return false
        val a = audio
        return try {
            synchronized(lock) {
                videoTrack = -1; audioTrack = -1; muxerStarted = false; firstVideoUs = -1
                lastPts.fill(-1); pending.clear(); framesWritten = 0
            }
            v.start(); a?.start()
            accepting = true
            drains = listOfNotNull(Thread({ drain(v, true) }, "rec-video"), a?.let { Thread({ drain(it, false) }, "rec-audio") }).onEach { it.start() }
            current = cfg
            startWallMs = System.currentTimeMillis()
            true
        } catch (e: Exception) {
            Log.e(TAG, "start recording", e)
            false
        }
    }

    /** Gained interleaved stereo PCM from [AudioInput]; [ptsNs] is on the monotonic clock. */
    fun onAudio(pcm: ShortArray, n: Int, ptsNs: Long) {
        if (!accepting) return
        val a = audio ?: return
        val stereo = cfg?.stereo != false
        try {
            val idx = a.dequeueInputBuffer(5_000)
            if (idx < 0) return
            val bb = a.getInputBuffer(idx) ?: return
            bb.clear()
            val sb = bb.order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
            val count: Int
            if (stereo) { sb.put(pcm, 0, n); count = n }
            else { var i = 0; while (i + 1 < n) { sb.put(((pcm[i] + pcm[i + 1]) / 2).toShort()); i += 2 }; count = n / 2 }
            a.queueInputBuffer(idx, 0, count * 2, ptsNs / 1000, 0)
        } catch (e: IllegalStateException) {
            // encoder stopping
        }
    }

    private fun drain(codec: MediaCodec, isVideo: Boolean) {
        val info = MediaCodec.BufferInfo()
        while (true) {
            val idx = try { codec.dequeueOutputBuffer(info, 10_000) } catch (e: IllegalStateException) { break }
            when {
                idx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> synchronized(lock) {
                    val m = muxer ?: return
                    if (isVideo) videoTrack = m.addTrack(codec.outputFormat) else audioTrack = m.addTrack(codec.outputFormat)
                    maybeStartMuxer()
                }
                idx >= 0 -> {
                    val out = codec.getOutputBuffer(idx)
                    if (out != null && info.size > 0 && info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0) write(isVideo, out, info)
                    codec.releaseOutputBuffer(idx, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
                }
            }
        }
    }

    private fun write(isVideo: Boolean, buf: ByteBuffer, src: MediaCodec.BufferInfo) { synchronized(lock) {
        var pts = src.presentationTimeUs
        if (isVideo && firstVideoUs < 0) firstVideoUs = pts
        if (firstVideoUs < 0 || pts < firstVideoUs) return          // audio from before the first frame
        pts -= firstVideoUs
        val c = cfg
        if (isVideo && c != null) {
            if (c.timelapse) pts = framesWritten * 1_000_000L / c.fps                       // one project frame apart
            else if (c.captureFps != c.fps) pts = pts * c.captureFps / c.fps                // off-speed: slow motion
            framesWritten++
        }
        val t = if (isVideo) 0 else 1
        if (pts <= lastPts[t]) pts = lastPts[t] + 1                 // the muxer wants rising timestamps
        lastPts[t] = pts
        val info = MediaCodec.BufferInfo().apply { set(0, src.size, pts, src.flags) }
        buf.position(src.offset); buf.limit(src.offset + src.size)
        if (muxerStarted) {
            muxer?.writeSampleData(if (isVideo) videoTrack else audioTrack, buf, info)
        } else {
            pending += Pending(isVideo, ByteBuffer.allocate(src.size).put(buf).apply { flip() }, info)
            // no audio track after ~2 s of video: write a picture-only file rather than nothing
            if (pending.count { it.video } > 60 && audioTrack < 0 && videoTrack >= 0) maybeStartMuxer(force = true)
        }
    } }

    private fun maybeStartMuxer(force: Boolean = false) {
        val m = muxer ?: return
        if (muxerStarted || videoTrack < 0 || (audioTrack < 0 && audio != null && !force)) return
        m.start()
        muxerStarted = true
        for (p in pending) {
            val track = if (p.video) videoTrack else audioTrack
            if (track >= 0) m.writeSampleData(track, p.data, p.info)
        }
        pending.clear()
    }

    /** Stop encoding and finalize the file (call after the camera stopped targeting [inputSurface]). */
    fun stop() {
        val started = startWallMs
        accepting = false
        runCatching { video?.signalEndOfInputStream() }
        runCatching {
            audio?.let { a -> val idx = a.dequeueInputBuffer(50_000); if (idx >= 0) a.queueInputBuffer(idx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM) }
        }
        drains.forEach { it.join(3000) }
        drains = emptyList()
        val ok = synchronized(lock) {
            val started2 = muxerStarted
            runCatching { if (muxerStarted) muxer?.stop() }.onFailure { Log.w(TAG, "muxer stop", it) }
            started2
        }
        lastClipSecs = if (started > 0) (System.currentTimeMillis() - started) / 1000.0 else 0.0
        startWallMs = 0
        lastUri = if (ok) uri else null
        release()
        if (ok) finishFile() else discardFile()
    }

    private fun finishFile() {
        uri?.let { u ->
            context.contentResolver.update(u, ContentValues().apply { put(MediaStore.Video.Media.IS_PENDING, 0) }, null, null)
        }
        uri = null
        runCatching { pfd?.close() }
        pfd = null
    }

    private fun discardFile() {
        runCatching { pfd?.close() }
        pfd = null
        uri?.let { u -> runCatching { context.contentResolver.delete(u, null, null) } }
        uri = null
    }

    fun forceStopIfNeeded() { if (video != null) stop() }

    fun release() {
        accepting = false
        runCatching { video?.stop() }; runCatching { video?.release() }; video = null
        runCatching { audio?.stop() }; runCatching { audio?.release() }; audio = null
        runCatching { muxer?.release() }; muxer = null
        runCatching { inputSurface?.release() }; inputSurface = null
    }

    /** (total, free, runtime seconds at the current bit rate) of the storage clips go to. */
    fun storage(bitrate: Int = current?.bitrate ?: 20_000_000): Triple<Long, Long, Long> {
        val st = StatFs(Environment.getExternalStorageDirectory().path)
        val free = st.availableBytes
        return Triple(st.totalBytes, free, free * 8 / (bitrate + 256_000))
    }

    companion object { const val TAG = "CRCam.Recorder" }
}
