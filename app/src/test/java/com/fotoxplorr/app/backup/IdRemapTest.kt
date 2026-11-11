package com.fotoxplorr.app.backup

import com.fotoxplorr.app.editor.JsonValue
import com.fotoxplorr.app.editor.asArray
import com.fotoxplorr.app.editor.asLong
import com.fotoxplorr.app.editor.asObject
import com.fotoxplorr.app.editor.get
import com.fotoxplorr.app.editor.parseJson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class IdRemapTest {
    private fun json(text: String): JsonValue = checkNotNull(parseJson(text)) { "fixture is not JSON" }

    private fun ids(value: JsonValue): List<Long> = value.asArray().map { it.asLong(-1) }

    private val library = """
        {
          "schema": 1,
          "collections": [
            {"id": "c1", "name": "Holiday", "createdAtMillis": 1700000000000, "mediaIds": [1, 2, 3]},
            {"id": "c2", "name": "Empty after", "createdAtMillis": 5, "mediaIds": [9]}
          ],
          "tags": {"beach": [1, 2], "gone": [9], "dog": [3]},
          "archivedIds": [2, 9],
          "autoTags": {"beach": [2], "gone": [9]},
          "captions": {"1": "sunny", "3": "good dog", "9": "lost"},
          "machineCaptionIds": [3, 9],
          "everUnarchivedIds": [1],
          "rejectedArchiveSuggestionIds": [2],
          "rejectedAutoTags": {"cat": [1], "bird": [9]},
          "suppressedMachineCaptionIds": [3]
        }
    """.trimIndent()

    private val map = mapOf(1L to 101L, 2L to 102L, 3L to 103L)

    @Test
    fun `every place an id is stored goes through the map`() {
        val result = IdRemap.remapLibrary(json(library), map)
        val out = result.json
        assertEquals(1L, out["schema"].asLong())
        val holiday = out["collections"].asArray()[0]
        assertEquals("c1", holiday["id"].let { (it as JsonValue.JsonString).value })
        assertEquals(listOf(101L, 102L, 103L), ids(holiday["mediaIds"]))
        assertEquals(1700000000000L, holiday["createdAtMillis"].asLong())
        assertEquals(listOf(101L, 102L), ids(out["tags"]["beach"]))
        assertEquals(listOf(103L), ids(out["tags"]["dog"]))
        assertEquals(listOf(102L), ids(out["archivedIds"]))
        assertEquals(listOf(102L), ids(out["autoTags"]["beach"]))
        assertEquals(setOf("101", "103"), out["captions"].asObject().keys)
        assertEquals(listOf(103L), ids(out["machineCaptionIds"]))
        assertEquals(listOf(101L), ids(out["everUnarchivedIds"]))
        assertEquals(listOf(102L), ids(out["rejectedArchiveSuggestionIds"]))
        assertEquals(listOf(101L), ids(out["rejectedAutoTags"]["cat"]))
        assertEquals(listOf(103L), ids(out["suppressedMachineCaptionIds"]))
    }

    @Test
    fun `unmapped ids are dropped and counted once each`() {
        val result = IdRemap.remapLibrary(json(library), map)
        assertEquals(setOf(9L), result.droppedIds)
        assertEquals(1, result.droppedCount)
        assertEquals(0, result.malformedCount)
    }

    @Test
    fun `a tag with no photo left disappears with its auto marks but a collection stays`() {
        val out = IdRemap.remapLibrary(json(library), map).json
        assertTrue("gone" !in out["tags"].asObject())
        assertTrue("gone" !in out["autoTags"].asObject())
        assertTrue("bird" !in out["rejectedAutoTags"].asObject())
        val collections = out["collections"].asArray()
        assertEquals(2, collections.size)
        assertEquals(emptyList<Long>(), ids(collections[1]["mediaIds"]))
    }

    @Test
    fun `two old ids landing on one new id merge without duplicating`() {
        val merged = mapOf(1L to 50L, 2L to 50L, 3L to 51L)
        val out = IdRemap.remapLibrary(json(library), merged).json
        assertEquals(listOf(50L, 51L), ids(out["collections"].asArray()[0]["mediaIds"]))
        assertEquals(listOf(50L), ids(out["tags"]["beach"]))
        // captions: the lowest old id keeps the slot
        assertEquals("sunny", (out["captions"]["50"] as JsonValue.JsonString).value)
    }

    @Test
    fun `an empty map drops everything and keeps the shape`() {
        val result = IdRemap.remapLibrary(json(library), emptyMap())
        assertEquals(setOf(1L, 2L, 3L, 9L), result.droppedIds)
        assertTrue(result.json["tags"].asObject().isEmpty())
        assertTrue(result.json["captions"].asObject().isEmpty())
        assertEquals(2, result.json["collections"].asArray().size)
        assertEquals(1L, result.json["schema"].asLong())
    }

    @Test
    fun `unusable ids are counted as malformed, not mapped`() {
        val odd = json("""{"schema":1,"archivedIds":[1, -4, 2.5, "7", null, true],"captions":{"x":"no","-1":"neg","1":"ok"}}""")
        val result = IdRemap.remapLibrary(odd, map)
        assertEquals(listOf(101L), ids(result.json["archivedIds"]))
        assertEquals(5 + 2, result.malformedCount)
        assertEquals(setOf("101"), result.json["captions"].asObject().keys)
    }

    @Test
    fun `keys the remap does not know are copied untouched`() {
        val result = IdRemap.remapLibrary(json("""{"schema":1,"futureThing":[1,2,3],"archivedIds":[1]}"""), map)
        assertEquals(listOf(1L, 2L, 3L), ids(result.json["futureThing"]))
        assertEquals(listOf(101L), ids(result.json["archivedIds"]))
    }

    @Test
    fun `a whole metadata file remaps the library and both id lists`() {
        val file = json("""{"schema":1,"exportedAtMillis":99,"library":$library,"favoriteIds":[1,9,3],"sensitiveIds":[2,2,9],"identity":{"1":["a"]}}""")
        val result = IdRemap.remapEnvelope(file, map)
        assertEquals(listOf(101L, 103L), ids(result.json["favoriteIds"]))
        assertEquals(listOf(102L), ids(result.json["sensitiveIds"]))
        assertEquals(listOf(101L, 102L), ids(result.json["library"]["tags"]["beach"]))
        assertEquals(99L, result.json["exportedAtMillis"].asLong())
        assertNotNull(result.json["identity"].asObject()["1"])
        assertEquals(setOf(9L), result.droppedIds)
    }

    @Test
    fun `the older shape with the library at the top level is understood`() {
        val flat = json("""{"schema":1,"tags":{"a":[1,9]},"favoriteIds":[2]}""")
        val result = IdRemap.remapEnvelope(flat, map)
        assertEquals(listOf(101L), ids(result.json["tags"]["a"]))
        assertEquals(listOf(102L), ids(result.json["favoriteIds"]))
    }

    @Test
    fun `the remap does not change its input`() {
        val input = json(library)
        val before = input.toString()
        IdRemap.remapLibrary(input, map)
        assertEquals(before, input.toString())
    }

    @Test
    fun `a large library remaps in one pass`() {
        val n = 200_000
        val tags = (0 until n).joinToString(",")
        val input = json("""{"schema":1,"tags":{"all":[$tags]}}""")
        val result = IdRemap.remapLibrary(input, (0 until n).associate { it.toLong() to it + 1_000_000L })
        assertEquals(n, result.json["tags"]["all"].asArray().size)
        assertEquals(0, result.droppedCount)
    }

    // ---- identity matching ----

    private fun id(name: String, size: Long = 100, taken: Long = 1000, path: String? = "DCIM/Camera/") = MediaIdentity(name, size, taken, path)

    @Test
    fun `the same id with the same identity keeps its id`() {
        val match = IdRemap.matchIdentities(mapOf(5L to id("a.jpg")), mapOf(5L to id("a.jpg"), 6L to id("b.jpg")))
        assertEquals(mapOf(5L to 5L), match.oldToNew)
        assertTrue(match.unmatched.isEmpty())
    }

    @Test
    fun `the same id on a different photo is never trusted`() {
        // The wrong-photo hazard: id 5 is now somebody else's picture.
        val match = IdRemap.matchIdentities(mapOf(5L to id("a.jpg")), mapOf(5L to id("different.jpg"), 8L to id("a.jpg")))
        assertEquals(mapOf(5L to 8L), match.oldToNew)
    }

    @Test
    fun `a photo that is no longer there matches nothing`() {
        val match = IdRemap.matchIdentities(mapOf(5L to id("a.jpg")), mapOf(5L to id("different.jpg")))
        assertTrue(match.oldToNew.isEmpty())
        assertEquals(setOf(5L), match.unmatched)
    }

    @Test
    fun `same name and size with another date taken is not the same photo`() {
        val match = IdRemap.matchIdentities(mapOf(1L to id("a.jpg", taken = 1000)), mapOf(2L to id("a.jpg", taken = 2000)))
        assertEquals(setOf(1L), match.unmatched)
    }

    @Test
    fun `an unknown date taken agrees with anything`() {
        val match = IdRemap.matchIdentities(mapOf(1L to id("a.jpg", taken = 0)), mapOf(2L to id("a.jpg", taken = 2000)))
        assertEquals(mapOf(1L to 2L), match.oldToNew)
    }

    @Test
    fun `ambiguity is resolved by date then folder and otherwise refused`() {
        val catalogue = mapOf(
            10L to id("a.jpg", taken = 1, path = "DCIM/A/"),
            11L to id("a.jpg", taken = 2, path = "DCIM/A/"),
            12L to id("a.jpg", taken = 2, path = "DCIM/B/"),
        )
        assertEquals(10L, IdRemap.matchIdentities(mapOf(1L to id("a.jpg", taken = 1)), catalogue).oldToNew[1L])
        assertEquals(12L, IdRemap.matchIdentities(mapOf(1L to id("a.jpg", taken = 2, path = "DCIM/B/")), catalogue).oldToNew[1L])
        assertEquals(setOf(1L), IdRemap.matchIdentities(mapOf(1L to id("a.jpg", taken = 2, path = "DCIM/C/")), catalogue).unmatched)
        assertEquals(setOf(1L), IdRemap.matchIdentities(mapOf(1L to id("a.jpg", taken = 2, path = null)), catalogue).unmatched)
    }

    @Test
    fun `a phone photo is claimed by one backup id only and same-id matches win over guesses`() {
        val catalogue = mapOf(3L to id("a.jpg"), 4L to id("b.jpg"))
        val wanted = mapOf(1L to id("a.jpg"), 3L to id("a.jpg"))
        val match = IdRemap.matchIdentities(wanted, catalogue)
        // 3 is a same-id match for old 3; old 1 may not also take it.
        assertEquals(mapOf(3L to 3L), match.oldToNew)
        assertEquals(setOf(1L), match.unmatched)
    }

    @Test
    fun `a restored library is matched to the new ids`() {
        val wanted = (1L..500L).associateWith { id("IMG_$it.jpg", size = 1000 + it, taken = 10_000 + it) }
        val catalogue = (1L..500L).associate { (it + 7000) to id("IMG_$it.jpg", size = 1000 + it, taken = 10_000 + it) }
        val match = IdRemap.matchIdentities(wanted, catalogue)
        assertEquals(500, match.oldToNew.size)
        assertTrue(match.oldToNew.all { (old, new) -> new == old + 7000 })
    }
}
