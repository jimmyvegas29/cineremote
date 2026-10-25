package com.cineremote.camera

import android.content.Context
import android.os.Build
import java.util.UUID

/** Saved settings: what CineRemote shows for this phone and the Remote Camera password. */
class Prefs(context: Context) {
    private val sp = context.getSharedPreferences("cineremote-camera", Context.MODE_PRIVATE)

    /** Stable id: CineRemote keys a camera by its mDNS instance name, so it must not change. */
    val uuid: String
        get() = sp.getString("uuid", null) ?: UUID.randomUUID().toString().also { sp.edit().putString("uuid", it).apply() }

    var deviceName: String
        get() = sp.getString("device_name", null) ?: "${Build.MANUFACTURER.replaceFirstChar { it.uppercase() }} ${Build.MODEL}"
        set(v) = sp.edit().putString("device_name", v.trim()).apply()

    /** Camera letter shown in CineRemote ("A", "B", ...). */
    var slate: String
        get() = sp.getString("slate", null) ?: "C"
        set(v) = sp.edit().putString("slate", v.trim().take(32)).apply()     // the whole camera name

    var password: String
        get() = sp.getString("password", null) ?: DEFAULT_PASSWORD
        set(v) = sp.edit().putString("password", v).apply()

    var cameraId: String?
        get() = sp.getString("camera_id", null)
        set(v) = sp.edit().putString("camera_id", v).apply()

    var fps: Int
        get() = sp.getInt("fps", 30)
        set(v) = sp.edit().putInt("fps", v).apply()

    // ---- record
    /** Resolution bucket ("OG", "4K", "3K", "2K", "HD") and the chosen frame size within it ("3840x2160"). */
    var resolution: String
        get() = sp.getString("resolution", null) ?: "HD"
        set(v) = sp.edit().putString("resolution", v).apply()
    var frameSize: String?
        get() = sp.getString("frame_size", null)
        set(v) = sp.edit().putString("frame_size", v).apply()
    var hevc: Boolean
        get() = sp.getBoolean("hevc", false)
        set(v) = sp.edit().putBoolean("hevc", v).apply()
    /** Bit-rate tier: 0 Low, 1 Medium, 2 High, 3 Max. */
    var bitrateTier: Int
        get() = sp.getInt("bitrate_tier", 2)
        set(v) = sp.edit().putInt("bitrate_tier", v).apply()

    // ---- camera
    var lockWbOnRecord: Boolean
        get() = sp.getBoolean("lock_wb_on_record", true)
        set(v) = sp.edit().putBoolean("lock_wb_on_record", v).apply()
    var volumeKeyRecords: Boolean
        get() = sp.getBoolean("volume_key_records", true)
        set(v) = sp.edit().putBoolean("volume_key_records", v).apply()

    // ---- audio
    /** Input gain, 0..100 percent of full level. */
    var audioGain: Int
        get() = sp.getInt("audio_gain", 100)
        set(v) = sp.edit().putInt("audio_gain", v.coerceIn(0, 100)).apply()
    var recordStereo: Boolean
        get() = sp.getBoolean("record_stereo", true)
        set(v) = sp.edit().putBoolean("record_stereo", v).apply()

    // ---- monitor
    var showAudioMeters: Boolean
        get() = sp.getBoolean("show_audio_meters", true)
        set(v) = sp.edit().putBoolean("show_audio_meters", v).apply()
    var showHistogram: Boolean
        get() = sp.getBoolean("show_histogram", true)
        set(v) = sp.edit().putBoolean("show_histogram", v).apply()
    var showStorage: Boolean
        get() = sp.getBoolean("show_storage", true)
        set(v) = sp.edit().putBoolean("show_storage", v).apply()
    var showBattery: Boolean
        get() = sp.getBoolean("show_battery", true)
        set(v) = sp.edit().putBoolean("show_battery", v).apply()
    /** Frame guide / grid line opacity, percent. */
    var guidesOpacity: Int
        get() = sp.getInt("guides_opacity", 50)
        set(v) = sp.edit().putInt("guides_opacity", v).apply()
    var gridsOpacity: Int
        get() = sp.getInt("grids_opacity", 50)
        set(v) = sp.edit().putInt("grids_opacity", v).apply()

    /** Zebra level ("MG", "MG+1", "75".."100") or null for off. */
    var zebra: String?
        get() = sp.getString("zebra", null)
        set(v) = sp.edit().putString("zebra", v).apply()
    var focusAssist: Boolean
        get() = sp.getBoolean("focus_assist", false)
        set(v) = sp.edit().putBoolean("focus_assist", v).apply()
    /** Focus assist style: true = Peak, false = Colored Lines. */
    var focusAssistPeak: Boolean
        get() = sp.getBoolean("focus_assist_peak", false)
        set(v) = sp.edit().putBoolean("focus_assist_peak", v).apply()
    var focusAssistColor: String
        get() = sp.getString("focus_assist_color", null) ?: "Red"
        set(v) = sp.edit().putString("focus_assist_color", v).apply()

    var faceDetect: Boolean
        get() = sp.getBoolean("face_detect", false)
        set(v) = sp.edit().putBoolean("face_detect", v).apply()
    /** Display LUT name, or null for none. */
    var lut: String?
        get() = sp.getString("lut", null)
        set(v) = sp.edit().putString("lut", v).apply()

    // ---- off-speed / timelapse
    var offSpeedFps: Int
        get() = sp.getInt("off_speed_fps", 60)
        set(v) = sp.edit().putInt("off_speed_fps", v).apply()
    var timelapse: Boolean
        get() = sp.getBoolean("timelapse", false)
        set(v) = sp.edit().putBoolean("timelapse", v).apply()
    /** Timelapse: capture one frame every this many seconds. */
    var timelapseSecs: Int
        get() = sp.getInt("timelapse_secs", 2)
        set(v) = sp.edit().putInt("timelapse_secs", v).apply()

    // ---- function buttons
    var functionButtons: Boolean
        get() = sp.getBoolean("function_buttons", true)
        set(v) = sp.edit().putBoolean("function_buttons", v).apply()
    fun fn(i: Int): String = sp.getString("fn$i", null) ?: listOf("FOCUS", "ZEBRA", "FALSE")[i.coerceIn(0, 2)]
    fun setFn(i: Int, action: String) = sp.edit().putString("fn$i", action).apply()

    // ---- presets (JSON array) and slate
    var presets: String
        get() = sp.getString("presets", null) ?: "[]"
        set(v) = sp.edit().putString("presets", v).apply()
    fun str(key: String, def: String = "") = sp.getString(key, null) ?: def
    fun putStr(key: String, v: String) = sp.edit().putString(key, v).apply()
    fun int(key: String, def: Int) = sp.getInt(key, def)
    fun putInt(key: String, v: Int) = sp.edit().putInt(key, v).apply()
    fun bool(key: String, def: Boolean) = sp.getBoolean(key, def)
    fun putBool(key: String, v: Boolean) = sp.edit().putBoolean(key, v).apply()

    // ---- remote
    var remoteEnabled: Boolean
        get() = sp.getBoolean("remote_enabled", true)
        set(v) = sp.edit().putBoolean("remote_enabled", v).apply()

    companion object {
        const val DEFAULT_PASSWORD = "cineremote"
    }
}
