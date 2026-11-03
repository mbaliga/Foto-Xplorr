package com.fotoxplorr.app.services.catalogue

/**
 * The Nooz `ai-catalogue` DATA contract (schemaVersion 1), as plain Kotlin with no Android types so
 * it can be lifted unchanged into a shared library. See nooz/ai-catalogue/README.md for the rules
 * this honours: a `null` field means "unknown", never zero or none; fields starting with `_` are
 * CI bookkeeping and are ignored; unknown fields and unknown kinds never fail a parse.
 */
enum class NoozKind { LLM_GGUF, SD_IMAGE, TTS_ONNX, UNKNOWN }

data class NoozModel(
    val id: String,
    val name: String,
    val kind: NoozKind,
    /** The kind exactly as written, kept so an UNKNOWN kind can still be shown honestly. */
    val rawKind: String?,
    val family: String?,
    val params: String?,
    val quant: String?,
    val contextWindow: Long?,
    val sizeBytes: Long?,
    /** Null = no verified mirror is known. Never render a null as "download available". */
    val downloadUrl: String?,
    val hfRepo: String?,
    val hfFile: String?,
    /** Present only when independently verified; still verify locally after any download. */
    val sha256: String?,
    /** True = official/licensed release; false = community remix; null = unknown. */
    val policySafe: Boolean?,
    val verifiedAt: String?,
    val note: String?,
    val groupId: String?,
)

data class NoozModelsDoc(val schemaVersion: Int, val lastUpdated: String?, val models: List<NoozModel>)

data class FreeTier(
    val id: String,
    val name: String,
    val providerKind: String?,
    /** ONGOING_FREE or TRIAL_CREDIT as written, or null. */
    val kind: String?,
    val summary: String?,
    val requestsPerDay: Long?,
    val tokensPerDay: Long?,
    val tokensPerMonth: Long?,
    val trialCredit: String?,
    val requiresCard: Boolean?,
    val sourceUrl: String?,
)

data class FreeTiersDoc(val schemaVersion: Int, val lastUpdated: String?, val tiers: List<FreeTier>)

class CatalogueFormatException(message: String) : Exception(message)

object NoozCatalogueParser {
    const val SUPPORTED_SCHEMA = 1

    fun parseModels(text: String): Result<NoozModelsDoc> = runCatching {
        val root = root(text)
        val list = root["models"] as? List<*> ?: throw CatalogueFormatException("No models list")
        NoozModelsDoc(
            schemaVersion = SUPPORTED_SCHEMA,
            lastUpdated = root.str("lastUpdated"),
            models = list.mapNotNull { (it as? Map<*, *>)?.let(::model) },
        )
    }

    fun parseFreeTiers(text: String): Result<FreeTiersDoc> = runCatching {
        val root = root(text)
        val list = root["providers"] as? List<*> ?: throw CatalogueFormatException("No providers list")
        FreeTiersDoc(
            schemaVersion = SUPPORTED_SCHEMA,
            lastUpdated = root.str("lastUpdated"),
            tiers = list.mapNotNull { (it as? Map<*, *>)?.let(::tier) },
        )
    }

    private fun root(text: String): Map<*, *> {
        val parsed = try {
            MiniJson.parse(text)
        } catch (e: MiniJson.JsonException) {
            throw CatalogueFormatException("Not valid JSON")
        }
        val root = parsed as? Map<*, *> ?: throw CatalogueFormatException("Not a JSON object")
        val version = (root["schemaVersion"] as? Long)?.toInt()
            ?: throw CatalogueFormatException("No schemaVersion")
        if (version != SUPPORTED_SCHEMA) throw CatalogueFormatException("Unsupported schemaVersion $version")
        return root
    }

    private fun model(m: Map<*, *>): NoozModel? {
        val id = m.str("id") ?: return null
        val raw = m.str("kind")
        return NoozModel(
            id = id,
            name = m.str("name") ?: id,
            kind = kindOf(raw),
            rawKind = raw,
            family = m.str("family"),
            params = m.str("params"),
            quant = m.str("quant"),
            contextWindow = m.long("contextWindow"),
            sizeBytes = m.long("sizeBytes"),
            downloadUrl = m.str("downloadUrl")?.takeIf(String::isNotBlank),
            hfRepo = m.str("hfRepo"),
            hfFile = m.str("hfFile"),
            sha256 = m.str("sha256")?.takeIf(String::isNotBlank),
            policySafe = m["policySafe"] as? Boolean,
            verifiedAt = m.str("verifiedAt"),
            note = m.str("note"),
            groupId = m.str("groupId"),
        )
    }

    private fun tier(m: Map<*, *>): FreeTier? {
        val id = m.str("id") ?: return null
        return FreeTier(
            id = id,
            name = m.str("name") ?: id,
            providerKind = m.str("providerKind"),
            kind = m.str("kind"),
            summary = m.str("summary"),
            requestsPerDay = m.long("requestsPerDay"),
            tokensPerDay = m.long("tokensPerDay"),
            tokensPerMonth = m.long("tokensPerMonth"),
            trialCredit = m.str("trialCredit"),
            requiresCard = m["requiresCard"] as? Boolean,
            sourceUrl = m.str("sourceUrl"),
        )
    }

    private fun kindOf(raw: String?): NoozKind =
        NoozKind.entries.firstOrNull { it != NoozKind.UNKNOWN && it.name == raw } ?: NoozKind.UNKNOWN

    private fun Map<*, *>.str(key: String): String? = this[key] as? String

    private fun Map<*, *>.long(key: String): Long? = when (val v = this[key]) {
        is Long -> v
        is Double -> v.toLong()
        else -> null
    }
}
