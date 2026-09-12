package com.fotoxplorr.app.ai

import java.io.File

/**
 * The offline flavor's bindings: every network capability answers honestly that it does
 * not exist. Secrets handed in are still zeroed — the buffer-wiping contract holds on
 * both sides of the boundary, so a caller can never learn the flavor from a leftover key
 * in memory.
 */
class AppConnectivityBindings : ConnectivityBindings {
    override val remoteAi: RemoteAiBridge = OfflineRemoteAi
}

private object OfflineRemoteAi : RemoteAiBridge {
    override val available: Boolean = false

    override suspend fun testConnection(config: AiProviderConfig, secret: CharArray): Result<String> {
        secret.fill('\u0000')
        return Result.failure(RemoteAiUnavailableException())
    }

    override suspend fun generateText(
        config: AiProviderConfig,
        secret: CharArray,
        prompt: String,
    ): Result<String> {
        secret.fill('\u0000')
        return Result.failure(RemoteAiUnavailableException())
    }

    override suspend fun downloadFile(
        url: String,
        destination: File,
        onProgress: (Long, Long?) -> Unit,
    ): Result<Unit> = Result.failure(RemoteAiUnavailableException())
}
