package com.fotoxplorr.app.gallery

import com.fotoxplorr.app.media.MediaAsset
import com.fotoxplorr.app.metadata.DefaultSidecarService
import com.fotoxplorr.app.metadata.InMemorySidecarDirectory
import com.fotoxplorr.app.metadata.SidecarDirectory
import com.fotoxplorr.app.metadata.SidecarEntry
import com.fotoxplorr.app.metadata.SidecarParent
import com.fotoxplorr.app.metadata.SidecarWriteResult
import com.fotoxplorr.core.metadata.SidecarAnnotations
import com.fotoxplorr.core.metadata.SidecarFlag
import com.fotoxplorr.core.metadata.SidecarPolicy
import com.fotoxplorr.core.metadata.XmpAnnotationCodec
import com.fotoxplorr.core.model.MediaId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The cull's sidecars go the sidecar layer's way: a granted folder found by the photo's volume and
 * relative path, never a raw path on the primary volume; no grant is an honest NeedsFolderGrant;
 * and a folder is listed once for the whole cull.
 */
class CullSidecarRouteTest {

    private fun asset(i: Int, rel: String? = "DCIM/Camera/") = MediaAsset(
        id = MediaId(i.toLong()), contentUriString = "content://media/external/images/media/$i", displayName = "IMG_$i.jpg",
        mimeType = "image/jpeg", bucketName = null, dateTakenMillis = 0, dateModifiedSeconds = 0,
        width = 4, height = 3, sizeBytes = 1, relativePath = rel, isFavorite = false, isTrashed = false,
    )

    private val dcim = FolderGrant("content://tree/primary%3ADCIM", "primary:DCIM", write = true)
    private val sdDcim = FolderGrant("content://tree/1234-5678%3ADCIM", "1234-5678:DCIM", write = true)

    // ---- which granted folder ----

    @Test
    fun `a grant covering the photo's folder on its own volume is used`() {
        assertEquals(GrantedFolder(dcim, "primary:DCIM/Camera"), grantedFolderFor("external_primary", "DCIM/Camera/", listOf(dcim)))
    }

    @Test
    fun `a photo on an SD card never gets the primary volume's folder of the same name`() {
        assertNull(grantedFolderFor("1234-5678", "DCIM/Camera/", listOf(dcim)))
        assertEquals(GrantedFolder(sdDcim, "1234-5678:DCIM/Camera"), grantedFolderFor("1234-5678", "DCIM/Camera/", listOf(dcim, sdDcim)))
    }

    @Test
    fun `an unknown volume or path is no grant, not a guess`() {
        assertNull(grantedFolderFor(null, "DCIM/Camera/", listOf(dcim)))
        assertNull(grantedFolderFor("external_primary", null, listOf(dcim)))
        assertNull(grantedFolderFor("external_primary", "Pictures/", listOf(dcim)))
    }

    @Test
    fun `a writable grant wins over a read-only one, then the narrowest`() {
        val readOnlyCamera = FolderGrant("t1", "primary:DCIM/Camera", write = false)
        val whole = FolderGrant("t2", "primary:", write = true)
        assertEquals(dcim, grantedFolderFor("external_primary", "DCIM/Camera", listOf(whole, readOnlyCamera, dcim))?.grant)
    }

    // ---- the route ----

    private class CountingDirectory(val inner: InMemorySidecarDirectory) : SidecarDirectory by inner {
        var listings = 0
        override fun listNames(): List<String> {
            listings++
            return inner.listNames()
        }
        override fun find(fileName: String): SidecarEntry? {
            listings++ // a real folder lists on a miss
            return inner.find(fileName)
        }
    }

    private fun route(
        assets: List<MediaAsset>,
        grants: List<FolderGrant>,
        folder: SidecarDirectory,
        volume: String = "external_primary",
    ) = CullSidecarRoute(
        assets = assets,
        grants = { grants },
        volumes = { ids -> ids.associateWith { volume } },
        directory = { folder },
    )

