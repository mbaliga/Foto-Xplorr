package com.fotoxplorr.app.sources.gallery

import com.fotoxplorr.app.gallery.folderIdentity
import com.fotoxplorr.app.media.MediaAsset
import com.fotoxplorr.app.metadata.SafParentResolver
import com.fotoxplorr.core.index.sources.OverlapKey
import com.fotoxplorr.core.index.sources.SourceAssetIds
import com.fotoxplorr.core.index.sources.SourceKind
import com.fotoxplorr.core.index.sources.SourceOverlap
import com.fotoxplorr.core.model.MediaId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
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
 * What the merge must know about the LIBRARY's protection (TRAPS #33). A source file that is the
 * same photo as a protected library photo must not surface through the source: the library photo
 * is protected by its id and its `folderIdentity` key, the source twin has a different id and a
 * different `~src/` folder key, so none of those locks would reach it.
 */
data class SourceMergeProtection(
    val archivedIds: Set<MediaId> = emptySet(),
    val sensitiveIds: Set<MediaId> = emptySet(),
    val lockedFolders: Set<String> = emptySet(),
) {
    companion object {
        val NONE = SourceMergeProtection()
    }
}

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
 *
 * Duplicates (design 1.5) are hidden on volume + path + name + size. On top of that, privacy never
 * rests on that heuristic: a source file with the same name and size as a library photo that is
 * locked, sensitive or archived is dropped whatever its volume and path (the library records no
 * volume of its own, so an SD-card photo cannot be matched by path), see [SourceMergeProtection].
 * Dropping too much only costs a duplicate; dropping too little would show a protected photo.
 */
fun mergeSourceAssets(
    libraryAssets: List<MediaAsset>,
    sourceAssets: List<SourceAssetEntry>,
    protection: SourceMergeProtection = SourceMergeProtection.NONE,
): List<MediaAsset> = mergeSourceAssetsReport(libraryAssets, sourceAssets, protection).assets

fun mergeSourceAssetsReport(
    libraryAssets: List<MediaAsset>,
    sourceAssets: List<SourceAssetEntry>,
    protection: SourceMergeProtection = SourceMergeProtection.NONE,
): SourceMergeResult = SourceMerger().merge(libraryAssets, sourceAssets, protection)

/**
 * A merge that remembers what it derived from the library list. Enrichment makes the source list
 * emit often while the library list stays the same instance, so the per-library work (an overlap
 * key per photo, the id set, the protected name+size set) is done once per library list and
 * protection state, not once per emission. Not thread safe: one instance per flow.
 */
class SourceMerger {
    private var cachedLibrary: List<MediaAsset>? = null
    private var cachedProtection: SourceMergeProtection? = null
    private var libraryIds: Set<Long> = emptySet()
    private var libraryKeys: Set<OverlapKey> = emptySet()
    private var protectedNameSize: Set<NameSize> = emptySet()

    private data class NameSize(val name: String, val size: Long)

    private fun prepare(library: List<MediaAsset>, protection: SourceMergeProtection) {
        if (cachedLibrary === library && cachedProtection == protection) return
        val ids = HashSet<Long>(library.size * 2)
        val keys = HashSet<OverlapKey>(library.size * 2)
        val guarded = HashSet<NameSize>()
        for (a in library) {
            ids += a.id.value
            // Trashed photos hide no source twin: the person put that copy in the trash on purpose.
            if (!a.isTrashed) keys += SourceOverlapKeys.forLibrary(a)
            val locked = protection.lockedFolders.isNotEmpty() && folderIdentity(a).key.value in protection.lockedFolders
            if (locked || a.id in protection.sensitiveIds || a.id in protection.archivedIds) {
                guarded += NameSize(a.displayName.trim().lowercase(), a.sizeBytes)
            }
        }
        libraryIds = ids
        libraryKeys = keys
        protectedNameSize = guarded
        cachedLibrary = library
        cachedProtection = protection
    }

