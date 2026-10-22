package com.fotoxplorr.app.recognition

import com.fotoxplorr.app.media.MediaAsset
import com.fotoxplorr.app.textselect.Quad
import com.fotoxplorr.app.textselect.RawBlock
import com.fotoxplorr.app.textselect.RawLine
import com.fotoxplorr.app.textselect.RawWord
import com.fotoxplorr.app.textselect.TextLayout
import com.fotoxplorr.core.model.MediaId
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

/**
 * Caching, single-flight, cancellation and release of [TextLayoutProvider], against a fake engine
 * so no decoder and no ML Kit is involved (those are pinned by `TextLayoutDecodingTest` and
 * `MlKitTextAdapterTest`). Runs on real threads: the provider's passes run on its own dispatcher.
 */
class TextLayoutProviderTest {

    private class FakeEngine : TextLayoutProvider.Engine {
        val reads = AtomicInteger()
        val closes = AtomicInteger()
        val cancelledReads = AtomicInteger()

        /** Completed by the first read that starts. */
        val started = CompletableDeferred<Unit>()

        /** When set, a read suspends until it is completed. */
        @Volatile var gate: CompletableDeferred<Unit>? = null
        @Volatile var outcome: (MediaAsset) -> TextLayout? = { layoutOf("word") }

        override suspend fun read(asset: MediaAsset): TextLayout? {
            reads.incrementAndGet()
            started.complete(Unit)
            try {
                gate?.await()
            } catch (e: CancellationException) {
                cancelledReads.incrementAndGet()
                throw e
            }
            return outcome(asset)
        }

        override fun close() {
            closes.incrementAndGet()
        }
    }

    private fun photo(id: Long, modified: Long = 1_000L, mime: String = "image/jpeg", name: String = "p$id.jpg") = MediaAsset(
        id = MediaId(id),
        contentUriString = "content://media/external/images/media/$id",
        displayName = name,
        mimeType = mime,
        bucketName = "Camera",
        dateTakenMillis = id,
        dateModifiedSeconds = modified,
        width = 400,
        height = 200,
        sizeBytes = 5_000L,
        relativePath = "DCIM/Camera/",
        isFavorite = false,
        isTrashed = false,
    )

    private fun provider(engine: FakeEngine, capacity: Int = 8) =
        TextLayoutProvider(engine, capacity, Dispatchers.Default)

    @Test
    fun `a second call for the same photo and revision does not run recognition again`() = runBlocking {
        val engine = FakeEngine()
        val provider = provider(engine)
        val first = provider.layoutFor(photo(1))
        val second = provider.layoutFor(photo(1))

        assertNotNull(first)
        assertSame(first, second)
        assertSame(first, provider.peek(photo(1)))
        assertEquals(1, engine.reads.get())
    }

    @Test
    fun `a changed revision is a different photo and runs again`() = runBlocking {
        val engine = FakeEngine()
        val provider = provider(engine)
        provider.layoutFor(photo(1, modified = 1_000L))
        provider.layoutFor(photo(1, modified = 2_000L))
        provider.layoutFor(photo(2, modified = 1_000L))

        assertEquals(3, engine.reads.get())
        assertNull(provider.peek(photo(1, modified = 3_000L)))
    }

    @Test
    fun `concurrent callers for one photo share one run`() = runBlocking {
        val engine = FakeEngine().apply { gate = CompletableDeferred() }
        val provider = provider(engine)
        val callers = List(4) { async(Dispatchers.Default) { provider.layoutFor(photo(1)) } }
        withTimeout(5_000) { engine.started.await() }
        engine.gate!!.complete(Unit)
        val results = withTimeout(5_000) { callers.awaitAll() }

        assertEquals(1, engine.reads.get())
        assertTrue(results.all { it != null && it === results[0] })
    }

    @Test
    fun `a photo with no text caches an empty layout rather than asking again`() = runBlocking {
        val engine = FakeEngine().apply { outcome = { TextLayout.empty(400, 200) } }
        val provider = provider(engine)

        assertTrue(provider.layoutFor(photo(1))!!.isEmpty)
        assertTrue(provider.layoutFor(photo(1))!!.isEmpty)
        assertEquals(1, engine.reads.get())
    }

    @Test
    fun `a failed read is null, not an exception, and is retried next time`() = runBlocking {
        val engine = FakeEngine().apply { outcome = { null } }
        val provider = provider(engine)
        assertNull(provider.layoutFor(photo(1)))
        assertNull(provider.layoutFor(photo(1)))
        assertEquals(2, engine.reads.get())

        engine.outcome = { throw IllegalStateException("decoder blew up") }
        assertNull(provider.layoutFor(photo(2)))
        engine.outcome = { throw OutOfMemoryError("too big") }
        assertNull(provider.layoutFor(photo(3)))
        engine.outcome = { layoutOf("fine") }
        assertNotNull(provider.layoutFor(photo(3)))
    }

