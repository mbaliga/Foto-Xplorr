package com.fotoxplorr.ut.bridge

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * The request/response envelopes crossing the C boundary as JSON text (ADR-013 point 2: "opaque
 * handles and byte buffers only" -- a JSON string is exactly such a buffer, this module's own
 * concrete choice over a flatbuffer for this first, coarse cut; see MASTER-PROGRESS.md's WP8.1
 * Decisions for why). `kotlinx-serialization-json` was catalog-only since WP1.1 -- this module is
 * its first real consumer.
 *
 * **Convention, load-bearing for every `@CName` function that returns a JSON string** (not one
 * that returns a raw status [Int] or an opaque handle): a `null` C pointer is reserved for a
 * structural failure only (an invalid/null library handle was passed in) -- every other failure,
 * expected or not, still returns a real, parseable JSON string carrying an `error` field, so a
 * caller that only speaks JSON never has to null-check twice. See each `@CName` function's own
 * KDoc for its exact null-vs-error-field rule.
 */
object BridgeJson {
    val json: Json = Json {
        ignoreUnknownKeys = true
        // Deliberately false (kotlinx.serialization's own default): a nullable field left at its
        // default (e.g. QueryPageResponseDto.error when there is no error) is OMITTED from the
        // JSON entirely, so a caller's `error` key lookup gets a genuine missing key -- not a
        // real JSON `null` value it would have to special-case separately (JsonNull is its own
        // JsonElement, distinct from Kotlin's null on a map lookup, and this module's own
        // `linuxTest` caught exactly that distinction the first time this was set to `true`).
        encodeDefaults = false
    }

    fun encodeError(message: String): String = json.encodeToString(ErrorResponseDto(error = message))
}

@Serializable
data class ErrorResponseDto(val error: String)

/** WP8.1's own capability report -- see [fotoz_capabilities]'s KDoc for why this is a bridge-
 *  local shape rather than a reuse of "the shared `Capability` model" ADR-013 point 3 names: that
 *  model does not exist anywhere in this codebase yet (WP1.7a deferred it, still deferred). */
@Serializable
data class CapabilitiesDto(
    val schemaVersion: Int,
    val database: DatabaseCapabilityDto,
    val queryPage: QueryPageCapabilityDto,
    val assetDetail: FeatureCapabilityDto,
    val setMetadata: SetMetadataCapabilityDto,
    val sync: FeatureCapabilityDto,
    val ops: FeatureCapabilityDto,
)

@Serializable
data class DatabaseCapabilityDto(
    val engine: String,
    val schema: String,
)

@Serializable
data class QueryPageCapabilityDto(
    val available: Boolean,
    val albums: List<String>,
    val freeTextSearch: String,
)

@Serializable
data class FeatureCapabilityDto(
    val available: Boolean,
    val reason: String? = null,
)

@Serializable
data class SetMetadataCapabilityDto(
    val sidecarName: Boolean,
    val strip: Boolean,
    val xmp: Boolean,
    val reason: String,
)

/** [fotoz_query_page]'s request envelope. `album` names one of [GalleryProjectionDao]'s own real,
 *  tested queries -- see that DAO's own KDoc for exactly what each one filters. */
@Serializable
data class QueryPageRequestDto(
    val album: String,
    val folderKey: String? = null,
    val hideSensitive: Boolean = false,
    val showVideos: Boolean = true,
    val query: String? = null,
    val pageSize: Int = 50,
)

@Serializable
data class AssetRowDto(
    val assetId: Long,
    val sourceId: Long,
    val locator: String,
    val contentUri: String?,
    val displayName: String,
    val mime: String,
    val formatId: String,
    val sizeBytes: Long,
    val width: Int,
    val height: Int,
    val orientation: Int,
    val durationMs: Long,
    val dateTakenMs: Long,
    val dateModifiedMs: Long,
    val dateAddedMs: Long?,
    val relativePath: String?,
    val folderKey: String,
    val bucketId: Long?,
    val bucketName: String?,
    val availability: String,
    val trashed: Boolean,
    val revision: Long,
)

@Serializable
data class QueryPageResponseDto(
    val rows: List<AssetRowDto>,
    val nextCursor: String?,
    val count: Int,
    val textFilterApplied: Boolean,
    val error: String? = null,
)

@Serializable
data class AssetUserDto(
    val favorite: Boolean,
    val rating: Int,
    val flag: Int,
    val colorLabel: Int,
    val archived: Boolean,
    val sensitive: Boolean,
    val caption: String?,
    val captionIsMachine: Boolean,
)

@Serializable
data class AssetDetailResponseDto(
    val asset: AssetRowDto,
    val user: AssetUserDto?,
)

@Serializable
data class SetMetadataRequestDto(
    val kind: String? = null,
    val originalFileName: String? = null,
    val style: String? = null,
    val inputPath: String? = null,
    val outputPath: String? = null,
)

@Serializable
data class SidecarNameResponseDto(val sidecarFileName: String)

@Serializable
data class StripResponseDto(val format: String)

@Serializable
data class OpsRequestDto(val op: String? = null)
