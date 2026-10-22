package com.fotoxplorr.app.spatial.offline

import java.text.DateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/** Where a candidate area came from. */
enum class AreaSource { THIS_VIEW, PLACE }

/**
 * One area the pane offers to save: what it covers, how many of the person's photos are in it, and
 * whether it fits the caps. Built by [OfflineMapsPaneModel.options]; holds no engine state.
 */
data class AreaOption(
    /** Stable for one set of inputs (`view`, `place-0`, ...), used to remember a typed name. */
    val key: String,
    val source: AreaSource,
    val box: GeoBox,
    /** Coordinates, until the person types a name. */
    val defaultName: String,
    /** Photos inside the box; 0 for [AreaSource.THIS_VIEW], which is not about photos. */
    val photoCount: Int,
    val verdict: BudgetVerdict,
) {
    /** The size this option would be saved at, or null when it cannot be saved at all. */
    val estimate: RegionEstimate?
        get() = when (verdict) {
            is BudgetVerdict.Fits -> verdict.estimate
            is BudgetVerdict.Reduced -> verdict.estimate
            is BudgetVerdict.TooLarge, BudgetVerdict.NoArea -> null
        }
}

/**
 * What the pane needs to know, besides the option itself, to decide whether Download may be tapped.
 *
 * [throttleInstalled] is the engine's flag ("the provider's four-request limit can be applied"); when
 * it is false nothing in the pane may start a download. [canDownloadBox] is the engine's own opinion
 * of a box (today it refuses boxes over the date line). [downloadRunning] is true from the tap until
 * the download stops: the pane runs one at a time.
 */
data class PaneEnv(
    val throttleInstalled: Boolean,
    val canDownloadBox: (GeoBox) -> Boolean,
    val saved: List<RegionRecord>,
    val downloadRunning: Boolean,
)

/** A button's label, whether it can be tapped, and the one sentence that says why not (or a caveat). */
data class DownloadButton(val label: String, val enabled: Boolean, val note: String?)

enum class RowStatus { DOWNLOADING, PAUSED, SAVED }

/** One line of the saved-areas list, ready to draw. */
data class SavedRow(
    val id: Long,
    val name: String,
    val status: RowStatus,
    /** "38 MB" once measured, "about 38 MB" while it is the estimate. */
    val sizeText: String,
    val dateText: String,
    /** The one sentence under the name for a download or a pause; null for a finished area. */
    val statusLine: String?,
    /** 0..1 while downloading and the total is known; null otherwise (indeterminate). */
    val fraction: Float?,
    val canResume: Boolean,
    /** Why Resume is off, or null. Shown only for a paused area. */
    val resumeNote: String?,
    val canPause: Boolean,
    val canDelete: Boolean,
    /** What deleting would free, for the confirmation. */
    val bytes: Long,
)

/**
 * The offline-maps pane's decisions as pure functions: which areas to offer, whether each Download
 * may be tapped and why not, and what each saved area's row says.
 *
 * Nothing here reads the network, the clock or an engine; the Connect pane feeds it what the engine
 * listed and what the person typed. The caps are [OfflineMapLimits]'s; none are repeated here.
 */
object OfflineMapsPaneModel {

    /**
     * The areas to offer: "this view" first when there is one, then the suggested places. A view that
     * is a point or a line is dropped. The box of every option comes from the browsable photo points
     * the caller passed (TRAPS #33) or from the map view; nothing here reads the photo library.
     */
    fun options(placePoints: Collection<GeoPoint>, currentView: GeoBox?): List<AreaOption> = buildList {
        if (currentView != null && !currentView.isZeroArea) {
            add(
                AreaOption(
                    key = KEY_VIEW,
                    source = AreaSource.THIS_VIEW,
                    box = currentView,
                    defaultName = RegionSuggestions.placeholderName(currentView),
                    photoCount = 0,
                    verdict = RegionBudget.plan(currentView),
                ),
            )
        }
        RegionSuggestions.suggest(placePoints).forEachIndexed { index, suggestion ->
            add(
                AreaOption(
                    key = "$KEY_PLACE_PREFIX$index",
                    source = AreaSource.PLACE,
                    box = suggestion.box,
                    defaultName = suggestion.namePlaceholder,
                    photoCount = suggestion.photoCount,
                    verdict = suggestion.verdict,
                ),
            )
        }
    }

