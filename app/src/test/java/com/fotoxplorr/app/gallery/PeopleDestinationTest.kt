package com.fotoxplorr.app.gallery

import com.fotoxplorr.app.recognition.ClusterFace
import com.fotoxplorr.app.recognition.PeopleRoster
import com.fotoxplorr.app.recognition.Person
import com.fotoxplorr.app.recognition.RecognitionIndex
import com.fotoxplorr.core.model.MediaId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The People destination as a FILTER on the library: the overview and a person's own grid start
 * from the same browsable list, so what the rest of the app hides, People hides too (TRAPS #33).
 */
class PeopleDestinationTest {

    private val assets = SyntheticCatalogue.assets(240)
    private val base = SyntheticCatalogue.state(assets)

    // Everyone is in every third photo's face list; person 1 has the multiples of 6, person 2 the
    // other multiples of 3. Both include photos the visibility rule hides (the locked Vault folder,
    // archived photos, trash).
    private fun person(id: Long, ids: List<Long>) = Person(
        id, null, false, ids.map { MediaId(it) }.sortedByDescending { it.value },
        ids.map { ClusterFace(MediaId(it), 0, 0.1f) },
    )

    private val withRoster = base.copy(
        recognition = base.recognition.copy(
            roster = listOf(
                person(1, (0L until 240L step 6).toList()),
                person(2, (3L until 240L step 6).toList()),
            ),
        ),
    )

    private val everyoneVisible = destinationAssets(HyleDestination.PEOPLE, withRoster)

    @Test
    fun `no person filter leaves the destination exactly as it was`() {
        val plain = destinationAssets(HyleDestination.PEOPLE, withRoster, personId = null)
        assertEquals(everyoneVisible, plain)
        assertTrue(plain.all { it.id in withRoster.recognition.peopleMediaIds })
    }

    @Test
    fun `a person's grid is the people list narrowed to their photos`() {
        val mine = destinationAssets(HyleDestination.PEOPLE, withRoster, personId = 1L)
        val expected = withRoster.recognition.roster.first { it.id == 1L }.mediaIds.toSet()
        assertTrue(mine.isNotEmpty())
        assertTrue(mine.all { it.id in expected })
        assertTrue(mine.all { it in everyoneVisible })
        // Order is the destination's own order, not the person's.
        assertEquals(everyoneVisible.filter { it.id in expected }, mine)
    }

    @Test
    fun `protected, archived and trashed photos are in no person's grid`() {
        val shown = (destinationAssets(HyleDestination.PEOPLE, withRoster, personId = 1L) +
            destinationAssets(HyleDestination.PEOPLE, withRoster, personId = 2L)).map { it.id }.toSet()
        val vault = assets.filter { it.bucketName == "Vault" }.map { it.id }.toSet()
        assertTrue(vault.isNotEmpty())
        assertTrue("locked folder leaked", shown.none { it in vault })
        assertTrue("archived leaked", shown.none { it in withRoster.library.archivedIds })
        assertTrue("trashed leaked", shown.none { id -> assets.first { it.id == id }.isTrashed })
    }

    @Test
    fun `cards count what the grid would show, so a card and its grid always agree`() {
        val visible = everyoneVisible.mapTo(HashSet()) { it.id }
        val cards = PeopleRoster.cards(withRoster.recognition.roster, visible, showHidden = true)
        cards.forEach { card ->
            assertEquals(
                "card ${card.id}",
                card.count,
                destinationAssets(HyleDestination.PEOPLE, withRoster, personId = card.id).size,
            )
        }
    }

    @Test
    fun `an unknown person id shows nothing rather than everything`() {
        assertTrue(destinationAssets(HyleDestination.PEOPLE, withRoster, personId = 99L).isEmpty())
    }

    @Test
    fun `the filter does nothing on any other destination`() {
        assertEquals(
            destinationAssets(HyleDestination.PHOTOS, withRoster),
            destinationAssets(HyleDestination.PHOTOS, withRoster, personId = 1L),
        )
    }

    @Test
    fun `status line reads the pass's own counters and does not hide a failure`() {
        val running = com.fotoxplorr.app.recognition.RecognitionProgress(running = true, completed = 749, total = 20_743)
        assertTrue(peopleStatusLine(running).let { it.contains("749") && it.contains("20") && it.startsWith("Recognising faces:") })
        assertTrue(peopleStatusLine(com.fotoxplorr.app.recognition.RecognitionProgress(message = "no space")).contains("no space"))
        // A fresh install has checked nothing, so it must not say it has.
        assertEquals(
            com.fotoxplorr.app.recognition.PeopleCopy.NOT_CHECKED,
            peopleStatusLine(com.fotoxplorr.app.recognition.RecognitionProgress()),
        )
        assertEquals(
            com.fotoxplorr.app.recognition.PeopleCopy.UP_TO_DATE,
            peopleStatusLine(com.fotoxplorr.app.recognition.RecognitionProgress(indexedCount = 50, completed = 50, total = 50)),
        )
        assertEquals(
            "3 photos could not be checked for faces.",
            peopleStatusLine(com.fotoxplorr.app.recognition.RecognitionProgress(indexedCount = 50, completed = 10, total = 10, failed = 3)),
        )
        assertTrue(RecognitionIndex.EMPTY.roster.isEmpty())
    }
}
