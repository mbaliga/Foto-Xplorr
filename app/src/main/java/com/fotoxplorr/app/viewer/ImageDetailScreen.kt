package com.fotoxplorr.app.viewer

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.CloudDone
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.exifinterface.media.ExifInterface
import com.fotoxplorr.app.media.MediaAsset
import com.fotoxplorr.app.media.MediaImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

private val PANEL_BACKGROUND = Color(0xFF0B0B0B)
private val CARD_BACKGROUND = Color(0xFF151515)
private val CARD_HEADER_BACKGROUND = Color(0xFF1D1D1D)
private val PRIMARY_TEXT = Color(0xFFF2F2F2)
private val SECONDARY_TEXT = Color(0xFF8A8A8A)
private val MUTED_TEXT = Color(0xFF6A6A6A)

/**
 * The image-detail screen, redrawn against the owner's detail mockup: the photo, an
 * "Add a Caption" affordance, a date line, the filename beside a cloud/backup-state glyph,
 * an EXIF card carrying a format badge + flash glyph + dynamic-range badge, and a
 * related-photos mosaic below.
 *
 * The caption row is still an affordance without storage -- Foto Xplorr has no caption
 * field in its data model, and inventing one was out of scope here -- so tapping it does
 * nothing yet. It is drawn because the mockup draws it; it is not claimed to persist.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ImageDetailScreen(
    asset: MediaAsset,
    relatedAssets: List<MediaAsset>,
    onOpenRelated: (MediaAsset) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    var exif by remember(asset.id) { mutableStateOf(ImageExifDetails()) }
    LaunchedEffect(asset.id) {
        exif = readImageExifDetails(context, asset)
    }

    Scaffold(
        modifier = modifier,
        containerColor = PANEL_BACKGROUND,
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text("Details", maxLines = 1, overflow = TextOverflow.Ellipsis) },
                colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
                    containerColor = PANEL_BACKGROUND,
                    titleContentColor = PRIMARY_TEXT,
                    navigationIconContentColor = PRIMARY_TEXT,
                ),
                navigationIcon = {
                    IconButton(onClick = onClose) {
                        Icon(Icons.Outlined.Close, contentDescription = "Back to viewer")
                    }
                },
            )
        },
    ) { padding ->
        LazyVerticalGrid(
            columns = GridCells.Fixed(3),
            modifier = Modifier
                .fillMaxSize()
                .background(PANEL_BACKGROUND)
                .padding(padding),
            contentPadding = PaddingValues(bottom = 24.dp),
        ) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                MediaImage(
                    asset = asset,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .aspectRatio(asset.aspectRatio.coerceIn(0.5f, 2.2f)),
                    contentScale = ContentScale.Fit,
                )
            }
            item(span = { GridItemSpan(maxLineSpan) }) {
                Text(
                    text = "Add a Caption",
                    color = MUTED_TEXT,
                    style = TextStyle(fontSize = 16.sp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(enabled = false) {}
                        .padding(horizontal = 20.dp, vertical = 18.dp),
                )
            }
            item(span = { GridItemSpan(maxLineSpan) }) {
                DetailPanel(asset = asset, exif = exif)
            }
            if (relatedAssets.isNotEmpty()) {
                items(relatedAssets, key = { it.id.value }) { related ->
                    Box(
                        modifier = Modifier
                            .aspectRatio(1f)
                            .padding(1.dp)
                            .background(Color.Black)
                            .clickable { onOpenRelated(related) },
                    ) {
                        MediaImage(
                            asset = related,
                            modifier = Modifier.fillMaxSize(),
                            contentScale = ContentScale.Crop,
                        )
                    }
                }
            }
        }
    }
}

/** Date line, filename + cloud glyph, and the EXIF card -- the mockup's middle block. */
@Composable
private fun DetailPanel(asset: MediaAsset, exif: ImageExifDetails) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color.Black)
            .padding(horizontal = 20.dp, vertical = 18.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(
            text = DetailFormatting.dateLine(asset.dateTakenMillis),
            color = PRIMARY_TEXT,
            style = TextStyle(fontSize = 15.sp),
        )
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            // Foto Xplorr has no cloud-backup subsystem, so this reports the one backup
            // state it can actually establish: whether the file is still present locally in
            // MediaStore (on device) or has been moved to the system trash (not backed up
            // anywhere by this app). It is not a claim about any cloud service.
            Icon(
                imageVector = if (asset.isTrashed) Icons.Outlined.CloudOff else Icons.Outlined.CloudDone,
                contentDescription = if (asset.isTrashed) "In trash" else "Stored on this device",
                tint = SECONDARY_TEXT,
                modifier = Modifier.size(18.dp),
            )
            Text(
                text = asset.displayName.substringBeforeLast('.'),
                color = SECONDARY_TEXT,
                style = TextStyle(fontSize = 15.sp),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        ExifCard(asset = asset, exif = exif)
    }
}

