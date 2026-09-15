package com.fotoxplorr.app.gallery

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.fotoxplorr.app.ScanState
import com.fotoxplorr.app.media.MediaAsset
import com.fotoxplorr.app.media.MediaId
import com.fotoxplorr.app.ui.RoomEyebrow
import com.fotoxplorr.app.ui.RoomRule
import com.fotoxplorr.app.ui.RoomStyle
import com.fotoxplorr.app.viewer.DetailFormatting
import java.util.Calendar

/**
 * The gallery's BOTTOM room: what the view you are looking at actually *is*.
 *
 * Exactly where the viewer puts one photo's EXIF, because the model is the edge, not the content
 * (owner, 2026-08-18: *"the info would be different as well as actions, but the model needs to
 * remain the same"*). Drag up from an open photo and you get that photo's facts; drag up from a
 * grid and you get the grid's.
 *
 * The numbers are counted from the assets actually on screen rather than from the whole library,
 * so the room describes the destination, album or search result you are in — a "Videos" that said
 * "22,310 items" would be describing something else.
 */
@Composable
fun GalleryInfoRoom(
    title: String,
    assets: List<MediaAsset>,
    state: GalleryUiState,
    modifier: Modifier = Modifier,
) {
    // One pass over the list rather than six. At 22,000 assets the difference between counting
    // once and calling count{} per statistic is the difference between opening instantly and
    // visibly hitching as the room reveals.
    val facts = remember(assets, state.favoriteIds) { summarise(assets, state.favoriteIds) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black)
            // The shell parks the card upward for a bottom room and insets this one at its top,
            // so the system bar to clear here is the navigation bar.
            .navigationBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(
                start = RoomStyle.GutterStart,
                end = RoomStyle.GutterEnd,
                top = 26.dp,
                bottom = 26.dp,
            ),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        RoomEyebrow("THIS VIEW")
        Text(
            text = title,
            color = RoomStyle.Ink,
            style = RoomStyle.Title,
            modifier = Modifier.padding(top = 4.dp, bottom = 10.dp),
        )

        InfoRow("Photos", facts.photos.toString())
        InfoRow("Videos", facts.videos.toString())
        if (facts.favourites > 0) InfoRow("Favourites", facts.favourites.toString())
        InfoRow("Size on disk", DetailFormatting.byteLine(facts.totalBytes))
        facts.span?.let { InfoRow("Spans", it) }
        if (facts.albums > 0) InfoRow("Folders", facts.albums.toString())

        RoomRule(Modifier.padding(vertical = 16.dp))
        RoomEyebrow("LIBRARY")

        InfoRow("Everything", "${state.assets.size}")
        InfoRow(
            "Scan",
            when (val scan = state.scanState) {
                is ScanState.Scanning -> "reading ${scan.scanned} of ${scan.discovered}"
                is ScanState.Error -> "failed"
                else -> "up to date"
            },
        )
        val recognition = state.recognitionProgress
        if (recognition.running) {
            InfoRow("Recognising", "${recognition.completed} of ${recognition.total}")
        }

        Text(
            text = "Counted from what is on screen, so it follows whichever destination, album " +
                "or search you are in.",
            color = RoomStyle.InkFaint,
            style = RoomStyle.Caption,
            modifier = Modifier.padding(top = 16.dp),
        )
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 7.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(
            text = label,
            color = RoomStyle.InkFaint,
            style = RoomStyle.Caption,
            modifier = Modifier.weight(0.4f),
        )
        Text(
            text = value,
            color = RoomStyle.Ink,
            style = RoomStyle.Row,
            modifier = Modifier.weight(0.6f),
        )
    }
}

/** What a room can say about a list of assets. Pure, so the arithmetic is testable. */
internal data class ViewFacts(
    val photos: Int,
    val videos: Int,
    val favourites: Int,
    val totalBytes: Long,
    val albums: Int,
    /** Human-readable range of capture years, or null when nothing carries a usable date. */
    val span: String?,
)

/**
 * Count a view in one pass.
 *
 * Trashed items are excluded because they are not part of what you are looking at -- except in the
 * trash itself, where every item is trashed and excluding them would report an empty room. So the
 * rule is "exclude trashed unless that would leave nothing", which is what a reader means by "how
 * many are here".
 */
internal fun summarise(assets: List<MediaAsset>, favouriteIds: Set<MediaId> = emptySet()): ViewFacts {
    val visible = assets.filterNot { it.isTrashed }.ifEmpty { assets }
    var photos = 0
    var videos = 0
    var favourites = 0
    var bytes = 0L
    val albums = HashSet<String>()
    var earliest = Long.MAX_VALUE
    var latest = Long.MIN_VALUE

    visible.forEach { asset ->
        if (asset.isVideo) videos++ else photos++
        if (asset.id in favouriteIds) favourites++
        bytes += asset.sizeBytes
        asset.bucketName?.takeIf(String::isNotBlank)?.let(albums::add)
        val millis = asset.dateTakenMillis.takeIf { it > 0L }
            ?: (asset.dateModifiedSeconds * 1_000L).takeIf { it > 0L }
        if (millis != null) {
            if (millis < earliest) earliest = millis
            if (millis > latest) latest = millis
        }
    }

    return ViewFacts(
        photos = photos,
        videos = videos,
        favourites = favourites,
        totalBytes = bytes,
        albums = albums.size,
        span = spanLabel(earliest, latest),
    )
}

/** `2019 – 2026`, or a single year, or null when no asset carried a date at all. */
internal fun spanLabel(earliestMillis: Long, latestMillis: Long): String? {
    if (earliestMillis == Long.MAX_VALUE || latestMillis == Long.MIN_VALUE) return null
    val calendar = Calendar.getInstance()
    calendar.timeInMillis = earliestMillis
    val from = calendar.get(Calendar.YEAR)
    calendar.timeInMillis = latestMillis
    val to = calendar.get(Calendar.YEAR)
    return if (from == to) "$from" else "$from – $to"
}
