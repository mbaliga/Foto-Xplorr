package com.fotoxplorr.core.db.migration

/**
 * ADR-011 §5 step 8's hardest check: "every destination and smart album computed from the old
 * in-memory data vs from `fotoz.db` for the live library, with count and order fingerprint" --
 * `CatalogueCharacterisationTest.describe()` reused on real data. That function lives in
 * `app/src/test` today (survey finding) and depends on `GalleryProjection`/
 * `DestinationBrowserScreen` (Android/Compose-reachable code `:core:db` cannot see), so this
 * comparison can only run where those live -- `:app` implements it for real; a JVM test fake
 * implements it against the same old/new fixture data the rest of [MigrationToV2] is tested
 * with.
 */
interface ProjectionParityCheck {
    /** One `describe()`-shaped fingerprint string per destination/smart-album/sort projection.
     *  A mismatched key between [oldFingerprints] and [newFingerprints] (or a mismatched value
     *  for the same key) fails VERIFY. */
    suspend fun oldFingerprints(): Map<String, String>
    suspend fun newFingerprints(): Map<String, String>
}