    /**
     * [view] when the map's pill should be shown for it: an area the caps allow (detail may be
     * reduced) that the engine will take. Null for no view yet, a point, a view too big for the
     * caps, and an area over the date line. The pill is the only thing this gates; the pane
     * re-checks everything when it is opened.
     */
    fun offerableView(view: GeoBox?, canDownloadBox: (GeoBox) -> Boolean): GeoBox? {
        if (view == null || !canDownloadBox(view)) return null
        return when (RegionBudget.plan(view)) {
            is BudgetVerdict.Fits, is BudgetVerdict.Reduced -> view
            is BudgetVerdict.TooLarge, BudgetVerdict.NoArea -> null
        }
    }

    /** The name to save under: what was typed, trimmed and cut to the record's limit, else the default. */
    fun nameFor(option: AreaOption, typed: String?): String {
        val cleaned = typed.orEmpty().trim().take(RegionRecord.MAX_NAME_LENGTH).trim()
        return cleaned.ifEmpty { option.defaultName }
    }

    /**
     * [name], or [name] with a number if a saved area already has it, so a pause or delete can find
     * its download by name without mixing two areas up.
     */
    fun uniqueName(name: String, saved: List<RegionRecord>): String {
        val taken = saved.mapTo(HashSet()) { it.name }
        if (name !in taken) return name
        var n = 2
        while (true) {
            val suffix = " $n"
            val candidate = name.take(RegionRecord.MAX_NAME_LENGTH - suffix.length) + suffix
            if (candidate !in taken) return candidate
            n++
        }
    }

    /**
     * The Download button for [option], in the order the reasons matter: the build cannot apply the
     * provider's limit, a download is already running, the area is too big or not an area, the engine
     * will not take it, it is already saved, then there is no room. Only when none applies is it enabled.
     */
    fun button(option: AreaOption, env: PaneEnv): DownloadButton {
        val estimate = option.estimate
        val refusal: String? = when {
            !env.throttleInstalled -> OfflineMapsCopy.UNAVAILABLE
            env.downloadRunning -> OfflineMapsCopy.ONE_AT_A_TIME
            option.verdict is BudgetVerdict.TooLarge -> OfflineMapsCopy.tooLarge(option.verdict.reason)
            option.verdict is BudgetVerdict.NoArea -> OfflineMapsCopy.failure(FailureReason.NO_AREA)
            !env.canDownloadBox(option.box) -> OfflineMapsCopy.DATE_LINE
            estimate != null && isAlreadySaved(option, estimate, env.saved) -> OfflineMapsCopy.ALREADY_SAVED
            estimate != null -> OfflineMapsCopy.room(RegionLedger.checkRoom(env.saved, estimate.bytes))
            else -> OfflineMapsCopy.failure(FailureReason.ENGINE)
        }
        if (refusal != null || estimate == null) {
            return DownloadButton(OfflineMapsCopy.DOWNLOAD, enabled = false, note = refusal)
        }
        val caveat = if (estimate.reducedDetail) OfflineMapsCopy.REDUCED_DETAIL else null
        return DownloadButton(OfflineMapsCopy.downloadWithSize(estimate.bytes), enabled = true, note = caveat)
    }

    /**
     * True when a saved area already holds all of [option] at the detail it would be saved at, so a
     * second download would only spend the allowance twice. Areas over the date line are never
     * compared (they cannot be saved yet), and a saved area at a lower top zoom does not count.
     */
    fun isAlreadySaved(option: AreaOption, estimate: RegionEstimate, saved: List<RegionRecord>): Boolean {
        val box = option.box
        if (box.crossesAntimeridian) return false
        return saved.any { record ->
            val other = record.box
            !other.crossesAntimeridian &&
                record.maxZoom >= estimate.maxZoom &&
                other.west <= box.west && other.east >= box.east &&
                other.south <= box.south && other.north >= box.north
        }
    }

