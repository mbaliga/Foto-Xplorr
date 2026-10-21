package com.fotoxplorr.app.lift

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Redo
import androidx.compose.material.icons.automirrored.outlined.Undo
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp

/**
 * Everything the lift editor shows that is not the photo: progress, errors, the tool panels and
 * the result card. Plain composables over [LiftEditorState]; the decisions (what a stroke means,
 * what a failure is) live in the pure files, not here.
 */

/**
 * Consume every touch that lands on this surface, including drags. A panel that merely had no
 * click handler would let a drag started on it fall through to the viewer's page-swipe detector
 * underneath, so a slip of the thumb on the toolbar would flip to the next photo mid-edit.
 */
private fun Modifier.swallowTouches(): Modifier = pointerInput(Unit) {
    awaitEachGesture {
        awaitFirstDown()
        do {
            val event = awaitPointerEvent()
            event.changes.forEach { it.consume() }
        } while (event.changes.any { it.pressed })
    }
}

@Composable
internal fun BoxScope.LiftLoadingChrome(onCancel: () -> Unit) {
    Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.55f)).swallowTouches())
    Column(
        modifier = Modifier.align(Alignment.Center),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        CircularProgressIndicator(color = Color.White)
        Text("Opening photo…", color = Color.White, style = MaterialTheme.typography.bodyMedium)
    }
    Row(
        modifier = Modifier.align(Alignment.TopCenter).statusBarsPadding().fillMaxWidth().padding(12.dp),
        horizontalArrangement = Arrangement.End,
    ) {
        TextButton(onClick = onCancel) { Text("Cancel", color = Color.White) }
    }
}

@Composable
internal fun BoxScope.LiftFailureChrome(message: String, onClose: () -> Unit) {
    Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.7f)).swallowTouches())
    Column(
        modifier = Modifier.align(Alignment.Center).padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(message, color = Color.White, style = MaterialTheme.typography.bodyLarge)
        FilledTonalButton(onClick = onClose) { Text("Close") }
    }
}

/** What the banner says, from the editor's state: the next useful thing to do, not a manual. */
private fun hintFor(editor: LiftEditorState): String = when {
    editor.solving -> "Working…"
    editor.solveFailure == LiftFailure.NoSubject -> "Couldn't find a clear subject there. Scribble over more of it"
    editor.previewCutout -> "Previewing the cut-out"
    editor.session.isEmpty -> "Tap or scribble over the subject"
    editor.tool == LiftTool.Refine -> "Paint to add or erase parts of the selection"
    editor.markSubject -> "Scribble over anything missing, or switch to Background"
    else -> "Scribble over anything that should NOT be included"
}

