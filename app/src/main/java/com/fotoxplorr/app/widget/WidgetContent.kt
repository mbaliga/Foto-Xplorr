package com.fotoxplorr.app.widget

import com.fotoxplorr.app.ScanState
import com.fotoxplorr.app.background.BackgroundWorkStatus
import com.fotoxplorr.app.gallery.GallerySort
import com.fotoxplorr.app.gallery.folderIdentity
import com.fotoxplorr.app.gallery.isScreenshot
import com.fotoxplorr.app.gallery.sortAssets
import com.fotoxplorr.app.media.MediaAsset
import com.fotoxplorr.app.organize.MediaCollection
import com.fotoxplorr.core.model.MediaId
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * Everything the home-screen widgets read, gathered once per update as plain values (no Context, no
 * store), so every decision below is a pure function a JVM test can pin.
 *
 * [assets] is the whole catalogue as the app last saw it. Nothing draws from it directly: [visible]
 * is the only list a widget may count, [pictured] the only list it may draw or open, and both are
 * computed here, from the stores, never from an Activity's live state. The raw list is kept only so
 * a builder can tell "nothing of this kind exists" from "something exists but may not be shown"
 * ([WidgetState.Withheld]).
 */
internal data class WidgetLibrary(
    val assets: List<MediaAsset>,
    val favoriteIds: Set<MediaId>,
    val sensitiveIds: Set<MediaId>,
    val archivedIds: Set<MediaId>,
    val lockedFolders: Set<String>,
    val collections: List<MediaCollection>,
    val hasMediaAccess: Boolean,
    /** Photos the on-device text check read as identity documents; never drawn ([WidgetPrivacy.picturedAssets]). */
    val identityIds: Set<MediaId> = emptySet(),
    /**
     * Wall-clock time Fotoz last finished reading the library from the phone ([LibraryReadStamp]),
     * or null when no read has been recorded. The catalogue is only as fresh as this: a process
     * started for a widget does not scan.
     */
    val libraryReadAtMillis: Long? = null,
) {
    /** What a widget may count: the forced home-screen filter ([WidgetPrivacy.visibleAssets]). */
    val visible: List<MediaAsset> by lazy {
        WidgetPrivacy.visibleAssets(assets, archivedIds, sensitiveIds, lockedFolders)
    }

    /** What a widget may draw as a picture or open from a tap: [visible] minus screenshots and identity documents. */
    val pictured: List<MediaAsset> by lazy { WidgetPrivacy.notPictured(visible, identityIds) }
}

/** What a widget can honestly say, in the order a builder checks for it. */
internal sealed interface WidgetState<out T> {
    data class Ready<T>(val content: T) : WidgetState<T>

    /** The app holds no photo or video permission, so it has nothing it may read. */
    data object NoAccess : WidgetState<Nothing>

    /** Nothing of this kind exists in the library. */
    data object Empty : WidgetState<Nothing>

    /**
     * Items of this kind exist, but every one is archived, sensitive or in a protected folder.
     * Drawn with exactly the same words as [Empty], on purpose: a different message would tell
     * anyone holding the phone that hidden items exist. Kept as its own value so tests can prove
     * the hidden items never came through.
     */
    data object Withheld : WidgetState<Nothing>
}

// ---- A favourite ----------------------------------------------------------------------------

internal data class FavouriteContent(
    val asset: MediaAsset,
    /** How many favourites the home screen may show, this one included. */
    val shownFavourites: Int,
)

/**
 * One favourite per local day. The choice is a pure function of the day and the favourites that
 * may be shown, so every redraw during a day shows the same photo instead of flickering, and the
 * next day brings another. Candidates are ordered by id so the result does not depend on the order
 * the catalogue happened to load in.
 */
internal fun favouriteOfTheDay(library: WidgetLibrary, epochDay: Long): WidgetState<FavouriteContent> {
    if (!library.hasMediaAccess) return WidgetState.NoAccess
    val candidates = library.pictured
        .filter { it.id in library.favoriteIds }
        .sortedBy { it.id.value }
    if (candidates.isEmpty()) {
        val anyLive = library.assets.any { it.id in library.favoriteIds && !it.isTrashed }
        return if (anyLive) WidgetState.Withheld else WidgetState.Empty
    }
    val index = Math.floorMod(dayMix(epochDay), candidates.size.toLong()).toInt()
    return WidgetState.Ready(FavouriteContent(candidates[index], candidates.size))
}

