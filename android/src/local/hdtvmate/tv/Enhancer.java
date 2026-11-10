package local.hdtvmate.tv;

import android.graphics.SurfaceTexture;
import android.opengl.EGL14;
import android.opengl.EGLConfig;
import android.opengl.EGLContext;
import android.opengl.EGLDisplay;
import android.opengl.EGLExt;
import android.opengl.EGLSurface;
import android.opengl.GLES11Ext;
import android.opengl.GLES20;
import android.util.Log;
import android.view.Surface;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;

/**
 * Optional picture clean-up for HD MPEG-2 broadcasts: the decoder renders into a SurfaceTexture and one
 * GPU pass draws it to the screen at source resolution (the system compositor scales it to the display).
 * Everything is spatial and works on the frame being shown, so it adds no frame of delay.
 *
 *   clean  - deblocking on the 8x8 DCT grid plus an edge-preserving smoother for mosquito noise / ringing
 *   sharp  - contrast-adaptive sharpening, gated so flat (noisy) areas are left alone, with no overshoot
 *   weave  - if the decoder hands 1080i over as woven frames, combed pixels are rebuilt from one field
 *
 * The pure maths (texture mapping) is static so it can be tested off the device.
 */
final class Enhancer {
    private static final String TAG = "HDTVMate";

    static final int DIRECT = 0, CLEAN = 1, SHARP = 2, CLEAN_SHARP = 3;
    static final String[] NAMES = {"Direct", "Clean", "Sharp", "Clean + Sharp"};

    static final String VERTEX =
            "attribute vec2 aPos;\n"
            + "uniform vec4 uMap;\n"                    // texcoord = uMap.xy + uMap.zw * uv
            + "varying vec2 vT;\n"
            + "void main() {\n"
            + "    vT = uMap.xy + uMap.zw * (aPos * 0.5 + 0.5);\n"
            + "    gl_Position = vec4(aPos, 0.0, 1.0);\n"
            + "}\n";

