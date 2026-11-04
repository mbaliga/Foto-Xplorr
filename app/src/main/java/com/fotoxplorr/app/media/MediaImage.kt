package com.fotoxplorr.app.media

import android.os.Build
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.isSpecified
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import coil3.compose.AsyncImage
import coil3.gif.AnimatedImageDecoder
import coil3.gif.GifDecoder
import coil3.request.ImageRequest
import com.fotoxplorr.core.formats.LoadResult
import com.fotoxplorr.core.formats.SizeHint
import com.fotoxplorr.core.formats.TileState
import com.fotoxplorr.core.formats.simple.SimpleFormatDecoders
import com.fotoxplorr.core.formats.tileState

/**
 * Every photo and video thumbnail in the app goes through here.
 *
 * @param animate play animated images (GIF, animated WebP) rather than showing their first frame.
 *   Off by default and gated on a user preference at the call sites, because a mosaic where every
 *   animated image plays at once is both visually noisy and genuinely expensive -- each one holds
 *   a decoder and drives its own invalidation loop for as long as it stays on screen.
 * @param onImageSize told the size the image decoded to, once it has (the viewer's backdrop for a
 *   transparent photo needs the picture's real shape, which a vector image has no other source
 *   for). Only the shape is meaningful: the decode may be smaller than the file.
 * @param showReason draw the placeholder's one-line reason even on a small area (the viewer does).
 *
 * A file that cannot be drawn -- the registry says no decoder exists, or the load fails -- gets a
 * [FormatPlaceholder] instead of an empty or black area (WP1.7). The decision is the pure
 * [tileState]; this function only feeds it.
 */
@Composable
fun MediaImage(
    asset: MediaAsset,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Fit,
    animate: Boolean = false,
    onImageSize: ((Size) -> Unit)? = null,
    showReason: Boolean = false,
) {
    val context = LocalContext.current
    val format by rememberResolvedFormat(asset)
    val verdict = remember(format, asset.mimeType, asset.width, asset.height, asset.sizeBytes) {
        DecoderRegistries.current().probe(format, asset.mimeType, SizeHint(asset.width, asset.height, asset.sizeBytes))
    }
    var load by remember(asset.contentUriString, asset.dateModifiedSeconds) { mutableStateOf(LoadResult.Pending) }
    val state = tileState(format, verdict, load)
    if (state is TileState.Placeholder) {
        FormatPlaceholder(
            state, modifier, showReason,
            nameForReader = if (load == LoadResult.Failed && verdict.canShow) asset.displayName else null,
        )
        return
    }

    // The decoder is attached PER REQUEST rather than registered on the shared ImageLoader, which
    // is what makes "loop animations" a real setting: a decoder registered globally would animate
    // everything everywhere with no way to opt a surface out.
    val request = remember(asset.contentUriString, asset.dateModifiedSeconds, animate, format) {
        ImageRequest.Builder(context)
            .data(asset.contentUri)
            // Without this, Coil's default memory-cache key is the Uri alone -- so a successful
            // overwrite (same row, new bytes) kept showing the THUMBNAIL Coil already had cached
            // for that Uri, indefinitely, because nothing about the request changed to tell it
            // otherwise. dateModifiedSeconds changes the moment the file's bytes do, which is
            // exactly the signal Coil needs to treat this as a different image to cache.
            .memoryCacheKey(mediaImageCacheKey(asset.contentUriString, asset.dateModifiedSeconds))
            .apply {
                // Pure-Kotlin decoders for TGA, TIFF, PSD... (never animated, so they win over `animate`).
                if (SimpleFormatDecoders.supports(format)) {
                    decoderFactory(SimpleCoilDecoder.Factory(format))
                } else if (animate) {
                    // ImageDecoder-backed on API 28+, which handles animated WebP and HEIF as
                    // well; the older GifDecoder is GIF-only but works back to this app's minSdk.
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                        decoderFactory(AnimatedImageDecoder.Factory())
                    } else {
                        decoderFactory(GifDecoder.Factory())
                    }
                }
            }
            .build()
    }

    AsyncImage(
        model = request,
        contentDescription = asset.displayName,
        modifier = modifier,
        contentScale = contentScale,
        onError = { load = LoadResult.Failed },
        onSuccess = { state ->
            load = LoadResult.Loaded
            val size = state.painter.intrinsicSize
            if (onImageSize != null && size.isSpecified) onImageSize(size)
        },
    )
}

/**
 * The Coil memory-cache key for [uri] at [dateModifiedSeconds].
 *
 * Pure and separate from the composable above so it is pinnable with plain JUnit: two different
 * assets must never collide, and the SAME asset before and after a write must never produce the
 * same key.
 */
internal fun mediaImageCacheKey(uri: String, dateModifiedSeconds: Long): String = "$uri@$dateModifiedSeconds"
