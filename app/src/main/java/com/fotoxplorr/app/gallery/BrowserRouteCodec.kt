package com.fotoxplorr.app.gallery

import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.listSaver
import com.fotoxplorr.app.media.MediaId

/**
 * [BrowserRoute] as a flat list of strings, and back -- what actually gets asked to survive
 * process death, since `rememberSaveable`'s default mechanism only trusts `Parcelable`,
 * `Serializable` and a short list of built-in types, and a sealed interface of data classes is
 * none of those.
 *
 * Pure so the round trip is pinnable with plain JUnit, independent of Compose's own saved-state
 * machinery (which needs a real `Bundle`/Robolectric to exercise end to end).
 */
internal fun encodeBrowserRoute(route: BrowserRoute): List<String> = when (route) {
    BrowserRoute.Root -> listOf("root")
    is BrowserRoute.DeviceAlbum -> listOf("album", route.key, route.name)
    is BrowserRoute.Collection -> listOf("collection", route.id, route.name)
    is BrowserRoute.Smart -> listOf("smart", route.album.name)
    is BrowserRoute.Tag -> listOf("tag", route.tag)
}

/**
 * The inverse of [encodeBrowserRoute]. Falls back to [BrowserRoute.Root] for anything it does not
 * recognise -- a future app version reading state saved by an older one (or vice versa) should
 * land somewhere safe, never crash reading its own saved instance state back.
 */
internal fun decodeBrowserRoute(encoded: List<String>): BrowserRoute {
    val kind = encoded.getOrNull(0) ?: return BrowserRoute.Root
    return when (kind) {
        "root" -> BrowserRoute.Root
        "album" -> {
            val key = encoded.getOrNull(1) ?: return BrowserRoute.Root
            val name = encoded.getOrNull(2) ?: key
            BrowserRoute.DeviceAlbum(key, name)
        }
        "collection" -> {
            val id = encoded.getOrNull(1) ?: return BrowserRoute.Root
            val name = encoded.getOrNull(2) ?: id
            BrowserRoute.Collection(id, name)
        }
        "smart" -> {
            val albumName = encoded.getOrNull(1) ?: return BrowserRoute.Root
            val album = SmartAlbum.entries.firstOrNull { it.name == albumName } ?: return BrowserRoute.Root
            BrowserRoute.Smart(album)
        }
        "tag" -> {
            val tag = encoded.getOrNull(1) ?: return BrowserRoute.Root
            BrowserRoute.Tag(tag)
        }
        else -> BrowserRoute.Root
    }
}

/** The [Saver] `rememberSaveable` needs to carry [BrowserRoute] through process death. */
internal val BrowserRouteSaver: Saver<BrowserRoute, *> = listSaver(
    save = { encodeBrowserRoute(it) },
    restore = { decodeBrowserRoute(it) },
)

/**
 * [GallerySelection] as (selecting, the selected ids as raw longs) -- a `Set<MediaId>` is not
 * itself one of `rememberSaveable`'s built-in types (a `value class` is erased to its underlying
 * `Long` at the JVM level, but the SET wrapping them is not on the trusted-types list), so this
 * unwraps to a plain `List<Long>` for the save side and rebuilds the value class on restore.
 */
internal val GallerySelectionSaver: Saver<GallerySelection, *> = listSaver<GallerySelection, Any>(
    save = { listOf(it.selecting, it.selectedIds.map(MediaId::value)) },
    restore = { saved ->
        val selecting = saved[0] as Boolean
        @Suppress("UNCHECKED_CAST")
        val ids = (saved[1] as List<Long>).mapTo(linkedSetOf(), ::MediaId)
        GallerySelection(selectedIds = ids, selecting = selecting)
    },
)