    /** Prefix with "#define LITE\n" for the 5-tap version used when the GPU cannot keep up. */
    static final String FRAGMENT =
            "precision highp float;\n"
            + "uniform samplerExternalOES uTex;\n"
            + "uniform vec2 uBuf;\n"                    // decoder buffer size in texels
            + "uniform vec4 uClamp;\n"                  // texel centres of the picture's edges (min xy, max xy)
            + "uniform vec4 uP;\n"                      // x clean threshold, y sharpen, z deinterlace, w deblock threshold (0 = off)
            + "uniform vec2 uGrid;\n"                   // deblock across vertical / horizontal block edges
            + "varying vec2 vT;\n"
            + "const vec3 LUMA = vec3(0.299, 0.587, 0.114);\n"
            + "vec3 tap(float dx, float dy) { return texture2D(uTex, clamp(vT + vec2(dx, dy) / uBuf, uClamp.xy, uClamp.zw)).rgb; }\n"
            + "float lum(vec3 v) { return dot(v, LUMA); }\n"
            + "float alike(vec3 a, vec3 b, float t) { return 1.0 - smoothstep(0.5 * t, t, abs(lum(a - b))); }\n"
            // p = position inside the 8-sample block; m* / p* = neighbours towards the previous / next block.
            // A small step across a block edge with flat pixels beside it is a coding artefact: spread it
            // over the two pixels on each side (1/8, 3/8, 5/8, 7/8 ramp). A large step is a real edge.
            + "vec3 deblock(float p, vec3 c, vec3 m1, vec3 p1, vec3 m2, vec3 p2, float tb) {\n"
            + "    vec3 delta; float act; float wt;\n"
            + "    if (p > 6.5)      { delta = p1 - c;  act = abs(lum(c - m1)); wt = 0.375; }\n"
            + "    else if (p < 0.5) { delta = m1 - c;  act = abs(lum(c - p1)); wt = 0.375; }\n"
            + "    else if (p > 5.5) { delta = p2 - p1; act = max(abs(lum(c - m1)), abs(lum(c - p1))); wt = 0.125; }\n"
            + "    else if (p < 1.5) { delta = m2 - m1; act = max(abs(lum(c - m1)), abs(lum(c - p1))); wt = 0.125; }\n"
            + "    else return c;\n"
            + "    float w = (1.0 - smoothstep(0.5 * tb, tb, abs(lum(delta)))) * (1.0 - smoothstep(0.25 * tb, 0.5 * tb, act));\n"
            + "    return c + wt * w * delta;\n"
            + "}\n"
            + "void main() {\n"
            + "    vec3 c = tap(0.0, 0.0), l = tap(-1.0, 0.0), r = tap(1.0, 0.0), u = tap(0.0, -1.0), d = tap(0.0, 1.0);\n"
            + "    vec2 px = floor(vT * uBuf);\n"
            + "#ifdef LITE\n"
            + "    vec3 l2 = l, r2 = r, u2 = u, d2 = d;\n"
            + "#else\n"
            + "    vec3 l2 = tap(-2.0, 0.0), r2 = tap(2.0, 0.0), u2 = tap(0.0, -2.0), d2 = tap(0.0, 2.0);\n"
            + "    if (uP.z > 0.5) {\n"                 // woven fields: this line differs from both neighbours the same way,
            + "        float yc = lum(c), a = yc - lum(u), b = yc - lum(d);\n"   // while the lines two away (same field) agree
            + "        float same = abs(yc - lum(u2)) + abs(yc - lum(d2));\n"
            + "        float comb = smoothstep(0.0004, 0.0025, a * b) * step(same, abs(a) + abs(b));\n"
            + "        if (mod(px.y, 2.0) > 0.5) {\n"   // odd lines: rebuild from the field above and below
            + "            c = mix(c, 0.5 * (u + d), comb);\n"
            + "            l = mix(l, c, comb); r = mix(r, c, comb); l2 = mix(l2, c, comb); r2 = mix(r2, c, comb);\n"
            + "        } else {\n"                      // even lines: keep, but stop the other field leaking into the filters
            + "            u = mix(u, u2, comb); d = mix(d, d2, comb);\n"
            + "        }\n"
            + "    }\n"
            + "#endif\n"
            + "    if (uP.w > 0.0) {\n"
            + "        if (uGrid.x > 0.5) c = deblock(mod(px.x, 8.0), c, l, r, l2, r2, uP.w);\n"
            + "        if (uGrid.y > 0.5) c = deblock(mod(px.y, 8.0), c, u, d, u2, d2, uP.w);\n"
            + "    }\n"
            + "    vec3 mn = min(c, min(min(l, r), min(u, d))), mx = max(c, max(max(l, r), max(u, d)));\n"
            + "    float range = lum(mx - mn);\n"
            + "    if (uP.x > 0.0) {\n"                 // average only neighbours that look like this pixel; strongest next to
            + "        float t = uP.x;\n"               // edges, where mosquito noise lives, gentle in plain texture
            + "        float wl = alike(c, l, t), wr = alike(c, r, t), wu = alike(c, u, t), wd = alike(c, d, t);\n"
            + "        float wl2 = 0.5 * wl * alike(c, l2, t), wr2 = 0.5 * wr * alike(c, r2, t);\n"
            + "        float wu2 = 0.5 * wu * alike(c, u2, t), wd2 = 0.5 * wd * alike(c, d2, t);\n"
            + "        vec3 avg = (c + wl * l + wr * r + wu * u + wd * d + wl2 * l2 + wr2 * r2 + wu2 * u2 + wd2 * d2)\n"
            + "                / (1.0 + wl + wr + wu + wd + wl2 + wr2 + wu2 + wd2);\n"
            + "        c = mix(c, avg, mix(0.4, 1.0, smoothstep(0.05, 0.18, range)));\n"
            + "    }\n"
            + "    if (uP.y > 0.0) {\n"                 // contrast-adaptive sharpening, clamped to the neighbourhood (no halos)
            + "        vec3 amp = sqrt(clamp(min(mn, 1.0 - mx) / max(mx, vec3(0.004)), 0.0, 1.0));\n"
            + "        vec3 w = -amp * (0.2 * uP.y * smoothstep(0.02, 0.07, range));\n"
            + "        c = clamp((c + w * (l + r + u + d)) / (1.0 + 4.0 * w), mn, mx);\n"
            + "    }\n"
            + "    gl_FragColor = vec4(c, 1.0);\n"
            + "}\n";

