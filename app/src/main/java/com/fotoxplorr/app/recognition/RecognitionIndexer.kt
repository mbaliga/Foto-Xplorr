package com.fotoxplorr.app.recognition

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Rect
import androidx.core.graphics.get
import com.fotoxplorr.app.media.DecodeLimits
import com.fotoxplorr.app.media.MediaAsset
import com.fotoxplorr.app.media.decodeUpright
import com.fotoxplorr.app.palette.PaletteSampling
import com.fotoxplorr.app.palette.PaletteSwatch
import com.fotoxplorr.core.model.MediaId
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
 * without ever being written to disk. Labels and faces together also feed [SceneClassifier]
 * for the [SceneCategory] set and [CaptionGenerator] for a caption and hashtags -- both pure,
 * template-based and computed once here, per photo; see [SceneClassifier]'s KDoc for why
 * [SceneCategory.FRIENDS_FAMILY] is the one category that is NOT decided in this pass.
 *
 * The same decoded bitmap also yields the photo's colour palette ([PaletteSampling.fromBitmap],
 * over a 100px sample of it), persisted for `colour:` search and the details room. Photos
 * recognised before palettes were stored get theirs from [backfillPalettes] -- a second,
 * palette-only pass that decodes each such photo once at sample size and runs no detector.
 */
