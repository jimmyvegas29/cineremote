package com.cineremote.camera

import android.opengl.GLES11Ext
import android.opengl.GLES20
import org.webrtc.GlRectDrawer
import org.webrtc.GlShader
import org.webrtc.RendererCommon
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

/**
 * Exposure and focus aids drawn on the phone's preview only (never in recordings or the stream): zebras -
 * diagonal stripes over picture areas in a brightness band - and focus assist (peaking) - edges above a
 * sharpness threshold drawn as coloured lines or brightened ("Peak"). Both run in the preview renderer's
 * fragment shader; this object holds the settings, read on the render thread every frame.
 */
class FocusAids(prefs: Prefs) {
    /** Zebra level: "MG" (mid grey), "MG+1" (one stop over), "75".."100" (percent), or null for off. */
    @Volatile var zebra: String? = prefs.zebra
    @Volatile var focusAssist = prefs.focusAssist
    /** Focus assist style: true = "Peak" (brightened edges), false = coloured lines. */
    @Volatile var peakStyle = prefs.focusAssistPeak
    @Volatile var color = prefs.focusAssistColor

    /** The (low, high) luma band zebras cover, on the 0..1 Rec.709 code values the preview shows. */
    private fun zebraBand(): Pair<Float, Float>? = when (val z = zebra) {
        null -> null
        "MG" -> 0.38f to 0.44f            // 18% grey under the Rec.709 curve sits at ~0.41
        "MG+1" -> 0.56f to 0.63f          // one stop over (36% reflectance) at ~0.59
        else -> (z.toIntOrNull() ?: 95).let { p -> (p / 100f - 0.02f).coerceAtMost(0.97f) to 2f }
    }

    /** The preview renderer's drawer: draws each frame through our shader (a [RendererCommon.GlDrawer]). */
    fun drawer(): RendererCommon.GlDrawer = Drawer()

    private inner class Drawer : RendererCommon.GlDrawer {
        private inner class Prog(val shader: GlShader) {
            val tex = shader.getUniformLocation("tex"); val texMat = shader.getUniformLocation("tex_mat")
            val step = shader.getUniformLocation("texStep"); val zebraBand = shader.getUniformLocation("zebraBand")
            val zebraOn = shader.getUniformLocation("zebraOn"); val peakOn = shader.getUniformLocation("peakOn")
            val peakColor = shader.getUniformLocation("peakColor"); val peakGlow = shader.getUniformLocation("peakGlow")
            val peakThresh = shader.getUniformLocation("peakThresh")
        }
        private var oes: Prog? = null
        private var rgb: Prog? = null
        private val yuvFallback = GlRectDrawer()       // only camera textures (OES) reach this renderer in practice

        override fun drawOes(t: Int, m: FloatArray, fw: Int, fh: Int, vx: Int, vy: Int, vw: Int, vh: Int) =
            draw(true, t, m, vx, vy, vw, vh)
        override fun drawRgb(t: Int, m: FloatArray, fw: Int, fh: Int, vx: Int, vy: Int, vw: Int, vh: Int) =
            draw(false, t, m, vx, vy, vw, vh)
        override fun drawYuv(t: IntArray, m: FloatArray, fw: Int, fh: Int, vx: Int, vy: Int, vw: Int, vh: Int) =
            yuvFallback.drawYuv(t, m, fw, fh, vx, vy, vw, vh)

        private fun draw(isOes: Boolean, texture: Int, texMatrix: FloatArray, vx: Int, vy: Int, vw: Int, vh: Int) {
            val p = (if (isOes) oes else rgb) ?: Prog(GlShader(VERTEX, fragment(isOes))).also { if (isOes) oes = it else rgb = it }
            p.shader.useProgram()
            val target = if (isOes) GLES11Ext.GL_TEXTURE_EXTERNAL_OES else GLES20.GL_TEXTURE_2D
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
            GLES20.glBindTexture(target, texture)
            GLES20.glUniform1i(p.tex, 0)
            GLES20.glUniformMatrix4fv(p.texMat, 1, false, texMatrix, 0)
            p.shader.setVertexAttribArray("in_pos", 2, RECT)
            p.shader.setVertexAttribArray("in_tc", 2, RECT_TC)
            // one display pixel in texture coordinates (the texture matrix may swap axes, so keep it square)
            val px = 1f / maxOf(vw, vh, 1)
            GLES20.glUniform2f(p.step, px, px)
            val band = zebraBand()
            GLES20.glUniform1f(p.zebraOn, if (band != null) 1f else 0f)
            GLES20.glUniform2f(p.zebraBand, band?.first ?: 2f, band?.second ?: 2f)
            GLES20.glUniform1f(p.peakOn, if (focusAssist) 1f else 0f)
            GLES20.glUniform1f(p.peakGlow, if (peakStyle) 1f else 0f)
            GLES20.glUniform1f(p.peakThresh, PEAK_THRESHOLD)
            val c = COLORS[color] ?: COLORS.getValue("Red")
            GLES20.glUniform3f(p.peakColor, c[0], c[1], c[2])
            GLES20.glViewport(vx, vy, vw, vh)
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
            GLES20.glBindTexture(target, 0)
        }

        override fun release() {
            oes?.shader?.release(); oes = null
            rgb?.shader?.release(); rgb = null
            yuvFallback.release()
        }
    }

