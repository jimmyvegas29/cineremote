package com.cineremote.camera

import android.content.ContentValues
import android.content.Context
import android.media.MediaRecorder
import android.net.Uri
import android.os.Environment
import android.os.ParcelFileDescriptor
import android.os.StatFs
import android.provider.MediaStore
import android.util.Log
import android.view.Surface
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Records the camera to H.264 + AAC MP4 files in Movies/CineRemote. The recorder owns its input surface
 * (valid between [prepare] and [stop]); the camera targets that surface only while a take runs, so the
 * capture session is rebuilt on start/stop (a ~200 ms hitch). Samsung rejects MediaCodec persistent input
 * surfaces here, so we use the recorder's own surface instead.
 */
class Recorder(private val context: Context) {
    private var recorder: MediaRecorder? = null
    private var pfd: ParcelFileDescriptor? = null
    private var uri: Uri? = null
    var inputSurface: Surface? = null
        private set
    var width = 1920
    var height = 1080

    @Volatile var startWallMs = 0L
        private set
    @Volatile var lastClipSecs = -1.0
        private set
    val isRecording get() = startWallMs > 0

    /** Create and prepare a recorder; returns the surface the camera must target before [start]. */
    fun prepare(fps: Int, size: android.util.Size? = null): Surface? {
        release()
        size?.let { width = it.width; height = it.height }
        val name = "CineRemote_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date()) + ".mp4"
        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, name)
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_MOVIES + "/CineRemote")
            put(MediaStore.Video.Media.IS_PENDING, 1)
        }
        return try {
            val u = context.contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)!!
            val fd = context.contentResolver.openFileDescriptor(u, "w")!!
            val r = MediaRecorder(context).apply {
                setAudioSource(MediaRecorder.AudioSource.CAMCORDER)
                setVideoSource(MediaRecorder.VideoSource.SURFACE)
                setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                setOutputFile(fd.fileDescriptor)
                setVideoEncoder(MediaRecorder.VideoEncoder.H264)
                setVideoSize(width, height)
                setVideoFrameRate(fps)
                setVideoEncodingBitRate(BITRATE)
                setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                setAudioSamplingRate(48000)
                setAudioChannels(2)
                setAudioEncodingBitRate(192_000)
                prepare()
            }
            recorder = r; pfd = fd; uri = u
            inputSurface = r.surface      // valid after prepare(), before stop()
            Log.i(TAG, "prepared $name")
            inputSurface
        } catch (e: Exception) {
            Log.e(TAG, "prepare recording", e)
            release()
            null
        }
    }

    /** Begin writing (call once the camera is targeting [inputSurface]). */
    fun start(): Boolean {
        val r = recorder ?: return false
        return try {
            r.start()
            startWallMs = System.currentTimeMillis()
            true
        } catch (e: Exception) {
            Log.e(TAG, "start recording", e)
            false
        }
    }

    /** Stop writing and finalize the file (call after the camera stopped targeting [inputSurface]). */
    fun stop() {
        val r = recorder ?: return
        val started = startWallMs
        try {
            r.stop()
        } catch (e: Exception) {
            Log.w(TAG, "stop (clip too short?)", e)
        }
        lastClipSecs = if (started > 0) (System.currentTimeMillis() - started) / 1000.0 else 0.0
        startWallMs = 0
        finishFile()
        release()
    }

    private fun finishFile() {
        uri?.let { u ->
            context.contentResolver.update(u, ContentValues().apply { put(MediaStore.Video.Media.IS_PENDING, 0) }, null, null)
        }
        uri = null
        runCatching { pfd?.close() }
        pfd = null
    }

    fun forceStopIfNeeded() {
        if (recorder != null) {
            runCatching { recorder?.stop() }
            startWallMs = 0
            finishFile()
            release()
        }
    }

    fun release() {
        runCatching { recorder?.release() }
        recorder = null
        inputSurface = null
    }

    /** (total, free, runtime seconds at our bitrate) of the storage clips go to. */
    fun storage(): Triple<Long, Long, Long> {
        val st = StatFs(Environment.getExternalStorageDirectory().path)
        val free = st.availableBytes
        return Triple(st.totalBytes, free, free * 8 / (BITRATE + 192_000))
    }

    companion object {
        const val TAG = "CRCam.Recorder"
        const val BITRATE = 20_000_000
    }
}
