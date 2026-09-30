@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class, kotlin.experimental.ExperimentalNativeApi::class)

package com.fotoxplorr.ut.bridge

import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.CPointer
import kotlinx.serialization.encodeToString
import kotlin.native.CName

/**
 * ADR-013 point 3 says "the shared `Capability` model reports the same way as on Android" -- but
 * as of this WP, **no such shared model exists anywhere in this codebase**. It was named in
 * ADR-010's original `:core:model` row, then deferred through WP1.2 and WP1.3, then deferred
 * again by WP1.7a ("a `Capability` row claiming real... support would be premature") -- confirmed
 * by grepping the whole repo for it before writing this file. There is nothing to reuse.
 *
 * So [CapabilitiesDto] is a bridge-local stand-in: a plain, honest report of which of THIS
 * module's own `@CName` entry points are real versus stubbed today, and why -- not a port of a
 * type that doesn't exist. Once WP1.7 (or whichever WP finally builds the real `Capability`
 * matrix) lands, this function should be rewired to report through it instead, matching ADR-013
 * point 3 as originally intended. Documented again in MASTER-PROGRESS.md's WP8.1 row/Decisions.
 *
 * Takes no handle: every field here describes this native BUILD's own feature set, not anything
 * about a particular opened database.
 *
 * @return a heap-allocated JSON string (see `BridgeJson.kt`'s convention) -- never `null`, and
 * never a hand-rolled `error` field: this call cannot fail short of an allocation failure, which
 * [runCatchingJson] still converts into one rather than crashing.
 */
@CName("fotoz_capabilities")
fun fotoz_capabilities(): CPointer<ByteVar> = runCatchingJson {
    val dto = CapabilitiesDto(
        schemaVersion = 1,
        database = DatabaseCapabilityDto(
            engine = "room-kmp-bundled-sqlite",
            schema = "fotoz.db v1 (ADR-011 section 2)",
        ),
        queryPage = QueryPageCapabilityDto(
            available = true,
            albums = SmartAlbum.entries.map { it.wireName },
            freeTextSearch = "partial: :core:search's real parseSearchQuery() feeds only bare, " +
                "non-negated Word terms into a display_name match; folder/tag/OCR/label matching " +
                "(GalleryProjectionDao's own documented gap) is not wired",
        ),
        assetDetail = FeatureCapabilityDto(available = true),
        setMetadata = SetMetadataCapabilityDto(
            sidecarName = true,
            strip = true,
            xmp = false,
            reason = "XmpPacket/XmpSidecarStore are jvmMain-only per ADR-010 -- not achievable on " +
                "Kotlin/Native yet. sidecarName (SidecarNaming) and strip (MetadataStripper) are " +
                "both real, native-ready :core:metadata pieces.",
        ),
        sync = FeatureCapabilityDto(
            available = false,
            reason = "Phase 1's real sync engine wiring (AndroidMediaStoreSource/AndroidMediaSync, " +
                "WP1.4b) is Android-only; :core:index's Source/SyncEngine are pure Kotlin but have " +
                "no native-ready Source implementation (no Linux directory source exists yet).",
        ),
        ops = FeatureCapabilityDto(
            available = false,
            reason = "Fotoz has no Fylz-style operation engine (journal/staging/recycle) at all -- " +
                ":core:organize only has planning-stage pure functions (BulkRenamePlanner/" +
                "SweepPolicy/ScanPlan); their execution lives in :app (Android-only) and was never " +
                "ported. Not a gap in this WP -- there is nothing to wire.",
        ),
    )
    BridgeJson.json.encodeToString(dto)
}
