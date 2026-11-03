package com.fotoxplorr.app.services

import com.fotoxplorr.app.ai.LocalModelState
import java.util.Locale

/**
 * One on-device model the app really supports. The facts here are the app's only registry of
 * models: [com.fotoxplorr.app.ai.LocalModelManager] reads [sourceUrl] from here, so the URL it
 * downloads and the host the screen names cannot disagree.
 *
 * Today there is exactly ONE such model. Face, label and text recognition use ML Kit models that
 * ship inside the app, the map fetches its own tiles, and the Connect-only translator lets ML Kit
 * fetch its language packs itself; none of those are files this app manages, so none are listed.
 *
 * @param approxBytes the published size, to show BEFORE a download starts. It is a stated figure,
 *   not a measurement made by this build; once installed the real size on disk is shown instead.
 */
data class ModelSpec(
    val id: String,
    val title: String,
    val enables: String,
    val approxBytes: Long,
    val licence: String,
    val host: String,
    val sourceUrl: String,
)

/** What the row says about one model right now. [Downloading.progress] is null when the size is unknown. */
sealed interface ModelEntryState {
    data object NotInstalled : ModelEntryState
    data class Downloading(val progress: Float?) : ModelEntryState
    data class Installed(val sizeBytes: Long, val sha256: String, val source: String) : ModelEntryState

    /** Installed from a different web address than the one the app now recommends. */
    data class Update(val sizeBytes: Long, val sha256: String, val source: String) : ModelEntryState
    data class Failed(val message: String) : ModelEntryState
}

/** [canDownload] is false in the Offline flavor; [canImport] is true in both. */
data class ModelEntry(
    val spec: ModelSpec,
    val state: ModelEntryState,
    val canDownload: Boolean,
    val canImport: Boolean = true,
) {
    val installed: Boolean get() = state is ModelEntryState.Installed || state is ModelEntryState.Update
    val busy: Boolean get() = state is ModelEntryState.Downloading
    val installedBytes: Long
        get() = when (state) {
            is ModelEntryState.Installed -> state.sizeBytes
            is ModelEntryState.Update -> state.sizeBytes
            else -> 0L
        }
}

/** Whether a tap on "Get" may start at once, must ask first, or cannot run in this build. */
enum class DownloadDecision { START, CONFIRM_METERED, UNAVAILABLE }

object ModelCatalogue {

    /** MediaPipe's MobileNet V3 (small) image embedder: the model behind "find look-alike photos". */
    val IMAGE_EMBEDDER = ModelSpec(
        id = "image-embedder-mobilenet-v3-small",
        title = "Look-alike photos",
        enables = "Finds photos that look alike and lays them out by similarity.",
        approxBytes = 10L * 1024L * 1024L,
        licence = "Apache 2.0",
        host = "storage.googleapis.com",
        sourceUrl = "https://storage.googleapis.com/mediapipe-models/image_embedder/" +
            "mobilenet_v3_small/float32/1/mobilenet_v3_small.tflite",
    )

    val all: List<ModelSpec> = listOf(IMAGE_EMBEDDER)

    /** The manager holds one model slot, so [state] describes [IMAGE_EMBEDDER]. */
    fun entries(state: LocalModelState, canDownload: Boolean): List<ModelEntry> =
        listOf(ModelEntry(IMAGE_EMBEDDER, entryState(IMAGE_EMBEDDER, state), canDownload))

    fun entryState(spec: ModelSpec, state: LocalModelState): ModelEntryState = when (state) {
        LocalModelState.NotInstalled -> ModelEntryState.NotInstalled
        is LocalModelState.Downloading -> ModelEntryState.Downloading(progressOf(state.bytesRead, state.totalBytes))
        is LocalModelState.Failed -> ModelEntryState.Failed(state.message)
        is LocalModelState.Ready -> {
            val outdated = state.source.startsWith("http") && state.source != spec.sourceUrl
            if (outdated) {
                ModelEntryState.Update(state.sizeBytes, state.sha256, state.source)
            } else {
                ModelEntryState.Installed(state.sizeBytes, state.sha256, state.source)
            }
        }
    }

    fun progressOf(bytesRead: Long, totalBytes: Long?): Float? {
        val total = totalBytes ?: return null
        if (total <= 0L) return null
        return (bytesRead.toFloat() / total).coerceIn(0f, 1f)
    }

    /**
     * An explicit tap never starts a download over mobile data without asking; Wi-Fi starts at once.
     * [metered] is "the active network is metered" (mobile data, or a Wi-Fi marked metered).
     */
    fun downloadDecision(canDownload: Boolean, metered: Boolean): DownloadDecision = when {
        !canDownload -> DownloadDecision.UNAVAILABLE
        metered -> DownloadDecision.CONFIRM_METERED
        else -> DownloadDecision.START
    }

    fun storageUsedBytes(entries: List<ModelEntry>): Long = entries.sumOf { it.installedBytes }

    /** "Models use 9.8 MB." or "No models installed." */
    fun storageLine(entries: List<ModelEntry>): String {
        val used = storageUsedBytes(entries)
        return if (used <= 0L) ServicesCopy.STORAGE_NONE else ServicesCopy.storageUsed(formatSize(used))
    }

    /** 1024-based, one decimal from MB up, so "9.8 MB" and "512 KB". Never negative. */
    fun formatSize(bytes: Long): String {
        val b = bytes.coerceAtLeast(0L)
        val kb = 1024.0
        return when {
            b < 1024L -> "$b B"
            b < 1024L * 1024L -> "${(b / kb).toInt()} KB"
            b < 1024L * 1024L * 1024L -> String.format(Locale.US, "%.1f MB", b / (kb * kb))
            else -> String.format(Locale.US, "%.1f GB", b / (kb * kb * kb))
        }
    }
}
