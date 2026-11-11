@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class, kotlin.experimental.ExperimentalNativeApi::class)

package com.fotoxplorr.ut.bridge

import com.fotoxplorr.core.db.entity.AssetEntity
import com.fotoxplorr.core.db.gallery.GalleryProjectionDefaults
import com.fotoxplorr.core.search.Term
import com.fotoxplorr.core.search.parseSearchQuery
import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.COpaquePointer
import kotlinx.cinterop.CPointer
import kotlinx.serialization.encodeToString
import kotlin.native.CName
import platform.posix.time

/**
 * WP8.1's real query path: `ast_json` is a small envelope ([QueryPageRequestDto]), not yet the
 * full [com.fotoxplorr.core.search.SearchQuery] AST the master-plan name suggests -- see this
 * file's own KDoc below for exactly how far `:core:search`'s real parser is actually wired in
 * today, and [SmartAlbum]'s KDoc for the query surface every `album` value routes to.
 *
 * @return a heap-allocated JSON [QueryPageResponseDto] -- **always** non-null and always shaped
 * with `rows`/`nextCursor`/`count`, even on a bad request (an unknown `album`, a missing
 * `folderKey` for `FOLDER`, malformed `ast_json`): those set `error` and leave `rows` empty,
 * rather than returning `null`, per `BridgeJson.kt`'s convention. Only an invalid [handle] can
 * make this function return `null`.
 */
@CName("fotoz_query_page")
fun fotoz_query_page(
    handle: COpaquePointer?,
    astJson: CPointer<ByteVar>?,
    cursor: CPointer<ByteVar>?,
): CPointer<ByteVar>? {
    val library = handle.asLibraryHandleOrNull() ?: return null
    return runCatchingJson {
        val requestText = astJson.readUtf8OrNull()
            ?: return@runCatchingJson BridgeJson.json.encodeToString(errorPage("ast_json is required"))
        val request = try {
            BridgeJson.json.decodeFromString(QueryPageRequestDto.serializer(), requestText)
        } catch (t: Throwable) {
            return@runCatchingJson BridgeJson.json.encodeToString(errorPage("malformed ast_json: ${t.message}"))
        }
        val album = SmartAlbum.of(request.album)
            ?: return@runCatchingJson BridgeJson.json.encodeToString(errorPage("unknown album '${request.album}'"))
        if (album == SmartAlbum.FOLDER && request.folderKey.isNullOrBlank()) {
            return@runCatchingJson BridgeJson.json.encodeToString(errorPage("album FOLDER requires folderKey"))
        }
        val offset = cursor.readUtf8OrNull()?.toIntOrNull() ?: 0
        val pageSize = request.pageSize.coerceIn(1, 500)

        val rows = runBridge { fetchPage(library, album, request, offset, pageSize) }
        val words = if (request.query.isNullOrBlank()) emptyList() else parseSearchQuery(request.query).terms.filterIsInstance<Term.Word>()
        val filtered = if (words.isEmpty()) rows else rows.filter { asset -> matchesBareWords(asset, words) }

        val response = QueryPageResponseDto(
            rows = filtered.map { it.toDto() },
            nextCursor = if (rows.size == pageSize) (offset + pageSize).toString() else null,
            count = filtered.size,
            textFilterApplied = words.isNotEmpty(),
        )
        BridgeJson.json.encodeToString(response)
    }
}

private fun errorPage(message: String) = QueryPageResponseDto(
    rows = emptyList(),
    nextCursor = null,
    count = 0,
    textFilterApplied = false,
    error = message,
)

