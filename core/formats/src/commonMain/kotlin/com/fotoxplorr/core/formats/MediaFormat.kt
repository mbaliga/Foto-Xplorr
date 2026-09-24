package com.fotoxplorr.core.formats

/**
 * The SVG MIME type, in one place because two callers need the exact same string and must not
 * drift apart: this classifier, and [com.fotoxplorr.app.media.AndroidMediaStoreScanner]'s query
 * selection, which has to admit SVG rows that MediaStore's own `MEDIA_TYPE` bucketing excludes
 * (see that file for why).
 */
const val SVG_MIME_TYPE = "image/svg+xml"

/**
 * What a file actually is, for the purposes this app cares about: can the platform put a bitmap
 * on screen for it, and -- for RAW specifically -- which camera made it.
 *
 * Pure Kotlin, no Android import, same reasoning as [com.fotoxplorr.app.editor.AutoFix]: the
 * rules for "is this a RAW file, and can Android decode it" are exactly the kind of thing that
 * looks obviously right and is quietly wrong at the edges (a `.CR2` misfiled with a generic
 * `image/x-canon-cr2` mime, a DNG that decodes on one device and not another), so they belong in
 * something a JVM test can pin down rather than something eyeballed on a device.
 *
 * [MediaAsset.format][com.fotoxplorr.app.media.MediaAsset.format] is a computed property, not a
 * stored column: classification runs off [MediaAsset.mimeType][com.fotoxplorr.app.media.MediaAsset.mimeType]
 * and [MediaAsset.displayName][com.fotoxplorr.app.media.MediaAsset.displayName], both already
 * persisted for other reasons, so improving the rules here never needs a database migration.
 */
sealed interface MediaFormat {

    /**
     * Whether Android's own image codecs (Skia, reached through `BitmapFactory` /
     * `ImageDecoder`) can turn this file into a bitmap without any help from this app.
     *
     * This is the flag the UI should check before drawing a thumbnail tile: false is the
     * caller's cue to render an honest "can't preview this" placeholder instead of handing the
     * file to Coil and getting back a broken-image icon that looks like the app is buggy rather
     * than like the format genuinely isn't supported. See [Raw] for the one case where this is
     * ever false.
     */
    val isLikelyDecodable: Boolean

    data object Jpeg : MediaFormat {
        override val isLikelyDecodable = true
    }

    data object Png : MediaFormat {
        override val isLikelyDecodable = true
    }

    data object Bmp : MediaFormat {
        override val isLikelyDecodable = true
    }

    data object WebP : MediaFormat {
        override val isLikelyDecodable = true
    }

    /**
     * Both still-frame and animated decode are already covered: `coil-gif` is a declared
     * dependency (app/build.gradle.kts) and [com.fotoxplorr.app.media.MediaImage] wires its
     * decoder in whenever animation is requested. This value exists so a caller can tell "this
     * is a GIF" apart from "this is any other decodable image" without re-deriving it from the
     * mime string. Distinct from [com.fotoxplorr.app.formats.AnimationSniffer] (P0-14): that
     * answers "does this GIF actually have more than one frame," a real-bytes question this
     * value has no opinion on -- every GIF is `Gif` here, animated or not.
     */
    data object Gif : MediaFormat {
        override val isLikelyDecodable = true
    }

    /**
     * Still-frame decode is dependable from API 28's `ImageDecoder` (and needs an HEVC hardware
     * decoder) -- NOT this app's own minSdk 26, two platform releases earlier than HEIF decode
     * actually arrived; a HEIF opened on API 26/27 can fail to decode at all, device-dependent.
     * Animated HEIF sequences need that same API 28 `ImageDecoder` path, same story as animated
     * GIF/WebP in [com.fotoxplorr.app.media.MediaImage].
     */
    data object Heif : MediaFormat {
        override val isLikelyDecodable = true
    }

