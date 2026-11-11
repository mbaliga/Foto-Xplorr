package com.fotoxplorr.app.palettebuilder

import android.app.Activity
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import androidx.core.content.FileProvider
import com.fotoxplorr.app.share.fileProviderAuthority
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

/** How the palette leaves the app: the system share sheet, nothing else. The screen holds this so a test gives it a fake. */
internal interface PaletteShare {
    /** Opens the share sheet with [palette] as text in [format]; false when nothing could be started. */
    fun text(palette: Palette, format: ExportFormat): Boolean

    /**
     * Opens the share sheet with [palette] as a swatch card picture; false when it could not be made or started, or
     * when [stillAllowed] said no once the card was drawn (the photos behind the palette were protected meanwhile):
     * the card is then deleted, never offered.
     */
    suspend fun card(palette: Palette, stillAllowed: () -> Boolean): Boolean
}

/**
 * [PaletteShare] over the platform: a plain send intent. Text goes as the intent's own text (no file, no
 * permission); the card is a PNG in this app's own cache folder, handed out through the app's file provider
 * with a read grant for that one file, the way a sticker is shared. No storage or other permission is asked.
 *
 * The card folder is [CARD_DIRECTORY], which must match a `cache-path` in `res/xml/file_paths.xml`
 * (`PaletteCardDirectoryTest` reads both). Each card goes in a folder of its own named by time, so a share
 * sheet still open on an earlier card is never written over; folders older than a day are swept at the start of
 * the next card. The file is written under a temporary name and renamed, so a card is whole or absent.
 *
 * Nothing here has run on a device: what a receiving app does with the text or the picture is theirs.
 */
internal class AndroidPaletteShare(private val context: Context) : PaletteShare {
    private val appContext = context.applicationContext

    override fun text(palette: Palette, format: ExportFormat): Boolean {
        val intent = textIntent(palette, format) ?: return false
        return launch(intent)
    }

    override suspend fun card(palette: Palette, stillAllowed: () -> Boolean): Boolean {
        val uri = withContext(Dispatchers.IO) {
            runCatching {
                val file = writeCard(File(appContext.cacheDir, CARD_DIRECTORY), palette, System.currentTimeMillis())
                if (!stillAllowed()) {
                    file.delete()
                    null
                } else {
                    FileProvider.getUriForFile(appContext, fileProviderAuthority(appContext), file)
                }
            }.getOrNull()
        } ?: return false
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = CARD_MIME
            putExtra(Intent.EXTRA_STREAM, uri)
            // The grant must also ride the clip, or a chooser started from an activity may not pass it on.
            clipData = ClipData.newRawUri(palette.name, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        return launch(intent)
    }

    private fun launch(intent: Intent): Boolean {
        val chooser = Intent.createChooser(intent, PaletteBuilderCopy.SHARE_CHOOSER)
        // From an application context a task flag is required; from an activity it is not wanted.
        if (context !is Activity) chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return runCatching { context.startActivity(chooser) }.isSuccess
    }

    companion object {
        const val CARD_DIRECTORY = "palette-cards"
        const val CARD_MIME = "image/png"
        const val CARD_MAX_AGE_MILLIS = 24L * 60 * 60 * 1000

        /** The send intent for a palette as text, or null for the card format (a picture, not text). */
        fun textIntent(palette: Palette, format: ExportFormat): Intent? {
            val body = PaletteExport.text(palette, format) ?: return null
            return Intent(Intent.ACTION_SEND).apply {
                // Plain text for every format: the receiver gets the words, and the subject names the file it would be.
                type = "text/plain"
                putExtra(Intent.EXTRA_SUBJECT, PaletteExport.fileName(palette, format))
                putExtra(Intent.EXTRA_TEXT, body)
            }
        }

        /**
         * Draws [palette]'s card into a new PNG under [directory]/[nowMillis]/, after sweeping folders older than
         * [CARD_MAX_AGE_MILLIS]. Returns the file. Throws [IOException] if it cannot be written; nothing partial is left.
         */
        internal fun writeCard(directory: File, palette: Palette, nowMillis: Long): File {
            sweep(directory, nowMillis)
            val folder = File(directory, nowMillis.toString()).apply {
                if (!mkdirs() && !isDirectory) throw IOException("Could not prepare card storage")
            }
            val target = File(folder, PaletteExport.fileName(palette, ExportFormat.CARD))
            val temp = File(folder, target.name + ".tmp")
            val bitmap = SwatchCardBitmap.render(SwatchCard.layout(palette))
            try {
                FileOutputStream(temp).buffered().use { out ->
                    if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)) throw IOException("Could not encode the card")
                }
                if (!temp.renameTo(target)) throw IOException("Could not finish the card")
            } catch (t: Throwable) {
                temp.delete()
                target.delete()
                throw t
            } finally {
                bitmap.recycle()
            }
            return target
        }

