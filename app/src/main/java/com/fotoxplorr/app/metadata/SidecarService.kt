package com.fotoxplorr.app.metadata

import com.fotoxplorr.core.metadata.SidecarAnnotations
import com.fotoxplorr.core.metadata.SidecarNaming
import com.fotoxplorr.core.metadata.SidecarPolicy
import com.fotoxplorr.core.metadata.XmpAnnotationCodec
import java.time.Instant

/** An asset as the sidecar layer needs to see it. No UI, no library store, no MediaStore here. */
data class SidecarAsset(
    val fileName: String,
    val source: SidecarPolicy.Source,
    val parent: SidecarParent,
)

sealed interface SidecarReadResult {
    data class Found(val annotations: SidecarAnnotations, val location: SidecarLocation) : SidecarReadResult
    data class NotFound(val location: SidecarLocation) : SidecarReadResult

    /** A file exists but is not XML we can parse: left alone, never overwritten. */
    data class Unparseable(val location: SidecarLocation) : SidecarReadResult

    /** We cannot even look ([SidecarLocation.NeedsFolderGrant] / [SidecarLocation.Unsupported]). */
    data class NoAccess(val location: SidecarLocation) : SidecarReadResult
}

sealed interface SidecarWriteResult {
    data class Written(val location: SidecarLocation.Writable) : SidecarWriteResult

    /** The policy said no (off, or not an explicit action). Nothing happened, by design. */
    data class Skipped(val reason: String) : SidecarWriteResult

    /** The policy says embed: this layer never edits the photo itself; route to MetadataWriter. */
    data object EmbedInFileRequested : SidecarWriteResult

    data object NeedsFolderGrant : SidecarWriteResult
    data class Unsupported(val reason: String) : SidecarWriteResult
    data class ReadOnly(val location: SidecarLocation.ReadOnly) : SidecarWriteResult
    data object ExistingSidecarUnparseable : SidecarWriteResult
    data class Failed(val reason: String) : SidecarWriteResult
}

/**
 * The only door the rest of the app uses for sidecars. Blocking (file/IO) calls: run them off the
 * main thread. No UI is wired to it yet, and nothing in the library stores, the indexer or any
 * SharedPreferences calls it: where ratings and flags are STORED is the owner's pending decision.
 */
interface SidecarService {
    fun read(asset: SidecarAsset): SidecarReadResult

    /**
     * Applies [annotations] to the sidecar if [SidecarPolicy] allows. Foreign content and foreign
     * keywords are kept; only [removedKeywords]/[removedPaths] are deleted.
     */
    fun write(
        asset: SidecarAsset,
        annotations: SidecarAnnotations,
        explicitUserAction: Boolean,
        removedKeywords: Set<String> = emptySet(),
        removedPaths: Set<String> = emptySet(),
    ): SidecarWriteResult
}

/**
 * The pure function the indexer will call on index (wiring comes later): a foreign sidecar's
 * bytes in, annotations out; null when the bytes are not parseable XMP.
 */
object SidecarIndexReader {
    fun annotationsFrom(sidecarBytes: ByteArray): SidecarAnnotations? = XmpAnnotationCodec.readBytes(sidecarBytes)
}

class DefaultSidecarService(
    private val policy: () -> SidecarPolicy,
    private val now: () -> String = { Instant.now().toString() },
) : SidecarService {

    override fun read(asset: SidecarAsset): SidecarReadResult {
        val style = policy().style
        val location = SidecarLocator.locate(asset.fileName, asset.parent, style, asset.source.isRaw)
        val dir = (asset.parent as? SidecarParent.Directory)?.directory
            ?: return SidecarReadResult.NoAccess(location)
        val entry = SidecarLocator.findExisting(asset.fileName, dir, style, asset.source.isRaw) ?: return SidecarReadResult.NotFound(location)
        val bytes = dir.read(entry) ?: return SidecarReadResult.NoAccess(location)
        val annotations = SidecarIndexReader.annotationsFrom(bytes)
            ?: return SidecarReadResult.Unparseable(location)
        return SidecarReadResult.Found(annotations, location)
    }

    override fun write(
        asset: SidecarAsset,
        annotations: SidecarAnnotations,
        explicitUserAction: Boolean,
        removedKeywords: Set<String>,
        removedPaths: Set<String>,
    ): SidecarWriteResult {
        val current = policy()
        when (val decision = current.decide(asset.source, explicitUserAction)) {
            is SidecarPolicy.Decision.Skip -> return SidecarWriteResult.Skipped(decision.reason)
            SidecarPolicy.Decision.EmbedInFile -> return SidecarWriteResult.EmbedInFileRequested
            is SidecarPolicy.Decision.WriteSidecar -> Unit
        }
        val style = current.style
        val location = SidecarLocator.locate(asset.fileName, asset.parent, style, asset.source.isRaw)
        val target = when (location) {
            SidecarLocation.NeedsFolderGrant -> return SidecarWriteResult.NeedsFolderGrant
            is SidecarLocation.Unsupported -> return SidecarWriteResult.Unsupported(location.reason)
            is SidecarLocation.ReadOnly -> return SidecarWriteResult.ReadOnly(location)
            is SidecarLocation.Writable -> location
        }
        val dir = (asset.parent as SidecarParent.Directory).directory
        // The base is the sidecar the locator found (which may be the other style's: its foreign
        // content is carried into the configured-style name, the old file is left alone).
        val base = SidecarLocator.findExisting(asset.fileName, dir, style, asset.source.isRaw)
        val baseBytes = if (base == null) null else dir.read(base) ?: return SidecarWriteResult.Failed("Could not read the existing sidecar.")
        val bytes = XmpAnnotationCodec.update(baseBytes, annotations, now(), removedKeywords, removedPaths)
            ?: return SidecarWriteResult.ExistingSidecarUnparseable
        val targetName = SidecarNaming.sidecarFileName(asset.fileName, style)
        // find() ignores case: an existing IMG.CR3.XMP is overwritten under its own name.
        val sameNameEntry = dir.find(targetName)
        return if (dir.write(targetName, sameNameEntry, bytes)) {
            // Report the file actually written, never the base's uri: when the base was the other
            // style's file (IMG_1.xmp for a darktable-style RAW), target still points at it.
            val written = sameNameEntry ?: dir.find(targetName)
            SidecarWriteResult.Written(
                if (written != null) SidecarLocation.Writable(written.uri, written.fileName, true)
                else SidecarLocation.Writable(dir.uri, targetName, false),
            )
        } else {
            SidecarWriteResult.Failed("The sidecar could not be written.")
        }
    }
}
