package com.fotoxplorr.app.recognition

import android.graphics.Bitmap
import android.graphics.Point
import android.graphics.Rect
import com.fotoxplorr.app.textselect.Pt
import com.fotoxplorr.app.textselect.Quad
import com.fotoxplorr.app.textselect.RawBlock
import com.fotoxplorr.app.textselect.RawLine
import com.fotoxplorr.app.textselect.RawWord
import com.google.android.gms.tasks.Task
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * One word-level text pass over a bitmap: blocks of lines of words with their corners normalised to
 * 0..1 of the bitmap handed in. The seam [TextLayoutProvider] is written against, so everything
 * above it (caching, single-flight, closing) is tested without ML Kit, which cannot run under a
 * JVM test.
 */
internal interface WordRecogniser : AutoCloseable {
    suspend fun recognise(bitmap: Bitmap): List<RawBlock>

    override fun close()
}

/**
 * The bundled Latin recogniser behind [WordRecogniser]. This file is where ML Kit's text types
 * stop: past [MlKitTextAdapter] the rest of the app sees only the plain values in
 * `textselect/TextLayout.kt`.
 *
 * Latin script only (the bundled model, same options as the library index): Arabic, Hebrew, CJK,
 * Devanagari and Thai come back as nothing or as noise, and the caller says "no selectable text
 * found" for an empty result rather than implying a failure.
 */
internal class MlKitWordRecogniser : WordRecogniser {
    private val client: TextRecognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

    override suspend fun recognise(bitmap: Bitmap): List<RawBlock> {
        val width = bitmap.width
        val height = bitmap.height
        // The pass is not abandoned half way through when the caller is cancelled: the native task
        // reads the bitmap until it finishes, and the caller recycles it the moment this returns,
        // so returning early would free pixels under a running recogniser. It is a fraction of a
        // second of work; the cancellation is delivered as this block ends.
        val text = withContext(NonCancellable) { client.process(InputImage.fromBitmap(bitmap, 0)).await() }
        return MlKitTextAdapter.toRawBlocks(text, width, height)
    }

    override fun close() {
        client.close()
    }
}

/**
 * Maps ML Kit's [Text] onto [RawBlock]s. Pure apart from reading the ML Kit objects, so the
 * mapping is pinned by building those objects by hand (their constructors are public).
 */
internal object MlKitTextAdapter {

    /**
     * Every block of [text] as raw blocks, corners normalised to 0..1 of a [frameWidth] x
     * [frameHeight] bitmap, in the order ML Kit delivered them (never re-sorted).
     *
     * Each level's shape is its four corner points (top-left, top-right, bottom-right, bottom-left
     * of the TEXT, so tilted and upside-down text keep their real quad); a level that reports no
     * usable corner points falls back to its axis-aligned bounding box, and one with neither is
     * left without a shape of its own for the layout to derive from its words. A line with no
     * elements becomes one word holding the whole line, so text is never lost to a missing level.
     * Words that never got a shape are dropped: there is nowhere to select them.
     */
    fun toRawBlocks(text: Text, frameWidth: Int, frameHeight: Int): List<RawBlock> {
        if (frameWidth <= 0 || frameHeight <= 0) return emptyList()
        val fw = frameWidth.toFloat()
        val fh = frameHeight.toFloat()
        return text.textBlocks.mapNotNull { block ->
            val lines = block.lines.mapNotNull { line ->
                val lineQuad = quadOf(line.cornerPoints, line.boundingBox, fw, fh)
                val words = if (line.elements.isEmpty()) {
                    listOfNotNull(lineQuad?.let { RawWord(line.text, it, line.recognizedLanguage) })
                } else {
                    line.elements.mapNotNull { element ->
                        quadOf(element.cornerPoints, element.boundingBox, fw, fh)
                            ?.let { RawWord(element.text, it, element.recognizedLanguage) }
                    }
                }
                if (words.isEmpty()) null else RawLine(words, lineQuad, line.recognizedLanguage)
            }
            if (lines.isEmpty()) null else RawBlock(lines, quadOf(block.cornerPoints, block.boundingBox, fw, fh), block.recognizedLanguage)
        }
    }

    private fun quadOf(corners: Array<Point>?, box: Rect?, fw: Float, fh: Float): Quad? {
        if (corners != null && corners.size == 4) {
            val quad = Quad(pt(corners[0], fw, fh), pt(corners[1], fw, fh), pt(corners[2], fw, fh), pt(corners[3], fw, fh))
            if (quad.isFinite) return quad
        }
        if (box == null) return null
        return Quad.fromBounds(box.left / fw, box.top / fh, box.right / fw, box.bottom / fh)
    }

    private fun pt(p: Point, fw: Float, fh: Float) = Pt(p.x / fw, p.y / fh)
}

/** Bridges a Play-services [Task] into a cancellable suspend call. */
private suspend fun <T> Task<T>.await(): T = suspendCancellableCoroutine { continuation ->
    addOnSuccessListener { result -> if (continuation.isActive) continuation.resume(result) }
    addOnFailureListener { error -> if (continuation.isActive) continuation.resumeWithException(error) }
    addOnCanceledListener { continuation.cancel() }
}
