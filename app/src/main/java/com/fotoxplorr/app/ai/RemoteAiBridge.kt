package com.fotoxplorr.app.ai

import java.io.File

/**
 * Everything in the app that needs a network, behind one seam (WP1, FX-012).
 *
 * The offline flavor supplies an implementation whose every call fails with
 * [RemoteAiUnavailableException]; the connect flavor supplies the real one backed by
 * `:feature:ai-remote`. **No UI reads `BuildConfig.NETWORK_FEATURES` directly** — the UI
 * asks this bridge, so the answer and the behaviour cannot disagree.
 *
 * The three members are the app's complete network surface as of WP1: BYOK provider
 * calls (test + generate) and the similarity-embedder model download. The street map is
 * the fourth network feature and is handled at the source-set level instead
 * (`PhotoMapExperience`), because MapLibre does its own fetching internally and cannot be
 * routed through a suspend function.
 */
interface RemoteAiBridge {

    /** False in the offline flavor. UI uses this to explain, not merely to fail. */
    val available: Boolean

    /**
     * Sends a canary prompt to the provider and returns its reply. The callee zeroes
     * [secret] before returning, success or failure — callers must not reuse the buffer.
     */
    suspend fun testConnection(config: AiProviderConfig, secret: CharArray): Result<String>

    /** As [testConnection], for a real prompt. Same [secret] zeroing contract. */
    suspend fun generateText(config: AiProviderConfig, secret: CharArray, prompt: String): Result<String>

    /**
     * Streams [url] into [destination], reporting progress. Validation, hashing and
     * atomic replacement stay with the caller ([LocalModelManager]) — only the byte
     * transfer needs a network.
     */
    suspend fun downloadFile(
        url: String,
        destination: File,
        onProgress: (bytesRead: Long, totalBytes: Long?) -> Unit,
    ): Result<Unit>
}

/**
 * The typed "this build has no network features" failure the plan calls
 * `FeatureUnavailable(OFFLINE_BUILD)`. A distinct type so UI can tell "the offline build
 * cannot do this" (expected, explain calmly) from "the network call failed" (an error).
 */
class RemoteAiUnavailableException : Exception(
    "This is the offline build of Foto Xplorr — it ships without any network capability.",
)

/**
 * Manual constructor wiring across the flavor boundary: `AppConnectivityBindings` exists
 * with this interface's shape at the same fully-qualified name in `src/offline/` and
 * `src/connect/`. No Hilt, no reflection, no class-name loading — the compiler picks the
 * implementation when the variant picks the source set.
 */
interface ConnectivityBindings {
    val remoteAi: RemoteAiBridge
}
