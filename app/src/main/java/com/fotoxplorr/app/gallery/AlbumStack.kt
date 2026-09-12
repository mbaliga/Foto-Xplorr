package com.fotoxplorr.app.gallery

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.unit.dp
import com.fotoxplorr.app.media.MediaAsset
import com.fotoxplorr.app.media.MediaImage

/**
 * An album, drawn as a stack of photographs rather than a single cover tile.
 *
 * The owner's reference is iPhoto's album stacks -- prints fanned out with a slight rotation, so
 * the cover reads as "a pile of pictures" and its depth tells you at a glance that there is more
 * inside. That idea is kept; its execution is not. The reference is skeuomorphic (drop shadows,
 * paper texture, a glossy white border pretending to be a real print), and this app is brutalist
 * and pure black. So the stack here is geometry only: flat rectangles, a hairline edge, and a
 * rotation per layer. The *information* the stack carries survives; the pretend materials do not.
 *
 * The rotation is deterministic per album, derived from its own key, rather than random. A random
 * fan would re-shuffle on every recomposition -- albums would visibly twitch as the list scrolled,
 * which is exactly the sort of thing that reads as a rendering bug.
 */
@Composable
fun AlbumStack(
    covers: List<MediaAsset>,
    label: String,
    count: Int,
    stackKey: String,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.padding(8.dp)) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                // Room for the fanned layers to lean out without clipping against the neighbours.
                .padding(10.dp),
            contentAlignment = Alignment.Center,
        ) {
            if (covers.isEmpty()) {
                Box(
                    Modifier
                        .fillMaxSize()
                        .background(Color(0xFF141414))
                        .border(1.dp, Color.White.copy(alpha = 0.12f)),
                )
            } else {
                // Drawn back to front, so the topmost photo is the LAST composed and the one the
                // eye lands on. Reversed here rather than at the call site because "the first
                // cover is the one on top" is this composable's contract, not the caller's.
                val layers = covers.take(MAX_LAYERS)
                layers.reversed().forEachIndexed { indexFromBack, asset ->
                    val depth = layers.lastIndex - indexFromBack
                    Box(
                        Modifier
                            .fillMaxSize()
                            .graphicsLayer {
                                rotationZ = stackRotation(stackKey, depth)
                                // Deeper layers sit fractionally smaller, which is what makes the
                                // fan read as depth rather than as a set of misaligned rectangles.
                                val shrink = 1f - depth * LAYER_SHRINK
                                scaleX = shrink
                                scaleY = shrink
                            }
                            .background(Color.Black)
                            .border(1.dp, Color.White.copy(alpha = if (depth == 0) 0.25f else 0.10f)),
                    ) {
                        MediaImage(
                            asset = asset,
                            modifier = Modifier.fillMaxSize(),
                            contentScale = ContentScale.Crop,
                        )
                        // Every layer below the top is dimmed, so the stack has an obvious front
                        // even when all its photos are similarly bright.
                        if (depth > 0) {
                            Box(
                                Modifier
                                    .fillMaxSize()
                                    .background(Color.Black.copy(alpha = 0.25f + 0.2f * depth)),
                            )
                        }
                    }
                }
            }
        }
        Text(
            text = label,
            color = Color.White,
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 6.dp),
        )
        Text(
            text = "$count",
            color = Color.White.copy(alpha = 0.5f),
            style = MaterialTheme.typography.labelSmall,
        )
    }
}

/**
 * A stable lean for the layer at [depth] of the stack identified by [key].
 *
 * Deterministic on purpose: derived from the key's hash rather than from a random source, so an
 * album's fan is the same every time it is drawn. A random angle would re-roll on every
 * recomposition and the whole grid would twitch while scrolling.
 *
 * Pure and internal so the determinism can actually be asserted rather than assumed.
 */
internal fun stackRotation(key: String, depth: Int): Float {
    if (depth == 0) return 0f
    // Two different multipliers per depth so layer 1 and layer 2 do not lean identically; the
    // hash supplies the per-album variation and the depth supplies the fan.
    val seed = key.hashCode()
    val direction = if ((seed shr depth) and 1 == 0) 1f else -1f
    val magnitude = MIN_LEAN + (kotlin.math.abs(seed / (depth + 1)) % LEAN_RANGE)
    return direction * magnitude * depth
}

private const val MAX_LAYERS = 3
private const val LAYER_SHRINK = 0.04f
private const val MIN_LEAN = 2f
private const val LEAN_RANGE = 4
