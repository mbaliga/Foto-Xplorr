package com.fotoxplorr.feature.airemote

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

/**
 * The wire protocols the BYOK feature speaks. A deliberate twin of the app's
 * `AiProviderKind` — the enum lives on both sides of the flavor boundary because the app's
 * offline sources must compile without this module, and this module must never depend on
 * the app. The connect flavor's bridge maps between them in four lines.
 */
enum class RemoteProtocol {
    OPENAI_RESPONSES,
    OPENAI_COMPATIBLE_CHAT,
    ANTHROPIC_MESSAGES,
    GEMINI_GENERATE_CONTENT,
}

/**
 * Moved verbatim from the app's `ai/AiProviderClient.kt` (FX-012): request shapes,
 * response parsing, the HTTPS-or-local-network rule for where a key may be sent, and the
 * cancellable call plumbing. Behaviour is unchanged; only the types at the boundary went
 * from `AiProviderConfig` to primitives.
 *
 * The caller keeps ownership of `secret` zeroing — the app-side bridge wipes the buffer
 * after the call, exactly as the old client did.
 */
class RemoteProviderHttp {

    suspend fun generateText(
        protocol: RemoteProtocol,
        baseUrl: String,
        model: String,
        timeoutSeconds: Int,
        secret: CharArray,
        prompt: String,
    ): Result<String> = runCatching {
        require(prompt.isNotBlank()) { "A prompt is required" }
        require(model.isNotBlank()) { "A model is required" }
        require(isSecureOrLocal(baseUrl)) {
            "Provider keys may only be sent over HTTPS or to a local/private-network endpoint"
        }
        require(secret.isNotEmpty()) { "An API key or access token is required" }

        val request = buildRequest(protocol, baseUrl, model, secret, prompt)
        client(timeoutSeconds).executeCancellable(request).use { response ->
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
            parseText(protocol, body)
                .takeIf { it.isNotBlank() }
                ?: error("Provider returned no text")
        }
    }

    private fun buildRequest(
        protocol: RemoteProtocol,
        baseUrl: String,
        model: String,
        secret: CharArray,
        prompt: String,
    ): Request {
        val token = String(secret)
        val json = when (protocol) {
            RemoteProtocol.OPENAI_RESPONSES -> JSONObject().apply {
                put("model", model)
                put("input", prompt)
                put("max_output_tokens", 256)
            }
            RemoteProtocol.OPENAI_COMPATIBLE_CHAT -> JSONObject().apply {
                put("model", model)
                put(
                    "messages",
                    JSONArray().put(
                        JSONObject().apply {
                            put("role", "user")
                            put("content", prompt)
                        },
                    ),
                )
                put("max_tokens", 256)
                put("temperature", 0.2)
            }
            RemoteProtocol.ANTHROPIC_MESSAGES -> JSONObject().apply {
                put("model", model)
                put("max_tokens", 256)
                put(
                    "messages",
                    JSONArray().put(
                        JSONObject().apply {
                            put("role", "user")
                            put("content", prompt)
                        },
                    ),
                )
            }
            RemoteProtocol.GEMINI_GENERATE_CONTENT -> JSONObject().apply {
                put(
                    "contents",
                    JSONArray().put(
                        JSONObject().apply {
                            put("parts", JSONArray().put(JSONObject().put("text", prompt)))
                        },
                    ),
                )
                put("generationConfig", JSONObject().put("maxOutputTokens", 256))
            }
        }

        val endpoint = when (protocol) {
            RemoteProtocol.OPENAI_RESPONSES -> "$baseUrl/v1/responses"
            RemoteProtocol.OPENAI_COMPATIBLE_CHAT -> "$baseUrl/v1/chat/completions"
            RemoteProtocol.ANTHROPIC_MESSAGES -> "$baseUrl/v1/messages"
            RemoteProtocol.GEMINI_GENERATE_CONTENT ->
                "$baseUrl/v1beta/models/$model:generateContent?key=$token"
        }
        return Request.Builder()
            .url(endpoint)
            .post(json.toString().toRequestBody(JSON_MEDIA_TYPE))
            .header("Accept", "application/json")
            .apply {
                when (protocol) {
                    RemoteProtocol.OPENAI_RESPONSES,
                    RemoteProtocol.OPENAI_COMPATIBLE_CHAT,
                    -> header("Authorization", "Bearer $token")
                    RemoteProtocol.ANTHROPIC_MESSAGES -> {
                        header("x-api-key", token)
                        header("anthropic-version", "2023-06-01")
                    }
                    RemoteProtocol.GEMINI_GENERATE_CONTENT -> Unit
                }
            }
            .build()
    }

    private fun parseText(protocol: RemoteProtocol, raw: String): String {
        val root = JSONObject(raw)
        return when (protocol) {
            RemoteProtocol.OPENAI_RESPONSES -> {
                root.optString("output_text").takeIf { it.isNotBlank() }
                    ?: root.optJSONArray("output")
                        ?.optJSONObject(0)
                        ?.optJSONArray("content")
                        ?.optJSONObject(0)
                        ?.optString("text")
                        .orEmpty()
            }
            RemoteProtocol.OPENAI_COMPATIBLE_CHAT -> root
                .optJSONArray("choices")
                ?.optJSONObject(0)
                ?.optJSONObject("message")
                ?.optString("content")
                .orEmpty()
            RemoteProtocol.ANTHROPIC_MESSAGES -> root
                .optJSONArray("content")
                ?.optJSONObject(0)
                ?.optString("text")
                .orEmpty()
            RemoteProtocol.GEMINI_GENERATE_CONTENT -> root
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
            call.enqueue(
                object : Callback {
                    override fun onFailure(call: Call, e: IOException) {
                        if (continuation.isActive) continuation.resumeWith(Result.failure(e))
                    }

                    override fun onResponse(call: Call, response: Response) {
                        if (continuation.isActive) continuation.resume(response)
                        else response.close()
                    }
                },
            )
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
