package com.fotoxplorr.app.video.gl

import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLExt
import android.opengl.EGLSurface
import android.view.Surface

/**
 * The one EGL display, config and context every surface in a transcode pass shares.
 *
 * A single context has to back BOTH the decoder's output texture and the encoder's input surface:
 * that texture is only usable from the context that owns it, and the render step needs it bound
 * while drawing onto the OTHER surface — this is the standard "shared EGL context" shape every
 * `MediaCodec`-via-`Surface` transcoder uses (the pattern is unchanged since `EGL14` itself
 * arrived in API 17; see Android's own `bigflake`/`grafika` sample repositories for the reference
 * this class follows).
 */
internal class EglCore {
    val display: EGLDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY).also { d ->
        check(d != EGL14.EGL_NO_DISPLAY) { "Unable to get an EGL display" }
        val version = IntArray(2)
        check(EGL14.eglInitialize(d, version, 0, version, 1)) { "Unable to initialize EGL" }
    }

    private val config: EGLConfig = run {
        val attributes = intArrayOf(
            EGL14.EGL_RED_SIZE, 8,
            EGL14.EGL_GREEN_SIZE, 8,
            EGL14.EGL_BLUE_SIZE, 8,
            EGL14.EGL_ALPHA_SIZE, 8,
            EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
            EGL14.EGL_NONE,
        )
        val configs = arrayOfNulls<EGLConfig>(1)
        val numConfigs = IntArray(1)
        val found = EGL14.eglChooseConfig(display, attributes, 0, configs, 0, configs.size, numConfigs, 0)
        check(found && numConfigs[0] > 0) { "Unable to find a matching EGL config" }
        checkNotNull(configs[0])
    }

    val context: EGLContext = EGL14.eglCreateContext(
        display,
        config,
        EGL14.EGL_NO_CONTEXT,
        intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE),
        0,
    ).also { c -> check(c != EGL14.EGL_NO_CONTEXT) { "Unable to create an EGL context" } }

    /** A window surface backed by [surface] — either the encoder's input surface. */
    fun windowSurface(surface: Surface): EGLSurface {
        val eglSurface = EGL14.eglCreateWindowSurface(display, config, surface, intArrayOf(EGL14.EGL_NONE), 0)
        check(eglSurface != EGL14.EGL_NO_SURFACE) { "Unable to create an EGL window surface" }
        return eglSurface
    }

    fun makeCurrent(surface: EGLSurface) {
        check(EGL14.eglMakeCurrent(display, surface, surface, context)) { "eglMakeCurrent failed" }
    }

    fun swapBuffers(surface: EGLSurface): Boolean = EGL14.eglSwapBuffers(display, surface)

    /** Stamps the frame about to be swapped with the SOURCE frame's own presentation time, so the
     *  encoder timestamps its output to match rather than to wall-clock render time. */
    fun setPresentationTime(surface: EGLSurface, nanos: Long) {
        EGLExt.eglPresentationTimeANDROID(display, surface, nanos)
    }

    fun releaseSurface(surface: EGLSurface) {
        EGL14.eglDestroySurface(display, surface)
    }

    fun release() {
        if (display != EGL14.EGL_NO_DISPLAY) {
            EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
            EGL14.eglDestroyContext(display, context)
            EGL14.eglReleaseThread()
            EGL14.eglTerminate(display)
        }
    }
}
