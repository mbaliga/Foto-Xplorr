package com.fotoxplorr.core.organize.ingest

import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime

/** What a template needs to expand for one photo (or one RAW+JPEG pair, taken from its primary). */
data class TemplateContext(
    val captureMillis: Long,
    val zone: TimeZone,
    val camera: String,
    val seq: Int,
    val originalStem: String,
    val extension: String,
)

sealed interface TemplateParse {
    data class Ok(val template: DestinationTemplate) : TemplateParse

    /** [index] is the character offset in the template text the problem was found at. */
    data class Error(val message: String, val index: Int) : TemplateParse
}

/**
 * The destination folder template, e.g. `{yyyy}/{yyyy-MM-dd}` or `{camera}/{yyyy}/{MM}`.
 *
 * Grammar (strict, an unknown token is an ERROR, never silently literal):
 *  - `/` separates folders; an empty folder (`a//b`, leading `/`) is an error, one trailing `/` is ignored;
 *  - `{{` and `}}` are a literal brace; a lone `{` or `}` is an error;
 *  - `{camera}`, `{orig}` (card file stem), `{ext}` (lower-cased extension), `{seq}` / `{seq:N}`
 *    (running number of the photo in this import from 1, zero padded to N = 1..9 digits, default 4);
 *  - a date token is runs of `yyyy yy MM dd HH mm ss` joined by `- _ .` or a space, e.g. `{yyyy-MM-dd}`,
 *    `{yyyy}`, `{HH.mm.ss}`; any other letter is an error;
 *  - literal text may not contain `\ : * ? " < > |` or control characters, and a whole folder that is
 *    literally `.` or `..` is an error (path traversal).
 * Values substituted for tokens are NOT trusted: each expanded folder passes [NameSanitizer.segment],
 * so a camera called `../../etc` becomes the single safe folder `_.._.._etc` and can never climb out.
 * The date is read in the fixed zone of the context, never the device default, so a plan is
 * reproducible.
 */
class DestinationTemplate private constructor(
    val text: String,
    private val segments: List<List<Part>>,
) {
    internal sealed interface Part {
        data class Literal(val s: String) : Part
        data class Date(val pieces: List<String>) : Part
        data object Camera : Part
        data object Orig : Part
        data object Ext : Part
        data class Seq(val width: Int) : Part
    }

    /** The relative folder as sanitised segments; empty for an empty template (flat import). */
    fun expand(ctx: TemplateContext): List<String> = segments.map { parts ->
        NameSanitizer.segment(parts.joinToString("") { render(it, ctx) })
    }

    fun expandPath(ctx: TemplateContext): String = expand(ctx).joinToString("/")

    private fun render(p: Part, ctx: TemplateContext): String = when (p) {
        is Part.Literal -> p.s
        Part.Camera -> ctx.camera.trim().ifEmpty { "Unknown camera" }
        Part.Orig -> ctx.originalStem
        Part.Ext -> ctx.extension.lowercase()
        is Part.Seq -> ctx.seq.toString().padStart(p.width, '0')
        is Part.Date -> {
            val dt = Instant.fromEpochMilliseconds(ctx.captureMillis).toLocalDateTime(ctx.zone)
            p.pieces.joinToString("") {
                when (it) {
                    "yyyy" -> dt.year.toString().padStart(4, '0')
                    "yy" -> (dt.year % 100).toString().padStart(2, '0')
                    "MM" -> dt.monthNumber.toString().padStart(2, '0')
                    "dd" -> dt.dayOfMonth.toString().padStart(2, '0')
                    "HH" -> dt.hour.toString().padStart(2, '0')
                    "mm" -> dt.minute.toString().padStart(2, '0')
                    "ss" -> dt.second.toString().padStart(2, '0')
                    else -> it // separator
                }
            }
        }
    }

    companion object {
        private const val ILLEGAL_LITERAL = "\\:*?\"<>|"
        private val dateFields = setOf("yyyy", "yy", "MM", "dd", "HH", "mm", "ss")

        fun parse(text: String): TemplateParse {
            val segments = ArrayList<List<Part>>()
            var cur = ArrayList<Part>()
            val lit = StringBuilder()

            fun flushLit() {
                if (lit.isNotEmpty()) { cur.add(Part.Literal(lit.toString())); lit.clear() }
            }

            fun endSegment(at: Int): TemplateParse.Error? {
                flushLit()
                if (cur.isEmpty()) return TemplateParse.Error("Empty folder name in the template.", at)
                if (cur.size == 1) {
                    val only = cur[0]
                    if (only is Part.Literal && (only.s == "." || only.s == "..")) {
                        return TemplateParse.Error("A folder may not be . or ..", at)
                    }
                }
                segments.add(cur)
                cur = ArrayList()
                return null
            }

            var i = 0
            while (i < text.length) {
                val c = text[i]
                when {
                    c == '{' -> {
                        if (i + 1 < text.length && text[i + 1] == '{') { lit.append('{'); i += 2; continue }
                        val close = text.indexOf('}', i + 1)
                        if (close < 0) return TemplateParse.Error("Unclosed { in the template.", i)
                        val body = text.substring(i + 1, close)
                        if ('{' in body) return TemplateParse.Error("Unclosed { in the template.", i)
                        flushLit()
                        cur.add(parseToken(body) ?: return TemplateParse.Error("Unknown token {$body}.", i))
                        i = close + 1
                    }
                    c == '}' -> {
                        if (i + 1 < text.length && text[i + 1] == '}') { lit.append('}'); i += 2; continue }
                        return TemplateParse.Error("Stray } in the template.", i)
                    }
                    c == '/' -> {
                        if (i == text.length - 1 && (cur.isNotEmpty() || lit.isNotEmpty())) { i++; continue }
                        endSegment(i)?.let { return it }
                        i++
                    }
                    c.isISOControl() || c in ILLEGAL_LITERAL ->
                        return TemplateParse.Error("The character '${if (c.isISOControl()) "?" else c}' cannot be used in a folder name.", i)
                    else -> { lit.append(c); i++ }
                }
            }
            if (text.isNotEmpty()) {
                if (lit.isNotEmpty() || cur.isNotEmpty()) endSegment(text.length)?.let { return it }
            }
            return TemplateParse.Ok(DestinationTemplate(text, segments))
        }

        /** Convenience for callers with a vetted template (tests, defaults). */
        fun parseOrThrow(text: String): DestinationTemplate = when (val r = parse(text)) {
            is TemplateParse.Ok -> r.template
            is TemplateParse.Error -> throw IllegalArgumentException(r.message)
        }

        private fun parseToken(body: String): Part? {
            when (body) {
                "camera" -> return Part.Camera
                "orig" -> return Part.Orig
                "ext" -> return Part.Ext
                "seq" -> return Part.Seq(4)
            }
            if (body.startsWith("seq:")) {
                val n = body.substring(4).toIntOrNull() ?: return null
                return if (n in 1..9) Part.Seq(n) else null
            }
            return parseDate(body)
        }

        private fun parseDate(body: String): Part? {
            if (body.isEmpty()) return null
            val pieces = ArrayList<String>()
            var i = 0
            var sawField = false
            while (i < body.length) {
                val c = body[i]
                if (c in "yMdHms") {
                    var j = i
                    while (j < body.length && body[j] == c) j++
                    val run = body.substring(i, j)
                    if (run !in dateFields) return null
                    pieces.add(run); sawField = true
                    i = j
                } else if (c == '-' || c == '_' || c == '.' || c == ' ') {
                    pieces.add(c.toString()); i++
                } else return null
            }
            return if (sawField) Part.Date(pieces) else null
        }
    }
}