    /** Shader settings for a picture mode: {clean threshold, sharpen, deinterlace, deblock threshold, grid x, grid y}. */
    static float[] params(int mode, boolean interlaced) {
        boolean clean = mode == CLEAN || mode == CLEAN_SHARP, sharp = mode == SHARP || mode == CLEAN_SHARP;
        return new float[]{
                clean ? 0.026f : 0f,                    // ~6.5 / 255: typical HD mosquito-noise amplitude
                sharp ? 0.8f : 0f,
                interlaced ? 1f : 0f,
                clean ? 0.06f : 0f,                     // block-edge steps above ~15 / 255 are treated as real edges
                clean ? 1f : 0f,
                clean && !interlaced ? 1f : 0f};        // rows of an interlaced frame do not sit on an 8-line grid
    }

    /**
     * SurfaceTexture's matrix shrinks a cropped picture (1920x1088 buffer, 1080 shown) by a texel so bilinear
     * filtering cannot reach the padding. That drifts samples off the texel centres, which smears lines
     * together and breaks line-exact filtering. Recover the buffer size and crop from the matrix and build
     * an exact mapping instead: out = {bx, by, ax, ay, bufW, bufH, minX, minY, maxX, maxY}.
     */
    static void map(float[] m, int cropW, int cropH, float[] out) {
        axis(m[0], m[12], cropW, out, 0);
        axis(m[5], m[13], cropH, out, 1);
    }

    private static void axis(float a, float b, int crop, float[] o, int k) {
        float span = Math.abs(a), lo = Math.min(b, b + a);
        int buf = crop, start = 0;
        boolean exact = span > 0.99999f;                // not cropped on this axis
        if (!exact && span > 0.1f) {
            float best = 1f;
            for (float s = 1f; s >= 0f; s -= 0.5f) {    // the shrink is 1 texel for YUV buffers, 0.5 for RGB, 0 unfiltered
                float bd = (crop - 2 * s) / span, err = Math.abs(bd - Math.round(bd));
                if (err < best) { best = err; buf = Math.round(bd); start = Math.round(lo * buf - s); }
            }
            exact = best < 0.05f && start >= 0 && start + crop <= buf;
        }
        if (!exact) {                                   // unknown layout: use the matrix as it is
            buf = span > 0.1f ? Math.round(crop / span) : crop;
            o[k] = b; o[k + 2] = a; o[k + 4] = buf;
            o[k + 6] = lo + 0.5f / buf; o[k + 8] = lo + span - 0.5f / buf;
            return;
        }
        float lo2 = start / (float) buf, sp2 = crop / (float) buf;
        o[k] = a >= 0 ? lo2 : lo2 + sp2;
        o[k + 2] = a >= 0 ? sp2 : -sp2;
        o[k + 4] = buf;
        o[k + 6] = (start + 0.5f) / buf;
        o[k + 8] = (start + crop - 0.5f) / buf;
    }

    // ================= instance (GL calls only on the video thread) =================

    private final Surface output;
    private final SurfaceTexture texture;
    private final Surface input;
    private final int mode;
    private volatile int cropW, cropH;
    private volatile boolean interlaced;