/** SplitMix64's finaliser: spreads consecutive days across the candidates instead of stepping by one. */
internal fun dayMix(epochDay: Long): Long {
    var z = epochDay + -7046029254386353131L // 0x9E3779B97F4A7C15
    z = (z xor (z ushr 30)) * -4658895280553007687L // 0xBF58476D1CE4E5B9
    z = (z xor (z ushr 27)) * -7723592293110705685L // 0x94D049BB133111EB
    return z xor (z ushr 31)
}

// ---- On this day ----------------------------------------------------------------------------

internal data class OnThisDayContent(
    val today: LocalDate,
    /** Most recent year first, then one from each earlier year in turn; capped at the run limit. */
    val items: List<MediaAsset>,
    val totalCount: Int,
    val yearCount: Int,
)

/**
 * Photos and videos taken on today's month and day in an earlier year, by date taken in [zone].
 * Interleaved by year so a strip of four shows four different years where it can, rather than four
 * shots from last year's party.
 */
internal fun onThisDay(
    library: WidgetLibrary,
    today: LocalDate,
    zone: ZoneId,
    limit: Int = WIDGET_RUN_LIMIT,
): WidgetState<OnThisDayContent> {
    if (!library.hasMediaAccess) return WidgetState.NoAccess
    fun takenOn(asset: MediaAsset): LocalDate? = asset.dateTakenMillis
        .takeIf { it > 0L }
        ?.let { Instant.ofEpochMilli(it).atZone(zone).toLocalDate() }
    fun matches(asset: MediaAsset): Boolean {
        val taken = takenOn(asset) ?: return false
        return taken.year < today.year && isSameCalendarDay(taken, today)
    }

    val matching = library.pictured.filter(::matches)
    if (matching.isEmpty()) {
        val anyLive = library.assets.any { !it.isTrashed && matches(it) }
        return if (anyLive) WidgetState.Withheld else WidgetState.Empty
    }
    val byYear = matching
        .groupBy { takenOn(it)!!.year }
        .toSortedMap(compareByDescending { it })
        .values
        .map { year -> year.sortedWith(compareBy<MediaAsset> { it.dateTakenMillis }.thenBy { it.id.value }) }
    return WidgetState.Ready(
        OnThisDayContent(
            today = today,
            items = roundRobin(byYear).take(limit),
            totalCount = matching.size,
            yearCount = byYear.size,
        ),
    )
}

/** 29 February photos surface on 28 February in a year that has no 29th, so they are never lost. */
internal fun isSameCalendarDay(taken: LocalDate, today: LocalDate): Boolean {
    if (taken.monthValue != today.monthValue) return false
    if (taken.dayOfMonth == today.dayOfMonth) return true
    return today.monthValue == 2 && today.dayOfMonth == 28 && !today.isLeapYear && taken.dayOfMonth == 29
}

internal fun <T> roundRobin(groups: List<List<T>>): List<T> {
    val out = ArrayList<T>(groups.sumOf { it.size })
    var round = 0
    while (out.size < groups.sumOf { it.size }) {
        groups.forEach { group -> group.getOrNull(round)?.let(out::add) }
        round++
    }
    return out
}

// ---- Recent ---------------------------------------------------------------------------------

internal data class RecentContent(val items: List<MediaAsset>)

/** The newest items the home screen may draw, newest first, in the app's own NEWEST order. */
internal fun recentItems(library: WidgetLibrary, limit: Int = RECENT_LIMIT): WidgetState<RecentContent> {
    if (!library.hasMediaAccess) return WidgetState.NoAccess
    if (library.pictured.isEmpty()) return emptyOrWithheld(library)
    return WidgetState.Ready(RecentContent(sortAssets(library.pictured, GallerySort.NEWEST).take(limit)))
}

// ---- Library at a glance --------------------------------------------------------------------

