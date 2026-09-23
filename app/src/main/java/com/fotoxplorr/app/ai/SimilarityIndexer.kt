package com.fotoxplorr.app.ai

import android.content.Context
import android.graphics.Bitmap
import com.fotoxplorr.app.media.DecodeLimits
import com.fotoxplorr.app.media.MediaAsset
import com.fotoxplorr.app.media.decodeUpright
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.vision.imageembedder.ImageEmbedder
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.File

sealed interface SimilarityIndexingState {
    data object Idle : SimilarityIndexingState
    data class Preparing(val alreadyIndexed: Int, val missing: Int) : SimilarityIndexingState
    data class Running(
        val completed: Int,
        val total: Int,
        val failed: Int,
        val currentName: String,
    ) : SimilarityIndexingState
    data class LayingOut(val indexedCount: Int) : SimilarityIndexingState
    data class Complete(val indexedCount: Int, val failed: Int) : SimilarityIndexingState
    data class Failed(val message: String) : SimilarityIndexingState
}

class SimilarityIndexer(
    context: Context,
    private val repository: EmbeddingRepository,
) {
    private val appContext = context.applicationContext
    private val state = MutableStateFlow<SimilarityIndexingState>(SimilarityIndexingState.Idle)

    fun observe(): StateFlow<SimilarityIndexingState> = state.asStateFlow()

    suspend fun index(
        assets: List<MediaAsset>,
        model: LocalModelState.Ready,
    ): Result<Int> = withContext(Dispatchers.Default) {
        runCatching {
            val imageAssets = assets.filterNot { it.isVideo || it.isTrashed }
            repository.removeMissing(imageAssets.mapTo(linkedSetOf()) { it.id })
            val missing = repository.missingAssets(imageAssets, model.sha256)
            val existing = imageAssets.size - missing.size
            state.value = SimilarityIndexingState.Preparing(existing, missing.size)

            var completed = 0
            var failed = 0
            val batch = ArrayList<StoredEmbedding>(BATCH_SIZE)

            ImageEmbedder.createFromFile(appContext, model.file).use { embedder ->
                missing.forEach { asset ->
                    currentCoroutineContext().ensureActive()
                    state.value = SimilarityIndexingState.Running(
                        completed = completed,
                        total = missing.size,
                        failed = failed,
                        currentName = asset.displayName,
                    )
                    val embedding = runCatching { embedAsset(embedder, asset, model.sha256) }.getOrNull()
                    if (embedding == null) {
                        failed += 1
                    } else {
                        batch += embedding
                        if (batch.size >= BATCH_SIZE) {
                            repository.upsertBatch(batch.toList())
                            batch.clear()
                        }
                    }
                    completed += 1
                }
            }
            if (batch.isNotEmpty()) repository.upsertBatch(batch)

            val all = repository.readAll(model.sha256)
            state.value = SimilarityIndexingState.LayingOut(all.size)
            repository.updateLayout(model.sha256, SimilarityLayout.project(all))
            state.value = SimilarityIndexingState.Complete(all.size, failed)
            all.size
        }.onFailure { error ->
            if (error is CancellationException) {
                state.value = SimilarityIndexingState.Idle
                throw error
            }
            state.value = SimilarityIndexingState.Failed(error.message ?: "Local similarity indexing failed")
        }
    }

    private suspend fun embedAsset(
        embedder: ImageEmbedder,
        asset: MediaAsset,
        modelSha: String,
    ): StoredEmbedding {
        val bitmap = loadThumbnail(asset)
        return try {
            val image = BitmapImageBuilder(bitmap).build()
            try {
                val vector = embedder.embed(image)
                    .embeddingResult()
                    .embeddings()
                    .firstOrNull()
                    ?.floatEmbedding()
                    ?: error("Embedding model returned no vector")
                val compact = EmbeddingRepository.quantize(vector)
                require(compact.isNotEmpty()) { "Embedding model returned an empty vector" }
                StoredEmbedding(
                    mediaId = asset.id,
                    sourceRevision = asset.sourceRevision(),
                    modelSha256 = modelSha,
                    vector = compact,
                    signature = EmbeddingRepository.signature(compact),
                    x = null,
                    y = null,
                )
            } finally {
                image.close()
            }
        } finally {
            bitmap.recycle()
        }
    }

    /**
     * P0-03: previously the pre-Q fallback here decoded the source at full resolution with no
     * [BitmapFactory.Options] at all before force-fitting down to [EMBED_SIZE] -- "the fallback
     * has no bound today", per the brief. [decodeUpright] now bounds the decode itself; the
     * embedding model still wants an exact [EMBED_SIZE] x [EMBED_SIZE] square (not this app's
     * usual aspect-preserving budget), so the force-fit stretch below is unchanged.
     */
    private suspend fun loadThumbnail(asset: MediaAsset): Bitmap {
        val decoded = decodeUpright(appContext, asset.contentUri, EMBED_DECODE_LIMITS)
            ?: error("Unable to decode ${asset.displayName}")
        val bitmap = decoded.bitmap
        val scaled = if (bitmap.width == EMBED_SIZE && bitmap.height == EMBED_SIZE) {
            bitmap
        } else {
            Bitmap.createScaledBitmap(bitmap, EMBED_SIZE, EMBED_SIZE, true).also {
                if (it !== bitmap) bitmap.recycle()
            }
        }
        return scaled.ensureArgb8888()
    }

    private fun Bitmap.ensureArgb8888(): Bitmap {
        if (config == Bitmap.Config.ARGB_8888) return this
        return copy(Bitmap.Config.ARGB_8888, false).also { if (it !== this) recycle() }
    }

    private fun MediaAsset.sourceRevision(): Long =
        (dateModifiedSeconds shl 17) xor sizeBytes xor width.toLong().shl(9) xor height.toLong()

    private companion object {
        const val EMBED_SIZE = 224
        val EMBED_DECODE_LIMITS = DecodeLimits(maxLongEdge = EMBED_SIZE, maxPixels = EMBED_SIZE.toLong() * EMBED_SIZE)
        const val BATCH_SIZE = 32
    }
}
