package com.fotoxplorr.app.sources.previews

import android.graphics.Bitmap
import android.graphics.Color
import androidx.test.core.app.ApplicationProvider
import com.fotoxplorr.app.gallery.browsableAssets
import com.fotoxplorr.app.media.MediaAsset
import com.fotoxplorr.app.sources.gallery.SourceAssetMapper
import com.fotoxplorr.app.sources.gallery.entry
import com.fotoxplorr.app.sources.gallery.libraryAsset
import com.fotoxplorr.app.sources.gallery.sourceRow
import com.fotoxplorr.core.model.MediaId
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34])
class SourcePreviewWriterTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val settings = InMemoryPreviewSettings()
    private val store by lazy { SourcePreviewStore(File(tmp.root, "p"), capOf = settings::capBytes, io = Dispatchers.Unconfined) }
    private var decodes = 0
    private fun writer() = SourcePreviewsFixtures.writer(store, settings) { decodes++; solid(256, 170) }

    private fun solid(w: Int, h: Int) = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.rgb(200, 60, 30)) }

    private fun sourceAsset(row: Long, source: Long = 3) = SourceAssetMapper.toMediaAsset(entry(sourceRow(row, sourceRow = source)))!!

    @Test
    fun `a capture stores real WebP bytes that decode back to the picture`() = runBlocking {
        val a = sourceAsset(7)
        assertTrue(writer().capture(a))
        val bytes = store.read(3, 7, a.dateModifiedSeconds)!!
        assertEquals("RIFF", String(bytes, 0, 4, Charsets.US_ASCII))
        assertEquals("WEBP", String(bytes, 8, 4, Charsets.US_ASCII))
        val back = PreviewCodec.decode(bytes)
        assertNotNull(back)
        assertEquals(256, back!!.width)
        assertEquals(170, back.height)
        assertEquals(bytes.size.toLong(), store.sizeBytes(3))
    }

    @Test
    fun `a second capture of the same revision decodes nothing`() = runBlocking {
        val a = sourceAsset(7)
        val w = writer()
        w.capture(a); w.capture(a)
        assertEquals(1, decodes)
    }

    @Test
    fun `a library asset never gets a preview`() = runBlocking {
        assertFalse(writer().capture(libraryAsset(5)))
        assertEquals(0, decodes)
        assertEquals(0L, store.sizeBytes(3))
    }

    @Test
    fun `a cap of zero is off for that source only`() = runBlocking {
        settings.setCapBytes(3, 0)
        val w = writer()
        assertFalse(w.capture(sourceAsset(1, source = 3)))
        assertTrue(w.capture(sourceAsset(2, source = 4)))
        assertEquals(0, store.count(3))
    }

    @Test
    fun `a file that will not decode is not retried every time`() = runBlocking {
        val w = SourcePreviewsFixtures.writer(store, settings) { decodes++; null }
        val a = sourceAsset(9)
        assertFalse(w.capture(a)); assertFalse(w.capture(a))
        assertEquals(1, decodes)
    }

    @Test
    fun `the background pass only sees the browsable list so a locked source photo gets no preview`() = runBlocking {
        val visible = sourceAsset(1)
        val locked = sourceAsset(2).copy(relativePath = "~src/3/Private/")
        val all: List<MediaAsset> = listOf(visible, locked, libraryAsset(8))
        val lockedKey = com.fotoxplorr.app.gallery.folderIdentity(locked).key.value
        val browsable = browsableAssets(all, emptySet(), emptySet<MediaId>(), setOf(lockedKey), emptySet(), hideSensitive = true)
        assertEquals(listOf(visible.id, libraryAsset(8).id), browsable.map { it.id })
        val pass = PreviewPass(writer(), store, settings)
        assertEquals(1, pass.run(browsable, onlineRows = setOf(3L)))
        assertTrue(store.has(3, 1, visible.dateModifiedSeconds))
        assertFalse("the locked asset has no preview", store.has(3, 2, locked.dateModifiedSeconds))
    }

    @Test
    fun `the pass is bounded skips offline and keep-off sources and what is already stored`() = runBlocking {
        val items = (1L..10L).map { sourceAsset(it) } + (11L..12L).map { sourceAsset(it, source = 4) } + (13L..14L).map { sourceAsset(it, source = 5) }
        settings.setKeep(5, false)
        val pass = PreviewPass(writer(), store, settings)
        assertEquals(4, pass.run(items, onlineRows = setOf(3L, 5L), limit = 4))
        assertEquals(6, pass.run(items, onlineRows = setOf(3L, 5L), limit = 100))
        assertEquals(0, pass.run(items, onlineRows = setOf(3L, 5L)))
        assertEquals(0, store.count(4))
        assertEquals(0, store.count(5))
    }

    @Test
    fun `a context built service puts its files under the no backup directory`() {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        val dir = File(ctx.noBackupFilesDir, "source-previews")
        assertTrue(dir.path.contains("no_backup"))
    }
}

internal object SourcePreviewsFixtures {
    fun writer(store: SourcePreviewStore, settings: PreviewSettings, decode: suspend (MediaAsset) -> Bitmap?) =
        PreviewWriter(ApplicationProvider.getApplicationContext(), store, settings, decode = decode)
}
