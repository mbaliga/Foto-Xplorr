package com.fotoxplorr.app.viewer.colour

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.os.Build
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalContext

/** The screen facts the OS reports; [ColourModePolicy] decides what to do with them. */
internal fun Context.screenColourFacts(): ScreenColourFacts {
    val c = resources.configuration
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        ScreenColourFacts(
            sdk = Build.VERSION.SDK_INT,
            screenHdr = c.isScreenHdr,
            screenWideGamut = c.isScreenWideColorGamut,
        )
    } else {
        ScreenColourFacts(Build.VERSION.SDK_INT, screenHdr = false, screenWideGamut = false)
    }
}

/**
 * The one Android seam of HDR / wide-colour display: sets the activity window's colour mode to
 * [mode] while the viewer shows the photo and puts it back when the photo changes or the viewer
 * leaves. [ColourModePolicy] has already refused anything the screen cannot do, so this only maps
 * the enum to `ActivityInfo.COLOR_MODE_*`. Whether an Ultra HDR highlight really renders brighter is
 * a device check; nothing here can show it on the JVM.
 */
@Composable
internal fun ApplyWindowColourMode(mode: WindowColourMode) {
    val activity = LocalContext.current.findHostActivity() ?: return
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
    DisposableEffect(activity, mode) {
        val window = activity.window
        val before = window.colorMode
        window.colorMode = when (mode) {
            WindowColourMode.DEFAULT -> ActivityInfo.COLOR_MODE_DEFAULT
            WindowColourMode.WIDE_COLOR_GAMUT -> ActivityInfo.COLOR_MODE_WIDE_COLOR_GAMUT
            WindowColourMode.HDR -> ActivityInfo.COLOR_MODE_HDR
        }
        onDispose { window.colorMode = before }
    }
}

private tailrec fun Context.findHostActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findHostActivity()
    else -> null
}
