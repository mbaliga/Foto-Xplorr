package com.fotoxplorr.app.media

import android.os.Build
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.isSpecified
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import coil3.compose.AsyncImage
import coil3.gif.AnimatedImageDecoder
import coil3.gif.GifDecoder
import coil3.request.ImageRequest

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
 */
@Composable
fun MediaImage(
    asset: MediaAsset,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Fit,
    animate: Boolean = false,
    onImageSize: ((Size) -> Unit)? = null,
) {
    val context = LocalContext.current
    // The decoder is attached PER REQUEST rather than registered on the shared ImageLoader, which
    // is what makes "loop animations" a real setting: a decoder registered globally would animate
    // everything everywhere with no way to opt a surface out.
    val request = remember(asset.contentUriString, asset.dateModifiedSeconds, animate) {
        ImageRequest.Builder(context)
            .data(asset.contentUri)
            // Without this, Coil's default memory-cache key is the Uri alone -- so a successful
            // overwrite (same row, new bytes) kept showing the THUMBNAIL Coil already had cached
            // for that Uri, indefinitely, because nothing about the request changed to tell it
            // otherwise. dateModifiedSeconds changes the moment the file's bytes do, which is
            // exactly the signal Coil needs to treat this as a different image to cache.
            .memoryCacheKey(mediaImageCacheKey(asset.contentUriString, asset.dateModifiedSeconds))
            .apply {
                if (animate) {
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
        onSuccess = onImageSize?.let { report ->
            { state ->
                val size = state.painter.intrinsicSize
                if (size.isSpecified) report(size)
            }
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
