package com.fotoxplorr.app.media

import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * [mergeIntoSortedCatalogue] replaced a rebuild-and-re-sort of the entire catalogue on every scan
 * batch, which is one of the reasons a scan made the whole app stutter. The merge is only a valid
 * substitution if it produces *exactly* what the re-sort produced, so that equivalence — not the
 * merge's internals — is what these tests pin.
 *
 * The oracle is deliberately the old implementation, written out longhand: replace-by-id into a
 * map, then normalise. If the two ever disagree, the merge is wrong, because the sort is total
 * (ids are unique) and therefore admits exactly one correct answer.
 */
class CatalogueMergeTest {

    private fun asset(
        id: Long,
        dateTaken: Long,
        dateModified: Long = 0L,
        name: String = "photo-$id.jpg",
    ) = MediaAsset(
        id = MediaId(id),
        contentUriString = "content://media/external/images/media/$id",
        displayName = name,
        mimeType = "image/jpeg",
        bucketName = "Camera",
        dateTakenMillis = dateTaken,
        dateModifiedSeconds = dateModified,
        width = 4032,
        height = 3024,
        sizeBytes = 1_000L + id,
        relativePath = "DCIM/Camera/",
        isFavorite = false,
        isTrashed = false,
    )

    /** The implementation that was replaced, kept verbatim as the oracle. */
    private fun rebuildAndResort(
        current: List<MediaAsset>,
        incoming: List<MediaAsset>,
    ): List<MediaAsset> = normalizeCatalogue(
        current.associateByTo(linkedMapOf()) { it.id }
            .apply { incoming.forEach { put(it.id, it) } }
            .values,
    )

    @Test
    fun `merging into an empty catalogue yields the sorted batch`() {
        val batch = listOf(asset(1, 100), asset(2, 300), asset(3, 200))
        assertEquals(
            rebuildAndResort(emptyList(), batch),
            mergeIntoSortedCatalogue(emptyList(), batch),
        )
    }

    @Test
    fun `an empty batch leaves the catalogue untouched`() {
        val current = normalizeCatalogue(listOf(asset(1, 100), asset(2, 300)))
        assertEquals(current, mergeIntoSortedCatalogue(current, emptyList()))
    }

    @Test
    fun `a batch that supersedes held entries replaces rather than duplicates`() {
        val current = normalizeCatalogue(listOf(asset(1, 100), asset(2, 200), asset(3, 300)))
        // Same ids, different dates — these must replace, and move, not appear twice.
        val batch = listOf(asset(2, 999), asset(3, 50))

        val merged = mergeIntoSortedCatalogue(current, batch)

        assertEquals(rebuildAndResort(current, batch), merged)
        assertEquals("no duplicate ids", merged.size, merged.map { it.id }.toSet().size)
        assertEquals(3, merged.size)
        assertEquals(MediaId(2), merged.first().id)
    }

    @Test
    fun `ties on date fall through to the id tiebreak identically`() {
        // Every asset shares a date, so ordering rests entirely on the final tiebreak.
        val current = normalizeCatalogue((1L..8L).map { asset(it, dateTaken = 500L) })
        val batch = (5L..12L).map { asset(it, dateTaken = 500L) }

        assertEquals(rebuildAndResort(current, batch), mergeIntoSortedCatalogue(current, batch))
    }

    @Test
    fun `a batch containing duplicate ids is deduped like the re-sort`() {
        val current = normalizeCatalogue(listOf(asset(1, 100)))
        val batch = listOf(asset(2, 200, name = "first.jpg"), asset(2, 200, name = "second.jpg"))

        assertEquals(rebuildAndResort(current, batch), mergeIntoSortedCatalogue(current, batch))
    }

    @Test
    fun `merge matches the re-sort across many randomised batches`() {
        // Fixed seed: a failure has to be reproducible to be worth anything.
        val random = Random(20260814)
        var current = emptyList<MediaAsset>()
        var oracle = emptyList<MediaAsset>()

        repeat(60) { round ->
            val batch = List(random.nextInt(1, 25)) {
                asset(
                    id = random.nextLong(1L, 120L),
                    dateTaken = random.nextLong(0L, 40L) * 1_000L,
                    dateModified = random.nextLong(0L, 5L),
                )
            }
            current = mergeIntoSortedCatalogue(current, batch)
            oracle = rebuildAndResort(oracle, batch)
            assertEquals("round $round", oracle, current)
        }
    }

    @Test
    fun `the merged catalogue is always in catalogue order`() {
        val random = Random(4471)
        var current = emptyList<MediaAsset>()
        repeat(40) {
            val batch = List(random.nextInt(1, 15)) {
                asset(random.nextLong(1L, 90L), random.nextLong(0L, 30L) * 1_000L)
            }
            current = mergeIntoSortedCatalogue(current, batch)
            val resorted = current.sortedWith(CATALOGUE_ORDER)
            assertEquals(resorted, current)
        }
    }
}