    private EGLDisplay dpy = EGL14.EGL_NO_DISPLAY;
    private EGLContext ctx = EGL14.EGL_NO_CONTEXT;
    private EGLSurface win = EGL14.EGL_NO_SURFACE;
    private int tex, program, uMap, uBuf, uClamp, uP, uGrid;
    private boolean lite, attached;
    private FloatBuffer quad;
    private final float[] matrix = new float[16], mapped = new float[10];
    private final int[] size = new int[2];
    private int frames, slow;

    /** Any thread. The decoder is configured with {@link #input()}; {@link #init()} then runs on the video thread. */
    Enhancer(Surface output, int mode, int w, int h, boolean interlaced) {
        this.output = output;
        this.mode = mode;
        this.cropW = w; this.cropH = h;
        this.interlaced = interlaced;
        texture = new SurfaceTexture(false);            // detached; attached to the GL context in init()
        texture.setDefaultBufferSize(w, h);
        input = new Surface(texture);
    }

    Surface input() { return input; }
    SurfaceTexture texture() { return texture; }

    void setSource(int w, int h, boolean interlaced) { cropW = w; cropH = h; this.interlaced = interlaced; }

    void init() {
        dpy = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY);
        int[] v = new int[2];
        if (dpy == EGL14.EGL_NO_DISPLAY || !EGL14.eglInitialize(dpy, v, 0, v, 1)) throw new RuntimeException("EGL init");
        int[] attrs = {EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8, EGL14.EGL_BLUE_SIZE, 8, EGL14.EGL_ALPHA_SIZE, 8,
                EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT, EGL14.EGL_SURFACE_TYPE, EGL14.EGL_WINDOW_BIT, EGL14.EGL_NONE};
        EGLConfig[] cfg = new EGLConfig[1];
        int[] n = new int[1];
        if (!EGL14.eglChooseConfig(dpy, attrs, 0, cfg, 0, 1, n, 0) || n[0] < 1) throw new RuntimeException("EGL config");
        ctx = EGL14.eglCreateContext(dpy, cfg[0], EGL14.EGL_NO_CONTEXT, new int[]{EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE}, 0);
        if (ctx == null || ctx == EGL14.EGL_NO_CONTEXT) throw new RuntimeException("EGL context");
        win = EGL14.eglCreateWindowSurface(dpy, cfg[0], output, new int[]{EGL14.EGL_NONE}, 0);
        if (win == null || win == EGL14.EGL_NO_SURFACE) throw new RuntimeException("EGL surface 0x" + Integer.toHexString(EGL14.eglGetError()));
        if (!EGL14.eglMakeCurrent(dpy, win, win, ctx)) throw new RuntimeException("EGL current");

