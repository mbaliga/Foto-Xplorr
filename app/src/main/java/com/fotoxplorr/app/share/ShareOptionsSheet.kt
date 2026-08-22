package com.fotoxplorr.app.share

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.fotoxplorr.app.media.MediaAsset
import com.fotoxplorr.app.pro.LocalProEntitlement
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private val SHEET_BACKGROUND = Color(0xFF141414)
private val PRIMARY_TEXT = Color.White
private val SECONDARY_TEXT = Color.White.copy(alpha = 0.65f)
private val MUTED_TEXT = Color.White.copy(alpha = 0.45f)

/**
 * The advanced share options, shown **above** the system share sheet (owner, 2026-08-15:
 * *"there has to be an 'advanced' trigger above the share sheet"*).
 *
 * Above, not before: a user who just wants to send a photo taps Share once more and is gone --
 * the defaults are already the safe ones, so this sheet is never in the way of the common case.
 * It exists for the times someone wants a frame, a seal, or to deliberately keep their metadata.
 *
 * The preview is the point. Every control here changes how the photo will actually look when it
 * lands in someone else's chat, and a switch labelled "Polaroid" tells you nothing about that --
 * so the real [FrameRenderer] draws a real sample, off the main thread, on every change. What is
 * on screen is what gets sent.
 *
 * The watermark row is the one control here that is not really a choice for a non-Pro account:
 * the free tier's mark cannot be switched off from this sheet (owner, 2026-08-21), so what is
 * shown is a locked, checked switch and a way to unlock Pro rather than a working toggle that
 * would be lying about what Share is about to do. [ShareOptions.resolvedFor] is what both the
 * preview above and the actual [onShare] call use, so what is on screen matches what is drawn
 * either way.
 */
@Composable
fun ShareOptionsSheet(
    sample: MediaAsset?,
    initial: ShareOptions,
    onDismiss: () -> Unit,
    onShare: (ShareOptions) -> Unit,
) {
    val context = LocalContext.current
    val entitlement = remember { LocalProEntitlement(context.applicationContext) }
    val isPro by entitlement.isPro.collectAsState()
    var options by remember { mutableStateOf(initial) }
    var sourceBitmap by remember(sample?.id) { mutableStateOf<Bitmap?>(null) }
    var previewBitmap by remember { mutableStateOf<Bitmap?>(null) }
    // What actually gets drawn and shared, once Pro status is folded in -- see
    // ShareOptions.resolvedFor. The preview below and the Share button both read this, never the
    // raw `options`, so what is on screen is never more optimistic than what Share does.
    val effectiveOptions = options.resolvedFor(isPro)

    // Decode the sample small. This is a thumbnail of a thumbnail -- it exists to show the frame's
    // proportions, and the frame's metrics are all relative to the photo's own edge, so a small
    // decode previews the real thing faithfully at a fraction of the cost.
    LaunchedEffect(sample?.id) {
        val asset = sample ?: return@LaunchedEffect
        sourceBitmap = withContext(Dispatchers.IO) {
            runCatching {
                context.contentResolver.openInputStream(asset.contentUri)?.use { stream ->
                    val bytes = stream.readBytes()
                    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
                    val longest = maxOf(bounds.outWidth, bounds.outHeight).coerceAtLeast(1)
                    var inSample = 1
                    while (longest / inSample > PREVIEW_EDGE_PX) inSample *= 2
                    BitmapFactory.decodeByteArray(
                        bytes, 0, bytes.size,
                        BitmapFactory.Options().apply { inSampleSize = inSample },
                    )
                }
            }.getOrNull()
        }
    }

    LaunchedEffect(sourceBitmap, effectiveOptions) {
        val base = sourceBitmap ?: return@LaunchedEffect
        previewBitmap = withContext(Dispatchers.Default) {
            runCatching { FrameRenderer.render(base, effectiveOptions) }.getOrNull()
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 40.dp)
                .background(SHEET_BACKGROUND, RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp))
                .navigationBarsPadding()
                .verticalScroll(rememberScrollState())
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            // The grabber, purely so the sheet reads as a sheet.
            Box(
                Modifier
                    .align(Alignment.CenterHorizontally)
                    .size(width = 36.dp, height = 4.dp)
                    .background(Color.White.copy(alpha = 0.25f), RoundedCornerShape(50)),
            )

            Text("Share options", color = PRIMARY_TEXT, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)

            previewBitmap?.let { preview ->
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(200.dp)
                        // A checkerboard-free neutral bed: the stamp frame is genuinely
                        // transparent at its perforations, and previewing it on the sheet's own
                        // dark surface is an honest look at what a dark chat background will show.
                        .background(Color(0xFF0A0A0A), RoundedCornerShape(12.dp))
                        .padding(10.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Image(
                        bitmap = preview.asImageBitmap(),
                        contentDescription = "Preview of the shared photo",
                        contentScale = ContentScale.Fit,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }

            Text("FRAME", color = MUTED_TEXT, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                ShareFrame.entries.forEach { frame ->
                    val selected = options.frame == frame
                    Text(
                        text = frame.label,
                        color = if (selected) Color.Black else PRIMARY_TEXT,
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                        modifier = Modifier
                            .clip(RoundedCornerShape(50))
                            .background(
                                if (selected) MaterialTheme.colorScheme.primary else Color.White.copy(alpha = 0.10f),
                            )
                            .clickable { options = options.copy(frame = frame) }
                            .padding(horizontal = 14.dp, vertical = 9.dp),
                    )
                }
            }
            Text(options.frame.description, color = SECONDARY_TEXT, style = MaterialTheme.typography.bodySmall)

            if (options.frame == ShareFrame.POLAROID) {
                SheetField(
                    value = options.caption.orEmpty(),
                    onValueChange = { options = options.copy(caption = it) },
                    label = "Caption",
                    caption = "Written along the bottom of the frame. Leave blank for none.",
                )
            }
            if (options.frame == ShareFrame.STAMP) {
                SheetField(
                    value = options.seal.orEmpty(),
                    onValueChange = { options = options.copy(seal = it) },
                    label = "Your seal",
                    caption = "A few letters, struck like a postmark in the corner.",
                )
            }

            SheetSwitch(
                label = "Remove location and camera data",
                caption = "On by default. Strips GPS, camera model and timestamps from the copy you send.",
                checked = options.stripMetadata,
                onCheckedChange = { options = options.copy(stripMetadata = it) },
            )
            WatermarkRow(isPro = isPro, onUnlock = entitlement::recordUnlock)

            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                horizontalArrangement = Arrangement.End,
            ) {
                Text(
                    "Cancel",
                    color = SECONDARY_TEXT,
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.clickable(onClick = onDismiss).padding(horizontal = 14.dp, vertical = 10.dp),
                )
                Text(
                    "Share",
                    color = MaterialTheme.colorScheme.primary,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier
                        .clip(RoundedCornerShape(50))
                        .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.16f))
                        .clickable { onShare(effectiveOptions) }
                        .padding(horizontal = 18.dp, vertical = 10.dp),
                )
            }
        }
    }
}

