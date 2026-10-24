package com.cineremote.camera

import kotlin.concurrent.thread

/**
 * Starts and stops takes - the one path for the on-screen record button, the volume key and CineRemote:
 * prepare the recorder for the current format/codec/bit rate, add its surface to the camera, start encoding
 * once frames flow, and feed it the mic; stopping reverses that and finalizes the file off the camera thread.
 */
class Takes(
    private val engine: CameraEngine,
    private val recorder: Recorder,
    private val audio: AudioInput,
    private val prefs: Prefs,
    private val onChanged: () -> Unit,
) {
    @Volatile var active = false
        private set

    /** The recorder settings a take would use right now (also used to estimate record time left). */
    fun config(): Recorder.Config {
        val f = engine.format
        return Recorder.Config(f.width, f.height, engine.fps, prefs.hevc,
            Formats.bitrate(f, engine.fps, prefs.hevc, prefs.bitrateTier), engine.uprightDegrees, prefs.recordStereo)
    }

    fun start(): Boolean {
        if (active) return true
        val surface = recorder.prepare(config()) ?: return false
        active = true
        if (prefs.lockWbOnRecord) engine.lockWbForTake(true)
        engine.setRecordSurface(surface) {
            recorder.start()
            audio.consumer = recorder::onAudio
            onChanged()
        }
        return true
    }

    fun stop() {
        if (!active) return
        active = false
        audio.consumer = null
        engine.setRecordSurface(null) {
            thread(name = "rec-finish") {
                recorder.stop()
                engine.lockWbForTake(false)
                onChanged()
            }
        }
    }

    fun toggle(): Boolean = if (active) { stop(); true } else start()
}
