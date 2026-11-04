package com.fotoxplorr.app.media

import android.graphics.ImageDecoder
import android.os.Build
import com.fotoxplorr.core.formats.DecoderProvider
import com.fotoxplorr.core.formats.DecoderVerdict
import com.fotoxplorr.core.formats.FormatCopy
import com.fotoxplorr.core.formats.FormatLabels
import com.fotoxplorr.core.formats.MediaFormat
import com.fotoxplorr.core.formats.SizeHint

/**
 * What Android itself can put on screen, as a [DecoderProvider]. Installed packs and the RAW
 * preview extractor are separate providers merged by [DecoderRegistry][com.fotoxplorr.core.formats.DecoderRegistry].
 *
 * Two layers, because neither alone is honest on this app's minSdk of 26:
 *  1. A static table of the API level each platform codec arrived at: HEIC from 28, AVIF from 31
 *     (MASTER-PLAN §4). Below that the answer is a refusal without asking the device.
 *  2. From API 29, where [ImageDecoder.isMimeTypeSupported] exists, the device is asked about
 *     AVIF, the format whose support varies by device and OEM codec set. A
 *     `false` answer wins over the table; `null` (cannot ask) leaves the table's answer. JPEG,
 *     PNG, GIF, BMP, WebP and HEIC are never asked (see the HEIC note below).
 *
 * RAW, EXR, PSD, TIFF and the other pack formats are refused honestly until a pack provider
 * exists; DNG is the one RAW the platform sometimes decodes, so it stays "decodable" and a failed
 * decode falls back to the placeholder at the tile (see [com.fotoxplorr.core.formats.tileState]).
 *
 * [sdkInt] and [mimeSupported] are injected so the whole table is a JVM test; the app passes
 * [AndroidMimeProbe].
 */
class PlatformDecoderProvider(
    private val sdkInt: Int,
    private val mimeSupported: (String) -> Boolean? = { null },
) : DecoderProvider {

    override val id: String = "platform"
    override val priority: Int = 10

    override fun probe(format: MediaFormat, mime: String, sizeHint: SizeHint?): DecoderVerdict = when (format) {
        MediaFormat.Jpeg, MediaFormat.Png, MediaFormat.Gif, MediaFormat.Bmp, MediaFormat.WebP,
        MediaFormat.Ico, MediaFormat.Apng,
        // Rasterised by coil-svg, video frames by coil-video; neither is the platform image codec.
        MediaFormat.Svg, MediaFormat.Video,
        // Unknown stays "try it": the tile's failure fallback is the safety net.
        MediaFormat.Other -> DecoderVerdict.Decodable

        // HEIC is table-only: from API 29 AOSP ships a software HEVC decoder, and on API 28 a phone
        // without one fails at load time, which the tile turns into a placeholder. Asking the
        // device would only add a way to hide a photo that does decode.
        MediaFormat.Heif -> gated(format, null, minApi = Build.VERSION_CODES.P, release = "9")
        MediaFormat.Avif -> gated(format, "image/avif", minApi = Build.VERSION_CODES.S, release = "12")

        is MediaFormat.Raw ->
            if (format.variant.isLikelyDecodable) DecoderVerdict.Decodable
            else DecoderVerdict.NotDecodable(FormatCopy.RAW_NO_PREVIEW)

        // Packs named in MASTER-PLAN §4 / WP2.3; none is installed in this build.
        MediaFormat.JpegXl, MediaFormat.Tiff, MediaFormat.Psd, MediaFormat.Exr, MediaFormat.Hdr,
        MediaFormat.Pnm, MediaFormat.Jp2, MediaFormat.Jxr, MediaFormat.Qoi, MediaFormat.Dds,
        MediaFormat.Ktx -> DecoderVerdict.NotDecodable(FormatCopy.needsPack(FormatLabels.kind(format)))

        // No decoder planned (PSB, XCF, ICNS, PCX) or not built yet (ORA, KRA unzip).
        MediaFormat.Psb, MediaFormat.Xcf, MediaFormat.Icns, MediaFormat.Pcx, MediaFormat.Ora,
        MediaFormat.Kra, MediaFormat.Tga -> DecoderVerdict.NotDecodable(FormatCopy.NO_DECODER)
    }

    private fun gated(format: MediaFormat, canonicalMime: String?, minApi: Int, release: String): DecoderVerdict {
        val kind = FormatLabels.badge(format)
        if (sdkInt < minApi) return DecoderVerdict.NotDecodable(FormatCopy.needsNewerAndroid(kind, release))
        if (canonicalMime != null && sdkInt >= Build.VERSION_CODES.Q && mimeSupported(canonicalMime) == false) {
            return DecoderVerdict.NotDecodable(FormatCopy.notOnThisPhone(kind))
        }
        return DecoderVerdict.Decodable
    }
}

/**
 * Wraps a raw "is this mime supported" question so a broken answerer cannot hide photos: an
 * answerer that denies `image/jpeg` (every Android release decodes JPEG; a stub or an
 * unimplemented native layer, as under Robolectric, says no to everything) is not trusted, and
 * every question then gets `null`, "cannot tell".
 */
internal fun trustedMimeProbe(raw: (String) -> Boolean): (String) -> Boolean? {
    val trusted by lazy(LazyThreadSafetyMode.PUBLICATION) { raw("image/jpeg") }
    return { mime -> if (trusted) raw(mime) else null }
}

/** Asks the device's [ImageDecoder]; `null` below API 29, where the question does not exist. */
object AndroidMimeProbe : (String) -> Boolean? {
    private val probe = trustedMimeProbe { mime -> runCatching { ImageDecoder.isMimeTypeSupported(mime) }.getOrDefault(false) }

    override fun invoke(mime: String): Boolean? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) probe(mime) else null
}