    companion object {
        /** Sobel luma gradient above which an edge counts as in focus (tuned on the S20 FE preview). */
        const val PEAK_THRESHOLD = 0.28f
        val COLORS = linkedMapOf(
            "Red" to floatArrayOf(1f, 0.15f, 0.15f), "Green" to floatArrayOf(0.2f, 1f, 0.2f),
            "Blue" to floatArrayOf(0.25f, 0.55f, 1f), "White" to floatArrayOf(1f, 1f, 1f), "Black" to floatArrayOf(0f, 0f, 0f))
        val ZEBRA_LEVELS = listOf("MG", "MG+1", "75", "80", "85", "90", "95", "100")
        fun zebraLabel(z: String) = when (z) { "MG" -> "Mid Grey"; "MG+1" -> "MG+1"; else -> "$z%" }

        private fun floats(vararg v: Float): FloatBuffer =
            ByteBuffer.allocateDirect(v.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().apply { put(v); position(0) }
        private val RECT = floats(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f)
        private val RECT_TC = floats(0f, 0f, 1f, 0f, 0f, 1f, 1f, 1f)

        private const val VERTEX = """
varying vec2 tc;
attribute vec4 in_pos;
attribute vec4 in_tc;
uniform mat4 tex_mat;
void main() {
  gl_Position = in_pos;
  tc = (tex_mat * in_tc).xy;
}
"""

        /** The fragment shader for a camera (external OES) or plain RGB texture. */
        private fun fragment(oes: Boolean): String =
            (if (oes) "#extension GL_OES_EGL_image_external : require\nprecision mediump float;\nvarying vec2 tc;\nuniform samplerExternalOES tex;\n"
             else "precision mediump float;\nvarying vec2 tc;\nuniform sampler2D tex;\n") + FRAGMENT_BODY

        private const val FRAGMENT_BODY = """
#define sample(p) texture2D(tex, p)
uniform vec2 texStep;
uniform vec2 zebraBand;
uniform float zebraOn;
uniform float peakOn;
uniform vec3 peakColor;
uniform float peakGlow;
uniform float peakThresh;

float luma(vec4 c) { return dot(c.rgb, vec3(0.2126, 0.7152, 0.0722)); }

void main() {
  vec4 c = sample(tc);
  vec3 rgb = c.rgb;
  if (peakOn > 0.5) {
    vec2 s = texStep * 1.5;
    float tl = luma(sample(tc + vec2(-s.x, -s.y)));
    float t  = luma(sample(tc + vec2(0.0, -s.y)));
    float tr = luma(sample(tc + vec2(s.x, -s.y)));
    float l  = luma(sample(tc + vec2(-s.x, 0.0)));
    float r  = luma(sample(tc + vec2(s.x, 0.0)));
    float bl = luma(sample(tc + vec2(-s.x, s.y)));
    float b  = luma(sample(tc + vec2(0.0, s.y)));
    float br = luma(sample(tc + vec2(s.x, s.y)));
    float gx = (tr + 2.0 * r + br) - (tl + 2.0 * l + bl);
    float gy = (bl + 2.0 * b + br) - (tl + 2.0 * t + tr);
    float g = length(vec2(gx, gy));
    if (g > peakThresh) {
      if (peakGlow > 0.5) rgb = mix(rgb, vec3(1.0), clamp((g - peakThresh) * 3.0 + 0.35, 0.0, 0.9));
      else rgb = peakColor;
    }
  }
  if (zebraOn > 0.5) {
    float y = luma(c);
    if (y >= zebraBand.x && y <= zebraBand.y) {
      float stripe = mod(gl_FragCoord.x + gl_FragCoord.y, 16.0);
      if (stripe < 7.0) rgb = mix(rgb, vec3(0.0), 0.7);
    }
  }
  gl_FragColor = vec4(rgb, c.a);
}
"""
    }
}
