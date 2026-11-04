package com.fotoxplorr.core.formats

/**
 * What the app can do with a file of a given format, once the right decoder route exists. This is
 * the "Decode / Animate / Metadata R/W" columns of `docs/handoff/MASTER-PLAN.md` §4 as data, so a
 * screen asks one question ("can I offer Edit here?") instead of re-deriving it from a mime string.
 *
 * It describes the FORMAT, not the device: whether this phone can actually decode it right now is
 * [DecoderRegistry]'s answer, and the two are deliberately separate (a format can be [Decode] in
 * principle and still be [DecoderVerdict.NotDecodable] on API 26).
 */
enum class Capability {
    /** Turn the file into pixels at all. */
    Decode,

    /** Produce a small preview cheaply (an embedded preview counts: RAW has one). */
    Thumbnail,

    /** Play more than one frame. */
    Animate,

    /** Carry high-dynamic-range data the viewer can show as such. */
    Hdr,

    /** Decode a window of the image without decoding all of it (deep zoom of a huge file). */
    RegionDecode,

    /** Save a new file of this format. Originals are never overwritten; this is "can we author it". */
    Write,

    /** Read EXIF / XMP from the file itself. */
    MetadataRead,

    /** Write metadata into the file itself (otherwise a sidecar carries it). */
    MetadataWrite,
}

/**
 * The capability matrix: one row per [MediaFormat], as a pure table.
 *
 * [of] is an exhaustive `when` with no `else`, so adding a [MediaFormat] case without a row is a
 * compile error, and `CapabilityMatrixTest` walks [MediaFormat.all] to prove the table is also
 * internally consistent (animation implies decode, and so on).
 *
 * Rows follow MASTER-PLAN.md §4. A capability listed here is a statement about the format, not a
 * promise that code for it has shipped; packs that do not exist yet are reported honestly by the
 * decoder providers, not hidden by trimming this table.
 */
object FormatCapabilities {

    private val fullRaster = setOf(
        Capability.Decode, Capability.Thumbnail, Capability.RegionDecode, Capability.Write,
        Capability.MetadataRead, Capability.MetadataWrite,
    )
    private val decodeOnly = setOf(Capability.Decode, Capability.Thumbnail)
    private val sidecarOnly = setOf(Capability.Decode, Capability.Thumbnail, Capability.MetadataRead)
    private val placeholderOnly = emptySet<Capability>()

    fun of(format: MediaFormat): Set<Capability> = when (format) {
        // Ultra HDR is a JPEG gain-map file (API 34+); region decode is BitmapRegionDecoder.
        MediaFormat.Jpeg -> fullRaster + Capability.Hdr
        MediaFormat.Png -> fullRaster
        MediaFormat.WebP -> fullRaster + Capability.Animate
        MediaFormat.Gif -> decodeOnly + Capability.Animate + Capability.Write
        MediaFormat.Bmp -> decodeOnly + Capability.MetadataRead
        MediaFormat.Ico -> decodeOnly
        MediaFormat.Heif -> setOf(
            Capability.Decode, Capability.Thumbnail, Capability.Animate, Capability.Hdr, Capability.RegionDecode,
            Capability.Write, Capability.MetadataRead,
        )
        MediaFormat.Avif -> setOf(
            Capability.Decode, Capability.Thumbnail, Capability.Animate, Capability.Hdr, Capability.MetadataRead,
        )
        MediaFormat.JpegXl -> setOf(Capability.Decode, Capability.Thumbnail, Capability.Animate, Capability.Hdr)
        // Vector: decode means rasterise; nothing here can edit the document itself.
        MediaFormat.Svg -> decodeOnly
        MediaFormat.Apng -> decodeOnly + Capability.Animate + Capability.MetadataRead
        // RAW is read for its preview; nothing is ever written into one (§4: "Never write into RAW").
        is MediaFormat.Raw -> setOf(Capability.Decode, Capability.Thumbnail, Capability.MetadataRead)
        MediaFormat.Video -> setOf(Capability.Decode, Capability.Thumbnail, Capability.Animate, Capability.MetadataRead)
        MediaFormat.Tiff -> decodeOnly + Capability.MetadataRead
        MediaFormat.Psd -> sidecarOnly
        MediaFormat.Exr -> setOf(Capability.Decode, Capability.Thumbnail, Capability.Hdr)
        MediaFormat.Hdr -> setOf(Capability.Decode, Capability.Thumbnail, Capability.Hdr)
        MediaFormat.Pnm, MediaFormat.Jp2, MediaFormat.Jxr, MediaFormat.Qoi, MediaFormat.Dds, MediaFormat.Ktx -> decodeOnly
        MediaFormat.Icns, MediaFormat.Pcx -> decodeOnly
        MediaFormat.Ora, MediaFormat.Kra -> decodeOnly
        // PSB and XCF: no permissive decoder, a placeholder is the plan (§4).
        MediaFormat.Psb, MediaFormat.Xcf -> placeholderOnly
        // Unknown: claims nothing, so no screen offers an action on a guess.
        MediaFormat.Other -> placeholderOnly
    }

    fun supports(format: MediaFormat, capability: Capability): Boolean = capability in of(format)
}
