package com.fotoxplorr.app.recognition

import com.fotoxplorr.core.model.MediaId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The bookkeeping that gives clusters a lasting identity, and the owner's choices on top of it. */
class PeopleRosterTest {

    private val goodBox = FaceBox(0.3f, 0.3f, 0.5f, 0.5f, 1f)

    private fun face(media: Long, index: Int = 0, area: Float = 0.1f, box: FaceBox? = goodBox) =
        ClusterFace(MediaId(media), index, area, box)

    private fun cluster(rank: Int, vararg media: Long, area: Float = 0.1f) = PersonCluster(
        id = rank,
        mediaIds = media.map { MediaId(it) }.sortedByDescending { it.value },
        faceCount = media.size,
        faces = media.map { face(it, area = area) },
    )

    private fun ids(person: Person) = person.mediaIds.map { it.value }.toSet()

    @Test
    fun `a first run gives every group of two or more photos a person, biggest first, and drops singletons`() {
        val result = PeopleRoster.resolve(
            listOf(cluster(0, 1, 2, 3), cluster(1, 4, 5), cluster(2, 6)),
            PeopleBook(),
        )
        assertEquals(listOf(1L, 2L), result.people.map { it.id })
        assertEquals(setOf(1L, 2L, 3L), ids(result.people[0]))
        assertEquals(2, result.insert.size)
        assertTrue(result.insert.all { it.witnesses.isNotEmpty() })
    }

    @Test
    fun `ids survive a reordering of the clusters that rank would have renumbered`() {
        val first = PeopleRoster.resolve(listOf(cluster(0, 1, 2, 3), cluster(1, 4, 5)), PeopleBook())
        val book = PeopleBook(first.insert)
        // Next run: the second group has overtaken the first, so rank 0 is now the 4/5 group, which
        // also gained photos. Rank-based ids would swap; matched ids must not.
        val second = PeopleRoster.resolve(
            listOf(cluster(0, 4, 5, 7, 8, 9, 10), cluster(1, 1, 2, 3)),
            book,
        )
        assertEquals(1L, second.people.single { 1L in ids(it) }.id)
        assertEquals(2L, second.people.single { 4L in ids(it) }.id)
        assertTrue(second.insert.isEmpty())
    }

    @Test
    fun `a name follows the person across a reordering`() {
        val first = PeopleRoster.resolve(listOf(cluster(0, 1, 2, 3), cluster(1, 4, 5)), PeopleBook())
        val named = first.insert.map { if (it.id == 2L) it.copy(name = "Asha") else it }
        val second = PeopleRoster.resolve(listOf(cluster(0, 4, 5, 6), cluster(1, 1, 2, 3)), PeopleBook(named))
        assertEquals("Asha", second.people.single { 4L in ids(it) }.name)
        assertNull(second.people.single { 1L in ids(it) }.name)
    }

    @Test
    fun `one person split into two clusters comes back together under the stored person`() {
        val first = PeopleRoster.resolve(listOf(cluster(0, 1, 2, 3, 4)), PeopleBook())
        // The next run splits them: both halves hold some of the stored witnesses.
        val second = PeopleRoster.resolve(listOf(cluster(0, 1, 2), cluster(1, 3, 4)), PeopleBook(first.insert))
        assertEquals(1, second.people.size)
        assertEquals(setOf(1L, 2L, 3L, 4L), ids(second.people.single()))
    }

    @Test
    fun `a cluster that only brushes a stored person by one face is not absorbed`() {
        val first = PeopleRoster.resolve(listOf(cluster(0, 1, 2, 3, 4, 5, 6, 7, 8)), PeopleBook())
        // A new big group shares exactly one stored face (media 1): 1 of 20 faces, 1 of 8 witnesses.
        val big = PersonCluster(
            9, (1L..20L).map { MediaId(it + if (it == 1L) 0 else 100) }, 20,
            (1L..20L).map { face(it + if (it == 1L) 0 else 100) },
        )
        val second = PeopleRoster.resolve(listOf(big), PeopleBook(first.insert))
        assertEquals("a new person, not the old one", 2L, second.people.single().id)
    }

