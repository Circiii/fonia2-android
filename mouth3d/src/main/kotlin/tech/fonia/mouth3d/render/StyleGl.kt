package tech.fonia.mouth3d.render

import android.opengl.GLES30
import tech.fonia.mouth3d.Articulator
import tech.fonia.mouth3d.PoseSpec
import tech.fonia.mouth3d.SceneFrame
import tech.fonia.mouth3d.style.Blend
import tech.fonia.mouth3d.style.MeshBuilder
import tech.fonia.mouth3d.style.Pass
import tech.fonia.mouth3d.style.StylePalette
import tech.fonia.mouth3d.style.StyleScene
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.nio.IntBuffer

/**
 * The GL ES 3 side of the stylised mouth: the frame's geometry ([StyleScene] → [MeshBuilder]) in one dynamic buffer,
 * one shader (lit / flat / particle / skin, see the fragment code; the JVM preview `StylePreview` is its copy), batches
 * drawn in order with their blend state. When the EGL surface has no MSAA it renders into a 4× multisampled
 * framebuffer and resolves it, so edges stay crisp on every GPU. Premultiplied alpha on a transparent background.
 */
internal class StyleGl {
    private var program = 0
    private val vao = IntArray(1)
    private val vbo = IntArray(1)
    private val ibo = IntArray(1)
    private var vboCap = 0
    private var iboCap = 0
    private var vbuf: FloatBuffer? = null
    private var ibuf: IntBuffer? = null
    private val mb = MeshBuilder()
    private var uMV = 0; private var uP = 0; private var uLight = 0; private var uRim = 0; private var uGlow = 0; private var uVig = 0; private var uDissolve = 0
    private var uClip = 0; private var uClipOn = 0; private var uLipZone = 0; private var uLipCol = 0
    private val fbo = IntArray(1)
    private val rbo = IntArray(2)
    private var fboW = 0; private var fboH = 0; private var fboSamples = 0

