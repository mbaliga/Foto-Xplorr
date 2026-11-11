package com.fotoxplorr.app.services.catalogue

import java.time.LocalDate
import java.time.temporal.ChronoUnit

/** Which Nooz models Fotoz lists, and the words about them. Pure, no Android types. */
object CataloguePolicy {

    /**
     * Kinds Fotoz never lists. TTS_ONNX is hidden because Fotoz has no audio feature (product rule:
     * no audio except the reel soundtrack file), so a voice model would be a download that can only
     * sit unused. UNKNOWN kinds are hidden too: showing a thing we cannot describe would be a guess.
     */
    private val HIDDEN_KINDS = setOf(NoozKind.TTS_ONNX, NoozKind.UNKNOWN)

    /** Kinds Fotoz has a runtime for. None of the Nooz kinds today (no GGUF or Stable Diffusion code in ai/). */
    val RUNNABLE_KINDS: Set<NoozKind> = emptySet()

    /** Models to list. By default only policySafe == true; [showAll] adds false and unknown. */
    fun visible(models: List<NoozModel>, showAll: Boolean): List<NoozModel> =
        models.filter { it.kind !in HIDDEN_KINDS && (showAll || it.policySafe == true) }

    /** A download can be offered only when a verified address exists AND Fotoz can run the kind. */
    fun canOfferDownload(model: NoozModel): Boolean =
        model.downloadUrl != null && model.kind in RUNNABLE_KINDS

    fun hiddenCount(models: List<NoozModel>, showAll: Boolean): Int =
        models.count { it.kind !in HIDDEN_KINDS } - visible(models, showAll).size

    /** "Catalogue from 2026-07-22, 104 days old." / "…, today." / "Catalogue date unknown." */
    fun snapshotAgeText(lastUpdated: String?, today: LocalDate): String {
        val date = lastUpdated?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
            ?: return "Catalogue date unknown."
        val days = ChronoUnit.DAYS.between(date, today)
        val age = when {
            days < 0L -> ""
            days == 0L -> ", today"
            days == 1L -> ", 1 day old"
            else -> ", $days days old"
        }
        return "Catalogue from $date$age."
    }

    /** "About 2.1 GB", or "Size unknown" for a null (never "0"). */
    fun sizeText(sizeBytes: Long?): String {
        if (sizeBytes == null || sizeBytes <= 0L) return "Size unknown"
        val gb = sizeBytes / 1_000_000_000.0
        val mb = sizeBytes / 1_000_000.0
        return if (gb >= 1.0) String.format(java.util.Locale.US, "About %.1f GB", gb)
        else String.format(java.util.Locale.US, "About %.0f MB", mb)
    }

    /** "Qwen/Qwen2.5-1.5B-Instruct-GGUF, qwen2.5-1.5b…gguf, checked 2026-07-11", only the parts that exist. */
    fun provenanceText(model: NoozModel): String {
        val parts = listOfNotNull(
            model.hfRepo,
            model.hfFile,
            model.verifiedAt?.let { "checked $it" },
        )
        return if (parts.isEmpty()) "Source not stated." else parts.joinToString(", ")
    }

    /** One honest line about a free tier. A reading of the provider's page on [asOf], never a promise. */
    fun freeTierLine(tier: FreeTier, asOf: String?): String {
        val what = tier.summary?.substringBefore(" (")?.trim()?.takeIf { it.isNotEmpty() }
            ?: when (tier.kind) {
                "ONGOING_FREE" -> "Has an ongoing free tier."
                "TRIAL_CREDIT" -> "Offers trial credit${tier.trialCredit?.let { ": $it" } ?: ""}."
                else -> "Free-tier details not stated."
            }
        val date = asOf?.let { "as of $it" } ?: "date unknown"
        return "$what ($date, check the provider)"
    }

    fun freeTierFor(tiers: List<FreeTier>, id: String?): FreeTier? = id?.let { wanted -> tiers.firstOrNull { it.id == wanted } }
}

/** Compares a finished download with what the catalogue said. Size and sha256 are checked only when stated. */
object ArtifactCheck {
    sealed interface Verdict {
        data object Ok : Verdict
        data class Bad(val reason: String) : Verdict
    }

    fun verify(model: NoozModel, actualBytes: Long, actualSha256: String?): Verdict {
        val size = model.sizeBytes
        // Catalogue sizes are rounded ("about"), so only a wildly different size is treated as wrong.
        if (size != null && size > 0L && (actualBytes < size / 2 || actualBytes > size * 2)) {
            return Verdict.Bad("The file size does not match the catalogue.")
        }
        val want = model.sha256
        if (want != null && !want.equals(actualSha256, ignoreCase = true)) {
            return Verdict.Bad("The file's checksum does not match the catalogue.")
        }
        return Verdict.Ok
    }
}
