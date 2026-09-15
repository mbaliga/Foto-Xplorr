package com.fotoxplorr.app.gallery

import com.fotoxplorr.app.ScanState
import com.fotoxplorr.app.media.MediaAsset
import com.fotoxplorr.app.media.MediaId
import com.fotoxplorr.app.organize.LibraryState
import com.fotoxplorr.app.recognition.RecognitionIndex

/**
 * The fixed synthetic catalogue behind the FX-003 characterisation goldens.
 *
 * Every field is derived *arithmetically* from the asset's index — deliberately no
 * `kotlin.random.Random`, whose seeded sequence is not guaranteed stable across Kotlin
 * versions. A golden that churns on a toolchain bump stops being a golden; this generator
 * produces byte-identical catalogues forever.
 *
 * The modulus choices are not arbitrary: they are picked so every projection branch has
 * real work to do — trashed items, archived items, sensitive items, favourites, tags,
 * videos, GIFs, screenshots (by name AND by folder), files over the 20 MB large-file
 * threshold, exact-duplicate groups, zero capture dates (the modified-time fallback), a
 * locked folder, and recognition hits for all three recognition destinations. The moduli
 * are mostly coprime so the subsets overlap in irregular ways, which is what makes the
 * goldens sensitive to subtle filter-order changes.
 */
object SyntheticCatalogue {

    /** 1 Aug 2026 00:00:00 UTC — fixed so goldens never depend on the clock. */
    const val BASE_MILLIS = 1_785_542_400_000L

    /**
     * `nowMillis` handed to time-windowed projections (RECENT). One day past [BASE_MILLIS]
     * so the newest ~360 assets fall inside the 30-day recent window.
     */
    const val NOW_MILLIS = BASE_MILLIS + 24L * 60L * 60L * 1_000L

    fun assets(count: Int): List<MediaAsset> = List(count) { i -> asset(i) }

    private fun asset(i: Int): MediaAsset {
        val isVideo = i % 6 == 3
        val isGif = !isVideo && i % 23 == 0
        val isScreenshotName = !isVideo && !isGif && i % 9 == 0
        val displayName = when {
            isVideo -> "VID_%05d.mp4".format(i)
            isGif -> "FUNNY_%05d.gif".format(i)
            isScreenshotName -> "Screenshot_%05d.png".format(i)
            else -> "IMG_%05d.jpg".format(i)
        }
        val mimeType = when {
            isVideo -> "video/mp4"
            isGif -> "image/gif"
            isScreenshotName -> "image/png"
            else -> "image/jpeg"
        }
        // Six folders, one of which ("Vault") is the locked folder in [state]. The
        // Screenshots *folder* is distinct from screenshot-named files, so the smart album's
        // name-or-folder rule has both routes exercised.
        val bucketIndex = i % 6
        val bucketName = when (bucketIndex) {
            0 -> "Camera"
            1 -> "Screenshots"
            2 -> "WhatsApp Images"
            3 -> "Download"
            4 -> null
            else -> "Vault"
        }
        val relativePath = when (bucketIndex) {
            0 -> "DCIM/Camera/"
            1 -> "Pictures/Screenshots/"
            2 -> "Pictures/WhatsApp Images/"
            3 -> "Download/"
            4 -> null
            else -> "Pictures/Vault/"
        }
        // Every 250th asset joins one exact-duplicate group (same size+dimensions+mime after
        // the branches above are forced off for them); every 17th has no capture date, so it
        // depends on the modified-time fallback everywhere timelines are involved.
        val inDuplicateGroup = i % 250 == 0
        val dateTaken = if (i % 17 == 0) 0L else BASE_MILLIS - i.toLong() * 7_200_000L
        val dateModifiedSeconds = BASE_MILLIS / 1_000L - i.toLong() * 3_600L
        return MediaAsset(
            id = MediaId(i.toLong()),
            contentUriString = "content://media/external/file/$i",
            displayName = if (inDuplicateGroup) "IMG_DUP_%05d.jpg".format(i) else displayName,
            mimeType = if (inDuplicateGroup) "image/jpeg" else mimeType,
            bucketName = bucketName,
            bucketId = bucketIndex.toLong() + 100L,
            dateTakenMillis = dateTaken,
            dateModifiedSeconds = dateModifiedSeconds,
            width = if (inDuplicateGroup) 3000 else 1080 + (i % 5) * 484,
            height = if (inDuplicateGroup) 2000 else 1920 + (i % 3) * 704,
            // The `+ i` term makes every non-group size unique (two indices 400 apart share
            // the modulus term but differ in the addend), so DUPLICATES contains exactly the
            // forced group rather than a sea of accidental collisions — a sharp golden, not a
            // blurry one. The 5 MB / 3000×2000 tuple is unreachable by this formula.
            sizeBytes = if (inDuplicateGroup) 5_000_000L else (i % 400).toLong() * 64_000L + i.toLong(),
            durationMillis = if (isVideo) 30_000L + (i % 90) * 1_000L else 0L,
            relativePath = relativePath,
            isFavorite = false, // favourites travel via favoriteIds, as in the app
            isTrashed = i % 97 == 0,
        )
    }

    /**
     * The full [GalleryUiState] the destination projections take. All membership sets are
     * index-derived with moduli coprime to the generator's own, so the overlaps are
     * irregular but fixed.
     */
    fun state(assets: List<MediaAsset>): GalleryUiState {
        val ids = assets.map { it.id }
        val favoriteIds = ids.filterTo(linkedSetOf()) { it.value % 7L == 0L }
        val sensitiveIds = ids.filterTo(linkedSetOf()) { it.value % 13L == 0L }
        val archivedIds = ids.filterTo(linkedSetOf()) { it.value % 11L == 0L }
        val tags = buildMap<MediaId, Set<String>> {
            ids.forEach { id ->
                val t = buildSet {
                    if (id.value % 5L == 0L) add("trip")
                    if (id.value % 35L == 0L) add("family")
                }
                if (t.isNotEmpty()) put(id, t)
            }
        }
        // Lock the Vault folder by its real projection key, not a guessed string — the
        // characterisation must go through the same folderIdentity the app uses.
        val vault = assets.first { it.bucketName == "Vault" }
        val lockedFolders = setOf(folderIdentity(vault).key.value)
        return GalleryUiState(
            assets = assets,
            favoriteIds = favoriteIds,
            sensitiveIds = sensitiveIds,
            lockedFolders = lockedFolders,
            unlockedFolders = emptySet(),
            library = LibraryState(
                collections = emptyList(),
                tagsByMediaId = tags,
                archivedIds = archivedIds,
            ),
            permissionGranted = true,
            scanState = ScanState.Idle,
            preferences = GalleryPreferencesState(),
            recognition = RecognitionIndex(
                people = emptyList(),
                peopleMediaIds = ids.filterTo(linkedSetOf()) { it.value % 3L == 0L },
                petMediaIds = ids.filterTo(linkedSetOf()) { it.value % 19L == 0L },
                identityMediaIds = ids.filterTo(linkedSetOf()) { it.value % 29L == 0L },
            ),
        )
    }

    /**
     * Order-sensitive fingerprint of a projection result: FNV-1a 64 over the id sequence.
     * Two results with the same members in a different order get different fingerprints,
     * which is the point — FX-003 pins *stable ordering*, not just membership.
     */
    fun fingerprint(assets: List<MediaAsset>): String {
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
}
