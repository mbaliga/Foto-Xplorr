package com.fotoxplorr.app.moments

import android.content.Context
import java.io.File

/**
 * The cache sub-directory [MomentFrameExporter] and [ClipExporter] both write their exports into.
 *
 * Must match a `<cache-path>` in `res/xml/file_paths.xml` (see the `moments_export` entry there),
 * or `FileProvider.getUriForFile` throws "Failed to find configured root that contains ..." at
 * the exact moment a user taps "save frame" or "save clip". Nothing in the compiler connects a
 * string here to a string in that XML file -- `MomentExportDirectoryTest` is what holds them
 * together, the same way `com.fotoxplorr.app.share.ShareDirectoryTest` holds
 * `com.fotoxplorr.app.share.SharePreparer` to ITS entry, after the two broke apart for real once
 * (see that test's KDoc for the incident).
 *
 * One directory for both a frame export and a clip export, not two: they are the same feature's
 * two outputs, a person can have a "save frame" share sheet and a "save clip" share sheet open
 * from the same moment at once, and neither caller ever deletes what is already here -- every
 * write below is a freshly `UUID`-named file that nothing else will collide with. That last part
 * is what makes sharing the directory safe: `com.fotoxplorr.app.share.SharePreparer`'s directory
 * cannot be reused for the same reason (it is wiped at the start of every single share), and
 * `com.fotoxplorr.app.lift.StickerExporter`'s KDoc has the full story of the first feature that
 * hit that exact race and grew its own directory instead of sharing that one.
 */
internal object MomentExportStorage {
    const val DIRECTORY = "moments-export"

    fun prepare(context: Context): File {
        val directory = File(context.cacheDir, DIRECTORY)
        check(directory.mkdirs() || directory.isDirectory) { "Could not prepare moment export storage" }
        return directory
    }
}
