package com.fotoxplorr.app.share

import android.graphics.Bitmap
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import com.fotoxplorr.app.media.MediaAsset
import com.fotoxplorr.app.media.MediaId
import com.fotoxplorr.app.pro.ProEntitlement
import java.io.ByteArrayInputStream
import kotlin.io.path.createTempFile
import androidx.core.content.FileProvider
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * [SharePreparer.prepare] against real JPEGs served through a shadowed [android.content.ContentResolver]
 * -- covering P0-04's actual behavior change: per-item [PreparedItem] outcomes instead of failing
 * the whole batch, GPS removed but `Orientation` restored on the plain (unrendered) strip path, and
 * an untouched copy when the sharer deliberately kept metadata. NATIVE graphics mode for the same
 * reason [MetadataStripperJpegTest] needs it: real `Bitmap.compress`/decode.
 *
 * Every asset here resolves `watermark = false` (a Pro entitlement) and the default [ShareFrame.NONE],
 * so [ShareOptions.requiresRender] is false and these exercise [SharePreparer]'s plain-copy /
 * metadata-strip paths specifically -- the ones this task actually changed. The framed/watermarked
 * render path was already covered by [MetadataStripperJpegTest]'s and P0-03's own coverage of
 * `decodeUpright`, and is unchanged by P0-04 beyond returning a [PreparedItem] instead of a bare
 * [Uri]; it does not get its own per-item batch test here.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34])
class SharePreparerTest {

    private val context = RuntimeEnvironment.getApplication()
    private val shadowResolver = shadowOf(context.contentResolver)
    private var nextId = 1L

    private class FakeProEntitlement(pro: Boolean) : ProEntitlement {
        override val isPro: StateFlow<Boolean> = MutableStateFlow(pro)
        override fun recordUnlock() = Unit
    }

    /** Pro, so [ShareOptions]'s default `watermark = true` resolves to false and every test asset
     * below goes through the plain copy/strip path rather than a render. */
    private fun preparer() = SharePreparer(context, FakeProEntitlement(pro = true))

    /**
     * [FileProvider] caches its [file_paths.xml][androidx.core.content.FileProvider]-derived roots
     * in a static map keyed by authority the first time anything calls `getUriForFile` for it. Left
     * to Robolectric's own manifest-driven lazy provider discovery, that first-ever call within a
     * fresh sandbox resolves against the provider's own not-yet-fully-attached context and caches a
     * root that does not match this test's actual (per-test, randomly-named) cache directory --
     * every subsequent `getUriForFile` in the same test then throws "Failed to find configured root
     * that contains ...", even though the file it is being asked about is squarely inside the
     * declared `outgoing-share` root. Explicitly attaching the provider up front, the documented
     * Robolectric pattern for testing code that calls a provider without going through
     * `ContentResolver` first, avoids that cold-start path entirely.
     *
     * A plain [Robolectric.setupContentProvider] builds a bare [android.content.pm.ProviderInfo]
     * that leaves `grantUriPermissions` false, and [FileProvider] itself refuses to attach without
     * it ("Provider must grant uri permissions") -- so this mirrors the manifest's own
     * `android:grantUriPermissions="true"` declaration by hand.
     */
    @Before
    fun attachFileProvider() {
        val providerInfo = android.content.pm.ProviderInfo().apply {
            authority = "${context.packageName}.files"
            grantUriPermissions = true
            exported = false
        }
        Robolectric.buildContentProvider(FileProvider::class.java).create(providerInfo)
    }

    private fun registerAsset(bytes: ByteArray, displayName: String = "photo.jpg", mimeType: String = "image/jpeg"): MediaAsset {
        val id = nextId++
        val uri = Uri.parse("content://share-preparer-test/$id")
        shadowResolver.registerInputStreamSupplier(uri) { ByteArrayInputStream(bytes) }
        return MediaAsset(
            id = MediaId(id),
            contentUriString = uri.toString(),
            displayName = displayName,
            mimeType = mimeType,
            bucketName = null,
            dateTakenMillis = 0L,
            dateModifiedSeconds = 0L,
            width = 0,
            height = 0,
            sizeBytes = bytes.size.toLong(),
            relativePath = null,
            isFavorite = false,
            isTrashed = false,
        )
    }

