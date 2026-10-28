package com.fotoxplorr.app.vision

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The viewer's words: short single sentences, no network talk, and the right sentence for each state. */
class ColourVisionCopyTest {

    /** Every user-facing sentence of the viewer and the check, found by name so a new one is covered by adding it here. */
    private val sentences = listOf(
        VisionCopy.NOT_FOR_VIDEO, VisionCopy.FILTER_ONLY, VisionCopy.PATTERNS_NEED_TYPE, VisionCopy.SAVE_CAPTION,
        VisionCopy.SAVE_NEED_TYPE, VisionCopy.SAVE_SENSITIVE, VisionCopy.SAVE_NO_JOBS, VisionCopy.WORKING, VisionCopy.SAVING,
        VisionCopy.COPY_SAVED, VisionCopy.COPY_SAVED_SMALLER, VisionCopy.COPY_CANCELLED, VisionCopy.COPY_PROTECTED,
        VisionCopy.COPY_CANNOT_READ, VisionCopy.COPY_NO_SPACE, VisionCopy.COPY_OUT_OF_MEMORY, VisionCopy.COPY_FAILED,
        VisionCopy.DALTONIZE_NOTE, VisionCopy.NAME_A_COLOUR, VisionCopy.PATTERNS, VisionCopy.NO_CONFUSION,
        VisionCopy.CHECK_BLURB, VisionCopy.CHECK_INTRO, VisionCopy.CHECK_SETUP, VisionCopy.CHECK_WORKING,
        VisionCopy.PLATE_DESCRIPTION, VisionCopy.PLATE_DISCLAIMER, VisionCopy.PLATE_QUESTION, VisionCopy.PLATE_CHECK_SCREEN,
        VisionCopy.RESULT_TYPICAL, VisionCopy.RESULT_RED_GREEN, VisionCopy.RESULT_BLUE_YELLOW, VisionCopy.RESULT_MIXED,
        VisionCopy.RESULT_DOCTOR, VisionCopy.RESULT_VIEWER, VisionCopy.BLURB, VisionCopy.UNREADABLE,
        VisionCopy.mainColours(listOf("grey", "sand", "dark green")), VisionCopy.RESULT_PASS_NOTE,
    )

    @Test
    fun `every sentence is one short sentence`() {
        for (s in sentences) {
            assertTrue("\"$s\" ends with a full stop or a question mark", s.endsWith(".") || s.endsWith("?"))
            assertEquals("\"$s\" has one sentence", 1, s.count { it == '.' || it == '?' })
            assertTrue("\"$s\" is short", s.split(' ').size <= 16)
            assertFalse("\"$s\" shouts", s.contains('!'))
        }
    }

    @Test
    fun `no sentence talks about the network`() {
        val everything = sentences + VisionCopy.badge(Deficiency.PROTAN, VisionKind.HELP) + VisionCopy.JOB_TITLE
        for (s in everything) {
            val lower = s.lowercase()
            for (word in listOf("network", "online", "internet", "upload", "server", "cloud", "wifi", "wi-fi")) {
                assertFalse("\"$word\" in \"$s\"", lower.contains(word))
            }
        }
    }

    @Test
    fun `the chip says the type and whether it is as seen or adjusted`() {
        assertEquals("Hard to see red, as seen", VisionCopy.badge(Deficiency.PROTAN, VisionKind.SIMULATE))
        assertEquals("Hard to see green, adjusted", VisionCopy.badge(Deficiency.DEUTAN, VisionKind.HELP))
        assertEquals("Hard to see blue, adjusted", VisionCopy.badge(Deficiency.TRITAN, VisionKind.HELP))
    }

    @Test
    fun `a rough view says so on the chip, for either kind, and never claims to be as seen`() {
        assertEquals("Hard to see red, rough view", VisionCopy.badge(Deficiency.PROTAN, VisionKind.SIMULATE, rough = true))
        assertEquals("Hard to see green, rough view", VisionCopy.badge(Deficiency.DEUTAN, VisionKind.HELP, rough = true))
        assertFalse(VisionCopy.badge(Deficiency.TRITAN, VisionKind.SIMULATE, rough = true).contains("as seen"))
    }

    @Test
    fun `the main colours read as one sentence with every name in it`() {
        assertEquals("Main colours: grey, sand, dark green.", VisionCopy.mainColours(listOf("grey", "sand", "dark green")))
        assertEquals("Main colours: grey.", VisionCopy.mainColours(listOf("grey")))
    }

