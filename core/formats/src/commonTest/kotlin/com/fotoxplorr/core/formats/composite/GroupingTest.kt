package com.fotoxplorr.core.formats.composite

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LivePhotoPairingTest {
    private fun still(key: String, name: String, t: Long, folder: String = "DCIM/100APPLE", id: String? = null) =
        PairCandidate(key, folder, LivePhotoPairing.baseName(name), isMovie = false, takenMs = t, contentId = id)

    private fun movie(key: String, name: String, t: Long, folder: String = "DCIM/100APPLE", id: String? = null) =
        PairCandidate(key, folder, LivePhotoPairing.baseName(name), isMovie = true, takenMs = t, contentId = id)

    @Test
    fun `the content identifier pairs across names and folders`() {
        val pairs = LivePhotoPairing.pair(
            listOf(still("s", "IMG_0001.HEIC", 0, id = "A"), movie("m", "renamed.MOV", 99_000, folder = "Other", id = "A")),
        )
        assertEquals(mapOf("s" to "m"), pairs)
    }

    @Test
    fun `the same base name within two seconds pairs`() {
        assertEquals(
            mapOf("s" to "m"),
            LivePhotoPairing.pair(listOf(still("s", "IMG_0002.JPG", 1_000), movie("m", "img_0002.mov", 2_900))),
        )
    }

    @Test
    fun `the same base name too far apart in time or in another folder does not pair`() {
        assertTrue(LivePhotoPairing.pair(listOf(still("s", "IMG_3.JPG", 0), movie("m", "IMG_3.MOV", 2_001))).isEmpty())
        assertTrue(LivePhotoPairing.pair(listOf(still("s", "IMG_3.JPG", 0), movie("m", "IMG_3.MOV", 0, folder = "x"))).isEmpty())
    }

    @Test
    fun `different known identifiers never pair by name`() {
        assertTrue(
            LivePhotoPairing.pair(listOf(still("s", "IMG_4.HEIC", 0, id = "A"), movie("m", "IMG_4.MOV", 0, id = "B"))).isEmpty(),
        )
    }

    @Test
    fun `a movie pairs once with the nearest still`() {
        val pairs = LivePhotoPairing.pair(
            listOf(still("far", "IMG_5.JPG", 0), still("near", "IMG_5.HEIC", 1_500), movie("m", "IMG_5.MOV", 1_600)),
        )
        assertEquals(mapOf("near" to "m"), pairs)
    }

    @Test
    fun `QuickTime files are the movie half`() {
        assertTrue(LivePhotoPairing.isLiveMovie("video/quicktime", "x.bin"))
        assertTrue(LivePhotoPairing.isLiveMovie("video/mp4", "IMG_1.MOV"))
        assertFalse(LivePhotoPairing.isLiveMovie("video/mp4", "clip.mp4"))
    }
}

class BurstGroupingTest {
    private fun c(key: String, name: String, t: Long, folder: String = "DCIM/Camera", uuid: String? = null) =
        BurstCandidate(key, folder, name, t, uuid)

    @Test
    fun `frames sharing an Apple burst id stack whatever the time gap`() {
        val stacks = BurstGrouping.group(listOf(c("a", "IMG_1.HEIC", 0, uuid = "U"), c("b", "IMG_9.HEIC", 60_000, uuid = "u"), c("x", "IMG_2.HEIC", 10)))
        assertEquals(listOf(BurstStack("a", listOf("a", "b"))), stacks)
    }

    @Test
    fun `Google burst names stack and the COVER frame is the cover`() {
        val stacks = BurstGrouping.group(
            listOf(
                c("1", "00000IMG_00000_BURST20190101123456789.jpg", 100),
                c("2", "00001IMG_00001_BURST20190101123456789_COVER.jpg", 200),
                c("3", "00002IMG_00002_BURST20190101123456789.jpg", 300),
            ),
        )
        assertEquals(1, stacks.size)
        assertEquals("2", stacks[0].cover)
        assertEquals(listOf("1", "2", "3"), stacks[0].members)
    }

    @Test
    fun `Samsung numbered frames of one second stack and other seconds do not join`() {
        val stacks = BurstGrouping.group(
            listOf(
                c("a", "20230101_120000_001.jpg", 1), c("b", "20230101_120000_002.jpg", 2),
                c("z", "20230101_120001_001.jpg", 3),
            ),
        )
        assertEquals(listOf(BurstStack("a", listOf("a", "b"))), stacks)
    }

    @Test
    fun `burst names in different folders do not join`() {
        val stacks = BurstGrouping.group(
            listOf(c("a", "IMG_BURST001.jpg", 0, folder = "x"), c("b", "IMG_BURST002.jpg", 0, folder = "y")),
        )
        assertTrue(stacks.isEmpty())
    }