    fun create() {
        program = link(VERTEX, FRAGMENT)
        uMV = GLES30.glGetUniformLocation(program, "uMV"); uP = GLES30.glGetUniformLocation(program, "uP")
        uLight = GLES30.glGetUniformLocation(program, "uLight"); uRim = GLES30.glGetUniformLocation(program, "uRim")
        uGlow = GLES30.glGetUniformLocation(program, "uGlow"); uVig = GLES30.glGetUniformLocation(program, "uVig")
        uDissolve = GLES30.glGetUniformLocation(program, "uDissolve")
        uClip = GLES30.glGetUniformLocation(program, "uClip"); uClipOn = GLES30.glGetUniformLocation(program, "uClipOn")
        uLipZone = GLES30.glGetUniformLocation(program, "uLipZone"); uLipCol = GLES30.glGetUniformLocation(program, "uLipCol")
        GLES30.glGenVertexArrays(1, vao, 0)
        GLES30.glGenBuffers(1, vbo, 0)
        GLES30.glGenBuffers(1, ibo, 0)
        GLES30.glBindVertexArray(vao[0])
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, vbo[0])
        GLES30.glBindBuffer(GLES30.GL_ELEMENT_ARRAY_BUFFER, ibo[0])
        val s = MeshBuilder.STRIDE * 4
        attrib(0, 3, s, 0); attrib(1, 3, s, 12); attrib(2, 4, s, 24); attrib(3, 3, s, 40); attrib(4, 2, s, 52)
        GLES30.glBindVertexArray(0)
    }

    /** [eglSamples]: the surface's own MSAA (0 = none: use the offscreen multisampled framebuffer). */
    fun render(frame: SceneFrame, spec: PoseSpec, pal: StylePalette, highlight: Articulator?, width: Int, height: Int, eglSamples: Int): Int {
        val cam = StyleScene.camera(frame, width, height)
        StyleScene.build(mb, frame, spec, pal, highlight, cam)
        upload()

        val samples = if (eglSamples > 0) 0 else ensureFbo(width, height)
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, if (samples > 0) fbo[0] else 0)
        GLES30.glViewport(0, 0, width, height)
        GLES30.glClearColor(0f, 0f, 0f, 0f)
        GLES30.glDepthMask(true)
        GLES30.glStencilMask(0xFF)
        GLES30.glClearStencil(0)
        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT or GLES30.GL_DEPTH_BUFFER_BIT or GLES30.GL_STENCIL_BUFFER_BIT)
        GLES30.glEnable(GLES30.GL_DEPTH_TEST)
        GLES30.glDepthFunc(GLES30.GL_LEQUAL)
        GLES30.glDisable(GLES30.GL_CULL_FACE)
        GLES30.glEnable(GLES30.GL_BLEND)
        GLES30.glBlendFunc(GLES30.GL_ONE, GLES30.GL_ONE_MINUS_SRC_ALPHA)
        GLES30.glUseProgram(program)
        GLES30.glUniformMatrix4fv(uMV, 1, false, cam.mv, 0)
        GLES30.glUniformMatrix4fv(uP, 1, false, cam.proj, 0)
        GLES30.glUniform3fv(uLight, 1, cam.light, 0)
        GLES30.glUniform3fv(uRim, 1, pal.rim, 0)
        GLES30.glUniform3fv(uGlow, 1, pal.highlight, 0)
        GLES30.glUniform4fv(uVig, 1, cam.vignette, 0)
        GLES30.glUniform4fv(uClip, 1, cam.clip, 0)
        GLES30.glUniform4fv(uLipZone, 1, cam.lipZone, 0)
        GLES30.glUniform3fv(uLipCol, 1, pal.lips, 0)
        GLES30.glBindVertexArray(vao[0])
        for (b in mb.batches) {
            GLES30.glUniform1f(uDissolve, b.dissolve)
            GLES30.glUniform1f(uClipOn, if (b.clip) 1f else 0f)
            when (b.pass) {
                Pass.Draw -> {
                    GLES30.glDepthMask(b.blend != Blend.AlphaNoDepthWrite)
                    GLES30.glDrawElements(GLES30.GL_TRIANGLES, b.count, GLES30.GL_UNSIGNED_INT, b.first * 4)
                }
                Pass.Stencil -> {
                    // parity of the closed mesh behind the cut: odd = the cut plane lies inside it
                    if (b.clearStencil) GLES30.glClear(GLES30.GL_STENCIL_BUFFER_BIT)
                    GLES30.glEnable(GLES30.GL_STENCIL_TEST)
                    GLES30.glStencilFunc(GLES30.GL_ALWAYS, 0, 0xFF)
                    GLES30.glStencilOp(GLES30.GL_KEEP, GLES30.GL_KEEP, GLES30.GL_INVERT)
                    GLES30.glColorMask(false, false, false, false)
                    GLES30.glDepthMask(false)
                    GLES30.glDisable(GLES30.GL_DEPTH_TEST)
                    GLES30.glDrawElements(GLES30.GL_TRIANGLES, b.count, GLES30.GL_UNSIGNED_INT, b.first * 4)
                    GLES30.glColorMask(true, true, true, true)
                    GLES30.glEnable(GLES30.GL_DEPTH_TEST)
                    GLES30.glDisable(GLES30.GL_STENCIL_TEST)
                }
                Pass.Cap -> {
                    GLES30.glEnable(GLES30.GL_STENCIL_TEST)
                    GLES30.glStencilFunc(GLES30.GL_NOTEQUAL, 0, 0x01)
                    GLES30.glStencilOp(GLES30.GL_KEEP, GLES30.GL_KEEP, GLES30.GL_KEEP)
                    GLES30.glDepthMask(true)
                    GLES30.glDrawElements(GLES30.GL_TRIANGLES, b.count, GLES30.GL_UNSIGNED_INT, b.first * 4)
                    GLES30.glDisable(GLES30.GL_STENCIL_TEST)
                }
            }
        }
        GLES30.glBindVertexArray(0)
        GLES30.glDepthMask(true)
        if (samples > 0) {
            GLES30.glBindFramebuffer(GLES30.GL_READ_FRAMEBUFFER, fbo[0])
            GLES30.glBindFramebuffer(GLES30.GL_DRAW_FRAMEBUFFER, 0)
            GLES30.glBlitFramebuffer(0, 0, width, height, 0, 0, width, height, GLES30.GL_COLOR_BUFFER_BIT, GLES30.GL_NEAREST)
            GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
        }
        return if (eglSamples > 0) eglSamples else samples
    }

    private fun upload() {
        val nf = mb.vertexCount * MeshBuilder.STRIDE
        val ni = mb.indexCount
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, vbo[0])
        if (nf > vboCap) {
            vboCap = nf + nf / 2
            vbuf = ByteBuffer.allocateDirect(vboCap * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
            GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, vboCap * 4, null, GLES30.GL_DYNAMIC_DRAW)
        }
        val vb = vbuf!!
        vb.position(0); vb.put(mb.data, 0, nf); vb.position(0)
        GLES30.glBufferSubData(GLES30.GL_ARRAY_BUFFER, 0, nf * 4, vb)
        GLES30.glBindBuffer(GLES30.GL_ELEMENT_ARRAY_BUFFER, ibo[0])
        if (ni > iboCap) {
            iboCap = ni + ni / 2
            ibuf = ByteBuffer.allocateDirect(iboCap * 4).order(ByteOrder.nativeOrder()).asIntBuffer()
            GLES30.glBufferData(GLES30.GL_ELEMENT_ARRAY_BUFFER, iboCap * 4, null, GLES30.GL_DYNAMIC_DRAW)
        }
        val ib = ibuf!!
        ib.position(0); ib.put(mb.indices, 0, ni); ib.position(0)
        GLES30.glBufferSubData(GLES30.GL_ELEMENT_ARRAY_BUFFER, 0, ni * 4, ib)
    }

    /** A multisampled colour + depth framebuffer of the surface's size; returns its sample count (0 if unavailable). */
    private fun ensureFbo(w: Int, h: Int): Int {
        if (fboSamples > 0 && fboW == w && fboH == h) return fboSamples
        if (fboSamples < 0) return 0
        releaseFbo()
        val max = IntArray(1)
        GLES30.glGetIntegerv(GLES30.GL_MAX_SAMPLES, max, 0)
        val s = minOf(4, max[0])
        if (s < 2) { fboSamples = -1; return 0 }
        GLES30.glGenFramebuffers(1, fbo, 0)
        GLES30.glGenRenderbuffers(2, rbo, 0)
        GLES30.glBindRenderbuffer(GLES30.GL_RENDERBUFFER, rbo[0])
        GLES30.glRenderbufferStorageMultisample(GLES30.GL_RENDERBUFFER, s, GLES30.GL_RGBA8, w, h)
        GLES30.glBindRenderbuffer(GLES30.GL_RENDERBUFFER, rbo[1])
        GLES30.glRenderbufferStorageMultisample(GLES30.GL_RENDERBUFFER, s, GLES30.GL_DEPTH24_STENCIL8, w, h)
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, fbo[0])
        GLES30.glFramebufferRenderbuffer(GLES30.GL_FRAMEBUFFER, GLES30.GL_COLOR_ATTACHMENT0, GLES30.GL_RENDERBUFFER, rbo[0])
        GLES30.glFramebufferRenderbuffer(GLES30.GL_FRAMEBUFFER, GLES30.GL_DEPTH_STENCIL_ATTACHMENT, GLES30.GL_RENDERBUFFER, rbo[1])
        val ok = GLES30.glCheckFramebufferStatus(GLES30.GL_FRAMEBUFFER) == GLES30.GL_FRAMEBUFFER_COMPLETE
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
        if (!ok) { releaseFbo(); fboSamples = -1; return 0 }
        fboW = w; fboH = h; fboSamples = s
        return s
    }

    private fun releaseFbo() {
        if (fboSamples > 0) {
            GLES30.glDeleteFramebuffers(1, fbo, 0)
            GLES30.glDeleteRenderbuffers(2, rbo, 0)
        }
        fboSamples = 0; fboW = 0; fboH = 0
    }

    fun release() {
        releaseFbo()
        if (program != 0) GLES30.glDeleteProgram(program)
        GLES30.glDeleteBuffers(1, vbo, 0); GLES30.glDeleteBuffers(1, ibo, 0); GLES30.glDeleteVertexArrays(1, vao, 0)
        program = 0
    }

    private fun attrib(loc: Int, size: Int, stride: Int, offset: Int) {
        GLES30.glEnableVertexAttribArray(loc)
        GLES30.glVertexAttribPointer(loc, size, GLES30.GL_FLOAT, false, stride, offset)
    }

    companion object {
        private fun compile(type: Int, src: String): Int {
            val s = GLES30.glCreateShader(type)
            GLES30.glShaderSource(s, src)
            GLES30.glCompileShader(s)
            val ok = IntArray(1)
            GLES30.glGetShaderiv(s, GLES30.GL_COMPILE_STATUS, ok, 0)
            if (ok[0] == 0) error("shader: " + GLES30.glGetShaderInfoLog(s))
            return s
        }

        private fun link(vs: String, fs: String): Int {
            val p = GLES30.glCreateProgram()
            val v = compile(GLES30.GL_VERTEX_SHADER, vs)
            val f = compile(GLES30.GL_FRAGMENT_SHADER, fs)
            GLES30.glAttachShader(p, v); GLES30.glAttachShader(p, f)
            GLES30.glLinkProgram(p)
            val ok = IntArray(1)
            GLES30.glGetProgramiv(p, GLES30.GL_LINK_STATUS, ok, 0)
            if (ok[0] == 0) error("program: " + GLES30.glGetProgramInfoLog(p))
            GLES30.glDeleteShader(v); GLES30.glDeleteShader(f)
            return p
        }

        private const val VERTEX = """#version 300 es
layout(location = 0) in vec3 aPos;
layout(location = 1) in vec3 aNrm;
layout(location = 2) in vec4 aCol;
layout(location = 3) in vec3 aKgg;
layout(location = 4) in vec2 aUV;
uniform mat4 uMV;
uniform mat4 uP;
out vec3 vM;
out vec3 vN;
out vec3 vV;
out vec4 vCol;
flat out float vKind;
out vec2 vGG;
out vec2 vUV;
void main() {
  vec4 e = uMV * vec4(aPos, 1.0);
  vM = aPos;
  vV = -e.xyz;
  vN = mat3(uMV) * aNrm;
  vCol = aCol;
  vKind = aKgg.x;
  vGG = aKgg.yz;
  vUV = aUV;
  gl_Position = uP * e;
}
"""

        // Keep in sync with StylePreview.shade (JVM test sources).
        private const val FRAGMENT = """#version 300 es
precision highp float;
uniform vec3 uLight;
uniform vec3 uRim;
uniform vec3 uGlow;
uniform vec4 uVig;
uniform float uDissolve;
uniform vec4 uClip;
uniform float uClipOn;
uniform vec4 uLipZone;
uniform vec3 uLipCol;
in vec3 vM;
in vec3 vN;
in vec3 vV;
in vec4 vCol;
flat in float vKind;
in vec2 vGG;
in vec2 vUV;
out vec4 o;
void main() {
  if (uClipOn > 0.5 && dot(vec4(vM, 1.0), uClip) < 0.0) discard;
  if (uDissolve < 0.999) {
    float n = fract(52.9829189 * fract(dot(gl_FragCoord.xy, vec2(0.06711056, 0.00583715))));
    if (n > uDissolve) discard;
  }
  float kind = vKind;
  float vig = 1.0;
  if (kind > 7.5) {
    kind -= 8.0;
    vec2 d = (vec2(vM.y, vM.z) - uVig.xy) / uVig.zw;
    vig = 1.0 - smoothstep(0.86, 1.0, length(d));
  }
  vec3 c = vCol.rgb;
  float a = vCol.a * vig;
  float glow = vGG.y;
  if (kind < 0.5 || (kind > 2.5 && kind < 3.5)) {
    vec3 n = normalize(vN);
    vec3 v = normalize(vV);
    if (dot(n, v) < 0.0) n = -n;
    bool skin = kind > 2.5;
    float amb = skin ? 0.965 : 0.58;
    float dd = dot(n, uLight) * 0.5 + 0.5;
    c *= amb + (1.0 - amb) * smoothstep(0.12, 0.95, dd);
    vec3 h = normalize(uLight + v);
    float gl = vGG.x;
    float sp = gl * 0.65 * pow(max(dot(n, h), 0.0), 14.0 + 90.0 * gl);
    float nv = max(dot(n, v), 0.0);
    float rim = pow(1.0 - nv, 3.0) * (skin ? 0.05 : 0.16);
    float ge = glow * (0.22 + 0.7 * pow(1.0 - nv, 2.0));
    c += vec3(sp) + uRim * rim + uGlow * ge;
  } else if (kind < 1.5) {
    c = mix(c, uGlow, glow * 0.16);
  } else if (kind > 3.5) {
    // the head's cut: tissue, the lips' red in front of the teeth
    float lip = smoothstep(uLipZone.x - 0.6, uLipZone.x + 0.6, vM.z) * smoothstep(uLipZone.y - 0.8, uLipZone.y + 0.8, vM.y) * (1.0 - smoothstep(uLipZone.z - 0.8, uLipZone.z + 0.8, vM.y));
    c = mix(c, uLipCol, lip);
  } else {
    a *= 1.0 - smoothstep(0.55, 1.0, length(vUV * 2.0 - 1.0));
  }
  o = vec4(min(c, vec3(1.0)) * a, a);
}
"""
    }
}
