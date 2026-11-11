package com.fotoxplorr.core.formats.simple

import com.fotoxplorr.core.formats.DecoderProvider
import com.fotoxplorr.core.formats.DecoderVerdict
import com.fotoxplorr.core.formats.FormatCopy
import com.fotoxplorr.core.formats.MediaFormat
import com.fotoxplorr.core.formats.SizeHint

/**
 * Entry point of the pure-Kotlin decoders for the simple formats (MASTER-PLAN §4, WP2.3 minus the
 * packs that need native code): TGA, PNM, QOI, Radiance HDR, PCX, ICNS, PSD (flattened composite),
 * DDS (uncompressed), baseline TIFF, and the merged image of ORA/KRA archives.
 *
 * Hostile input: nothing is allocated before [SimpleLimits] has approved the declared size, every
 * read is bounds-checked, deflate output is capped, and any failure ends as [SimpleResult.Failure]
 * (never an uncaught exception). An OutOfMemoryError is turned into a Failure too, as a last resort
 * only: the caps above are what keep a decode inside the heap.
 */
object SimpleFormatDecoders {

    /** The formats this object can attempt. */
    val formats: Set<MediaFormat> = setOf(
        MediaFormat.Tga, MediaFormat.Pnm, MediaFormat.Qoi, MediaFormat.Hdr, MediaFormat.Pcx, MediaFormat.Icns,
        MediaFormat.Psd, MediaFormat.Dds, MediaFormat.Tiff, MediaFormat.Ora, MediaFormat.Kra,
    )

    fun supports(format: MediaFormat): Boolean = format in formats

    fun decode(format: MediaFormat, bytes: ByteArray, limits: SimpleLimits = SimpleLimits()): SimpleResult {
        if (format !in formats) return SimpleResult.Failure("format not handled here")
        if (bytes.size.toLong() > limits.maxInputBytes) return SimpleResult.Failure("file too large")
        return try {
            val image = when (format) {
                MediaFormat.Tga -> TgaDecoder.decode(bytes, limits)
                MediaFormat.Pnm -> PnmDecoder.decode(bytes, limits)
                MediaFormat.Qoi -> QoiDecoder.decode(bytes, limits)
                MediaFormat.Hdr -> HdrDecoder.decode(bytes, limits)
                MediaFormat.Pcx -> PcxDecoder.decode(bytes, limits)
                MediaFormat.Icns -> IcnsDecoder.decode(bytes, limits)
                MediaFormat.Psd -> PsdDecoder.decode(bytes, limits)
                MediaFormat.Dds -> DdsDecoder.decode(bytes, limits)
                MediaFormat.Tiff -> TiffDecoder.decode(bytes, limits)
                MediaFormat.Ora -> ArchiveThumbnails.decode(bytes, krita = false, limits = limits)
                else -> ArchiveThumbnails.decode(bytes, krita = true, limits = limits)
            }
            SimpleResult.Success(image)
        } catch (e: InvalidImageException) {
            SimpleResult.Failure(e.message ?: "invalid image")
        } catch (e: IndexOutOfBoundsException) {
            SimpleResult.Failure("truncated or corrupt file")
        } catch (e: IllegalArgumentException) {
            SimpleResult.Failure("corrupt file")
        } catch (e: ArithmeticException) {
            SimpleResult.Failure("corrupt file")
        } catch (e: Error) {
            // Common code has no OutOfMemoryError type; match it by name and rethrow anything else.
            if (e::class.simpleName != "OutOfMemoryError") throw e
            SimpleResult.Failure("out of memory")
        }
    }
}

/**
 * Registers [SimpleFormatDecoders] with the [com.fotoxplorr.core.formats.DecoderRegistry]. It says
 * yes for its formats unless the size hint already exceeds [limits] (then a short refusal, so the
 * placeholder explains itself instead of a decode failing later); the actual pixels come from
 * [SimpleFormatDecoders.decode] through the app's decode hook.
 */
class SimpleDecoderProvider(private val limits: SimpleLimits = SimpleLimits()) : DecoderProvider {
    override val id: String = "simple-pure"
    override val priority: Int = 20

    override fun probe(format: MediaFormat, mime: String, sizeHint: SizeHint?): DecoderVerdict {
        if (!SimpleFormatDecoders.supports(format)) return DecoderVerdict.NotDecodable(FormatCopy.NO_DECODER)
        if (sizeHint != null && (sizeHint.pixels > limits.maxPixels || sizeHint.bytes > limits.maxInputBytes)) {
            return DecoderVerdict.NotDecodable(FormatCopy.TOO_LARGE)
        }
        return DecoderVerdict.Decodable
    }
}
