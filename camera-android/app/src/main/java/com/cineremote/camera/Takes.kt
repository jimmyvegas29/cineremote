package com.cineremote.camera

import kotlin.concurrent.thread

/**
 * Starts and stops takes - the one path for the on-screen record button, the volume key, function buttons
 * and CineRemote: prepare the recorder for the current format/codec/bit rate (and timelapse or off-speed),
 * add its surface to the camera, start encoding once frames flow, and feed it the mic; stopping reverses that,
 * finalizes the file off the camera thread and moves the slate on.
 */
class Takes(
    private val engine: CameraEngine,
    private val recorder: Recorder,
    private val audio: AudioInput,
    private val prefs: Prefs,
    private val slate: Slate,
    private val onChanged: () -> Unit,
) {
    @Volatile var active = false
        private set

    /** The recorder settings a take would use right now (also used to estimate record time left). */
    fun config(): Recorder.Config {
        val f = engine.format
        val captureFps = engine.sensorFps
        val timelapse = prefs.timelapse && !engine.offSpeed
        return Recorder.Config(f.width, f.height, engine.fps, prefs.hevc,
            Formats.bitrate(f, captureFps, prefs.hevc, prefs.bitrateTier), engine.uprightDegrees, prefs.recordStereo,
            captureFps = captureFps, timelapse = timelapse,
            fileName = slate.nextFileName(), description = slate.describe())
    }

    fun start(): Boolean {
        if (active) return true
        val cfg = config()
        val surface = recorder.prepare(cfg) ?: return false
        active = true
        if (prefs.lockWbOnRecord) engine.lockWbForTake(true)
        val tlMs = if (cfg.timelapse) prefs.timelapseSecs * 1000L else 0L
        engine.setRecordSurface(surface, tlMs) {
            recorder.start()
            if (cfg.audio) audio.consumer = recorder::onAudio
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
                val cfg = recorder.current
                recorder.stop()
                if (cfg != null && cfg.captureFps > cfg.fps && recorder.lastUri == null) engine.markHighSpeedFailed(cfg.captureFps)
                slate.clipFinished(recorder.lastUri)
                engine.lockWbForTake(false)
                onChanged()
            }
        }
    }

    fun toggle(): Boolean = if (active) { stop(); true } else start()
}
