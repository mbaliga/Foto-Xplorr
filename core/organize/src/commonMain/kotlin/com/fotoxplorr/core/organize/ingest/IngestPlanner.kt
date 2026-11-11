package com.fotoxplorr.core.organize.ingest

import kotlinx.datetime.TimeZone

/**
 * Builds an [IngestPlan] from a card listing. Pure apart from reading the source (head and tail of
 * each file for the cheap fingerprint) and asking the targets whether a name is taken.
 *
 * Order and numbering: items are sorted by capture time, then folder, then name; `{seq}` counts
 * RAW+JPEG groups that have something to copy, from 1, so a pair is one number.
 *
 * Duplicates (see [CheapFingerprint]): a file whose cheap key AND full hash equal a [known] library
 * entry is SKIP_ALREADY_IN_LIBRARY; equal to an earlier file of the card is SKIP_DUPLICATE_IN_CARD.
 * A skip is never offered for deletion from the card (see [IngestRunner]).
 *
 * Pairing: same folder, same stem ignoring case, one RAW and one JPEG extension. The pair shares
 * the folder (expanded from the RAW's time and camera) and the new stem, and is conflict-checked as
 * one unit so the two files can never end up with different stems.
 *
 * Conflicts never overwrite: a name taken in the plan so far (compared lower-cased, so `A.JPG` and
 * `a.jpg` collide, as they do on FAT/exFAT/macOS), or existing on ANY target, becomes `stem (2).ext`,
 * `(3)` ... The same relative path is used on every destination.
 *
 * [prior] (from [plannedPaths]) pins the paths a killed run had already planned, so resuming does not
 * see its own half-copied files as conflicts and rename them.
 */
class IngestPlanner(
    private val template: DestinationTemplate,
    private val zone: TimeZone,
    private val rename: RenameRule = IdentityRenameRule,
    private val known: KnownFingerprints = KnownFingerprints.None,
) {
    fun plan(
        source: IngestSource,
        targets: List<IngestTarget>,
        prior: Map<String, String> = emptyMap(),
    ): IngestPlan {
        require(targets.size in 1..2) { "One destination, or two with backup-on-ingest." }
        val items = source.list().sortedWith(
            compareBy<IngestItem>({ it.captureMillis }, { it.folder }, { it.name }, { it.id }),
        )

        // Fingerprints and duplicate decisions.
        val fp = HashMap<String, CheapFingerprint>()
        val fullCache = HashMap<String, String>()
        fun full(item: IngestItem): String = fullCache.getOrPut(item.id) {
            val r = source.open(item)
            try { Fingerprints.full(r) } finally { r.close() }
        }
        val keeperByKey = HashMap<String, MutableList<IngestItem>>()
        val disposition = HashMap<String, Disposition>()
        val dupOf = HashMap<String, String>()
        for (item in items) {
            val r = source.open(item)
            val cheap = try { Fingerprints.cheap(r, item.captureMillis) } finally { r.close() }
            fp[item.id] = cheap
            val libHit = known.candidates(cheap).any { it.fullHash() == full(item) }
            if (libHit) { disposition[item.id] = Disposition.SKIP_ALREADY_IN_LIBRARY; continue }
            val peers = keeperByKey.getOrPut(cheap.key) { ArrayList() }
            val twin = peers.firstOrNull { full(it) == full(item) }
            if (twin != null) {
                disposition[item.id] = Disposition.SKIP_DUPLICATE_IN_CARD
                dupOf[item.id] = twin.id
            } else {
                peers.add(item)
                disposition[item.id] = Disposition.COPY
            }
        }

        // Pairing.
        val grouped = LinkedHashMap<String, MutableList<IngestItem>>()
        for (item in items) grouped.getOrPut(item.folder + "\u0000" + item.stem.lowercase()) { ArrayList() }.add(item)
        val groups = ArrayList<List<IngestItem>>()
        for (members in grouped.values) {
            val raws = members.filter { isRaw(it.extension) }
            val jpgs = members.filter { isJpeg(it.extension) }
            if (raws.size == 1 && jpgs.size == 1 && members.size == 2) groups.add(listOf(raws[0], jpgs[0]))
            else members.forEach { groups.add(listOf(it)) }
        }
        groups.sortBy { g -> g.minOf { it.captureMillis } }

        val taken = HashSet<String>()
        prior.values.forEach { taken.add(it.lowercase()) }
        var seq = 0
        val out = ArrayList<PlannedGroup>()
        for (g in groups) {
            val primary = g.firstOrNull { isRaw(it.extension) } ?: g[0]
            val copying = g.filter { disposition[it.id] == Disposition.COPY }
            if (copying.isEmpty()) {
                out.add(
                    PlannedGroup(
                        g.map { skipped(it, disposition.getValue(it.id), dupOf[it.id], fp.getValue(it.id)) },
                        g.size == 2,
                    ),
                )
                continue
            }
            seq++
            val ctx = TemplateContext(
                primary.captureMillis, zone, primary.camera, seq, primary.stem, primary.extension,
            )
            val dir = template.expand(ctx)
            val stem = NameSanitizer.segment(
                rename.newStem(RenameInput(primary.stem, primary.extension, primary.captureMillis, primary.camera, seq, zone)),
            )
            val pinned = copying.all { prior.containsKey(it.id) }
            var chosen: Map<String, String>? = null
            var renamed = false
            if (pinned) {
                chosen = copying.associate { it.id to prior.getValue(it.id) }
            } else {
                for (n in 1..9999) {
                    val s = if (n == 1) stem else "$stem ($n)"
                    val paths = copying.associate { it.id to path(dir, NameSanitizer.fileName(s, it.extension)) }
                    val free = paths.values.all { p ->
                        p.lowercase() !in taken && targets.none { t -> t.exists(p) }
                    }
                    if (free && paths.values.map { it.lowercase() }.toSet().size == paths.size) {
                        chosen = paths; renamed = n > 1; break
                    }
                }
            }
            val files = g.map { item ->
                val cheap = fp.getValue(item.id)
                if (disposition[item.id] != Disposition.COPY) {
                    skipped(item, disposition.getValue(item.id), dupOf[item.id], cheap)
                } else if (chosen == null) {
                    skipped(item, Disposition.SKIP_NO_FREE_NAME, null, cheap)
                } else {
                    val p = chosen.getValue(item.id)
                    taken.add(p.lowercase())
                    PlannedFile(item, Disposition.COPY, p, renamed, null, cheap)
                }
            }
            out.add(PlannedGroup(files, g.size == 2))
        }
        return IngestPlan(out, targets.size)
    }

    private fun skipped(item: IngestItem, d: Disposition, dup: String?, cheap: CheapFingerprint) =
        PlannedFile(item, d, "", false, dup, cheap)

    private fun path(dir: List<String>, name: String) = (dir + name).joinToString("/")

    companion object {
        private val rawExt = setOf("cr2", "cr3", "crw", "nef", "nrw", "arw", "sr2", "orf", "rw2", "raf", "dng", "pef", "srw", "3fr", "erf", "kdc", "mrw")
        private val jpegExt = setOf("jpg", "jpeg", "jpe")

        fun isRaw(ext: String) = ext.lowercase() in rawExt
        fun isJpeg(ext: String) = ext.lowercase() in jpegExt
    }
}
