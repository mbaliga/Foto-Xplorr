package com.fotoxplorr.app.sources.ui

import com.fotoxplorr.app.sources.runtime.IndexPhase
import com.fotoxplorr.app.sources.runtime.SourceStatus
import com.fotoxplorr.core.index.sources.SourceState
import java.text.DecimalFormat

/** One source as the screen draws it, every string already made. Pure, so JVM tests pin what the person reads. */
data class SourceCard(
    val row: Long,
    val name: String,
    val state: SourceState,
    val stateLabel: String,
    val stateNote: String?,
    val filesLabel: String,
    val bytesLabel: String,
    val lastRead: String,
    val busy: Boolean,
    val progressText: String?,
    /** 0..1 while the walk or sizing can say how far it is, else null (an indeterminate bar). */
    val progressFraction: Float?,
    /** The last walk's problem and the files that could not be read, one sentence each. */
    val problems: List<String>,
    val sizesPending: Boolean,
    val included: Boolean,
    val indexOnConnect: Boolean,
    /** A rescan is offered unless one is running, and not while the grant is gone (the person must pick the folder again). */
    val canRescan: Boolean,
)

object SourcesUiModel {
    fun cards(statuses: List<SourceStatus>, formatTime: (Long) -> String): List<SourceCard> = statuses.map { card(it, formatTime) }

    fun card(s: SourceStatus, formatTime: (Long) -> String): SourceCard {
        val p = s.progress
        val busy = p != null
        val problems = buildList {
            s.meta.lastError?.takeIf { !busy && it != SourcesCopy.stateNote(s.info.state) }?.let { add(it) }
            if (s.unreadable > 0) add(SourcesCopy.unreadable(s.unreadable))
        }
        return SourceCard(
            row = s.info.row,
            name = s.info.displayName,
            state = s.info.state,
            stateLabel = SourcesCopy.stateLabel(s.info.state),
            stateNote = SourcesCopy.stateNote(s.info.state),
            filesLabel = SourcesCopy.files(s.files),
            bytesLabel = formatBytes(s.bytes),
            lastRead = if (s.meta.lastIndexedMs > 0) SourcesCopy.lastRead(formatTime(s.meta.lastIndexedMs)) else SourcesCopy.NEVER_READ,
            busy = busy,
            progressText = p?.let {
                when (it.phase) {
                    IndexPhase.WALKING -> if (it.filesFound > 0) "${SourcesCopy.READING} ${SourcesCopy.found(it.filesFound)}" else SourcesCopy.READING
                    IndexPhase.SIZING -> SourcesCopy.SIZING
                }
            },
            progressFraction = p?.fraction,
            problems = problems,
            sizesPending = !busy && s.files > 0 && s.sized + s.unreadable < s.files,
            included = s.meta.included,
            indexOnConnect = s.meta.indexOnConnect,
            canRescan = !busy,
        )
    }

    /** 1 KB = 1000 bytes, the way a drive's label counts. */
    fun formatBytes(bytes: Long): String {
        if (bytes < 1000) return "$bytes B"
        val units = arrayOf("KB", "MB", "GB", "TB")
        var v = bytes / 1000.0
        var i = 0
        while (v >= 1000 && i < units.lastIndex) { v /= 1000; i++ }
        return DecimalFormat(if (v >= 100) "0" else "0.#").format(v) + " " + units[i]
    }
}
