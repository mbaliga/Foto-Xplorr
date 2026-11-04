package com.fotoxplorr.core.metadata

import java.time.LocalDate
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneOffset

enum class SidecarFlag { PICK, REJECT, NONE }

/**
 * The user-facing annotations that travel in an XMP sidecar (WP3.8). Every field is "no opinion"
 * when null (or empty for the collections): [merge] and [XmpAnnotationCodec.apply] never turn
 * "unknown" into "cleared", so a sidecar's foreign `dc:creator` survives an app that never set one.
 * To CLEAR a scalar say so explicitly: rating 0, label "", flag [SidecarFlag.NONE], text "".
 *
 * @property keywords flat keywords (`dc:subject`); @property keywordPaths hierarchical keywords
 * as `Parent|Child` (`lr:hierarchicalSubject`, Lightroom's and digiKam's convention).
 * @property metadataDate `xmp:MetadataDate`, ISO 8601 as the tools write it: what [merge] orders by.
 */
data class SidecarAnnotations(
    val rating: Int? = null,
    val label: String? = null,
    val flag: SidecarFlag? = null,
    val keywords: Set<String> = emptySet(),
    val keywordPaths: Set<String> = emptySet(),
    val title: String? = null,
    val caption: String? = null,
    val creators: List<String> = emptyList(),
    val copyright: String? = null,
    val metadataDate: String? = null,
) {
    companion object {
        /**
         * Newest wins by [metadataDate] (a missing or unparseable date is oldest; a tie goes to
         * [local], the user's own latest action). Scalars: the winner's non-null value, else the
         * loser's. Keywords: the UNION, always -- a keyword another tool added is never deleted
         * by a merge; removing one is the explicit `removed*` argument of the write.
         */
        fun merge(local: SidecarAnnotations, remote: SidecarAnnotations): SidecarAnnotations {
            val localWins = (epochOf(local.metadataDate) ?: Long.MIN_VALUE) >=
                (epochOf(remote.metadataDate) ?: Long.MIN_VALUE)
            val (win, lose) = if (localWins) local to remote else remote to local
            return SidecarAnnotations(
                rating = win.rating ?: lose.rating,
                label = win.label ?: lose.label,
                flag = win.flag ?: lose.flag,
                keywords = local.keywords + remote.keywords,
                keywordPaths = local.keywordPaths + remote.keywordPaths,
                title = win.title ?: lose.title,
                caption = win.caption ?: lose.caption,
                creators = win.creators.ifEmpty { lose.creators },
                copyright = win.copyright ?: lose.copyright,
                metadataDate = listOfNotNull(local.metadataDate, remote.metadataDate)
                    .maxByOrNull { epochOf(it) ?: Long.MIN_VALUE },
            )
        }

        /** Epoch seconds of an ISO 8601 date(-time), local times read as UTC; null if unparseable. */
        fun epochOf(iso: String?): Long? {
            val text = iso?.trim()?.takeIf { it.isNotEmpty() } ?: return null
            runCatching { return OffsetDateTime.parse(text).toEpochSecond() }
            runCatching { return LocalDateTime.parse(text).toEpochSecond(ZoneOffset.UTC) }
            runCatching { return LocalDate.parse(text).atStartOfDay().toEpochSecond(ZoneOffset.UTC) }
            return null
        }
    }
}

/**
 * Maps [SidecarAnnotations] to and from an [XmpPacket]. Every write goes through the packet's
 * in-place setters, so whatever else the file holds -- Lightroom `crs:` develop settings, darktable
 * `darktable:history`, digiKam `digiKam:` / `MicrosoftPhoto:` blocks, `xmpMM:History` -- is carried
 * through untouched. (Re-serialising reformats whitespace and attribute order; the infoset of every
 * part not listed below is identical, which `SidecarForeignContentTest` asserts.)
 *
 * What we own, and nothing else: `xmp:Rating`, `xmp:Label`, `xmp:MetadataDate`, `fotoz:Flag`,
 * `dc:subject`, `lr:hierarchicalSubject`, `dc:title`, `dc:description`, `dc:creator`, `dc:rights`.
 */
object XmpAnnotationCodec {
    /** Our own documented namespace for the pick/reject flag (XMP has no standard one; Lightroom's
     *  reject is `xmp:Rating=-1`, which is also honoured on read). */
    const val FOTOZ_NS = "http://ns.fotoz.app/1.0/"
    const val LR_NS = "http://ns.adobe.com/lightroom/1.0/"
    const val FLAG_PICK = "pick"
    const val FLAG_REJECT = "reject"

