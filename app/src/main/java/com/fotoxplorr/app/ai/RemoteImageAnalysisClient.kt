package com.fotoxplorr.app.ai

import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.URI
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume

data class AiRequestPreview(
    val providerLabel: String,
    val endpoint: String,
    val model: String,
    val promptCharacters: Int,
    val imageMimeType: String,
    val imageBytes: Int,
    val imageWidth: Int,
    val imageHeight: Int,
)

class RemoteImageAnalysisClient {
    fun preview(
        config: AiProviderConfig,
        prompt: String,
        image: PreparedAiImage,
    ): AiRequestPreview = AiRequestPreview(
        providerLabel = config.label,
        endpoint = endpoint(config),
        model = config.model,
        promptCharacters = prompt.length,
        imageMimeType = image.mimeType,
        imageBytes = image.byteCount,
        imageWidth = image.width,
        imageHeight = image.height,
    )

    suspend fun analyze(
        config: AiProviderConfig,
        secret: CharArray,
        prompt: String,
        image: PreparedAiImage,
    ): Result<String> = runCatching {
        val normalized = config.normalized()
        require(normalized.enabled) { "This provider is disabled" }
        require(prompt.isNotBlank()) { "A question or instruction is required" }
        require(isSecureOrLocal(normalized.baseUrl)) {
            "Provider keys may only be sent over HTTPS or to a local/private-network endpoint"
        }
        require(secret.isNotEmpty()) { "No encrypted provider key is available" }

        val request = buildRequest(normalized, secret, prompt, image)
        client(normalized.timeoutSeconds).executeCancellable(request).use { response ->
            val raw = response.body.string()
            if (!response.isSuccessful) {
                val message = runCatching {
                    JSONObject(raw).optJSONObject("error")?.optString("message")
                }.getOrNull().orEmpty()
                error(if (message.isBlank()) "Provider returned HTTP ${response.code}" else message.take(320))
            }
            parseText(normalized.kind, raw).takeIf(String::isNotBlank)
                ?: error("Provider returned no text")
        }
    }.also {
        secret.fill('\u0000')
    }

    private fun buildRequest(
        config: AiProviderConfig,
        secret: CharArray,
        prompt: String,
        image: PreparedAiImage,
    ): Request {
        val token = String(secret)
        val dataUrl = "data:${image.mimeType};base64,${image.base64Data}"
        val body = when (config.kind) {
            AiProviderKind.OPENAI_RESPONSES -> JSONObject().apply {
                put("model", config.model)
                put("max_output_tokens", 800)
                put("input", JSONArray().put(JSONObject().apply {
                    put("role", "user")
                    put("content", JSONArray()
                        .put(JSONObject().put("type", "input_text").put("text", prompt))
                        .put(JSONObject().put("type", "input_image").put("image_url", dataUrl)))
                }))
            }
            AiProviderKind.OPENAI_COMPATIBLE_CHAT -> JSONObject().apply {
                put("model", config.model)
                put("max_tokens", 800)
                put("temperature", 0.2)
                put("messages", JSONArray().put(JSONObject().apply {
                    put("role", "user")
                    put("content", JSONArray()
                        .put(JSONObject().put("type", "text").put("text", prompt))
                        .put(JSONObject().put("type", "image_url").put(
                            "image_url",
                            JSONObject().put("url", dataUrl),
                        )))
                }))
            }
            AiProviderKind.ANTHROPIC_MESSAGES -> JSONObject().apply {
                put("model", config.model)
                put("max_tokens", 800)
                put("messages", JSONArray().put(JSONObject().apply {
                    put("role", "user")
                    put("content", JSONArray()
                        .put(JSONObject().apply {
                            put("type", "image")
                            put("source", JSONObject().apply {
                                put("type", "base64")
                                put("media_type", image.mimeType)
                                put("data", image.base64Data)
                            })
                        })
                        .put(JSONObject().put("type", "text").put("text", prompt)))
                }))
            }
            AiProviderKind.GEMINI_GENERATE_CONTENT -> JSONObject().apply {
                put("contents", JSONArray().put(JSONObject().apply {
                    put("parts", JSONArray()
                        .put(JSONObject().apply {
                            put("inline_data", JSONObject().apply {
                                put("mime_type", image.mimeType)
                                put("data", image.base64Data)
                            })
                        })
                        .put(JSONObject().put("text", prompt)))
                }))
                put("generationConfig", JSONObject().put("maxOutputTokens", 800))
            }
        }

        return Request.Builder()
            .url(endpoint(config))
            .post(body.toString().toRequestBody(JSON))
            .header("Accept", "application/json")
            .apply {
                when (config.kind) {
                    AiProviderKind.OPENAI_RESPONSES,
                    AiProviderKind.OPENAI_COMPATIBLE_CHAT,
                    -> header("Authorization", "Bearer $token")
                    AiProviderKind.ANTHROPIC_MESSAGES -> {
                        header("x-api-key", token)
                        header("anthropic-version", "2023-06-01")
                    }
                    AiProviderKind.GEMINI_GENERATE_CONTENT -> header("x-goog-api-key", token)
                }
            }
            .build()
    }