    @Test
    fun `a quick sequential run stacks by time`() {
        val stacks = BurstGrouping.group(
            listOf(c("1", "IMG_0041.JPG", 1_000), c("2", "IMG_0042.JPG", 1_300), c("3", "IMG_0043.JPG", 1_700), c("4", "IMG_0044.JPG", 9_000)),
        )
        assertEquals(listOf(BurstStack("1", listOf("1", "2", "3"))), stacks)
    }

    @Test
    fun `a time run needs three frames and sequential names`() {
        assertTrue(BurstGrouping.group(listOf(c("1", "IMG_1.JPG", 0), c("2", "IMG_2.JPG", 100))).isEmpty())
        assertTrue(BurstGrouping.group(listOf(c("1", "cat.jpg", 0), c("2", "dog.jpg", 100), c("3", "sun.jpg", 200))).isEmpty())
    }

    @Test
    fun `a copied folder with one timestamp is not one huge burst`() {
        val many = (1..150).map { c("$it", "IMG_$it.JPG", 5_000) }
        assertTrue(BurstGrouping.group(many).isEmpty())
    }

    @Test
    fun `files written in one second with no taken date never stack by time`() {
        val received = (1..8).map { BurstCandidate("$it", "DCIM/WhatsApp", "IMG-20231004-WA000$it.jpg", 1_696_400_000_000, takenIsCaptureTime = false) }
        assertTrue(BurstGrouping.group(received).isEmpty())
        // The same files with real capture times are a burst.
        assertEquals(1, BurstGrouping.group(received.map { it.copy(takenIsCaptureTime = true) }).size)
    }

    @Test
    fun `a time run needs frame numbers close together`() {
        val spread = listOf(c("1", "image1.png", 1_000), c("2", "image5.png", 1_100), c("3", "image9.png", 1_200))
        assertTrue(BurstGrouping.group(spread).isEmpty())
        val gap = listOf(c("1", "IMG_0041.JPG", 1_000), c("2", "IMG_0043.JPG", 1_100), c("3", "IMG_0044.JPG", 1_200))
        assertEquals(listOf(BurstStack("1", listOf("1", "2", "3"))), BurstGrouping.group(gap))
    }

    @Test
    fun `frames of one instant are ordered by number not by text`() {
        val frames = listOf(c("9", "IMG_9.JPG", 4_000), c("10", "IMG_10.JPG", 4_000), c("11", "IMG_11.JPG", 4_000))
        assertEquals(1, BurstGrouping.group(frames).size)
        assertTrue(BurstGrouping.sequential("IMG_9.JPG", "IMG_10.JPG"))
        assertFalse(BurstGrouping.sequential("IMG_10.JPG", "IMG_9.JPG"))
    }

    @Test
    fun `undated photos never stack by time`() {
        assertTrue(BurstGrouping.group((1..5).map { c("$it", "IMG_$it.JPG", 0) }).isEmpty())
    }

    @Test
    fun `names that are not burst names have no name key`() {
        assertNull(BurstGrouping.nameKey("IMG_0001.JPG"))
        assertNull(BurstGrouping.nameKey("20230101_120000.jpg"))
    }
}

class BurstStacksTest {
    private val stack = BurstStack("b", listOf("a", "b", "c"))
    private val items = listOf("x", "a", "b", "c", "y")

    @Test
    fun `a collapsed stack shows only its cover in place with the count`() {
        val view = BurstStacks.present(items, { it }, listOf(stack), expanded = emptySet())
        assertEquals(listOf("x", "b", "y"), view.visible)
        assertEquals(StackBadge("b", 3, expanded = false, members = listOf("a", "b", "c")), view.badges["b"])
    }

    @Test
    fun `an expanded stack shows every frame and keeps its badge`() {
        val view = BurstStacks.present(items, { it }, listOf(stack), expanded = setOf("b"))
        assertEquals(items, view.visible)
        assertTrue(view.badges.getValue("b").expanded)
    }

    @Test
    fun `a filtered away cover is represented by the first frame still shown`() {
        val view = BurstStacks.present(listOf("a", "c"), { it }, listOf(stack), expanded = emptySet())
        assertEquals(listOf("a"), view.visible)
        assertEquals(2, view.badges.getValue("a").count)
    }

    @Test
    fun `a stack reduced to one frame by a filter is no stack`() {
        val view = BurstStacks.present(listOf("x", "c"), { it }, listOf(stack), expanded = emptySet())
        assertEquals(listOf("x", "c"), view.visible)
        assertTrue(view.badges.isEmpty())
    }

    @Test
    fun `no stacks returns the very same list`() {
        val view = BurstStacks.present(items, { it }, emptyList<BurstStack<String>>(), emptySet())
        assertTrue(view.visible === items)
    }
}