    fun merge(
        libraryAssets: List<MediaAsset>,
        sourceAssets: List<SourceAssetEntry>,
        protection: SourceMergeProtection = SourceMergeProtection.NONE,
    ): SourceMergeResult {
        if (sourceAssets.isEmpty()) return SourceMergeResult(libraryAssets)
        prepare(libraryAssets, protection)

        var hidden = 0
        var skipped = 0
        val takenSource = HashSet<Long>(sourceAssets.size * 2)
        val mapped = ArrayList<MediaAsset>(sourceAssets.size)
        for (entry in sourceAssets) {
            if (!SourceAssetMapper.isListed(entry)) { skipped++; continue }
            val asset = SourceAssetMapper.toMediaAsset(entry)
            if (asset == null || !SourceAssetIds.isSourceId(asset.id) || asset.id.value in libraryIds || !takenSource.add(asset.id.value)) {
                skipped++; continue
            }
            val key = SourceOverlapKeys.forSource(entry)
            val sameFile = key != null && key in libraryKeys
            val protectedTwin = protectedNameSize.isNotEmpty() &&
                NameSize(entry.row.displayName.trim().lowercase(), entry.row.sizeBytes) in protectedNameSize
            if (sameFile || protectedTwin) {
                takenSource.remove(asset.id.value)
                hidden++
                continue
            }
            mapped += asset
        }
        if (mapped.isEmpty()) return SourceMergeResult(libraryAssets, hidden, skipped)
        mapped.sortWith(compareByDescending<MediaAsset> { it.dateTakenMillis }.thenByDescending { it.id.value })
        return SourceMergeResult(libraryAssets + mapped, hidden, skipped)
    }
}

/**
 * What the merge flow emits. [sourceFiles] is how many source files the merged list holds (0 means
 * [assets] is the library list itself); [sourcesSeen] is false only for the placeholder before the
 * source provider has answered once, which the process-death restore waits for.
 */
class MergedCatalogue(val assets: List<MediaAsset>, val sourceFiles: Int, val sourcesSeen: Boolean) {
    companion object {
        fun pending(library: List<MediaAsset>) = MergedCatalogue(library, 0, false)
    }
}

/**
 * The merged catalogue as a flow, computed OFF the main thread (a 100k library is real work) and
 * with one [SourceMerger], so the library side is derived once per library list however often the
 * source list emits. With no source rows the library list comes through untouched, the same instance.
 */
fun mergedCatalogueFlow(
    library: Flow<List<MediaAsset>>,
    sources: Flow<List<SourceAssetEntry>>,
    protection: Flow<SourceMergeProtection>,
): Flow<MergedCatalogue> {
    val merger = SourceMerger()
    return combine(library, sources, protection) { lib, src, prot ->
        val merged = merger.merge(lib, src, prot).assets
        MergedCatalogue(merged, merged.size - lib.size, sourcesSeen = true)
    }.flowOn(Dispatchers.Default)
}

/**
 * True while a restore of the open photo must wait: its id belongs to a source and the source
 * provider has not answered yet, so "not in the list" would only mean "not loaded". Library
 * photos arrive first, so restoring on the first non-empty list used to drop a source photo's id.
 */
fun mustWaitForSources(openId: MediaId?, sourcesSeen: Boolean): Boolean =
    openId != null && SourceAssetIds.isSourceId(openId) && !sourcesSeen

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
        val volume = mediaStoreVolume(asset.contentUriString)
        return SourceOverlap.key(SafParentResolver.providerVolumeId(volume), asset.relativePath, asset.displayName, asset.sizeBytes)
    }

    private const val MEDIA_PREFIX = "content://media/"

    /** The `<volume>` of `content://media/<volume>/...`, or `external` (the merged collection) when it has none. No Regex: this runs once per library photo. */
    internal fun mediaStoreVolume(uri: String): String {
        if (!uri.startsWith(MEDIA_PREFIX)) return "external"
        val end = uri.indexOf('/', MEDIA_PREFIX.length)
        return if (end > MEDIA_PREFIX.length) uri.substring(MEDIA_PREFIX.length, end) else "external"
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
