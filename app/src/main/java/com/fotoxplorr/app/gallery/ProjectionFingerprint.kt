package com.fotoxplorr.app.gallery

import com.fotoxplorr.app.media.MediaAsset
import java.time.ZoneId
import java.util.Locale

// The FX-003 characterisation primitive, in production code so more than the test suite can use
// it.
//
// It used to live only in `app/src/test` (`SyntheticCatalogue.fingerprint` and
// `CatalogueCharacterisationTest.describe`). ADR-011 §5 step 8 needs the same check at runtime:
// `VERIFY` compares every destination and smart album computed from the old in-memory data with
// the same projections computed from `fotoz.db`, by count and order fingerprint. Production code
// can't call test sources, so the primitive lives here now and the characterisation test calls it
// from here. That keeps one definition, so the goldens and the migration gate can't drift apart.
// The migration's `AndroidProjectionParityCheck` and the Phase 1 gate report both use it.

/**
 * Order-sensitive fingerprint of a projection: FNV-1a 64 over the id sequence, 8 little-endian
 * bytes per id. The same members in a different order give a different fingerprint on purpose,
 * because FX-003 pins stable ordering as well as membership.
 */
fun projectionFingerprint(assets: List<MediaAsset>): String {
    var hash = -0x340d631b7bdddcdbL // FNV-1a 64 offset basis
    assets.forEach { asset ->
        var v = asset.id.value
        repeat(8) {
            hash = (hash xor (v and 0xFF)) * 0x100000001b3L
            v = v ushr 8
        }
    }
    return "%016x".format(hash)
}

/** `count:fingerprint:firstId:lastId`, the value format the FX-003 goldens are recorded in. */
fun describeProjection(projected: List<MediaAsset>): String {
    val first = projected.firstOrNull()?.id?.value ?: -1L
    val last = projected.lastOrNull()?.id?.value ?: -1L
    return "${projected.size}:${projectionFingerprint(projected)}:$first:$last"
}

/**
 * Every projection `CatalogueCharacterisationTest` pins, keyed exactly as its goldens are:
 * `destination:<HyleDestination>` (all 10, AUDIO included), `smart:<SmartAlbum>` (all 11),
 * `sort:<GallerySort>` (the everyday projection under each of the 4 sort modes) and
 * `timeline:stops`.
 *
 * [nowMillis] feeds the time-windowed smart album (RECENT). A caller comparing two states must
 * pass the SAME instant to both calls, or an asset sitting on the 30-day boundary can flip
 * between them. [zoneId] and [locale] only shape the timeline stop labels. Pass fixed values,
 * not the device defaults, when the result is compared or stored.
 */
fun projectionFingerprints(
    state: GalleryUiState,
    nowMillis: Long,
    zoneId: ZoneId,
    locale: Locale,
): Map<String, String> = buildMap {
    HyleDestination.entries.forEach { destination ->
        put("destination:${destination.name}", describeProjection(destinationAssets(destination, state)))
    }
    SmartAlbum.entries.forEach { album ->
        val projected = smartAlbumAssets(
            smartAlbum = album,
            assets = state.assets,
            favoriteIds = state.favoriteIds,
            sensitiveIds = state.sensitiveIds,
            archivedIds = state.library.archivedIds,
            tagsByMediaId = state.library.tagsByMediaId,
            lockedFolders = state.lockedFolders,
            unlockedFolders = state.unlockedFolders,
            preferences = state.preferences,
            animatedIds = state.animatedIds,
            nowMillis = nowMillis,
        )
        put("smart:${album.name}", describeProjection(projected))
    }
    GallerySort.entries.forEach { sort ->
        put("sort:${sort.name}", describeProjection(everydayProjection(state, state.preferences.copy(sort = sort))))
    }
    // Stops are pinned as label@index pairs, hashed. The scrubber's contract is that these
    // indices keep pointing at the same grid rows. Unlike the id fingerprints above, this also
    // catches a changed capture date that happens not to change the order.
    val stops = timelineStops(everydayProjection(state, state.preferences), zoneId = zoneId, locale = locale)
    val joined = stops.joinToString("|") { "${it.label}@${it.itemIndex}" }
    put("timeline:stops", "${stops.size}:${joined.hashCode()}")
}

private fun everydayProjection(state: GalleryUiState, preferences: GalleryPreferencesState): List<MediaAsset> =
    everydayAssets(
        assets = state.assets,
        archivedIds = state.library.archivedIds,
        sensitiveIds = state.sensitiveIds,
        lockedFolders = state.lockedFolders,
        unlockedFolders = state.unlockedFolders,
        preferences = preferences,
        query = "",
        tagsByMediaId = state.library.tagsByMediaId,
    )
