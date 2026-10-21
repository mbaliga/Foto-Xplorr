package com.fotoxplorr.app.openwith

import com.fotoxplorr.app.media.MediaAsset
import com.fotoxplorr.core.model.MediaId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The hidden-photo guard keys off [classifyMediaStoreUri]. Android percent-decodes a Uri's
 * authority and path segments before the media provider matches them, so a guard that reads the
 * raw string can be walked around by spelling the same Uri differently. Every case below is a
 * spelling the provider would resolve to a real row (or that it would not, and must be refused).
 */
class MediaStoreUriClassificationTest {

    private fun row(id: Long) = MediaStoreUri.Row(id)

    @Test
    fun `a percent-encoded id still names its row`() {
        assertEquals(row(4711), classifyMediaStoreUri("content://media/external/images/media/%34%37%31%31"))
        assertEquals(row(42), classifyMediaStoreUri("content://media/external/images/media/%34%32"))
    }

    @Test
    fun `a percent-encoded authority is still the media provider`() {
        assertEquals(row(42), classifyMediaStoreUri("content://%6Dedia/external/images/media/42"))
        assertEquals(row(42), classifyMediaStoreUri("content://%30@%6dedia/external/images/media/42"))
    }

    @Test
    fun `a trailing non-numeric segment does not hide the id`() {
        assertEquals(row(42), classifyMediaStoreUri("content://media/external/images/media/42/thumbnail"))
        assertEquals(row(4711), classifyMediaStoreUri("content://media/external/images/media/4711/thumbnail"))
        assertEquals(row(42), classifyMediaStoreUri("content://media/external/images/media/42/%74humbnail"))
    }

    @Test
    fun `the last numeric segment is the row id`() {
        assertEquals(row(7), classifyMediaStoreUri("content://media/external/video/media/7?foo=1"))
        assertEquals(row(9), classifyMediaStoreUri("content://media/external/images/media/42/9"))
    }

    @Test
    fun `a user id prefix on the authority is still the media provider`() {
        assertEquals(row(42), classifyMediaStoreUri("content://0@media/external/images/media/42"))
        // Android takes the part after the LAST '@' as the authority.
        assertEquals(row(42), classifyMediaStoreUri("content://a@0@media/external/images/media/42"))
    }

    @Test
    fun `a media provider uri with no readable row id is unreadable, not external`() {
        assertEquals(MediaStoreUri.Unreadable, classifyMediaStoreUri("content://media"))
        assertEquals(MediaStoreUri.Unreadable, classifyMediaStoreUri("content://media/"))
        assertEquals(MediaStoreUri.Unreadable, classifyMediaStoreUri("content://media/external/images/media"))
        assertEquals(MediaStoreUri.Unreadable, classifyMediaStoreUri("content://media/external/images/media/notanumber"))
        assertEquals(MediaStoreUri.Unreadable, classifyMediaStoreUri("content://%6dedia/external/images/media/%ZZ"))
        assertEquals(MediaStoreUri.Unreadable, classifyMediaStoreUri("content://media/external/images/media/-5"))
        // Decoded once only, as Android does: %2534 is the text "%34", not a number.
        assertEquals(MediaStoreUri.Unreadable, classifyMediaStoreUri("content://media/external/images/media/%2534"))
    }

    @Test
    fun `other providers and non content uris are not media store`() {
        assertEquals(MediaStoreUri.NotMediaStore, classifyMediaStoreUri("content://com.example.files/media/42"))
        assertEquals(MediaStoreUri.NotMediaStore, classifyMediaStoreUri("content://media.example/external/images/media/42"))
        assertEquals(MediaStoreUri.NotMediaStore, classifyMediaStoreUri("content://media:80/external/images/media/42"))
        assertEquals(MediaStoreUri.NotMediaStore, classifyMediaStoreUri("file:///sdcard/DCIM/42"))
    }

    @Test
    fun `photo picker uris are not rows however they are spelled`() {
        assertEquals(
            MediaStoreUri.NotMediaStore,
            classifyMediaStoreUri("content://media/picker/0/com.android.providers.media.photopicker/media/42"),
        )
        assertEquals(
            MediaStoreUri.NotMediaStore,
            classifyMediaStoreUri("content://media/%70icker/0/com.android.providers.media.photopicker/media/42"),
        )
    }

    @Test
    fun `mediaStoreRowId keeps returning the id for rows and null otherwise`() {
        assertEquals(42L, mediaStoreRowId("content://%6Dedia/external/images/media/%34%32"))
        assertNull(mediaStoreRowId("content://media/external/images/media/notanumber"))
        assertNull(mediaStoreRowId("content://com.example.files/media/42"))
    }

    // ---- a media row the catalogue has not scanned yet ------------------------------------

    private val lockedKey = "path:pictures/private"

    private fun mediaRow(path: String?, trashed: Boolean = false) = mediaStoreRowAsset(
        rowId = 77,
        contentUri = "content://media/external/images/media/77",
        displayName = "77.jpg",
        mimeType = "image/jpeg",
        bucketId = 5,
        bucketName = path?.trimEnd('/')?.substringAfterLast('/'),
        relativePath = path,
        isTrashed = trashed,
    )

    private fun browsableWith(unlocked: Set<String> = emptySet()): (List<MediaAsset>) -> List<MediaAsset> = { list ->
        com.fotoxplorr.app.gallery.browsableAssets(
            assets = list,
            archivedIds = emptySet(),
            sensitiveIds = emptySet(),
            lockedFolders = setOf(lockedKey),
            unlockedFolders = unlocked,
            hideSensitive = true,
        )
    }

    @Test
    fun `an unscanned row in a locked folder is hidden`() {
        assertEquals(
            OpenRequestLookup.Hidden,
            lookupUncataloguedRow(mediaRow("Pictures/Private/"), browsableWith()),
        )
    }

    @Test
    fun `the same row is fine once its folder is unlocked`() {
        assertEquals(
            OpenRequestLookup.Unknown,
            lookupUncataloguedRow(mediaRow("Pictures/Private/"), browsableWith(unlocked = setOf(lockedKey))),
        )
    }

    @Test
    fun `an unscanned row in an ordinary folder may be opened as an external file`() {
        assertEquals(OpenRequestLookup.Unknown, lookupUncataloguedRow(mediaRow("DCIM/Camera/"), browsableWith()))
    }

    @Test
    fun `an unscanned trashed row is hidden`() {
        assertEquals(
            OpenRequestLookup.Hidden,
            lookupUncataloguedRow(mediaRow("DCIM/Camera/", trashed = true), browsableWith()),
        )
    }

    @Test
    fun `a row MediaStore will not describe cannot be verified and is refused`() {
        assertEquals(OpenRequestLookup.Unverified, lookupUncataloguedRow(null, browsableWith()))
    }

    @Test
    fun `the stub row carries the same folder identity the scanner would give`() {
        val stub = mediaRow("Pictures/Private/")
        assertEquals(MediaId(77), stub.id)
        assertEquals(lockedKey, com.fotoxplorr.app.gallery.folderIdentity(stub).key.value)
    }
}