    @Test
    fun `without a grant a cull write reports a needed folder grant and nothing is saved`() {
        val photos = listOf(asset(1))
        val route = route(photos, emptyList(), InMemorySidecarDirectory())
        assertEquals(SidecarParent.NoGrant, route.resolve(1)!!.parent)
        val service = DefaultSidecarService(policy = { SidecarPolicy(writeEnabled = true) })
        val sidecars = SidecarAnnotationStore(service, absent = route::absent, resolve = route::resolve)
        val session = CullSession(WriteThroughAnnotationStore(sidecars), sidecars, route)
        assertFalse(session.store.put(1, SidecarAnnotations(flag = SidecarFlag.PICK)))
        assertEquals(SidecarWriteResult.NeedsFolderGrant, sidecars.lastWrite)
        assertTrue(session.needsFolderGrant())
        assertFalse(session.store.lastPutDurable)
    }

    @Test
    fun `with a grant the sidecar is written beside the photo through the sidecar service`() {
        val folder = InMemorySidecarDirectory()
        val photos = listOf(asset(1))
        val route = route(photos, listOf(dcim), folder)
        val service = DefaultSidecarService(policy = { SidecarPolicy(writeEnabled = true) })
        val sidecars = SidecarAnnotationStore(service, absent = route::absent, resolve = route::resolve)
        val session = CullSession(WriteThroughAnnotationStore(sidecars), sidecars, route)
        assertTrue(session.store.put(1, SidecarAnnotations(rating = 4, flag = SidecarFlag.PICK)))
        assertFalse(session.needsFolderGrant())
        assertEquals(listOf("IMG_1.jpg.xmp"), folder.files.keys.toList())
        assertEquals(4, XmpAnnotationCodec.readBytes(folder.files.getValue("IMG_1.jpg.xmp"))?.rating)
    }

    @Test
    fun `a photo on another volume than the grant is not written`() {
        val folder = InMemorySidecarDirectory()
        val route = route(listOf(asset(1)), listOf(dcim), folder, volume = "1234-5678")
        assertEquals(SidecarParent.NoGrant, route.resolve(1)!!.parent)
    }

    @Test
    fun `the first pass over a big folder lists it once and reads only the photos with a sidecar`() {
        val memory = InMemorySidecarDirectory()
        val photos = List(2000) { asset(it) }
        photos.forEach { memory.others += it.displayName }
        memory.files["IMG_7.jpg.xmp"] = XmpAnnotationCodec.update(
            null, SidecarAnnotations(flag = SidecarFlag.REJECT), "2026-01-01T00:00:00Z", emptySet(), emptySet(),
        )!!
        val counting = CountingDirectory(memory)
        val route = route(photos, listOf(dcim), counting)
        val service = DefaultSidecarService(policy = { SidecarPolicy(writeEnabled = true) })
        val sidecars = SidecarAnnotationStore(service, absent = route::absent, resolve = route::resolve)
        val store = WriteThroughAnnotationStore(sidecars)
        photos.forEach { store.get(it.id.value) }
        assertEquals(CullSummary(0, 1, 1999), store.summary(photos.size))
        assertTrue("listed ${counting.listings} times", counting.listings <= 3)
    }

    @Test
    fun `a folder granted later is looked up again`() {
        var grants = emptyList<FolderGrant>()
        val route = CullSidecarRoute(listOf(asset(1)), { grants }, { ids -> ids.associateWith { "external_primary" } }, { InMemorySidecarDirectory() })
        assertEquals(SidecarParent.NoGrant, route.resolve(1)!!.parent)
        grants = listOf(dcim)
        route.refresh()
        assertTrue(route.resolve(1)!!.parent is SidecarParent.Directory)
    }

    @Test
    fun `a listed folder learns the files it writes`() {
        val listed = ListedDirectory(InMemorySidecarDirectory())
        assertFalse(listed.mayHoldAny(listOf("IMG_1.jpg.xmp")))
        assertTrue(listed.write("IMG_1.jpg.xmp", null, byteArrayOf(1)))
        assertTrue(listed.mayHoldAny(listOf("img_1.JPG.xmp")))
        assertEquals("IMG_1.jpg.xmp", listed.find("IMG_1.jpg.xmp")?.fileName)
    }
}
