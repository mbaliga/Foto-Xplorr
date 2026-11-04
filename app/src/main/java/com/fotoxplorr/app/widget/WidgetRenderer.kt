package com.fotoxplorr.app.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.os.Build
import android.os.Bundle
import android.text.format.DateFormat
import android.text.format.DateUtils
import android.util.SizeF
import android.view.View
import android.widget.RemoteViews
import com.fotoxplorr.app.FotoXplorrActivity
import com.fotoxplorr.app.R
import com.fotoxplorr.app.media.MediaAsset
import java.text.NumberFormat
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.Date

/** What one widget instance draws: three sized layouts, or one state layout that fits every size. */
internal sealed interface WidgetViews {
    data class Sized(val bySize: Map<WidgetSize, RemoteViews>) : WidgetViews
    data class Single(val views: RemoteViews) : WidgetViews
}

/**
 * Turns the pure content from `WidgetContent.kt` into `RemoteViews`, per size. Every string comes
 * from resources; every picture is a bitmap from [pictures] (WidgetThumbnails), bounded by [budgetBytes];
 * every tap is an explicit, immutable PendingIntent to [FotoXplorrActivity].
 *
 * @param withImages false only for the retry after the platform refused an update for its bitmap
 *   size: the same layouts with placeholders, so the widget still says something true.
 */
