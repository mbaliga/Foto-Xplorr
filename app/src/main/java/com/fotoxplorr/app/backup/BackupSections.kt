package com.fotoxplorr.app.backup

import com.fotoxplorr.app.editor.JsonValue
import com.fotoxplorr.app.editor.jsonObjectOf
import com.fotoxplorr.app.editor.parseJson
import com.fotoxplorr.app.editor.stringify
import com.fotoxplorr.app.editor.toJson

/**
 * The extra sections an encrypted manifest carries next to its file entries (ADR-014). The shapes
 * live here, both directions, so the writer (this unit) and the restore (the next) cannot drift
 * apart. Every id is the MediaStore id on the phone that made the backup, a hint that a restore
 * remaps through the entry table ([IdRemap]); none is an identity. A pin or recipe also says which
 * photo it is (`identity`, as in [METADATA]) so two snapshots made on different phones are not
 * merged by a row number that means two photos; a record without one is from before that and is
 * taken to belong to the phone that wrote the file.
 *
 * - [METADATA]: the `Tags and collections` file's own envelope (`MetadataBackup.buildFile`):
 *   library, favourite ids, sensitive ids, and an identity per id.
 * - [LOCKS]: `[{ "key", "salt", "hash" }]`, a protected folder's lock exactly as stored (salt and
 *   hash of the password, Base64; never the password), so a restored folder opens with the old one.
 * - [PINS]: `[{ "id", "lat", "lon", "identity"? }]`, the pins the person placed by hand.
 * - [RECIPES]: `[{ "id", "mod", "recipe", "identity"? }]`, the unsaved edit recipe of a photo at a file revision.
 *
 * Settings and the recognition caches are not here: they can be set again or recomputed.
 */
object BackupSections {
    const val METADATA = "metadata"
    const val LOCKS = "locks"
    const val PINS = "pins"
    const val RECIPES = "recipes"

    class Lock(val folderKey: String, val saltBase64: String, val hashBase64: String)

    class Pin(val mediaId: Long, val latitude: Double, val longitude: Double, val identity: MediaIdentity? = null)

    class Recipe(val mediaId: Long, val dateModifiedSeconds: Long, val recipe: JsonValue, val identity: MediaIdentity? = null)

    fun build(metadata: JsonValue, locks: List<Lock>, pins: List<Pin>, recipes: List<Recipe>): List<ManifestSection> = listOf(
        ManifestSection(METADATA, metadata),
        ManifestSection(
            LOCKS,
            JsonValue.JsonArray(
                locks.map { jsonObjectOf("key" to it.folderKey.toJson(), "salt" to it.saltBase64.toJson(), "hash" to it.hashBase64.toJson()) },
            ),
        ),
        ManifestSection(
            PINS,
            JsonValue.JsonArray(
                pins.map {
                    withIdentity(
                        linkedMapOf<String, JsonValue>("id" to it.mediaId.toJson(), "lat" to JsonValue.JsonNumber(it.latitude), "lon" to JsonValue.JsonNumber(it.longitude)),
                        it.identity,
                    )
                },
            ),
        ),
        ManifestSection(
            RECIPES,
            JsonValue.JsonArray(
                recipes.map {
                    withIdentity(
                        linkedMapOf<String, JsonValue>("id" to it.mediaId.toJson(), "mod" to it.dateModifiedSeconds.toJson(), "recipe" to it.recipe),
                        it.identity,
                    )
                },
            ),
        ),
    )

    fun locks(section: JsonValue?): List<Lock> = items(section).mapNotNull { f ->
        val key = text(f, "key")
        val salt = text(f, "salt")
        val hash = text(f, "hash")
        if (key == null || salt == null || hash == null) null else Lock(key, salt, hash)
    }

    fun pins(section: JsonValue?): List<Pin> = items(section).mapNotNull { f ->
        val id = whole(f, "id")
        val lat = number(f, "lat")
        val lon = number(f, "lon")
        if (id == null || lat == null || lon == null || lat !in -90.0..90.0 || lon !in -180.0..180.0) null else Pin(id, lat, lon, identityOf(f))
    }

    fun recipes(section: JsonValue?): List<Recipe> = items(section).mapNotNull { f ->
        val id = whole(f, "id")
        val mod = whole(f, "mod")
        val recipe = f["recipe"]
        if (id == null || mod == null || recipe == null) null else Recipe(id, mod, recipe, identityOf(f))
    }

    /** A recipe as the editor's store keeps it on disk: the JSON text of the recipe object. */
    fun recipeText(recipe: Recipe): String = recipe.recipe.stringify()

    fun recipeFromStored(mediaId: Long, dateModifiedSeconds: Long, json: String, identity: MediaIdentity? = null): Recipe? =
        parseJson(json)?.let { Recipe(mediaId, dateModifiedSeconds, it, identity) }

    private const val IDENTITY = "identity"

    private fun withIdentity(fields: LinkedHashMap<String, JsonValue>, identity: MediaIdentity?): JsonValue {
        if (identity != null) fields[IDENTITY] = MetadataBackup.identityJson(identity)
        return JsonValue.JsonObject(fields)
    }

    /** Who a pin or recipe record is about; null for a record from before the field, or one whose identity is damaged. */
    internal fun identityOf(record: Map<String, JsonValue>): MediaIdentity? = MetadataBackup.identityFrom(record[IDENTITY])

    private fun items(section: JsonValue?): List<Map<String, JsonValue>> =
        (section as? JsonValue.JsonArray)?.items.orEmpty().mapNotNull { (it as? JsonValue.JsonObject)?.entries }

    private fun text(f: Map<String, JsonValue>, key: String): String? = (f[key] as? JsonValue.JsonString)?.value

    private fun number(f: Map<String, JsonValue>, key: String): Double? =
        (f[key] as? JsonValue.JsonNumber)?.value?.takeIf { !it.isNaN() && !it.isInfinite() }

    private fun whole(f: Map<String, JsonValue>, key: String): Long? =
        number(f, key)?.takeIf { it == Math.floor(it) && it >= 0 && it < 9_007_199_254_740_992.0 }?.toLong()
}
