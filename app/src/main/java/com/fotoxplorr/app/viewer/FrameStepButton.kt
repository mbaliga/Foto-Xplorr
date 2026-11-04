package com.fotoxplorr.app.viewer

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Dp
import kotlinx.coroutines.withTimeoutOrNull

/**
 * A 48 dp round button that steps one frame on a tap and keeps stepping, faster the longer the finger
 * stays ([FrameStep.repeatDelayMs]). The press-and-hold is a convenience, not the only way: a tap is
 * one step, and the semantics `onClick` gives TalkBack and switch access a plain activation, so
 * nothing needs a held gesture. A disabled button draws dim and does nothing.
 */
@Composable
internal fun FrameStepButton(
    icon: ImageVector,
    description: String,
    enabled: Boolean,
    onStep: () -> Unit,
    tag: String,
    modifier: Modifier = Modifier,
    diameter: Dp = 48.dp,
) {
    val step by rememberUpdatedState(onStep)
    val live by rememberUpdatedState(enabled)
    Box(
        modifier = modifier
            .size(diameter)
            .testTag(tag)
            .background(Color.Black.copy(alpha = 0.55f), CircleShape)
            .semantics(mergeDescendants = true) {
                contentDescription = description
                role = Role.Button
                onClick(label = description) { if (live) step(); live }
            }
            .pointerInput(Unit) {
                awaitEachGesture {
                    awaitFirstDown()
                    if (!live) { waitForUpOrCancellation(); return@awaitEachGesture }
                    step()
                    var held = 0L
                    while (true) {
                        val wait = FrameStep.repeatDelayMs(held)
                        val up = withTimeoutOrNull(wait) { waitForUpOrCancellation() }
                        if (up != null || !live) break
                        // Timed out with the finger still down: this is a repeat.
                        held += wait
                        step()
                    }
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = Color.White.copy(alpha = if (enabled) 1f else 0.38f),
            modifier = Modifier.size(diameter * 0.5f),
        )
    }
}

internal const val FRAME_BACK_TAG = "viewer-frame-back"
internal const val FRAME_FORWARD_TAG = "viewer-frame-forward"
