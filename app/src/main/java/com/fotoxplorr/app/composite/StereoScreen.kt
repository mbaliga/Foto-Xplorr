package com.fotoxplorr.app.composite

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.fotoxplorr.app.media.DecodeLimits
import com.fotoxplorr.app.media.MediaAsset
import com.fotoxplorr.app.media.applyOrientation
import com.fotoxplorr.app.media.orientationTransform
import com.fotoxplorr.app.media.sampleSizeFor
import com.fotoxplorr.app.media.targetSize
import com.fotoxplorr.core.formats.composite.MpoImage
import com.fotoxplorr.core.formats.composite.StereoMode
import com.fotoxplorr.core.formats.composite.StereoPair
import com.fotoxplorr.core.formats.composite.StereoPixels
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Tags for tests. */
internal object StereoTags {
    const val SCREEN = "stereoScreen"
    fun mode(mode: StereoMode) = "stereoMode-${mode.name}"
}

/** Both eyes, decoded to one size. [anaglyph] is made lazily the first time it is asked for. */
internal class StereoEyes(val left: Bitmap, val right: Bitmap) {
    val leftImage: ImageBitmap = left.asImageBitmap()
    val rightImage: ImageBitmap = right.asImageBitmap()
}

/**
 * The 3D view of a stereo MPO: side by side (parallel), cross-eye (swapped), red-cyan anaglyph and
 * wiggle, chosen with 48 dp buttons whose selected state is marked by a border and bold text as well
 * as colour. Wiggle is motion: it is not offered while the system's animations are off, and a
 * choice of wiggle falls back to side by side if they are turned off.
 *
 * It owns the screen like the 360 view (composed last, counted by `ViewerChromePolicy.screenOwned`
 * through the viewer's hook), and Back or the 48 dp close button leave it.
 *
 * Both eyes are decoded from their own byte ranges with the same orientation rules as every other
 * decode (`orientationTransform` / `applyOrientation`, TRAPS #34); the platform's whole-file decode
 * would only ever see the first JPEG. Nothing here has run on a device.
 */
@Composable
internal fun StereoScreen(asset: MediaAsset, pair: StereoPair, onClose: () -> Unit) {
    val context = LocalContext.current.applicationContext
    BackHandler(onBack = onClose)
    val motionAllowed = remember { animationsOn(context) }
    var mode by remember { mutableStateOf(StereoMode.SIDE_BY_SIDE) }
    if (!StereoPixels.offered(mode, motionAllowed)) mode = StereoMode.SIDE_BY_SIDE

    val eyes by produceState<Result<StereoEyes>?>(null, asset.id, pair) {
        value = withContext(Dispatchers.IO) {
            try {
                Result.success(StereoDecoding.decode(context, asset.contentUri, pair) ?: error("not decodable"))
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                Result.failure(t)
            }
        }
    }
    val loaded = eyes?.getOrNull()

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            .testTag(StereoTags.SCREEN)
            // Owns the screen: touches stop here and never reach the viewer below.
            .pointerInput(Unit) { },
    ) {
        when {
            eyes == null -> CentreText(CompositeCopy.STEREO_LOADING)
            loaded == null -> CentreText(CompositeCopy.STEREO_FAILED)
            else -> StereoPicture(loaded, mode, motionAllowed)
        }
        IconButton(
            onClick = onClose,
            modifier = Modifier
                .align(Alignment.TopStart)
                .statusBarsPadding()
                .padding(12.dp)
                .size(48.dp)
                .background(Color.Black.copy(alpha = 0.55f), CircleShape)
                .semantics { contentDescription = CompositeCopy.CLOSE_STEREO },
        ) { Icon(Icons.Outlined.Close, contentDescription = null, tint = Color.White) }
        Row(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
                .padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            StereoMode.entries.filter { StereoPixels.offered(it, motionAllowed) }.forEach { option ->
                ModeButton(option, selected = option == mode) { mode = option }
            }
        }
    }
}

@Composable
private fun StereoPicture(eyes: StereoEyes, mode: StereoMode, motionAllowed: Boolean) {
    when (mode) {
        StereoMode.SIDE_BY_SIDE, StereoMode.CROSS_EYE -> Row(Modifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically) {
            val first = if (StereoPixels.leftFirst(mode)) eyes.leftImage else eyes.rightImage
            val second = if (StereoPixels.leftFirst(mode)) eyes.rightImage else eyes.leftImage
            Image(first, contentDescription = null, contentScale = ContentScale.Fit, modifier = Modifier.weight(1f))
            Image(second, contentDescription = null, contentScale = ContentScale.Fit, modifier = Modifier.weight(1f))
        }
        StereoMode.ANAGLYPH -> {
            val blended by produceState<ImageBitmap?>(null, eyes) {
                value = withContext(Dispatchers.Default) { StereoDecoding.anaglyph(eyes).asImageBitmap() }
            }
            blended?.let { Image(it, contentDescription = null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize()) }
        }
        StereoMode.WIGGLE -> {
            var right by remember { mutableStateOf(false) }
            LaunchedEffect(motionAllowed) {
                val start = withFrameMillis { it }
                while (true) {
                    withFrameMillis { now -> right = StereoPixels.wiggleShowsRight(now - start, motionAllowed) }
                }
            }
            Image(if (right) eyes.rightImage else eyes.leftImage, contentDescription = null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize())
        }
    }
}

