package com.fotoxplorr.core.formats.raw

/** Where in the container a preview JPEG was found (for diagnostics and tests). */
enum class RawPreviewKind {
    /** IFD1 (or a later chained IFD) JPEGInterchangeFormat: the classic small thumbnail. */
    IFD_THUMBNAIL,

    /** IFD0 JPEGInterchangeFormat. */
    IFD0_JPEG,

    /** A SubIFD (DNG, NEF, ARW, ...) JPEGInterchangeFormat or JPEG strip: usually the big preview. */
    SUB_IFD_PREVIEW,

    /** StripOffsets/StripByteCounts with Compression 6 (old JPEG) or 7 (new JPEG). */
    STRIP_JPEG,

    /** A vendor MakerNote's preview offset/length (Olympus, Pentax, Sony) or Nikon's PreviewIFD. */
    MAKER_NOTE_PREVIEW,

    /** Panasonic RW2 IFD0 tag 0x002E JpgFromRaw. */
    JPG_FROM_RAW,

    /** Fujifilm RAF header's embedded JPEG. */
    RAF_EMBEDDED,

    /** Canon CR3 `PRVW` box. */
    ISOBMFF_PRVW,

    /** Canon CR3 `THMB` box. */
    ISOBMFF_THMB,
}

/**
 * A validated embedded JPEG inside a RAW file: the byte range [offset, offset + length) starts with
 * FFD8, ends with FFD9 and declares a baseline/extended/progressive SOF of [width] x [height].
 * [orientation] is the EXIF orientation (1..8) of the container (falling back to the JPEG's own
 * EXIF, then 1) that the caller must apply, since the preview's pixels are stored sensor-up.
 */
data class RawPreview(
    val offset: Long,
    val length: Long,
    val width: Int,
    val height: Int,
    val orientation: Int,
    val kind: RawPreviewKind,
) {
    val pixels: Long get() = width.toLong() * height.toLong()
    val longEdge: Int get() = maxOf(width, height)
}
