package com.fotoxplorr.app.receive

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReceivePlanTest {
    private fun image(n: Int) = ReceiveCandidate("content://sender/img$n.jpg", "image/jpeg", "img$n.jpg")

    @Test
    fun `images and videos are kept in the order given and classified`() {
        val plan = planReceive(
            listOf(
                image(1),
                ReceiveCandidate("content://sender/clip.mp4", "video/mp4", "clip.mp4"),
                image(2),
            ),
        )
        assertEquals(listOf("content://sender/img1.jpg", "content://sender/clip.mp4", "content://sender/img2.jpg"), plan.items.map { it.uri })
        assertEquals(listOf(ReceiveKind.IMAGE, ReceiveKind.VIDEO, ReceiveKind.IMAGE), plan.items.map { it.kind })
        assertEquals(2, plan.images)
        assertEquals(1, plan.videos)
        assertFalse(plan.wasCapped)
    }

    @Test
    fun `an unreadable item and a file scheme are dropped and counted`() {
        val plan = planReceive(
            listOf(
                image(1),
                ReceiveCandidate("content://sender/gone.jpg", "image/jpeg", readable = false),
                ReceiveCandidate("file:///sdcard/secret.jpg", "image/jpeg", readable = true),
            ),
        )
        assertEquals(1, plan.items.size)
        assertEquals(2, plan.unreadable)
        assertEquals(0, plan.notMedia)
    }

    @Test
    fun `a document is not media and is counted apart`() {
        val plan = planReceive(listOf(image(1), ReceiveCandidate("content://sender/a.pdf", "application/pdf", "a.pdf")))
        assertEquals(1, plan.items.size)
        assertEquals(1, plan.notMedia)
        assertEquals(0, plan.unreadable)
    }

    @Test
    fun `a repeated uri counts once`() {
        val plan = planReceive(listOf(image(1), image(1), image(2)))
        assertEquals(2, plan.items.size)
        assertEquals(0, plan.unreadable)
    }

    @Test
    fun `the cap keeps the first items and says how many were left out`() {
        val plan = planReceive((1..600).map(::image))
        assertEquals(ReceivePlan.MAX_ITEMS, plan.items.size)
        assertEquals(100, plan.overCap)
        assertTrue(plan.wasCapped)
        assertEquals("content://sender/img1.jpg", plan.items.first().uri)
        assertEquals("content://sender/img500.jpg", plan.items.last().uri)
    }

    @Test
    fun `unreadable items never use up the cap`() {
        val bad = (1..10).map { ReceiveCandidate("content://sender/bad$it.jpg", "image/jpeg", readable = false) }
        val plan = planReceive(bad + (1..5).map(::image), cap = 5)
        assertEquals(5, plan.items.size)
        assertEquals(0, plan.overCap)
        assertEquals(10, plan.unreadable)
    }

    @Test
    fun `a wildcard type is not a type so the name and then the hint decide`() {
        assertEquals(ReceiveKind.IMAGE, classifyReceived("image/*", "x.PNG"))
        assertEquals(ReceiveKind.VIDEO, classifyReceived(null, "x.mov"))
        assertEquals(ReceiveKind.IMAGE, classifyReceived(null, null, typeHint = "image/*"))
        assertEquals(ReceiveKind.OTHER, classifyReceived(null, null, typeHint = "*/*"))
        assertEquals(ReceiveKind.OTHER, classifyReceived("application/pdf", "x", typeHint = "image/*"))
        assertEquals(ReceiveKind.IMAGE, classifyReceived("IMAGE/JPEG; charset=x", null))
    }

    @Test
    fun `a concrete type is kept lower case and a wildcard is not carried into the item`() {
        assertNull(concreteMime("*/*"))
        assertNull(concreteMime("image/*"))
        assertNull(concreteMime("nonsense"))
        assertEquals("image/png", concreteMime(" Image/PNG "))
        val plan = planReceive(listOf(ReceiveCandidate("content://s/a", "image/*", "a.jpg")))
        assertNull(plan.items.single().mimeType)
    }

    @Test
    fun `an empty share is an empty plan`() {
        val plan = planReceive(emptyList())
        assertTrue(plan.isEmpty)
        assertEquals(0, plan.unreadable + plan.notMedia + plan.overCap)
    }

    @Test
    fun `the sentences about what was left out are one short sentence each`() {
        val capped = planReceive((1..501).map(::image))
        assertEquals("Only the first 500 are kept, the other 1 were left out.", ReceiveCopy.leftOut(capped))
        assertNull(ReceiveCopy.leftOut(planReceive(listOf(image(1)))))
        assertEquals("1 shared file could not be used.", ReceiveCopy.leftOut(planReceive(listOf(image(1), ReceiveCandidate("content://s/x", readable = false)))))
    }
}