    @Test
    fun `witnesses are capped and grow only while below the cap`() {
        val many = cluster(0, *(1L..200L).toList().toLongArray())
        val first = PeopleRoster.resolve(listOf(many), PeopleBook())
        assertEquals(PeopleRoster.MAX_WITNESSES, first.insert.single().witnesses.size)
        val again = PeopleRoster.resolve(listOf(many), PeopleBook(first.insert))
        assertTrue("a full witness list is left alone", again.update.isEmpty())
    }

    @Test
    fun `merge points the source at the target and the person is one from then on`() {
        val first = PeopleRoster.resolve(listOf(cluster(0, 1, 2, 3), cluster(1, 4, 5)), PeopleBook())
        val book = PeopleBook(first.insert)
        val changed = book.merge(2L, 1L)
        assertEquals(1L, changed.single { it.id == 2L }.mergedInto)
        val merged = PeopleBook(book.records.map { r -> changed.firstOrNull { it.id == r.id } ?: r })
        val after = PeopleRoster.resolve(listOf(cluster(0, 1, 2, 3), cluster(1, 4, 5)), merged)
        assertEquals(1, after.people.size)
        assertEquals(1L, after.people.single().id)
        assertEquals(setOf(1L, 2L, 3L, 4L, 5L), ids(after.people.single()))
    }

    @Test
    fun `merge keeps a name the target lacks and refuses a self-merge or an unknown person`() {
        val book = PeopleBook(listOf(PersonRecord(1), PersonRecord(2, name = "Sam")))
        val changed = book.merge(2L, 1L)
        assertEquals("Sam", changed.single { it.id == 1L }.name)
        assertTrue(book.merge(1L, 1L).isEmpty())
        assertTrue(book.merge(1L, 99L).isEmpty())
        // Merging into a person that was itself merged lands on the end of the chain.
        val chain = PeopleBook(listOf(PersonRecord(1), PersonRecord(2, mergedInto = 1), PersonRecord(3)))
        assertEquals(1L, chain.merge(3L, 2L).single { it.id == 3L }.mergedInto)
    }

    @Test
    fun `a merge cycle in stored data cannot hang the lookup`() {
        val bad = PeopleBook(listOf(PersonRecord(1, mergedInto = 2), PersonRecord(2, mergedInto = 1)))
        // Terminates, and lands on one of them.
        assertTrue(bad.canonical(1L) in setOf(1L, 2L))
    }

    @Test
    fun `not this person removes one photo from one person and nothing else`() {
        val first = PeopleRoster.resolve(listOf(cluster(0, 1, 2, 3)), PeopleBook())
        val book = PeopleBook(first.insert, setOf(Exclusion(1L, 2L)))
        val after = PeopleRoster.resolve(listOf(cluster(0, 1, 2, 3)), book)
        assertEquals(setOf(1L, 3L), ids(after.people.single()))
        assertTrue(after.people.single().faces.none { it.mediaId.value == 2L })
        assertEquals(Exclusion(1L, 2L), book.exclude(1L, MediaId(2)))
        assertNull(PeopleBook().exclude(5L, MediaId(2)))
    }

    @Test
    fun `excluding the only photos leaves no person`() {
        val first = PeopleRoster.resolve(listOf(cluster(0, 1, 2)), PeopleBook())
        val book = PeopleBook(first.insert, setOf(Exclusion(1L, 1L), Exclusion(1L, 2L)))
        assertTrue(PeopleRoster.resolve(listOf(cluster(0, 1, 2)), book).people.isEmpty())
    }

    @Test
    fun `rename trims and caps, a blank clears, an unchanged name writes nothing`() {
        val book = PeopleBook(listOf(PersonRecord(1, name = "Asha")))
        assertEquals("Maya", book.rename(1L, "  Maya ")!!.name)
        assertNull(book.rename(1L, "Asha"))
        assertNull(book.rename(1L, "   ")!!.name)
        assertEquals(PeopleBook.MAX_NAME_LENGTH, book.rename(1L, "x".repeat(500))!!.name!!.length)
        assertNull(book.rename(42L, "Nobody"))
    }

