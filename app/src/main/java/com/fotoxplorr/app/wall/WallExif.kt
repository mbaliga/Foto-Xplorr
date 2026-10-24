package com.fotoxplorr.app.wall

import android.content.Context
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import java.io.InputStream

/**
 * The 35 mm equivalent focal length a photo says it was taken with, for [WallScale.fromDistance].
 * Read here from the tag itself rather than through the viewer's details reader, which is another
 * package's to change. The metadata only: no pixels are read, so no export check applies; a caller
 * still asks the host's `mayExport` first so a locked photo is not even opened.
 */
internal object WallExif {
    /** The focal length in mm, or null when the photo has none (a screenshot, an edited copy) or cannot be read. */
    fun focal35mm(stream: InputStream): Float? = runCatching {
        ExifInterface(stream).getAttributeInt(ExifInterface.TAG_FOCAL_LENGTH_IN_35MM_FILM, 0)
    }.getOrNull()?.takeIf { it in 5..2000 }?.toFloat()

    fun focal35mm(context: Context, uri: Uri): Float? =
        runCatching { context.contentResolver.openInputStream(uri)?.use(::focal35mm) }.getOrNull()
}
