package com.fotoxplorr.ios

import com.fotoxplorr.core.formats.FormatCapabilities
import com.fotoxplorr.core.formats.MediaFormat
import com.fotoxplorr.core.model.MediaId
import com.fotoxplorr.core.organize.BulkRenamePlanner
import com.fotoxplorr.core.organize.RenameSubject
import com.fotoxplorr.core.search.SearchDocument
import com.fotoxplorr.core.search.Term
import com.fotoxplorr.core.search.matchesQuery
import com.fotoxplorr.core.search.parseSearchQuery
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * The whole surface the SwiftUI shell calls into. Deliberately String-in / String-out (JSON) so the
 * Objective-C header Kotlin/Native generates stays three trivial `String -> String` methods, no
 * Kotlin generics, sealed hierarchies or value classes leaking into Swift, and so every behaviour
 * is testable from plain `jvmTest` with no Apple toolchain.
 *
 * Every function returns `{"ok":true,...}` or `{"ok":false,"error":"..."}`; none throws across the
 * language boundary (an uncaught Kotlin exception in a Kotlin/Native framework call terminates the
 * process).
 *
 * Time handling is explicit: Swift passes the IANA zone id and "today" (yyyy-MM-dd) so "last
 * month" resolves identically in tests and on the phone, and nothing here reads a clock.
 */
object FotozCore {
    /** Bumped when the JSON envelope shapes change; Swift asserts it matches at startup. */
    const val API_VERSION: Int = 1

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; explicitNulls = false }

    fun apiVersion(): Int = API_VERSION

    /** Parse [raw] into chips. [tzId] is an IANA id ("Europe/London"); [todayIso] is yyyy-MM-dd. */
    fun parseQuery(raw: String, tzId: String, todayIso: String): String = guarded {
        val parsed = parseSearchQuery(raw, zone(tzId), LocalDate.parse(todayIso))
        json.encodeToString(
            ParseResponse(
                ok = true,
                raw = parsed.raw,
                showsEverything = parsed.isEmpty,
                terms = parsed.terms.map(::chip),
            ),
        )
    }

    /**
     * Filter [documentsJson] (a JSON array of [DocumentDto]) by [raw]; returns the matching ids in
     * input order. The shell builds documents from PhotoKit metadata only (name, album, mime, dates,
     * dimensions, favourite flag) -- no recognition index exists on iOS yet, so labels/text/palette
     * stay empty and `label:`/`text:`/`colour:` terms simply match nothing, as they do on Android
     * before its recognition pass has run.
     */
    fun search(raw: String, tzId: String, todayIso: String, documentsJson: String): String = guarded {
        val parsed = parseSearchQuery(raw, zone(tzId), LocalDate.parse(todayIso))
        val docs = json.decodeFromString<List<DocumentDto>>(documentsJson)
        val hits = docs.withIndex()
            .filter { (index, dto) -> matchesQuery(parsed, dto.toDocument(index.toLong())) }
            .map { it.value.id }
        json.encodeToString(SearchResponse(ok = true, showsEverything = parsed.isEmpty, ids = hits))
    }

    /** Format + capability row for one file, from the MIME type and file name. */
    fun classify(mimeType: String, fileName: String): String = guarded {
        val format = MediaFormat.classify(mimeType, fileName)
        val capabilities = FormatCapabilities.of(format).map { it.name }.sorted()
        json.encodeToString(
            ClassifyResponse(
                ok = true,
                format = formatName(format),
                likelyDecodable = format.isLikelyDecodable,
                capabilities = capabilities,
            ),
        )
    }

    /**
     * Preview a bulk rename: collision-free final names for [subjectsJson] (array of
     * [RenameSubjectDto]) under [pattern]. Preview only -- PhotoKit cannot rename assets, so the
     * iOS shell uses this for "export with these names", never to mutate the library.
     */
    fun planRename(
        pattern: String,
        subjectsJson: String,
        startAt: Int,
        existingNamesJson: String,
        tzId: String,
    ): String = guarded {
        val subjects = json.decodeFromString<List<RenameSubjectDto>>(subjectsJson)
            .map { RenameSubject(it.name, it.takenMillis, it.modifiedSeconds) }
        val existing = json.decodeFromString<List<String>>(existingNamesJson).toSet()
        val plan = BulkRenamePlanner.plan(pattern, subjects, startAt, existing, zone(tzId))
        json.encodeToString(RenameResponse(ok = true, names = plan.map { it.finalName }))
    }

    // ---- internals -------------------------------------------------------------------------

    private inline fun guarded(block: () -> String): String = try {
        block()
    } catch (e: Throwable) {
        // Throwable on purpose: nothing may unwind into Swift. IllegalArgumentException (blank
        // pattern, bad date) is the expected case; everything else is reported the same way.
        json.encodeToString(ErrorResponse(ok = false, error = e.message ?: e::class.simpleName ?: "error"))
    }

    private fun zone(tzId: String): TimeZone =
        try { TimeZone.of(tzId) } catch (_: Throwable) { TimeZone.UTC }

    private fun chip(term: Term): ChipDto = when (term) {
        is Term.Word -> ChipDto("word", term.label(), term.negated)
        is Term.Field -> ChipDto("field", term.label(), term.negated, field = term.field.key)
        is Term.DateWindow -> ChipDto("date", term.label(), term.negated)
        is Term.AnyOf -> ChipDto("anyOf", term.label(), false, branches = term.branches.map(::chip))
    }

    private fun formatName(format: MediaFormat): String =
        if (format is MediaFormat.Raw) "Raw" else format::class.simpleName ?: "Other"
}

@Serializable
data class DocumentDto(
    val id: String,
    val name: String,
    val folder: String = "",
    val mimeType: String = "",
    val takenAtMillis: Long = 0,
    val tags: List<String> = emptyList(),
    val categories: List<String> = emptyList(),
    val width: Int = 0,
    val height: Int = 0,
    val sizeBytes: Long = 0,
) {
    fun toDocument(index: Long) = SearchDocument(
        mediaId = MediaId(index),
        name = name,
        folder = folder,
        mimeType = mimeType,
        takenAtMillis = takenAtMillis,
        tags = tags.toSet(),
        labels = emptySet(),
        text = "",
        categories = categories.toSet(),
        camera = "",
        iso = null,
        width = width,
        height = height,
        sizeBytes = sizeBytes,
    )
}

@Serializable
data class RenameSubjectDto(val name: String, val takenMillis: Long = 0, val modifiedSeconds: Long = 0)

@Serializable
data class ChipDto(
    val kind: String,
    val label: String,
    val negated: Boolean,
    val field: String? = null,
    val branches: List<ChipDto>? = null,
)

@Serializable
private data class ParseResponse(val ok: Boolean, val raw: String, val showsEverything: Boolean, val terms: List<ChipDto>)

@Serializable
private data class SearchResponse(val ok: Boolean, val showsEverything: Boolean, val ids: List<String>)

@Serializable
private data class ClassifyResponse(
    val ok: Boolean,
    val format: String,
    val likelyDecodable: Boolean,
    val capabilities: List<String>,
)

@Serializable
private data class RenameResponse(val ok: Boolean, val names: List<String>)

@Serializable
private data class ErrorResponse(val ok: Boolean, val error: String)
