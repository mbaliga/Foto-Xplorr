package com.fotoxplorr.app.vision

import android.graphics.Bitmap
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.performClick
import com.fotoxplorr.app.media.MediaAsset
import com.fotoxplorr.core.model.MediaId

/** Small fixtures for the viewer-side colour-vision tests. */
internal object ColourVisionKit {
    fun asset(
        id: Long = 7L,
        name: String = "IMG_0007.jpg",
        mime: String = "image/jpeg",
        external: Boolean = false,
        modified: Long = 100L,
    ) = MediaAsset(
        id = MediaId(id), contentUriString = "content://fake/$id", displayName = name, mimeType = mime,
        bucketName = null, dateTakenMillis = 1_700_000_000_000L, dateModifiedSeconds = modified,
        width = 8, height = 6, sizeBytes = 0, relativePath = "DCIM/Camera/", isFavorite = false, isTrashed = false,
        isExternal = external,
    )

    /** A [w] x [h] bitmap whose pixel at (x, y) is [colour](x, y). */
    fun bitmap(w: Int, h: Int, colour: (Int, Int) -> Int): Bitmap {
        val pixels = IntArray(w * h) { colour(it % w, it / w) }
        return Bitmap.createBitmap(pixels, w, h, Bitmap.Config.ARGB_8888)
    }

    fun pixels(bitmap: Bitmap): IntArray = IntArray(bitmap.width * bitmap.height).also {
        bitmap.getPixels(it, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
    }
}

/** Clicks only if the node is enabled, so a loop can run to the end of a stepper. */
internal fun androidx.compose.ui.test.SemanticsNodeInteraction.performClickIfEnabled() {
    val enabled = runCatching { assertIsEnabled() }.isSuccess
    if (enabled) performClick()
}
