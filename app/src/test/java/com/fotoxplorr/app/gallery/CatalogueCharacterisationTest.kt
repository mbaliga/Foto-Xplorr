package com.fotoxplorr.app.gallery

import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test

/**
 * FX-003: the characterisation oracle for WP2.
 *
 * Every projection the app can show — all nine destinations and all eleven smart albums —
 * run over the fixed 10k [SyntheticCatalogue], pinned by **row count and an
 * order-sensitive fingerprint**. These goldens freeze today's behaviour so the federated
 * catalogue (WP2) can land underneath the UI and *prove* it changed nothing: the shadow
 * tests compare the new projection against this same oracle.
 *
 * **Do not update a golden to make a refactor pass.** A changed golden is a behaviour
 * change and needs saying out loud — that is this suite's entire job. (Regenerating after
 * a *deliberate* product change: run with `FX_GOLDENS_PRINT=true`, which prints the
 * table and fails, then paste and say in the commit message what changed and why.)
 */
class CatalogueCharacterisationTest {

    @Test
    fun `nine destinations match their goldens, order included`() {
        val actual = HyleDestination.entries.associate { destination ->
            val projected = destinationAssets(destination, state)
            "destination:${destination.name}" to describe(projected)
        }
        compare(actual)
    }

    @Test
    fun `eleven smart albums match their goldens, order included`() {
        val actual = SmartAlbum.entries.associate { album ->
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
                nowMillis = SyntheticCatalogue.NOW_MILLIS,
            )
            "smart:${album.name}" to describe(projected)
        }
        compare(actual)
    }

    @Test
    fun `every sort mode orders the everyday projection stably`() {
        val actual = GallerySort.entries.associate { sort ->
            val projected = everydayAssets(
                assets = state.assets,
                archivedIds = state.library.archivedIds,
                sensitiveIds = state.sensitiveIds,
                lockedFolders = state.lockedFolders,
                unlockedFolders = state.unlockedFolders,
                preferences = state.preferences.copy(sort = sort),
                query = "",
                tagsByMediaId = state.library.tagsByMediaId,
            )
            "sort:${sort.name}" to describe(projected)
        }
        compare(actual)
    }

    @Test
    fun `timeline stops over the everyday projection match their golden`() {
        val projected = everydayAssets(
            assets = state.assets,
            archivedIds = state.library.archivedIds,
            sensitiveIds = state.sensitiveIds,
            lockedFolders = state.lockedFolders,
            unlockedFolders = state.unlockedFolders,
            preferences = state.preferences,
            query = "",
            tagsByMediaId = state.library.tagsByMediaId,
        )
        val stops = timelineStops(projected, zoneId = java.time.ZoneOffset.UTC, locale = java.util.Locale.UK)
        // Stops are pinned as label@index pairs hashed the same way — the scrubber's whole
        // contract is that these indices keep pointing at the same grid rows.
        val joined = stops.joinToString("|") { "${it.label}@${it.itemIndex}" }
        compare(mapOf("timeline:stops" to "${stops.size}:${joined.hashCode()}"))
    }

    private fun describe(projected: List<com.fotoxplorr.app.media.MediaAsset>): String {
        val first = projected.firstOrNull()?.id?.value ?: -1L
        val last = projected.lastOrNull()?.id?.value ?: -1L
        return "${projected.size}:${SyntheticCatalogue.fingerprint(projected)}:$first:$last"
    }

    private fun compare(actual: Map<String, String>) {
        // An env var, not a -D property: Gradle forks the test JVM, and a -D on the CLI
        // stays in the daemon. The environment is inherited by the fork.
        if (System.getenv("FX_GOLDENS_PRINT") == "true") {
            val block = actual.entries.joinToString("\n") { (k, v) -> "        \"$k\" to \"$v\"," }
            fail("Golden regeneration mode — paste into GOLDENS:\n$block")
        }
        actual.forEach { (key, value) ->
            val expected = GOLDENS[key]
                ?: fail_("No golden recorded for '$key' — regenerate with FX_GOLDENS_PRINT=true and commit the table.")
            assertEquals(
                "Projection '$key' changed (count:fingerprint:firstId:lastId). If deliberate, regenerate and explain.",
                expected,
                value,
            )
        }
    }

    private fun fail_(message: String): Nothing {
        fail(message)
        error("unreachable")
    }

    private companion object {
        val state = SyntheticCatalogue.state(SyntheticCatalogue.assets(10_000))

        /**
         * `count:fnv1a64(id sequence):firstId:lastId` per projection, captured from the
         * implementation on `claude/fotoz-ui-interactions-bxvgbw` — see file header for
         * the regeneration rule.
         */
        val GOLDENS: Map<String, String> = mapOf(
            "destination:FAVOURITES" to "1178:d2ef98cb77ff9028:7:9996",
            "destination:IDENTITY" to "258:989689564c45ee63:58:9860",
            "destination:PEOPLE" to "2999:395a1ed18e4a86c2:3:9996",
            "destination:PETS" to "396:d6c47c08c4cccdb6:19:9690",
            "destination:PHOTOS" to "7497:6cab169eefc7cd8a:1:9996",
            "destination:PLACES" to "0:cbf29ce484222325:-1:-1",
            "destination:PROTECTED" to "0:cbf29ce484222325:-1:-1",
            "destination:SCREENSHOTS" to "2171:0a4bc12b792f5b46:1:9979",
            "destination:VIDEOS" to "1650:ecf841907be24c96:3:9945",
            "smart:ANIMATED" to "286:98c7726b2a5558fd:46:9775",
            "smart:ARCHIVED" to "750:9d837fdd8653a6e6:22:9724",
            "smart:DUPLICATES" to "39:30036af67ca13eb4:250:8500",
            "smart:FAVORITES" to "1178:d2ef98cb77ff9028:7:9996",
            "smart:LARGE_FILES" to "1478:2e612f216c4d1bce:328:9996",
            "smart:RECENT" to "271:abe7cd03844df0bb:1:348",
            "smart:SCREENSHOTS" to "2171:0a4bc12b792f5b46:1:9979",
            "smart:SENSITIVE" to "635:91e0ecf724d0c3e3:13:9945",
            "smart:TRASH" to "87:9f8fe4534c11968e:97:9894",
            "smart:UNTAGGED" to "6598:13f49f741e7c97bc:1:9996",
            "smart:VIDEOS" to "1650:ecf841907be24c96:3:9945",
            "sort:NAME" to "7497:e3f39c1f6cde88ba:46:9993",
            "sort:NEWEST" to "7497:6cab169eefc7cd8a:1:9996",
            "sort:OLDEST" to "7497:436b29720315658e:9996:1",
            "sort:SIZE" to "7497:3e9bbb1d578b3636:9199:400",
            "timeline:stops" to "42:335992031",
        )
    }
}
