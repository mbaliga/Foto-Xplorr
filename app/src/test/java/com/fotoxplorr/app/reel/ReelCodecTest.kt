package com.fotoxplorr.app.reel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/** The saved text of a project: exact round trip, and tolerance of everything an old or damaged file can hold. */
class ReelCodecTest {

    private fun full() = ReelProject(
        name = "June trip \"north\"\nday 2",
        aspect = ReelAspect.TALL,
        soundtrack = ReelSoundtrack("content://com.android.providers.media.documents/document/audio%3A9", 12_000L, 3_000L),
        items = listOf(
            TitleItem("r1", "12 June 2026\nLisbon", 0xFF1B2330.toInt(), 2_500L, ReelTransition.CROSS_FADE),
            StillItem("r2", 101L, 3_000L, ReelMotion.None, ReelTransition.CUT),
            StillItem("r3", 102L, 3_250L, ReelMotion.ZoomIn(NormPoint(0.3f, 0.7f)), ReelTransition.SLIDE),
            StillItem("r4", 103L, 2_500L, ReelMotion.ZoomOut(NormPoint(0.1f, 0.9f))),
            StillItem("r5", 104L, 3_500L, ReelMotion.Pan(NormRect(0f, 0f, 0.5625f, 1f), NormRect(0.4375f, 0f, 1f, 1f))),
            ClipItem("r6", 105L, 1_250L, 4_750L, 20_000L, ReelTransition.CROSS_FADE),
            StillItem("r7", 106L, 3_000L, ReelMotion.ZoomIn(NormPoint(0.123456789f, 1f / 3f))),
        ),
    )

    @Test
    fun `a project round trips exactly`() {
        val p = full()
        val back = ReelCodec.decode(ReelCodec.encode(p))!!
        assertEquals(p, back.project)
        assertEquals(0, back.skippedItems)
        assertFalse(back.fromNewerVersion)
    }

    @Test
    fun `an empty project round trips`() {
        val p = ReelProject()
        assertEquals(p, ReelCodec.decode(ReelCodec.encode(p))!!.project)
    }

    @Test
    fun `a negative card colour round trips`() {
        val p = ReelProject(listOf(TitleItem("r1", "x", 0xFF000000.toInt())))
        assertEquals(p, ReelCodec.decode(ReelCodec.encode(p))!!.project)
    }

    @Test
    fun `the text is plain json with a version`() {
        val text = ReelCodec.encode(full())
        assertTrue(text.startsWith("{\"version\":${ReelCodec.CURRENT_VERSION}"))
        assertTrue(text.contains("\"type\":\"still\""))
        assertTrue(text.contains("\"color\":\"#FF1B2330\""))
    }

    @Test
    fun `unknown keys at every level are ignored`() {
        val text = """
            {"version":1,"future":{"a":[1,2]},"aspect":"1:1","items":[
              {"type":"still","id":"r1","media":7,"ms":3000,"sparkle":true,"motion":{"kind":"none","speed":3},"next":"cut","x":1},
              {"type":"clip","id":"r2","media":8,"in":0,"out":2000,"source":5000,"volume":0.2}
            ],"soundtrack":{"uri":"content://a/b","trimStart":5,"fadeOut":100,"loop":true}}
        """.trimIndent()
        val d = ReelCodec.decode(text)!!
        assertEquals(0, d.skippedItems)
        assertEquals(ReelAspect.SQUARE, d.project.aspect)
        assertEquals(StillItem("r1", 7L, 3_000L, ReelMotion.None, ReelTransition.CUT), d.project.items[0])
        assertEquals(ClipItem("r2", 8L, 0L, 2_000L, 5_000L), d.project.items[1])
        assertEquals(ReelSoundtrack("content://a/b", 5L, 100L), d.project.soundtrack)
    }

