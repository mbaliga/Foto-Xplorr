package com.fotoxplorr.app.video.gl

import android.graphics.SurfaceTexture
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.os.Handler
import android.os.Looper
import android.view.Surface

/**
 * The GL-drawable side of a [android.media.MediaCodec] decoder's output [Surface].
 *
 * A decoder configured with a [Surface] backed by a [SurfaceTexture] renders each decoded frame
 * onto that texture the moment [android.media.MediaCodec.releaseOutputBuffer] is called with
 * `render = true` — but that render happens asynchronously on the GPU, which is exactly why
 * [awaitNewImage] exists: drawing this texture (via [TextureRenderer]) before the frame it names
 * has actually landed would silently draw the PREVIOUS frame a second time, with nothing to throw
 * to catch it.
 *
 * Must be constructed with an EGL context already current on the calling thread — creating the
 * backing texture is a GL call like any other and needs one, exactly as [TextureRenderer] does.
 */
internal class DecoderOutputSurface {
    val textureId: Int = createOesTexture()
    private val surfaceTexture = SurfaceTexture(textureId).apply {
        // The main looper, not whichever thread constructs this: SurfaceTexture's frame-available
        // callback needs a Looper to post to, and a background transcode thread run from a
        // coroutine dispatcher does not reliably have one of its own. The callback only signals a
        // monitor (see onFrameAvailable) that the actual transcode thread waits on in
        // awaitNewImage, so which thread the callback itself runs on does not otherwise matter.
        setOnFrameAvailableListener({ onFrameAvailable() }, Handler(Looper.getMainLooper()))
    }

    /** Hand to [android.media.MediaCodec.configure] as the decoder's output surface. */
    val surface: Surface = Surface(surfaceTexture)

    private val frameSyncObject = Object()
    private var frameAvailable = false

    private fun onFrameAvailable() {
        synchronized(frameSyncObject) {
            frameAvailable = true
            frameSyncObject.notifyAll()
        }
    }

    /** Blocks until the frame just released to [surface] has actually arrived, then binds it as
     *  the current external texture ([textureId]) so [TextureRenderer.draw] can read it. */
    fun awaitNewImage() {
        synchronized(frameSyncObject) {
            while (!frameAvailable) {
                frameSyncObject.wait(FRAME_WAIT_TIMEOUT_MS)
                check(frameAvailable) { "Timed out waiting ${FRAME_WAIT_TIMEOUT_MS}ms for a decoded frame" }
            }
            frameAvailable = false
        }
        surfaceTexture.updateTexImage()
    }

    /** The transform [TextureRenderer] must apply to texture coordinates for this frame — GPU
     *  decoders can hand back a rotated/cropped/flipped texture, and this is how a caller finds
     *  out which, rather than assuming an identity mapping. */
    fun transformMatrix(destination: FloatArray) = surfaceTexture.getTransformMatrix(destination)

    fun release() {
        surface.release()
        surfaceTexture.release()
        GLES20.glDeleteTextures(1, intArrayOf(textureId), 0)
    }

    private companion object {
        const val FRAME_WAIT_TIMEOUT_MS = 10_000L

        fun createOesTexture(): Int {
            val textures = IntArray(1)
            GLES20.glGenTextures(1, textures, 0)
            val textureId = textures[0]
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId)
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
            return textureId
        }
    }
}
