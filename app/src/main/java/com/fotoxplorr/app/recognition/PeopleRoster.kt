package com.fotoxplorr.app.recognition

import com.fotoxplorr.core.model.MediaId

/** One face's address: which photo, which face in it. What a stored person is remembered by. */
data class FaceKey(val mediaId: Long, val faceIndex: Int)

/** "Not this person": [mediaId] is taken out of [personId]'s photos. */
data class Exclusion(val personId: Long, val mediaId: Long)

/**
 * A person the app keeps across runs: the name and choices the owner gave them, and a handful of
 * their faces ([witnesses]) so the next clustering can find them again.
 *
 * ### Why witnesses and not the cluster
 * [FaceClustering] re-runs from scratch over every descriptor each time the index reloads (every
 * couple of seconds while indexing), and numbers its clusters by RANK in size. So "cluster 2" is a
 * different group of people after the next 24 photos, and nothing about it can carry a name. What
 * does stay put is a face's address, so a person remembers some addresses and each run matches its
 * clusters to people by how many of those faces they hold ([PeopleRoster.resolve]).
 *
 * [mergedInto] is another person's id: this one has been folded into that one, and everything that
 * reads people follows the chain to the end.
 */
data class PersonRecord(
    val id: Long,
    val name: String? = null,
    val hidden: Boolean = false,
    val mergedInto: Long? = null,
    val witnesses: Set<FaceKey> = emptySet(),
)

/** Everything stored about people, as [PeopleRoster] reads it and the store's tables hold it. */
data class PeopleBook(
    val records: List<PersonRecord> = emptyList(),
    val exclusions: Set<Exclusion> = emptySet(),
) {
    private val byId: Map<Long, PersonRecord> by lazy(LazyThreadSafetyMode.NONE) { records.associateBy { it.id } }

    /** The person [id] finally is, following merges. An unknown id is its own end. */
    fun canonical(id: Long): Long {
        var current = id
        var steps = 0
        while (steps++ <= records.size) {
            val next = byId[current]?.mergedInto ?: return current
            if (next == current || byId[next] == null) return current
            current = next
        }
        return current
    }

    /** The record to write for a rename; null if there is no such person. A blank name clears it. */
    fun rename(id: Long, name: String): PersonRecord? {
        val record = byId[canonical(id)] ?: return null
        val clean = name.trim().take(MAX_NAME_LENGTH).ifEmpty { null }
        return if (clean == record.name) null else record.copy(name = clean)
    }

    fun setHidden(id: Long, hidden: Boolean): PersonRecord? {
        val record = byId[canonical(id)] ?: return null
        return if (record.hidden == hidden) null else record.copy(hidden = hidden)
    }

    /**
     * The records to write to fold [sourceId] into [targetId]: the source now points at the target,
     * and the target takes the source's name if it had none (a merge must not throw away a name the
     * owner typed). Empty when they are already the same person or either is unknown.
     */
    fun merge(sourceId: Long, targetId: Long): List<PersonRecord> {
        val source = byId[canonical(sourceId)] ?: return emptyList()
        val target = byId[canonical(targetId)] ?: return emptyList()
        if (source.id == target.id) return emptyList()
        val inherited = if (target.name == null && source.name != null) target.copy(name = source.name) else target
        return listOfNotNull(
            source.copy(mergedInto = target.id),
            inherited.takeIf { it != target },
        )
    }

    fun exclude(personId: Long, mediaId: MediaId): Exclusion? {
        val id = canonical(personId)
        if (byId[id] == null) return null
        return Exclusion(id, mediaId.value)
    }

    companion object {
        const val MAX_NAME_LENGTH = 60
    }
}

/**
 * A person as the app shows them: [PeopleBook]'s record joined with whichever clusters it matched
 * this run. [mediaIds] is already net of "not this person"; [faces] likewise.
 */
data class Person(
    val id: Long,
    val name: String?,
    val hidden: Boolean,
    val mediaIds: List<MediaId>,
    val faces: List<ClusterFace>,
) {
    val displayName: String get() = name ?: PeopleCopy.unnamed(id)
}

/** What the People destination draws for one person. */
data class PersonCard(
    val id: Long,
    val name: String?,
    val count: Int,
    val cover: ClusterFace?,
    val hidden: Boolean,
) {
    val displayName: String get() = name ?: PeopleCopy.unnamed(id)
}

/** What a run of [PeopleRoster.resolve] found, and what the store must write to remember it. */
data class RosterResolution(
    val people: List<Person>,
    val insert: List<PersonRecord>,
    val update: List<PersonRecord>,
)

/**
 * Pure bookkeeping between this run's clusters and the people the owner has met before.
 *
 * Deliberately not clever: it does not change how faces are grouped (see [FaceClustering]); it
 * gives each group a lasting identity and applies the owner's choices on top.
 */
object PeopleRoster {

    /** A group of one photo is not "a person you see again"; it gets a card only once it has two. */
    const val MIN_CARD_PHOTOS = 2

    /** How many faces a person remembers. Enough to survive drift, small enough to be one text cell. */
    const val MAX_WITNESSES = 48

    /** A cluster joins a stored person if it holds this share of the cluster's faces... */
    const val MIN_CLUSTER_SHARE = 0.25f

    /** ...or this share of the person's witnesses (a small old person absorbed into a big new cluster). */
    const val MIN_WITNESS_SHARE = 0.5f

