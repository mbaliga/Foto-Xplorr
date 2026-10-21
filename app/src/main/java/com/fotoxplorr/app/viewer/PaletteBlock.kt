package com.fotoxplorr.app.viewer

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.fotoxplorr.app.media.MediaAsset
import com.fotoxplorr.app.palette.PaletteMemo
import com.fotoxplorr.app.palette.PaletteSampling
import com.fotoxplorr.app.palette.PaletteState
import com.fotoxplorr.app.palette.PaletteSwatch
import com.fotoxplorr.app.palette.PaletteSwatchRow
import com.fotoxplorr.app.ui.HyleGrotesk
import com.fotoxplorr.core.search.ColourSearch
import kotlinx.coroutines.delay

/**
 * The photo's colours, always visible: a compact strip directly under the camera card, each
 * segment's width its share of the photo, hex values under it, every segment a button that
 * searches the library for that colour (`colour:#rrggbb`, through the same [onSearchLibrary] the
 * Search pill uses) and a long press on it copies the hex.
 *
 * This used to be the last of nine rows inside the collapsed Information block, a screen below
 * the fold, and so was never seen (#11). It is a block of its own now and no longer shown there.
 *
 * Sources, in order. [indexed], the recognition index's stored palette; then [PaletteMemo], what
 * this process already read; and only for a photo with neither, one read of its own off the main
 * thread ([PaletteSampling.readState]: a decode for a still, one frame for a video). Whatever is
 * shown also goes into the memo, so the expanded map can colour this photo's dot without decoding
 * anything. The four answers are drawn apart -- see [PaletteState] -- and the block is the SAME
 * height in all of them, so a palette landing late never moves the map plate under it: the
 * plate's pin is the flight's landing target (PhotoToPinFlight) and must not shift mid-flight.
 */
@Composable
internal fun PaletteBlock(
    asset: MediaAsset,
    indexed: List<PaletteSwatch>,
    onSearchLibrary: ((String) -> Unit)?,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    var state by remember(asset.id, indexed) {
        mutableStateOf<PaletteState>(
            (indexed.ifEmpty { PaletteMemo.get(asset.id).orEmpty() })
                .takeIf { it.isNotEmpty() }?.let(PaletteState::Ready) ?: PaletteState.Loading,
        )
    }
    LaunchedEffect(asset.id, asset.contentUriString, indexed) {
        if (indexed.isNotEmpty()) {
            PaletteMemo.put(asset.id, indexed)
        } else if (state !is PaletteState.Ready) {
            val read = PaletteSampling.readState(context, asset)
            if (read is PaletteState.Ready) PaletteMemo.put(asset.id, read.swatches)
            state = read
        }
    }
    // "Copied #rrggbb" in place of the hex line for a moment; per photo, so a swipe cannot carry
    // it onto another photo's strip.
    var copied by remember(asset.id) { mutableStateOf<String?>(null) }
    LaunchedEffect(copied) {
        if (copied != null) {
            delay(COPIED_NOTICE_MS)
            copied = null
        }
    }

    Box(modifier.fillMaxWidth().height(PALETTE_BLOCK_HEIGHT), contentAlignment = Alignment.CenterStart) {
        when (val current = state) {
            PaletteState.Loading -> Box(
                Modifier
                    .fillMaxWidth()
                    .height(SKELETON_HEIGHT)
                    .clip(RoundedCornerShape(SKELETON_HEIGHT / 3))
                    .background(CARD_HEADER_BACKGROUND)
                    .semantics { contentDescription = "Reading colours" },
            )
            is PaletteState.Ready -> PaletteSwatchRow(
                swatches = current.swatches,
                // Same path as the Search pill: the activity closes the viewer and seeds the
                // grid's search field. With no onSearchLibrary (no catalogue actions) the bar
                // stays visible and a tap goes nowhere.
                onSwatchTap = { argb -> onSearchLibrary?.invoke(ColourSearch.colourQuery(argb)) },
                onSwatchLongPress = { argb ->
                    val hex = PaletteSwatch(argb, 0f).hex
                    clipboard.setText(AnnotatedString(hex))
                    copied = "Copied $hex"
                },
                caption = copied,
                labelColor = SECONDARY_TEXT,
            )
            // A failed read says so; it is not an empty palette and not a dash (TRAPS 32).
            PaletteState.Failed -> PaletteMessage("Couldn't read colours")
            PaletteState.None -> PaletteMessage("No visible colours")
        }
    }
}

@Composable
private fun PaletteMessage(text: String) {
    Text(
        text = text,
        color = SECONDARY_TEXT,
        style = TextStyle(fontFamily = HyleGrotesk, fontSize = 13.sp, fontWeight = FontWeight.Medium),
    )
}

/** The swatch row's own 48 dp touch-target minimum, which is what its strip and hex line sit in. */
private val PALETTE_BLOCK_HEIGHT = 48.dp
private val SKELETON_HEIGHT = 14.dp
private const val COPIED_NOTICE_MS = 1_800L
