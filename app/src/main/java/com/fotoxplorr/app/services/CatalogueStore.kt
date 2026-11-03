package com.fotoxplorr.app.services

import android.content.Context
import com.fotoxplorr.app.ai.RemoteAiBridge
import com.fotoxplorr.app.ai.RemoteAiUnavailableException
import com.fotoxplorr.app.services.catalogue.FreeTiersDoc
import com.fotoxplorr.app.services.catalogue.NoozCatalogueParser
import com.fotoxplorr.app.services.catalogue.NoozModelsDoc
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** What the screens read: the models list, the free tiers, and where they came from. */
data class CatalogueSnapshot(
    val models: NoozModelsDoc,
    val tiers: FreeTiersDoc?,
    val fromCache: Boolean,
)

/**
 * The shared AI catalogue (Nooz `ai-catalogue`) as Fotoz holds it: a snapshot BUNDLED in the APK
 * (assets/ai-catalogue, refreshed by scripts/refresh-ai-catalogue.sh) and, in the Connect build
 * only, a copy fetched by an explicit "Update catalogue" tap and cached app-private in [cacheDir].
 *
 * Nothing here runs by itself: [update] is called from a button and nowhere else. The fetch goes
 * through [RemoteAiBridge.downloadFile], the app's one network seam, so the Offline build refuses
 * it with a typed failure and holds no network code. A fetched copy replaces the cache only when
 * both files parse under the supported schemaVersion; otherwise the old copy stays.
 *
 * @param readBundled returns the text of a bundled asset by file name, or null.
 */
class CatalogueStore(
    private val cacheDir: File,
    private val readBundled: (String) -> String?,
) {
    constructor(context: Context) : this(
        File(context.applicationContext.filesDir, CACHE_DIR),
        { name -> assetText(context.applicationContext, name) },
    )

    fun load(): CatalogueSnapshot {
        val cached = parseBoth(File(cacheDir, MODELS).takeIf(File::isFile)?.readText(), File(cacheDir, TIERS).takeIf(File::isFile)?.readText())
        if (cached != null) return cached.copy(fromCache = true)
        return parseBoth(readBundled(MODELS), readBundled(TIERS))
            ?: CatalogueSnapshot(NoozModelsDoc(NoozCatalogueParser.SUPPORTED_SCHEMA, null, emptyList()), null, fromCache = false)
    }

    suspend fun update(bridge: RemoteAiBridge): Result<CatalogueSnapshot> = withContext(Dispatchers.IO) {
        runCatching {
            if (!bridge.available) throw RemoteAiUnavailableException()
            val incoming = File(cacheDir, "incoming").apply { deleteRecursively(); mkdirs() }
            for (name in listOf(MODELS, TIERS)) {
                bridge.downloadFile(BASE_URL + name, File(incoming, name)) { _, _ -> }.getOrThrow()
            }
            val fresh = parseBoth(File(incoming, MODELS).readText(), File(incoming, TIERS).readText())
                ?.takeIf { it.tiers != null }
                ?: error("The downloaded catalogue could not be read.")
            for (name in listOf(MODELS, TIERS)) {
                File(incoming, name).copyTo(File(cacheDir, name), overwrite = true)
            }
            incoming.deleteRecursively()
            fresh.copy(fromCache = true)
        }
    }

    private fun parseBoth(models: String?, tiers: String?): CatalogueSnapshot? {
        val m = models?.let { NoozCatalogueParser.parseModels(it).getOrNull() } ?: return null
        val t = tiers?.let { NoozCatalogueParser.parseFreeTiers(it).getOrNull() }
        return CatalogueSnapshot(m, t, fromCache = false)
    }

    companion object {
        private fun assetText(context: Context, name: String): String? = try {
            context.assets.open("ai-catalogue/$name").bufferedReader().use { it.readText() }
        } catch (_: java.io.IOException) {
            null
        }

        const val CACHE_DIR = "ai-catalogue-cache"
        const val MODELS = "models.json"
        const val TIERS = "free-tiers.json"

        /** Nooz's default branch. Raw files are fetched only on an explicit tap. */
        const val BASE_URL = "https://raw.githubusercontent.com/mbaliga/nooz/claude/app-build-d1f9s6/ai-catalogue/"
    }
}
