package com.fotoxplorr.app.wall

import android.graphics.Bitmap
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.pdf.PdfDocument
import android.os.Bundle
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.print.PageRange
import android.print.PrintAttributes
import android.print.PrintDocumentAdapter
import android.print.PrintDocumentInfo
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.FileOutputStream
import java.io.OutputStream
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** Where a picture goes on a page, in PDF points (1/72 in) from the page's top left. [shrunk] when it did not fit at real size. */
internal data class PagePlacement(val leftPt: Float, val topPt: Float, val widthPt: Float, val heightPt: Float, val shrunk: Boolean)

/** The page maths of the system print path. Pure. */
internal object PrintPage {
    const val PT_PER_INCH = 72f
    private const val MILS_PER_INCH = 1000f

    /**
     * What a printer cannot reach at a sheet's edge (most cut 3 to 5 mm), kept clear: 1 cm. A picture
     * counts as fitting only inside the page less this on each side, so one as big as the paper is
     * shrunk too instead of printed edge to edge.
     */
    const val FIT_MARGIN_PT = 28.35f

    /** A4, for a print dialog that gave no paper size. */
    const val A4_WIDTH_PT = 595f
    const val A4_HEIGHT_PT = 842f

    fun milsToPt(mils: Int): Float = mils * PT_PER_INCH / MILS_PER_INCH

    fun cmToPt(cm: Double): Float = (cm / PrintMath.CM_PER_INCH * PT_PER_INCH).toFloat()

    fun ptToCm(pt: Float): Double = pt / PT_PER_INCH * PrintMath.CM_PER_INCH

    /**
     * The picture at its real size, centred, when it fits inside the page less [FIT_MARGIN_PT] on
     * each side; otherwise the whole picture scaled down to fit inside that, centred. The
     * shape is kept either way. The picture is never turned: the person picks the paper's
     * orientation in the print dialog.
     */
    fun place(pageWidthPt: Float, pageHeightPt: Float, picture: PrintSize): PagePlacement {
        val w = cmToPt(picture.wCm)
        val h = cmToPt(picture.hCm)
        val fits = w <= pageWidthPt - 2 * FIT_MARGIN_PT && h <= pageHeightPt - 2 * FIT_MARGIN_PT
        val scale = if (fits) {
            1f
        } else {
            min((pageWidthPt - 2 * FIT_MARGIN_PT) / w, (pageHeightPt - 2 * FIT_MARGIN_PT) / h).coerceAtLeast(MIN_SCALE)
        }
        val pw = w * scale
        val ph = h * scale
        return PagePlacement((pageWidthPt - pw) / 2, (pageHeightPt - ph) / 2, pw, ph, shrunk = !fits)
    }

    private const val MIN_SCALE = 0.01f
}

/**
 * Hands one photo to the platform's print dialog as a one-page PDF at the real paper size, with
 * the picture on it at its real size when the paper is big enough ([PrintPage.place]). The PDF is
 * made in [onWrite], once per time the dialog asks for it, on a worker thread; nothing is kept
 * between asks, and no file is left behind (the system gives a descriptor to write into).
 *
 * [renderPicture] is told the size the picture will have on the page and must hand the page's
 * bitmap to its callback and recycle it after: in the app that is [PrintExporter.render], which
 * checks again that the photo may still be exported on every ask. After [close] (the screen was
 * left) every ask fails with a sentence instead of reading a photo whose live check is gone.
 *
 * Nothing here has run on a device: the PDF's size, the preview and the output of a real printer
 * are device checks.
 */
internal class PhotoPrintAdapter(
    private val jobName: String,
    private val size: PrintSize,
    private val renderPicture: suspend (placed: PrintSize, use: suspend (Bitmap) -> Unit) -> Unit,
) : PrintDocumentAdapter() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @Volatile private var closed = false

    @Volatile private var pageWidthPt = PrintPage.A4_WIDTH_PT

    @Volatile private var pageHeightPt = PrintPage.A4_HEIGHT_PT

    /** Stops any work in flight and refuses further asks. */
    fun close() {
        closed = true
        scope.cancel()
    }

    override fun onLayout(
        oldAttributes: PrintAttributes?,
        newAttributes: PrintAttributes,
        cancellationSignal: CancellationSignal,
        callback: LayoutResultCallback,
        extras: Bundle?,
    ) {
        if (cancellationSignal.isCanceled) {
            callback.onLayoutCancelled()
            return
        }
        if (closed) {
            callback.onLayoutFailed(WallCopy.PRINT_CLOSED)
            return
        }
        newAttributes.mediaSize?.let {
            pageWidthPt = PrintPage.milsToPt(it.widthMils)
            pageHeightPt = PrintPage.milsToPt(it.heightMils)
        }
        val info = PrintDocumentInfo.Builder(jobName)
            .setContentType(PrintDocumentInfo.CONTENT_TYPE_PHOTO)
            .setPageCount(1)
            .build()
        callback.onLayoutFinished(info, oldAttributes != newAttributes)
    }

    override fun onWrite(
        pages: Array<out PageRange>,
        destination: ParcelFileDescriptor,
        cancellationSignal: CancellationSignal,
        callback: WriteResultCallback,
    ) {
        if (closed) {
            callback.onWriteFailed(WallCopy.PRINT_CLOSED)
            return
        }
        val job: Job = scope.launch {
            try {
                writePage(destination)
                withContext(Dispatchers.Main) { callback.onWriteFinished(arrayOf(PageRange.ALL_PAGES)) }
            } catch (e: CancellationException) {
                withContext(Dispatchers.Main + NonCancellable) { callback.onWriteCancelled() }
                throw e
            } catch (e: Throwable) {
                val message = (e as? PrintRefusedException ?: e as? PrintFailedException)?.message ?: WallCopy.PRINT_FAILED
                withContext(Dispatchers.Main) { callback.onWriteFailed(message) }
            }
        }
        cancellationSignal.setOnCancelListener { job.cancel() }
    }

    private suspend fun writePage(destination: ParcelFileDescriptor) {
        val pageW = pageWidthPt
        val pageH = pageHeightPt
        val placement = PrintPage.place(pageW, pageH, size)
        val placed = PrintSize(PrintPage.ptToCm(placement.widthPt), PrintPage.ptToCm(placement.heightPt))
        renderPicture(placed) { bitmap ->
            // The descriptor is the system's: flushed, not closed.
            val out = FileOutputStream(destination.fileDescriptor)
            writePhotoPdf(out, pageW, pageH, placement, bitmap)
            out.flush()
        }
    }
}

/**
 * A one-page PDF of [pageWidthPt] x [pageHeightPt] points with [bitmap] drawn at [placement], onto [out]. Not closed.
 */
internal fun writePhotoPdf(out: OutputStream, pageWidthPt: Float, pageHeightPt: Float, placement: PagePlacement, bitmap: Bitmap) {
    val pdf = PdfDocument()
    try {
        val info = PdfDocument.PageInfo.Builder(max(1, pageWidthPt.roundToInt()), max(1, pageHeightPt.roundToInt()), 1).create()
        val page = pdf.startPage(info)
        val target = RectF(placement.leftPt, placement.topPt, placement.leftPt + placement.widthPt, placement.topPt + placement.heightPt)
        page.canvas.drawBitmap(bitmap, null, target, Paint(Paint.FILTER_BITMAP_FLAG))
        pdf.finishPage(page)
        pdf.writeTo(out)
    } finally {
        pdf.close()
    }
}
