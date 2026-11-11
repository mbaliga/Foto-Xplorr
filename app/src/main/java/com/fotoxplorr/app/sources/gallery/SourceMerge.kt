package com.fotoxplorr.app.sources.gallery

import com.fotoxplorr.app.media.MediaAsset
import com.fotoxplorr.app.metadata.SafParentResolver
import com.fotoxplorr.core.index.sources.OverlapKey
import com.fotoxplorr.core.index.sources.SourceAssetIds
import com.fotoxplorr.core.index.sources.SourceKind
import com.fotoxplorr.core.index.sources.SourceOverlap
import java.net.URLDecoder

/** What a merge did, so the person can be told how many duplicates were hidden (Q3). */
data class SourceMergeResult(
    val assets: List<MediaAsset>,
    /** Source files hidden because MediaStore already lists the same file (volume, path, name, size). */
    val hiddenDuplicates: Int = 0,
    /** Source files not listed: not present, revoked, no uri, or an id that was already taken. */
    val skipped: Int = 0,
)

/**
 * The one place that knows two catalogues exist (ADR-020 decision 3). Used ONLY for display and id
 * lookups; consumers keyed to the MediaStore library or its stores keep the unmerged list.
 *
 * Library assets come first and untouched (same order, same instances). Source assets follow,
 * newest first (date taken, then id), so the merged list is stable between emissions. With no
 * sources the very same list instance comes back, which is what keeps every existing library-only
 * behaviour byte-identical.
 *
 * Guards: a source asset whose id is already used by a library asset loses (an id of the other
 * world in the band is a bug there, never a reason to drop a library photo); two source rows with
 * one id keep the first; ids in the result are therefore distinct.
 */
fun mergeSourceAssets(libraryAssets: List<MediaAsset>, sourceAssets: List<SourceAssetEntry>): List<MediaAsset> =
    mergeSourceAssetsReport(libraryAssets, sourceAssets).assets

fun mergeSourceAssetsReport(libraryAssets: List<MediaAsset>, sourceAssets: List<SourceAssetEntry>): SourceMergeResult {
    if (sourceAssets.isEmpty()) return SourceMergeResult(libraryAssets)

    val taken = HashSet<Long>(libraryAssets.size + sourceAssets.size)
    for (a in libraryAssets) taken += a.id.value
    val libraryKeys: Set<OverlapKey> = libraryAssets.asSequence()
        .filterNot { it.isTrashed }
        .mapTo(HashSet()) { SourceOverlapKeys.forLibrary(it) }

    var hidden = 0
    var skipped = 0
    val mapped = ArrayList<MediaAsset>(sourceAssets.size)
    for (entry in sourceAssets) {
        if (!SourceAssetMapper.isListed(entry)) { skipped++; continue }
        val asset = SourceAssetMapper.toMediaAsset(entry)
        if (asset == null || !SourceAssetIds.isSourceId(asset.id) || !taken.add(asset.id.value)) { skipped++; continue }
        val key = SourceOverlapKeys.forSource(entry)
        if (key != null && key in libraryKeys) {
            taken.remove(asset.id.value)
            hidden++
            continue
        }
        mapped += asset
    }
    if (mapped.isEmpty()) return SourceMergeResult(libraryAssets, hidden, skipped)
    mapped.sortWith(compareByDescending<MediaAsset> { it.dateTakenMillis }.thenByDescending { it.id.value })
    return SourceMergeResult(libraryAssets + mapped, hidden, skipped)
}

/**
 * The volume + path + name + size keys of PHASE-3-DESIGN 1.5, on both sides. MediaStore assets
 * carry no volume of their own (the scanner reads the merged `external` collection), so their
 * volume is the one in the content uri, which the provider calls `primary`; a source on an SD
 * card or USB drive has its own volume id and therefore never matches, and is shown rather than
 * hidden (the safe side of a heuristic). Only trees of the platform's external-storage provider
 * give a key; a cloud app's tree has no volume and is never hidden.
 */
object SourceOverlapKeys {
    private const val EXTERNAL_STORAGE_AUTHORITY = "com.android.externalstorage.documents"

    fun forLibrary(asset: MediaAsset): OverlapKey {
        val volume = Regex("^content://media/([^/]+)/").find(asset.contentUriString)?.groupValues?.get(1) ?: "external"
        return SourceOverlap.key(SafParentResolver.providerVolumeId(volume), asset.relativePath, asset.displayName, asset.sizeBytes)
    }

    fun forSource(entry: SourceAssetEntry): OverlapKey? {
        if (entry.source.kind == SourceKind.MEDIASTORE_VOLUME) return null
        val (volume, rootPath) = treeVolumeAndPath(entry.source.rootLocator) ?: return null
        val inside = entry.row.pathInsideTree.trim('/')
        val rel = listOf(rootPath, inside).filter { it.isNotEmpty() }.joinToString("/")
        return SourceOverlap.key(volume, rel, entry.row.displayName, entry.row.sizeBytes)
    }

    /** `(volume id, path inside the volume)` of a `content://com.android.externalstorage.documents/tree/<id>` uri. */
    fun treeVolumeAndPath(treeUri: String): Pair<String, String>? {
        val prefix = "content://$EXTERNAL_STORAGE_AUTHORITY/tree/"
        if (!treeUri.startsWith(prefix)) return null
        val encoded = treeUri.substring(prefix.length).substringBefore('/')
        // '+' is a literal plus in a document id; URLDecoder would turn it into a space.
        val id = runCatching { URLDecoder.decode(encoded.replace("+", "%2B"), "UTF-8") }.getOrNull() ?: return null
        val volume = id.substringBefore(':', "").takeIf { it.isNotEmpty() } ?: return null
        return volume to id.substringAfter(':', "").trim('/')
    }
}
