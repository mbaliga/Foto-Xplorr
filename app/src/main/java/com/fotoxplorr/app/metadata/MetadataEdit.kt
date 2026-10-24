package com.fotoxplorr.app.metadata

import com.fotoxplorr.core.metadata.XmpPacket

/**
 * One photo's professional metadata, as a photographer edits it -- not as EXIF or XMP tag names,
 * which is exactly the layer [MetadataWriter] hides behind this.
 *
 * Every field follows ONE convention, matching [com.fotoxplorr.app.organize.LibraryStore.setCaption]'s
 * own already-established rule rather than inventing a second one: `null` means "leave this field
 * alone", blank/empty means "clear it", anything else means "set it to this". That single rule is
 * what lets the exact same type serve a single photo's edit (every field the user actually typed
 * into is non-null, everything else is null) and a batch edit across a whole selection (only the
 * fields the photographer chose to touch across the WHOLE batch are non-null; a batch edit that
 * only sets copyright must never blank out captions on every photo in it because the field
 * defaulted to something other than "leave alone").
 *
 * @param keywordsToAdd deliberately ADDITIVE, never a replacement list -- see [MetadataWriter]'s
 *   class doc for why a batch keyword edit must never overwrite a photo's existing keywords with
 *   whatever the batch happened to specify.
 * @param keywordsToRemove the mirror of [keywordsToAdd]: keywords taken OFF a photo's existing
 *   list, by value, leaving every keyword not named here untouched. Still not "replace the whole
 *   list" -- a caller that wants a photo's keywords emptied entirely passes every existing keyword
 *   here rather than this type growing a third, more destructive shape. A keyword named in both
 *   [keywordsToAdd] and [keywordsToRemove] in the same edit is removed: removal is the more
 *   deliberate, more specific action of the two; see [applyToXmp] for where that order is fixed.
 * @param rating `0` clears a rating (no stars, the same "unrated" a fresh photo starts at), `1`
 *   through `5` sets one. `null` leaves whatever rating (if any) already exists untouched.
 * @param setLocation a coordinate to write into the file's own GPS tags, or null to leave the
 *   file's location as it is. Kept as its own action, separate and mutually exclusive with
 *   [clearLocation], for the same reason `PhotoDetailRoom` already exposes `onSetLocation` and
 *   `onClearLocation` as two different callbacks rather than one nullable coordinate: nothing
 *   about a coordinate value can mean "remove the location entirely" the way blank means "clear"
 *   for a text field.
 * @param clearLocation strips the file's own GPS tags. Meaningless combined with [setLocation]
 *   set at the same time; [MetadataWriter] treats that combination as "set", the same way it
 *   would make no sense for a caller to ask for both in one edit and only one can win.
 */
data class MetadataEdit(
    val caption: String? = null,
    val creator: String? = null,
    val copyright: String? = null,
    val rating: Int? = null,
    val keywordsToAdd: List<String> = emptyList(),
    val keywordsToRemove: List<String> = emptyList(),
    val setLocation: GpsCoordinate? = null,
    val clearLocation: Boolean = false,
) {
    /** True when this edit would not actually change anything -- see [MetadataWriter.write]. */
    val isEmpty: Boolean
        get() = caption == null && creator == null && copyright == null && rating == null &&
            keywordsToAdd.isEmpty() && keywordsToRemove.isEmpty() &&
            setLocation == null && !clearLocation

    /**
     * Combines this edit with a LATER one arriving before this one has been written (P0-08 item
     * 6) -- `other`'s non-null fields win over this edit's for the same reason a second keystroke
     * always wins over a first for the SAME field, and [keywordsToAdd] unions rather than picking
     * one side, since two field commits adding different keywords (or the same photo tagged from
     * two different UI surfaces) should not make either add silently disappear.
     *
     * [keywordsToAdd] and [keywordsToRemove] follow the same "later intent wins" rule per keyword:
     * a keyword `other` removes is dropped from this edit's adds (and stays on the merged
     * removals, so a copy already carrying it loses it too), and a keyword `other` adds is dropped
     * from this edit's removals -- otherwise an add-then-remove (or remove-then-add) pair of
     * queued edits would resolve by [applyToXmp]'s "removal wins" order instead of by which
     * action actually came last.
     *
     * [setLocation]/[clearLocation] are merged as ONE combined intent, not two independent fields:
     * `other` only overrides this edit's location intent when `other` actually expresses one
     * (a non-null [setLocation] or `clearLocation = true`) -- an `other` that touched neither must
     * not silently cancel a location change THIS edit queued, the same "null means leave alone"
     * rule every other field on this type already follows.
     */
    fun merge(other: MetadataEdit): MetadataEdit {
        val otherTouchesLocation = other.setLocation != null || other.clearLocation
        return MetadataEdit(
            caption = other.caption ?: caption,
            creator = other.creator ?: creator,
            copyright = other.copyright ?: copyright,
            rating = other.rating ?: rating,
            keywordsToAdd = (keywordsToAdd.filterNot { it in other.keywordsToRemove } + other.keywordsToAdd)
                .distinct(),
            keywordsToRemove = (keywordsToRemove.filterNot { it in other.keywordsToAdd } + other.keywordsToRemove)
                .distinct(),
            setLocation = if (otherTouchesLocation) other.setLocation else setLocation,
            clearLocation = if (otherTouchesLocation) other.clearLocation else clearLocation,
        )
    }
}