@Composable
private fun ModeButton(mode: StereoMode, selected: Boolean, onSelect: () -> Unit) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .heightIn(min = 48.dp)
            .widthIn(min = 48.dp)
            .background(if (selected) Color.White.copy(alpha = 0.92f) else Color.Black.copy(alpha = 0.6f), RoundedCornerShape(24.dp))
            .then(if (selected) Modifier.border(2.dp, Color.White, RoundedCornerShape(24.dp)) else Modifier)
            .selectable(selected = selected, role = Role.RadioButton, onClick = onSelect)
            .semantics {
                contentDescription = CompositeCopy.modeDescription(mode)
                stateDescription = if (selected) "Selected" else "Not selected"
            }
            .testTag(StereoTags.mode(mode))
            .padding(horizontal = 14.dp),
    ) {
        Text(
            (if (selected) "✓ " else "") + CompositeCopy.modeLabel(mode),
            color = if (selected) Color.Black else Color.White,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
            style = MaterialTheme.typography.labelLarge,
        )
    }
}

@Composable
private fun CentreText(text: String) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(text, color = Color.White, modifier = Modifier.fillMaxWidth().padding(32.dp))
    }
}

/** Whether the system's animations are on (Settings > Accessibility > Remove animations is off). */
private fun animationsOn(context: Context): Boolean =
    runCatching { Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) > 0f }
        .getOrDefault(true)

/** Decoding both eyes of an MPO, upright and at one size. Blocking IO; run it off the main thread. */
internal object StereoDecoding {
    /** Each eye's bound: the two eyes and an anaglyph stay near 24 MB together. */
    val EYE_LIMITS = DecodeLimits(maxLongEdge = 1_600, maxPixels = 2_000_000L)
    private const val MAX_EYE_BYTES = 48L shl 20

    fun decode(context: Context, uri: Uri, pair: StereoPair): StereoEyes? {
        val left = decodeEye(context, uri, pair.left) ?: return null
        val right = decodeEye(context, uri, pair.right) ?: run {
            left.recycle()
            return null
        }
        if (right.width == left.width && right.height == left.height) return StereoEyes(left, right)
        // Eyes of different sizes are drawn at the left eye's size, so the pair lines up.
        val scaled = Bitmap.createScaledBitmap(right, left.width, left.height, true)
        if (scaled !== right) right.recycle()
        return StereoEyes(left, scaled)
    }

    fun anaglyph(eyes: StereoEyes): Bitmap {
        val w = eyes.left.width
        val h = eyes.left.height
        val l = IntArray(w * h).also { eyes.left.getPixels(it, 0, w, 0, 0, w, h) }
        val r = IntArray(w * h).also { eyes.right.getPixels(it, 0, w, 0, 0, w, h) }
        val out = StereoPixels.anaglyph(l, r)
        return Bitmap.createBitmap(out, w, h, Bitmap.Config.ARGB_8888)
    }

    private fun decodeEye(context: Context, uri: Uri, image: MpoImage): Bitmap? {
        if (image.range.length > MAX_EYE_BYTES) return null
        val ops = orientationTransform(image.orientation ?: 1)
        val uprightW = if (ops.swapsDimensions) image.height else image.width
        val uprightH = if (ops.swapsDimensions) image.width else image.height
        val (tw, th) = targetSize(uprightW, uprightH, EYE_LIMITS)
        val rawTw = if (ops.swapsDimensions) th else tw
        val rawTh = if (ops.swapsDimensions) tw else th
        val options = BitmapFactory.Options().apply { inSampleSize = sampleSizeFor(image.width, image.height, rawTw, rawTh) }
        val sampled = CompositeProbe.withRange(context, uri, image.range.offset, image.range.length) {
            BitmapFactory.decodeStream(it, null, options)
        } ?: return null
        val scaled = if (sampled.width == rawTw && sampled.height == rawTh) {
            sampled
        } else {
            Bitmap.createScaledBitmap(sampled, maxOf(1, rawTw), maxOf(1, rawTh), true).also { if (it !== sampled) sampled.recycle() }
        }
        return applyOrientation(scaled, ops)
    }
}