    @Test
    fun `an item of an unknown kind or with no picture is skipped and counted`() {
        val text = """
            {"version":1,"items":[
              {"type":"hologram","id":"r1"},
              {"type":"still","id":"r2"},
              {"type":"still","id":"r3","media":0},
              "nonsense",
              {"type":"still","id":"r4","media":5}
            ]}
        """.trimIndent()
        val d = ReelCodec.decode(text)!!
        assertEquals(4, d.skippedItems)
        assertEquals(listOf("r4"), d.project.items.map { it.id })
    }

    @Test
    fun `a newer version is read and flagged`() {
        val d = ReelCodec.decode("""{"version":9,"items":[{"type":"still","id":"r1","media":3}],"newField":1}""")!!
        assertTrue(d.fromNewerVersion)
        assertEquals(1, d.project.items.size)
    }

    @Test
    fun `a missing or odd version reads as the first`() {
        assertFalse(ReelCodec.decode("""{"items":[]}""")!!.fromNewerVersion)
        assertFalse(ReelCodec.decode("""{"version":"two","items":[]}""")!!.fromNewerVersion)
        assertFalse(ReelCodec.decode("""{"version":-4,"items":[]}""")!!.fromNewerVersion)
    }

    @Test
    fun `text that is not a json object gives null and never throws`() {
        for (bad in listOf("", "   ", "[]", "null", "42", "\"x\"", "{", "{\"items\":", "not json", "{\"items\":[}")) {
            assertNull(bad, ReelCodec.decode(bad))
        }
    }

    @Test
    fun `an object with nothing known is an empty project`() {
        val d = ReelCodec.decode("{}")!!
        assertEquals(ReelProject(), d.project)
    }

    @Test
    fun `unknown names take their defaults`() {
        val d = ReelCodec.decode(
            """{"aspect":"21:9","items":[{"type":"still","id":"r1","media":4,"next":"wipe","motion":{"kind":"spin"}}]}""",
        )!!
        assertEquals(ReelAspect.WIDE, d.project.aspect)
        val s = d.project.items[0] as StillItem
        assertEquals(ReelTransition.CUT, s.transition)
        assertEquals(ReelMotion.None, s.motion)
        assertEquals(ReelLimits.STILL_DEFAULT_MS, s.durationMs)
    }

    @Test
    fun `numbers are clamped into their ranges`() {
        val d = ReelCodec.decode(
            """{"items":[
              {"type":"still","id":"r1","media":4,"ms":-50,"motion":{"kind":"zoomIn","x":7,"y":-2}},
              {"type":"still","id":"r2","media":5,"ms":99999999999},
              {"type":"clip","id":"r3","media":6,"in":-9,"out":-20},
              {"type":"title","id":"r4","text":"x","ms":1,"color":"#12345"}
            ],"soundtrack":{"uri":"content://a","trimStart":-5,"fadeOut":9999999}}""",
        )!!
        val items = d.project.items
        assertEquals(ReelLimits.MIN_ITEM_MS, items[0].durationMs)
        assertEquals(ReelMotion.ZoomIn(NormPoint(1f, 0f)), (items[0] as StillItem).motion)
        assertEquals(ReelLimits.ITEM_MAX_MS, items[1].durationMs)
        val clip = items[2] as ClipItem
        assertEquals(0L, clip.inMs)
        assertEquals(ReelLimits.MIN_ITEM_MS, clip.outMs)
        assertEquals(ReelLimits.TITLE_DEFAULT_ARGB, (items[3] as TitleItem).backgroundArgb)
        assertEquals(ReelLimits.MIN_ITEM_MS, items[3].durationMs)
        assertEquals(ReelSoundtrack("content://a", 0L, ReelLimits.FADE_OUT_MAX_MS), d.project.soundtrack)
    }

    @Test
    fun `a decoded project has no blocking plan issue unless the file said so on purpose`() {
        val d = ReelCodec.decode(
            """{"items":[{"type":"clip","id":"r1","media":4,"in":-9,"out":-20},{"type":"still","id":"r2","media":5,"ms":-1}]}""",
        )!!
        assertTrue(ReelPlan.canRender(d.project))
    }