data class GpsCoordinate(val latitude: Double, val longitude: Double) {
    init {
        require(latitude in -90.0..90.0) { "Latitude $latitude is out of range" }
        require(longitude in -180.0..180.0) { "Longitude $longitude is out of range" }
    }
}

/**
 * Reads current professional metadata off an already-open [XmpPacket] and a caller-supplied EXIF
 * reader, for a UI that needs to show what is there before someone edits it. Pure and
 * [XmpPacket]-shaped rather than [android.media.ExifInterface]-shaped so the EXIF half of it is
 * genuinely unit-testable -- see [MetadataWriterTest] and [XmpPacketTest].
 *
 * @param exifImageDescription/exifArtist/exifCopyright the plain EXIF strings, already read by
 *   the caller (`ExifInterface.getAttribute(...)`) -- this function does not touch
 *   `ExifInterface` itself, only combines its output with the XMP side of the same facts.
 */
fun currentMetadataFrom(
    xmp: XmpPacket?,
    exifImageDescription: String?,
    exifArtist: String?,
    exifCopyright: String?,
): CurrentMetadata = CurrentMetadata(
    // EXIF and XMP are meant to agree once this app has written both; where they do not (a file
    // edited by some OTHER tool that only touched one side), XMP wins as the read-back value --
    // it is the richer, structured side (language-tagged, list-shaped) and the one every field
    // this app writes lives in unconditionally, where EXIF only gets the plain-string subset.
    caption = xmp?.langAlt(XmpPacket.DC_NS, "description") ?: exifImageDescription,
    creator = xmp?.seq(XmpPacket.DC_NS, "creator")?.firstOrNull() ?: exifArtist,
    copyright = xmp?.langAlt(XmpPacket.DC_NS, "rights") ?: exifCopyright,
    rating = xmp?.intValue(XmpPacket.XMP_NS, "Rating"),
    keywords = xmp?.bag(XmpPacket.DC_NS, "subject").orEmpty(),
)

data class CurrentMetadata(
    val caption: String?,
    val creator: String?,
    val copyright: String?,
    val rating: Int?,
    val keywords: List<String>,
) {
    companion object {
        val EMPTY = CurrentMetadata(null, null, null, null, emptyList())
    }
}

/**
 * Applies [edit] to an already-parsed [XmpPacket] in place -- the pure half of what
 * [MetadataWriter.write] does, split out purely so it is testable without a real file. The EXIF
 * half has no equivalent pure function: `ExifInterface`'s own `setAttribute`/`setLatLong` calls
 * ARE the whole of that side of the work, and wrapping them in another layer here would just be
 * indirection with nothing left to test once [XmpPacketTest] and this function are both covered.
 */
fun applyToXmp(packet: XmpPacket, edit: MetadataEdit) {
    edit.caption?.let { packet.setLangAlt(XmpPacket.DC_NS, "dc", "description", it) }
    edit.creator?.let { creator ->
        packet.setSeq(XmpPacket.DC_NS, "dc", "creator", if (creator.isBlank()) emptyList() else listOf(creator))
    }
    edit.copyright?.let { packet.setLangAlt(XmpPacket.DC_NS, "dc", "rights", it) }
    edit.rating?.let { packet.setIntValue(XmpPacket.XMP_NS, "xmp", "Rating", it.coerceIn(0, 5).takeIf { r -> r != 0 }) }
    if (edit.keywordsToAdd.isNotEmpty() || edit.keywordsToRemove.isNotEmpty()) {
        // Removal wins over addition for the same keyword in the same edit -- see
        // MetadataEdit.keywordsToRemove's own doc for why that is the deliberate order rather
        // than an accident of "whichever line runs second".
        val merged = (packet.bag(XmpPacket.DC_NS, "subject") + edit.keywordsToAdd)
            .distinct()
            .filterNot { it in edit.keywordsToRemove }
        packet.setBag(XmpPacket.DC_NS, "dc", "subject", merged)
    }
    // setLocation wins over clearLocation if a caller somehow asks for both in one edit -- the
    // same precedence applyMetadataToExif's own identical comment documents for the EXIF side.
    if (edit.clearLocation && edit.setLocation == null) clearXmpLocation(packet)
}