    /**
     * Decodable today because `coil-svg` is already a dependency (app/build.gradle.kts) --
     * it RASTERISES the vector at request size, the same shape of operation as any other format
     * [com.fotoxplorr.app.media.MediaImage] shows. That is everything viewing and thumbnailing
     * need, so [isLikelyDecodable] is true.
     *
     * It is NOT enough for editing, and that is a real gap this note is here to be honest about
     * rather than paper over. `AutoFix` and the rest of `com.fotoxplorr.app.editor` operate on a
     * decoded pixel buffer (crop, rotate, exposure, colour) -- rasterising an SVG first would
     * throw away exactly the property that makes editing one worth doing, its infinite-
     * resolution vector paths. Real SVG editing means parsing the XML into its path/shape/
     * gradient tree, exposing THAT structure to an editor surface, and re-serialising XML on
     * save: a document-model feature, not a bitmap operation with an SVG-shaped input. That is
     * a different, larger piece of work and out of scope for this classifier -- it can tell a
     * caller a file IS an SVG, never that the file can be edited as one.
     */
    data object Svg : MediaFormat {
        override val isLikelyDecodable = true
    }

    /**
     * A RAW photo, further identified by [variant] -- see [RawVariant] for exactly which
     * cameras' files decode on-device and which do not, and why.
     */
    data class Raw(val variant: RawVariant) : MediaFormat {
        override val isLikelyDecodable: Boolean get() = variant.isLikelyDecodable
    }

    data object Video : MediaFormat {
        override val isLikelyDecodable = true
    }

    /** Windows icon container -- typically wraps a PNG or 32-bit BMP internally, which is why
     *  Skia's codecs decode most real-world ICOs despite ICO never appearing on Android's own
     *  documented `BitmapFactory`/`ImageDecoder` format list (the same "best-effort, not a
     *  contract" caveat [Raw]'s DNG case already carries). Section 4 of MASTER-PLAN.md groups it
     *  with the platform-decodable formats on exactly this basis. */
    data object Ico : MediaFormat {
        override val isLikelyDecodable = true
    }

    /** Still-frame decode from API 31 -- this app's own minSdk is 26, five releases earlier, same
     *  "best-effort on this app's actual floor" caveat as [Heif]. Animated AVIF needs a pack or
     *  API 36 per MASTER-PLAN.md §4; that gap doesn't change [isLikelyDecodable] here any more
     *  than an un-sniffed animated GIF does -- this value only answers "is this file an AVIF." */
    data object Avif : MediaFormat {
        override val isLikelyDecodable = true
    }

    /** No Android platform codec at all (MASTER-PLAN.md §4: needs the `jxl-coder` pack, WP2.3).
     *  [FormatSniffer] can already tell a JPEG XL file apart from everything else -- that's
     *  "Discover", not "Decode", and Phase 1 only needs the former. */
    data object JpegXl : MediaFormat {
        override val isLikelyDecodable = false
    }

    /** Needs a pack (MASTER-PLAN.md §4). */
    data object Tiff : MediaFormat {
        override val isLikelyDecodable = false
    }

    /** Composite-flattened decode needs a pack (`stb_image`, MASTER-PLAN.md §4); this value is
     *  Discover-only in Phase 1, same as every other pack-gated format below. */
    data object Psd : MediaFormat {
        override val isLikelyDecodable = false
    }

    /** Needs a pack (`tinyexr` + tone-mapping, MASTER-PLAN.md §4). */
    data object Exr : MediaFormat {
        override val isLikelyDecodable = false
    }

    /** Radiance RGBE HDR. Needs a pack (`stb_image`, MASTER-PLAN.md §4). */
    data object Hdr : MediaFormat {
        override val isLikelyDecodable = false
    }

    /** Netpbm (PBM/PGM/PPM). Needs a pack (`stb_image`, MASTER-PLAN.md §4). */
    data object Pnm : MediaFormat {
        override val isLikelyDecodable = false
    }

    /** JPEG 2000 (raw codestream or JP2 container). Needs a pack (OpenJPEG, MASTER-PLAN.md §4). */
    data object Jp2 : MediaFormat {
        override val isLikelyDecodable = false
    }

    /** JPEG XR / HD Photo. Needs a pack (`jxrlib`, MASTER-PLAN.md §4). */
    data object Jxr : MediaFormat {
        override val isLikelyDecodable = false
    }

    /** Needs a pack (`qoi`, MASTER-PLAN.md §4). */
    data object Qoi : MediaFormat {
        override val isLikelyDecodable = false
    }

    /** DirectDraw Surface. Needs a pack (`dds-ktx`, MASTER-PLAN.md §4). */
    data object Dds : MediaFormat {
        override val isLikelyDecodable = false
    }

    /** Khronos texture container (KTX1 or KTX2). Needs a pack (`dds-ktx`, MASTER-PLAN.md §4). */
    data object Ktx : MediaFormat {
        override val isLikelyDecodable = false
    }

