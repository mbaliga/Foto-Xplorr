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
        cleanLeftovers()
        val cached = parseBoth(File(cacheDir, MODELS).takeIf(File::isFile)?.readText(), File(cacheDir, TIERS).takeIf(File::isFile)?.readText())
        if (cached != null) return cached.copy(fromCache = true)
        return parseBoth(readBundled(MODELS), readBundled(TIERS))
            ?: CatalogueSnapshot(NoozModelsDoc(NoozCatalogueParser.SUPPORTED_SCHEMA, null, emptyList()), null, fromCache = false)
    }

    /** A killed update can leave a half-written download folder or *.tmp file; they are never read, only removed. */
    private fun cleanLeftovers() {
        File(cacheDir, INCOMING).deleteRecursively()
        cacheDir.listFiles { f -> f.name.endsWith(".tmp") }?.forEach(File::delete)
    }

    suspend fun update(bridge: RemoteAiBridge): Result<CatalogueSnapshot> = withContext(Dispatchers.IO) {
        val incoming = File(cacheDir, INCOMING)
        try {
            runCatching {
                if (!bridge.available) throw RemoteAiUnavailableException()
                cleanLeftovers()
                incoming.mkdirs()
                for (name in listOf(MODELS, TIERS)) {
                    val file = File(incoming, name)
                    bridge.downloadFile(BASE_URL + name, file) { _, _ -> }.getOrThrow()
                    check(file.length() <= MAX_BYTES) { "The downloaded catalogue is too large." }
                }
                val fresh = parseBoth(File(incoming, MODELS).readText(), File(incoming, TIERS).readText())
                    ?.takeIf { it.tiers != null }
                    ?: error("The downloaded catalogue could not be read.")
                // Each file is copied to *.tmp then renamed, so a kill never leaves a truncated
                // file. The free tiers go first: load() keys on models.json, so the pair is only
                // ever "old+old", "old models + new tiers" or "new+new", all of which parse.
                for (name in listOf(TIERS, MODELS)) {
                    val tmp = File(cacheDir, "$name.tmp")
                    File(incoming, name).copyTo(tmp, overwrite = true)
                    check(tmp.renameTo(File(cacheDir, name))) { "The catalogue could not be saved." }
                }
                fresh.copy(fromCache = true)
            }
        } finally {
            incoming.deleteRecursively()
            cacheDir.listFiles { f -> f.name.endsWith(".tmp") }?.forEach(File::delete)
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
        private const val INCOMING = "incoming"

        /** Largest catalogue file accepted (the real ones are under 20 KB). */
        const val MAX_BYTES = 2L * 1024L * 1024L

        /**
         * The one place the fetch address is set. It is pinned to a COMMIT of the Nooz repo, not a
         * branch: Nooz currently has a single branch (claude/app-build-d1f9s6, also its default),
         * no main and no tags, so a branch name could be deleted or force-pushed and silently serve
         * unreviewed content. A commit address is immutable. Consequence: "Update catalogue"
         * returns exactly this reviewed commit until the constant is bumped (scripts/refresh-ai-catalogue.sh
         * and this ref move together); once Nooz has a main or a release tag, point the ref at that.
         */
        const val BASE_URL = "https://raw.githubusercontent.com/mbaliga/nooz/c396e9a2d022ac28e7ce0a238c33ffc602afc7cf/ai-catalogue/"
    }
}
