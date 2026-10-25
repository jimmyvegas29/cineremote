package com.cineremote.camera

import org.json.JSONArray
import org.json.JSONObject

/**
 * Saved camera setups (Blackmagic's Presets panel): lens, frame rate, exposure, white balance, focus, zoom,
 * stabilisation, recording format/codec/bit rate, audio gain, display LUT and monitoring tools. Stored as
 * JSON in prefs; applying one restores everything it holds.
 */
class Presets(private val prefs: Prefs) {
    fun all(): List<JSONObject> {
        val a = runCatching { JSONArray(prefs.presets) }.getOrDefault(JSONArray())
        return (0 until a.length()).map { a.getJSONObject(it) }
    }
    private fun save(list: List<JSONObject>) { prefs.presets = JSONArray(list).toString() }
    fun names() = all().map { it.optString("name") }

    fun capture(name: String, engine: CameraEngine, aids: MonitorAids): JSONObject = JSONObject().apply {
        put("name", name)
        put("lens", engine.lens.id); put("fps", engine.fps); put("zoom", engine.zoom.toDouble())
        put("isoAuto", engine.isoAuto); put("iso", engine.iso)
        put("shutterAuto", engine.shutterAuto); put("shutterDen", engine.shutterDen)
        put("wbAuto", engine.wbAuto); put("kelvin", engine.kelvin); put("tint", engine.tint)
        put("ev", engine.evStops.toDouble()); put("afAuto", engine.afAuto); put("focus", engine.focus01.toDouble())
        put("stab", engine.stabilization)
        put("resolution", prefs.resolution); put("frameSize", prefs.frameSize ?: ""); put("hevc", prefs.hevc)
        put("bitrate", prefs.bitrateTier); put("gain", prefs.audioGain); put("stereo", prefs.recordStereo)
        put("lut", prefs.lut ?: ""); put("zebra", aids.zebra ?: ""); put("focusAssist", aids.focusAssist)
    }

    fun add(name: String, engine: CameraEngine, aids: MonitorAids) = save(all() + capture(name, engine, aids))
    fun update(i: Int, engine: CameraEngine, aids: MonitorAids) =
        save(all().mapIndexed { k, p -> if (k == i) capture(p.optString("name"), engine, aids) else p })
    fun rename(i: Int, name: String) = save(all().mapIndexed { k, p -> if (k == i) p.put("name", name) else p })
    fun delete(i: Int) = save(all().filterIndexed { k, _ -> k != i })

    /** Restore preset [i]; [onLut] applies the display LUT, [onGain] the audio gain. */
    fun apply(i: Int, engine: CameraEngine, aids: MonitorAids, onLut: (String?) -> Unit, onGain: (Int) -> Unit) {
        val p = all().getOrNull(i) ?: return
        prefs.hevc = p.optBoolean("hevc", prefs.hevc)
        prefs.bitrateTier = p.optInt("bitrate", prefs.bitrateTier)
        prefs.recordStereo = p.optBoolean("stereo", prefs.recordStereo)
        p.optString("lens").takeIf { it.isNotEmpty() && it != engine.lens.id }?.let { engine.switchLens(it) }
        engine.setFormat(p.optString("resolution", prefs.resolution), p.optString("frameSize").ifEmpty { null })
        // after the lens switch and format change (same camera thread, in order), which reset zoom/fps
        engine.handlerPost {
            engine.selectFps(p.optInt("fps", engine.fps))
            engine.isoAuto = p.optBoolean("isoAuto", true); engine.iso = p.optInt("iso", engine.iso)
            engine.shutterAuto = p.optBoolean("shutterAuto", true); engine.shutterDen = p.optInt("shutterDen", engine.shutterDen)
            engine.wbAuto = p.optBoolean("wbAuto", true); engine.kelvin = p.optInt("kelvin", engine.kelvin); engine.tint = p.optInt("tint", 0)
            engine.evStops = p.optDouble("ev", 0.0).toFloat()
            engine.afAuto = p.optBoolean("afAuto", true); engine.focus01 = p.optDouble("focus", 0.0).toFloat(); engine.focusLockedByTap = false
            engine.zoom = p.optDouble("zoom", 1.0).toFloat()
            engine.stabilization = p.optBoolean("stab", true)
            engine.apply()
        }
        onGain(p.optInt("gain", prefs.audioGain))
        onLut(p.optString("lut").ifEmpty { null })
        aids.zebra = p.optString("zebra").ifEmpty { null }; prefs.zebra = aids.zebra
        aids.focusAssist = p.optBoolean("focusAssist", false); prefs.focusAssist = aids.focusAssist
    }
}
