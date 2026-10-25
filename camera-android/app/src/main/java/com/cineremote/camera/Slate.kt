package com.cineremote.camera

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * The clip slate (Blackmagic's slate panel, and CineRemote's slate editor - one shared, saved model): reel,
 * scene, take (auto-increments after each clip), interior/exterior, day/night, good take, production,
 * director and camera operator. Clips are named like a Blackmagic camera's ("A001_10242046_C005.mp4") and
 * the slate text is written to the clip's MediaStore description.
 */
class Slate(private val prefs: Prefs) {
    var reel: Int get() = prefs.int("slate_reel", 1); set(v) = prefs.putInt("slate_reel", v.coerceIn(1, 999))
    var scene: String get() = prefs.str("slate_scene", "1"); set(v) = prefs.putStr("slate_scene", v.trim().take(16))
    var take: Int get() = prefs.int("slate_take", 1); set(v) = prefs.putInt("slate_take", v.coerceIn(1, 999))
    var autoIncrement: Boolean get() = prefs.bool("slate_auto", true); set(v) = prefs.putBool("slate_auto", v)
    /** 0 unset, 1 interior, 2 exterior (CineRemote's SlateLight). */
    var light: Int get() = prefs.int("slate_light", 0); set(v) = prefs.putInt("slate_light", v)
    /** 0 unset, 1 day, 2 night (CineRemote's SlateTimeOfDay). */
    var timeOfDay: Int get() = prefs.int("slate_tod", 0); set(v) = prefs.putInt("slate_tod", v)
    var goodTake: Boolean get() = prefs.bool("slate_good", false); set(v) = prefs.putBool("slate_good", v)
    var production: String get() = prefs.str("slate_prod"); set(v) = prefs.putStr("slate_prod", v.trim())
    var director: String get() = prefs.str("slate_dir"); set(v) = prefs.putStr("slate_dir", v.trim())
    var operator: String get() = prefs.str("slate_op"); set(v) = prefs.putStr("slate_op", v.trim())
    /** Clip number on the current reel. */
    var clip: Int get() = prefs.int("slate_clip", 1); set(v) = prefs.putInt("slate_clip", v.coerceIn(1, 999))
    private var lastClip: String? get() = prefs.str("slate_last_uri").ifEmpty { null }; set(v) = prefs.putStr("slate_last_uri", v ?: "")

    /** File name for the next clip: camera letter + reel, date/time, clip number. */
    fun nextFileName(): String {
        val letter = prefs.slate.ifBlank { "A" }.take(1).uppercase()     // first letter of the camera name
        return "%s%03d_%s_C%03d.mp4".format(letter, reel, SimpleDateFormat("MMddHHmm", Locale.US).format(Date()), clip)
    }

    /** One-line slate text for the clip's description. */
    fun describe(good: Boolean = false): String = listOfNotNull(
        "Scene $scene", "Take $take",
        when (light) { 1 -> "INT"; 2 -> "EXT"; else -> null }, when (timeOfDay) { 1 -> "DAY"; 2 -> "NIGHT"; else -> null },
        production.ifBlank { null }, director.ifBlank { null }?.let { "Dir. $it" }, operator.ifBlank { null }?.let { "Op. $it" },
        if (good) "Good take" else null,
    ).joinToString(" · ")

    /** A clip finished: remember it (for Good Take), advance the clip number and, if set, the take. */
    fun clipFinished(uri: Uri?) {
        lastClip = uri?.toString()
        goodTake = false
        clip += 1
        if (autoIncrement) take += 1
    }

    /** Mark (or unmark) the last clip as a good take, in its description. */
    fun markLastGood(context: Context, good: Boolean) {
        goodTake = good
        val u = lastClip?.let(Uri::parse) ?: return
        val desc = context.contentResolver.query(u, arrayOf(MediaStore.Video.Media.DESCRIPTION), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        } ?: return
        val base = desc.removeSuffix(" · Good take")
        runCatching {
            context.contentResolver.update(u, ContentValues().apply { put(MediaStore.Video.Media.DESCRIPTION, if (good) "$base · Good take" else base) }, null, null)
        }
    }
}
