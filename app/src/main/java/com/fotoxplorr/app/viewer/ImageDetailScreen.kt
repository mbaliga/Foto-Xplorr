package com.fotoxplorr.app.viewer

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowBack
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.exifinterface.media.ExifInterface
import com.fotoxplorr.app.media.MediaAsset
import com.fotoxplorr.app.media.MediaImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.DateFormat
import java.util.Date
import kotlin.math.roundToInt

/**
 * Full image-detail screen from the mockups: photo, an "Add a Caption" affordance
 * (placeholder only -- there is no caption storage/backing field in Foto Xplorr's data
 * model yet, so this does not persist anything), a metadata card, and a related-photos
 * grid (other media from the same device folder).
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
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text("Details", maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = {
                    IconButton(onClick = onClose) {
                        Icon(Icons.Outlined.ArrowBack, contentDescription = "Back to viewer")
                    }
                },
            )
        },
    ) { padding ->
        LazyVerticalGrid(
            columns = GridCells.Fixed(3),
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 24.dp),
        ) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                MediaImage(
                    asset = asset,
                    modifier = Modifier.fillMaxWidth().aspectRatio(asset.aspectRatio.coerceIn(0.5f, 2.2f)),
                    contentScale = ContentScale.Fit,
                )
            }
            item(span = { GridItemSpan(maxLineSpan) }) {
                CaptionPlaceholder(modifier = Modifier.fillMaxWidth().padding(16.dp))
            }
            item(span = { GridItemSpan(maxLineSpan) }) {
                MetadataCard(
                    asset = asset,
                    exif = exif,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                )
            }
            if (relatedAssets.isNotEmpty()) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    Text(
                        "Related",
                        style = MaterialTheme.typography.titleSmall,
                        modifier = Modifier.padding(start = 16.dp, top = 20.dp, bottom = 8.dp),
                    )
                }
                items(relatedAssets, key = { it.id.value }) { related ->
                    Box(
                        modifier = Modifier
                            .aspectRatio(1f)
                            .padding(1.dp)
                            .clip(RoundedCornerShape(4.dp))
                            .background(MaterialTheme.colorScheme.surfaceVariant)
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

@Composable
private fun CaptionPlaceholder(modifier: Modifier = Modifier) {
    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Text(
            "Add a caption",
            modifier = Modifier.fillMaxWidth().padding(14.dp),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

@Composable
private fun MetadataCard(
    asset: MediaAsset,
    exif: ImageExifDetails,
    modifier: Modifier = Modifier,
) {
    Card(modifier = modifier) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            MetadataLine(formatDateTime(asset.dateTakenMillis))
            MetadataLine(asset.displayName)

            val deviceLine = listOfNotNull(exif.make, exif.model).joinToString(" ").trim()
                .takeIf(String::isNotEmpty)
            val lensLine = listOfNotNull(
                exif.lensModel,
                exif.focalLengthMm,
                exif.aperture,
            ).joinToString(" · ").takeIf(String::isNotEmpty)
            if (deviceLine != null) MetadataLine(deviceLine)
            if (lensLine != null) MetadataLine(lensLine, secondary = true)

            MetadataLine(
                "${asset.width} × ${asset.height} · ${formatBytes(asset.sizeBytes)}",
                secondary = true,
            )

            val exposureRow = listOfNotNull(
                exif.iso?.let { "ISO $it" },
                exif.focalLengthMm,
                exif.exposureBias,
                exif.aperture,
                exif.shutterSpeed,
            )
            if (exposureRow.isNotEmpty()) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    exposureRow.forEach { field ->
                        Text(
                            field,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun MetadataLine(text: String, secondary: Boolean = false) {
    Text(
        text,
        style = if (secondary) MaterialTheme.typography.bodySmall else MaterialTheme.typography.bodyMedium,
        color = if (secondary) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
    )
}

/** EXIF fields for the metadata card. All optional: many images (esp. screenshots, edited
 * copies, or anything that already had EXIF stripped by this app's own clean-share export)
 * won't carry camera data, and this has not been verified against real camera-originated
 * files on a device -- only read against the ExifInterface API shape.
 */
data class ImageExifDetails(
    val make: String? = null,
    val model: String? = null,
    val lensModel: String? = null,
    val focalLengthMm: String? = null,
    val aperture: String? = null,
    val iso: String? = null,
    val shutterSpeed: String? = null,
    val exposureBias: String? = null,
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
    val focalLength = exif.getAttributeDouble(ExifInterface.TAG_FOCAL_LENGTH, Double.NaN).takeUnless(Double::isNaN)
    val aperture = exif.getAttributeDouble(ExifInterface.TAG_F_NUMBER, Double.NaN).takeUnless(Double::isNaN)
    val shutter = exif.getAttributeDouble(ExifInterface.TAG_EXPOSURE_TIME, Double.NaN).takeUnless(Double::isNaN)
    val bias = exif.getAttributeDouble(ExifInterface.TAG_EXPOSURE_BIAS_VALUE, Double.NaN).takeUnless(Double::isNaN)
    val iso = exif.getAttribute(ExifInterface.TAG_PHOTOGRAPHIC_SENSITIVITY)
        ?: exif.getAttribute(ExifInterface.TAG_ISO_SPEED_RATINGS)
    return ImageExifDetails(
        make = exif.getAttribute(ExifInterface.TAG_MAKE)?.trim()?.takeIf(String::isNotEmpty),
        model = exif.getAttribute(ExifInterface.TAG_MODEL)?.trim()?.takeIf(String::isNotEmpty),
        lensModel = exif.getAttribute(ExifInterface.TAG_LENS_MODEL)?.trim()?.takeIf(String::isNotEmpty),
        focalLengthMm = focalLength?.let { "%.0f mm".format(it) },
        aperture = aperture?.let { "f/%.1f".format(it) },
        iso = iso,
        shutterSpeed = shutter?.let(::formatShutterSpeed),
        exposureBias = bias?.takeUnless { it == 0.0 }?.let { "%+.1f EV".format(it) },
    )
}

private fun formatShutterSpeed(seconds: Double): String = when {
    seconds <= 0.0 -> "Unknown shutter speed"
    seconds >= 1.0 -> "%.1f s".format(seconds)
    else -> "1/${(1.0 / seconds).roundToInt()} s"
}

private fun formatDateTime(epochMillis: Long): String {
    if (epochMillis <= 0L) return "Unknown date"
    return DateFormat.getDateTimeInstance(DateFormat.FULL, DateFormat.SHORT).format(Date(epochMillis))
}

private fun formatBytes(bytes: Long): String {
    if (bytes <= 0L) return "Unknown size"
    val units = listOf("B", "KB", "MB", "GB")
    var value = bytes.toDouble()
    var unitIndex = 0
    while (value >= 1024.0 && unitIndex < units.lastIndex) {
        value /= 1024.0
        unitIndex += 1
    }
    return if (unitIndex == 0) "${value.roundToInt()} ${units[unitIndex]}" else "%.1f %s".format(value, units[unitIndex])
}
