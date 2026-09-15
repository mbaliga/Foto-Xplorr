package com.fotoxplorr.app.video.gl

import android.opengl.EGLSurface
import android.view.Surface

/**
 * The GL-drawable side of a [android.media.MediaCodec] encoder's input [Surface].
 *
 * An encoder created with [android.media.MediaCodec.createInputSurface] takes its input purely by
 * whatever is drawn to this surface between [makeCurrent] and [swapBuffers] — there is no separate
 * "feed this encoder a frame" call for surface-input mode. [setPresentationTime] before each
 * [swapBuffers] is what gives the encoder the frame's real timestamp; without it the encoder would
 * stamp frames by wall-clock render time instead of the source's own timing.
 */
internal class InputSurface(private val egl: EglCore, surface: Surface) {
    private val eglSurface: EGLSurface = egl.windowSurface(surface)

    fun makeCurrent() = egl.makeCurrent(eglSurface)

    fun setPresentationTime(nanos: Long) = egl.setPresentationTime(eglSurface, nanos)

    /** Presents whatever has been drawn since [makeCurrent] to the encoder as one frame. */
    fun swapBuffers(): Boolean = egl.swapBuffers(eglSurface)

    fun release() = egl.releaseSurface(eglSurface)
}
