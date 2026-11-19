package com.fotoxplorr.core.index.walker

import com.fotoxplorr.core.formats.MediaFormat
import com.fotoxplorr.core.formats.RawVariant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TreeWalkerTest {
    private fun walkAll(tree: DocumentTree, config: WalkConfig = WalkConfig()): Pair<List<WalkedFile>, WalkStatus> {
        val files = ArrayList<WalkedFile>()
        val status = TreeWalker(tree, config).run(commit = { files += it.files })
        return files to status
    }

    private fun names(files: List<WalkedFile>) = files.map { it.name }.toSet()

    @Test
    fun fiftyThousandFilesResumeAfterAKillAtTenFiftyAndNinetyNinePercent() {
        val tree = FakeDocumentTree.big(top = 50, sub = 20, perDir = 50) // 50,000
        val (reference, refStatus) = walkAll(tree)
        assertEquals(50_000, reference.size)
        assertEquals(WalkStatus.Complete(true), refStatus)
        assertEquals(50_000, reference.map { it.documentId }.toSet().size)

        for (percent in listOf(10, 50, 99)) {
            val stopAt = reference.size * percent / 100
            // Persisted state only: the committed files and the cursor TEXT, as a database would hold them.
            val committed = ArrayList<WalkedFile>()
            var saved = TreeWalker(tree).start().encode()
            val firstRun = TreeWalker(tree)
            var cursor = WalkCursor.decode(saved)!!
            while (committed.size < stopAt) {
                val slice = firstRun.advance(cursor, WalkBudget(maxDirectories = 7, maxFiles = 300))
                committed += slice.files
                cursor = slice.cursor
                saved = cursor.encode()
            }
            // The process dies here. A new walker, a new cursor object, only the text survives.
            val resumed = WalkCursor.decode(saved)!!
            val status = TreeWalker(tree).run(from = resumed, sliceBudget = WalkBudget(maxDirectories = 11)) { committed += it.files }
            assertEquals(WalkStatus.Complete(true), status, "at $percent%")
            assertEquals(reference.size, committed.size, "no skip and no duplicate at $percent%")
            assertEquals(reference.map { it.documentId }, committed.map { it.documentId }, "same order at $percent%")
        }
    }

    @Test
    fun eachDirectoryIsQueriedOnceAndOrderIsBreadthFirst() {
        val tree = FakeDocumentTree.big(3, 3, 2)
        val (files, _) = walkAll(tree)
        assertEquals(tree.queried.size, tree.queried.toSet().size)
        assertEquals(1 + 3 + 9, tree.childrenCalls)
        assertEquals(files.map { it.depth }, files.map { it.depth }.sorted())
    }

    @Test
    fun nomediaHidesTheDirectoryAndEverythingBelowIt() {
        val t = FakeDocumentTree()
        t.file("root", "r1", "keep.jpg")
        val hidden = t.dir("root", "hidden")
        t.file(hidden, "h0", ".nomedia", mime = "application/octet-stream", size = 0)
        t.file(hidden, "h1", "secret.jpg")
        val below = t.dir(hidden, "below")
        t.file(below, "b1", "deep.jpg")
        val (files, status) = walkAll(t)
        assertEquals(setOf("keep.jpg"), names(files))
        assertEquals(WalkStatus.Complete(true), status)
        assertFalse("below" in t.queried)
        // Non-cascading: the subdirectory is still walked.
        val (loose, _) = walkAll(t, WalkConfig(nomediaCascades = false))
        assertEquals(setOf("keep.jpg", "deep.jpg"), names(loose))
        // Switched off: everything.
        assertEquals(4 - 1, walkAll(t, WalkConfig(honorNomedia = false)).first.size)
    }

    @Test
    fun builtInIgnoresHiddenOptionAndUserPatterns() {
        val t = FakeDocumentTree()
        for ((i, d) in listOf(".fotoz-trash", "Android", ".thumbnails", "\$RECYCLE.BIN", "System Volume Information", ".cache", "Holiday").withIndex()) {
            val id = t.dir("root", "d$i", d)
            t.file(id, "f$i", "in_$i.jpg")
        }
        val nested = t.dir("d6", "d6/Android", "Android") // Android below the root is an ordinary folder
        t.file(nested, "fa", "nested_android.jpg")
        val scratch = t.dir("d6", "d6/scratch", "scratch")
        t.file(scratch, "fs", "x.jpg")
        t.file("d6", "ft", "temp.tmp.jpg")
        t.file("d6", "fw", "photo.jpg")

        val (basic, _) = walkAll(t)
        assertEquals(setOf("in_5.jpg", "in_6.jpg", "nested_android.jpg", "x.jpg", "temp.tmp.jpg", "photo.jpg"), names(basic))
        val (noHidden, _) = walkAll(t, WalkConfig(skipHiddenDirectories = true))
        assertFalse("in_5.jpg" in names(noHidden))
        val (user, _) = walkAll(t, WalkConfig(ignorePatterns = listOf("scratch/", "*.tmp.jpg", "Holiday/Android")))
        assertEquals(setOf("in_5.jpg", "in_6.jpg", "photo.jpg"), names(user))
    }

    @Test
    fun appleDoubleAndNomediaFilesAreNeverReported() {
        val t = FakeDocumentTree()
        t.file("root", "a", "._IMG_1.jpg")
        t.file("root", "b", "IMG_1.jpg")
        assertEquals(setOf("IMG_1.jpg"), names(walkAll(t).first))
    }

    @Test
    fun aDirectoryThatListsItsAncestorTerminates() {
        val t = FakeDocumentTree()
        val a = t.dir("root", "a")
        val b = t.dir(a, "b")
        t.file(b, "f", "p.jpg")
        t.dirs.getValue("b") += DocumentEntry("a", "back", "vnd.android.document/directory", true)
        t.dirs.getValue("b") += DocumentEntry("root", "root-again", "vnd.android.document/directory", true)
        t.dirs.getValue("a") += DocumentEntry("a", "self", "vnd.android.document/directory", true)
        val files = ArrayList<WalkedFile>()
        val status = TreeWalker(t).run { files += it.files }
        assertEquals(WalkStatus.Complete(true), status)
        assertEquals(1, files.size)
        assertEquals(3, t.childrenCalls)
    }

    @Test
    fun aProviderInventingFreshIdsForeverIsBoundedByTheCapsAndIsNotExhaustive() {
        val t = FakeDocumentTree()
        t.onChildren = { id -> t.dirs.getOrPut(id) { ArrayList() }.let { if (it.isEmpty()) it += DocumentEntry("$id/x", "x", "vnd.android.document/directory", true) } }
        t.dir("root", "n0")
        val (_, status) = walkAll(t, WalkConfig(maxDepth = 10))
        assertEquals(WalkStatus.Complete(exhaustive = false), status)
        assertTrue(t.childrenCalls <= 12)
        val slice = TreeWalker(t, WalkConfig(maxDepth = 10)).advance(TreeWalker(t).start())
        assertFalse(slice.mayConcludeMissing)
    }

    @Test
    fun directoryCountCapStopsAWideTree() {
        val t = FakeDocumentTree()
        repeat(100) { t.dir("root", "d$it") }
        val (_, status) = walkAll(t, WalkConfig(maxDirectories = 20))
        assertEquals(WalkStatus.Complete(false), status)
        assertTrue(t.childrenCalls <= 21)
    }

    @Test
    fun hostileNamesAreSkippedOrMadeSafe() {
        val t = FakeDocumentTree()
        t.file("root", "1", "../../etc/passwd.jpg")
        t.file("root", "2", "..")
        t.file("root", "3", ".")
        t.file("root", "4", "")
        t.file("root", "5", "   ")
        t.file("root", "6", "a\u0000b.jpg")
        t.file("root", "7", "x".repeat(600) + ".jpg")
        t.file("root", "8", "evil‮gpj.exe.jpg")
        t.file("root", "9", "line\nbreak\tname.jpg")
        t.file("root", "x".repeat(5000), "huge-id.jpg")
        t.file("root", "10", "fine.jpg")
        t.dirs.getValue("root") += DocumentEntry("d..", "..", "vnd.android.document/directory", true)
        t.dirs.getValue("root") += DocumentEntry("d/", "a/b", "vnd.android.document/directory", true)
        val files = ArrayList<WalkedFile>()
        val status = TreeWalker(t).run { files += it.files }
        assertEquals(WalkStatus.Complete(true), status)
        assertEquals(setOf("evil�gpj.exe.jpg", "line�break�name.jpg", "fine.jpg"), names(files))
        assertTrue(files.none { '/' in it.name || it.name.any { c -> c.code < 0x20 } })
        assertFalse("d.." in t.queried)
        assertFalse("d/" in t.queried)
    }

    @Test
    fun aListerThatThrowsIsFailedNotAnEmptyCompleteAndTheRetryResumesAtTheSameDirectory() {
        val t = FakeDocumentTree.big(2, 2, 3)
        var broken = true
        t.onChildren = { id -> if (broken && id == "a1") throw TreeAccessException(TreeFailure.ERROR, "provider said no") }
        val walker = TreeWalker(t)
        val first = walker.advance(walker.start())
        val status = assertIs<WalkStatus.Failed>(first.status)
        assertEquals(TreeFailure.ERROR, status.failure)
        assertFalse(first.mayConcludeMissing)
        assertEquals("a1", first.cursor.pending.first().documentId)
        assertTrue(first.files.isNotEmpty() || first.cursor.counters.directoriesDone > 0)
        // A plain exception from the lister is the same story.
        broken = false
        val rest = ArrayList<WalkedFile>(first.files)
        val end = walker.run(from = first.cursor) { rest += it.files }
        assertEquals(WalkStatus.Complete(true), end)
        assertEquals(12, rest.size)
        assertEquals(12, rest.map { it.documentId }.toSet().size)
    }

    @Test
    fun anUnexpectedExceptionIsAlsoFailed() {
        val t = FakeDocumentTree()
        t.onChildren = { throw IllegalStateException("boom") }
        val slice = TreeWalker(t).let { it.advance(it.start()) }
        assertEquals(TreeFailure.ERROR, assertIs<WalkStatus.Failed>(slice.status).failure)
        assertFalse(slice.mayConcludeMissing)
    }

    @Test
    fun aRevokedGrantMidWalkIsRevokedAndNothingIsConcluded() {
        val t = FakeDocumentTree.big(4, 4, 5)
        var calls = 0
        t.onChildren = { if (++calls == 9) throw TreeAccessException(TreeFailure.REVOKED, "permission denied") }
        val walker = TreeWalker(t)
        val files = ArrayList<WalkedFile>()
        val status = walker.run { files += it.files }
        assertEquals(TreeFailure.REVOKED, assertIs<WalkStatus.Failed>(status).failure)
        assertTrue(files.size in 1 until 80)
        // The cursor is intact: nothing was dropped, and an unplugged drive is a different failure.
        val u = FakeDocumentTree()
        u.onChildren = { throw TreeAccessException(TreeFailure.UNREACHABLE, "not mounted") }
        assertEquals(TreeFailure.UNREACHABLE, assertIs<WalkStatus.Failed>(TreeWalker(u).let { it.advance(it.start()).status }).failure)
    }

    @Test
    fun sniffingFindsAJpgThatIsAPngAndARenamedRaw() {
        val t = FakeDocumentTree()
        t.file("root", "1", "holiday.jpg", head = PNG_HEAD)
        t.file("root", "2", "renamed.jpg", head = RAF_HEAD)
        t.file("root", "3", "honest.jpg", head = JPEG_HEAD)
        t.file("root", "4", "DSC_1.NEF", mime = "image/x-nikon-nef", head = TIFF_HEAD)
        t.file("root", "5", "noext", mime = "application/octet-stream", head = JPEG_HEAD)
        t.file("root", "6", "notes.txt", mime = "text/plain", head = JPEG_HEAD)
        t.file("root", "7", "unreadable.jpg", head = null)
        val (files, _) = walkAll(t)
        val by = files.associateBy { it.name }
        assertEquals(MediaFormat.Png, by.getValue("holiday.jpg").format)
        assertTrue(by.getValue("holiday.jpg").sniffMismatch)
        assertEquals(MediaFormat.Raw(RawVariant.RAF), by.getValue("renamed.jpg").format)
        assertFalse(by.getValue("honest.jpg").sniffMismatch)
        assertEquals(MediaFormat.Raw(RawVariant.NEF), by.getValue("DSC_1.NEF").format) // TIFF bytes must not downgrade a RAW
        assertEquals(MediaFormat.Jpeg, by.getValue("noext").format)
        assertNull(by["notes.txt"])
        assertEquals(MediaFormat.Jpeg, by.getValue("unreadable.jpg").format) // unreadable head falls back to the label
        assertNull(by.getValue("unreadable.jpg").sniffedFormat)
    }

    @Test
    fun virtualDocumentsAreNotReadForSniffingByDefaultAndNeverIsHonoured() {
        val t = FakeDocumentTree()
        t.file("root", "1", "cloud.jpg", flags = DocumentFlags.VIRTUAL_DOCUMENT, head = PNG_HEAD)
        walkAll(t)
        assertEquals(0, t.headReads)
        walkAll(t, WalkConfig(sniffVirtualDocuments = true))
        assertEquals(1, t.headReads)
        walkAll(t, WalkConfig(sniff = SniffPolicy.NEVER, sniffVirtualDocuments = true))
        assertEquals(1, t.headReads)
    }

    @Test
    fun sniffReadsAreBoundedToTheConfiguredHead() {
        val t = FakeDocumentTree()
        t.file("root", "1", "big.jpg", head = ByteArray(10_000) { 7 })
        var asked = -1
        val spy = object : DocumentTree by t {
            override fun readHead(documentId: String, maxBytes: Int): ByteArray? { asked = maxBytes; return t.readHead(documentId, maxBytes) }
        }
        walkAll(spy)
        assertEquals(64, asked)
    }

    @Test
    fun siblingXmpSidecarsAreRecorded() {
        val t = FakeDocumentTree()
        t.file("root", "1", "IMG_1.jpg")
        t.file("root", "2", "IMG_1.xmp", mime = "application/rdf+xml")
        t.file("root", "3", "IMG_2.CR3", mime = "image/x-canon-cr3")
        t.file("root", "4", "IMG_2.CR3.xmp", mime = "application/rdf+xml")
        t.file("root", "5", "IMG_3.jpg")
        val by = walkAll(t).first.associateBy { it.name }
        assertEquals(listOf("IMG_1.xmp"), by.getValue("IMG_1.jpg").sidecarNames)
        assertEquals(listOf("IMG_2.CR3.xmp"), by.getValue("IMG_2.CR3").sidecarNames)
        assertEquals(emptyList(), by.getValue("IMG_3.jpg").sidecarNames)
    }

    @Test
    fun cancellationStopsWithAnIntactCursorThatFinishesTheJob() {
        val t = FakeDocumentTree.big(5, 5, 4)
        val walker = TreeWalker(t)
        var n = 0
        val slice = walker.advance(walker.start(), cancelled = { ++n > 6 })
        assertEquals(WalkStatus.Cancelled, slice.status)
        assertFalse(slice.mayConcludeMissing)
        val all = ArrayList<WalkedFile>(slice.files)
        walker.run(from = slice.cursor) { all += it.files }
        assertEquals(100, all.size)
    }

    @Test
    fun progressIsReportedAfterEveryDirectory() {
        val t = FakeDocumentTree.big(2, 2, 2)
        val seen = ArrayList<WalkProgress>()
        val walker = TreeWalker(t)
        walker.advance(walker.start(), progress = { seen += it })
        assertEquals(7, seen.size)
        assertEquals(8, seen.last().counters.mediaFound)
        assertEquals(0, seen.last().pendingDirectories)
        assertEquals((1..7).toList(), seen.map { it.counters.directoriesDone })
    }

    @Test
    fun budgetAlwaysMakesProgressEvenAtZero() {
        val t = FakeDocumentTree.big(1, 1, 1)
        val walker = TreeWalker(t)
        val slice = walker.advance(walker.start(), WalkBudget(maxDirectories = 0, maxFiles = 0, maxMillis = 0))
        assertEquals(1, slice.cursor.counters.directoriesDone)
    }

    @Test
    fun cursorRoundTripsAwkwardIdsAndRejectsGarbage() {
        val t = FakeDocumentTree()
        val weird = "primary:Pictures/a\tb\nc\\d\re"
        val d = t.dir("root", weird, "Odd\tName")
        t.file(d, "f", "x.jpg")
        val walker = TreeWalker(t)
        val slice = walker.advance(walker.start(), WalkBudget(maxDirectories = 1))
        val back = assertNotNull(WalkCursor.decode(slice.cursor.encode()))
        assertEquals(slice.cursor.pending, back.pending)
        assertEquals(slice.cursor.counters, back.counters)
        assertEquals(slice.cursor.queued, back.queued)
        assertNull(WalkCursor.decode("hello"))
        assertNull(WalkCursor.decode(""))
        assertNull(WalkCursor.decode(slice.cursor.encode().replace("C\t", "C\tx")))
        val rest = ArrayList<WalkedFile>()
        walker.run(from = back) { rest += it.files }
        assertEquals(listOf("x.jpg"), rest.map { it.name })
    }

    @Test
    fun globMatchingHandlesStarsAndQuestionMarksWithoutBlowingUp() {
        assertTrue(Glob.matches("*.TMP", "a.tmp"))
        assertTrue(Glob.matches("img_?.jpg", "IMG_1.jpg"))
        assertFalse(Glob.matches("img_?.jpg", "IMG_12.jpg"))
        assertTrue(Glob.matches("a*b*c", "aXXbYYc"))
        assertFalse(Glob.matches("*a*a*a*a*a*a*a*a*b", "a".repeat(5000)))
    }

    @Test
    fun walkTimeOfFiftyThousandEntriesOnTheJvmIsMeasured() {
        val tree = FakeDocumentTree.big(50, 20, 50)
        // One warm-up pass, then the number that is reported.
        TreeWalker(tree, WalkConfig(sniff = SniffPolicy.NEVER)).run { }
        val t0 = System.nanoTime()
        var count = 0
        TreeWalker(tree, WalkConfig(sniff = SniffPolicy.NEVER)).run { count += it.files.size }
        val ms = (System.nanoTime() - t0) / 1_000_000
        for (dir in tree.dirs.values) for (e in dir) if (!e.isDirectory) tree.heads[e.documentId] = JPEG_HEAD
        val t1 = System.nanoTime()
        TreeWalker(tree).run { }
        val sniffMs = (System.nanoTime() - t1) / 1_000_000
        println("WALK-TIMING 50000 files, 1051 dirs, JVM: ${ms} ms without sniff, ${sniffMs} ms with a real head sniff per file (fake tree, no I/O)")
        assertEquals(50_000, count)
        assertTrue(ms < 20_000, "50k entries took $ms ms")
    }
}
