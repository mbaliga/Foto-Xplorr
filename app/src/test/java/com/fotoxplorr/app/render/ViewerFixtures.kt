package com.fotoxplorr.app.render

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import androidx.compose.foundation.Canvas as ComposeCanvas
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color as ComposeColor
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.unit.dp
import coil3.ImageLoader
import coil3.SingletonImageLoader
import com.fotoxplorr.app.media.MediaAsset
import com.fotoxplorr.app.viewer.ImageExifDetails
import com.fotoxplorr.core.model.MediaId
import java.io.File
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlin.coroutines.EmptyCoroutineContext

/**
 * Photos for the viewer renders, as real files Coil can decode on the JVM.
 *
 * Earlier render tests drew photos as empty tiles because Coil cannot read a `content://` URI
 * without a MediaStore. A `file://` URI needs no MediaStore, Robolectric's NATIVE graphics decode
 * PNG, and an [ImageLoader] whose every dispatcher is the caller's own runs the whole request
 * synchronously inside composition -- so the capture that follows `setContent` already holds the
 * pixels. That is what lets a render of the filmstrip or the plate's pin be compared against the
 * owner's screenshots at all: the geometry of a photo you cannot see is not checkable.
 *
 * The pictures are synthetic -- a soft wall, a face-shaped oval, a sweep of hair -- because the
 * repo carries no photographs and the comparison is about chrome, letterboxing and tiles, not
 * about the subject.
 */
internal object ViewerFixtures {

    /** Make Coil decode on the calling thread. Idempotent: `setSafe` keeps the first factory. */
    fun installSynchronousImageLoader(context: Context) {
        SingletonImageLoader.setSafe {
            ImageLoader.Builder(context.applicationContext)
                .coroutineContext(EmptyCoroutineContext)
                .fetcherCoroutineContext(EmptyCoroutineContext)
                .decoderCoroutineContext(EmptyCoroutineContext)
                .build()
        }
    }

