package com.fotoxplorr.app.recognition

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Rect
import android.os.Build
import android.util.Size
import androidx.core.graphics.get
import com.fotoxplorr.app.media.MediaAsset
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.Face
import com.google.mlkit.vision.face.FaceContour
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetector
import com.google.mlkit.vision.face.FaceDetectorOptions
import com.google.mlkit.vision.face.FaceLandmark
import com.google.mlkit.vision.label.ImageLabeler
import com.google.mlkit.vision.label.ImageLabeling
import com.google.mlkit.vision.label.defaults.ImageLabelerOptions
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import com.google.android.gms.tasks.Task

/**
 * Runs the on-device recognition pass that populates the Pets, People and Identity
 * destinations.
 *
 * Strictly local: every model used here is bundled into the APK (see the ML Kit
 * dependencies in app/build.gradle.kts), so the pass works with the network off and cannot
 * route a photo to any provider. The BYOK remote-AI path in `com.fotoxplorr.app.ai` is not
 * touched.
 *
 * Per image: one decode, three detectors. Faces produce descriptors for clustering; labels
 * produce a [PetVerdict]; OCR text is scored to an [IdentityVerdict] and then discarded
 * without ever being written to disk.
 */
class RecognitionIndexer(
    context: Context,
    private val store: RecognitionStore,
) {
    private val appContext = context.applicationContext

    suspend fun index(assets: List<MediaAsset>): Result<Int> = withContext(Dispatchers.Default) {
        runCatching {
            val images = assets.filterNot { it.isVideo || it.isTrashed }
            store.removeMissing(images.mapTo(linkedSetOf()) { it.id })
            val pending = store.pendingAssets(images)
            if (pending.isEmpty()) {
                store.publishProgress(RecognitionProgress(running = false, indexedCount = images.size))
                return@runCatching 0
            }

            store.publishProgress(RecognitionProgress(running = true, completed = 0, total = pending.size))

            val faceDetector = FaceDetection.getClient(
                FaceDetectorOptions.Builder()
                    // ACCURATE + ALL contours: the contour points are what
                    // FaceDescriptorBuilder turns into a groupable descriptor, so the cheaper
                    // FAST mode would leave People detecting faces but unable to group them.
                    .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_ACCURATE)
                    .setLandmarkMode(FaceDetectorOptions.LANDMARK_MODE_ALL)
                    .setContourMode(FaceDetectorOptions.CONTOUR_MODE_ALL)
                    .setMinFaceSize(MIN_FACE_SIZE)
                    .build(),
            )
            val labeler = ImageLabeling.getClient(
                ImageLabelerOptions.Builder()
                    .setConfidenceThreshold(LABEL_CONFIDENCE_FLOOR)
                    .build(),
            )
            val textRecognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

            var completed = 0
            var failed = 0
            val batch = ArrayList<AssetRecognition>(BATCH_SIZE)
            // Progress is THROTTLED, not published per photo.
            //
            // RecognitionProgress is a field of GalleryUiState, so every emission recomposes the
            // whole gallery, which re-derives the entire catalogue. Publishing once per asset
            // therefore cost one full re-derivation per photo -- tens of thousands of them on a
            // real library, on the main thread, while the user is trying to scroll. The progress
            // line is a human-readable count; it cannot be read faster than a few times a second,
            // so nothing is lost by rate-limiting it and a great deal is gained.
            var lastPublishAtMs = 0L
            try {
                for (asset in pending) {
                    currentCoroutineContext().ensureActive()
                    val row = runCatching { analyse(asset, faceDetector, labeler, textRecognizer) }
                        .getOrElse { error ->
                            if (error is CancellationException) throw error
                            failed += 1
                            null
                        }
                    if (row != null) {
                        batch += row
                        if (batch.size >= BATCH_SIZE) {
                            store.upsert(batch.toList())
                            batch.clear()
                        }
                    }
                    completed += 1
                    val nowMs = System.currentTimeMillis()
                    if (nowMs - lastPublishAtMs >= PROGRESS_INTERVAL_MS) {
                        lastPublishAtMs = nowMs
                        store.publishProgress(
                            RecognitionProgress(
                                running = true,
                                completed = completed,
                                total = pending.size,
                                failed = failed,
                            ),
                        )
                    }
                }
            } finally {
                if (batch.isNotEmpty()) store.upsert(batch.toList())
                runCatching { faceDetector.close() }
                runCatching { labeler.close() }
                runCatching { textRecognizer.close() }
            }

            store.reload()
            store.publishProgress(
                RecognitionProgress(
                    running = false,
                    completed = completed,
                    total = pending.size,
                    failed = failed,
                ),
            )
            completed - failed
        }.onFailure { error ->
            if (error is CancellationException) {
                store.publishProgress(RecognitionProgress(running = false))
                throw error
            }
            store.publishProgress(
                RecognitionProgress(
                    running = false,
                    message = error.message ?: "On-device recognition failed",
                ),
            )
        }
    }

    private suspend fun analyse(
        asset: MediaAsset,
        faceDetector: FaceDetector,
        labeler: ImageLabeler,
        textRecognizer: TextRecognizer,
    ): AssetRecognition {
        val bitmap = loadBitmap(asset)
        try {
            val image = InputImage.fromBitmap(bitmap, 0)
            val faces = faceDetector.process(image).await()
            val labels = labeler.process(image).await().map { ImageLabel(it.text, it.confidence) }

            // OCR now runs on EVERY image, where it used to be skipped on any frame with more than
            // a couple of faces. That skip was correct for its original purpose — deciding whether
            // something is an identity document — and wrong for the two purposes the text now
            // serves: searching for words in photos, and selecting text off a photo. A group of
            // friends in front of a shop sign has text on it, and the old rule guaranteed the app
            // would never find it. The identity heuristic keeps the face rule for itself, below.
            val recognisedText = textRecognizer.process(image).await()
            val frameWidth = bitmap.width.toFloat().coerceAtLeast(1f)
            val frameHeight = bitmap.height.toFloat().coerceAtLeast(1f)
            val textBlocks = recognisedText.textBlocks.mapNotNull { block ->
                val box = block.boundingBox ?: return@mapNotNull null
                val text = block.text.trim()
                if (text.isEmpty()) return@mapNotNull null
                TextBlock(
                    text = text,
                    left = (box.left / frameWidth).coerceIn(0f, 1f),
                    top = (box.top / frameHeight).coerceIn(0f, 1f),
                    right = (box.right / frameWidth).coerceIn(0f, 1f),
                    bottom = (box.bottom / frameHeight).coerceIn(0f, 1f),
                )
            }

            val frameArea = (bitmap.width.toLong() * bitmap.height).coerceAtLeast(1L).toFloat()
            val descriptors = faces.mapIndexedNotNull { faceIndex, face ->
                describe(face, faceIndex, asset, bitmap, frameArea)
            }

            // The face rule lives here now: a frame full of faces is a portrait, so its text is
            // not evidence of a document even though the text itself is still worth keeping.
            val documentText = if (faces.size > MAX_FACES_FOR_DOCUMENT) "" else recognisedText.text

            return AssetRecognition(
                mediaId = asset.id,
                sourceRevision = asset.recognitionRevision(),
                faceCount = faces.size,
                faceDescriptors = descriptors,
                petVerdict = PetClassifier.classify(labels),
                identityVerdict = IdentityDocumentHeuristics.classify(documentText),
                labels = labels.filter { it.confidence >= LABEL_CONFIDENCE_FLOOR }.map { it.text },
                textBlocks = textBlocks,
            )
        } finally {
            bitmap.recycle()
        }
    }

    private fun describe(
        face: Face,
        faceIndex: Int,
        asset: MediaAsset,
        bitmap: Bitmap,
        frameArea: Float,
    ): FaceDescriptor? {
        val box = face.boundingBox
        val relativeArea = (box.width().toLong() * box.height()).toFloat() / frameArea
        if (relativeArea < FaceClustering.MIN_RELATIVE_AREA) return null

        val contour = face.getContour(FaceContour.FACE)?.points.orEmpty() +
            face.getContour(FaceContour.LEFT_EYE)?.points.orEmpty() +
            face.getContour(FaceContour.RIGHT_EYE)?.points.orEmpty() +
            face.getContour(FaceContour.NOSE_BRIDGE)?.points.orEmpty() +
            face.getContour(FaceContour.UPPER_LIP_TOP)?.points.orEmpty() +
            face.getContour(FaceContour.LOWER_LIP_BOTTOM)?.points.orEmpty()

        val vector = FaceDescriptorBuilder.build(
            contour = contour.map { FacePoint(it.x, it.y) },
            leftEye = face.getLandmark(FaceLandmark.LEFT_EYE)?.position?.let { FacePoint(it.x, it.y) },
            rightEye = face.getLandmark(FaceLandmark.RIGHT_EYE)?.position?.let { FacePoint(it.x, it.y) },
            patch = grayscalePatch(bitmap, box),
        )
        if (vector.isEmpty()) return null

        return FaceDescriptor(
            mediaId = asset.id,
            faceIndex = faceIndex,
            vector = vector,
            relativeArea = relativeArea,
        )
    }

    /**
     * Samples the face box into a [PATCH_SIDE] x [PATCH_SIDE] grid of luminance values in
     * [0,1]. Nearest-neighbour on purpose: the values are averaged into an 8x8 grid straight
     * afterwards, so interpolation would cost time without changing the result.
     */
    private fun grayscalePatch(bitmap: Bitmap, box: Rect): FloatArray {
        val left = box.left.coerceIn(0, bitmap.width - 1)
        val top = box.top.coerceIn(0, bitmap.height - 1)
        val right = box.right.coerceIn(left + 1, bitmap.width)
        val bottom = box.bottom.coerceIn(top + 1, bitmap.height)
        val width = right - left
        val height = bottom - top
        if (width < PATCH_SIDE || height < PATCH_SIDE) return FloatArray(0)

        val out = FloatArray(PATCH_SIDE * PATCH_SIDE)
        for (y in 0 until PATCH_SIDE) {
            val sourceY = top + (y * height) / PATCH_SIDE
            for (x in 0 until PATCH_SIDE) {
                val sourceX = left + (x * width) / PATCH_SIDE
                val pixel = bitmap[sourceX, sourceY]
                val luminance = 0.2126f * Color.red(pixel) +
                    0.7152f * Color.green(pixel) +
                    0.0722f * Color.blue(pixel)
                out[y * PATCH_SIDE + x] = luminance / 255f
            }
        }
        return out
    }

    private fun loadBitmap(asset: MediaAsset): Bitmap {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            runCatching {
                return appContext.contentResolver
                    .loadThumbnail(asset.contentUri, Size(ANALYSIS_SIZE, ANALYSIS_SIZE), null)
                    .ensureArgb8888()
            }
        }
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        appContext.contentResolver.openInputStream(asset.contentUri)?.use {
            BitmapFactory.decodeStream(it, null, bounds)
        }
        val sample = maxOf(
            1,
            maxOf(bounds.outWidth, bounds.outHeight) / ANALYSIS_SIZE,
        )
        val options = BitmapFactory.Options().apply { inSampleSize = Integer.highestOneBit(sample) }
        val decoded = appContext.contentResolver.openInputStream(asset.contentUri)?.use {
            BitmapFactory.decodeStream(it, null, options)
        } ?: error("Unable to decode ${asset.displayName}")
        return decoded.ensureArgb8888()
    }

    private fun Bitmap.ensureArgb8888(): Bitmap {
        if (config == Bitmap.Config.ARGB_8888) return this
        return copy(Bitmap.Config.ARGB_8888, false).also { if (it !== this) recycle() }
    }

    private companion object {
        /** Analysis resolution. Large enough for ML Kit's contours, small enough to be quick. */
        const val ANALYSIS_SIZE = 640

        /** Must be a multiple of FaceDescriptorBuilder.APPEARANCE_GRID. */
        const val PATCH_SIDE = 32

        const val BATCH_SIZE = 24

        /**
         * Floor on the gap between two progress publications, in milliseconds.
         *
         * Each publication recomposes the gallery and re-derives the catalogue (see the call
         * site), so this is a frame-budget decision rather than a cosmetic one: four updates a
         * second is faster than anyone can read a changing number, and leaves the remaining
         * ~240 ms of every quarter-second free for scrolling.
         */
        const val PROGRESS_INTERVAL_MS = 250L
        const val MIN_FACE_SIZE = 0.06f
        const val LABEL_CONFIDENCE_FLOOR = 0.45f
        const val MAX_FACES_FOR_DOCUMENT = 2
    }
}

/** Bridges a Play-services [Task] into a cancellable suspend call. */
private suspend fun <T> Task<T>.await(): T = suspendCancellableCoroutine { continuation ->
    addOnSuccessListener { result -> if (continuation.isActive) continuation.resume(result) }
    addOnFailureListener { error -> if (continuation.isActive) continuation.resumeWithException(error) }
    addOnCanceledListener { continuation.cancel() }
}
