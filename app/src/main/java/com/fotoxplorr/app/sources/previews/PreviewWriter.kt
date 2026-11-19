package com.fotoxplorr.app.sources.previews

import android.content.Context
import com.fotoxplorr.app.media.DecodeLimits
import com.fotoxplorr.app.media.MediaAsset
import com.fotoxplorr.app.media.decodeUpright
import com.fotoxplorr.app.sources.gallery.SourceRef
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext

/**
 * Makes the 256 px preview of one source asset while its file can still be read.
 *
 * TRAPS #33: this only ever receives an asset some surface is already showing the person (a tile
 * of the browsable list, or the [PreviewPass] input that the caller took from the browsable list).
 * It adds no list of its own, so a protected, archived, locked-folder or sensitive-hidden asset
 * never gets a preview unless the browsable list admits it.
 * TRAPS #34: pixels come from `decodeUpright`, so a sideways photo is stored upright.
 */
class PreviewWriter(
    private val context: Context,
    private val store: SourcePreviewStore,
    private val settings: PreviewSettings,
    private val cpu: CoroutineDispatcher = Dispatchers.Default,
    private val decode: suspend (MediaAsset) -> android.graphics.Bitmap? = { asset ->
        decodeUpright(context, asset.contentUri, DecodeLimits(PreviewPolicy.EDGE_PX, PreviewPolicy.EDGE_PX.toLong() * PreviewPolicy.EDGE_PX * 4)).let { it?.bitmap }
    },
) {
    // Two decodes at a time: a grid scrolling past must not open a hundred files of a slow drive at once.
    private val gate = Semaphore(2)

    // Files that could not be decoded this process: asked again on the next launch, not on every tile.
    private val failed = HashSet<String>()

    /** True when a preview of this revision is stored afterwards (it was, or it was just made). */
    suspend fun capture(asset: MediaAsset): Boolean {
        val sourceRow = SourceRef.sourceRowOf(asset.sourceRef) ?: return false
        val assetRow = SourceRef.assetRowOf(asset.sourceRef) ?: return false
        if (PreviewPolicy.isOff(settings.capBytes(sourceRow))) return false
        val revision = asset.dateModifiedSeconds
        val key = "$sourceRow:$assetRow:$revision"
        if (synchronized(failed) { key in failed }) return false
        if (store.has(sourceRow, assetRow, revision)) return true
        return gate.withPermit {
            // Re-checked inside the gate: the same tile can ask twice while the first decode runs.
            if (store.has(sourceRow, assetRow, revision)) return@withPermit true
            val bytes = decode(asset)?.let { bitmap -> withContext(cpu) { PreviewCodec.encode(bitmap) } }
            val ok = bytes != null && store.write(sourceRow, assetRow, revision, bytes)
            if (!ok && bytes == null) synchronized(failed) { failed += key }
            ok
        }
    }
}

/**
 * The bounded background pass of "keep previews": walks the browsable source assets of ONLINE
 * sources that asked for it and makes the missing previews, at most [limit] new ones per run, so
 * one run is a small, cancellable amount of work. The input must be the browsable list
 * (TRAPS #33); [candidates] is the pure part the tests pin.
 */
class PreviewPass(private val writer: PreviewWriter, private val store: SourcePreviewStore, private val settings: PreviewSettings) {
    suspend fun candidates(browsable: List<MediaAsset>, onlineRows: Set<Long>): List<MediaAsset> =
        browsable.filter { a ->
            val s = SourceRef.sourceRowOf(a.sourceRef) ?: return@filter false
            val r = SourceRef.assetRowOf(a.sourceRef) ?: return@filter false
            s in onlineRows && settings.keep(s) && !PreviewPolicy.isOff(settings.capBytes(s)) &&
                a.mimeType.startsWith("image/") && !store.has(s, r, a.dateModifiedSeconds)
        }

    /** How many previews this run made. Stops at [limit], on cancellation, and never touches a library asset. */
    suspend fun run(browsable: List<MediaAsset>, onlineRows: Set<Long>, limit: Int = DEFAULT_LIMIT): Int {
        var made = 0
        for (asset in candidates(browsable, onlineRows)) {
            if (made >= limit) break
            kotlinx.coroutines.yield()
            if (writer.capture(asset)) made++
        }
        return made
    }

    companion object {
        const val DEFAULT_LIMIT = 150
    }
}