    /** Apple icon container. No pack planned (not on MASTER-PLAN.md §4's Notes column for any
     *  entry) -- Discover-only for the foreseeable future, not just Phase 1. */
    data object Icns : MediaFormat {
        override val isLikelyDecodable = false
    }

    /** ZSoft Paintbrush. No pack planned. Discover-only. */
    data object Pcx : MediaFormat {
        override val isLikelyDecodable = false
    }

    /** GIMP's native format. MASTER-PLAN.md §4 is explicit: "No permissive decoder" exists for
     *  XCF at all -- a thumbnail-only placeholder is the plan, not a future pack. */
    data object Xcf : MediaFormat {
        override val isLikelyDecodable = false
    }

    /**
     * Everything this classifier does not recognise. Deliberately [isLikelyDecodable] = true,
     * not false: "unrecognised" is not the same claim as "known broken", and defaulting to true
     * matches what already happens today for every format this app has not specifically singled
     * out -- it goes to Coil and either renders or produces Coil's own broken-image state, no
     * regression either way. A false default here would invent placeholders for formats this
     * classifier has simply never been taught about, which is its own kind of dishonesty.
     */
    data object Other : MediaFormat {
        override val isLikelyDecodable = true
    }

    companion object {

        /**
         * Classify a file from its MediaStore [mimeType] and [fileName].
         *
         * RAW is checked by EXTENSION FIRST, mime second, because RAW mime types are the least
         * reliable signal here: MediaStore reports RAW files under a scattering of vendor
         * mime strings (`image/x-canon-cr2`, `image/x-sony-arw`, `image/x-adobe-dng`, and on
         * some OEM skins a generic `application/octet-stream` when the platform's mime map has
         * no entry for the extension at all) -- there is no one string every device agrees on.
         * The file extension is the one thing every RAW file actually has in common.
         */
        fun classify(mimeType: String, fileName: String): MediaFormat {
            val mime = mimeType.trim().lowercase()
            val extension = fileName.substringAfterLast('.', missingDelimiterValue = "").lowercase()

            RawVariant.byExtension(extension)?.let { return Raw(it) }

            return when {
                mime == SVG_MIME_TYPE || extension == "svg" -> Svg
                mime == "image/gif" || extension == "gif" -> Gif
                mime == "image/heif" || mime == "image/heic" || extension == "heif" || extension == "heic" -> Heif
                mime == "image/webp" || extension == "webp" -> WebP
                mime == "image/png" || extension == "png" -> Png
                mime == "image/jpeg" || mime == "image/jpg" || extension == "jpg" || extension == "jpeg" -> Jpeg
                mime == "image/bmp" || extension == "bmp" -> Bmp
                mime.startsWith("video/") -> Video
                else -> Other
            }
        }
    }
}

/**
 * RAW file variants this app recognises, by filename extension.
 *
 * ## What Android can actually decode, and why
 *
 * This app carries no RAW decoder of its own -- see the constraints that shaped this file: a
 * real demosaicing RAW decoder is a heavyweight, per-vendor undertaking (Adobe's DNG SDK alone
 * is tens of thousands of lines, and CR2/NEF/ARW each need their own), and pulling one in was
 * explicitly ruled out for this change. What each variant gets instead is an honest answer to
 * "can the PLATFORM put something on screen for this at all":
 *
 *  - **DNG** is the one RAW format Android's own image codec sometimes handles. AOSP's Skia
 *    build has carried a raw-image codec (built on Adobe's DNG SDK and Google's `piex` preview
 *    extractor) since Lollipop, and in practice it decodes a DNG using "baseline" compression --
 *    uncompressed or classic lossless-JPEG sensor data in a standard TIFP/EXIF structure, which
 *    covers most DNGs a phone camera or Adobe DNG Converter actually produces. That said, this
 *    is NOT a documented `BitmapFactory`/`ImageDecoder` guarantee -- the public supported-format
 *    list for both is JPEG/PNG/GIF/BMP/WebP/HEIF, and RAW appears on neither -- so it is OEM-
 *    and version-dependent, and it fails outright for DNGs using newer compression (lossy,
 *    JPEG-XL) or an unusual sensor layout. [RawVariant.isLikelyDecodable] is true for DNG, and
 *    the "likely" is doing real work: this is a best-effort platform behaviour, not a contract.
 *  - **CR2, CR3, NEF, ARW, ORF, RW2, RAF, SRW, PEF** are each one camera vendor's own proprietary
 *    encoding, and Android ships no decoder for any of them, official or otherwise. Handing one
 *    of these to `BitmapFactory.decodeFile`/`ImageDecoder.decodeBitmap` does not degrade
 *    gracefully -- it returns null / throws, because no registered codec recognises the byte
 *    stream at all. [RawVariant.isLikelyDecodable] is false, unconditionally, for every one of
 *    these.
 *
 * A false [isLikelyDecodable] is not a dead end for the UI, only for a full-resolution decode
 * through this app's normal image path. Every format on this list is TIFF/EXIF-structured
 * underneath and, per the DNG/TIFF-EP convention essentially all of them follow, carries an
 * embedded preview JPEG that `androidx.exifinterface` (already a dependency of this module, via
 * `ExifInterface.getThumbnail()`/`thumbnailBitmap`) can read without touching the sensor data at
 * all. Pulling that thumbnail out is a rendering concern for whichever surface draws the tile,
 * not this classifier's job -- but it is the reason a "can't decode this" placeholder should say
 * exactly that, rather than reading as a generic broken-file error: the pixels usually exist,
 * just not on the path this app's ordinary image loader reads them through.
 */
