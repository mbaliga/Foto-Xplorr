package com.fotoxplorr.app.video.gl

import android.opengl.GLES11Ext
import android.opengl.GLES20
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

/**
 * Draws a decoder's OES texture to whichever EGL surface is currently current — a full-viewport
 * textured quad, nothing more. This is the whole of Phase 3's pixel "processing" step, matching
 * its own scope as the transcode pipeline rather than any actual filter (see
 * [com.fotoxplorr.app.video.VideoTranscoder]'s class doc). A future filter tool replaces
 * [FRAGMENT_SHADER] with one that samples the same texture through a colour transform before it
 * ever needs to touch this class's plumbing.
 *
 * Must be constructed with an EGL context already current — compiling and linking a GL program is
 * a GL call like any other and needs one.
 */
internal class TextureRenderer {
    private val program: Int = createProgram(VERTEX_SHADER, FRAGMENT_SHADER)
    private val positionHandle: Int = GLES20.glGetAttribLocation(program, "aPosition")
    private val texCoordHandle: Int = GLES20.glGetAttribLocation(program, "aTexCoord")
    private val textureMatrixHandle: Int = GLES20.glGetUniformLocation(program, "uTexMatrix")
    private val vertexBuffer: FloatBuffer = directFloatBuffer(FULL_RECTANGLE_COORDS)
    private val texCoordBuffer: FloatBuffer = directFloatBuffer(FULL_RECTANGLE_TEX_COORDS)

    /**
     * Draws [textureId] (an `GL_TEXTURE_EXTERNAL_OES` texture, as [DecoderOutputSurface] produces)
     * across the full [width]×[height] viewport, applying [textureMatrix] — the transform
     * [DecoderOutputSurface.transformMatrix] reports for the frame just bound — to its texture
     * coordinates, so a decoder-rotated or cropped frame is sampled correctly rather than
     * assuming an identity mapping.
     */
    fun draw(textureId: Int, textureMatrix: FloatArray, width: Int, height: Int) {
        GLES20.glViewport(0, 0, width, height)
        GLES20.glUseProgram(program)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId)

        vertexBuffer.position(0)
        GLES20.glEnableVertexAttribArray(positionHandle)
        GLES20.glVertexAttribPointer(positionHandle, 2, GLES20.GL_FLOAT, false, 0, vertexBuffer)

        texCoordBuffer.position(0)
        GLES20.glEnableVertexAttribArray(texCoordHandle)
        GLES20.glVertexAttribPointer(texCoordHandle, 2, GLES20.GL_FLOAT, false, 0, texCoordBuffer)

        GLES20.glUniformMatrix4fv(textureMatrixHandle, 1, false, textureMatrix, 0)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)

        GLES20.glDisableVertexAttribArray(positionHandle)
        GLES20.glDisableVertexAttribArray(texCoordHandle)
    }

    fun release() {
        GLES20.glDeleteProgram(program)
    }

    private companion object {
        // A full-viewport quad as a triangle strip: (-1,-1) bottom-left to (1,1) top-right in
        // GL's clip space, paired one-to-one with (0,0)-(1,1) texture space below.
        val FULL_RECTANGLE_COORDS = floatArrayOf(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f)
        val FULL_RECTANGLE_TEX_COORDS = floatArrayOf(0f, 0f, 1f, 0f, 0f, 1f, 1f, 1f)

        const val VERTEX_SHADER = """
            uniform mat4 uTexMatrix;
            attribute vec4 aPosition;
            attribute vec4 aTexCoord;
            varying vec2 vTexCoord;
            void main() {
                gl_Position = aPosition;
                vTexCoord = (uTexMatrix * aTexCoord).xy;
            }
        """

        // GL_OES_EGL_image_external and samplerExternalOES: a decoder's SurfaceTexture is not a
        // plain 2D texture (it can be a platform-specific YUV layout under the hood), and this
        // extension is what lets a shader sample it as if it already were RGB.
        const val FRAGMENT_SHADER = """
            #extension GL_OES_EGL_image_external : require
            precision mediump float;
            varying vec2 vTexCoord;
            uniform samplerExternalOES sTexture;
            void main() {
                gl_FragColor = texture2D(sTexture, vTexCoord);
            }
        """

        fun directFloatBuffer(values: FloatArray): FloatBuffer =
            ByteBuffer.allocateDirect(values.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().apply {
                put(values)
                position(0)
            }

        fun createProgram(vertexSource: String, fragmentSource: String): Int {
            val vertexShader = compileShader(GLES20.GL_VERTEX_SHADER, vertexSource)
            val fragmentShader = compileShader(GLES20.GL_FRAGMENT_SHADER, fragmentSource)
            val program = GLES20.glCreateProgram()
            check(program != 0) { "Could not create a GL program" }
            GLES20.glAttachShader(program, vertexShader)
            GLES20.glAttachShader(program, fragmentShader)
            GLES20.glLinkProgram(program)
            val status = IntArray(1)
            GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, status, 0)
            if (status[0] == 0) {
                val log = GLES20.glGetProgramInfoLog(program)
                GLES20.glDeleteProgram(program)
                error("Could not link the GL program: $log")
            }
            return program
        }

        fun compileShader(type: Int, source: String): Int {
            val shader = GLES20.glCreateShader(type)
            check(shader != 0) { "Could not create a GL shader" }
            GLES20.glShaderSource(shader, source)
            GLES20.glCompileShader(shader)
            val status = IntArray(1)
            GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, status, 0)
            if (status[0] == 0) {
                val log = GLES20.glGetShaderInfoLog(shader)
                GLES20.glDeleteShader(shader)
                error("Could not compile a GL shader: $log")
            }
            return shader
        }
    }
}
