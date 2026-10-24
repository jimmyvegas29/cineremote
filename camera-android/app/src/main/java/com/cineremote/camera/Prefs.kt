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
        set(v) = sp.edit().putString("slate", v.trim().take(2).uppercase()).apply()

    var password: String
        get() = sp.getString("password", null) ?: DEFAULT_PASSWORD
        set(v) = sp.edit().putString("password", v).apply()

    var cameraId: String?
        get() = sp.getString("camera_id", null)
        set(v) = sp.edit().putString("camera_id", v).apply()

    var fps: Int
        get() = sp.getInt("fps", 30)
        set(v) = sp.edit().putInt("fps", v).apply()

    companion object {
        const val DEFAULT_PASSWORD = "cineremote"
    }
}
