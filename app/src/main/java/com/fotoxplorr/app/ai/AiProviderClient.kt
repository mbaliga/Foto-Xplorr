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

class AiProviderClient {
    suspend fun testConnection(config: AiProviderConfig, secret: CharArray): Result<String> =
        generateText(config, secret, "Reply with the single word OK.")

    suspend fun generateText(
        config: AiProviderConfig,
        secret: CharArray,
        prompt: String,
    ): Result<String> {
        val normalized = config.normalized()
        return runCatching {
            require(prompt.isNotBlank()) { "A prompt is required" }
            require(normalized.model.isNotBlank()) { "A model is required" }
            require(isSecureOrLocal(normalized.baseUrl)) {
                "Provider keys may only be sent over HTTPS or to a local/private-network endpoint"
            }
            require(secret.isNotEmpty()) { "An API key or access token is required" }

            val request = buildRequest(normalized, secret, prompt)
            client(normalized.timeoutSeconds).executeCancellable(request).use { response ->
                val body = response.body.string()
                if (!response.isSuccessful) {
                    val safeMessage = runCatching {
                        JSONObject(body).optJSONObject("error")?.optString("message")
                    }.getOrNull().orEmpty()
                    error(
                        if (safeMessage.isNotBlank()) {
                            "Provider returned ${response.code}: ${safeMessage.take(240)}"
                        } else {
                            "Provider returned HTTP ${response.code}"
                        },
                    )
                }
                parseText(normalized.kind, body)
                    .takeIf { it.isNotBlank() }
                    ?: error("Provider returned no text")
            }
        }.also {
            secret.fill('\u0000')
        }
    }

    private fun buildRequest(
        config: AiProviderConfig,
        secret: CharArray,
        prompt: String,
    ): Request {
        val token = String(secret)
        val json = when (config.kind) {
            AiProviderKind.OPENAI_RESPONSES -> JSONObject().apply {
                put("model", config.model)
                put("input", prompt)
                put("max_output_tokens", 256)
            }
            AiProviderKind.OPENAI_COMPATIBLE_CHAT -> JSONObject().apply {
                put("model", config.model)
                put("messages", JSONArray().put(JSONObject().apply {
                    put("role", "user")
                    put("content", prompt)
                }))
                put("max_tokens", 256)
                put("temperature", 0.2)
            }
            AiProviderKind.ANTHROPIC_MESSAGES -> JSONObject().apply {
                put("model", config.model)
                put("max_tokens", 256)
                put("messages", JSONArray().put(JSONObject().apply {
                    put("role", "user")
                    put("content", prompt)
                }))
            }
            AiProviderKind.GEMINI_GENERATE_CONTENT -> JSONObject().apply {
                put("contents", JSONArray().put(JSONObject().apply {
                    put("parts", JSONArray().put(JSONObject().put("text", prompt)))
                }))
                put("generationConfig", JSONObject().put("maxOutputTokens", 256))
            }
        }

        val endpoint = when (config.kind) {
            AiProviderKind.OPENAI_RESPONSES -> "${config.baseUrl}/v1/responses"
            AiProviderKind.OPENAI_COMPATIBLE_CHAT -> "${config.baseUrl}/v1/chat/completions"
            AiProviderKind.ANTHROPIC_MESSAGES -> "${config.baseUrl}/v1/messages"
            AiProviderKind.GEMINI_GENERATE_CONTENT ->
                "${config.baseUrl}/v1beta/models/${config.model}:generateContent?key=$token"
        }
        return Request.Builder()
            .url(endpoint)
            .post(json.toString().toRequestBody(JSON_MEDIA_TYPE))
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
                    AiProviderKind.GEMINI_GENERATE_CONTENT -> Unit
                }
            }
            .build()
    }

    private fun parseText(kind: AiProviderKind, raw: String): String {
        val root = JSONObject(raw)
        return when (kind) {
            AiProviderKind.OPENAI_RESPONSES -> {
                root.optString("output_text").takeIf { it.isNotBlank() }
                    ?: root.optJSONArray("output")
                        ?.optJSONObject(0)
                        ?.optJSONArray("content")
                        ?.optJSONObject(0)
                        ?.optString("text")
                        .orEmpty()
            }
            AiProviderKind.OPENAI_COMPATIBLE_CHAT -> root
                .optJSONArray("choices")
                ?.optJSONObject(0)
                ?.optJSONObject("message")
                ?.optString("content")
                .orEmpty()
            AiProviderKind.ANTHROPIC_MESSAGES -> root
                .optJSONArray("content")
                ?.optJSONObject(0)
                ?.optString("text")
                .orEmpty()
            AiProviderKind.GEMINI_GENERATE_CONTENT -> root
                .optJSONArray("candidates")
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
                    if (continuation.isActive) continuation.resume(response)
                    else response.close()
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
        val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
    }
}
