package com.fotoxplorr.app.viewer

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The pure part of building an ad-hoc [com.fotoxplorr.app.media.MediaAsset] from an incoming
 * `ACTION_VIEW` intent (P0-18's own brief, verbatim: "put the pure part -- name, MIME and
 * extension resolution -- in a tested function"). No `Uri`/`Context` anywhere, so this needs no
 * Robolectric: every input is exactly what a real caller would have already pulled out of
 * `OpenableColumns`, `Intent.type` and `ContentResolver.getType`.
 */
class ExternalAssetIdentityTest {

    @Test
    fun `a queried display name and a real mime type both win outright`() {
        val identity = resolveExternalAssetIdentity(
            queriedDisplayName = "Vacation.jpg",
            uriLastPathSegment = "123",
            intentMimeType = "image/jpeg",
            resolverMimeType = null,
        )
        assertEquals("Vacation.jpg", identity.displayName)
        assertEquals("image/jpeg", identity.mimeType)
    }

    @Test
    fun `a blank queried name falls back to the uri's own last path segment`() {
        val identity = resolveExternalAssetIdentity(
            queriedDisplayName = "  ",
            uriLastPathSegment = "clip.mp4",
            intentMimeType = "video/mp4",
            resolverMimeType = null,
        )
        assertEquals("clip.mp4", identity.displayName)
    }

    @Test
    fun `no usable name anywhere falls back to a generic placeholder, never blank`() {
        val identity = resolveExternalAssetIdentity(
            queriedDisplayName = null,
            uriLastPathSegment = null,
            intentMimeType = "image/png",
            resolverMimeType = null,
        )
        assertEquals("Untitled", identity.displayName)
    }

    @Test
    fun `a wildcard intent type is skipped in favour of the resolver's own real answer`() {
        val identity = resolveExternalAssetIdentity(
            queriedDisplayName = "photo.heic",
            uriLastPathSegment = null,
            intentMimeType = "*/*",
            resolverMimeType = "image/heic",
        )
        assertEquals("image/heic", identity.mimeType)
    }

    @Test
    fun `neither mime source is usable falls back to the file extension`() {
        val identity = resolveExternalAssetIdentity(
            queriedDisplayName = "IMG_0001.PNG",
            uriLastPathSegment = null,
            intentMimeType = "*/*",
            resolverMimeType = "",
        )
        // Matched case-insensitively against the extension table -- the display name itself is
        // left exactly as reported, uppercase extension and all.
        assertEquals("IMG_0001.PNG", identity.displayName)
        assertEquals("image/png", identity.mimeType)
    }

    @Test
    fun `an unrecognised extension and no mime source is an honest generic fallback`() {
        val identity = resolveExternalAssetIdentity(
            queriedDisplayName = "mystery.xyz",
            uriLastPathSegment = null,
            intentMimeType = null,
            resolverMimeType = null,
        )
        assertEquals("application/octet-stream", identity.mimeType)
    }

    @Test
    fun `every supported extension fallback resolves to a real image or video mime type`() {
        val cases = mapOf(
            "a.jpg" to "image/jpeg",
            "a.jpeg" to "image/jpeg",
            "a.png" to "image/png",
            "a.gif" to "image/gif",
            "a.webp" to "image/webp",
            "a.heic" to "image/heic",
            "a.heif" to "image/heif",
            "a.bmp" to "image/bmp",
            "a.mp4" to "video/mp4",
            "a.mov" to "video/quicktime",
            "a.webm" to "video/webm",
            "a.mkv" to "video/x-matroska",
            "a.3gp" to "video/3gpp",
        )
        cases.forEach { (name, expectedMime) ->
            val identity = resolveExternalAssetIdentity(
                queriedDisplayName = name,
                uriLastPathSegment = null,
                intentMimeType = null,
                resolverMimeType = null,
            )
            assertEquals("extension in '$name'", expectedMime, identity.mimeType)
        }
    }
}
