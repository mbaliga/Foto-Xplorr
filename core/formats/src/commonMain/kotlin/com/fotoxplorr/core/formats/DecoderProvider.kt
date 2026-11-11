package com.fotoxplorr.core.formats

/** What a provider knows about the file before decoding it: dimensions and size, either may be 0. */
data class SizeHint(val width: Int = 0, val height: Int = 0, val bytes: Long = 0L) {
    val pixels: Long get() = width.toLong() * height.toLong()
}

/** One provider's answer to "can you put pixels on screen for this?". */
sealed interface DecoderVerdict {

    /** The platform (or this provider's own code) decodes it as is. */
    data object Decodable : DecoderVerdict

    /** Decodable through the named optional pack, which is installed. */
    data class DecodableWithPack(val packName: String) : DecoderVerdict

    /** Not decodable here; [reason] is one short sentence a person can read. */
    data class NotDecodable(val reason: String) : DecoderVerdict

    val canShow: Boolean get() = this !is NotDecodable
}

/**
 * The decoder SPI (MASTER-PLAN WP1.7). Pure: the Android platform provider, each optional pack and
 * the RAW preview extractor are all providers; none of them is known to this module.
 *
 * A provider that cannot help with [format] answers [DecoderVerdict.NotDecodable] with its reason,
 * rather than null: the registry keeps the best answer across providers, and a refusal with a
 * reason is what the placeholder shows when nothing says yes.
 */
interface DecoderProvider {
    /** Stable, unique id; also the tie-break for [DecoderRegistry]'s ordering. */
    val id: String

    /** Lower numbers are asked first and win ties between equally good verdicts. */
    val priority: Int get() = 100

    fun probe(format: MediaFormat, mime: String, sizeHint: SizeHint? = null): DecoderVerdict
}

/**
 * Merges providers deterministically: order is `(priority, id)`, never registration order, so two
 * builds that register the same providers in a different order answer identically.
 *
 * Merge rule: the best verdict wins -- [DecoderVerdict.Decodable] over
 * [DecoderVerdict.DecodableWithPack] over [DecoderVerdict.NotDecodable] -- and among equally good
 * verdicts the earliest provider in that order wins. With no provider at all, nothing is decodable.
 */
class DecoderRegistry(providers: List<DecoderProvider>) {
    private val ordered: List<DecoderProvider> =
        providers.sortedWith(compareBy<DecoderProvider> { it.priority }.thenBy { it.id })

    init {
        val ids = ordered.map { it.id }
        require(ids.toSet().size == ids.size) { "duplicate DecoderProvider ids: $ids" }
    }

    /** The provider ids in the order they are asked. */
    val providerIds: List<String> get() = ordered.map { it.id }

    fun probe(format: MediaFormat, mime: String = "", sizeHint: SizeHint? = null): DecoderVerdict {
        var best: DecoderVerdict? = null
        for (provider in ordered) {
            val verdict = provider.probe(format, mime, sizeHint)
            if (best == null || rank(verdict) < rank(best)) best = verdict
            if (best is DecoderVerdict.Decodable) break
        }
        return best ?: DecoderVerdict.NotDecodable(FormatCopy.NO_DECODER)
    }

    private fun rank(verdict: DecoderVerdict): Int = when (verdict) {
        DecoderVerdict.Decodable -> 0
        is DecoderVerdict.DecodableWithPack -> 1
        is DecoderVerdict.NotDecodable -> 2
    }
}
