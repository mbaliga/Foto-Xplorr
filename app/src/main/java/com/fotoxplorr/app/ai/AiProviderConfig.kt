package com.fotoxplorr.app.ai

import java.util.UUID

enum class AiProviderKind {
    OPENAI_RESPONSES,
    OPENAI_COMPATIBLE_CHAT,
    ANTHROPIC_MESSAGES,
    GEMINI_GENERATE_CONTENT,
}

data class AiProviderConfig(
    val id: String = UUID.randomUUID().toString(),
    val label: String,
    val kind: AiProviderKind,
    val baseUrl: String,
    val model: String,
    val enabled: Boolean = false,
    val hasSecret: Boolean = false,
    val timeoutSeconds: Int = 45,
) {
    fun normalized(): AiProviderConfig = copy(
        label = label.trim().take(80),
        baseUrl = baseUrl.trim().trimEnd('/'),
        model = model.trim().take(160),
        timeoutSeconds = timeoutSeconds.coerceIn(5, 180),
    )
}

object AiProviderPresets {
    fun openAi() = AiProviderConfig(
        label = "OpenAI",
        kind = AiProviderKind.OPENAI_RESPONSES,
        baseUrl = "https://api.openai.com",
        model = "gpt-5-mini",
    )

    fun openAiCompatible() = AiProviderConfig(
        label = "OpenAI-compatible",
        kind = AiProviderKind.OPENAI_COMPATIBLE_CHAT,
        baseUrl = "http://127.0.0.1:11434",
        model = "",
    )

    fun anthropic() = AiProviderConfig(
        label = "Anthropic",
        kind = AiProviderKind.ANTHROPIC_MESSAGES,
        baseUrl = "https://api.anthropic.com",
        model = "claude-sonnet-4-5",
    )

    fun gemini() = AiProviderConfig(
        label = "Google Gemini",
        kind = AiProviderKind.GEMINI_GENERATE_CONTENT,
        baseUrl = "https://generativelanguage.googleapis.com",
        model = "gemini-2.5-flash",
    )
}
