package app.hypnotica.core.render

import android.opengl.GLES30
import android.util.Log

/** GLSL ES 3.00 sources and a small compile/link helper. */
object Shaders {

    const val TAG = "HypnoticaGL"

    /**
     * One instanced quad covers every solid shape the app draws: bars, dots,
     * rings with a gap, lines (a rotated bar) and soft glows.
     */
    val SHAPE_VERT = """
        #version 300 es
        layout(location = 0) in vec2 aCorner;
        layout(location = 1) in vec4 iGeom;
        layout(location = 2) in vec4 iMisc;
        layout(location = 3) in vec4 iColor;
        uniform vec4 uCam;
        uniform vec2 uCamRot;
        out vec2 vLocal;
        out vec4 vColor;
        out vec2 vExtra;
        flat out int vShape;
        void main() {
            float c = cos(iMisc.x);
            float s = sin(iMisc.x);
            vec2 local = aCorner * iGeom.zw;
            vec2 world = iGeom.xy + vec2(local.x * c - local.y * s, local.x * s + local.y * c);
            vec2 rel = world - uCam.xy;
            vec2 rot = vec2(rel.x * uCamRot.x - rel.y * uCamRot.y,
                            rel.x * uCamRot.y + rel.y * uCamRot.x);
            gl_Position = vec4(rot * uCam.zw, 0.0, 1.0);
            vLocal = aCorner;
            vColor = iColor;
            vExtra = iMisc.zw;
            vShape = int(iMisc.y + 0.5);
        }
    """.trimIndent()

    val SHAPE_FRAG = """
        #version 300 es
        precision mediump float;
        in vec2 vLocal;
        in vec4 vColor;
        in vec2 vExtra;
        flat in int vShape;
        out vec4 fragColor;
        void main() {
            float a = vColor.a;
            if (vShape == 1) {
                float d = length(vLocal);
                a *= 1.0 - smoothstep(0.86, 1.0, d);
            } else if (vShape == 2) {
                float d = length(vLocal);
                float inner = vExtra.x;
                a *= (1.0 - smoothstep(0.93, 1.0, d)) *
                     smoothstep(max(0.0, inner - 0.04), inner + 0.02, d);
                float gap = vExtra.y;
                if (gap > 0.0) {
                    float ang = atan(vLocal.y, vLocal.x);
                    if (ang < 0.0) ang += 6.28318530718;
                    if (ang < gap) a = 0.0;
                }
            } else if (vShape == 3) {
                float d = length(vLocal);
                float f = max(0.0, 1.0 - d);
                a *= f * f;
            }
            if (a <= 0.003) discard;
            fragColor = vec4(vColor.rgb * a, a);
        }
    """.trimIndent()

    /** The huge-count path: 16 bytes a dot, one draw call for the whole field. */
    val POINT_VERT = """
        #version 300 es
        layout(location = 0) in vec3 aPosSize;
        layout(location = 1) in vec4 aColor;
        uniform vec4 uCam;
        uniform vec2 uCamRot;
        uniform float uPixelsPerWorld;
        out vec4 vColor;
        void main() {
            vec2 rel = aPosSize.xy - uCam.xy;
            vec2 rot = vec2(rel.x * uCamRot.x - rel.y * uCamRot.y,
                            rel.x * uCamRot.y + rel.y * uCamRot.x);
            gl_Position = vec4(rot * uCam.zw, 0.0, 1.0);
            gl_PointSize = clamp(aPosSize.z * uPixelsPerWorld, 1.0, 96.0);
            vColor = aColor;
        }
    """.trimIndent()

    val POINT_FRAG = """
        #version 300 es
        precision mediump float;
        in vec4 vColor;
        out vec4 fragColor;
        void main() {
            vec2 p = gl_PointCoord * 2.0 - 1.0;
            float d = dot(p, p);
            if (d > 1.0) discard;
            float a = vColor.a * (1.0 - smoothstep(0.55, 1.0, d));
            fragColor = vec4(vColor.rgb * a, a);
        }
    """.trimIndent()

    /** Full-screen pass: trail feedback, vignette, flash. */
    val QUAD_VERT = """
        #version 300 es
        layout(location = 0) in vec2 aCorner;
        out vec2 vUv;
        void main() {
            vUv = aCorner * 0.5 + 0.5;
            gl_Position = vec4(aCorner, 0.0, 1.0);
        }
    """.trimIndent()

    /** Draws the previous frame back, dimmed, to make trails. */
    val FADE_FRAG = """
        #version 300 es
        precision mediump float;
        in vec2 vUv;
        uniform sampler2D uTex;
        uniform float uFade;
        out vec4 fragColor;
        void main() {
            fragColor = texture(uTex, vUv) * uFade;
        }
    """.trimIndent()

    /** Final blit with vignette and the (rate-limited) flash. */
    val PRESENT_FRAG = """
        #version 300 es
        precision mediump float;
        in vec2 vUv;
        uniform sampler2D uTex;
        uniform float uVignette;
        uniform float uFlash;
        uniform vec3 uBackground;
        out vec4 fragColor;
        void main() {
            vec4 scene = texture(uTex, vUv);
            vec3 col = uBackground * (1.0 - scene.a) + scene.rgb;
            vec2 d = vUv - 0.5;
            float v = 1.0 - uVignette * dot(d, d) * 2.2;
            col *= clamp(v, 0.0, 1.0);
            col += vec3(uFlash);
            fragColor = vec4(clamp(col, 0.0, 1.0), 1.0);
        }
    """.trimIndent()

    fun compile(type: Int, source: String): Int {
        val id = GLES30.glCreateShader(type)
        GLES30.glShaderSource(id, source)
        GLES30.glCompileShader(id)
        val status = IntArray(1)
        GLES30.glGetShaderiv(id, GLES30.GL_COMPILE_STATUS, status, 0)
        if (status[0] == 0) {
            Log.e(TAG, "Shader compile failed: " + GLES30.glGetShaderInfoLog(id))
            GLES30.glDeleteShader(id)
            return 0
        }
        return id
    }

    fun link(vertSrc: String, fragSrc: String): Int {
        val v = compile(GLES30.GL_VERTEX_SHADER, vertSrc)
        val f = compile(GLES30.GL_FRAGMENT_SHADER, fragSrc)
        if (v == 0 || f == 0) return 0
        val p = GLES30.glCreateProgram()
        GLES30.glAttachShader(p, v)
        GLES30.glAttachShader(p, f)
        GLES30.glLinkProgram(p)
        val status = IntArray(1)
        GLES30.glGetProgramiv(p, GLES30.GL_LINK_STATUS, status, 0)
        GLES30.glDeleteShader(v)
        GLES30.glDeleteShader(f)
        if (status[0] == 0) {
            Log.e(TAG, "Program link failed: " + GLES30.glGetProgramInfoLog(p))
            GLES30.glDeleteProgram(p)
            return 0
        }
        return p
    }
}
