package com.fotoxplorr.core.organize.ingest

import kotlinx.datetime.TimeZone

/** The facts a rename rule may use for one photo or RAW+JPEG pair (taken from the pair's primary). */
data class RenameInput(
    val originalStem: String,
    val extension: String,
    val captureMillis: Long,
    val camera: String,
    val seq: Int,
    val zone: TimeZone,
)

/**
 * Hook for naming files on ingest (WP4.6 plugs richer rules in later). Returns the new STEM only:
 * the planner keeps each file's own extension, gives both files of a RAW+JPEG pair the same stem,
 * sanitises the result with [NameSanitizer] and resolves collisions, so a rule can be naive.
 */
fun interface RenameRule {
    fun newStem(input: RenameInput): String
}

/** Default: keep the camera's own name. */
object IdentityRenameRule : RenameRule {
    override fun newStem(input: RenameInput): String = input.originalStem
}

/**
 * A pattern such as `{yyyy-MM-dd}_{seq}` or `{camera}-{orig}`; same token grammar and same escaping
 * as [DestinationTemplate] except that it must be a single name (no `/`). Build with [create].
 */
class PatternRenameRule private constructor(private val template: DestinationTemplate) : RenameRule {
    override fun newStem(input: RenameInput): String =
        template.expand(
            TemplateContext(
                captureMillis = input.captureMillis, zone = input.zone, camera = input.camera,
                seq = input.seq, originalStem = input.originalStem, extension = input.extension,
            ),
        ).single()

    companion object {
        fun create(pattern: String): Result<PatternRenameRule> {
            if ('/' in pattern) return Result.failure(IllegalArgumentException("A name cannot contain /"))
            return when (val r = DestinationTemplate.parse(pattern)) {
                is TemplateParse.Ok ->
                    if (pattern.isEmpty()) Result.failure(IllegalArgumentException("The name pattern is empty."))
                    else Result.success(PatternRenameRule(r.template))
                is TemplateParse.Error -> Result.failure(IllegalArgumentException(r.message))
            }
        }
    }
}
