package com.fotoxplorr.app.ai

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
import android.util.Base64
import android.util.Size
import com.fotoxplorr.app.media.MediaAsset
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import kotlin.math.max
import kotlin.math.min

data class PreparedAiImage(
    val mimeType: String,
    val base64Data: String,
    val byteCount: Int,
    val width: Int,
    val height: Int,
)

class AiImagePreparer(context: Context) {
    private val appContext = context.applicationContext

    suspend fun prepare(asset: MediaAsset): Result<PreparedAiImage> = withContext(Dispatchers.IO) {
        runCatching {
            require(!asset.isVideo) { "Remote photo analysis currently accepts still images only" }
            val source = loadBitmap(asset)
            try {
                var quality = INITIAL_JPEG_QUALITY
                var bytes = compress(source, quality)
                while (bytes.size > MAX_IMAGE_BYTES && quality > MIN_JPEG_QUALITY) {
                    bytes.fill(0)
                    quality -= QUALITY_STEP
                    bytes = compress(source, quality)
                }
                require(bytes.size <= ABSOLUTE_MAX_IMAGE_BYTES) {
                    "Prepared image is still too large for inline analysis"
                }
                try {
                    PreparedAiImage(
                        mimeType = "image/jpeg",
                        base64Data = Base64.encodeToString(bytes, Base64.NO_WRAP),
                        byteCount = bytes.size,
                        width = source.width,
                        height = source.height,
                    )
                } finally {
                    bytes.fill(0)
                }
            } finally {
                source.recycle()
            }
        }
    }

    private fun loadBitmap(asset: MediaAsset): Bitmap {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            return appContext.contentResolver.loadThumbnail(
                asset.contentUri,
                Size(MAX_IMAGE_DIMENSION, MAX_IMAGE_DIMENSION),
                null,
            ).ensureArgb8888()
        }
        val decoded = appContext.contentResolver.openInputStream(asset.contentUri)?.use { input ->
            BitmapFactory.decodeStream(input)
        } ?: error("Unable to decode ${asset.displayName}")
        val scale = min(1f, MAX_IMAGE_DIMENSION.toFloat() / max(decoded.width, decoded.height))
        val scaled = if (scale < 1f) {
            Bitmap.createScaledBitmap(
                decoded,
                max(1, (decoded.width * scale).toInt()),
                max(1, (decoded.height * scale).toInt()),
                true,
            ).also { if (it !== decoded) decoded.recycle() }
        } else decoded
        return scaled.ensureArgb8888()
    }

    private fun compress(bitmap: Bitmap, quality: Int): ByteArray = ByteArrayOutputStream().use { output ->
        check(bitmap.compress(Bitmap.CompressFormat.JPEG, quality, output)) {
            "Unable to prepare image for analysis"
        }
        output.toByteArray()
    }

    private fun Bitmap.ensureArgb8888(): Bitmap = if (config == Bitmap.Config.ARGB_8888) {
        this
    } else {
        copy(Bitmap.Config.ARGB_8888, false).also { if (it !== this) recycle() }
    }

    private companion object {
        const val MAX_IMAGE_DIMENSION = 1280
        const val INITIAL_JPEG_QUALITY = 86
        const val MIN_JPEG_QUALITY = 56
        const val QUALITY_STEP = 10
        const val MAX_IMAGE_BYTES = 2_500_000
        const val ABSOLUTE_MAX_IMAGE_BYTES = 5_000_000
    }
}
