package com.fotoxplorr.core.index.walker

import com.fotoxplorr.core.formats.FormatSniffer
import com.fotoxplorr.core.formats.MediaFormat
import kotlin.time.TimeSource

/**
 * WP3.2: a breadth-first, resumable walk of a folder tree. Pure: it knows only [DocumentTree], so
 * the same code runs over a `DocumentsContract` provider on a phone and over a fake in a JVM test.
 *
 * Contract:
 *  - One [DocumentTree.children] call per directory, ever (a resumed walk repeats at most the one
 *    directory that was in flight when the process died).
 *  - [advance] returns the files found AND the next cursor together; persist both in one
 *    transaction and a kill at any point resumes with no skip and no duplicate. If the caller
 *    persists only the cursor after the files, a repeated directory yields the same documents
 *    again, which an upsert by document id absorbs.
 *  - A failed read never looks like an empty folder: the slice is [WalkStatus.Failed], the cursor
 *    still holds the failing directory, and [WalkSlice.mayConcludeMissing] is false.
 *  - This class deletes nothing and cannot: it has no write path. Revoked access is a status.
 *  - Loops: a directory id already queued is not queued again; depth, directory and entry caps bound
 *    a provider that invents fresh ids forever. A cap makes the walk non-exhaustive, not failed.
 */
