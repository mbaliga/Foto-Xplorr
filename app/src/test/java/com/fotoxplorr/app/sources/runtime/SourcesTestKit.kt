package com.fotoxplorr.app.sources.runtime

import android.content.Context
import androidx.room.Room
import androidx.sqlite.driver.AndroidSQLiteDriver
import com.fotoxplorr.app.sources.saf.EnrichmentOutcome
import com.fotoxplorr.app.sources.saf.GrantOutcome
import com.fotoxplorr.app.sources.saf.SourceFacts
import com.fotoxplorr.core.db.sources.FotozSourcesDatabase
import com.fotoxplorr.core.index.walker.DocumentEntry
import com.fotoxplorr.core.index.walker.DocumentFlags
import com.fotoxplorr.core.index.walker.DocumentTree
import com.fotoxplorr.core.index.walker.TreeAccessException
import com.fotoxplorr.core.index.walker.TreeFailure
import kotlinx.coroutines.Dispatchers

/** An in-memory `fotoz-sources.db`, on the framework driver Robolectric can run (the device uses the bundled one). */
fun inMemorySourcesDb(context: Context): FotozSourcesDatabase =
    Room.inMemoryDatabaseBuilder<FotozSourcesDatabase>(context)
        .setDriver(AndroidSQLiteDriver())
        .setQueryCoroutineContext(Dispatchers.IO)
        .build()

/** A grant registry the test steers: which trees are held, what take refuses, what was forgotten. */
class FakeGrants(vararg held: String) : SourceGrants {
    val heldUris = held.toMutableSet()
    val forgotten = ArrayList<String>()
    val otherFeatures = HashSet<String>()
    var refuse: String? = null
    override fun isHeld(treeUri: String) = treeUri in heldUris
    override fun take(treeUri: String): GrantOutcome {
        refuse?.let { return GrantOutcome.Refused(it) }
        heldUris += treeUri
        return GrantOutcome.Taken(heldUris.size, 512)
    }
    override fun forget(treeUri: String): Boolean {
        forgotten += treeUri
        heldUris -= treeUri
        return true
    }
    override fun usedByOtherFeatures(treeUri: String) = treeUri in otherFeatures
}

/**
 * A document tree in memory: [dirs] maps a directory id to its children. Counts every call so a test
 * can say "one listing per directory" and "nothing was read while revoked". Has no write path at all.
 */
class MemoryTree(
    override val rootDocumentId: String = "root",
    val dirs: MutableMap<String, List<DocumentEntry>> = HashMap(),
) : DocumentTree {
    var listings = 0
    val listed = ArrayList<String>()
    /** Throws this for [failAt] once, to stand in for a drive pulled or a grant lost mid-walk. */
    var failAt: String? = null
    var failure: TreeFailure = TreeFailure.UNREACHABLE
    /** Runs before each listing (a test revokes the grant here). */
    var beforeListing: (String) -> Unit = {}

    override fun children(directoryId: String): List<DocumentEntry> {
        beforeListing(directoryId)
        listings++
        listed += directoryId
        if (directoryId == failAt) throw TreeAccessException(failure, "no")
        return dirs[directoryId] ?: throw TreeAccessException(TreeFailure.ERROR, "missing $directoryId")
    }

    override fun readHead(documentId: String, maxBytes: Int): ByteArray? = null

    companion object {
        fun file(id: String, name: String = "$id.jpg", size: Long = 1000, modified: Long = 1_700_000_000_000L) =
            DocumentEntry(id, name, "image/jpeg", false, size, modified, 0)

        fun dir(id: String, name: String = id) = DocumentEntry(id, name, "vnd.android.document/directory", true, -1, 0, DocumentFlags.SUPPORTS_THUMBNAIL and 0)

        /** [files] jpgs spread over a three-level tree: [fanout] folders per level, files at every folder. */
        fun generate(files: Int, fanout: Int = 6): MemoryTree {
            val t = MemoryTree()
            val ids = ArrayList<String>()
            ids += "root"
            var level = listOf("root")
            repeat(3) {
                val next = ArrayList<String>()
                for (parent in level) for (i in 0 until fanout) next += "$parent/d$i"
                ids += next
                level = next
            }
            val perDir = (files + ids.size - 1) / ids.size
            var made = 0
            for (id in ids) {
                val children = ArrayList<DocumentEntry>()
                for (sub in ids.filter { it.substringBeforeLast('/', "") == id && it != id && it.startsWith("$id/") }) {
                    children += dir(sub, sub.substringAfterLast('/'))
                }
                var n = 0
                while (n < perDir && made < files) {
                    children += file("$id#f$n", "IMG_${made}.jpg", 1000L + made)
                    n++; made++
                }
                t.dirs[id] = children
            }
            return t
        }
    }
}

/** Sizes every file 400 x 300 with a date, counting what it read. */
class FakeFacts(val outcome: EnrichmentOutcome = EnrichmentOutcome.READ, val bytesEach: Long = 100) {
    var calls = 0
    val failFor = HashSet<String>()
    val reader = FactsReader { _, a ->
        calls++
        if (a.documentId in failFor) SourceFacts(EnrichmentOutcome.UNREADABLE, bytesRead = bytesEach)
        else SourceFacts(outcome, 400, 300, 1, 1_650_000_000_000L, null, bytesEach)
    }
}
