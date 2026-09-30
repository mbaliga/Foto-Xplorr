package com.fotoxplorr.app.metadata

import androidx.exifinterface.media.ExifInterface
import com.fotoxplorr.core.metadata.XmpPacket

/**
 * [exif]'s own [ExifInterface.TAG_XMP], ready to be read or mutated -- or null when it is not
 * safe to touch: existing XMP this app's parser cannot understand. NOT null for "no XMP at all"
 * ([ExifInterface.TAG_XMP] blank or absent), which is a blank [XmpPacket.empty] slate instead --
 * see [XmpPacket.parse]'s own doc for why a genuine parse failure and "nothing here yet" are
 * different states a caller must treat differently. Shared by [MetadataWriter] (P0-08) and the
 * edited-copy save/overwrite flow (P0-07); both need the identical "copy through untouched
 * bytes I don't understand rather than silently replace them" contract.
 *
 * ADR-010 (WP1.2): [XmpPacket] itself moved to `:core:metadata` (jvmMain); this bridge to
 * [ExifInterface] stayed behind here because `androidx.exifinterface` is an Android-only
 * artifact a bare `jvm()` KMP target cannot link against, and [XmpPacket] never depended on it.
 */
fun readXmpAttribute(exif: ExifInterface): XmpPacket? {
    val existing = exif.xmpAttributeUtf8()
    return if (existing.isNullOrBlank()) XmpPacket.empty() else XmpPacket.parse(existing)
}

/**
 * [ExifInterface.TAG_XMP]'s raw bytes, decoded as UTF-8 -- NOT [ExifInterface.getAttribute], which
 * this app confirmed empirically (P0-08, [Utf8XmpPreservationTest][com.fotoxplorr.app.metadata.Utf8XmpPreservationTest])
 * decodes an XMP segment's bytes with the wrong charset: a real packet containing genuine
 * (non-entity-escaped) UTF-8, exactly what a Lightroom or Capture One export writes, comes back
 * through `getAttribute` with every non-ASCII character replaced by U+FFFD. XMP packets are
 * UTF-8 by spec, so this decodes the raw bytes [ExifInterface.getAttributeBytes] does provide
 * (with no matching String-returning counterpart of its own) correctly instead of trusting
 * androidx's own conversion. Every reader of [ExifInterface.TAG_XMP] in this app --
 * [readXmpAttribute] and [com.fotoxplorr.app.metadata.ExifCopier.copy] alike -- goes through
 * this, not `getAttribute`, for exactly that reason.
 */
fun ExifInterface.xmpAttributeUtf8(): String? =
    getAttributeBytes(ExifInterface.TAG_XMP)?.toString(Charsets.UTF_8)?.takeIf { it.isNotBlank() }