    @Test
    fun `hide is a flag on the record and only writes on change`() {
        val book = PeopleBook(listOf(PersonRecord(1)))
        assertTrue(book.setHidden(1L, true)!!.hidden)
        assertNull(book.setHidden(1L, false))
    }

    // ---- cards ----

    private fun person(id: Long, name: String?, hidden: Boolean, vararg media: Long, faces: List<ClusterFace>? = null) = Person(
        id, name, hidden, media.map { MediaId(it) },
        faces ?: media.map { face(it) },
    )

    @Test
    fun `cards count only visible photos and a person with none has no card`() {
        val people = listOf(person(1, null, false, 1, 2, 3), person(2, null, false, 4, 5))
        val cards = PeopleRoster.cards(people, setOf(MediaId(1), MediaId(2)), showHidden = false)
        assertEquals(listOf(1L), cards.map { it.id })
        assertEquals(2, cards.single().count)
    }

    @Test
    fun `a protected photo is never a card's face`() {
        val people = listOf(
            Person(
                1, null, false, listOf(MediaId(1), MediaId(2)),
                listOf(face(1, area = 0.5f), face(2, area = 0.1f)),
            ),
        )
        // Photo 1 (the biggest face) is protected and so absent from the visible set.
        val card = PeopleRoster.cards(people, setOf(MediaId(2)), false).single()
        assertEquals(MediaId(2), card.cover!!.mediaId)
        assertEquals(1, card.count)
    }

    @Test
    fun `named people come first, then by count, then by id`() {
        val people = listOf(
            person(1, null, false, 1, 2, 3, 4, 5),
            person(2, "Zed", false, 6, 7),
            person(3, null, false, 8, 9, 10),
            person(4, "Amy", false, 11, 12, 13),
            person(5, null, false, 14, 15, 16),
        )
        val order = PeopleRoster.cards(people, (1L..16L).map { MediaId(it) }.toSet(), false).map { it.id }
        assertEquals(listOf(4L, 2L, 1L, 3L, 5L), order)
    }

    @Test
    fun `hidden people appear only when asked for`() {
        val people = listOf(person(1, null, true, 1, 2), person(2, null, false, 3, 4))
        val visible = setOf(MediaId(1), MediaId(2), MediaId(3), MediaId(4))
        assertEquals(listOf(2L), PeopleRoster.cards(people, visible, false).map { it.id })
        assertEquals(listOf(1L, 2L), PeopleRoster.cards(people, visible, true).map { it.id }.sorted())
    }

    @Test
    fun `the cover prefers a croppable face over a bigger one with no box`() {
        val faces = listOf(face(1, area = 0.9f, box = null), face(2, area = 0.2f))
        assertEquals(MediaId(2), PeopleRoster.coverFace(faces)!!.mediaId)
        assertEquals(MediaId(1), PeopleRoster.coverFace(listOf(face(1, area = 0.9f, box = null)))!!.mediaId)
        assertNull(PeopleRoster.coverFace(emptyList()))
    }

    @Test
    fun `an unnamed person reads as Person N and a named one by name`() {
        assertEquals("Person 7", person(7, null, false, 1).displayName)
        assertEquals("Asha", person(7, "Asha", false, 1).displayName)
        assertFalse(PeopleCopy.filterChip("Asha").isBlank())
        assertEquals("Asha ×", PeopleCopy.filterChip("Asha"))
    }

    @Test
    fun `witness text round trips and a bad cell is skipped, not fatal`() {
        val set = setOf(FaceKey(1, 0), FaceKey(99, 3))
        assertEquals(set, decodeWitnesses(encodeWitnesses(set)))
        assertEquals(setOf(FaceKey(5, 1)), decodeWitnesses("5:1,garbage,7:,:2,1:2:3"))
        assertEquals(emptySet<FaceKey>(), decodeWitnesses(null))
        assertEquals(emptySet<FaceKey>(), decodeWitnesses(""))
    }
}
