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
 * Monitoring tools drawn on the phone's preview only (never in recordings or the stream), all in the preview
 * renderer's fragment shader:
 *  - display LUT: a 3D colour look applied to the picture
 *  - false colour: exposure bands painted over a greyscale picture
 *  - focus assist (peaking): edges above a sharpness threshold as coloured lines or a white glow ("Peak")
 *  - zebras: diagonal stripes over picture areas in a brightness band
 * Exposure tools measure the camera signal before the LUT. This object holds the settings, which the render
 * thread reads every frame.
 */
class MonitorAids(prefs: Prefs) {
    /** Zebra level: "MG" (mid grey), "MG+1" (one stop over), "75".."100" (percent), or null for off. */
    @Volatile var zebra: String? = prefs.zebra
    @Volatile var focusAssist = prefs.focusAssist
    /** Focus assist style: true = "Peak" (brightened edges), false = coloured lines. */
    @Volatile var peakStyle = prefs.focusAssistPeak
    @Volatile var color = prefs.focusAssistColor
    @Volatile var falseColor = false
    /** The display LUT (null = none); [lutVersion] tells the render thread to upload a new one. */
    @Volatile var lut: Lut? = null
        set(v) { field = v; lutVersion++ }
    @Volatile private var lutVersion = 0

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
            val peakThresh = shader.getUniformLocation("peakThresh"); val falseOn = shader.getUniformLocation("falseOn")
            val lutTex = shader.getUniformLocation("lut"); val lutOn = shader.getUniformLocation("lutOn")
            val lutSize = shader.getUniformLocation("lutSize")
        }
        private var oes: Prog? = null
        private var rgb: Prog? = null
        private val yuvFallback = GlRectDrawer()       // only camera textures (OES) reach this renderer in practice
        private var lutTexture = 0
        private var uploadedVersion = -1
        private var uploadedSize = 0

        override fun drawOes(t: Int, m: FloatArray, fw: Int, fh: Int, vx: Int, vy: Int, vw: Int, vh: Int) =
            draw(true, t, m, vx, vy, vw, vh)
        override fun drawRgb(t: Int, m: FloatArray, fw: Int, fh: Int, vx: Int, vy: Int, vw: Int, vh: Int) =
            draw(false, t, m, vx, vy, vw, vh)
        override fun drawYuv(t: IntArray, m: FloatArray, fw: Int, fh: Int, vx: Int, vy: Int, vw: Int, vh: Int) =
            yuvFallback.drawYuv(t, m, fw, fh, vx, vy, vw, vh)

        /** Upload the LUT atlas when it changed (on the render thread, which owns the GL context). */
        private fun syncLut(): Lut? {
            val l = lut
            if (uploadedVersion != lutVersion) {
                uploadedVersion = lutVersion
                if (l != null) {
                    if (lutTexture == 0) { val ids = IntArray(1); GLES20.glGenTextures(1, ids, 0); lutTexture = ids[0] }
                    GLES20.glActiveTexture(GLES20.GL_TEXTURE1)
                    GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, lutTexture)
                    GLES20.glPixelStorei(GLES20.GL_UNPACK_ALIGNMENT, 1)
                    GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGB, l.size * l.size, l.size, 0,
                        GLES20.GL_RGB, GLES20.GL_UNSIGNED_BYTE, l.atlas())
                    for ((k, v) in listOf(GLES20.GL_TEXTURE_MIN_FILTER to GLES20.GL_LINEAR, GLES20.GL_TEXTURE_MAG_FILTER to GLES20.GL_LINEAR,
                            GLES20.GL_TEXTURE_WRAP_S to GLES20.GL_CLAMP_TO_EDGE, GLES20.GL_TEXTURE_WRAP_T to GLES20.GL_CLAMP_TO_EDGE))
                        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, k, v)
                    uploadedSize = l.size
                    GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
                }
            }
            return l
        }

        private fun draw(isOes: Boolean, texture: Int, texMatrix: FloatArray, vx: Int, vy: Int, vw: Int, vh: Int) {
            val p = (if (isOes) oes else rgb) ?: Prog(GlShader(VERTEX, fragment(isOes))).also { if (isOes) oes = it else rgb = it }
            val l = syncLut()
            p.shader.useProgram()
            val target = if (isOes) GLES11Ext.GL_TEXTURE_EXTERNAL_OES else GLES20.GL_TEXTURE_2D
            GLES20.glActiveTexture(GLES20.GL_TEXTURE1)
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, if (l != null) lutTexture else 0)
            GLES20.glUniform1i(p.lutTex, 1)
            GLES20.glUniform1f(p.lutOn, if (l != null) 1f else 0f)
            GLES20.glUniform1f(p.lutSize, uploadedSize.toFloat().coerceAtLeast(2f))
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
            GLES20.glUniform1f(p.falseOn, if (falseColor) 1f else 0f)
            val c = COLORS[color] ?: COLORS.getValue("Red")
            GLES20.glUniform3f(p.peakColor, c[0], c[1], c[2])
            GLES20.glViewport(vx, vy, vw, vh)
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
            GLES20.glBindTexture(target, 0)
        }

        override fun release() {
            oes?.shader?.release(); oes = null
            rgb?.shader?.release(); rgb = null
            if (lutTexture != 0) { GLES20.glDeleteTextures(1, intArrayOf(lutTexture), 0); lutTexture = 0 }
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
        /** False colour key, low to high: (label, rgb). Matches the shader's bands. */
        val FALSE_KEY = listOf("Black clip" to 0x7A1FA0, "Under" to 0x1E5BFF, "18% grey" to 0x2FD44A,
            "+1 stop" to 0xFF7FB4, "Bright" to 0xFFE600, "Clip" to 0xFF2020)

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

        private const val PRECISION = "#ifdef GL_FRAGMENT_PRECISION_HIGH\nprecision highp float;\n#else\nprecision mediump float;\n#endif\n"

        /** The fragment shader for a camera (external OES) or plain RGB texture. */
        private fun fragment(oes: Boolean): String =
            (if (oes) "#extension GL_OES_EGL_image_external : require\n$PRECISION" + "varying vec2 tc;\nuniform samplerExternalOES tex;\n"
             else PRECISION + "varying vec2 tc;\nuniform sampler2D tex;\n") + FRAGMENT_BODY

        private const val FRAGMENT_BODY = """
#define sample(p) texture2D(tex, p)
uniform vec2 texStep;
uniform vec2 zebraBand;
uniform float zebraOn;
uniform float peakOn;
uniform vec3 peakColor;
uniform float peakGlow;
uniform float peakThresh;
uniform float falseOn;
uniform sampler2D lut;
uniform float lutOn;
uniform float lutSize;

float luma(vec4 c) { return dot(c.rgb, vec3(0.2126, 0.7152, 0.0722)); }

// 3D LUT stored as blue slices side by side: trilinear = bilinear within two slices, mixed
vec3 applyLut(vec3 c) {
  float n = lutSize;
  float b = clamp(c.b, 0.0, 1.0) * (n - 1.0);
  float b0 = floor(b);
  float b1 = min(b0 + 1.0, n - 1.0);
  float rx = clamp(c.r, 0.0, 1.0) * (n - 1.0) + 0.5;
  float gy = (clamp(c.g, 0.0, 1.0) * (n - 1.0) + 0.5) / n;
  vec3 s0 = texture2D(lut, vec2((b0 * n + rx) / (n * n), gy)).rgb;
  vec3 s1 = texture2D(lut, vec2((b1 * n + rx) / (n * n), gy)).rgb;
  return mix(s0, s1, b - b0);
}

vec3 falseColour(float y) {
  if (y < 0.025) return vec3(0.48, 0.12, 0.63);
  if (y < 0.10) return vec3(0.12, 0.36, 1.0);
  if (y >= 0.38 && y < 0.44) return vec3(0.18, 0.83, 0.29);
  if (y >= 0.52 && y < 0.58) return vec3(1.0, 0.5, 0.71);
  if (y >= 0.97) return vec3(1.0, 0.13, 0.13);
  if (y >= 0.92) return vec3(1.0, 0.9, 0.0);
  return vec3(y * 0.85);
}

void main() {
  vec4 c = sample(tc);
  float y = luma(c);
  vec3 rgb = lutOn > 0.5 ? applyLut(c.rgb) : c.rgb;
  if (falseOn > 0.5) rgb = falseColour(y);
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
  if (zebraOn > 0.5 && y >= zebraBand.x && y <= zebraBand.y) {
    float stripe = mod(gl_FragCoord.x + gl_FragCoord.y, 16.0);
    if (stripe < 7.0) rgb = mix(rgb, vec3(0.0), 0.7);
  }
  gl_FragColor = vec4(rgb, c.a);
}
"""
    }
}
