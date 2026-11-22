package app.drivecast.tv;

import android.content.Context;
import android.hardware.display.DisplayManager;
import android.opengl.GLES20;
import android.view.Display;
import androidx.media3.common.Effect;
import androidx.media3.common.VideoFrameProcessingException;
import androidx.media3.common.util.GlProgram;
import androidx.media3.common.util.GlUtil;
import androidx.media3.common.util.Size;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.effect.BaseGlShaderProgram;
import androidx.media3.effect.GlEffect;
import androidx.media3.effect.GlShaderProgram;
import java.util.ArrayList;
import java.util.List;

/** 🔷 «تحسين الصورة»: two GPU passes, each only where it gives something:
 *  1. the edges sharpened at the picture's OWN size (an adaptive unsharp mask in the manner of AMD's contrast-adaptive
 *     sharpening: strong on soft detail, gentle where the contrast is already high, so no halos). A 1080p frame is a quarter
 *     of a 4K one, so this is cheap; a QHD / 4K picture is copied as it is.
 *  2. only when the TV's output is really 4K: the picture scaled to the screen's lines with a sharp bicubic (Catmull-Rom,
 *     9 taps) instead of the plain bilinear. On a 1080p output nothing is scaled (scaling up to 2160 only to be scaled back
 *     down was pure work for nothing).
 *  Coordinates in high precision: in medium precision a texel's step (1 / 1920) rounds away and the neighbours are missed.
 *  The player turns it off by itself when the box drops frames under it. */
@UnstableApi
final class Up4K {
    /** the screen's real lines: the TV's output mode (2160 on a 4K output, else 1080 / 720) */
    static int screenLines(Context c) {
        try {
            Display d = ((DisplayManager) c.getSystemService(Context.DISPLAY_SERVICE)).getDisplay(Display.DEFAULT_DISPLAY);
            Display.Mode m = d.getMode();
            return Math.min(m.getPhysicalWidth(), m.getPhysicalHeight());
        } catch (Throwable t) { return 1080; }
    }

    static List<Effect> effects(Context c) {
        int lines = screenLines(c);
        List<Effect> l = new ArrayList<>();
        l.add(new Fx(false, 0, 0.55f));
        if (lines > 1100) l.add(new Fx(true, lines, 0f));
        return l;
    }

    static final class Fx implements GlEffect {
        final boolean up; final int lines; final float amount;
        Fx(boolean up, int lines, float amount) { this.up = up; this.lines = lines; this.amount = amount; }
        @Override public GlShaderProgram toGlShaderProgram(Context context, boolean useHdr) throws VideoFrameProcessingException {
            return new Program(useHdr, up, lines, amount);
        }
    }