/**
 * Applies only the narrow slice of a parsed free-text query this WP can honestly claim: bare,
 * non-negated [Term.Word] terms matched against `displayName`, case-insensitively. This is
 * deliberately NOT the app's real free-text search (`matchesQuery`/`matchesGallerySearch`, which
 * also checks folder, tags, OCR text and AI labels) -- [GalleryProjectionDao] itself does not
 * implement that yet either (its own documented gap, MASTER-PROGRESS.md's WP1.5 Decisions).
 * Field terms (`field:value`), numeric comparisons, date terms and negation are all parsed
 * correctly by the real `:core:search` parser but not applied here -- proving the parser is
 * wired in without pretending this module can act on constraints no query here can evaluate yet.
 */
private fun matchesBareWords(asset: AssetEntity, words: List<Term.Word>): Boolean =
    words.all { word ->
        val hit = asset.displayName.contains(word.value, ignoreCase = true)
        if (word.negated) !hit else hit
    }

private suspend fun fetchPage(
    library: LibraryHandle,
    album: SmartAlbum,
    request: QueryPageRequestDto,
    offset: Int,
    limit: Int,
): List<AssetEntity> {
    val dao = library.db.galleryProjectionDao()
    return when (album) {
        SmartAlbum.EVERYDAY -> dao.everyday(request.hideSensitive, request.showVideos, limit, offset)
        SmartAlbum.FAVORITES -> dao.observeFavorites(request.hideSensitive, request.showVideos, limit, offset)
        SmartAlbum.RECENT -> dao.observeRecent(
            sinceMs = nowMillis() - GalleryProjectionDefaults.RECENT_WINDOW_MILLIS,
            hideSensitive = request.hideSensitive,
            showVideos = request.showVideos,
            limit = limit,
            offset = offset,
        )
        SmartAlbum.VIDEOS -> dao.observeVideos(request.hideSensitive, limit, offset)
        SmartAlbum.SCREENSHOTS -> dao.observeScreenshots(request.hideSensitive, request.showVideos, limit, offset)
        SmartAlbum.ANIMATED -> dao.observeAnimated(request.hideSensitive, request.showVideos, limit, offset)
        SmartAlbum.LARGE_FILES -> dao.observeLargeFiles(
            GalleryProjectionDefaults.LARGE_FILE_THRESHOLD_BYTES,
            request.hideSensitive,
            request.showVideos,
            limit,
            offset,
        )
        SmartAlbum.DUPLICATES -> dao.observeDuplicates(request.hideSensitive, request.showVideos, limit, offset)
        SmartAlbum.SENSITIVE -> dao.observeSensitive(request.showVideos, limit, offset)
        SmartAlbum.ARCHIVED -> dao.observeArchived(request.hideSensitive, request.showVideos, limit, offset)
        SmartAlbum.TRASH -> dao.observeTrash(request.hideSensitive, request.showVideos, limit, offset)
        SmartAlbum.UNTAGGED -> dao.observeUntagged(request.hideSensitive, request.showVideos, limit, offset)
        SmartAlbum.FOLDER -> dao.observeFolder(requireNotNull(request.folderKey), request.showVideos, limit, offset)
    }
}

/** Seconds-since-epoch from the C runtime's own `time(2)`, not `kotlinx-datetime` -- this module
 *  has no other use for that dependency, and POSIX `time()` is exactly the right, dependency-free
 *  tool for "now" on a Linux-only native target. */
private fun nowMillis(): Long = time(null).toLong() * 1000L

internal fun AssetEntity.toDto() = AssetRowDto(
    assetId = assetId.value,
    sourceId = sourceId.value,
    locator = locator,
    contentUri = contentUri,
    displayName = displayName,
    mime = mime,
    formatId = formatId.value,
    sizeBytes = sizeBytes,
    width = width,
    height = height,
    orientation = orientation,
    durationMs = durationMs,
    dateTakenMs = dateTakenMs,
    dateModifiedMs = dateModifiedMs,
    dateAddedMs = dateAddedMs,
    relativePath = relativePath,
    folderKey = folderKey,
    bucketId = bucketId,
    bucketName = bucketName,
    availability = availability,
    trashed = trashed,
    revision = revision,
)