    fun read(packet: XmpPacket): SidecarAnnotations {
        val rawRating = packet.intValue(XmpPacket.XMP_NS, "Rating")
        val explicitFlag = when (packet.stringValue(FOTOZ_NS, "Flag")?.lowercase()) {
            FLAG_PICK -> SidecarFlag.PICK
            FLAG_REJECT -> SidecarFlag.REJECT
            else -> null
        }
        return SidecarAnnotations(
            // -1 is "rejected" in Adobe's scheme, not a star count.
            rating = rawRating?.takeIf { it in 0..5 },
            label = packet.stringValue(XmpPacket.XMP_NS, "Label"),
            flag = explicitFlag ?: if (rawRating == -1) SidecarFlag.REJECT else null,
            keywords = packet.bag(XmpPacket.DC_NS, "subject").toSet(),
            keywordPaths = packet.bag(LR_NS, "hierarchicalSubject").toSet(),
            title = packet.langAlt(XmpPacket.DC_NS, "title"),
            caption = packet.langAlt(XmpPacket.DC_NS, "description"),
            creators = packet.seq(XmpPacket.DC_NS, "creator"),
            copyright = packet.langAlt(XmpPacket.DC_NS, "rights"),
            metadataDate = packet.stringValue(XmpPacket.XMP_NS, "MetadataDate"),
        )
    }

    /**
     * Writes [annotations] into [packet]. [stamp] becomes `xmp:MetadataDate`. Keywords already in
     * the packet stay unless named in [removedKeywords]/[removedPaths].
     */
    fun apply(
        packet: XmpPacket,
        annotations: SidecarAnnotations,
        stamp: String,
        removedKeywords: Set<String> = emptySet(),
        removedPaths: Set<String> = emptySet(),
    ) {
        val rawBefore = packet.intValue(XmpPacket.XMP_NS, "Rating")
        val rating = annotations.rating?.coerceIn(0, 5)
        when {
            annotations.flag == SidecarFlag.REJECT && rating == null ->
                packet.setIntValue(XmpPacket.XMP_NS, "xmp", "Rating", -1)
            rating != null -> packet.setIntValue(XmpPacket.XMP_NS, "xmp", "Rating", rating)
            annotations.flag != null && rawBefore == -1 ->
                packet.setIntValue(XmpPacket.XMP_NS, "xmp", "Rating", null) // no longer rejected
        }
        annotations.label?.let { packet.setStringValue(XmpPacket.XMP_NS, "xmp", "Label", it) }
        when (annotations.flag) {
            SidecarFlag.PICK -> packet.setStringValue(FOTOZ_NS, "fotoz", "Flag", FLAG_PICK)
            SidecarFlag.REJECT -> packet.setStringValue(FOTOZ_NS, "fotoz", "Flag", FLAG_REJECT)
            SidecarFlag.NONE -> packet.setStringValue(FOTOZ_NS, "fotoz", "Flag", null)
            null -> Unit
        }
        mergeBag(packet, XmpPacket.DC_NS, "dc", "subject",
            annotations.keywords + annotations.keywordPaths.flatMap { it.split('|') }, removedKeywords)
        mergeBag(packet, LR_NS, "lr", "hierarchicalSubject", annotations.keywordPaths, removedPaths)
        annotations.title?.let { packet.setLangAltDefault(XmpPacket.DC_NS, "dc", "title", it) }
        annotations.caption?.let { packet.setLangAltDefault(XmpPacket.DC_NS, "dc", "description", it) }
        if (annotations.creators.isNotEmpty()) {
            packet.setSeq(XmpPacket.DC_NS, "dc", "creator", annotations.creators)
        }
        annotations.copyright?.let { packet.setLangAltDefault(XmpPacket.DC_NS, "dc", "rights", it) }
        packet.setStringValue(XmpPacket.XMP_NS, "xmp", "MetadataDate", stamp)
    }

    private fun mergeBag(
        packet: XmpPacket, ns: String, prefix: String, name: String, add: Set<String>, remove: Set<String>,
    ) {
        if (add.isEmpty() && remove.isEmpty()) return
        val result = (packet.bag(ns, name) + add).filterNot { it in remove }.distinct()
        packet.setBag(ns, prefix, name, result)
    }

    // ---- bytes: the pure entry points the indexer and the Android glue use ----

    /** Parsed annotations of a sidecar's bytes; null when the bytes are not parseable XML. */
    fun readBytes(bytes: ByteArray): SidecarAnnotations? = parse(bytes)?.let(::read)

    /**
     * The new sidecar bytes: [existing] (null = no file yet) with [annotations] applied, merged
     * with what [existing] already says by [SidecarAnnotations.merge]. Null when [existing] is
     * present but unparseable -- the caller must then NOT write, or a foreign file is destroyed.
     */
    fun update(
        existing: ByteArray?,
        annotations: SidecarAnnotations,
        stamp: String,
        removedKeywords: Set<String> = emptySet(),
        removedPaths: Set<String> = emptySet(),
    ): ByteArray? {
        val packet = if (existing == null) XmpPacket.empty() else parse(existing) ?: return null
        val merged = SidecarAnnotations.merge(annotations.copy(metadataDate = stamp), read(packet))
        // merge's keyword union would resurrect removed ones; apply() handles the removal
        apply(packet, merged, stamp, removedKeywords, removedPaths)
        return packet.serialize().toByteArray(Charsets.UTF_8)
    }

    private fun parse(bytes: ByteArray): XmpPacket? =
        XmpPacket.parse(bytes.toString(Charsets.UTF_8).removePrefix("﻿"))
}
