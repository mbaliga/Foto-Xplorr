package com.fotoxplorr.app.viewer

import androidx.activity.ComponentActivity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.core.app.PictureInPictureModeChangedInfo
import androidx.core.util.Consumer

/**
 * Whether the window is currently in picture-in-picture, as [VideoPlayer] provides it to its own
 * chrome (see that composable's use of [CompositionLocalProvider][androidx.compose.runtime.CompositionLocalProvider]
 * around its transport controls). A host that wants to hide ITS OWN chrome during PiP is not a
 * descendant of [VideoPlayer]'s composition, so it cannot read this local -- it calls
 * [rememberIsInPictureInPicture] directly instead, which is the same underlying signal with no
 * dependency on composition position.
 */
val LocalIsInPictureInPicture = compositionLocalOf { false }

/**
 * Tracks the hosting [ComponentActivity]'s picture-in-picture mode via
 * `addOnPictureInPictureModeChangedListener` -- self-sufficient rather than requiring a particular
 * composition ancestor, so both [VideoPlayer] and any future host composable can call this
 * directly. Returns a fixed `false` outside a [ComponentActivity] (a preview, or a host predating
 * this feature) rather than throwing.
 */
@Composable
fun rememberIsInPictureInPicture(): State<Boolean> {
    val context = LocalContext.current
    val activity = remember(context) { context as? ComponentActivity }
    val state = remember { mutableStateOf(activity?.isInPictureInPictureMode == true) }
    DisposableEffect(activity) {
        val current = activity
        if (current == null) {
            onDispose {}
        } else {
            val listener = Consumer<PictureInPictureModeChangedInfo> { info ->
                state.value = info.isInPictureInPictureMode
            }
            current.addOnPictureInPictureModeChangedListener(listener)
            onDispose { current.removeOnPictureInPictureModeChangedListener(listener) }
        }
    }
    return state
}
