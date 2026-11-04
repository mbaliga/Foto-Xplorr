package com.fotoxplorr.app.widget

import com.fotoxplorr.app.gallery.browsableAssets
import com.fotoxplorr.app.gallery.isScreenshot
import com.fotoxplorr.app.media.MediaAsset
import com.fotoxplorr.core.model.MediaId

/**
 * The visibility rule for everything a home-screen widget draws, and for every photo a widget tap
 * may open.
 *
 * A home screen is a public surface: anyone holding the phone sees it, and it is drawn whether or
 * not this app is running. So the widget path never asks the app's session what is unlocked, and
 * never reads the in-app "hide sensitive" preference (which defaults to off). It always calls the
 * one visibility filter every screen uses, [browsableAssets] (TRAPS #33), with no protected folder
 * unlocked and sensitive items hidden. Called that way, trashed, archived, sensitive-flagged and
 * protected-folder items never reach a widget, whatever the in-app session or settings say.
 *
 * The filter is a parameter only so `WidgetPrivacyTest` can record the two arguments this object
 * exists to force; production code always uses the default.
 *
 * Pictures go one step further ([picturedAssets]): an item that passes that filter is still never
 * drawn, nor opened from a tap, when it is a screenshot (chats, bank screens, one-time codes) or a
 * photo the on-device text check has read as an identity document (passport, ID card). Both stay
 * visible in the app; they are only kept off the public home screen. Counts may include them,
 * since a number names nothing.
 */
internal object WidgetPrivacy {
    /** No protected folder is treated as unlocked on the home screen, ever. */
    val UNLOCKED_FOLDERS: Set<String> = emptySet()

    /** Sensitive-flagged items never reach the home screen, whatever the in-app preference. */
    const val HIDE_SENSITIVE: Boolean = true

    fun visibleAssets(
        assets: List<MediaAsset>,
        archivedIds: Set<MediaId>,
        sensitiveIds: Set<MediaId>,
        lockedFolders: Set<String>,
        filter: (
            assets: List<MediaAsset>,
            archivedIds: Set<MediaId>,
            sensitiveIds: Set<MediaId>,
            lockedFolders: Set<String>,
            unlockedFolders: Set<String>,
            hideSensitive: Boolean,
        ) -> List<MediaAsset> = ::browsableAssets,
    ): List<MediaAsset> = filter(
        assets,
        archivedIds,
        sensitiveIds,
        lockedFolders,
        UNLOCKED_FOLDERS,
        HIDE_SENSITIVE,
    )

    /**
     * What a widget may draw as a picture, or open from a tap: [visibleAssets], minus screenshots
     * and identity documents. Recognition runs after a photo arrives, so a document photographed
     * moments ago can show until its text has been read; the widgets redraw when that pass ends.
     */
    fun picturedAssets(
        assets: List<MediaAsset>,
        archivedIds: Set<MediaId>,
        sensitiveIds: Set<MediaId>,
        lockedFolders: Set<String>,
        identityIds: Set<MediaId>,
    ): List<MediaAsset> = notPictured(visibleAssets(assets, archivedIds, sensitiveIds, lockedFolders), identityIds)

    /** The picture rule alone, for a list that has already passed [visibleAssets]. */
    fun notPictured(visible: List<MediaAsset>, identityIds: Set<MediaId>): List<MediaAsset> =
        visible.filterNot { it.id in identityIds || it.isScreenshot() }
}
