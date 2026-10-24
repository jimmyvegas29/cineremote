package com.cineremote.camera

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.max

/**
 * Input level meter for the HUD's audio panel: reads the camera mic (stereo where the phone has it) on its own
 * thread and keeps per-channel peak dBFS with a meter-style fall-off plus a short peak hold. Android lets an
 * app run this alongside the recorder and the stream's own capture.
 */
class AudioMeter {
    /** Current level per channel in dBFS (-90 = silence). */
    val level = floatArrayOf(SILENCE, SILENCE)
    /** Peak hold per channel in dBFS. */
    val peak = floatArrayOf(SILENCE, SILENCE)
    private val peakAt = LongArray(2)
    @Volatile private var running = false
    private var thread: Thread? = null

    @SuppressLint("MissingPermission")   // started only after RECORD_AUDIO is granted
    fun start() {
        if (running) return
        running = true
        thread = Thread({
            val rate = 48000
            val min = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_STEREO, AudioFormat.ENCODING_PCM_16BIT)
            val rec = try {
                AudioRecord(MediaRecorder.AudioSource.CAMCORDER, rate, AudioFormat.CHANNEL_IN_STEREO,
                    AudioFormat.ENCODING_PCM_16BIT, max(min, rate / 10 * 4))
            } catch (e: Exception) {
                Log.w(TAG, "no meter input", e); running = false; return@Thread
            }
            if (rec.state != AudioRecord.STATE_INITIALIZED) { rec.release(); running = false; return@Thread }
            rec.startRecording()
            val buf = ShortArray(rate / 50 * 2)            // 20 ms of interleaved stereo
            while (running) {
                val n = rec.read(buf, 0, buf.size)
                if (n <= 0) continue
                var l = 0; var r = 0
                var i = 0
                while (i + 1 < n) {
                    l = max(l, abs(buf[i].toInt())); r = max(r, abs(buf[i + 1].toInt())); i += 2
                }
                update(0, l); update(1, r)
            }
            runCatching { rec.stop() }
            rec.release()
        }, "audio-meter").apply { start() }
    }

    private fun update(ch: Int, sample: Int) {
        val db = if (sample <= 0) SILENCE else (20 * log10(sample / 32767f)).coerceAtLeast(SILENCE)
        level[ch] = if (db > level[ch]) db else max(db, level[ch] - 0.9f)    // ~45 dB/s fall at 50 updates/s
        val now = System.currentTimeMillis()
        if (db >= peak[ch] || now - peakAt[ch] > 1500) { peak[ch] = db; peakAt[ch] = now }
    }

    fun stop() {
        running = false
        thread?.join(300)
        thread = null
    }

    companion object {
        const val TAG = "CRCam.Meter"
        const val SILENCE = -90f
    }
}