    @Test
    fun `a colour is spoken as its name`() {
        assertEquals("Colour: Seems dusty pink", VisionCopy.nameDescription("Seems dusty pink"))
    }

    @Test
    fun `progress counts from one`() {
        assertEquals("Picture 1 of 7.", VisionCopy.plateProgress(1, 7))
        assertEquals("The number 4", VisionCopy.digitDescription(4))
    }

    // ---- states that pick a sentence ----

    private val on = ColourVisionChoice(Deficiency.DEUTAN)

    @Test
    fun `saving needs a type first`() {
        assertEquals(SaveBlock.NEEDS_TYPE, saveBlock(ColourVisionChoice(), VisionSupport.FULL, sensitive = false, canSave = true, hasRunner = true))
    }

    @Test
    fun `saving is open for a library photo with a filter on and a job list`() {
        assertNull(saveBlock(on, VisionSupport.FULL, sensitive = false, canSave = true, hasRunner = true))
    }

    @Test
    fun `saving is not offered for a file whose pixels cannot be read or one the library does not own`() {
        assertEquals(SaveBlock.NOT_PHOTO_FILE, saveBlock(on, VisionSupport.VIEW_ONLY, sensitive = false, canSave = true, hasRunner = true))
        assertEquals(SaveBlock.NOT_PHOTO_FILE, saveBlock(on, VisionSupport.FULL, sensitive = false, canSave = false, hasRunner = true))
        assertEquals(SaveBlock.NOT_PHOTO_FILE, saveBlock(on, VisionSupport.NONE, sensitive = false, canSave = true, hasRunner = true))
    }

    @Test
    fun `a sensitive photo is never copied`() {
        assertEquals(SaveBlock.SENSITIVE, saveBlock(on, VisionSupport.FULL, sensitive = true, canSave = true, hasRunner = true))
    }

    @Test
    fun `with no job list there is nowhere to run it`() {
        assertEquals(SaveBlock.NO_RUNNER, saveBlock(on, VisionSupport.FULL, sensitive = false, canSave = true, hasRunner = false))
    }

    @Test
    fun `what a file supports follows what can be decoded as one still picture`() {
        val photo = ColourVisionKit.asset()
        assertEquals(VisionSupport.FULL, visionSupport(photo, animated = false))
        assertEquals(VisionSupport.VIEW_ONLY, visionSupport(photo, animated = true))
        assertEquals(VisionSupport.VIEW_ONLY, visionSupport(ColourVisionKit.asset(mime = "image/svg+xml", name = "a.svg"), animated = false))
        assertEquals(VisionSupport.VIEW_ONLY, visionSupport(ColourVisionKit.asset(mime = "", name = "IMG_1.NEF"), animated = false))
        assertEquals(VisionSupport.NONE, visionSupport(ColourVisionKit.asset(mime = "video/mp4", name = "v.mp4"), animated = false))
    }

    @Test
    fun `the photo key follows the file and its modified time`() {
        val a = ColourVisionKit.asset(id = 1, modified = 10)
        assertEquals(PhotoKey(a), PhotoKey(ColourVisionKit.asset(id = 1, modified = 10)))
        assertFalse(PhotoKey(a) == PhotoKey(ColourVisionKit.asset(id = 2, modified = 10)))
        assertFalse(PhotoKey(a) == PhotoKey(ColourVisionKit.asset(id = 1, modified = 11)))
    }

    // ---- how a copy ended ----

    @Test
    fun `a saved copy is told in the job's own words`() {
        assertEquals(VisionCopy.COPY_SAVED, copyNotice(Result.success(CorrectedCopyOutcome.Done("x", smaller = false))))
        assertEquals(VisionCopy.COPY_SAVED_SMALLER, copyNotice(Result.success(CorrectedCopyOutcome.Done("x", smaller = true))))
    }

    @Test
    fun `a failure is the job's sentence, a cancel is cancelled, anything else is a plain failure`() {
        assertEquals(VisionCopy.COPY_NO_SPACE, copyNotice(Result.failure<Any>(CorrectedCopyFailure(VisionCopy.COPY_NO_SPACE))))
        assertEquals(VisionCopy.COPY_CANCELLED, copyNotice(Result.failure<Any>(kotlinx.coroutines.CancellationException("x"))))
        assertEquals(VisionCopy.COPY_FAILED, copyNotice(Result.failure<Any>(IllegalStateException("content://x: EACCES"))))
        assertEquals(VisionCopy.COPY_FAILED, copyNotice(Result.success("not an outcome")))
    }
}
