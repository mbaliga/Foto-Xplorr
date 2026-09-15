package com.fotoxplorr.app.viewer

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.SelectAll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.fotoxplorr.app.recognition.TextBlock
import com.fotoxplorr.app.ui.HyleGrotesk

/**
 * Selecting text off a photograph.
 *
 * The whole feature rests on a decision made earlier, when OCR results were first persisted: text
 * was stored as positioned blocks with boxes normalised to the image, not as one flat string. A
 * string would have made this impossible without re-scanning the entire library, because knowing
 * *that* a photo says "PLATFORM 9" tells you nothing about where to draw the selection.
 *
 * Nothing here runs OCR. The recognition pass already did it, in the background, offline; this
 * draws what it found and lets you take it.
 */

/**
 * Where a normalised OCR box lands on screen, given a container and the image's own aspect.
 *
 * Pure, and separated from the composable on purpose: this is the part that goes subtly wrong
 * (letterboxing, portrait vs landscape, an image wider than its container) and the part worth
 * pinning in a JVM test rather than discovering on a device.
 *
 * Assumes the image is drawn with `ContentScale.Fit` — scaled to fit whole, centred, letterboxed
 * on whichever axis has slack. That is what the viewer does.
 */
internal fun fittedImageRect(container: Size, imageWidth: Int, imageHeight: Int): Rect {
    if (imageWidth <= 0 || imageHeight <= 0 || container.width <= 0f || container.height <= 0f) {
        return Rect(Offset.Zero, container)
    }
    val scale = minOf(container.width / imageWidth, container.height / imageHeight)
    val width = imageWidth * scale
    val height = imageHeight * scale
    val left = (container.width - width) / 2f
    val top = (container.height - height) / 2f
    return Rect(left, top, left + width, top + height)
}

/** Map one normalised text block onto the drawn image. */
internal fun TextBlock.toScreenRect(imageRect: Rect): Rect = Rect(
    left = imageRect.left + left * imageRect.width,
    top = imageRect.top + top * imageRect.height,
    right = imageRect.left + right * imageRect.width,
    bottom = imageRect.top + bottom * imageRect.height,
)

/** Which block, if any, sits under a tap. Later blocks win, so small boxes over large ones work. */
internal fun blockAt(blocks: List<TextBlock>, imageRect: Rect, point: Offset): Int? {
    var found: Int? = null
    blocks.forEachIndexed { index, block ->
        if (block.toScreenRect(imageRect).contains(point)) found = index
    }
    return found
}

/**
 * The tappable text layer over a photo.
 *
 * Placed INSIDE the viewer's own zoom/pan `graphicsLayer`, so the boxes track the image under
 * pinch and drag without this having to know anything about the current transform — the one
 * arrangement that cannot drift out of alignment as the gesture code changes.
 *
 * @param blocks positioned text from the recognition pass. Empty renders nothing at all, so a
 *   photo with no text carries no invisible tap targets that would eat the viewer's own gestures.
 */
@Composable
fun LiveTextOverlay(
    blocks: List<TextBlock>,
    imageWidth: Int,
    imageHeight: Int,
    modifier: Modifier = Modifier,
) {
    if (blocks.isEmpty()) return

    var selected by remember(blocks) { mutableStateOf(emptySet<Int>()) }
    var containerSize by remember { mutableStateOf(Size.Zero) }
    val clipboard = LocalClipboardManager.current

    Box(
        modifier = modifier
            .fillMaxSize()
            .pointerInput(blocks, imageWidth, imageHeight) {
                containerSize = Size(size.width.toFloat(), size.height.toFloat())
                detectTapGestures(
                    onTap = { point ->
                        val rect = fittedImageRect(containerSize, imageWidth, imageHeight)
                        val index = blockAt(blocks, rect, point)
                        selected = when {
                            // A tap on bare photo clears, which is what every text selection does
                            // and what makes the layer feel like it is not in the way.
                            index == null -> emptySet()
                            index in selected -> selected - index
                            else -> selected + index
                        }
                    },
                    onLongPress = { point ->
                        // Long-press takes the whole photo's text: the common case is "give me
                        // all of this", and hunting every block for it would be tedious.
                        val rect = fittedImageRect(containerSize, imageWidth, imageHeight)
                        if (blockAt(blocks, rect, point) != null) {
                            selected = blocks.indices.toSet()
                        }
                    },
                )
            }
            .drawBehind {
                containerSize = size
                val rect = fittedImageRect(size, imageWidth, imageHeight)
                blocks.forEachIndexed { index, block ->
                    val box = block.toScreenRect(rect)
                    val isSelected = index in selected
                    drawRoundRect(
                        color = if (isSelected) SELECTION_FILL else HINT_FILL,
                        topLeft = Offset(box.left, box.top),
                        size = Size(box.width, box.height),
                        cornerRadius = androidx.compose.ui.geometry.CornerRadius(
                            BOX_RADIUS.dp.toPx(),
                            BOX_RADIUS.dp.toPx(),
                        ),
                    )
                }
            },
    ) {
        if (selected.isNotEmpty()) {
            val text = remember(selected, blocks) {
                selected.sorted().joinToString("\n") { blocks[it].text }
            }
            Row(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 96.dp)
                    .background(ACTION_FILL, RoundedCornerShape(14.dp))
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(20.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ActionButton(
                    icon = Icons.Outlined.ContentCopy,
                    label = if (selected.size == 1) "Copy" else "Copy ${selected.size}",
                ) {
                    clipboard.setText(AnnotatedString(text))
                    selected = emptySet()
                }
                ActionButton(icon = Icons.Outlined.SelectAll, label = "All") {
                    selected = blocks.indices.toSet()
                }
            }
        }
    }
}

@Composable
private fun ActionButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier.pointerInput(onClick) { detectTapGestures { onClick() } },
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = label, tint = Color.White)
        Text(
            label,
            color = Color.White,
            style = TextStyle(fontFamily = HyleGrotesk, fontSize = 15.sp),
        )
    }
}

private const val BOX_RADIUS = 3

/** Faint until touched: the text is discoverable without the photo becoming a wall of boxes. */
private val HINT_FILL = Color(0x22FFFFFF)
private val SELECTION_FILL = Color(0x668E7BFF)
private val ACTION_FILL = Color(0xE6121216)