    @Test
    fun `video, SVG, RAW and animated images are never read`() = runBlocking {
        val engine = FakeEngine()
        val provider = provider(engine)

        assertNull(provider.layoutFor(photo(1, mime = "video/mp4", name = "clip.mp4")))
        assertNull(provider.layoutFor(photo(2, mime = "image/svg+xml", name = "logo.svg")))
        assertNull(provider.layoutFor(photo(3, mime = "image/x-adobe-dng", name = "raw.dng")))
        assertNull(provider.layoutFor(photo(4), isAnimated = true))
        assertEquals(0, engine.reads.get())
    }

    @Test
    fun `cancelling the only caller cancels the pass and the cancellation reaches the caller`() = runBlocking {
        val engine = FakeEngine().apply { gate = CompletableDeferred() }
        val provider = provider(engine)
        val caller = async(Dispatchers.Default) { provider.layoutFor(photo(1)) }
        withTimeout(5_000) { engine.started.await() }
        caller.cancelAndJoin()

        assertTrue(caller.isCancelled)
        val failure = runCatching { caller.await() }.exceptionOrNull()
        assertTrue(failure is CancellationException)
        awaitTrue { engine.cancelledReads.get() == 1 }
        assertNull(provider.peek(photo(1)))
    }

    @Test
    fun `one caller leaving does not cancel a pass another still waits on`() = runBlocking {
        val engine = FakeEngine().apply { gate = CompletableDeferred() }
        val provider = provider(engine)
        val staying = async(Dispatchers.Default) { provider.layoutFor(photo(1)) }
        val leaving = async(Dispatchers.Default) { provider.layoutFor(photo(1)) }
        withTimeout(5_000) { engine.started.await() }
        // Let both register as waiters before one leaves.
        delay(200)
        leaving.cancelAndJoin()
        engine.gate!!.complete(Unit)

        assertNotNull(withTimeout(5_000) { staying.await() })
        assertEquals(0, engine.cancelledReads.get())
        assertEquals(1, engine.reads.get())
    }

    @Test
    fun `the least recently used layout is dropped past capacity`() = runBlocking {
        val engine = FakeEngine()
        val provider = provider(engine, capacity = 2)
        provider.layoutFor(photo(1))
        provider.layoutFor(photo(2))
        provider.layoutFor(photo(1)) // touch 1, so 2 is now the eldest
        provider.layoutFor(photo(3))

        assertNotNull(provider.peek(photo(1)))
        assertNull(provider.peek(photo(2)))
        assertNotNull(provider.peek(photo(3)))
        assertEquals(3, engine.reads.get())
    }

    @Test
    fun `release forgets every layout, closes the engine, and the provider still works`() = runBlocking {
        val engine = FakeEngine()
        val provider = provider(engine)
        provider.layoutFor(photo(1))
        provider.layoutFor(photo(2))
        withTimeout(5_000) { provider.release().join() }

        assertNull(provider.peek(photo(1)))
        assertNull(provider.peek(photo(2)))
        assertEquals(1, engine.closes.get())

        assertNotNull(provider.layoutFor(photo(1)))
        assertEquals(3, engine.reads.get())
        withTimeout(5_000) { provider.release().join() }
        assertEquals(2, engine.closes.get())
    }

    @Test
    fun `release during a pass gives the waiting caller no layout and caches nothing`() = runBlocking {
        val engine = FakeEngine().apply { gate = CompletableDeferred() }
        val provider = provider(engine)
        val caller = async(Dispatchers.Default) { provider.layoutFor(photo(1)) }
        withTimeout(5_000) { engine.started.await() }
        withTimeout(5_000) { provider.release().join() }

        assertNull(withTimeout(5_000) { caller.await() })
        assertNull(provider.peek(photo(1)))
        assertEquals(1, engine.closes.get())
        assertFalse(caller.isCancelled)
    }

    @Test
    fun `the block-only fallback is the stored blocks as one word each in the upright frame`() {
        val blocks = listOf(
            TextBlock("EXIT", 0.1f, 0.2f, 0.3f, 0.3f),
            TextBlock("Platform 9", 0.5f, 0.6f, 0.9f, 0.7f),
        )
        val layout = TextLayoutProvider.blockOnly(blocks, 1000, 500)

        assertFalse(layout.wordLevel)
        assertEquals(listOf("EXIT", "Platform 9"), layout.words.map { it.text })
        assertEquals(1000f, layout.frameWidth, 0f)
        assertEquals(500f, layout.frameHeight, 0f)
        assertEquals(100f, layout.words[0].quad.left, 1e-3f)
        assertEquals(100f, layout.words[0].quad.top, 1e-3f)
        assertTrue(TextLayoutProvider.blockOnly(emptyList(), 1000, 500).isEmpty)
    }

    private suspend fun awaitTrue(condition: () -> Boolean) = withTimeout(5_000) {
        while (!condition()) delay(10)
    }
}

private fun layoutOf(text: String): TextLayout =
    TextLayout.build(listOf(RawBlock(listOf(RawLine(listOf(RawWord(text, Quad.fromBounds(0.1f, 0.1f, 0.4f, 0.2f))))))), 400, 200)