@Composable
private fun ExifCard(asset: MediaAsset, exif: ImageExifDetails) {
    val deviceLine = listOfNotNull(exif.make, exif.model)
        .joinToString(" ").trim().takeIf(String::isNotEmpty)
    val lensLine = DetailFormatting.lensLine(exif.lensModel, exif.focalLengthMm, exif.aperture)
    val formatBadge = DetailFormatting.formatBadge(asset.mimeType)

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(9.dp))
            .background(CARD_BACKGROUND),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(CARD_HEADER_BACKGROUND)
                .padding(horizontal = 14.dp, vertical = 11.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = deviceLine ?: "Unknown camera",
                color = PRIMARY_TEXT,
                style = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.Medium),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            formatBadge?.let { OutlineBadge(it) }
            if (DetailFormatting.flashFired(exif.flash)) {
                Box(
                    modifier = Modifier
                        .size(22.dp)
                        .clip(RoundedCornerShape(11.dp))
                        .background(Color(0xFF2B2B2B)),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        Icons.Filled.Bolt,
                        contentDescription = "Flash fired",
                        tint = PRIMARY_TEXT,
                        modifier = Modifier.size(14.dp),
                    )
                }
            }
        }

        Column(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            lensLine?.let {
                Text(it, color = SECONDARY_TEXT, style = TextStyle(fontSize = 14.sp))
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = DetailFormatting.dimensionsLine(asset.width, asset.height, asset.sizeBytes),
                    color = SECONDARY_TEXT,
                    style = TextStyle(fontSize = 14.sp),
                    modifier = Modifier.weight(1f),
                )
                OutlineBadge(DetailFormatting.dynamicRangeBadge(asset.mimeType))
            }
        }

        val exposureFields = listOfNotNull(
            exif.iso?.let { "ISO $it" },
            exif.focalLengthMm?.takeIf { it > 0.0 }?.let { "${it.roundToInt()} mm" },
            exif.exposureBiasEv?.let { "${it.roundToInt()} ev" },
            exif.aperture?.takeIf { it > 0.0 }?.let { DetailFormatting.apertureText(it) },
            exif.shutterSpeed,
        )
        if (exposureFields.isNotEmpty()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp)
                    .padding(bottom = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                exposureFields.forEachIndexed { index, field ->
                    if (index > 0) {
                        Box(
                            Modifier
                                .width(1.dp)
                                .height(12.dp)
                                .background(Color(0xFF333333)),
                        )
                    }
                    Text(
                        text = field,
                        color = MUTED_TEXT,
                        style = TextStyle(fontSize = 12.sp),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    )
                }
            }
        }
    }
}

@Composable
private fun OutlineBadge(text: String) {
    Text(
        text = text,
        color = SECONDARY_TEXT,
        style = TextStyle(fontSize = 11.sp, letterSpacing = 0.4.sp),
        modifier = Modifier
            .border(1.dp, Color(0xFF3A3A3A), RoundedCornerShape(4.dp))
            .padding(horizontal = 7.dp, vertical = 3.dp),
    )
}

/**
 * EXIF fields for the metadata card. All optional: many images (esp. screenshots, edited
 * copies, or anything that already had EXIF stripped by this app's own clean-share export)
 * won't carry camera data.
 *
 * Numeric fields are kept numeric here and formatted in [DetailFormatting] rather than being
 * pre-stringified at extraction time, so the presentation can be changed without touching
 * the reader and so the formatting is unit-testable on its own.
 */
data class ImageExifDetails(
    val make: String? = null,
    val model: String? = null,
    val lensModel: String? = null,
    val focalLengthMm: Double? = null,
    val aperture: Double? = null,
    val iso: String? = null,
    val shutterSpeed: String? = null,
    val exposureBiasEv: Double? = null,
    /** Raw EXIF flash bitfield; bit 0 set means the flash fired. */
    val flash: Int? = null,
)

suspend fun readImageExifDetails(context: Context, asset: MediaAsset): ImageExifDetails {
    if (asset.isVideo) return ImageExifDetails()
    return withContext(Dispatchers.IO) {
        runCatching {
            context.contentResolver.openInputStream(asset.contentUri)?.use { input ->
                exifDetailsFrom(ExifInterface(input))
            } ?: ImageExifDetails()
        }.getOrDefault(ImageExifDetails())
    }
}

@Suppress("DEPRECATION") // TAG_ISO_SPEED_RATINGS is the pre-EXIF-2.3 fallback for older files.
private fun exifDetailsFrom(exif: ExifInterface): ImageExifDetails {
    val iso = exif.getAttribute(ExifInterface.TAG_PHOTOGRAPHIC_SENSITIVITY)
        ?: exif.getAttribute(ExifInterface.TAG_ISO_SPEED_RATINGS)
    val shutter = exif.getAttributeDouble(ExifInterface.TAG_EXPOSURE_TIME, Double.NaN)
        .takeUnless(Double::isNaN)
    return ImageExifDetails(
        make = exif.getAttribute(ExifInterface.TAG_MAKE)?.trim()?.takeIf(String::isNotEmpty),
        model = exif.getAttribute(ExifInterface.TAG_MODEL)?.trim()?.takeIf(String::isNotEmpty),
        lensModel = exif.getAttribute(ExifInterface.TAG_LENS_MODEL)?.trim()?.takeIf(String::isNotEmpty),
        focalLengthMm = exif.getAttributeDouble(ExifInterface.TAG_FOCAL_LENGTH, Double.NaN)
            .takeUnless(Double::isNaN),
        aperture = exif.getAttributeDouble(ExifInterface.TAG_F_NUMBER, Double.NaN)
            .takeUnless(Double::isNaN),
        iso = iso,
        shutterSpeed = shutter?.let(::formatShutterSpeed),
        exposureBiasEv = exif.getAttributeDouble(ExifInterface.TAG_EXPOSURE_BIAS_VALUE, Double.NaN)
            .takeUnless(Double::isNaN),
        flash = exif.getAttributeInt(ExifInterface.TAG_FLASH, -1).takeIf { it >= 0 },
    )
}

internal fun formatShutterSpeed(seconds: Double): String = when {
    seconds <= 0.0 -> "—"
    seconds >= 1.0 -> "%.1f s".format(java.util.Locale.US, seconds)
    else -> "1/${(1.0 / seconds).roundToInt()} s"
}