internal data class LibraryCounts(
    val total: Int,
    val photos: Int,
    val videos: Int,
    val favourites: Int,
    val screenshots: Int,
    /** Distinct device folders among the counted items. */
    val albums: Int,
    /**
     * Collections the Collections widget can show ([shownCollections]): the same rule, so the two
     * widgets never disagree and the difference never hints that a collection holds only hidden items.
     */
    val collections: Int,
)

/** Counts over the items the home screen may show; archived, sensitive and protected items are not counted. */
internal fun libraryCounts(library: WidgetLibrary): WidgetState<LibraryCounts> {
    if (!library.hasMediaAccess) return WidgetState.NoAccess
    val visible = library.visible
    if (visible.isEmpty()) return emptyOrWithheld(library)
    val videos = visible.count { it.isVideo }
    return WidgetState.Ready(
        LibraryCounts(
            total = visible.size,
            photos = visible.size - videos,
            videos = videos,
            favourites = visible.count { it.id in library.favoriteIds },
            screenshots = visible.count { it.isScreenshot() },
            albums = visible.mapTo(HashSet()) { folderIdentity(it).key }.size,
            collections = shownCollections(library).size,
        ),
    )
}

// ---- Collections ----------------------------------------------------------------------------

/**
 * One collection as the home screen may show it. It deliberately carries no name: a collection's
 * name is text the person typed ("Divorce evidence", someone's name), and a home screen never shows
 * typed text (docs/widgets.md, Privacy). Rows are told apart by their cover and the dates their
 * pictures span.
 */
internal data class CollectionRow(
    val id: String,
    /** The collection's members the home screen may draw, newest first. Never empty. */
    val members: List<MediaAsset>,
) {
    val cover: MediaAsset get() = members.first()

    /** Earliest and latest date taken among [members], or null when none of them is dated. */
    val takenSpan: LongRange?
        get() = members.map { it.dateTakenMillis }.filter { it > 0L }.takeIf { it.isNotEmpty() }?.let { it.min()..it.max() }
}

internal data class CollectionsContent(
    val rows: List<CollectionRow>,
    /** How many collections have at least one item the home screen may draw ([shownCollections]). */
    val shownCollections: Int,
)

/**
 * Every collection with at least one member a widget may draw, in the app's own order (by name),
 * each with those members newest first. A collection with nothing drawable is left out entirely.
 * One rule for the Collections widget and Library's collection count.
 */
internal fun shownCollections(library: WidgetLibrary): List<CollectionRow> {
    val picturedById = library.pictured.associateBy { it.id }
    return library.collections.mapNotNull { collection ->
        val members = collection.mediaIds.mapNotNull(picturedById::get)
        if (members.isEmpty()) null else CollectionRow(collection.id, sortAssets(members, GallerySort.NEWEST))
    }
}

/** Collections for the Collections widget ([shownCollections]), at most [limit] rows. */
internal fun collectionRows(library: WidgetLibrary, limit: Int = COLLECTION_ROWS): WidgetState<CollectionsContent> {
    if (!library.hasMediaAccess) return WidgetState.NoAccess
    if (library.collections.isEmpty()) return WidgetState.Empty
    val rows = shownCollections(library)
    if (rows.isEmpty()) {
        val liveIds = library.assets.asSequence().filterNot { it.isTrashed }.mapTo(HashSet()) { it.id }
        val anyLiveMember = library.collections.any { collection -> collection.mediaIds.any { it in liveIds } }
        return if (anyLiveMember) WidgetState.Withheld else WidgetState.Empty
    }
    return WidgetState.Ready(CollectionsContent(rows.take(limit), rows.size))
}

// ---- Indexing -------------------------------------------------------------------------------

internal data class IndexingCounts(
    val photosTotal: Int,
    /** Photos whose on-device recognition result is missing or out of date (see RecognitionStore.pendingAssets). */
    val photosPending: Int,
    val videosTotal: Int,
    /** Videos already through key-moment detection, successfully or not. */
    val videosScanned: Int,
) {
    val photosDone: Int get() = (photosTotal - photosPending).coerceIn(0, photosTotal)
}

/** What the background work is doing, when this process knows. Null means "not known here". */
internal sealed interface IndexingActivity {
    data object Running : IndexingActivity
    data class ReadingLibrary(val scanned: Int, val discovered: Int) : IndexingActivity
    data class Waiting(val reason: String, val checkedAtMillis: Long) : IndexingActivity
    data object Scheduled : IndexingActivity
}

