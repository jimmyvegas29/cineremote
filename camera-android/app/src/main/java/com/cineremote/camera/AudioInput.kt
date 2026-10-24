package com.cineremote.camera

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * The camera mic: one capture (48 kHz, stereo where the phone has it) on its own thread that applies the
 * input gain, keeps per-channel meter levels (peak dBFS with fall-off and a short peak hold), and hands the
 * gained PCM to [consumer] - the recorder, during a take - stamped on the monotonic clock, which is what
 * the video encoder's frame timestamps use, so sound and picture line up in the file.
 */
class AudioInput(prefs: Prefs) {
    val sampleRate = 48000
    val channels = 2
    /** Linear input gain, 0..1 (the Audio Gain percentage). */
    @Volatile var gain = prefs.audioGain / 100f
    /** Current level per channel in dBFS ([SILENCE] = silence). */
    val level = floatArrayOf(SILENCE, SILENCE)
    /** Peak hold per channel in dBFS. */
    val peak = floatArrayOf(SILENCE, SILENCE)
    /** Gained interleaved 16-bit PCM, sample count, and the capture time (ns) of its first sample. */
    @Volatile var consumer: ((ShortArray, Int, Long) -> Unit)? = null

    private val peakAt = LongArray(2)
    @Volatile private var running = false
    private var thread: Thread? = null

    @SuppressLint("MissingPermission")   // started only after RECORD_AUDIO is granted
    fun start() {
        if (running) return
        running = true
        thread = Thread({ loop() }, "audio-input").apply { start() }
    }

    @SuppressLint("MissingPermission")
    private fun loop() {
        val min = AudioRecord.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_IN_STEREO, AudioFormat.ENCODING_PCM_16BIT)
        val rec = try {
            AudioRecord(MediaRecorder.AudioSource.CAMCORDER, sampleRate, AudioFormat.CHANNEL_IN_STEREO,
                AudioFormat.ENCODING_PCM_16BIT, max(min, sampleRate / 10 * 4))
        } catch (e: Exception) {
            Log.w(TAG, "no audio input", e); running = false; return
        }
        if (rec.state != AudioRecord.STATE_INITIALIZED) { rec.release(); running = false; return }
        rec.startRecording()
        val buf = ShortArray(sampleRate / 50 * channels)        // 20 ms
        val nsPerFrame = 1e9 / sampleRate
        var baseNs = -1L; var frames = 0L
        while (running) {
            val n = rec.read(buf, 0, buf.size)
            if (n <= 0) continue
            val f = n / channels
            // capture time of this chunk's first sample: a running sample count, re-anchored on clock drift
            val nowStart = System.nanoTime() - (f * nsPerFrame).toLong()
            if (baseNs < 0 || abs(baseNs + (frames * nsPerFrame).toLong() - nowStart) > 30_000_000L) { baseNs = nowStart; frames = 0 }
            val ptsNs = baseNs + (frames * nsPerFrame).toLong()
            frames += f
            applyGain(buf, n)
            var l = 0; var r = 0
            var i = 0
            while (i + 1 < n) { l = max(l, abs(buf[i].toInt())); r = max(r, abs(buf[i + 1].toInt())); i += 2 }
            update(0, l); update(1, r)
            consumer?.invoke(buf, n, ptsNs)
        }
        runCatching { rec.stop() }
        rec.release()
    }

    private fun applyGain(buf: ShortArray, n: Int) {
        val g = gain
        if (g >= 0.999f) return
        for (i in 0 until n) buf[i] = (buf[i] * g).roundToInt().coerceIn(-32768, 32767).toShort()
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
        const val TAG = "CRCam.Audio"
        const val SILENCE = -90f
    }
}