    private fun realJpegWithGps(width: Int, height: Int, orientation: Int? = null): ByteArray {
        val file = createTempFile("fx-share-source", ".jpg").toFile()
        file.deleteOnExit()
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it) }
        ExifInterface(file.absolutePath).apply {
            setAttribute(ExifInterface.TAG_GPS_LATITUDE, "51/1,30/1,0/1")
            setAttribute(ExifInterface.TAG_GPS_LATITUDE_REF, "N")
            setAttribute(ExifInterface.TAG_GPS_LONGITUDE, "0/1,7/1,0/1")
            setAttribute(ExifInterface.TAG_GPS_LONGITUDE_REF, "W")
            if (orientation != null) setAttribute(ExifInterface.TAG_ORIENTATION, orientation.toString())
            saveAttributes()
        }
        return file.readBytes()
    }

    private fun readBack(uri: Uri): ByteArray =
        context.contentResolver.openInputStream(uri)!!.use { it.readBytes() }

    /** A syntactically valid, single-frame GIF -- enough for [MetadataStripper] to accept and
     * [SharePreparer] to route by MIME; its pixels are never inspected by any test here. */
    private fun minimalGif(): ByteArray {
        val header = "GIF89a".toByteArray(Charsets.US_ASCII)
        val logicalScreenDescriptor = byteArrayOf(1, 0, 1, 0, 0x00, 0, 0)
        val imageDescriptor = byteArrayOf(0x2C, 0, 0, 0, 0, 1, 0, 1, 0, 0x00)
        val lzwMinCodeSize = byteArrayOf(0x02)
        val dataSubBlock = byteArrayOf(2, 0x44, 0x01)
        val subBlockTerminator = byteArrayOf(0)
        val trailer = byteArrayOf(0x3B)
        return header + logicalScreenDescriptor + imageDescriptor + lzwMinCodeSize + dataSubBlock +
            subBlockTerminator + trailer
    }

    @Test
    fun `a known-animated asset is stripped but never rendered, even with a frame requested`() = runBlocking {
        val asset = registerAsset(minimalGif(), "party.gif", mimeType = "image/gif")

        val items = preparer()
            .prepare(
                listOf(asset),
                ShareOptions(frame = ShareFrame.POLAROID, stripMetadata = true),
                animatedIds = setOf(asset.id),
            )
            .getOrThrow()

        val ready = items.single() as PreparedItem.Ready
        // A render always comes out "image/jpeg" or "image/png" (see prepareRendered) -- seeing
        // the GIF's own MIME type back confirms this went through the strip path, not a render
        // that would have baked a static Polaroid frame over the animation.
        assertEquals("image/gif", ready.mimeType)
    }

    @Test
    fun `a file the index has not marked animated is rendered normally, unlike the old MIME-blanket exclusion`() = runBlocking {
        // Real JPEG bytes, claiming a MIME type the OLD code excluded from rendering purely by
        // name -- P0-14's whole point is that this asset, absent from animatedIds, is no longer
        // automatically treated as animated just because its MIME type could be.
        val asset = registerAsset(realJpegWithGps(4, 4), "photo.webp", mimeType = "image/webp")

        val items = preparer()
            .prepare(listOf(asset), ShareOptions(frame = ShareFrame.POLAROID, stripMetadata = true))
            .getOrThrow()

        val ready = items.single() as PreparedItem.Ready
        // A Polaroid frame always renders to JPEG (see prepareRendered) -- seeing that, rather
        // than the source's own "image/webp" coming straight through, confirms this went through
        // the render path, which the old MIME-blanket check would have refused outright.
        assertEquals("image/jpeg", ready.mimeType)
    }

    @Test
    fun `a batch with one undecodable item still shares the other two, naming the bad one Failed`() = runBlocking {
        val good1 = registerAsset(realJpegWithGps(4, 4), "a.jpg")
        val bad = registerAsset(byteArrayOf(1, 2, 3, 4), "b.jpg")
        val good2 = registerAsset(realJpegWithGps(4, 4), "c.jpg")

        val items = preparer().prepare(listOf(good1, bad, good2), ShareOptions()).getOrThrow()

        assertEquals(3, items.size)
        assertTrue("expected a.jpg to be Ready", items[0] is PreparedItem.Ready)
        val failed = items[1] as PreparedItem.Failed
        assertEquals(bad, failed.asset)
        assertTrue("expected c.jpg to be Ready", items[2] is PreparedItem.Ready)
    }

    @Test
    fun `a plain stripped share removes GPS but restores a non-default Orientation`() = runBlocking {
        val asset = registerAsset(realJpegWithGps(6, 4, orientation = ExifInterface.ORIENTATION_ROTATE_90))

        val items = preparer().prepare(listOf(asset), ShareOptions(stripMetadata = true)).getOrThrow()

        val ready = items.single() as PreparedItem.Ready
        assertEquals("image/jpeg", ready.mimeType)
        val exif = ExifInterface(ByteArrayInputStream(readBack(ready.uri)))
        assertNull(exif.latLong)
        assertEquals(
            ExifInterface.ORIENTATION_ROTATE_90,
            exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL),
        )
    }

    @Test
    fun `keeping metadata copies the source through untouched, GPS and all`() = runBlocking {
        val asset = registerAsset(realJpegWithGps(4, 4))

        val items = preparer().prepare(listOf(asset), ShareOptions(stripMetadata = false)).getOrThrow()

        val ready = items.single() as PreparedItem.Ready
        val exif = ExifInterface(ByteArrayInputStream(readBack(ready.uri)))
        assertNotNull("a kept-metadata share must not have its GPS stripped", exif.latLong)
    }
}