    @Test
    fun `a six digit colour is opaque and a bad rectangle becomes no motion`() {
        val d = ReelCodec.decode(
            """{"items":[
              {"type":"title","id":"r1","text":"a","color":"#102030"},
              {"type":"still","id":"r2","media":2,"motion":{"kind":"pan","from":[0,0,0.01,1],"to":[0,0,1,1]}},
              {"type":"still","id":"r3","media":3,"motion":{"kind":"pan","from":[0,0],"to":"x"}},
              {"type":"still","id":"r4","media":4,"motion":{"kind":"pan","from":[-3,0,2,1],"to":[0,0,1,1]}}
            ]}""",
        )!!
        assertEquals(0xFF102030.toInt(), (d.project.items[0] as TitleItem).backgroundArgb)
        assertEquals(ReelMotion.None, (d.project.items[1] as StillItem).motion)
        assertEquals(ReelMotion.None, (d.project.items[2] as StillItem).motion)
        assertEquals(ReelMotion.Pan(NormRect.FULL, NormRect.FULL), (d.project.items[3] as StillItem).motion)
    }

    @Test
    fun `missing or repeated ids are replaced by fresh unique ones`() {
        val d = ReelCodec.decode(
            """{"items":[
              {"type":"still","media":1},
              {"type":"still","id":"r1","media":2},
              {"type":"still","id":"r1","media":3},
              {"type":"still","id":"","media":4}
            ]}""",
        )!!
        val ids = d.project.items.map { it.id }
        assertEquals(4, ids.toSet().size)
        assertTrue(ids.all { it.isNotEmpty() })
    }

    @Test
    fun `a soundtrack with no uri is none`() {
        assertNull(ReelCodec.decode("""{"soundtrack":{"trimStart":5}}""")!!.project.soundtrack)
        assertNull(ReelCodec.decode("""{"soundtrack":null}""")!!.project.soundtrack)
        assertNull(ReelCodec.decode("""{"soundtrack":"x"}""")!!.project.soundtrack)
    }

    @Test
    fun `more items than the limit are dropped and counted`() {
        val items = (1..ReelLimits.MAX_ITEMS + 5).joinToString(",") { """{"type":"still","id":"r$it","media":$it}""" }
        val d = ReelCodec.decode("""{"items":[$items]}""")!!
        assertEquals(ReelLimits.MAX_ITEMS, d.project.items.size)
        assertEquals(5, d.skippedItems)
    }

    @Test
    fun `an over long name or card text is cut`() {
        val long = "x".repeat(5_000)
        val d = ReelCodec.decode("""{"name":"$long","items":[{"type":"title","id":"r1","text":"$long"}]}""")!!
        assertEquals(ReelLimits.TEXT_MAX_CHARS, d.project.name.length)
        assertEquals(ReelLimits.TEXT_MAX_CHARS, (d.project.items[0] as TitleItem).text.length)
    }

    @Test
    fun `a non finite number is written as zero so the text stays readable`() {
        val p = ReelProject(listOf(StillItem("r1", 1L, motion = ReelMotion.ZoomIn(NormPoint(Float.NaN, Float.POSITIVE_INFINITY)))))
        val back = ReelCodec.decode(ReelCodec.encode(p))
        assertNotNull(back)
        assertEquals(ReelMotion.ZoomIn(NormPoint(0f, 0f)), (back!!.project.items[0] as StillItem).motion)
    }

    @Test
    fun `damaged copies of a real file never throw`() {
        val text = ReelCodec.encode(full())
        val rnd = Random(11)
        repeat(500) {
            val chars = text.toCharArray()
            repeat(rnd.nextInt(1, 6)) { chars[rnd.nextInt(chars.size)] = "{}[]\",:x0-9 ".random(rnd) }
            val cut = if (rnd.nextBoolean()) String(chars) else String(chars).take(rnd.nextInt(chars.size))
            ReelCodec.decode(cut) // null or a project, never an exception
        }
    }
}
