package com.fotoxplorr.app.sources.previews

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
import java.io.ByteArrayOutputStream

/** WebP bytes in and out of a [Bitmap]. Pure CPU work: call it off the main thread. */
object PreviewCodec {
    /** The bytes of [bitmap] as WebP, or null if the platform has no encoder for it. */
    fun encode(bitmap: Bitmap): ByteArray? {
        val out = ByteArrayOutputStream(24 * 1024)
        val format = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Bitmap.CompressFormat.WEBP_LOSSY
        } else {
            // WEBP_LOSSY only exists from API 30; the older constant is superseded, not gone.
            @Suppress("DEPRECATION")
            Bitmap.CompressFormat.WEBP
        }
        return if (bitmap.compress(format, PreviewPolicy.QUALITY, out)) out.toByteArray() else null
    }

    /** The picture in [bytes], or null if they are not a decodable image (a damaged file). */
    fun decode(bytes: ByteArray): Bitmap? = runCatching { BitmapFactory.decodeByteArray(bytes, 0, bytes.size) }.getOrNull()
}
