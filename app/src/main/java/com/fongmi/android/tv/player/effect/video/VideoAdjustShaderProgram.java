package com.fongmi.android.tv.player.effect.video;

import android.opengl.GLES20;

import androidx.annotation.NonNull;
import androidx.media3.common.VideoFrameProcessingException;
import androidx.media3.common.util.GlProgram;
import androidx.media3.common.util.GlUtil;
import androidx.media3.common.util.Size;
import androidx.media3.effect.BaseGlShaderProgram;

abstract class VideoAdjustShaderProgram extends BaseGlShaderProgram {

    private static final String VERTEX_SHADER = """
            attribute vec4 aFramePosition;
            varying vec2 vTexSamplingCoord;
            void main() {
              gl_Position = aFramePosition;
              vTexSamplingCoord = aFramePosition.xy * 0.5 + 0.5;
            }
            """;

    protected final GlProgram glProgram;

    VideoAdjustShaderProgram(boolean useHdr, String fragmentShader) throws VideoFrameProcessingException {
        super(false, 1);
        if (useHdr) throw new VideoFrameProcessingException("Video adjustment does not support HDR");
        GlProgram program = null;
        try {
            program = new GlProgram(VERTEX_SHADER, fragmentShader);
            program.setBufferAttribute("aFramePosition", GlUtil.getNormalizedCoordinateBounds(), GlUtil.HOMOGENEOUS_COORDINATE_VECTOR_SIZE);
            glProgram = program;
        } catch (GlUtil.GlException e) {
            // Media3 never calls release() on a shader program whose constructor threw, so a failure
            // after the program was linked (setBufferAttribute, say) leaked it and its VBO.
            if (program != null) deleteQuietly(program);
            throw new VideoFrameProcessingException(e);
        }
    }

    private static void deleteQuietly(GlProgram program) {
        try {
            program.delete();
        } catch (GlUtil.GlException ignored) {
        }
    }

    @NonNull
    @Override
    public Size configure(int inputWidth, int inputHeight) {
        return new Size(inputWidth, inputHeight);
    }

    @Override
    public void drawFrame(int inputTexId, long presentationTimeUs) throws VideoFrameProcessingException {
        try {
            glProgram.use();
            glProgram.setSamplerTexIdUniform("uTexSampler", inputTexId, 0);
            bindUniforms();
            glProgram.bindAttributesAndUniforms();
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);
        } catch (GlUtil.GlException e) {
            throw new VideoFrameProcessingException(e, presentationTimeUs);
        }
    }

    protected abstract void bindUniforms();

    @Override
    public void release() throws VideoFrameProcessingException {
        super.release();
        try {
            glProgram.delete();
        } catch (GlUtil.GlException e) {
            throw new VideoFrameProcessingException(e);
        }
    }
}
