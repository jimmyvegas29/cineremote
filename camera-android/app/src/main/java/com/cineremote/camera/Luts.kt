package com.cineremote.camera

import android.content.Context
import android.util.Log
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/** A 3D colour lookup table: size³ RGB entries (0..1), red varying fastest, then green, then blue. */
class Lut(val name: String, val size: Int, val data: FloatArray) {
    /** For GLES2 (no 3D textures): the blue slices side by side in one RGB texture, (size*size) x size. */
    fun atlas(): ByteBuffer {
        val n = size
        val buf = ByteBuffer.allocateDirect(n * n * n * 3).order(ByteOrder.nativeOrder())
        for (g in 0 until n) for (b in 0 until n) for (r in 0 until n) {
            val i = ((b * n + g) * n + r) * 3
            for (k in 0..2) buf.put((data[i + k].coerceIn(0f, 1f) * 255f + 0.5f).toInt().toByte())
        }
        buf.position(0)
        return buf
    }

    /** CPU lookup with trilinear interpolation (for the LUT picker's thumbnails). */
    fun apply(rgb: Int): Int {
        val n1 = size - 1f
        val r = ((rgb shr 16) and 0xFF) / 255f * n1; val g = ((rgb shr 8) and 0xFF) / 255f * n1; val b = (rgb and 0xFF) / 255f * n1
        val r0 = r.toInt().coerceAtMost(size - 2); val g0 = g.toInt().coerceAtMost(size - 2); val b0 = b.toInt().coerceAtMost(size - 2)
        val fr = r - r0; val fg = g - g0; val fb = b - b0
        val out = IntArray(3)
        for (k in 0..2) {
            fun v(ri: Int, gi: Int, bi: Int) = data[((bi * size + gi) * size + ri) * 3 + k]
            val c00 = v(r0, g0, b0) * (1 - fr) + v(r0 + 1, g0, b0) * fr
            val c10 = v(r0, g0 + 1, b0) * (1 - fr) + v(r0 + 1, g0 + 1, b0) * fr
            val c01 = v(r0, g0, b0 + 1) * (1 - fr) + v(r0 + 1, g0, b0 + 1) * fr
            val c11 = v(r0, g0 + 1, b0 + 1) * (1 - fr) + v(r0 + 1, g0 + 1, b0 + 1) * fr
            val c = (c00 * (1 - fg) + c10 * fg) * (1 - fb) + (c01 * (1 - fg) + c11 * fg) * fb
            out[k] = (c.coerceIn(0f, 1f) * 255f + 0.5f).toInt()
        }
        return (0xFF shl 24) or (out[0] shl 16) or (out[1] shl 8) or out[2]
    }
}

/**
 * The LUT library: a few built-in looks (generated here, our own grades) plus .cube files the user imports
 * into the app's storage (Settings > LUTs). LUTs are for monitoring: they colour the preview only.
 */
object Luts {
    val BUILT_IN = listOf("Cinema Teal", "Warm Film", "Day for Night", "Bleach Bypass", "Faded", "Mono")
    private val cache = HashMap<String, Lut>()

    private fun dir(c: Context) = File(c.filesDir, "luts").apply { mkdirs() }

    /** Built-in looks first, then imported files (by name without ".cube"). */
    fun names(c: Context): List<String> = BUILT_IN + imported(c)
    fun imported(c: Context): List<String> =
        dir(c).listFiles { f -> f.name.endsWith(".cube", ignoreCase = true) }?.map { it.nameWithoutExtension }?.sorted() ?: emptyList()

    fun load(c: Context, name: String): Lut? = synchronized(cache) {
        cache[name] ?: run {
            val lut = if (name in BUILT_IN) builtIn(name)
                      else runCatching { parseCube(name, File(dir(c), "$name.cube").readText()) }.onFailure { Log.w(TAG, "load $name", it) }.getOrNull()
            lut?.also { cache[name] = it }
        }
    }

    /** Import a .cube file's text; returns the stored name, or null if it isn't a valid 3D LUT. */
    fun import(c: Context, fileName: String, text: String): String? {
        val base = fileName.substringBeforeLast('.').replace(Regex("[^A-Za-z0-9 _.-]"), "_").take(40).ifBlank { "LUT" }
        var name = base; var i = 2
        while (name in BUILT_IN || File(dir(c), "$name.cube").exists()) name = "$base ${i++}"
        runCatching { parseCube(name, text) }.getOrElse { Log.w(TAG, "bad cube", it); return null }
        File(dir(c), "$name.cube").writeText(text)
        return name
    }

    fun delete(c: Context, name: String) {
        if (name in BUILT_IN) return
        File(dir(c), "$name.cube").delete()
        synchronized(cache) { cache.remove(name) }
    }