internal class WidgetRenderer(
    private val context: Context,
    /** Returns the picture for an item at a long-edge size, or null; [WidgetThumbnails.load] in the app. */
    private val pictures: suspend (MediaAsset, Int) -> Bitmap?,
    private val budgetBytes: Long,
    private val nowMillis: Long,
    private val zone: ZoneId,
    private val withImages: Boolean = true,
) {
    private val packageName = context.packageName
    private val resources = context.resources
    private val numbers: NumberFormat = NumberFormat.getIntegerInstance(resources.configuration.locales[0])
    private val today: LocalDate = Instant.ofEpochMilli(nowMillis).atZone(zone).toLocalDate()

    suspend fun render(
        kind: WidgetKind,
        widgetId: Int,
        library: WidgetLibrary,
        indexing: WidgetState<IndexingContent>?,
    ): WidgetViews = when (kind) {
        WidgetKind.FAVOURITE -> favourite(widgetId, favouriteOfTheDay(library, today.toEpochDay()))
        WidgetKind.ON_THIS_DAY -> onThisDay(widgetId, onThisDay(library, today, zone))
        WidgetKind.RECENT -> recent(widgetId, recentItems(library), library.libraryReadAtMillis)
        WidgetKind.LIBRARY -> library(widgetId, libraryCounts(library), library.libraryReadAtMillis)
        WidgetKind.COLLECTIONS -> collections(widgetId, collectionRows(library))
        WidgetKind.INDEXING -> indexing(widgetId, indexing ?: WidgetState.Empty, library.libraryReadAtMillis)
    }

    /** The app failed to produce anything; says so in words. */
    fun error(widgetId: Int): WidgetViews =
        WidgetViews.Single(message(widgetId, R.string.widget_error_title, string(R.string.widget_error_message)))

    // ---- A favourite ------------------------------------------------------------------------

    private suspend fun favourite(widgetId: Int, state: WidgetState<FavouriteContent>): WidgetViews {
        val content = (state as? WidgetState.Ready)?.content ?: return stateViews(WidgetKind.FAVOURITE, widgetId, state)
        val asset = content.asset
        val bitmap = picture(asset, planDecodeEdges(listOf(HERO_EDGE_PX), budgetBytes).first())
        val open = openItem(widgetId, 0, asset, listOf(asset))
        val label = string(R.string.widget_favourite_tag)
        val title = takenDate(asset)
        val meta = plural(R.plurals.widget_favourite_count, content.shownFavourites)

        fun photo(layout: Int) = RemoteViews(packageName, layout).apply {
            bindPicture(R.id.widget_image, R.id.widget_badge, asset, bitmap)
            setOnClickPendingIntent(android.R.id.background, open)
        }
        return WidgetViews.Sized(
            mapOf(
                WidgetSize.SMALL to photo(R.layout.widget_photo_small).apply {
                    setTextViewText(R.id.widget_label, label)
                },
                WidgetSize.MEDIUM to photo(R.layout.widget_photo_wide).apply { bindTextBlock(label, title, meta) },
                WidgetSize.LARGE to photo(R.layout.widget_photo_tall).apply { bindTextBlock(label, title, meta) },
            ),
        )
    }

    // ---- On this day ------------------------------------------------------------------------

    private suspend fun onThisDay(widgetId: Int, state: WidgetState<OnThisDayContent>): WidgetViews {
        val content = (state as? WidgetState.Ready)?.content ?: return stateViews(WidgetKind.ON_THIS_DAY, widgetId, state)
        val items = content.items
        val hero = items.first()
        val strip = items.drop(1).take(SLOTS_PER_ROW)
        val edges = planDecodeEdges(listOf(HERO_EDGE_PX) + List(strip.size) { TILE_EDGE_PX }, budgetBytes)
        val bitmaps = HashMap<Long, Bitmap?>()
        bitmaps[hero.id.value] = picture(hero, edges[0])
        strip.forEachIndexed { i, asset -> bitmaps[asset.id.value] = picture(asset, edges[i + 1]) }
        // Medium shows items 0 to 3; the first three of those are hero and strip already decoded.
        items.take(SLOTS_PER_ROW).forEach { asset ->
            if (asset.id.value !in bitmaps) bitmaps[asset.id.value] = picture(asset, TILE_EDGE_PX)
        }

        val title = string(R.string.widget_on_this_day_title)
        val meta = string(
            R.string.widget_on_this_day_meta,
            dayWithoutYear(content.today),
            plural(R.plurals.widget_item_count, content.totalCount),
        )
        val openApp = openApp(widgetId)

        val small = RemoteViews(packageName, R.layout.widget_photo_small).apply {
            bindPicture(R.id.widget_image, R.id.widget_badge, hero, bitmaps[hero.id.value])
            setTextViewText(R.id.widget_label, string(R.string.widget_on_this_day_small, yearOf(hero)))
            setOnClickPendingIntent(android.R.id.background, openItem(widgetId, 0, hero, items))
        }
        val medium = RemoteViews(packageName, R.layout.widget_grid).apply {
            bindHeader(title, meta)
            setOnClickPendingIntent(android.R.id.background, openApp)
            bindSlots(widgetId, items.take(SLOTS_PER_ROW), items, bitmaps, rows = 1, caption = { yearOf(it).toString() })
        }
        val large = RemoteViews(packageName, R.layout.widget_hero_strip).apply {
            bindHeader(title, meta)
            setOnClickPendingIntent(android.R.id.background, openApp)
            bindPicture(R.id.widget_hero, R.id.widget_hero_badge, hero, bitmaps[hero.id.value])
            setTextViewText(
                R.id.widget_hero_caption,
                string(R.string.widget_on_this_day_hero, yearOf(hero), plural(R.plurals.widget_years_ago, today.year - yearOf(hero))),
            )
            setOnClickPendingIntent(R.id.widget_hero_frame, openItem(widgetId, 0, hero, items))
            if (strip.isEmpty()) {
                setViewVisibility(R.id.widget_row0, View.GONE)
            } else {
                bindSlots(widgetId, strip, items, bitmaps, rows = 1, caption = { yearOf(it).toString() }, layoutRows = 1)
            }
        }
        return WidgetViews.Sized(mapOf(WidgetSize.SMALL to small, WidgetSize.MEDIUM to medium, WidgetSize.LARGE to large))
    }

    // ---- Recent -----------------------------------------------------------------------------

    private suspend fun recent(widgetId: Int, state: WidgetState<RecentContent>, readAt: Long?): WidgetViews {
        val content = (state as? WidgetState.Ready)?.content ?: return stateViews(WidgetKind.RECENT, widgetId, state)
        val items = content.items
        val edges = planDecodeEdges(items.mapIndexed { i, _ -> if (i == 0) HERO_EDGE_PX else TILE_EDGE_PX }, budgetBytes)
        val bitmaps = HashMap<Long, Bitmap?>()
        items.forEachIndexed { i, asset -> bitmaps[asset.id.value] = picture(asset, edges[i]) }
        val latest = items.first()
        val title = string(R.string.widget_recent_title)
        val meta = asOf(readAt, short = true)
        val openApp = openApp(widgetId)

        val small = RemoteViews(packageName, R.layout.widget_photo_small).apply {
            bindPicture(R.id.widget_image, R.id.widget_badge, latest, bitmaps[latest.id.value])
            // "Latest" only as of the last read of the library, so the small layout says when too.
            setTextViewText(
                R.id.widget_label,
                string(R.string.widget_two_lines, string(R.string.widget_recent_small, compactDate(latest)), meta),
            )
            setOnClickPendingIntent(android.R.id.background, openItem(widgetId, 0, latest, items))
        }
        fun grid(rows: Int) = RemoteViews(packageName, R.layout.widget_grid).apply {
            bindHeader(title, meta)
            setOnClickPendingIntent(android.R.id.background, openApp)
            bindSlots(widgetId, items.take(rows * SLOTS_PER_ROW), items, bitmaps, rows = rows, caption = null)
        }
        return WidgetViews.Sized(mapOf(WidgetSize.SMALL to small, WidgetSize.MEDIUM to grid(1), WidgetSize.LARGE to grid(2)))
    }

    // ---- Library at a glance ----------------------------------------------------------------

    private fun library(widgetId: Int, state: WidgetState<LibraryCounts>, readAt: Long?): WidgetViews {
        val counts = (state as? WidgetState.Ready)?.content ?: return stateViews(WidgetKind.LIBRARY, widgetId, state)
        val openApp = openApp(widgetId)
        val totalLabel = string(if (counts.total == 1) R.string.widget_library_total_one else R.string.widget_library_total_other)
        val cells = listOf(
            counts.photos to R.string.widget_library_photos,
            counts.videos to R.string.widget_library_videos,
            counts.favourites to R.string.widget_library_favourites,
            counts.screenshots to R.string.widget_library_screenshots,
            counts.albums to R.string.widget_library_albums,
            counts.collections to R.string.widget_library_collections,
        )
        fun RemoteViews.bindCells(count: Int) = cells.take(count).forEachIndexed { i, (value, label) ->
            setTextViewText(CELL_VALUE[i], numbers.format(value))
            setTextViewText(CELL_LABEL[i], string(label))
        }

        val small = RemoteViews(packageName, R.layout.widget_stat_small).apply {
            setTextViewText(R.id.widget_value, numbers.format(counts.total))
            setTextViewText(R.id.widget_label, totalLabel)
            setOnClickPendingIntent(android.R.id.background, openApp)
        }
        // Medium has room for one line of header: it gives it to the "As of" time, since photos and
        // videos below already add up to the total.
        val medium = RemoteViews(packageName, R.layout.widget_stat_medium).apply {
            bindHeader(string(R.string.widget_library_title), asOf(readAt, short = true))
            bindCells(3)
            setOnClickPendingIntent(android.R.id.background, openApp)
        }
        val large = RemoteViews(packageName, R.layout.widget_stat_large).apply {
            bindHeader(string(R.string.widget_library_title), plural(R.plurals.widget_item_count, counts.total))
            bindCells(6)
            setTextViewText(R.id.widget_footer, string(R.string.widget_library_footer, asOf(readAt)))
            setOnClickPendingIntent(android.R.id.background, openApp)
        }
        return WidgetViews.Sized(mapOf(WidgetSize.SMALL to small, WidgetSize.MEDIUM to medium, WidgetSize.LARGE to large))
    }

    // ---- Collections ------------------------------------------------------------------------

    private suspend fun collections(widgetId: Int, state: WidgetState<CollectionsContent>): WidgetViews {
        val content = (state as? WidgetState.Ready)?.content ?: return stateViews(WidgetKind.COLLECTIONS, widgetId, state)
        val rows = content.rows
        val edges = planDecodeEdges(rows.mapIndexed { i, _ -> if (i == 0) HERO_EDGE_PX else ROW_EDGE_PX }, budgetBytes)
        val covers = rows.mapIndexed { i, row -> picture(row.cover, edges[i]) }
        val openApp = openApp(widgetId)
        val first = rows.first()

        // No row carries the collection's name (see CollectionRow): each is told apart by its cover
        // and the dates its pictures span.
        val small = RemoteViews(packageName, R.layout.widget_photo_small).apply {
            bindPicture(R.id.widget_image, R.id.widget_badge, first.cover, covers.first())
            val firstCount = plural(R.plurals.widget_item_count, first.members.size)
            setTextViewText(
                R.id.widget_label,
                first.takenSpan?.let { string(R.string.widget_collection_small, span(it), firstCount) } ?: firstCount,
            )
            setOnClickPendingIntent(android.R.id.background, openItem(widgetId, 0, first.cover, first.members))
        }
        fun list(count: Int) = RemoteViews(packageName, R.layout.widget_list).apply {
            bindHeader(string(R.string.widget_collections_title), plural(R.plurals.widget_collection_count, content.shownCollections))
            setOnClickPendingIntent(android.R.id.background, openApp)
            for (i in ROW_ROOT.indices) {
                val row = rows.getOrNull(i)
                if (row == null || i >= count) {
                    setViewVisibility(ROW_ROOT[i], View.GONE)
                    continue
                }
                val countText = plural(R.plurals.widget_item_count, row.members.size)
                val spanText = row.takenSpan?.let(::span)
                setViewVisibility(ROW_ROOT[i], View.VISIBLE)
                bindPicture(ROW_IMAGE[i], null, row.cover, covers[i])
                setTextViewText(ROW_TITLE[i], spanText ?: string(R.string.widget_collection_undated))
                setTextViewText(ROW_META[i], countText)
                setContentDescription(
                    ROW_ROOT[i],
                    if (spanText != null) {
                        string(R.string.widget_cd_collection, spanText, countText)
                    } else {
                        string(R.string.widget_cd_collection_undated, countText)
                    },
                )
                setOnClickPendingIntent(ROW_ROOT[i], openItem(widgetId, i, row.cover, row.members))
            }
        }
        return WidgetViews.Sized(mapOf(WidgetSize.SMALL to small, WidgetSize.MEDIUM to list(3), WidgetSize.LARGE to list(5)))
    }

    // ---- Indexing ---------------------------------------------------------------------------

    private fun indexing(widgetId: Int, state: WidgetState<IndexingContent>, readAt: Long?): WidgetViews {
        val content = (state as? WidgetState.Ready)?.content ?: return stateViews(WidgetKind.INDEXING, widgetId, state)
        val counts = content.counts
        val openApp = openApp(widgetId)
        val photosPercent = percentDone(counts.photosDone, counts.photosTotal)
        val videosPercent = percentDone(counts.videosScanned, counts.videosTotal)

        // The small layout leads with photos, or with videos in a library that has no stills.
        val smallIsPhotos = counts.photosTotal > 0
        val small = RemoteViews(packageName, R.layout.widget_progress_small).apply {
            val percent = if (smallIsPhotos) photosPercent else videosPercent
            val complete = if (smallIsPhotos) counts.photosDone == counts.photosTotal else counts.videosScanned == counts.videosTotal
            setTextViewText(R.id.widget_value, string(R.string.widget_indexing_percent, percent))
            setTextViewText(
                R.id.widget_label,
                string(
                    when {
                        smallIsPhotos && complete -> R.string.widget_indexing_small_photos_done
                        smallIsPhotos -> R.string.widget_indexing_small_photos
                        complete -> R.string.widget_indexing_small_videos_done
                        else -> R.string.widget_indexing_small_videos
                    },
                ),
            )
            setProgressBar(R.id.widget_bar, 100, percent, false)
            setOnClickPendingIntent(android.R.id.background, openApp)
        }
        fun RemoteViews.bindSections() {
            bindHeader(string(R.string.widget_indexing_title), asOf(readAt, short = true))
            bindSection(R.id.widget_photos_section, R.id.widget_photos_label, R.id.widget_photos_value, R.id.widget_photos_bar,
                R.string.widget_indexing_photos, counts.photosDone, counts.photosTotal, photosPercent)
            bindSection(R.id.widget_videos_section, R.id.widget_videos_label, R.id.widget_videos_value, R.id.widget_videos_bar,
                R.string.widget_indexing_videos, counts.videosScanned, counts.videosTotal, videosPercent)
            setOnClickPendingIntent(android.R.id.background, openApp)
        }
        val medium = RemoteViews(packageName, R.layout.widget_progress_medium).apply { bindSections() }
        val large = RemoteViews(packageName, R.layout.widget_progress_large).apply {
            bindSections()
            val status = content.activity?.let(::describeActivity)
            if (status == null) {
                setViewVisibility(R.id.widget_status, View.GONE)
            } else {
                setViewVisibility(R.id.widget_status, View.VISIBLE)
                setTextViewText(R.id.widget_status, status)
            }
            setTextViewText(R.id.widget_footer, string(R.string.widget_indexing_footer))
        }
        return WidgetViews.Sized(mapOf(WidgetSize.SMALL to small, WidgetSize.MEDIUM to medium, WidgetSize.LARGE to large))
    }

    private fun RemoteViews.bindSection(
        sectionId: Int, labelId: Int, valueId: Int, barId: Int,
        labelRes: Int, done: Int, total: Int, percent: Int,
    ) {
        if (total <= 0) {
            setViewVisibility(sectionId, View.GONE)
            return
        }
        setViewVisibility(sectionId, View.VISIBLE)
        setTextViewText(labelId, string(labelRes))
        val ofText = string(R.string.widget_indexing_of, numbers.format(done), numbers.format(total))
        setTextViewText(valueId, if (done >= total) string(R.string.widget_indexing_complete, ofText) else ofText)
        setProgressBar(barId, 100, percent, false)
    }

    /** Each status is a shape and a word, never a colour: ● now, ◐ held back, ○ waiting its turn. */
    private fun describeActivity(activity: IndexingActivity): String = when (activity) {
        IndexingActivity.Running -> string(R.string.widget_indexing_running)
        is IndexingActivity.ReadingLibrary -> string(
            R.string.widget_indexing_reading,
            numbers.format(activity.scanned),
            numbers.format(activity.discovered),
        )
        is IndexingActivity.Waiting -> string(R.string.widget_indexing_waiting, activity.reason, stamp(activity.checkedAtMillis))
        IndexingActivity.Scheduled -> string(R.string.widget_indexing_scheduled)
    }

    // ---- States -----------------------------------------------------------------------------

    private fun stateViews(kind: WidgetKind, widgetId: Int, state: WidgetState<*>): WidgetViews = WidgetViews.Single(
        when (state) {
            WidgetState.NoAccess -> message(widgetId, R.string.widget_no_access_title, string(R.string.widget_no_access_message))
            // Withheld deliberately reads exactly like Empty; see WidgetState.Withheld.
            WidgetState.Empty, WidgetState.Withheld, is WidgetState.Ready -> when (kind) {
                WidgetKind.FAVOURITE -> message(widgetId, R.string.widget_favourite_empty_title, string(R.string.widget_favourite_empty_message))
                WidgetKind.ON_THIS_DAY -> message(
                    widgetId,
                    R.string.widget_on_this_day_empty_title,
                    string(R.string.widget_on_this_day_empty_message, dayWithoutYear(today)),
                )
                WidgetKind.RECENT -> message(widgetId, R.string.widget_recent_empty_title, string(R.string.widget_recent_empty_message))
                WidgetKind.LIBRARY -> message(widgetId, R.string.widget_library_empty_title, string(R.string.widget_library_empty_message))
                WidgetKind.COLLECTIONS -> message(widgetId, R.string.widget_collections_empty_title, string(R.string.widget_collections_empty_message))
                WidgetKind.INDEXING -> message(widgetId, R.string.widget_indexing_empty_title, string(R.string.widget_indexing_empty_message))
            }
        },
    )

    private fun message(widgetId: Int, titleRes: Int, body: String): RemoteViews =
        RemoteViews(packageName, R.layout.widget_message).apply {
            setTextViewText(R.id.widget_title, string(titleRes))
            setTextViewText(R.id.widget_message, body)
            setOnClickPendingIntent(android.R.id.background, openApp(widgetId))
        }

    // ---- Binding helpers --------------------------------------------------------------------

    private suspend fun picture(asset: MediaAsset, edgePx: Int): Bitmap? =
        if (withImages) pictures(asset, edgePx) else null

    private fun RemoteViews.bindHeader(title: String, meta: String) {
        setTextViewText(R.id.widget_title, title)
        setTextViewText(R.id.widget_meta, meta)
    }

    private fun RemoteViews.bindTextBlock(label: String, title: String, meta: String) {
        setTextViewText(R.id.widget_label, label)
        setTextViewText(R.id.widget_title, title)
        setTextViewText(R.id.widget_meta, meta)
    }

    private fun RemoteViews.bindPicture(imageId: Int, badgeId: Int?, asset: MediaAsset, bitmap: Bitmap?) {
        if (bitmap != null) setImageViewBitmap(imageId, bitmap) else setImageViewResource(imageId, R.drawable.widget_tile_placeholder)
        setContentDescription(imageId, describe(asset))
        if (badgeId != null) setViewVisibility(badgeId, if (asset.isVideo) View.VISIBLE else View.GONE)
    }

    /**
     * Fills up to [rows] rows of four tiles from [shown]. A row with nothing in it is removed so the
     * rows that remain grow into its space; an unused tile in a partly filled row keeps its place.
     * [layoutRows] is how many slot rows the inflated layout has (the hero strip has one).
     */
    private fun RemoteViews.bindSlots(
        widgetId: Int,
        shown: List<MediaAsset>,
        run: List<MediaAsset>,
        bitmaps: Map<Long, Bitmap?>,
        rows: Int,
        caption: ((MediaAsset) -> String)?,
        layoutRows: Int = MAX_ROWS,
    ) {
        for (row in 0 until layoutRows) {
            val used = row < rows && shown.size > row * SLOTS_PER_ROW
            setViewVisibility(ROW_OF_SLOTS[row], if (used) View.VISIBLE else View.GONE)
        }
        for (i in 0 until rows * SLOTS_PER_ROW) {
            val asset = shown.getOrNull(i)
            if (asset == null) {
                setViewVisibility(SLOT_FRAME[i], View.INVISIBLE)
                continue
            }
            setViewVisibility(SLOT_FRAME[i], View.VISIBLE)
            bindPicture(SLOT_IMAGE[i], SLOT_BADGE[i], asset, bitmaps[asset.id.value])
            if (caption != null) {
                setTextViewText(SLOT_CAPTION[i], caption(asset))
                setViewVisibility(SLOT_CAPTION[i], View.VISIBLE)
            }
            setOnClickPendingIntent(SLOT_FRAME[i], openItem(widgetId, run.indexOf(asset).coerceAtLeast(0), asset, run))
        }
    }

    // ---- Intents ----------------------------------------------------------------------------

    /** Opens the app the way its launcher icon does: an existing task comes forward as it was. */
    private fun openApp(widgetId: Int): PendingIntent = PendingIntent.getActivity(
        context,
        widgetRequestCode(widgetId, slot = 0),
        Intent(Intent.ACTION_MAIN)
            .addCategory(Intent.CATEGORY_LAUNCHER)
            .setClass(context, FotoXplorrActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    /** Opens [asset] in the viewer, able to page through [run]; the Activity re-checks both. */
    private fun openItem(widgetId: Int, index: Int, asset: MediaAsset, run: List<MediaAsset>): PendingIntent =
        PendingIntent.getActivity(
            context,
            widgetRequestCode(widgetId, slot = index + 1),
            Intent(context, FotoXplorrActivity::class.java)
                .setAction(WidgetLaunch.ACTION_OPEN_FROM_WIDGET)
                .putExtra(WidgetLaunch.EXTRA_MEDIA_ID, asset.id.value)
                .putExtra(WidgetLaunch.EXTRA_RUN_IDS, run.take(WIDGET_RUN_LIMIT).map { it.id.value }.toLongArray())
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

    // ---- Words ------------------------------------------------------------------------------

    private fun string(id: Int, vararg args: Any): String = resources.getString(id, *args)

    private fun plural(id: Int, count: Int): String = resources.getQuantityString(id, count, numbers.format(count))

    private fun takenDate(asset: MediaAsset): String =
        if (asset.dateTakenMillis > 0L) {
            DateUtils.formatDateTime(context, asset.dateTakenMillis, DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_SHOW_YEAR)
        } else {
            string(R.string.widget_date_unknown)
        }

    private fun describe(asset: MediaAsset): String = when {
        asset.dateTakenMillis <= 0L && asset.isVideo -> string(R.string.widget_cd_video_undated)
        asset.dateTakenMillis <= 0L -> string(R.string.widget_cd_photo_undated)
        asset.isVideo -> string(R.string.widget_cd_video, takenDate(asset))
        else -> string(R.string.widget_cd_photo, takenDate(asset))
    }

    private fun yearOf(asset: MediaAsset): Int = Instant.ofEpochMilli(asset.dateTakenMillis).atZone(zone).year

    private fun dayWithoutYear(date: LocalDate): String = DateUtils.formatDateTime(
        context,
        date.atStartOfDay(zone).toInstant().toEpochMilli(),
        DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_NO_YEAR,
    )

    private fun timeOf(millis: Long): String = DateFormat.getTimeFormat(context).format(Date(millis))

    /** A recorded moment: its time on the same day, with the date (and year) otherwise ([stampStyle]). */
    private fun stamp(millis: Long): String = when (stampStyle(millis, nowMillis, zone)) {
        StampStyle.TIME -> timeOf(millis)
        StampStyle.DATE_AND_TIME -> DateUtils.formatDateTime(
            context,
            millis,
            DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_NO_YEAR or DateUtils.FORMAT_SHOW_TIME or DateUtils.FORMAT_ABBREV_MONTH,
        )
        StampStyle.YEAR_DATE_AND_TIME -> DateUtils.formatDateTime(
            context,
            millis,
            DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_SHOW_YEAR or DateUtils.FORMAT_SHOW_TIME or DateUtils.FORMAT_ABBREV_MONTH,
        )
    }

    /** A header's room: the time on the same day, the date alone otherwise ("Nov 3", "Nov 3, 2025"). */
    private fun shortStamp(millis: Long): String = when (stampStyle(millis, nowMillis, zone)) {
        StampStyle.TIME -> timeOf(millis)
        StampStyle.DATE_AND_TIME -> DateUtils.formatDateTime(
            context,
            millis,
            DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_NO_YEAR or DateUtils.FORMAT_ABBREV_MONTH,
        )
        StampStyle.YEAR_DATE_AND_TIME -> DateUtils.formatDateTime(
            context,
            millis,
            DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_SHOW_YEAR or DateUtils.FORMAT_ABBREV_MONTH,
        )
    }

    /**
     * When Fotoz last finished reading the library ([LibraryReadStamp]), not when this was drawn: a
     * widget redrawn at midnight from a catalogue read days ago must say days ago. [short] for a
     * header or a small label; footers have room for the date and the time.
     */
    private fun asOf(readAt: Long?, short: Boolean = false): String = when {
        readAt == null -> string(R.string.widget_as_of_unknown)
        short -> string(R.string.widget_as_of, shortStamp(readAt))
        else -> string(R.string.widget_as_of, stamp(readAt))
    }

    /** A photo's date in little room: "Oct 29", with the year only when it is not this year. */
    private fun compactDate(asset: MediaAsset): String {
        if (asset.dateTakenMillis <= 0L) return string(R.string.widget_date_unknown)
        val sameYear = Instant.ofEpochMilli(asset.dateTakenMillis).atZone(zone).year == today.year
        return DateUtils.formatDateTime(
            context,
            asset.dateTakenMillis,
            DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_ABBREV_MONTH or
                (if (sameYear) DateUtils.FORMAT_NO_YEAR else DateUtils.FORMAT_SHOW_YEAR),
        )
    }

    /** The months a collection's pictures span, e.g. "Oct – Nov 2026"; one month reads as one. */
    private fun span(range: LongRange): String = DateUtils.formatDateRange(
        context,
        range.first,
        range.last,
        DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_NO_MONTH_DAY or DateUtils.FORMAT_ABBREV_MONTH or DateUtils.FORMAT_SHOW_YEAR,
    )

    private companion object {
        const val SLOTS_PER_ROW = 4
        const val MAX_ROWS = 2
        val ROW_OF_SLOTS = intArrayOf(R.id.widget_row0, R.id.widget_row1)
        val SLOT_FRAME = intArrayOf(
            R.id.widget_slot0_frame, R.id.widget_slot1_frame, R.id.widget_slot2_frame, R.id.widget_slot3_frame,
            R.id.widget_slot4_frame, R.id.widget_slot5_frame, R.id.widget_slot6_frame, R.id.widget_slot7_frame,
        )
        val SLOT_IMAGE = intArrayOf(
            R.id.widget_slot0, R.id.widget_slot1, R.id.widget_slot2, R.id.widget_slot3,
            R.id.widget_slot4, R.id.widget_slot5, R.id.widget_slot6, R.id.widget_slot7,
        )
        val SLOT_CAPTION = intArrayOf(
            R.id.widget_slot0_caption, R.id.widget_slot1_caption, R.id.widget_slot2_caption, R.id.widget_slot3_caption,
            R.id.widget_slot4_caption, R.id.widget_slot5_caption, R.id.widget_slot6_caption, R.id.widget_slot7_caption,
        )
        val SLOT_BADGE = intArrayOf(
            R.id.widget_slot0_badge, R.id.widget_slot1_badge, R.id.widget_slot2_badge, R.id.widget_slot3_badge,
            R.id.widget_slot4_badge, R.id.widget_slot5_badge, R.id.widget_slot6_badge, R.id.widget_slot7_badge,
        )
        val CELL_VALUE = intArrayOf(
            R.id.widget_cell0_value, R.id.widget_cell1_value, R.id.widget_cell2_value,
            R.id.widget_cell3_value, R.id.widget_cell4_value, R.id.widget_cell5_value,
        )
        val CELL_LABEL = intArrayOf(
            R.id.widget_cell0_label, R.id.widget_cell1_label, R.id.widget_cell2_label,
            R.id.widget_cell3_label, R.id.widget_cell4_label, R.id.widget_cell5_label,
        )
        val ROW_ROOT = intArrayOf(R.id.widget_row0, R.id.widget_row1, R.id.widget_row2, R.id.widget_row3, R.id.widget_row4)
        val ROW_IMAGE = intArrayOf(
            R.id.widget_row0_image, R.id.widget_row1_image, R.id.widget_row2_image, R.id.widget_row3_image, R.id.widget_row4_image,
        )
        val ROW_TITLE = intArrayOf(
            R.id.widget_row0_title, R.id.widget_row1_title, R.id.widget_row2_title, R.id.widget_row3_title, R.id.widget_row4_title,
        )
        val ROW_META = intArrayOf(
            R.id.widget_row0_meta, R.id.widget_row1_meta, R.id.widget_row2_meta, R.id.widget_row3_meta, R.id.widget_row4_meta,
        )
    }
}

/**
 * Turns [WidgetViews] into what AppWidgetManager takes. API 31+: one `RemoteViews` holding all three
 * layouts keyed by their minimum sizes, so the launcher switches layout on resize without waking the
 * app. Below 31: the layouts the reported options call for in landscape and in portrait, combined
 * with the platform's own `RemoteViews(landscape, portrait)`, so a rotation needs no redraw either;
 * a resize arrives as an options change and is drawn again (FotozWidgetProvider).
 *
 * [fontScale] is the system's, which the launcher draws with too: the minimums grow with it (see
 * [LayoutMinimum]). A change of font size takes effect at the next redraw.
 */
internal fun WidgetViews.toRemoteViews(kind: WidgetKind, options: Bundle, fontScale: Float = 1f): RemoteViews = when (this) {
    is WidgetViews.Single -> views
    is WidgetViews.Sized -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        RemoteViews(
            bySize.mapKeys { (size, _) ->
                val (width, height) = kind.thresholds.minimumFor(size, fontScale)
                SizeF(width, height)
            },
        )
    } else {
        fun forOrientation(portrait: Boolean): RemoteViews = bySize.getValue(
            sizeFromOptions(
                minWidthDp = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH),
                minHeightDp = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT),
                maxWidthDp = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH),
                maxHeightDp = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT),
                portrait = portrait,
                thresholds = kind.thresholds,
                fontScale = fontScale,
            ),
        )
        val landscape = forOrientation(portrait = false)
        val portrait = forOrientation(portrait = true)
        if (landscape === portrait) portrait else RemoteViews(landscape, portrait)
    }
}
