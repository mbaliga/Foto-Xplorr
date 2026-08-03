package com.fotoxplorr.app.ai

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject

class AiProviderStore(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(
        PREFERENCES_NAME,
        Context.MODE_PRIVATE,
    )
    private val secrets = EncryptedSecretStore(context)
    private val state = MutableStateFlow(load())

    fun observe(): StateFlow<List<AiProviderConfig>> = state.asStateFlow()

    fun upsert(config: AiProviderConfig, secret: CharArray? = null): AiProviderConfig {
        val normalized = config.normalized()
        require(normalized.label.isNotEmpty()) { "Provider name is required" }
        require(normalized.baseUrl.startsWith("https://") || normalized.baseUrl.startsWith("http://")) {
            "Provider URL must start with http:// or https://"
        }
        require(normalized.model.isNotEmpty()) { "Model name is required" }

        if (secret != null && secret.isNotEmpty()) {
            secrets.put(normalized.id, secret)
        } else {
            secret?.fill('\u0000')
        }

        val updated = (state.value.filterNot { it.id == normalized.id } + normalized.copy(
            hasSecret = secrets.contains(normalized.id),
        )).sortedBy { it.label.lowercase() }
        persist(updated)
        return updated.first { it.id == normalized.id }
    }

    fun setEnabled(id: String, enabled: Boolean) {
        val updated = state.value.map {
            if (it.id == id) it.copy(enabled = enabled && secrets.contains(id)) else it
        }
        persist(updated)
    }

    fun remove(id: String) {
        secrets.remove(id)
        persist(state.value.filterNot { it.id == id })
    }

    fun secret(id: String): CharArray? = secrets.get(id)

    fun addPreset(config: AiProviderConfig): AiProviderConfig = upsert(config)

    private fun persist(configs: List<AiProviderConfig>) {
        val array = JSONArray()
        configs.forEach { config ->
            array.put(JSONObject().apply {
                put("id", config.id)
                put("label", config.label)
                put("kind", config.kind.name)
                put("baseUrl", config.baseUrl)
                put("model", config.model)
                put("enabled", config.enabled)
                put("timeoutSeconds", config.timeoutSeconds)
            })
        }
        preferences.edit().putString(KEY_CONFIGS, array.toString()).apply()
        state.value = configs.map { it.copy(hasSecret = secrets.contains(it.id)) }
    }

    private fun load(): List<AiProviderConfig> = runCatching {
        val raw = preferences.getString(KEY_CONFIGS, null) ?: return@runCatching emptyList()
        val array = JSONArray(raw)
        buildList {
            for (index in 0 until array.length()) {
                val item = array.getJSONObject(index)
                val id = item.getString("id")
                val kind = runCatching {
                    AiProviderKind.valueOf(item.getString("kind"))
                }.getOrDefault(AiProviderKind.OPENAI_COMPATIBLE_CHAT)
                add(
                    AiProviderConfig(
                        id = id,
                        label = item.optString("label", "Provider"),
                        kind = kind,
                        baseUrl = item.optString("baseUrl", ""),
                        model = item.optString("model", ""),
                        enabled = item.optBoolean("enabled", false) && secrets.contains(id),
                        hasSecret = secrets.contains(id),
                        timeoutSeconds = item.optInt("timeoutSeconds", 45).coerceIn(5, 180),
                    ),
                )
            }
        }.sortedBy { it.label.lowercase() }
    }.getOrDefault(emptyList())

    private companion object {
        const val PREFERENCES_NAME = "foto_xplorr_ai_providers"
        const val KEY_CONFIGS = "provider_configs_v1"
    }
}
