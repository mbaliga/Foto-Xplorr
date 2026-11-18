package com.fotoxplorr.ios

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FotozCoreTest {
    private val today = "2026-11-17"
    private val tz = "Europe/London"

    private fun obj(s: String): JsonObject = Json.parseToJsonElement(s).jsonObject
    private fun ids(s: String): List<String> = obj(s)["ids"]!!.jsonArray.map { it.jsonPrimitive.content }

    private val docs = """
        [
          {"id":"A","name":"IMG_0001.HEIC","folder":"Holiday","mimeType":"image/heic","takenAtMillis":1754000000000,"width":4032,"height":3024,"sizeBytes":3000000},
          {"id":"B","name":"IMG_0002.PNG","folder":"Screenshots","mimeType":"image/png","takenAtMillis":1700000000000,"categories":["screenshot"],"width":1170,"height":2532,"sizeBytes":400000},
          {"id":"C","name":"clip.MOV","folder":"Holiday","mimeType":"video/quicktime","takenAtMillis":1754100000000,"width":1920,"height":1080,"sizeBytes":90000000}
        ]
    """.trimIndent()

    @Test fun apiVersionIsStable() = assertEquals(1, FotozCore.apiVersion())

    @Test fun parseQueryReturnsChipsAndDropsStopwords() {
        val r = obj(FotozCore.parseQuery("pictures of flowers in August 2025", tz, today))
        assertTrue(r["ok"]!!.jsonPrimitive.boolean)
        val labels = r["terms"]!!.jsonArray.map { it.jsonObject["label"]!!.jsonPrimitive.content }
        assertEquals(listOf("flowers", "August 2025"), labels)
        assertEquals("date", r["terms"]!!.jsonArray[1].jsonObject["kind"]!!.jsonPrimitive.content)
    }

    @Test fun stopwordOnlyQueryShowsEverything() {
        val r = obj(FotozCore.parseQuery("pictures of the", tz, today))
        assertTrue(r["showsEverything"]!!.jsonPrimitive.boolean)
    }

    @Test fun searchFiltersByFolderTypeAndSize() {
        assertEquals(listOf("A", "C"), ids(FotozCore.search("folder:holiday", tz, today, docs)))
        assertEquals(listOf("C"), ids(FotozCore.search("folder:holiday type:video", tz, today, docs)))
        assertEquals(listOf("C"), ids(FotozCore.search("size:>10mb", tz, today, docs)))
        assertEquals(listOf("A", "C"), ids(FotozCore.search("not category:screenshot", tz, today, docs)))
        assertEquals(listOf("B"), ids(FotozCore.search("width:<2000 type:png", tz, today, docs)))
    }

    @Test fun searchEmptyQueryReturnsEverythingInOrder() {
        val r = FotozCore.search("", tz, today, docs)
        assertEquals(listOf("A", "B", "C"), ids(r))
        assertTrue(obj(r)["showsEverything"]!!.jsonPrimitive.boolean)
    }

    @Test fun searchDateWindowIsZoneAwareAndUsesSuppliedToday() {
        // A = 2025-07-31T22:13:20Z, C = 2025-08-02T02:00:00Z. The same "August 2025" query splits
        // differently by zone: London (BST, +1) puts A at 23:13 on 31 July; Auckland (+12) puts it on 1 Aug.
        assertEquals(listOf("C"), ids(FotozCore.search("August 2025", "Europe/London", today, docs)))
        assertEquals(listOf("A", "C"), ids(FotozCore.search("August 2025", "Pacific/Auckland", today, docs)))
        assertEquals(emptyList(), ids(FotozCore.search("March 2024", tz, today, docs)))
    }

    @Test fun unknownTimeZoneFallsBackToUtcInsteadOfThrowing() {
        assertTrue(obj(FotozCore.parseQuery("flowers", "Not/AZone", today))["ok"]!!.jsonPrimitive.boolean)
    }

    @Test fun classifyKnownAndUnknown() {
        val heic = obj(FotozCore.classify("image/heic", "IMG_1.HEIC"))
        assertEquals("Heif", heic["format"]!!.jsonPrimitive.content)
        assertTrue("Decode" in heic["capabilities"]!!.jsonArray.map { it.jsonPrimitive.content })
        val raw = obj(FotozCore.classify("image/x-adobe-dng", "a.dng"))
        assertEquals("Raw", raw["format"]!!.jsonPrimitive.content)
        // RAW is never written into (MASTER-PLAN section 4).
        assertFalse("Write" in raw["capabilities"]!!.jsonArray.map { it.jsonPrimitive.content })
        assertEquals("Other", obj(FotozCore.classify("application/x-weird", "x.zzz"))["format"]!!.jsonPrimitive.content)
    }

    @Test fun planRenameIsCollisionFree() {
        val subjects = """[{"name":"IMG_1.HEIC","takenMillis":1754000000000},{"name":"IMG_2.HEIC","takenMillis":1754000000000}]"""
        val r = obj(FotozCore.planRename("Holiday_{counter:3}", subjects, 1, """["Holiday_001.HEIC"]""", "UTC"))
        val names = r["names"]!!.jsonArray.map { it.jsonPrimitive.content }
        assertEquals(2, names.size)
        assertEquals(names.size, names.toSet().size)
        assertFalse("Holiday_001.HEIC" in names, "must not steal an existing name")
    }

    @Test fun badInputIsAnErrorEnvelopeNotAnException() {
        val blank = obj(FotozCore.planRename("  ", "[]", 1, "[]", "UTC"))
        assertFalse(blank["ok"]!!.jsonPrimitive.boolean)
        assertTrue(blank["error"]!!.jsonPrimitive.content.isNotEmpty())
        assertFalse(obj(FotozCore.search("x", tz, today, "not json"))["ok"]!!.jsonPrimitive.boolean)
        assertFalse(obj(FotozCore.parseQuery("x", tz, "not-a-date"))["ok"]!!.jsonPrimitive.boolean)
    }
}