    fun resolve(
        clusters: List<PersonCluster>,
        book: PeopleBook,
    ): RosterResolution {
        val records = book.records
        val ownersOfFace = HashMap<FaceKey, MutableList<Long>>()
        val witnessesOf = HashMap<Long, MutableSet<FaceKey>>()
        records.forEach { record ->
            val person = book.canonical(record.id)
            record.witnesses.forEach { key ->
                ownersOfFace.getOrPut(key) { mutableListOf() } += person
                witnessesOf.getOrPut(person) { linkedSetOf() } += key
            }
        }
        val recordOf = records.associateBy { it.id }

        val grouped = LinkedHashMap<Long, MutableList<PersonCluster>>()
        val insert = ArrayList<PersonRecord>()
        var nextId = (records.maxOfOrNull { it.id } ?: 0L) + 1L

        clusters.forEach { cluster ->
            val votes = HashMap<Long, Int>()
            cluster.faces.forEach { face ->
                ownersOfFace[FaceKey(face.mediaId.value, face.faceIndex)]
                    ?.toSet()
                    ?.forEach { person -> votes.merge(person, 1, Int::plus) }
            }
            val best = votes.entries
                .filter { (person, count) ->
                    val share = count.toFloat() / cluster.faces.size.coerceAtLeast(1)
                    val ofWitnesses = count.toFloat() / (witnessesOf[person]?.size ?: 1).coerceAtLeast(1)
                    share >= MIN_CLUSTER_SHARE || ofWitnesses >= MIN_WITNESS_SHARE
                }
                .maxWithOrNull(compareBy<Map.Entry<Long, Int>> { it.value }.thenByDescending { it.key })
                ?.key
            if (best != null) {
                grouped.getOrPut(best) { mutableListOf() } += cluster
            } else if (cluster.size >= MIN_CARD_PHOTOS && cluster.faces.isNotEmpty()) {
                val record = PersonRecord(id = nextId++, witnesses = pickWitnesses(cluster.faces, emptySet()))
                insert += record
                grouped[record.id] = mutableListOf(cluster)
            }
        }

        val excludedBy = HashMap<Long, MutableSet<Long>>()
        book.exclusions.forEach { excludedBy.getOrPut(book.canonical(it.personId)) { linkedSetOf() } += it.mediaId }

        val update = ArrayList<PersonRecord>()
        val people = grouped.mapNotNull { (id, group) ->
            val record = recordOf[id] ?: insert.first { it.id == id }
            val excluded = excludedBy[id].orEmpty()
            val mediaIds = group.flatMap { it.mediaIds }.distinct()
                .filter { it.value !in excluded }
                .sortedByDescending { it.value }
            val faces = group.flatMap { it.faces }.filter { it.mediaId.value !in excluded }
            if (record.id in recordOf) {
                val grown = pickWitnesses(group.flatMap { it.faces }, record.witnesses)
                if (grown != record.witnesses) update += record.copy(witnesses = grown)
            }
            if (mediaIds.isEmpty()) {
                null
            } else {
                Person(
                    id = id,
                    name = record.name?.takeIf { it.isNotBlank() },
                    hidden = record.hidden,
                    mediaIds = mediaIds,
                    faces = faces,
                )
            }
        }
        return RosterResolution(people, insert, update)
    }

    /** [existing] plus the largest of [faces] not yet in it, up to [MAX_WITNESSES]. */
    private fun pickWitnesses(faces: List<ClusterFace>, existing: Set<FaceKey>): Set<FaceKey> {
        if (existing.size >= MAX_WITNESSES) return existing
        val out = LinkedHashSet(existing)
        faces.sortedWith(compareByDescending<ClusterFace> { it.relativeArea }.thenBy { it.mediaId.value }.thenBy { it.faceIndex })
            .forEach { face ->
                if (out.size < MAX_WITNESSES) out += FaceKey(face.mediaId.value, face.faceIndex)
            }
        return out
    }

    /**
     * The cards for [people], counting only photos in [visibleIds] -- the destination's own list,
     * which has already been through the one visibility rule (TRAPS #33). That is the privacy
     * rule for People in one place: a protected, archived or hidden photo is not in [visibleIds],
     * so it adds nothing to a count and can never be a card's face; a person with no visible photo
     * has no card at all.
     *
     * Order: named people first, then the rest; each group by photo count, most first, then by id.
     * Named first because someone you took the trouble to name is who you will look for; the
     * unnamed tail is exactly the part that needs sifting, so it is sorted by what is most worth
     * naming (the most photos).
     */
    fun cards(people: List<Person>, visibleIds: Set<MediaId>, showHidden: Boolean): List<PersonCard> =
        people.asSequence()
            .filter { showHidden || !it.hidden }
            .mapNotNull { person ->
                val count = person.mediaIds.count { it in visibleIds }
                if (count == 0) return@mapNotNull null
                PersonCard(
                    id = person.id,
                    name = person.name,
                    count = count,
                    cover = coverFace(person.faces.filter { it.mediaId in visibleIds }),
                    hidden = person.hidden,
                )
            }
            .sortedWith(
                compareByDescending<PersonCard> { it.name != null }
                    .thenByDescending { it.count }
                    .thenBy { it.id },
            )
            .toList()

    /** The biggest face that has a usable box; failing that the biggest face (the card shows a placeholder). */
    fun coverFace(faces: List<ClusterFace>): ClusterFace? {
        val order = compareByDescending<ClusterFace> { it.relativeArea }
            .thenBy { it.mediaId.value }
            .thenBy { it.faceIndex }
        return faces.filter { it.box?.isUsable == true }.minWithOrNull(order) ?: faces.minWithOrNull(order)
    }
}