    /** A square portrait, like the reference's: a pale wall, a face, a sweep of hair. */
    fun portrait(context: Context, id: Long, seed: Int = 0, width: Int = 900, height: Int = 900): MediaAsset {
        val file = File(context.cacheDir, "viewer-fixture-$id.png")
        if (!file.exists()) {
            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            val wall = PALETTE[seed % PALETTE.size]
            canvas.drawColor(wall.first)
            val paint = Paint(Paint.ANTI_ALIAS_FLAG)
            // A soft side light on the wall.
            paint.shader = LinearGradient(
                0f, 0f, width.toFloat(), 0f,
                wall.first, wall.second, Shader.TileMode.CLAMP,
            )
            canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), paint)
            paint.shader = null
            // Shoulders.
            paint.color = wall.third
            canvas.drawRoundRect(
                RectF(width * 0.12f, height * 0.72f, width * 0.95f, height * 1.3f),
                width * 0.18f, width * 0.18f, paint,
            )
            // Hair, then the face over it.
            paint.color = HAIR[seed % HAIR.size]
            canvas.drawOval(RectF(width * 0.22f, height * 0.14f, width * 0.74f, height * 0.78f), paint)
            paint.color = SKIN[seed % SKIN.size]
            canvas.drawOval(RectF(width * 0.30f, height * 0.26f, width * 0.66f, height * 0.74f), paint)
            file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 90, it) }
            bitmap.recycle()
        }
        return MediaAsset(
            id = MediaId(id),
            contentUriString = "file://${file.absolutePath}",
            displayName = "IMG_%04d.HEIC".format(760 + id),
            mimeType = "image/heic",
            bucketName = "Camera",
            bucketId = 100L,
            dateTakenMillis = TAKEN_MILLIS,
            dateModifiedSeconds = MODIFIED_MILLIS / 1_000L,
            width = width,
            height = height,
            sizeBytes = 2_202_010L,
            relativePath = "DCIM/Camera/",
            isFavorite = false,
            isTrashed = false,
        )
    }

    /** The reference's own photo: `IMG_0767`, a 3024 x 4032 HEIF of 2.1 MB taken on an iPhone. */
    fun referencePhoto(context: Context): MediaAsset =
        portrait(context, id = 7L, seed = 0).copy(displayName = "IMG_0767.HEIC", width = 3024, height = 4032)

    /** A roll of nine, the reference photo fifth, so the strip has thumbnails either side of the centre. */
    fun roll(context: Context): List<MediaAsset> = (1L..9L).map { id ->
        if (id == 5L) referencePhoto(context).copy(id = MediaId(5L)) else portrait(context, id, seed = id.toInt())
    }

    /** The reference's camera card, line for line: iPhone 17 Pro, Ultra Wide, ISO 50, ƒ2.2, 1/33 s. */
    val referenceExif = ImageExifDetails(
        make = "Apple",
        model = "iPhone 17 Pro",
        lensModel = "Ultra Wide Camera",
        focalLengthMm = 13.0,
        focalLength35mm = 24,
        aperture = 2.2,
        iso = "50",
        shutterSpeed = "1/33 s",
        exposureBiasEv = 0.0,
        flash = 0,
        latitude = 17.4435,
        longitude = 78.3772,
        colorSpace = "sRGB",
        colorProfile = "sRGB IEC61966-2.1",
    )

    /** Saturday 1 Aug 2026, 10:33 PM, in whatever zone the test JVM runs in. */
    val TAKEN_MILLIS: Long = ZonedDateTime.of(2026, 8, 1, 22, 33, 0, 0, ZoneId.systemDefault()).toInstant().toEpochMilli()

    /** Monday 3 Aug 2026, 10:47 PM. */
    val MODIFIED_MILLIS: Long = ZonedDateTime.of(2026, 8, 3, 22, 47, 0, 0, ZoneId.systemDefault()).toInstant().toEpochMilli()

    /**
     * A stand-in for the plate's map, because MapLibre cannot render on the JVM: the reference's
     * own ground and road colours, a river and two parks, drawn as plain shapes. It stands in for
     * the connect flavour's snapshot in exactly the slot that snapshot fills.
     */
    val mapStandIn: @Composable BoxScope.(settled: Boolean) -> Unit = { _ ->
        ComposeCanvas(Modifier.matchParentSize()) {
            drawRect(MAP_GROUND)
            // Water, bottom-left, and two parks.
            val water = Path().apply {
                moveTo(0f, size.height * 0.52f)
                cubicTo(size.width * 0.18f, size.height * 0.48f, size.width * 0.26f, size.height * 0.70f, size.width * 0.14f, size.height)
                lineTo(0f, size.height)
                close()
            }
            drawPath(water, MAP_WATER)
            drawRoundRect(MAP_PARK, Offset(size.width * 0.20f, size.height * 0.62f), Size(size.width * 0.16f, size.height * 0.24f))
            drawRoundRect(MAP_PARK, Offset(size.width * 0.78f, size.height * 0.04f), Size(size.width * 0.18f, size.height * 0.14f))
            // Roads: a few mains, many minors.
            val minor = 1.5.dp.toPx()
            val major = 4.dp.toPx()
            for (i in 1 until 10) {
                val x = size.width * i / 10f
                val y = size.height * i / 10f
                drawLine(MAP_ROAD, Offset(x, 0f), Offset(x + size.width * 0.06f, size.height), minor, StrokeCap.Round)
                drawLine(MAP_ROAD, Offset(0f, y), Offset(size.width, y - size.height * 0.05f), minor, StrokeCap.Round)
            }
            drawLine(MAP_ROAD_MAJOR, Offset(size.width * 0.30f, 0f), Offset(size.width * 0.58f, size.height), major, StrokeCap.Round)
            drawLine(MAP_ROAD_MAJOR, Offset(0f, size.height * 0.18f), Offset(size.width, size.height * 0.08f), major, StrokeCap.Round)
            drawLine(MAP_ROAD_MAJOR, Offset(size.width * 0.5f, size.height * 0.62f), Offset(size.width, size.height * 0.78f), major, StrokeCap.Round)
        }
    }

    private val MAP_GROUND = ComposeColor(0xFF344459)
    private val MAP_ROAD = ComposeColor(0xFF445D7C)
    private val MAP_ROAD_MAJOR = ComposeColor(0xFF6E86A6)
    private val MAP_WATER = ComposeColor(0xFF1D3373)
    private val MAP_PARK = ComposeColor(0xFF1F6B4C)

    private val PALETTE = listOf(
        Triple(Color.rgb(201, 205, 206), Color.rgb(182, 184, 180), Color.rgb(236, 230, 214)),
        Triple(Color.rgb(224, 211, 196), Color.rgb(196, 186, 172), Color.rgb(120, 98, 80)),
        Triple(Color.rgb(18, 96, 140), Color.rgb(8, 70, 110), Color.rgb(32, 32, 36)),
        Triple(Color.rgb(226, 220, 214), Color.rgb(203, 196, 188), Color.rgb(90, 70, 58)),
        Triple(Color.rgb(165, 101, 70), Color.rgb(132, 76, 50), Color.rgb(210, 190, 168)),
        Triple(Color.rgb(60, 58, 60), Color.rgb(40, 38, 40), Color.rgb(44, 36, 30)),
        Triple(Color.rgb(96, 142, 182), Color.rgb(60, 110, 160), Color.rgb(242, 240, 236)),
        Triple(Color.rgb(230, 228, 226), Color.rgb(206, 204, 200), Color.rgb(44, 40, 38)),
        Triple(Color.rgb(84, 110, 120), Color.rgb(60, 80, 90), Color.rgb(222, 212, 196)),
    )
    private val HAIR = listOf(
        Color.rgb(198, 112, 52), Color.rgb(40, 28, 20), Color.rgb(60, 36, 24), Color.rgb(24, 20, 20),
        Color.rgb(150, 60, 40), Color.rgb(28, 26, 30), Color.rgb(70, 48, 36), Color.rgb(140, 90, 50), Color.rgb(30, 30, 34),
    )
    private val SKIN = listOf(
        Color.rgb(236, 206, 188), Color.rgb(214, 172, 140), Color.rgb(120, 82, 60), Color.rgb(230, 198, 176),
        Color.rgb(170, 120, 92), Color.rgb(224, 190, 168), Color.rgb(96, 64, 48), Color.rgb(240, 214, 198), Color.rgb(190, 150, 120),
    )
}