internal data class IndexingContent(val counts: IndexingCounts, val activity: IndexingActivity?)

/** Photos the indexing widget counts: the visible stills. Videos have their own row. */
internal fun indexingPhotos(library: WidgetLibrary): List<MediaAsset> = library.visible.filterNot { it.isVideo }

internal fun indexingVideos(library: WidgetLibrary): List<MediaAsset> = library.visible.filter { it.isVideo }

internal fun indexingState(
    library: WidgetLibrary,
    photosPending: Int,
    videosScanned: Int,
    activity: IndexingActivity?,
): WidgetState<IndexingContent> {
    if (!library.hasMediaAccess) return WidgetState.NoAccess
    val photos = indexingPhotos(library).size
    val videos = indexingVideos(library).size
    if (photos + videos == 0) return emptyOrWithheld(library)
    return WidgetState.Ready(
        IndexingContent(
            counts = IndexingCounts(
                photosTotal = photos,
                photosPending = photosPending.coerceIn(0, photos),
                videosTotal = videos,
                videosScanned = videosScanned.coerceIn(0, videos),
            ),
            activity = activity,
        ),
    )
}

/**
 * The in-memory status this process holds, mapped to what the widget says. Both sources are
 * process-local (see BackgroundWorkStatusCenter's own KDoc), so a widget drawn in a fresh process
 * usually knows neither and says nothing rather than guessing.
 */
internal fun indexingActivity(status: BackgroundWorkStatus, scan: ScanState): IndexingActivity? = when {
    scan is ScanState.Scanning -> IndexingActivity.ReadingLibrary(scan.scanned, scan.discovered)
    status is BackgroundWorkStatus.Running -> IndexingActivity.Running
    status is BackgroundWorkStatus.Blocked -> IndexingActivity.Waiting(status.reason, status.checkedAt)
    status is BackgroundWorkStatus.Pending -> IndexingActivity.Scheduled
    else -> null
}

/** Whole percent, rounded down, so 100 means every item and never "almost". */
internal fun percentDone(done: Int, total: Int): Int =
    if (total <= 0) 0 else (done.coerceIn(0, total).toLong() * 100L / total).toInt()

// ---- Shared ---------------------------------------------------------------------------------

private fun emptyOrWithheld(library: WidgetLibrary): WidgetState<Nothing> =
    if (library.assets.any { !it.isTrashed }) WidgetState.Withheld else WidgetState.Empty

/**
 * How a recorded time is printed: the time alone on the same local day, the date as well otherwise,
 * and the year too when it is not this year.
 */
internal enum class StampStyle { TIME, DATE_AND_TIME, YEAR_DATE_AND_TIME }

/** A stamp from another day (or a clock that has since moved back) always says which day. */
internal fun stampStyle(stampMillis: Long, nowMillis: Long, zone: ZoneId): StampStyle {
    val stampDay = Instant.ofEpochMilli(stampMillis).atZone(zone).toLocalDate()
    val today = Instant.ofEpochMilli(nowMillis).atZone(zone).toLocalDate()
    return when {
        stampDay == today -> StampStyle.TIME
        stampDay.year == today.year -> StampStyle.DATE_AND_TIME
        else -> StampStyle.YEAR_DATE_AND_TIME
    }
}

/** Milliseconds from [nowMillis] to the next local midnight in [zone]; DST-safe, never zero. */
internal fun millisUntilNextLocalMidnight(nowMillis: Long, zone: ZoneId): Long {
    val now = Instant.ofEpochMilli(nowMillis).atZone(zone)
    val next = now.toLocalDate().plusDays(1).atStartOfDay(zone)
    return Duration.between(now, next).toMillis().coerceAtLeast(1L)
}

/** The most ids one tap carries into the viewer; keeps the PendingIntent small. */
internal const val WIDGET_RUN_LIMIT = 60

/** Recent's largest layout shows two rows of four. */
internal const val RECENT_LIMIT = 8

/** Collections' largest layout shows five rows. */
internal const val COLLECTION_ROWS = 5