class TreeWalker(
    private val tree: DocumentTree,
    private val config: WalkConfig = WalkConfig(),
) {
    private val ignoreFiles = config.ignorePatterns.filter { it.isNotBlank() && !it.endsWith("/") }.map(::normalise)
    private val ignoreDirs = config.ignorePatterns.filter { it.isNotBlank() }.map { normalise(it.trimEnd('/')) }

    fun start(): WalkCursor = WalkCursor.start(tree.rootDocumentId)

    /** Continues [from] until [budget] is spent, the tree is drained, a read fails or [cancelled] says stop. */
    fun advance(
        from: WalkCursor,
        budget: WalkBudget = WalkBudget(),
        cancelled: () -> Boolean = { false },
        progress: (WalkProgress) -> Unit = {},
    ): WalkSlice {
        val queue = ArrayDeque(from.pending)
        val queued = HashSet(from.queued)
        var c = from.counters
        val out = ArrayList<WalkedFile>()
        val clock = TimeSource.Monotonic.markNow()
        var dirsThisSlice = 0
        var status: WalkStatus = WalkStatus.Running
        val sniffStats = IntArray(2)

        while (queue.isNotEmpty()) {
            if (cancelled()) {
                status = WalkStatus.Cancelled
                break
            }
            if (dirsThisSlice > 0 && (dirsThisSlice >= budget.maxDirectories || out.size >= budget.maxFiles ||
                    clock.elapsedNow().inWholeMilliseconds >= budget.maxMillis)
            ) break

            val dir = queue.first()
            val entries = try {
                tree.children(dir.documentId)
            } catch (e: TreeAccessException) {
                status = WalkStatus.Failed(e.failure, e.message ?: "The folder could not be read")
                break
            } catch (e: Exception) {
                status = WalkStatus.Failed(TreeFailure.ERROR, e.message ?: "The folder could not be read")
                break
            }
            // Listed: from here the directory is done whatever happens to its children.
            queue.removeFirst()
            dirsThisSlice++
            c = c.copy(directoriesDone = c.directoriesDone + 1)

            var entriesBudgetLeft = config.maxEntries - c.entriesSeen
            val usable = if (entries.size > entriesBudgetLeft) {
                c = c.copy(limitSkipped = c.limitSkipped + (entries.size - maxOf(entriesBudgetLeft, 0)))
                entries.subList(0, maxOf(entriesBudgetLeft, 0))
            } else entries
            entriesBudgetLeft -= usable.size
            c = c.copy(entriesSeen = c.entriesSeen + usable.size)

            val hasNomedia = config.honorNomedia && usable.any { !it.isDirectory && it.name.equals(".nomedia", ignoreCase = true) }
            if (hasNomedia) c = c.copy(nomediaDirectories = c.nomediaDirectories + 1)
            val skipFiles = hasNomedia
            val skipSubdirs = hasNomedia && config.nomediaCascades

            val names = HashMap<String, String>()
            for (e in usable) if (!e.isDirectory) names[e.name.lowercase()] = e.name
            val seenIds = HashSet<String>()

            for (e in usable) {
                if (NameGuard.isHostile(e.name, e.documentId)) {
                    c = c.copy(hostileSkipped = c.hostileSkipped + 1)
                    continue
                }
                if (!seenIds.add(e.documentId)) {
                    c = c.copy(loopsSkipped = c.loopsSkipped + 1)
                    continue
                }
                val shown = NameGuard.display(e.name)
                val path = if (dir.path.isEmpty()) shown else dir.path + "/" + shown
                if (e.isDirectory) {
                    if (skipSubdirs) continue
                    if (isIgnoredDirectory(shown, path, dir.depth)) {
                        c = c.copy(ignoredDirectories = c.ignoredDirectories + 1)
                        continue
                    }
                    if (!queued.add(e.documentId)) {
                        c = c.copy(loopsSkipped = c.loopsSkipped + 1)
                        continue
                    }
                    if (dir.depth + 1 > config.maxDepth || queued.size > config.maxDirectories) {
                        queued.remove(e.documentId)
                        c = c.copy(limitSkipped = c.limitSkipped + 1)
                        continue
                    }
                    queue.addLast(PendingDirectory(e.documentId, path, dir.depth + 1))
                } else {
                    if (skipFiles) continue
                    if (shown.equals(".nomedia", ignoreCase = true) || shown.startsWith("._") ||
                        (config.skipHiddenFiles && shown.startsWith(".")) || matchesAny(ignoreFiles, shown, path)
                    ) {
                        c = c.copy(ignoredFiles = c.ignoredFiles + 1)
                        continue
                    }
                    val file = classify(e, shown, dir, names, sniffStats)
                    if (file == null) {
                        c = c.copy(notMedia = c.notMedia + 1)
                        continue
                    }
                    c = c.copy(
                        mediaFound = c.mediaFound + 1,
                        sniffMismatches = c.sniffMismatches + if (file.sniffMismatch) 1 else 0,
                    )
                    out += file
                }
            }
            c = c.copy(sniffReads = c.sniffReads + sniffStats[0], sniffFailures = c.sniffFailures + sniffStats[1])
            sniffStats[0] = 0
            sniffStats[1] = 0
            progress(WalkProgress(c, queue.size))
        }

        if (status == WalkStatus.Running && queue.isEmpty()) {
            status = WalkStatus.Complete(exhaustive = c.limitSkipped == 0)
        }
        return WalkSlice(out, WalkCursor(from.rootDocumentId, queue.toList(), queued, c), status)
    }

    /** Walks to the end in [sliceBudget] steps, handing every slice to [commit] (persist files and cursor together). Returns the last slice's status. */
    fun run(
        from: WalkCursor = start(),
        sliceBudget: WalkBudget = WalkBudget(maxDirectories = 64, maxFiles = 2_000),
        cancelled: () -> Boolean = { false },
        progress: (WalkProgress) -> Unit = {},
        commit: (WalkSlice) -> Unit,
    ): WalkStatus {
        var cursor = from
        while (true) {
            val slice = advance(cursor, sliceBudget, cancelled, progress)
            commit(slice)
            cursor = slice.cursor
            if (slice.status != WalkStatus.Running) return slice.status
        }
    }

    private fun classify(e: DocumentEntry, shown: String, dir: PendingDirectory, siblings: Map<String, String>, sniffStats: IntArray): WalkedFile? {
        val label = MediaFormat.classify(e.mimeType, shown)
        val ext = shown.substringAfterLast('.', "").lowercase()
        val mime = e.mimeType.trim().lowercase()
        val mediaMime = mime.startsWith("image/") || mime.startsWith("video/")
        val unknownType = label == MediaFormat.Other && (mime.isEmpty() || mime == "application/octet-stream" || mediaMime || ext.isEmpty())
        val wantSniff = config.sniff == SniffPolicy.MEDIA_AND_UNKNOWN && e.sizeBytes != 0L &&
            (label != MediaFormat.Other || unknownType) && (!e.isVirtual || config.sniffVirtualDocuments)

        var sniffed: MediaFormat? = null
        if (wantSniff) {
            sniffStats[0]++
            val head = try {
                tree.readHead(e.documentId, config.headBytes)
            } catch (x: TreeAccessException) {
                null
            } catch (x: Exception) {
                null
            }
            if (head == null) sniffStats[1]++ else sniffed = FormatSniffer.sniff(head)
        }
        // A TIFF-shaped RAW (NEF, ARW, DNG) sniffs as TIFF; the name is the better witness there.
        val trustedSniff = if (label is MediaFormat.Raw && sniffed == MediaFormat.Tiff) null else sniffed
        val format = trustedSniff ?: label
        val mismatch = trustedSniff != null && trustedSniff != label
        if (format == MediaFormat.Other && !mediaMime) return null

        val stem = shown.substringBeforeLast('.', shown)
        val sidecars = listOfNotNull(siblings["$stem.xmp".lowercase()], siblings["$shown.xmp".lowercase()]).distinct()
        return WalkedFile(
            documentId = e.documentId,
            parentDocumentId = dir.documentId,
            name = shown,
            directoryPath = dir.path,
            mimeType = e.mimeType,
            sizeBytes = if (e.sizeBytes < 0) -1L else e.sizeBytes,
            lastModifiedMillis = if (e.lastModifiedMillis < 0) 0L else e.lastModifiedMillis,
            flags = e.flags,
            labelledFormat = label,
            sniffedFormat = sniffed,
            format = format,
            sniffMismatch = mismatch,
            sidecarNames = sidecars,
            depth = dir.depth,
        )
    }

    private fun isIgnoredDirectory(name: String, path: String, parentDepth: Int): Boolean {
        val lower = name.lowercase()
        return lower in WalkConfig.BUILT_IN_IGNORED_DIRECTORIES ||
            (parentDepth == 0 && lower == WalkConfig.ANDROID_DATA_DIRECTORY) ||
            (config.skipHiddenDirectories && name.startsWith(".")) ||
            matchesAny(ignoreDirs, name, path)
    }

    private fun matchesAny(patterns: List<String>, name: String, path: String): Boolean =
        patterns.any { if ('/' in it) Glob.matches(it, path) else Glob.matches(it, name) }

    private fun normalise(pattern: String): String = pattern.trim().trimStart('/')
}

/** Reported after every directory. */
data class WalkProgress(val counters: WalkCounters, val pendingDirectories: Int)