/**
 * Removes [packet]'s own GPS-bearing properties (P0-08 item 5): every `exif:GPS*` property (the
 * XMP EXIF schema's mirror of the classic EXIF GPS IFD -- [XMP_GPS_PROPERTIES]) plus the two
 * structured IPTC location properties, `Iptc4xmpExt:LocationShown` (where a photo was published as
 * depicting) and `Iptc4xmpExt:LocationCreated` (where it was actually shot) -- both bag-of-structure
 * properties this class has no typed model for, cleared with [XmpPacket.clearProperty] rather than
 * read/modified, since all "Clear location" ever needs from them is gone.
 *
 * Deliberately does NOT touch `photoshop:City`/`photoshop:State`/`photoshop:Country`: the brief
 * this app follows for this feature says to clear them "only if they came with GPS", and this app
 * has no way to tell a value a geocoder derived from GPS apart from one a photographer typed in by
 * hand describing a place they remember, not where a receiver reads coordinates from -- with no XMP
 * field recording that provenance, the fail-closed choice (see this repo's own brief, 0.5 "keep
 * data rather than delete it") is to leave all three alone unconditionally, not guess.
 */
private fun clearXmpLocation(packet: XmpPacket) {
    XMP_GPS_PROPERTIES.forEach { packet.clearProperty(XmpPacket.EXIF_NS, it) }
    packet.clearProperty(XmpPacket.IPTC4XMPEXT_NS, "LocationShown")
    packet.clearProperty(XmpPacket.IPTC4XMPEXT_NS, "LocationCreated")
}

/**
 * [clearXmpLocation] plus the place NAMES, for a file that leaves the device's own library
 * ([com.fotoxplorr.app.editor.EditedCopyWriter.saveTo]): `photoshop:City`/`State`/`Country`,
 * `Iptc4xmpCore:Location`/`CountryCode`, and every GPS or `Iptc4xmpExt` location structure wherever
 * it sits in the packet. The metadata panel's "Clear location" leaves the names alone on purpose
 * (see above: one may have been typed in by hand about a remembered place, and keeping data beats
 * guessing); an export is the opposite case, where a name that identifies where a photo was taken
 * is the leak, whoever wrote it. [XmpPacket.clearProperty] takes every copy, in every Description
 * and nested, so a second Description or a duplicated element cannot survive.
 */
internal fun clearXmpLocationForExport(packet: XmpPacket) {
    clearXmpLocation(packet)
    XMP_PLACE_PROPERTIES.forEach { (namespace, name) -> packet.clearProperty(namespace, name) }
}

/**
 * Whether [packet] still holds any location [clearXmpLocationForExport] clears: a GPS property, an
 * IPTC location structure or a place name, in any shape and under any prefix. The XMP half of the
 * proof [com.fotoxplorr.app.editor.carriesLocation] gives for a Save As.
 */
internal fun xmpCarriesLocation(packet: XmpPacket): Boolean =
    XMP_GPS_PROPERTIES.any { packet.hasProperty(XmpPacket.EXIF_NS, it) } ||
        packet.hasProperty(XmpPacket.IPTC4XMPEXT_NS, "LocationShown") ||
        packet.hasProperty(XmpPacket.IPTC4XMPEXT_NS, "LocationCreated") ||
        XMP_PLACE_PROPERTIES.any { (namespace, name) -> packet.hasProperty(namespace, name) }

/** Place-name properties an export clears (namespace to local name). */
private val XMP_PLACE_PROPERTIES = listOf(
    XmpPacket.PHOTOSHOP_NS to "City",
    XmpPacket.PHOTOSHOP_NS to "State",
    XmpPacket.PHOTOSHOP_NS to "Country",
    XmpPacket.IPTC4XMPCORE_NS to "Location",
    XmpPacket.IPTC4XMPCORE_NS to "CountryCode",
)

/** Every property in the XMP EXIF schema's GPS group (Adobe XMP Specification Part 2, the EXIF
 *  schema's GPS Info IFD section) that this app's own GPS write path ([applyMetadataToExif]'s
 *  `exif.setLatLong`) could plausibly have a counterpart for, or that a camera/GPS receiver
 *  commonly populates alongside coordinates -- not a partial guess at "the important ones". */
private val XMP_GPS_PROPERTIES = listOf(
    "GPSLatitude", "GPSLongitude", "GPSAltitude", "GPSAltitudeRef", "GPSTimeStamp",
    "GPSSatellites", "GPSStatus", "GPSMeasureMode", "GPSDOP", "GPSSpeedRef", "GPSSpeed",
    "GPSTrackRef", "GPSTrack", "GPSImgDirectionRef", "GPSImgDirection", "GPSMapDatum",
    "GPSDestLatitude", "GPSDestLongitude", "GPSDestBearingRef", "GPSDestBearing",
    "GPSDestDistanceRef", "GPSDestDistance", "GPSProcessingMethod", "GPSAreaInformation",
    "GPSDifferential", "GPSHPositioningError", "GPSVersionID",
)
