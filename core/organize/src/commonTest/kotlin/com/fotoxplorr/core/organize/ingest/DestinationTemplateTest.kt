package com.fotoxplorr.core.organize.ingest

import kotlinx.datetime.TimeZone
import kotlinx.datetime.UtcOffset
import kotlinx.datetime.asTimeZone
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class DestinationTemplateTest {
    private val t0 = 1_700_000_000_000L // 2023-11-14T22:13:20Z

    private fun ctx(camera: String = "Canon EOS R5", seq: Int = 7, zone: TimeZone = TimeZone.UTC, at: Long = t0, orig: String = "IMG_0001") =
        TemplateContext(at, zone, camera, seq, orig, "JPG")

    private fun expand(t: String, c: TemplateContext = ctx()) = DestinationTemplate.parseOrThrow(t).expandPath(c)
    private fun error(t: String) = assertIs<TemplateParse.Error>(DestinationTemplate.parse(t))

    @Test fun yearAndDayFolders() = assertEquals("2023/2023-11-14", expand("{yyyy}/{yyyy-MM-dd}"))

    @Test fun fixedZoneDecidesTheDay() {
        val la = UtcOffset(hours = -8).asTimeZone()
        assertEquals("2023-11-14", expand("{yyyy-MM-dd}", ctx(zone = la, at = 1_700_000_000_000L)))
        val plus = UtcOffset(hours = 5).asTimeZone()
        assertEquals("2023-11-15", expand("{yyyy-MM-dd}", ctx(zone = plus)))
    }

    @Test fun allTokens() {
        assertEquals("Canon EOS R5/0007/IMG_0001/jpg/23-11-14 22.13.20", expand("{camera}/{seq}/{orig}/{ext}/{yy-MM-dd HH.mm.ss}"))
        assertEquals("7", expand("{seq:1}"))
        assertEquals("000000007", expand("{seq:9}"))
    }

    @Test fun emptyTemplateIsFlat() = assertEquals("", expand(""))

    @Test fun trailingSlashIgnored() = assertEquals("2023", expand("{yyyy}/"))

    @Test fun escapedBraces() = assertEquals("{x}", expand("{{x}}"))

    @Test fun unknownTokenIsAnError() {
        error("{year}")
        error("{yyyy-QQ}")
        error("{}")
        error("{yyy}")
        error("{seq:0}")
        error("{seq:10}")
        error("{seq:x}")
        error("{ -}")
    }

    @Test fun braceMisuseIsAnError() {
        error("{yyyy")
        error("yyyy}")
        error("{{yyyy}")
        error("a{b{c}}")
    }

    @Test fun traversalAndShapeErrors() {
        error("..")
        error("a/../b")
        error("./a")
        error("/abs")
        error("a//b")
        error("a\\b")
        error("a:b")
        error("a|b")
        error("a*b")
        error("a\u0000b")
        error("/")
    }

    @Test fun hostileCameraStaysOneSafeFolder() {
        val r = expand("{camera}", ctx(camera = "../../etc/passwd"))
        assertTrue('/' !in r && !r.startsWith("."), r)
        assertEquals("passwd", r.takeLast(6))
        assertEquals("_", NameSanitizer.segment("."))
        assertTrue(NameSanitizer.segment("..").none { it == '/' } && !NameSanitizer.segment("..").startsWith("."))
    }

    @Test fun deviceNamesAreDefused() {
        assertEquals("_CON", expand("{camera}", ctx(camera = "CON")))
        assertEquals("_nul.txt", NameSanitizer.segment("nul.txt"))
        assertEquals("COM10", NameSanitizer.segment("COM10"))
    }

    @Test fun bidiAndZeroWidthDropped() =
        assertEquals("ab", NameSanitizer.segment("a‮​b"))

    @Test fun blankCameraGetsAFallback() = assertEquals("Unknown camera", expand("{camera}", ctx(camera = "   ")))

    @Test fun trailingDotsAndSpacesTrimmed() = assertEquals("x", NameSanitizer.segment("x. . "))

    @Test fun longNamesAreCutOnByteLimitWithoutSplittingEmoji() {
        val long = "📷".repeat(100) // 4 bytes each = 400 bytes
        val s = NameSanitizer.segment(long)
        assertTrue(NameSanitizer.utf8Length(s) <= NameSanitizer.MAX_BYTES)
        assertEquals(50, s.length / 2)
        assertTrue(s.chunked(2).all { it[0].isHighSurrogate() && it[1].isLowSurrogate() })
        val ascii = NameSanitizer.segment("a".repeat(500))
        assertEquals(NameSanitizer.MAX_BYTES, ascii.length)
    }

    @Test fun fileNameKeepsExtensionWhenStemIsCut() {
        val n = NameSanitizer.fileName("x".repeat(400), "JPG")
        assertTrue(n.endsWith(".JPG"))
        assertTrue(NameSanitizer.utf8Length(n) <= NameSanitizer.MAX_BYTES)
    }

    @Test fun unicodeNamesSurvive() {
        assertEquals("Fotoapparat Ünïcödé 写真", expand("{camera}", ctx(camera = "Fotoapparat Ünïcödé 写真")))
    }

    @Test fun renamePatternRuleUsesSameGrammar() {
        val rule = PatternRenameRule.create("{yyyy-MM-dd}_{seq}").getOrThrow()
        assertEquals("2023-11-14_0007", rule.newStem(RenameInput("IMG_1", "jpg", t0, "x", 7, TimeZone.UTC)))
        assertTrue(PatternRenameRule.create("a/b").isFailure)
        assertTrue(PatternRenameRule.create("{nope}").isFailure)
        assertTrue(PatternRenameRule.create("").isFailure)
        assertEquals("IMG_9", IdentityRenameRule.newStem(RenameInput("IMG_9", "jpg", 0, "", 1, TimeZone.UTC)))
    }
}
