package com.fotoxplorr.app.sources.ui

import com.fotoxplorr.app.sources.drives.IgnoredDrive
import com.fotoxplorr.app.sources.runtime.SourceStatus
import com.fotoxplorr.core.index.sources.SourceState

/**
 * What the offline previews of one source cost on disk. U5 owns the previews and will implement
 * this; until then [NoPreviewCost] answers null ("not kept"), so this panel never depends on U5's code.
 */
interface PreviewCost {
    /** Bytes of previews kept for the source, or null when none are kept or nothing can say. */
    fun bytesFor(sourceRow: Long): Long?
}

object NoPreviewCost : PreviewCost {
    override fun bytesFor(sourceRow: Long): Long? = null
}

/**
 * On how many places a source's photos are also kept (backup destinations, cloud sync). A count of
 * what the existing backup and sync manifests already record would answer it, but those manifests
 * are keyed by photo and destination, not by source, and reading them means decoding a file per
 * destination, so there is no cheap read today. The default answers null and the panel shows an honest placeholder.
 */
interface ProtectionCount {
    fun placesFor(sourceRow: Long): Int?
}

object NoProtectionCount : ProtectionCount {
    override fun placesFor(sourceRow: Long): Int? = null
}

/** One source in the storage section, every string already made. */
data class StorageRow(
    val row: Long,
    val name: String,
    val online: Boolean,
    val stateLabel: String,
    val filesLabel: String,
    val bytesLabel: String,
    /** Null when no preview cost is known. */
    val previewsLabel: String?,
    val lastRead: String,
    val problems: List<String>,
    /** Null when the count is not available. */
    val protectedLabel: String?,
)

data class StoragePanelState(
    val summary: String,
    val totalBytesLabel: String,
    val rows: List<StorageRow>,
    /** Shown once when no source can say how many places hold its photos. Null when at least one can. */
    val protectionPlaceholder: String?,
    val ignored: List<IgnoredDrive>,
)

object StoragePanelModel {
    fun build(
        statuses: List<SourceStatus>,
        formatTime: (Long) -> String,
        previewCost: PreviewCost = NoPreviewCost,
        protection: ProtectionCount = NoProtectionCount,
        ignored: List<IgnoredDrive> = emptyList(),
    ): StoragePanelState {
        val rows = statuses.map { s ->
            val cost = previewCost.bytesFor(s.info.row)
            val places = protection.placesFor(s.info.row)
            StorageRow(
                row = s.info.row,
                name = s.info.displayName,
                online = s.info.state == SourceState.ONLINE,
                stateLabel = SourcesCopy.stateLabel(s.info.state),
                filesLabel = SourcesCopy.files(s.files),
                bytesLabel = SourcesUiModel.formatBytes(s.bytes),
                previewsLabel = cost?.let { StorageCopy.previews(SourcesUiModel.formatBytes(it)) },
                lastRead = if (s.meta.lastIndexedMs > 0) SourcesCopy.lastRead(formatTime(s.meta.lastIndexedMs)) else SourcesCopy.NEVER_READ,
                problems = buildList {
                    s.meta.lastError?.takeIf { s.progress == null && it != SourcesCopy.stateNote(s.info.state) }?.let { add(it) }
                    if (s.unreadable > 0) add(SourcesCopy.unreadable(s.unreadable))
                },
                protectedLabel = places?.let { StorageCopy.protectedOn(it) },
            )
        }
        val online = rows.count { it.online }
        return StoragePanelState(
            summary = StorageCopy.summary(rows.size, online, statuses.sumOf { it.files }),
            totalBytesLabel = SourcesUiModel.formatBytes(statuses.sumOf { it.bytes }),
            rows = rows,
            protectionPlaceholder = if (rows.isNotEmpty() && rows.all { it.protectedLabel == null }) StorageCopy.PROTECTION_PENDING else null,
            ignored = ignored,
        )
    }
}

/** The words of the storage section. One short sentence each. */
object StorageCopy {
    const val TITLE = "Storage"
    const val EMPTY = "Nothing to count yet."
    const val PROTECTION_PENDING = "Fotoz does not count backup and sync copies of these photos yet."
    const val IGNORED_TITLE = "Drives you asked not to be offered."
    const val ASK_AGAIN = "Ask again"
    const val PREVIEWS_NONE = "No offline previews kept."

    fun summary(sources: Int, online: Int, files: Int): String {
        val s = if (sources == 1) "1 folder" else "$sources folders"
        val f = if (files == 1) "1 file" else "$files files"
        return "$s, $online online, $f."
    }

    fun previews(bytes: String): String = "Offline previews use $bytes."
    fun protectedOn(places: Int): String = if (places == 1) "Protected on 1 place." else "Protected on $places places."
    fun askAgainA11y(label: String): String = "Ask again when $label is connected"
}