    /** Parse an Adobe/Resolve .cube 3D LUT (LUT_3D_SIZE, optional DOMAIN_MIN/MAX, rows of "r g b"). */
    fun parseCube(name: String, text: String): Lut {
        var size = 0
        val min = floatArrayOf(0f, 0f, 0f); val maxD = floatArrayOf(1f, 1f, 1f)
        val values = ArrayList<Float>()
        for (raw in text.lineSequence()) {
            val line = raw.substringBefore('#').trim()
            if (line.isEmpty()) continue
            val parts = line.split(Regex("\\s+"))
            when (parts[0].uppercase()) {
                "TITLE" -> {}
                "LUT_3D_SIZE" -> size = parts[1].toInt()
                "LUT_1D_SIZE" -> throw IllegalArgumentException("1D LUTs are not supported")
                "DOMAIN_MIN" -> for (k in 0..2) min[k] = parts[k + 1].toFloat()
                "DOMAIN_MAX" -> for (k in 0..2) maxD[k] = parts[k + 1].toFloat()
                else -> if (parts.size >= 3 && parts[0].first().let { it.isDigit() || it == '-' || it == '.' }) {
                    for (k in 0..2) values += (parts[k].toFloat() - min[k]) / (maxD[k] - min[k])
                }
            }
        }
        require(size in 2..65) { "missing or bad LUT_3D_SIZE" }
        require(values.size == size * size * size * 3) { "expected ${size * size * size} entries, got ${values.size / 3}" }
        return Lut(name, size, values.toFloatArray())
    }

    // ------------------------------------------------------------------ built-in looks
    private fun builtIn(name: String): Lut {
        val n = 33
        val data = FloatArray(n * n * n * 3)
        val out = FloatArray(3)
        for (b in 0 until n) for (g in 0 until n) for (r in 0 until n) {
            grade(name, r / (n - 1f), g / (n - 1f), b / (n - 1f), out)
            val i = ((b * n + g) * n + r) * 3
            data[i] = out[0]; data[i + 1] = out[1]; data[i + 2] = out[2]
        }
        return Lut(name, n, data)
    }

    private fun luma(r: Float, g: Float, b: Float) = 0.2126f * r + 0.7152f * g + 0.0722f * b
    /** Gentle filmic S-curve around mid grey; k = strength. */
    private fun sCurve(x: Float, k: Float): Float {
        val c = x.coerceIn(0f, 1f)
        val s = c * c * (3 - 2 * c)
        return c + (s - c) * k
    }
    private fun mixF(a: Float, b: Float, t: Float) = a + (b - a) * t

    private fun grade(name: String, r0: Float, g0: Float, b0: Float, o: FloatArray) {
        var r = r0; var g = g0; var b = b0
        when (name) {
            "Cinema Teal" -> {
                // teal shadows, warm highlights and skin, a little contrast
                val l = luma(r, g, b)
                val sh = (1 - l).pow(2); val hi = l.pow(2)
                r += -0.06f * sh + 0.07f * hi; g += 0.02f * sh + 0.02f * hi; b += 0.07f * sh - 0.06f * hi
                r = sCurve(r, 0.35f); g = sCurve(g, 0.35f); b = sCurve(b, 0.35f)
                val l2 = luma(r, g, b); val sat = 1.08f
                r = l2 + (r - l2) * sat; g = l2 + (g - l2) * sat * 0.94f; b = l2 + (b - l2) * sat
            }
            "Warm Film" -> {
                r = r * 1.06f + 0.015f; g = g * 1.01f + 0.01f; b = b * 0.90f + 0.02f
                r = sCurve(r, 0.3f); g = sCurve(g, 0.3f); b = sCurve(b, 0.25f)
                // soft highlight roll-off
                r = r / (1 + max(0f, r - 0.8f)); g = g / (1 + max(0f, g - 0.8f)); b = b / (1 + max(0f, b - 0.8f))
            }
            "Day for Night" -> {
                val l = luma(r, g, b)
                r = mixF(r, l, 0.6f) * 0.38f; g = mixF(g, l, 0.6f) * 0.45f; b = mixF(b, l, 0.5f) * 0.62f + 0.02f
                r = sCurve(r, 0.2f); g = sCurve(g, 0.2f); b = sCurve(b, 0.2f)
            }
            "Bleach Bypass" -> {
                val l = luma(r, g, b)
                r = mixF(r, l, 0.55f); g = mixF(g, l, 0.55f); b = mixF(b, l, 0.55f)
                r = sCurve(r, 0.7f); g = sCurve(g, 0.7f); b = sCurve(b, 0.7f)
            }
            "Faded" -> {
                val l = luma(r, g, b)
                r = mixF(r, l, 0.25f); g = mixF(g, l, 0.25f); b = mixF(b, l, 0.25f)
                r = 0.07f + r * 0.86f + 0.02f * (1 - l); g = 0.06f + g * 0.86f; b = 0.06f + b * 0.84f - 0.01f * (1 - l)
            }
            "Mono" -> {
                val l = sCurve(luma(r, g, b), 0.4f)
                r = l; g = l; b = l
            }
        }
        o[0] = min(1f, max(0f, r)); o[1] = min(1f, max(0f, g)); o[2] = min(1f, max(0f, b))
    }

    private const val TAG = "CRCam.Luts"
}
