package com.fotoxplorr.app.viewer.colour

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.fotoxplorr.app.editor.ToneHistogram
import com.fotoxplorr.app.media.DecodeLimits
import com.fotoxplorr.app.media.MediaAsset
import com.fotoxplorr.app.media.decodeUpright
import com.fotoxplorr.app.ui.RoomEyebrow
import com.fotoxplorr.app.ui.RoomRow
import com.fotoxplorr.app.ui.RoomToggle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** The small upright copy of the photo the overlays are measured on, and what was measured. */
internal class ProAnalysis(
    val width: Int,
    val height: Int,
    val histogram: ToneHistogram,
    val clipping: PixelAnalysis.Clipping,
    val clippingMask: ImageBitmap,
    val peakingMask: ImageBitmap,
)

private const val ANALYSIS_EDGE = 640

private fun maskBitmap(argb: IntArray, w: Int, h: Int): ImageBitmap =
    Bitmap.createBitmap(argb, w, h, Bitmap.Config.ARGB_8888).asImageBitmap()

/**
 * Decodes the photo small (upright, TRAPS #34) and measures it, off the main thread, only while an
 * overlay that needs pixels is on. Null until it lands and for a photo that will not decode.
 */
@Composable
internal fun rememberProAnalysis(asset: MediaAsset, wanted: Boolean): ProAnalysis? {
    val context = LocalContext.current
    var analysis by remember(asset.id, asset.dateModifiedSeconds) { mutableStateOf<ProAnalysis?>(null) }
    LaunchedEffect(asset.id, asset.dateModifiedSeconds, wanted) {
        if (!wanted || asset.isVideo || analysis != null) return@LaunchedEffect
        analysis = withContext(Dispatchers.Default) {
            val decoded = decodeUpright(context, asset.contentUri, DecodeLimits(ANALYSIS_EDGE, ANALYSIS_EDGE.toLong() * ANALYSIS_EDGE))
                ?: return@withContext null
            val bmp = decoded.bitmap
            val w = bmp.width
            val h = bmp.height
            val px = IntArray(w * h).also { bmp.getPixels(it, 0, w, 0, 0, w, h) }
            ProAnalysis(
                width = w, height = h,
                histogram = ToneHistogram.of(px),
                clipping = PixelAnalysis.clipping(px),
                clippingMask = maskBitmap(PixelAnalysis.clippingMask(px), w, h),
                peakingMask = maskBitmap(PixelAnalysis.peakingMask(px, w, h), w, h),
            )
        }
    }
    return analysis
}

/**
 * The clipping and focus-peaking masks, drawn INSIDE the viewer's zoom layer so they track the
 * photo exactly like the text layer does. The masks are a small copy stretched over the fitted
 * picture, so at deep zoom they are soft by design: they say where, not how finely.
 */
@Composable
internal fun ProMaskLayer(choice: ProOverlayChoice, analysis: ProAnalysis?, modifier: Modifier = Modifier) {
    if (analysis == null || !(choice.clipping || choice.peaking)) return
    Canvas(modifier = modifier) {
        if (choice.clipping) drawFitted(analysis.clippingMask, analysis)
        if (choice.peaking) drawFitted(analysis.peakingMask, analysis)
    }
}

private fun DrawScope.drawFitted(mask: ImageBitmap, a: ProAnalysis) {
    val aspect = a.width.toFloat() / a.height
    val fittedW = if (aspect > size.width / size.height) size.width else size.height * aspect
    val fittedH = fittedW / aspect
    drawImage(
        image = mask,
        dstOffset = IntOffset(((size.width - fittedW) / 2f).toInt(), ((size.height - fittedH) / 2f).toInt()),
        dstSize = IntSize(fittedW.toInt().coerceAtLeast(1), fittedH.toInt().coerceAtLeast(1)),
    )
}

/**
 * The chrome-level card: the info rows and/or the histogram, bottom-aligned by the caller, not
 * zoomed. Drawn over the photo on a scrim, white text, one line per fact.
 */
@Composable
internal fun ProOverlayCard(
    choice: ProOverlayChoice,
    lines: List<ProInfoLine>,
    analysis: ProAnalysis?,
    modifier: Modifier = Modifier,
) {
    val showInfo = choice.info && lines.isNotEmpty()
    val showHist = choice.histogram && analysis != null
    if (!showInfo && !showHist) return
    Column(
        modifier = modifier
            .background(Color(0xCC000000), RoundedCornerShape(16.dp))
            .padding(horizontal = 14.dp, vertical = 12.dp)
            .testTag(PRO_CARD_TAG),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        if (showHist && analysis != null) {
            HistogramStrip(analysis.histogram)
            if (choice.clipping && analysis.clipping.any) {
                Text(
                    text = "Highlights ${percent(analysis.clipping.highlights)}  Shadows ${percent(analysis.clipping.shadows)}",
                    color = Color.White, fontSize = 12.sp,
                )
            }
        }
        if (showInfo) {
            for (line in lines) {
                Text(
                    text = "${line.label}  ${line.value}",
                    color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Medium,
                    modifier = Modifier.semantics { contentDescription = "${line.label}: ${line.value}" },
                )
            }
        }
    }
}

internal fun percent(fraction: Float): String {
    val p = fraction * 100f
    return if (p > 0f && p < 0.1f) "<0.1%" else "%.1f%%".format(java.util.Locale.US, p)
}

@Composable
private fun HistogramStrip(h: ToneHistogram) {
    val luma = remember(h) { ToneHistogram.normalised(h.luma) }
    Canvas(
        modifier = Modifier
            .width(HIST_W_DP.dp).height(HIST_H_DP.dp)
            .semantics { contentDescription = "Histogram of the photo's tones" },
    ) {
        val barW = size.width / luma.size
        for (i in luma.indices) {
            val bh = luma[i] * size.height
            drawRect(Color.White.copy(alpha = 0.85f), Offset(i * barW, size.height - bh), Size(barW + 0.5f, bh))
        }
    }
}

private const val HIST_W_DP = 192
private const val HIST_H_DP = 56
internal const val PRO_CARD_TAG = "viewer-pro-card"

/** The view-options room's group: one 48 dp toggle row per overlay, each with its one-sentence caption. */
@Composable
internal fun ColumnScope.ProOverlayRoomSection(choice: ProOverlayChoice, onSet: (ProOverlay, Boolean) -> Unit) {
    RoomEyebrow("PRO OVERLAYS")
    for (overlay in ProOverlay.values()) {
        RoomRow(
            label = overlay.label,
            caption = overlay.caption,
            onClick = { onSet(overlay, !choice.has(overlay)) },
        ) { RoomToggle(choice.has(overlay), { onSet(overlay, it) }) }
    }
}
