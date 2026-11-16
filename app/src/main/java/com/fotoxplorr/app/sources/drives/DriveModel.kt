package com.fotoxplorr.app.sources.drives

import java.util.Locale

/**
 * A removable volume that is mounted right now, identified by its FILESYSTEM UUID (what
 * `StorageVolume.getUuid` returns and what the platform's document ids carry as their volume part:
 * `1234-ABCD:DCIM`). The label is only for words; two drives with the same label are two drives.
 */
data class DriveVolume(val uuid: String, val label: String)

object DriveIds {
    /** The identity every comparison uses: trimmed, upper case. Null for no id, a blank id and the internal volume. */
    fun normalise(raw: String?): String? {
        val id = raw?.trim()?.uppercase(Locale.ROOT)?.takeIf { it.isNotEmpty() } ?: return null
        return if (id == "PRIMARY") null else id
    }
}

/** What the platform says about one storage volume, before the app decides it is a drive worth offering. */
data class RawVolume(
    val uuid: String?,
    val description: String,
    val removable: Boolean,
    val primary: Boolean,
    val mounted: Boolean,
)

object DriveVolumes {
    /** Mounted, removable, not the internal volume, with a filesystem uuid; one entry per uuid. */
    fun fromRaw(raw: List<RawVolume>): List<DriveVolume> = raw
        .filter { it.removable && !it.primary && it.mounted }
        .mapNotNull { r -> DriveIds.normalise(r.uuid)?.let { DriveVolume(it, r.description.ifBlank { it }) } }
        .distinctBy { it.uuid }
}

/** How a drive's offline previews are kept (U5 reads it; this unit only remembers the choice). */
enum class PreviewPolicy(val wire: Int) {
    /** No offline previews for this drive. */
    NONE(0),

    /** Keep a preview of photos the person looked at. */
    AS_VIEWED(1),

    /** Make a preview of every photo while the drive is there. */
    ALL(2),
    ;

    companion object {
        fun fromWire(wire: Int): PreviewPolicy = entries.firstOrNull { it.wire == wire } ?: AS_VIEWED
    }
}

/**
 * What the person decided about one drive, remembered by its uuid.
 *
 * @param never "Never for this drive": no offer when it is connected (a source already added from it is unaffected).
 * @param asked the person has seen the offer for this drive at least once (ADR-020 owner Q12: ask on first connect).
 * @param included the drive's photos are shown in the gallery (mirrors the per-source switch for a new source).
 * @param indexOnConnect read the drive again each time it comes back; default OFF (owner Q12).
 */
data class DriveSettings(
    val label: String = "",
    val never: Boolean = false,
    val asked: Boolean = false,
    val included: Boolean = true,
    val indexOnConnect: Boolean = false,
    val previews: PreviewPolicy = PreviewPolicy.AS_VIEWED,
)

enum class DriveChoice { ADD, NOT_NOW, NEVER }

/** A drive the person asked never to be offered, as the storage panel lists it. */
data class IgnoredDrive(val uuid: String, val label: String)