@Composable
internal fun BoxScope.LiftEditorChrome(editor: LiftEditorState, onCancel: () -> Unit, onDone: () -> Unit) {
    val canFinish = editor.visuals?.hasSelection == true && !editor.solving

    // Top: leave, what to do next, finish.
    Row(
        modifier = Modifier
            .align(Alignment.TopCenter)
            .fillMaxWidth()
            .statusBarsPadding()
            .padding(horizontal = 12.dp, vertical = 8.dp)
            .swallowTouches(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TextButton(onClick = onCancel) { Text("Cancel", color = Color.White) }
        Row(
            modifier = Modifier
                .weight(1f, fill = false)
                .background(Color.Black.copy(alpha = 0.6f), RoundedCornerShape(50))
                .padding(horizontal = 14.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (editor.solving) {
                CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp, color = Color.White)
            }
            Text(hintFor(editor), color = Color.White, style = MaterialTheme.typography.bodyMedium)
        }
        Button(onClick = onDone, enabled = canFinish) { Text("Done") }
    }

    // Bottom: tools, their options, history.
    Column(
        modifier = Modifier
            .align(Alignment.BottomCenter)
            .fillMaxWidth()
            .background(Color.Black.copy(alpha = 0.72f))
            .swallowTouches()
            .navigationBarsPadding()
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            FilterChip(
                selected = editor.tool == LiftTool.Select,
                onClick = { editor.tool = LiftTool.Select },
                label = { Text("Select") },
            )
            FilterChip(
                selected = editor.tool == LiftTool.Refine,
                onClick = { editor.tool = LiftTool.Refine },
                label = { Text("Refine") },
            )
            FilterChip(
                selected = editor.previewCutout,
                onClick = { editor.previewCutout = !editor.previewCutout },
                label = { Text("Preview") },
                enabled = editor.visuals?.hasSelection == true,
            )
        }

        if (editor.tool == LiftTool.Select) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = editor.markSubject,
                    onClick = { editor.markSubject = true },
                    label = { Text("Subject") },
                )
                FilterChip(
                    selected = !editor.markSubject,
                    onClick = { editor.markSubject = false },
                    label = { Text("Background") },
                )
            }
        } else {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = editor.paintAdd,
                    onClick = { editor.paintAdd = true },
                    label = { Text("Add") },
                )
                FilterChip(
                    selected = !editor.paintAdd,
                    onClick = { editor.paintAdd = false },
                    label = { Text("Erase") },
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Size", color = Color.White, style = MaterialTheme.typography.labelLarge)
                Slider(
                    value = editor.brushRadius,
                    onValueChange = { editor.brushRadius = it },
                    valueRange = MIN_REFINE_RADIUS..MAX_REFINE_RADIUS,
                    modifier = Modifier.weight(1f),
                )
            }
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { editor.session = editor.session.undo() }, enabled = editor.session.canUndo) {
                Icon(Icons.AutoMirrored.Outlined.Undo, contentDescription = "Undo", tint = Color.White.copy(alpha = if (editor.session.canUndo) 1f else 0.38f))
            }
            IconButton(onClick = { editor.session = editor.session.redo() }, enabled = editor.session.canRedo) {
                Icon(Icons.AutoMirrored.Outlined.Redo, contentDescription = "Redo", tint = Color.White.copy(alpha = if (editor.session.canRedo) 1f else 0.38f))
            }
            TextButton(
                onClick = {
                    editor.session = editor.session.reset()
                    editor.previewCutout = false
                },
                enabled = !editor.session.isEmpty,
            ) { Text("Start over", color = Color.White.copy(alpha = if (editor.session.isEmpty) 0.38f else 1f)) }
        }
    }
}

@Composable
internal fun BoxScope.LiftResultScreen(
    preview: ImageBitmap?,
    statusMessage: String?,
    busy: Boolean,
    onCopy: () -> Unit,
    onShare: () -> Unit,
    onSave: () -> Unit,
    onEditSelection: () -> Unit,
    onClose: () -> Unit,
) {
    Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.94f)).swallowTouches())
    Column(
        modifier = Modifier.align(Alignment.Center).statusBarsPadding().navigationBarsPadding().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        // The cut-out on a checkerboard, not on a flat card: a grid is the one backdrop in which
        // transparency reads as transparency, so a sticker with a stray background fringe, or a
        // hole where there should be subject, is visible before it is saved.
        if (preview == null) {
            CircularProgressIndicator(color = Color.White)
        } else {
            Box(
                modifier = Modifier
                    .sizeIn(maxWidth = 320.dp, maxHeight = 360.dp)
                    .aspectRatio(preview.width.toFloat() / preview.height.toFloat().coerceAtLeast(1f))
                    .clip(RoundedCornerShape(12.dp))
                    .checkerboard(),
                contentAlignment = Alignment.Center,
            ) {
                Image(
                    bitmap = preview,
                    contentDescription = "Lifted sticker",
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Fit,
                )
            }
        }
        Text(
            text = statusMessage ?: if (busy) "Preparing…" else "Your sticker is ready",
            color = Color.White,
            style = MaterialTheme.typography.labelLarge,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilledTonalButton(onClick = onCopy, enabled = !busy && preview != null) { Text("Copy") }
            FilledTonalButton(onClick = onShare, enabled = !busy && preview != null) { Text("Share") }
            Button(onClick = onSave, enabled = !busy && preview != null) { Text("Save sticker") }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = onEditSelection) { Text("Edit selection", color = Color.White) }
            TextButton(onClick = onClose) { Text("Close", color = Color.White.copy(alpha = 0.7f)) }
        }
    }
}

internal const val MIN_REFINE_RADIUS = 0.01f
internal const val MAX_REFINE_RADIUS = 0.12f
