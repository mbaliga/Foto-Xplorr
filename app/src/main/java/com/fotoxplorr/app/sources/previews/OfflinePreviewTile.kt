package com.fotoxplorr.app.sources.previews

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.fotoxplorr.app.media.MediaAsset
import com.fotoxplorr.app.sources.gallery.SourceRef
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** What the store holds for one tile. */
sealed interface StoredPreview {
    data object Loading : StoredPreview
    data object None : StoredPreview
    class Found(val image: ImageBitmap) : StoredPreview
}

/**
 * Reads (and decodes, off the main thread) the stored preview of [asset] once [wanted]. A file
 * that cannot be decoded is discarded, so the next time the source is online its tile makes a new one.
 */
@Composable
fun rememberStoredPreview(previews: SourcePreviews?, asset: MediaAsset, wanted: Boolean): State<StoredPreview> =
    produceState<StoredPreview>(StoredPreview.Loading, previews, asset.sourceRef, asset.dateModifiedSeconds, wanted) {
        val s = SourceRef.sourceRowOf(asset.sourceRef)
        val a = SourceRef.assetRowOf(asset.sourceRef)
        if (!wanted || previews == null || s == null || a == null) { value = StoredPreview.None; return@produceState }
        val bytes = previews.store.read(s, a, asset.dateModifiedSeconds)
        if (bytes == null) { value = StoredPreview.None; return@produceState }
        val bitmap = withContext(Dispatchers.Default) { PreviewCodec.decode(bytes) }
        if (bitmap == null) { previews.store.discard(s, a); value = StoredPreview.None } else value = StoredPreview.Found(bitmap.asImageBitmap())
    }

/**
 * A source asset whose drive is away: its stored preview in grey, a small offline glyph (so it is
 * not colour alone) and, on a tile wide enough, the one-sentence cue. With no stored preview it is
 * a labelled neutral tile, never blank. The tile's own description says it is offline.
 */
@Composable
fun OfflinePreviewTile(
    asset: MediaAsset,
    stored: StoredPreview,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Crop,
    showReason: Boolean = false,
) {
    BoxWithConstraints(
        modifier.background(MaterialTheme.colorScheme.surfaceVariant).semantics { contentDescription = PreviewsCopy.offlineA11y(asset.displayName) },
        contentAlignment = Alignment.Center,
    ) {
        if (stored is StoredPreview.Found) {
            Image(
                bitmap = stored.image,
                contentDescription = null,
                contentScale = contentScale,
                colorFilter = ColorFilter.colorMatrix(ColorMatrix().apply { setToSaturation(0f) }),
                alpha = 0.7f,
                modifier = Modifier.matchParentSize(),
            )
        }
        val small = maxWidth < 72.dp
        Box(
            Modifier.align(Alignment.TopEnd).padding(if (small) 2.dp else 6.dp)
                .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.85f), CircleShape).padding(if (small) 2.dp else 4.dp),
        ) {
            Icon(Icons.Outlined.CloudOff, contentDescription = null, tint = MaterialTheme.colorScheme.onSurface, modifier = Modifier.size(if (small) 12.dp else 16.dp))
        }
        if (!small && (showReason || maxWidth >= 140.dp)) {
            Text(
                if (stored is StoredPreview.Found) PreviewsCopy.PLUG_IN else PreviewsCopy.NO_PREVIEW,
                color = MaterialTheme.colorScheme.onSurface,
                style = MaterialTheme.typography.labelMedium,
                textAlign = TextAlign.Center,
                modifier = Modifier.align(Alignment.BottomCenter).padding(6.dp)
                    .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.85f), RoundedCornerShape(6.dp)).padding(horizontal = 6.dp, vertical = 2.dp),
            )
        }
    }
}
