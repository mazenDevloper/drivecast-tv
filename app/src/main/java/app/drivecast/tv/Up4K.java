package app.drivecast.tv;

import android.content.Context;
import android.opengl.GLES20;
import androidx.media3.common.Effect;
import androidx.media3.common.VideoFrameProcessingException;
import androidx.media3.common.util.GlProgram;
import androidx.media3.common.util.GlUtil;
import androidx.media3.common.util.Size;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.effect.BaseGlShaderProgram;
import androidx.media3.effect.GlEffect;
import androidx.media3.effect.GlShaderProgram;
import androidx.media3.effect.Presentation;
import java.util.ArrayList;
import java.util.List;

/** 🔷 «تحسين الصورة إلى 4K»: the picture is scaled on the GPU to 2160 lines and its edges sharpened (an adaptive unsharp mask,
 *  in the manner of AMD's contrast-adaptive sharpening: strong on soft detail, gentle where the contrast is already high, so
 *  no halos). It doesn't invent detail — it makes the scaled picture crisper than the TV's own scaler. Off by default. */
@UnstableApi
final class Up4K {
    static List<Effect> effects() {
        List<Effect> l = new ArrayList<>();
        l.add(Presentation.createForHeight(2160));
        l.add(new Sharpen(0.55f));
        return l;
    }

    static final class Sharpen implements GlEffect {
        final float amount;
        Sharpen(float amount) { this.amount = amount; }
        @Override public GlShaderProgram toGlShaderProgram(Context context, boolean useHdr) throws VideoFrameProcessingException {
            return new Program(useHdr, amount);
        }
    }

    static final class Program extends BaseGlShaderProgram {
        private static final String VERTEX =
            "attribute vec4 aFramePosition;\n" +
            "varying vec2 vTex;\n" +
            "void main() {\n" +
            "  gl_Position = aFramePosition;\n" +
            "  vTex = vec2(aFramePosition.x * 0.5 + 0.5, aFramePosition.y * 0.5 + 0.5);\n" +
            "}\n";
        private static final String FRAGMENT =
            "precision mediump float;\n" +
            "uniform sampler2D uTexSampler;\n" +
            "uniform vec2 uTexel;\n" +
            "uniform float uAmount;\n" +
            "varying vec2 vTex;\n" +
            "void main() {\n" +
            "  vec3 c = texture2D(uTexSampler, vTex).rgb;\n" +
            "  vec3 n = texture2D(uTexSampler, vTex + vec2(0.0, -uTexel.y)).rgb;\n" +
            "  vec3 s = texture2D(uTexSampler, vTex + vec2(0.0, uTexel.y)).rgb;\n" +
            "  vec3 e = texture2D(uTexSampler, vTex + vec2(uTexel.x, 0.0)).rgb;\n" +
            "  vec3 w = texture2D(uTexSampler, vTex + vec2(-uTexel.x, 0.0)).rgb;\n" +
            "  vec3 mn = min(c, min(min(n, s), min(e, w)));\n" +
            "  vec3 mx = max(c, max(max(n, s), max(e, w)));\n" +
            "  vec3 amp = sqrt(clamp(min(mn, 1.0 - mx) / max(mx, 0.001), 0.0, 1.0));\n" +   // less where the contrast is high
            "  vec3 wgt = -amp * mix(0.125, 0.2, uAmount);\n" +
            "  vec3 o = (c + (n + s + e + w) * wgt) / (1.0 + 4.0 * wgt);\n" +
            "  gl_FragColor = vec4(clamp(o, 0.0, 1.0), 1.0);\n" +
            "}\n";
        private final GlProgram gl;
        private final float amount;
        private int w = 1, h = 1;

        Program(boolean useHdr, float amount) throws VideoFrameProcessingException {
            super(useHdr, 1);
            this.amount = amount;
            try { gl = new GlProgram(VERTEX, FRAGMENT); } catch (GlUtil.GlException e) { throw new VideoFrameProcessingException(e); }
            gl.setBufferAttribute("aFramePosition", GlUtil.getNormalizedCoordinateBounds(), GlUtil.HOMOGENEOUS_COORDINATE_VECTOR_SIZE);
        }
        @Override public Size configure(int inputWidth, int inputHeight) { w = Math.max(1, inputWidth); h = Math.max(1, inputHeight); return new Size(inputWidth, inputHeight); }
        @Override public void drawFrame(int inputTexId, long presentationTimeUs) throws VideoFrameProcessingException {
            try {
                gl.use();
                gl.setSamplerTexIdUniform("uTexSampler", inputTexId, 0);
                gl.setFloatsUniform("uTexel", new float[] { 1f / w, 1f / h });
                gl.setFloatUniform("uAmount", amount);
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