enum class RawVariant(
    val extension: String,
    /** Which camera maker's format this is, for a placeholder to name honestly ("Canon RAW",
     *  not just "RAW"). */
    val vendor: String,
    val isLikelyDecodable: Boolean,
) {
    DNG(extension = "dng", vendor = "Adobe DNG", isLikelyDecodable = true),
    CR2(extension = "cr2", vendor = "Canon", isLikelyDecodable = false),
    CR3(extension = "cr3", vendor = "Canon", isLikelyDecodable = false),
    NEF(extension = "nef", vendor = "Nikon", isLikelyDecodable = false),
    ARW(extension = "arw", vendor = "Sony", isLikelyDecodable = false),
    ORF(extension = "orf", vendor = "Olympus / OM System", isLikelyDecodable = false),
    RW2(extension = "rw2", vendor = "Panasonic", isLikelyDecodable = false),
    RAF(extension = "raf", vendor = "Fujifilm", isLikelyDecodable = false),
    SRW(extension = "srw", vendor = "Samsung", isLikelyDecodable = false),
    PEF(extension = "pef", vendor = "Pentax / Ricoh", isLikelyDecodable = false),
    // P0-16: added for recognition/labelling only -- every one of these is `isLikelyDecodable =
    // false`, matching every proprietary RAW variant above; the existing ten's own flags are left
    // untested-on-device and unchanged.
    NRW(extension = "nrw", vendor = "Nikon", isLikelyDecodable = false),
    THREE_FR(extension = "3fr", vendor = "Hasselblad", isLikelyDecodable = false),
    IIQ(extension = "iiq", vendor = "Phase One", isLikelyDecodable = false),
    ERF(extension = "erf", vendor = "Epson", isLikelyDecodable = false),
    MRW(extension = "mrw", vendor = "Minolta", isLikelyDecodable = false),
    X3F(extension = "x3f", vendor = "Sigma", isLikelyDecodable = false),
    DCR(extension = "dcr", vendor = "Kodak", isLikelyDecodable = false),
    KDC(extension = "kdc", vendor = "Kodak", isLikelyDecodable = false),
    MEF(extension = "mef", vendor = "Mamiya", isLikelyDecodable = false),
    MOS(extension = "mos", vendor = "Leaf", isLikelyDecodable = false),
    RWL(extension = "rwl", vendor = "Leica", isLikelyDecodable = false),
    SR2(extension = "sr2", vendor = "Sony", isLikelyDecodable = false),
    SRF(extension = "srf", vendor = "Sony", isLikelyDecodable = false),
    GPR(extension = "gpr", vendor = "GoPro", isLikelyDecodable = false),
    CRW(extension = "crw", vendor = "Canon", isLikelyDecodable = false),
    ;

    companion object {
        private val byExtension = entries.associateBy { it.extension }

        /** Null for anything that is not one of these extensions -- deliberately not a
         *  fallback "unknown RAW" case, since a `.xyz` file this table has never heard of is
         *  far more likely to just not be RAW at all than to be a RAW variant worth a wrong
         *  guess. */
        fun byExtension(extension: String): RawVariant? = byExtension[extension]
    }
}