        private fun sweep(directory: File, nowMillis: Long) {
            for (folder in directory.listFiles().orEmpty()) {
                if (!folder.isDirectory || nowMillis - folder.lastModified() <= CARD_MAX_AGE_MILLIS) continue
                folder.listFiles().orEmpty().forEach { it.delete() }
                folder.delete()
            }
        }
    }
}

/**
 * Paints a [SwatchCardSpec] into a bitmap. The layout (every position and size) is [SwatchCard]'s and is
 * tested on a JVM; this adds the one thing it cannot know, the width of text in a real font: the title and each
 * hex label are shrunk by [fittedSize] until they fit their space. Nothing here has been looked at on a device,
 * so how the title baseline and the labels sit is a device check.
 */
internal object SwatchCardBitmap {
    fun render(spec: SwatchCardSpec): Bitmap {
        val bitmap = Bitmap.createBitmap(spec.widthPx, spec.heightPx, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(spec.backgroundArgb)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Typeface.DEFAULT_BOLD }

        paint.color = spec.titleArgb
        paint.textSize = fittedSize(spec.titleSizePx, (spec.widthPx - 2 * spec.titleX).toFloat()) { size ->
            paint.textSize = size
            paint.measureText(spec.title)
        }
        canvas.drawText(spec.title, spec.titleX.toFloat(), spec.titleBaselineY.toFloat(), paint)

        for (cell in spec.cells) {
            paint.color = cell.argb
            canvas.drawRect(cell.left.toFloat(), cell.top.toFloat(), cell.right.toFloat(), cell.bottom.toFloat(), paint)
            paint.color = cell.labelArgb
            paint.textAlign = Paint.Align.CENTER
            paint.textSize = fittedSize(cell.labelSizePx, cell.width * LABEL_SHARE) { size ->
                paint.textSize = size
                paint.measureText(cell.label)
            }
            canvas.drawText(cell.label, (cell.left + cell.right) / 2f, cell.bottom - cell.height * LABEL_FROM_BOTTOM, paint)
            paint.textAlign = Paint.Align.LEFT
        }
        return bitmap
    }

    /** How much of a block's width its label may use. */
    private const val LABEL_SHARE = 0.9f

    /** How far above a block's bottom edge its label's baseline sits, as a share of the block's height. */
    private const val LABEL_FROM_BOTTOM = 0.1f

    /** The smallest text size [fittedSize] goes to; below it the text is drawn at this size and may run over. */
    private const val SMALLEST = 6f

    /**
     * [wanted] shrunk until [widthAt] (the text's width at a size) is within [maxWidth], never below [SMALLEST].
     * Pure given a pure [widthAt], so a test holds it with a width that grows with the size.
     */
    internal fun fittedSize(wanted: Float, maxWidth: Float, widthAt: (Float) -> Float): Float {
        var size = wanted.coerceAtLeast(SMALLEST)
        var guard = 0
        while (size > SMALLEST && widthAt(size) > maxWidth && guard++ < 64) {
            size = (size * 0.92f).coerceAtLeast(SMALLEST)
        }
        return size
    }
}
