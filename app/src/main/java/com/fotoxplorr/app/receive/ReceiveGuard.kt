package com.fotoxplorr.app.receive

import android.content.Context
import com.fotoxplorr.app.LibraryRuntime
import com.fotoxplorr.app.gallery.GalleryPreferences
import com.fotoxplorr.app.gallery.browsableAssets
import com.fotoxplorr.app.media.MediaAsset
import com.fotoxplorr.app.openwith.ExternalAssetLoader
import com.fotoxplorr.app.openwith.MediaStoreUri
import com.fotoxplorr.app.openwith.OpenRequestLookup
import com.fotoxplorr.app.openwith.classifyMediaStoreUri
import com.fotoxplorr.app.openwith.lookupUncataloguedRow
import com.fotoxplorr.app.organize.LibraryStore
import com.fotoxplorr.app.privacy.PrivateFolderStore
import com.fotoxplorr.app.privacy.SensitiveStore
import com.fotoxplorr.core.model.MediaId

/**
 * Whether a shared uri may be shown or copied, for the one case where the answer is not "yes, the
 * sender gave it to us": a `content://media` uri names a row of THIS device's library, which the
 * sender's grant says nothing about. Reading it with this app's own media access would skip the
 * lock, so such a uri passes only when the library's own browsable list (TRAPS #33: not trashed,
 * archived, locked or sensitive-hidden) would show that row, or when the row is too new to be
 * catalogued yet and the same rules say nothing hides it. Any other provider's uri is the
 * sender's own file and is allowed. Same rule as `ExternalViewerActivity`'s open-with check.
 */
class ReceiveGuard(private val context: Context) {
    suspend fun mayUse(uri: String): Boolean = when (val ref = classifyMediaStoreUri(uri)) {
        MediaStoreUri.NotMediaStore -> true
        MediaStoreUri.Unreadable -> false
        is MediaStoreUri.Row -> {
            val catalogued = LibraryRuntime.get(context).repository.awaitLoaded().firstOrNull { it.id == MediaId(ref.id) }
            if (catalogued != null) {
                browsable(listOf(catalogued)).isNotEmpty()
            } else {
                lookupUncataloguedRow(ExternalAssetLoader(context).readMediaStoreRow(ref.id), ::browsable) ==
                    OpenRequestLookup.Unknown
            }
        }
    }

    private fun browsable(list: List<MediaAsset>): List<MediaAsset> = browsableAssets(
        list,
        LibraryStore.get(context).observe().value.archivedIds,
        SensitiveStore(context).observe().value,
        PrivateFolderStore(context).observeLockedFolders().value,
        PrivateFolderStore(context).observeUnlockedFolders().value,
        GalleryPreferences(context).observe().value.hideSensitive,
    )
}