        int[] t = new int[1];
        GLES20.glGenTextures(1, t, 0);
        tex = t[0];
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, tex);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);
        texture.attachToGLContext(tex);
        attached = true;

        quad = ByteBuffer.allocateDirect(32).order(ByteOrder.nativeOrder()).asFloatBuffer();
        quad.put(new float[]{-1, -1, 1, -1, -1, 1, 1, 1}).position(0);
        build(false);
        GLES20.glDisable(GLES20.GL_BLEND);
        GLES20.glDisable(GLES20.GL_DEPTH_TEST);
        Log.i(TAG, "picture: " + NAMES[mode] + " on " + GLES20.glGetString(GLES20.GL_RENDERER));
    }

    private void build(boolean lite) {
        int vs = compile(GLES20.GL_VERTEX_SHADER, VERTEX);
        int fs = compile(GLES20.GL_FRAGMENT_SHADER, "#extension GL_OES_EGL_image_external : require\n"
                + (lite ? "#define LITE\n" : "") + FRAGMENT);
        int p = GLES20.glCreateProgram();
        GLES20.glAttachShader(p, vs);
        GLES20.glAttachShader(p, fs);
        GLES20.glBindAttribLocation(p, 0, "aPos");
        GLES20.glLinkProgram(p);
        int[] ok = new int[1];
        GLES20.glGetProgramiv(p, GLES20.GL_LINK_STATUS, ok, 0);
        if (ok[0] == 0) throw new RuntimeException("link: " + GLES20.glGetProgramInfoLog(p));
        GLES20.glDeleteShader(vs);
        GLES20.glDeleteShader(fs);
        if (program != 0) GLES20.glDeleteProgram(program);
        program = p;
        this.lite = lite;
        GLES20.glUseProgram(p);
        uMap = GLES20.glGetUniformLocation(p, "uMap");
        uBuf = GLES20.glGetUniformLocation(p, "uBuf");
        uClamp = GLES20.glGetUniformLocation(p, "uClamp");
        uP = GLES20.glGetUniformLocation(p, "uP");
        uGrid = GLES20.glGetUniformLocation(p, "uGrid");
        GLES20.glUniform1i(GLES20.glGetUniformLocation(p, "uTex"), 0);
        GLES20.glEnableVertexAttribArray(0);
        GLES20.glVertexAttribPointer(0, 2, GLES20.GL_FLOAT, false, 0, quad);
    }

    private static int compile(int type, String src) {
        int s = GLES20.glCreateShader(type);
        GLES20.glShaderSource(s, src);
        GLES20.glCompileShader(s);
        int[] ok = new int[1];
        GLES20.glGetShaderiv(s, GLES20.GL_COMPILE_STATUS, ok, 0);
        if (ok[0] == 0) throw new RuntimeException("shader: " + GLES20.glGetShaderInfoLog(s));
        return s;
    }

    /** Latch the newest decoded frame, filter it and queue it for display at presentNs (System.nanoTime base). */
    void draw(long presentNs, long frameIntervalNs) {
        texture.updateTexImage();
        texture.getTransformMatrix(matrix);
        EGL14.eglQuerySurface(dpy, win, EGL14.EGL_WIDTH, size, 0);
        EGL14.eglQuerySurface(dpy, win, EGL14.EGL_HEIGHT, size, 1);
        GLES20.glViewport(0, 0, size[0], size[1]);
        map(matrix, cropW, cropH, mapped);
        float[] p = params(mode, interlaced);
        GLES20.glUniform4f(uMap, mapped[0], mapped[1], mapped[2], mapped[3]);
        GLES20.glUniform2f(uBuf, mapped[4], mapped[5]);
        GLES20.glUniform4f(uClamp, mapped[6], mapped[7], mapped[8], mapped[9]);
        GLES20.glUniform4f(uP, p[0], p[1], p[2], p[3]);
        GLES20.glUniform2f(uGrid, p[4], p[5]);
        boolean measure = !lite && (++frames & 31) == 0;
        long t0 = measure ? System.nanoTime() : 0;
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);
        if (measure) {                                  // GPU too slow for the 9-tap filter: fall back to 5 taps
            GLES20.glFinish();
            long dt = System.nanoTime() - t0;
            slow = dt > frameIntervalNs * 6 / 10 ? slow + 1 : 0;
            if (slow >= 3) {
                Log.w(TAG, "picture: GPU needs " + dt / 1000000 + " ms per frame, switching to the light filter");
                build(true);
            }
        }
        EGLExt.eglPresentationTimeANDROID(dpy, win, presentNs);
        EGL14.eglSwapBuffers(dpy, win);
    }

    /** Video thread: free the GL side so the screen surface can be used again (by the decoder or a new player). */
    void releaseGl() {
        try { if (attached) { attached = false; texture.detachFromGLContext(); } } catch (Exception ignored) { }
        if (dpy != EGL14.EGL_NO_DISPLAY) {
            EGL14.eglMakeCurrent(dpy, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT);
            if (win != null && win != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(dpy, win);
            if (ctx != null && ctx != EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(dpy, ctx);
            EGL14.eglReleaseThread();
        }
        win = EGL14.EGL_NO_SURFACE; ctx = EGL14.EGL_NO_CONTEXT; dpy = EGL14.EGL_NO_DISPLAY;
    }

    /** Any thread, after releaseGl() and after the decoder has stopped. */
    void release() {
        input.release();
        texture.release();
    }
}