    private fun endpoint(config: AiProviderConfig): String = when (config.kind) {
        AiProviderKind.OPENAI_RESPONSES -> "${config.baseUrl.trimEnd('/')}/v1/responses"
        AiProviderKind.OPENAI_COMPATIBLE_CHAT -> "${config.baseUrl.trimEnd('/')}/v1/chat/completions"
        AiProviderKind.ANTHROPIC_MESSAGES -> "${config.baseUrl.trimEnd('/')}/v1/messages"
        AiProviderKind.GEMINI_GENERATE_CONTENT ->
            "${config.baseUrl.trimEnd('/')}/v1beta/models/${config.model}:generateContent"
    }

    private fun parseText(kind: AiProviderKind, raw: String): String {
        val root = JSONObject(raw)
        return when (kind) {
            AiProviderKind.OPENAI_RESPONSES -> root.optString("output_text").takeIf(String::isNotBlank)
                ?: root.optJSONArray("output")
                    ?.optJSONObject(0)
                    ?.optJSONArray("content")
                    ?.optJSONObject(0)
                    ?.optString("text")
                    .orEmpty()
            AiProviderKind.OPENAI_COMPATIBLE_CHAT -> root.optJSONArray("choices")
                ?.optJSONObject(0)
                ?.optJSONObject("message")
                ?.optString("content")
                .orEmpty()
            AiProviderKind.ANTHROPIC_MESSAGES -> root.optJSONArray("content")
                ?.optJSONObject(0)
                ?.optString("text")
                .orEmpty()
            AiProviderKind.GEMINI_GENERATE_CONTENT -> root.optJSONArray("candidates")
                ?.optJSONObject(0)
                ?.optJSONObject("content")
                ?.optJSONArray("parts")
                ?.optJSONObject(0)
                ?.optString("text")
                .orEmpty()
        }
    }

    private fun client(timeoutSeconds: Int): OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(timeoutSeconds.toLong(), TimeUnit.SECONDS)
        .readTimeout(timeoutSeconds.toLong(), TimeUnit.SECONDS)
        .writeTimeout(timeoutSeconds.toLong(), TimeUnit.SECONDS)
        .callTimeout((timeoutSeconds * 2L).coerceAtMost(240L), TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    private suspend fun OkHttpClient.executeCancellable(request: Request): Response =
        suspendCancellableCoroutine { continuation ->
            val call = newCall(request)
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (continuation.isActive) continuation.resumeWith(Result.failure(e))
                }

                override fun onResponse(call: Call, response: Response) {
                    if (continuation.isActive) continuation.resume(response) else response.close()
                }
            })
        }

    private fun isSecureOrLocal(rawUrl: String): Boolean = runCatching {
        val uri = URI(rawUrl)
        if (uri.userInfo != null) return@runCatching false
        if (uri.scheme.equals("https", ignoreCase = true)) return@runCatching true
        if (!uri.scheme.equals("http", ignoreCase = true)) return@runCatching false
        val host = uri.host?.lowercase().orEmpty()
        host == "localhost" || host == "127.0.0.1" || host == "::1" ||
            host.startsWith("10.") || host.startsWith("192.168.") ||
            host.matches(Regex("172\\.(1[6-9]|2\\d|3[01])\\..*"))
    }.getOrDefault(false)

    private companion object {
        val JSON = "application/json; charset=utf-8".toMediaType()
    }
}