    /**
     * The saved-areas list: downloads first, then paused areas, then finished ones, each newest-used
     * first. [pauseReasons] says why an unfinished, not-running area stopped (an area missing from it
     * is one found after a relaunch). [live] holds the newest reading for areas being fetched; an area
     * with none falls back to what the engine stored.
     */
    fun savedRows(
        regions: List<StoredRegion>,
        pauseReasons: Map<Long, PauseReason>,
        live: Map<Long, RegionProgress>,
        env: PaneEnv,
        locale: Locale = Locale.getDefault(),
        zone: TimeZone = TimeZone.getDefault(),
    ): List<SavedRow> {
        val rows = regions.map { region -> rowOf(region, pauseReasons, live, env, locale, zone) }
        return rows.sortedWith(
            compareBy<SavedRow> { it.status.ordinal }.thenByDescending { lastUsed(regions, it.id) },
        )
    }

    private fun lastUsed(regions: List<StoredRegion>, id: Long): Long =
        regions.firstOrNull { it.id == id }?.record?.lastUsedMillis ?: 0L

    private fun rowOf(
        region: StoredRegion,
        pauseReasons: Map<Long, PauseReason>,
        live: Map<Long, RegionProgress>,
        env: PaneEnv,
        locale: Locale,
        zone: TimeZone,
    ): SavedRow {
        val record = region.record
        val size = if (record.bytesMeasured) {
            OfflineMapsCopy.megabytes(record.bytes)
        } else {
            "about ${OfflineMapsCopy.megabytes(record.bytes)}"
        }
        val date = formatDate(record.createdAtMillis, locale, zone)
        val status = when {
            region.running -> RowStatus.DOWNLOADING
            region.progress.complete -> RowStatus.SAVED
            else -> RowStatus.PAUSED
        }
        val reading = live[region.id] ?: region.progress
        val reason = pauseReasons[region.id] ?: PauseReason.RESTARTED
        val paused = DownloadState.Paused(PaneDownloadFlow.estimateOf(record), region.progress, reason)
        val canResume = status == RowStatus.PAUSED && !env.downloadRunning && DownloadGuard.canResume(paused, env.throttleInstalled)
        val resumeNote = when {
            status != RowStatus.PAUSED -> null
            !env.throttleInstalled -> OfflineMapsCopy.UNAVAILABLE
            env.downloadRunning -> OfflineMapsCopy.ONE_AT_A_TIME
            else -> null
        }
        return SavedRow(
            id = region.id,
            name = record.name,
            status = status,
            sizeText = size,
            dateText = date,
            statusLine = when (status) {
                RowStatus.DOWNLOADING -> OfflineMapsCopy.downloading(reading.fraction?.let { (it * 100f).toInt().coerceIn(0, 99) })
                RowStatus.PAUSED -> OfflineMapsCopy.paused(reason)
                RowStatus.SAVED -> null
            },
            fraction = if (status == RowStatus.DOWNLOADING) reading.fraction else null,
            canResume = canResume,
            resumeNote = resumeNote,
            canPause = status == RowStatus.DOWNLOADING,
            canDelete = status != RowStatus.DOWNLOADING,
            bytes = record.bytes,
        )
    }

    /** "2 of 6 areas saved, 80 of 300 MB used." Counts every stored area, finished or not. */
    fun quotaLine(records: List<RegionRecord>): String =
        OfflineMapsCopy.quota(records.size, RegionLedger.totalBytes(records))

    /** A short date for a row, in the person's language and time zone. */
    fun formatDate(millis: Long, locale: Locale, zone: TimeZone): String =
        DateFormat.getDateInstance(DateFormat.MEDIUM, locale).apply { timeZone = zone }.format(Date(millis))

    const val KEY_VIEW = "view"
    const val KEY_PLACE_PREFIX = "place-"
}
