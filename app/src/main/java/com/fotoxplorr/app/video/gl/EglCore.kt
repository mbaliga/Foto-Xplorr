package com.fotoxplorr.app.video.gl

import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLExt
import android.opengl.EGLSurface
import android.util.Log
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

    // EGL_RECORDABLE_ANDROID (P0-11) hints the platform that this surface feeds a video encoder,
    // which some GPU drivers use to pick a config their encode path actually supports -- without
    // it, a handful of devices silently hand back a config the encoder then can't consume. Not
    // every device reports a config that supports it, so this tries WITH the attribute first, then
    // retries without it (logging when that happens) rather than failing a device that would
    // otherwise transcode just fine.
    private val config: EGLConfig = chooseConfig(withRecordable = true) ?: run {
        Log.w(TAG, "No EGL config supports EGL_RECORDABLE_ANDROID; retrying without it")
        checkNotNull(chooseConfig(withRecordable = false)) { "Unable to find a matching EGL config" }
    }

    private fun chooseConfig(withRecordable: Boolean): EGLConfig? {
        val attributes = buildList {
            add(EGL14.EGL_RED_SIZE); add(8)
            add(EGL14.EGL_GREEN_SIZE); add(8)
            add(EGL14.EGL_BLUE_SIZE); add(8)
            add(EGL14.EGL_ALPHA_SIZE); add(8)
            add(EGL14.EGL_RENDERABLE_TYPE); add(EGL14.EGL_OPENGL_ES2_BIT)
            if (withRecordable) { add(EGLExt.EGL_RECORDABLE_ANDROID); add(1) }
            add(EGL14.EGL_NONE)
        }.toIntArray()
        val configs = arrayOfNulls<EGLConfig>(1)
        val numConfigs = IntArray(1)
        val found = EGL14.eglChooseConfig(display, attributes, 0, configs, 0, configs.size, numConfigs, 0)
        return if (found && numConfigs[0] > 0) configs[0] else null
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

    private companion object {
        const val TAG = "EglCore"
    }
}
