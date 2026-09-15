package com.fotoxplorr.app.ai

import com.fotoxplorr.feature.airemote.RemoteFileDownloader
import com.fotoxplorr.feature.airemote.RemoteProtocol
import com.fotoxplorr.feature.airemote.RemoteProviderHttp
import java.io.File

/**
 * The connect flavor's bindings: the real network implementations from
 * `:feature:ai-remote`, adapted from app types to the module's wire-protocol primitives.
 * The module never sees `AiProviderConfig` — it must not depend on the app — so this
 * adapter is where the two enums meet.
 */
class AppConnectivityBindings : ConnectivityBindings {
    override val remoteAi: RemoteAiBridge = ConnectRemoteAi()
}

private class ConnectRemoteAi : RemoteAiBridge {
    private val http = RemoteProviderHttp()
    private val downloader = RemoteFileDownloader()

    override val available: Boolean = true

    override suspend fun testConnection(config: AiProviderConfig, secret: CharArray): Result<String> =
        generateText(config, secret, "Reply with the single word OK.")

    override suspend fun generateText(
        config: AiProviderConfig,
        secret: CharArray,
        prompt: String,
    ): Result<String> {
        val normalized = config.normalized()
        return try {
            http.generateText(
                protocol = normalized.kind.toProtocol(),
                baseUrl = normalized.baseUrl,
                model = normalized.model,
                timeoutSeconds = normalized.timeoutSeconds,
                secret = secret,
                prompt = prompt,
            )
        } finally {
            // The bridge contract: the secret buffer dies here, success or failure.
            secret.fill('\u0000')
        }
    }

    override suspend fun downloadFile(
        url: String,
        destination: File,
        onProgress: (Long, Long?) -> Unit,
    ): Result<Unit> = downloader.download(url, destination, onProgress)

    private fun AiProviderKind.toProtocol(): RemoteProtocol = when (this) {
        AiProviderKind.OPENAI_RESPONSES -> RemoteProtocol.OPENAI_RESPONSES
        AiProviderKind.OPENAI_COMPATIBLE_CHAT -> RemoteProtocol.OPENAI_COMPATIBLE_CHAT
        AiProviderKind.ANTHROPIC_MESSAGES -> RemoteProtocol.ANTHROPIC_MESSAGES
        AiProviderKind.GEMINI_GENERATE_CONTENT -> RemoteProtocol.GEMINI_GENERATE_CONTENT
    }
}