class RecognitionIndexer(
    context: Context,
    private val store: RecognitionStore,
) {
    private val appContext = context.applicationContext

    suspend fun index(assets: List<MediaAsset>): Result<Int> = withContext(Dispatchers.Default) {
        runCatching {
            val images = assets.filterNot { it.isVideo || it.isTrashed }
            // P0-12: every catalogue id, trashed included -- a trashed photo's faces/labels/OCR
            // must survive a trash-then-restore, not be deleted and redone. `pending` below still
            // excludes trashed items; only the "is this row's asset still real" check widens.
            store.removeMissing(assets.mapTo(linkedSetOf()) { it.id })
            val pending = store.pendingAssets(images)
            val recognised = if (pending.isEmpty()) {
                store.publishProgress(RecognitionProgress(running = false, indexedCount = images.size))
                0
            } else {
                recognise(pending)
            }
            // After the detectors, not before: on an upgraded install the detector pass is usually
            // empty and this is the whole of the work, but on a device that has both new photos
            // and a legacy library, new photos' faces and labels are worth more than old photos'
            // colours. Nothing to do at all on a fresh install, since analyse() samples as it goes.
            backfillPalettes(images)
            recognised
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

    /** The detector pass over [pending]. Returns how many photos were recognised successfully. */
    private suspend fun recognise(pending: List<MediaAsset>): Int {
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
                        // P0-12: previously nothing was recorded on failure, so an
                        // unreadable file stayed "pending" forever and was retried on every
                        // pass; recordFailure bounds that to MAX_FAILURE_ATTEMPTS via
                        // RecognitionStore.pendingAssets's own exclusion.
                        store.recordFailure(asset.id, asset.recognitionRevision())
                        null
                    }
                if (row != null) {
                    store.clearFailure(asset.id)
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
        return completed - failed
    }

    /**
     * Palettes for photos that were recognised before palettes were stored (store v5), and for
     * nothing else: [RecognitionStore.pendingPaletteOnly] names exactly the photos that have a
     * recognition row at their current revision and no palette rows at all.
     *
     * Deliberately NOT a re-run of [analyse]: that would decode every such photo at 640px and sit
     * it through three detectors again for faces, labels and text the store already holds. This
     * pass decodes each photo once, straight to [PaletteSampling.SAMPLE_DIMENSION] on the long
     * edge, and quantises that -- a few milliseconds of I/O and arithmetic per photo against the
     * hundreds the detector pass costs, so a 20k-photo library is a few minutes of background
     * work once, and nothing thereafter. No ML Kit client is created here; see
     * `RecognitionIndexerPaletteTest`.
     *
     * A photo that cannot be decoded is recorded through the same bounded-retry bookkeeping the
     * detector pass uses (P0-12), so a bad file costs at most [RecognitionStore]'s
     * `MAX_FAILURE_ATTEMPTS` decodes and then stops being asked. One that decodes but shows
     * nothing is stored as an empty palette and never asked again.
     *
     * Returns how many photos received a palette.
     */
    internal suspend fun backfillPalettes(images: List<MediaAsset>): Int {
        val pending = store.pendingPaletteOnly(images)
        if (pending.isEmpty()) return 0

        store.publishProgress(RecognitionProgress(running = true, completed = 0, total = pending.size))
        var completed = 0
        var failed = 0
        var lastPublishAtMs = 0L
        val batch = LinkedHashMap<MediaId, List<PaletteSwatch>>(BATCH_SIZE)
        try {
            for (asset in pending) {
                currentCoroutineContext().ensureActive()
                val palette = runCatching { PaletteSampling.read(appContext, asset) }
                    .getOrElse { error ->
                        if (error is CancellationException) throw error
                        null
                    }
                if (palette == null) {
                    failed += 1
                    store.recordFailure(asset.id, asset.recognitionRevision())
                } else {
                    batch[asset.id] = palette
                    if (batch.size >= BATCH_SIZE) {
                        store.upsertPalettes(batch.toMap())
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
            if (batch.isNotEmpty()) store.upsertPalettes(batch.toMap())
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
        return completed - failed
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

            // SceneFace is deliberately built from the raw ML Kit faces, not from `descriptors`:
            // the latter drops anything below FaceClustering.MIN_RELATIVE_AREA because a small
            // face clusters unreliably, but a small face is still perfectly good evidence that
            // this is NOT a single large official-style portrait -- exactly the distinction
            // SceneClassifier needs, so it must see every detected face, not just the usable ones.
            val sceneFaces = faces.map { face ->
                val box = face.boundingBox
                SceneFace(
                    relativeArea = (box.width().toLong() * box.height()).toFloat() / frameArea,
                    centerXFraction = (box.exactCenterX() / frameWidth).coerceIn(0f, 1f),
                    centerYFraction = (box.exactCenterY() / frameHeight).coerceIn(0f, 1f),
                )
            }
            val labelTexts = labels.filter { it.confidence >= LABEL_CONFIDENCE_FLOOR }.map { it.text }
            val categories = SceneClassifier.classify(labels, faces.size, sceneFaces)
            val generatedCaption = CaptionGenerator.generate(labelTexts, categories, faces.size)

            // From the same bitmap the detectors saw, downscaled to the palette sample size first
            // -- never quantised at analysis size. Caught on its own so that a palette problem
            // costs this photo its palette, not the faces, labels and text already in hand.
            val palette = runCatching { PaletteSampling.fromBitmap(bitmap) }.getOrDefault(emptyList())

            return AssetRecognition(
                mediaId = asset.id,
                sourceRevision = asset.recognitionRevision(),
                faceCount = faces.size,
                faceDescriptors = descriptors,
                petVerdict = PetClassifier.classify(labels),
                identityVerdict = IdentityDocumentHeuristics.classify(documentText),
                labels = labelTexts,
                textBlocks = textBlocks,
                categories = categories,
                caption = generatedCaption.caption,
                hashtags = generatedCaption.hashtags,
                palette = palette,
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

    private suspend fun loadBitmap(asset: MediaAsset): Bitmap {
        val decoded = decodeUpright(appContext, asset.contentUri, ANALYSIS_LIMITS)
            ?: error("Unable to decode ${asset.displayName}")
        return decoded.bitmap.ensureArgb8888()
    }

    private fun Bitmap.ensureArgb8888(): Bitmap {
        if (config == Bitmap.Config.ARGB_8888) return this
        return copy(Bitmap.Config.ARGB_8888, false).also { if (it !== this) recycle() }
    }

    private companion object {
        /** Analysis resolution. Large enough for ML Kit's contours, small enough to be quick. */
        const val ANALYSIS_SIZE = 640
        val ANALYSIS_LIMITS = DecodeLimits(maxLongEdge = ANALYSIS_SIZE, maxPixels = ANALYSIS_SIZE.toLong() * ANALYSIS_SIZE)

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
