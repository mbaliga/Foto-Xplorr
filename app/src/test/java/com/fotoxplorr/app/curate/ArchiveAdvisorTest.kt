package com.fotoxplorr.app.curate

import com.fotoxplorr.app.curate.ArchiveAdvisor.ArchiveCandidate
import com.fotoxplorr.app.curate.ArchiveAdvisor.ArchiveReasonCategory
import com.fotoxplorr.app.curate.ArchiveAdvisor.ArchiveSuggestion
import com.fotoxplorr.app.media.MediaId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ArchiveAdvisorTest {

    @Test
    fun `a favourited photo is never suggested even when every other signal qualifies`() {
        val favourite = candidate(id = 1, isFavorite = true, isScreenshot = true, ageMillis = 240 * DAY)
        assertEquals(emptyList<ArchiveSuggestion>(), ArchiveAdvisor.suggestions(listOf(favourite)))
    }

    @Test
    fun `an already archived photo is never suggested again`() {
        val archived = candidate(id = 1, isArchived = true, isScreenshot = true, ageMillis = 240 * DAY)
        assertEquals(emptyList<ArchiveSuggestion>(), ArchiveAdvisor.suggestions(listOf(archived)))
    }

    @Test
    fun `a previously dismissed photo is never suggested again`() {
        val dismissed = candidate(id = 1, previouslyDismissed = true, isScreenshot = true, ageMillis = 240 * DAY)
        assertEquals(emptyList<ArchiveSuggestion>(), ArchiveAdvisor.suggestions(listOf(dismissed)))
    }

    @Test
    fun `an old screenshot is suggested with the exact phrasing from the task's own example`() {
        val screenshot = candidate(id = 1, isScreenshot = true, ageMillis = 240 * DAY) // 240 / 30 = 8 months
        val result = ArchiveAdvisor.suggestions(listOf(screenshot))
        assertEquals(1, result.size)
        assertEquals(ArchiveReasonCategory.OLD_SCREENSHOT, result[0].category)
        assertEquals("Screenshot from 8 months ago", result[0].reason)
    }

    @Test
    fun `age phrasing rolls over into years past the twelve-month mark`() {
        val screenshot = candidate(id = 1, isScreenshot = true, ageMillis = 800 * DAY) // 800 / 365 = 2 years
        val result = ArchiveAdvisor.suggestions(listOf(screenshot))
        assertEquals("Screenshot from 2 years ago", result.single().reason)
    }

    @Test
    fun `a screenshot younger than the threshold is not suggested`() {
        val recent = candidate(id = 1, isScreenshot = true, ageMillis = 10 * DAY)
        assertEquals(emptyList<Any>(), ArchiveAdvisor.suggestions(listOf(recent)))
    }

    @Test
    fun `an old photo that is not a screenshot is not suggested for its age alone`() {
        val old = candidate(id = 1, isScreenshot = false, ageMillis = 800 * DAY)
        assertEquals(emptyList<Any>(), ArchiveAdvisor.suggestions(listOf(old)))
    }

    @Test
    fun `duplicate groups suggest every member except the oldest, with an accurate count`() {
        val oldest = candidate(id = 1, ageMillis = 300 * DAY, sizeBytes = 5_000_000L, widthPx = 3000, heightPx = 2000)
        val middle = oldest.copy(mediaId = MediaId(2), ageMillis = 100 * DAY)
        val newest = oldest.copy(mediaId = MediaId(3), ageMillis = 50 * DAY)

        val result = ArchiveAdvisor.suggestions(listOf(oldest, middle, newest))

        assertEquals(setOf(MediaId(2), MediaId(3)), result.map { it.mediaId }.toSet())
        assertTrue(result.all { it.category == ArchiveReasonCategory.DUPLICATE })
        assertTrue(result.all { it.reason == "Near-duplicate of 2 others" })
    }

    @Test
    fun `a favourited original still counts toward ranking, so the next-oldest copy is not excused`() {
        // Three identical photos; the TRUE original is favourited (protected on its own terms).
        // The middle one must still read as an ordinary redundant copy -- it must not be quietly
        // promoted to "the keeper" just because the true original was filtered out of the
        // eligible set first.
        val trueOriginal = candidate(
            id = 1, isFavorite = true, ageMillis = 300 * DAY,
            sizeBytes = 5_000_000L, widthPx = 3000, heightPx = 2000,
        )
        val ordinaryCopy = trueOriginal.copy(mediaId = MediaId(2), isFavorite = false, ageMillis = 100 * DAY)
        val anotherCopy = trueOriginal.copy(mediaId = MediaId(3), isFavorite = false, ageMillis = 50 * DAY)

        val result = ArchiveAdvisor.suggestions(listOf(trueOriginal, ordinaryCopy, anotherCopy))

        assertEquals(setOf(MediaId(2), MediaId(3)), result.map { it.mediaId }.toSet())
        assertTrue(result.all { it.reason == "Near-duplicate of 2 others" })
    }

    @Test
    fun `a low-resolution photo is suggested with its dimensions in the reason`() {
        val tiny = candidate(id = 1, widthPx = 320, heightPx = 240)
        val result = ArchiveAdvisor.suggestions(listOf(tiny))
        assertEquals(1, result.size)
        assertEquals(ArchiveReasonCategory.LOW_RESOLUTION, result[0].category)
        assertEquals("Low resolution (320×240)", result[0].reason)
    }

    @Test
    fun `an ordinary-resolution photo is not suggested for size`() {
        val normal = candidate(id = 1, widthPx = 4000, heightPx = 3000)
        assertEquals(emptyList<Any>(), ArchiveAdvisor.suggestions(listOf(normal)))
    }

    @Test
    fun `missing dimensions (zero) are never mistaken for a small photo`() {
        val unknownDimensions = candidate(id = 1, widthPx = 0, heightPx = 0)
        assertEquals(emptyList<Any>(), ArchiveAdvisor.suggestions(listOf(unknownDimensions)))
    }

    @Test
    fun `a very blurry photo is suggested`() {
        val blurry = candidate(id = 1, sharpness = 1f)
        val result = ArchiveAdvisor.suggestions(listOf(blurry))
        assertEquals(1, result.size)
        assertEquals(ArchiveReasonCategory.BLURRY, result[0].category)
        assertEquals("Very blurry", result[0].reason)
    }

    @Test
    fun `a sharp photo is not suggested for blur`() {
        val sharp = candidate(id = 1, sharpness = 500f)
        assertEquals(emptyList<Any>(), ArchiveAdvisor.suggestions(listOf(sharp)))
    }

    @Test
    fun `unknown sharpness (null) is never treated as blurry`() {
        val unknown = candidate(id = 1, sharpness = null)
        assertEquals(emptyList<Any>(), ArchiveAdvisor.suggestions(listOf(unknown)))
    }

    @Test
    fun `a photo qualifying on several signals at once gets exactly one suggestion, the highest-priority one`() {
        val messy = candidate(
            id = 1,
            isScreenshot = true,
            ageMillis = 240 * DAY,
            widthPx = 100,
            heightPx = 100,
            sharpness = 1f,
        )
        val result = ArchiveAdvisor.suggestions(listOf(messy))
        assertEquals(1, result.size)
        assertEquals(ArchiveReasonCategory.OLD_SCREENSHOT, result[0].category)
    }

    @Test
    fun `duplicate outranks an old-screenshot signal on the same photo`() {
        val keeper = candidate(
            id = 1, ageMillis = 300 * DAY, isScreenshot = false,
            sizeBytes = 5_000_000L, widthPx = 3000, heightPx = 2000,
        )
        // The redundant copy is ALSO, independently, an old screenshot -- duplicate must still win.
        val redundantScreenshot = keeper.copy(mediaId = MediaId(2), ageMillis = 240 * DAY, isScreenshot = true)

        val result = ArchiveAdvisor.suggestions(listOf(keeper, redundantScreenshot))

        assertEquals(1, result.size)
        assertEquals(MediaId(2), result[0].mediaId)
        assertEquals(ArchiveReasonCategory.DUPLICATE, result[0].category)
        assertEquals("Near-duplicate of 1 other", result[0].reason)
    }

    @Test
    fun `no candidates yields no suggestions`() {
        assertEquals(emptyList<Any>(), ArchiveAdvisor.suggestions(emptyList()))
    }

    @Test
    fun `output preserves the candidates' own input order, not id or any other sort`() {
        val blurry = candidate(id = 3, sharpness = 1f)
        val excluded = candidate(id = 2, isFavorite = true, isScreenshot = true, ageMillis = 240 * DAY)
        val screenshot = candidate(id = 1, isScreenshot = true, ageMillis = 240 * DAY)

        val result = ArchiveAdvisor.suggestions(listOf(blurry, excluded, screenshot))

        assertEquals(listOf(MediaId(3), MediaId(1)), result.map { it.mediaId })
    }

    private fun candidate(
        id: Long,
        isFavorite: Boolean = false,
        isArchived: Boolean = false,
        previouslyDismissed: Boolean = false,
        isScreenshot: Boolean = false,
        ageMillis: Long = 0L,
        // Unique per id by default so two candidates never accidentally land in the same
        // duplicate group unless a test deliberately gives them matching size/dimensions/type.
        sizeBytes: Long = 1_000_000L + id,
        widthPx: Int = 4000,
        heightPx: Int = 3000,
        mimeType: String = "image/jpeg",
        sharpness: Float? = null,
    ) = ArchiveCandidate(
        mediaId = MediaId(id),
        isFavorite = isFavorite,
        isArchived = isArchived,
        previouslyDismissed = previouslyDismissed,
        isScreenshot = isScreenshot,
        ageMillis = ageMillis,
        sizeBytes = sizeBytes,
        widthPx = widthPx,
        heightPx = heightPx,
        mimeType = mimeType,
        sharpness = sharpness,
    )

    private companion object {
        const val DAY = 24L * 60L * 60L * 1_000L
    }
}
