package com.fotoxplorr.app.board

import com.fotoxplorr.app.board.BoardTestKit.identity
import com.fotoxplorr.app.board.BoardTestKit.layer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class PhotoResolverTest {

    private fun ref(id: Long, name: String = "IMG_$id.jpg", size: Long = id * 1000, date: Long = 1_000L + id) =
        PhotoRef(id, identity(name, size, date), 4000, 3000)

    private fun catalogue(vararg refs: PhotoRef) = refs.associate { it.mediaId to it.identity!! }

    @Test
    fun `a photo still under its own id with its own identity is the same`() {
        val r = ref(1)
        assertEquals(PhotoResolution.Same(1), PhotoResolver.resolve(listOf(r), catalogue(r))[1])
    }

    @Test
    fun `a photo that is gone is missing, not an error`() {
        val r = ref(1)
        assertEquals(PhotoResolution.Missing, PhotoResolver.resolve(listOf(r), catalogue(ref(2)))[1])
        assertEquals(PhotoResolution.Missing, PhotoResolver.resolve(listOf(r), emptyMap())[1])
    }

    @Test
    fun `the same photo under another id is found by name, size and date`() {
        val old = ref(1)
        val now = PhotoRef(50, old.identity, 4000, 3000)
        assertEquals(PhotoResolution.Moved(50), PhotoResolver.resolve(listOf(old), catalogue(now))[1])
    }

    @Test
    fun `an id reused by a different photo is not the photo`() {
        val old = ref(1)
        val other = ref(1, name = "OTHER.jpg", size = 77)
        assertEquals(PhotoResolution.Missing, PhotoResolver.resolve(listOf(old), catalogue(other))[1])
    }

    @Test
    fun `two candidates that cannot be told apart are no match`() {
        val old = ref(1)
        val a = PhotoRef(10, identity("IMG_1.jpg", 1000, 0, "A/"))
        val b = PhotoRef(11, identity("IMG_1.jpg", 1000, 0, "B/"))
        val wantedWithoutPath = PhotoRef(1, identity("IMG_1.jpg", 1000, 0, null))
        assertEquals(PhotoResolution.Missing, PhotoResolver.resolve(listOf(wantedWithoutPath), catalogue(a, b))[1])
        assertEquals(PhotoResolution.Moved(10), PhotoResolver.resolve(listOf(PhotoRef(1, identity("IMG_1.jpg", 1000, 0, "A/"))), catalogue(a, b))[1])
        assertEquals(PhotoResolution.Missing, PhotoResolver.resolve(listOf(old), emptyMap())[1])
    }

    @Test
    fun `a photo with no identity is trusted by id alone`() {
        val bare = PhotoRef(3)
        assertEquals(PhotoResolution.Same(3), PhotoResolver.resolve(listOf(bare), catalogue(ref(3)))[3])
        assertEquals(PhotoResolution.Missing, PhotoResolver.resolve(listOf(bare), catalogue(ref(4)))[3])
    }

    @Test
    fun `a photo used twice is resolved once`() {
        val r = ref(1)
        assertEquals(1, PhotoResolver.resolve(listOf(r, r.copy()), catalogue(r)).size)
    }

    @Test
    fun `relinking points moved photos at their new id and leaves the rest alone`() {
        val moved = ref(1)
        val here = ref(2)
        val gone = ref(3)
        val board = Board(
            layers = listOf(
                layer(1, 0f, 0f, content = LayerContent.Photo(moved)),
                layer(2, 0f, 0f, content = LayerContent.Photo(here)),
                layer(3, 0f, 0f, content = LayerContent.Photo(gone)),
                layer(4, 0f, 0f, content = LayerContent.Photo(moved)),
                layer(5, 0f, 0f),
            ),
        )
        val resolution = PhotoResolver.resolve(listOf(moved, here, gone), catalogue(PhotoRef(90, moved.identity), here))
        val relinked = PhotoResolver.relink(board, resolution)
        fun idOf(l: Int) = ((relinked.layers[l].content as? LayerContent.Photo)?.ref?.mediaId)
        assertEquals(90L, idOf(0))
        assertEquals(2L, idOf(1))
        assertEquals(3L, idOf(2))
        assertEquals(90L, idOf(3))
        assertEquals(null, idOf(4))
        assertEquals(moved.identity, (relinked.layers[0].content as LayerContent.Photo).ref.identity)
        assertSame(relinked, PhotoResolver.relink(relinked, PhotoResolver.resolve(relinked.photoRefs, catalogue(PhotoRef(90, moved.identity), here))))
    }

    @Test
    fun `ids that swap places are each relinked from the old id, not chained`() {
        val a = PhotoRef(5, identity("A.jpg", 10, 1))
        val b = PhotoRef(7, identity("B.jpg", 20, 2))
        val now = mapOf(7L to a.identity!!, 9L to b.identity!!)
        val res = PhotoResolver.resolve(listOf(a, b), now)
        assertEquals(PhotoResolution.Moved(7), res[5])
        assertEquals(PhotoResolution.Moved(9), res[7])
        val board = Board(layers = listOf(layer(1, 0f, 0f, content = LayerContent.Photo(a)), layer(2, 0f, 0f, content = LayerContent.Photo(b))))
        assertEquals(listOf(7L, 9L), PhotoResolver.relink(board, res).photoRefs.map { it.mediaId })
    }
}
