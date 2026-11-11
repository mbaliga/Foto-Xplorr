package com.fotoxplorr.app.sources.gallery

import com.fotoxplorr.app.media.MediaAsset
import com.fotoxplorr.core.index.sources.SourceState

/** The text carried in [MediaAsset.sourceRef]: which source row and which asset row the file is. */
object SourceRef {
    fun of(sourceRow: Long, assetRow: Long): String = "src:$sourceRow:$assetRow"

    fun sourceRowOf(ref: String?): Long? = parts(ref)?.first

    fun assetRowOf(ref: String?): Long? = parts(ref)?.second

    private fun parts(ref: String?): Pair<Long, Long>? {
        if (ref == null || !ref.startsWith("src:")) return null
        val p = ref.split(':')
        if (p.size != 3) return null
        val a = p[1].toLongOrNull() ?: return null
        val b = p[2].toLongOrNull() ?: return null
        return if (a >= 1 && b >= 1) a to b else null
    }
}

/**
 * Pure mapping from a `fotoz-sources.db` row (plus its source) to the gallery's [MediaAsset]
 * (PHASE-3-DESIGN 1.2 to 1.6):
 *  - id `-(2^33) - row`, from the row itself;
 *  - `relativePath = ~src/<sourceRow>/<path inside the tree>/`, so `folderIdentity` never merges it
 *    with a phone folder of the same name; `bucketName` is the leaf folder (or the source's name
 *    at the root);
 *  - the content uri is the document uri the walk stored, read by the same `ContentResolver`
 *    streams as any other uri (Coil tiles, `decodeUpright`, EXIF);
 *  - dates: taken falls back to the file's modified time until enrichment has read EXIF; width and
 *    height stay 0 until enriched (the layout falls back to a square, `MediaAsset.aspectRatio`);
 *  - never favourite, never trashed: a source asset has no row in those stores.
 */
object SourceAssetMapper {
    /** Null when the row has no uri to open (it cannot be shown or decoded). */
    fun toMediaAsset(entry: SourceAssetEntry): MediaAsset? {
        val row = entry.row
        val uri = row.contentUri?.takeIf { it.isNotBlank() } ?: return null
        val leaf = row.pathInsideTree.replace('\\', '/').split('/').lastOrNull { it.isNotBlank() }
        return MediaAsset(
            id = row.mediaId,
            contentUriString = uri,
            displayName = row.displayName,
            mimeType = row.mime,
            bucketName = leaf ?: entry.source.displayName.takeIf { it.isNotBlank() },
            bucketId = null,
            dateTakenMillis = if (row.dateTakenMs > 0) row.dateTakenMs else row.dateModifiedMs,
            dateModifiedSeconds = row.dateModifiedMs / 1000,
            width = row.width,
            height = row.height,
            sizeBytes = row.sizeBytes,
            durationMillis = row.durationMs,
            relativePath = row.relativePath,
            isFavorite = false,
            isTrashed = false,
            isExternal = false,
            sourceRef = SourceRef.of(row.sourceRow, row.row),
        )
    }

    /** Whether the gallery lists this entry at all: seen by the last full walk, and the grant still held. */
    fun isListed(entry: SourceAssetEntry): Boolean =
        entry.row.present && entry.source.state != SourceState.REVOKED
}