@Composable
private fun SheetSwitch(
    label: String,
    caption: String,
    checked: Boolean,
    onCheckedChange: ((Boolean) -> Unit)?,
    enabled: Boolean = true,
) {
    Row(
        Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Column(Modifier.padding(end = 16.dp).weight(1f)) {
            Text(label, color = PRIMARY_TEXT, style = MaterialTheme.typography.bodyLarge)
            Text(caption, color = SECONDARY_TEXT, style = MaterialTheme.typography.bodySmall)
        }
        com.fotoxplorr.app.hyle.HyleToggle(
            checked = checked,
            onCheckedChange = onCheckedChange,
            enabled = enabled,
            description = label,
        )
    }
}

/**
 * The watermark control, which is present-but-locked for everyone who has not unlocked Pro.
 *
 * A working switch a non-Pro account could flip off would be a promise the free tier does not
 * keep — see [ShareOptions.resolveWatermark]. So the switch here always shows the true state
 * ([isPro]'s free/Pro reading, not a per-share choice) and is always disabled; the only thing a
 * non-Pro sharer can actually do about the mark lives below it, as a plain, honestly labelled
 * unlock action rather than a toggle wired to look like one.
 */
@Composable
private fun WatermarkRow(isPro: Boolean, onUnlock: () -> Unit) {
    if (isPro) {
        SheetSwitch(
            label = "No Foto Xplorr mark",
            caption = "Included with Pro. Every share leaves the mark off.",
            checked = false,
            onCheckedChange = null,
            enabled = false,
        )
        return
    }

    Column {
        SheetSwitch(
            label = "Add the Foto Xplorr mark",
            caption = "On for every free share. Unlock Pro to turn it off.",
            checked = true,
            onCheckedChange = null,
            enabled = false,
        )
        Text(
            "Unlock Pro",
            color = MaterialTheme.colorScheme.primary,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.Bold,
            modifier = Modifier
                .padding(top = 6.dp)
                .clickable(onClick = onUnlock)
                .padding(vertical = 4.dp),
        )
        Text(
            // Honest about what tapping it actually does today -- see ProEntitlement's KDoc for
            // why there is no store, no charge and no receipt behind this yet, and where the real
            // purchase goes once the connect flavour implements one.
            "This build doesn't charge anything yet — unlocking here just remembers Pro on this " +
                "device, the same way it will once real billing is wired in.",
            color = MUTED_TEXT,
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.padding(top = 2.dp),
        )
    }
}

@Composable
private fun SheetField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    caption: String,
) {
    Column {
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            label = { Text(label) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
            colors = OutlinedTextFieldDefaults.colors(
                focusedTextColor = PRIMARY_TEXT,
                unfocusedTextColor = PRIMARY_TEXT,
                focusedBorderColor = MaterialTheme.colorScheme.primary,
                unfocusedBorderColor = Color.White.copy(alpha = 0.25f),
                focusedLabelColor = MaterialTheme.colorScheme.primary,
                unfocusedLabelColor = SECONDARY_TEXT,
                cursorColor = MaterialTheme.colorScheme.primary,
                focusedContainerColor = Color.Transparent,
                unfocusedContainerColor = Color.Transparent,
            ),
        )
        Text(caption, color = MUTED_TEXT, style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(start = 4.dp, top = 2.dp))
    }
}

/** Longest edge of the sheet's sample decode. Small on purpose -- see the call site. */
private const val PREVIEW_EDGE_PX = 720