    private static final String VERTEX =
        "attribute vec4 aFramePosition;\n" +
        "varying vec2 vTex;\n" +
        "void main() {\n" +
        "  gl_Position = aFramePosition;\n" +
        "  vTex = vec2(aFramePosition.x * 0.5 + 0.5, aFramePosition.y * 0.5 + 0.5);\n" +
        "}\n";
    private static final String HEAD =
        "#ifdef GL_FRAGMENT_PRECISION_HIGH\n" +
        "precision highp float;\n" +
        "#else\n" +
        "precision mediump float;\n" +
        "#endif\n" +
        "uniform sampler2D uTexSampler;\n" +
        "uniform float uPass;\n" +
        "varying vec2 vTex;\n";
    private static final String SHARPEN = HEAD +
        "uniform vec2 uTexel;\n" +
        "uniform float uAmount;\n" +
        "void main() {\n" +
        "  vec3 c = texture2D(uTexSampler, vTex).rgb;\n" +
        "  if (uPass > 0.5) { gl_FragColor = vec4(c, 1.0); return; }\n" +
        "  vec3 n = texture2D(uTexSampler, vTex + vec2(0.0, -uTexel.y)).rgb;\n" +
        "  vec3 s = texture2D(uTexSampler, vTex + vec2(0.0, uTexel.y)).rgb;\n" +
        "  vec3 e = texture2D(uTexSampler, vTex + vec2(uTexel.x, 0.0)).rgb;\n" +
        "  vec3 w = texture2D(uTexSampler, vTex + vec2(-uTexel.x, 0.0)).rgb;\n" +
        "  vec3 mn = min(c, min(min(n, s), min(e, w)));\n" +
        "  vec3 mx = max(c, max(max(n, s), max(e, w)));\n" +
        "  vec3 amp = sqrt(clamp(min(mn, 1.0 - mx) / max(mx, 0.001), 0.0, 1.0));\n" +
        "  vec3 wgt = -amp * mix(0.125, 0.2, uAmount);\n" +
        "  vec3 o = (c + (n + s + e + w) * wgt) / (1.0 + 4.0 * wgt);\n" +
        "  gl_FragColor = vec4(clamp(o, 0.0, 1.0), 1.0);\n" +
        "}\n";
    private static final String UPSCALE = HEAD +
        "uniform vec2 uSize;\n" +
        "void main() {\n" +
        "  if (uPass > 0.5) { gl_FragColor = vec4(texture2D(uTexSampler, vTex).rgb, 1.0); return; }\n" +
        "  vec2 sp = vTex * uSize;\n" +
        "  vec2 t1 = floor(sp - 0.5) + 0.5;\n" +
        "  vec2 f = sp - t1;\n" +
        "  vec2 w0 = f * (-0.5 + f * (1.0 - 0.5 * f));\n" +
        "  vec2 w1 = 1.0 + f * f * (-2.5 + 1.5 * f);\n" +
        "  vec2 w2 = f * (0.5 + f * (2.0 - 1.5 * f));\n" +
        "  vec2 w3 = f * f * (-0.5 + 0.5 * f);\n" +
        "  vec2 w12 = w1 + w2;\n" +
        "  vec2 a = (t1 - 1.0) / uSize;\n" +
        "  vec2 b = (t1 + w2 / w12) / uSize;\n" +
        "  vec2 d = (t1 + 2.0) / uSize;\n" +
        "  vec3 r = texture2D(uTexSampler, vec2(a.x, a.y)).rgb * (w0.x * w0.y)\n" +
        "         + texture2D(uTexSampler, vec2(b.x, a.y)).rgb * (w12.x * w0.y)\n" +
        "         + texture2D(uTexSampler, vec2(d.x, a.y)).rgb * (w3.x * w0.y)\n" +
        "         + texture2D(uTexSampler, vec2(a.x, b.y)).rgb * (w0.x * w12.y)\n" +
        "         + texture2D(uTexSampler, vec2(b.x, b.y)).rgb * (w12.x * w12.y)\n" +
        "         + texture2D(uTexSampler, vec2(d.x, b.y)).rgb * (w3.x * w12.y)\n" +
        "         + texture2D(uTexSampler, vec2(a.x, d.y)).rgb * (w0.x * w3.y)\n" +
        "         + texture2D(uTexSampler, vec2(b.x, d.y)).rgb * (w12.x * w3.y)\n" +
        "         + texture2D(uTexSampler, vec2(d.x, d.y)).rgb * (w3.x * w3.y);\n" +
        "  gl_FragColor = vec4(clamp(r, 0.0, 1.0), 1.0);\n" +
        "}\n";

    static final class Program extends BaseGlShaderProgram {
        private final GlProgram gl;
        private final boolean up; private final int lines; private final float amount;
        private int w = 1, h = 1; private boolean pass;

        Program(boolean useHdr, boolean up, int lines, float amount) throws VideoFrameProcessingException {
            super(useHdr, 1);
            this.up = up; this.lines = lines; this.amount = amount;
            try { gl = new GlProgram(VERTEX, up ? UPSCALE : SHARPEN); } catch (GlUtil.GlException e) { throw new VideoFrameProcessingException(e); }
            gl.setBufferAttribute("aFramePosition", GlUtil.getNormalizedCoordinateBounds(), GlUtil.HOMOGENEOUS_COORDINATE_VECTOR_SIZE);
        }
        @Override public Size configure(int inputWidth, int inputHeight) {
            w = Math.max(1, inputWidth); h = Math.max(1, inputHeight);
            if (!up) { pass = h >= 1440; return new Size(w, h); }
            pass = h >= lines * 0.9f;
            if (pass) return new Size(w, h);
            int ow = Math.max(2, Math.round(w * (float) lines / h) & ~1);
            return new Size(ow, lines);
        }
        @Override public void drawFrame(int inputTexId, long presentationTimeUs) throws VideoFrameProcessingException {
            try {
                gl.use();
                gl.setSamplerTexIdUniform("uTexSampler", inputTexId, 0);
                gl.setFloatUniform("uPass", pass ? 1f : 0f);
                if (up) gl.setFloatsUniform("uSize", new float[] { w, h });
                else { gl.setFloatsUniform("uTexel", new float[] { 1f / w, 1f / h }); gl.setFloatUniform("uAmount", amount); }
                gl.bindAttributesAndUniforms();
                GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);
            } catch (GlUtil.GlException e) { throw new VideoFrameProcessingException(e, presentationTimeUs); }
        }
        @Override public void release() throws VideoFrameProcessingException {
            super.release();
            try { gl.delete(); } catch (GlUtil.GlException e) { throw new VideoFrameProcessingException(e); }
        }
    }
}
