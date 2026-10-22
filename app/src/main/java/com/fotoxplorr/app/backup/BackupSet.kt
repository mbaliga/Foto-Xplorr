package com.fotoxplorr.app.backup

import java.io.BufferedInputStream
import java.io.IOException
import java.io.InputStreamReader
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/**
 * What a folder holds before any key is involved, read from the plaintext header of one backup
 * file. The header names the set's salt and iteration count, which is what the passphrase is
 * combined with; nothing else is learned.
 */
sealed interface SetState {
    /** No backup file in the folder: a new set will be started here. */
    data object Empty : SetState

    /** A set is here. [header] is from its newest manifest, or failing that any file of it. */
    class Existing(val header: BackupHeader, val fileCount: Int) : SetState

    /** A backup file is here that this version cannot read. [reason] is a sentence for the person. */
    class Unreadable(val reason: String) : SetState
}

/**
 * Everything a run needs to know about the destination, decided with the right keys in hand.
 *
 * - [manifest]: the newest manifest that authenticated and validated in full, or null.
 *   [skippedManifests] counts newer ones that did not (a manifest the last run died while writing).
 * - [recovered]: indexes of closed packs that no manifest lists yet (a run that ended before its
 *   manifest). Their entries are known, so the run carries on from them.
 * - [leftovers]: files Fotoz cannot use: a pack with no index (the run died inside it), an index
 *   that does not open or whose pack is gone. Never deleted by a run; the person can remove them.
 * - [reservedPackNumbers]: numbers in use by a name with no pack beside it.
 * - [foreignFiles]: backup files that belong to some other set, or that this version cannot read
 *   (a newer one's). They are not this run's to use and never leftovers: nothing offers to delete them.
 */
class LoadedSet(
    val listing: List<DestinationFile>,
    val manifest: BackupManifest?,
    val skippedManifests: Int,
    val highestGeneration: Int,
    val recovered: List<PackIndex>,
    val leftovers: List<DestinationFile>,
    val reservedPackNumbers: Set<Int>,
    val foreignFiles: List<DestinationFile> = emptyList(),
) {
    val known: List<ManifestEntry> get() = manifest?.entries.orEmpty() + recovered.flatMap { it.entries }
    val nextGeneration: Int get() = highestGeneration + 1

    /** The pack files that exist in the folder right now, which is what the planner calls "existing". */
    val packFileNames: Set<String> get() = listing.mapNotNullTo(HashSet()) { file -> file.name.takeIf { BackupLayout.isPackName(it) } }

    val leftoverBytes: Long get() = leftovers.sumOf { it.sizeBytes.coerceAtLeast(0) }
}

object BackupSetLoader {
    private const val PROOF_PACKS = 3

    /**
     * Looks at the folder's files and reads one header. Fast: no key, no KDF. Only manifests and
     * packs make a folder "a set": an index with neither beside it is a leftover and starts nothing,
     * and neither does a pack whose header cannot be read (a run that died as it created it).
     */
    fun inspect(destination: BackupDestination): SetState {
        val names = destination.list().map { it.name }
        val manifests = BackupLayout.manifestsNewestFirst(names)
        val packs = names.filter { BackupLayout.isPackName(it) }.sorted()
        var unreadableManifests = 0
        for (name in manifests) {
            when (val read = headerOf(destination, name)) {
                is HeaderRead.Ok -> return SetState.Existing(read.header, manifests.size + packs.size)
                HeaderRead.Newer -> return SetState.Unreadable(NEWER_VERSION)
                HeaderRead.Bad -> unreadableManifests++
            }
        }
        if (unreadableManifests > 0) return SetState.Unreadable(UNREADABLE_FILES)
        for (name in packs) {
            when (val read = headerOf(destination, name)) {
                is HeaderRead.Ok -> return SetState.Existing(read.header, packs.size)
                HeaderRead.Newer -> return SetState.Unreadable(NEWER_VERSION)
                HeaderRead.Bad -> Unit
            }
        }
        return SetState.Empty
    }

    private sealed interface HeaderRead {
        class Ok(val header: BackupHeader) : HeaderRead

        data object Newer : HeaderRead

        data object Bad : HeaderRead
    }

    private fun headerOf(destination: BackupDestination, name: String): HeaderRead = try {
        HeaderRead.Ok(destination.open(name).use { BackupStreams.readHeader(it) })
    } catch (e: BackupException.UnsupportedVersion) {
        HeaderRead.Newer
    } catch (e: BackupException) {
        HeaderRead.Bad
    }

    private const val NEWER_VERSION = "This folder holds a backup from a newer version of Fotoz. Update Fotoz to use it."
    private const val UNREADABLE_FILES = "This folder holds backup files that cannot be read. Choose another folder for a new backup."

    /**
     * Opens the set with [keys]: lists, reads the newest manifest that is whole, and recovers the
     * entries of finished packs no manifest lists. Throws [BackupException.WrongPassphraseOrDamaged]
     * when nothing in a folder that holds backup files authenticates, which is also what a wrong
     * passphrase looks like; that must stop a run before anything is written with those keys.
     *
     * A file whose header is a backup's but of another set (or another kind, or a version this one
     * cannot read) proves the folder holds a backup these keys do not open. When nothing opened,
     * that is [BackupException.AnotherBackupHere]: the listing the person chose "a new backup" on
     * may have been a partial one, and a new set written beside the real one would hide it.
     */
    suspend fun load(destination: BackupDestination, keys: BackupKeys): LoadedSet {
        val listing = destination.list()
        val sizes = listing.associate { it.name to it.sizeBytes }
        val names = sizes.keys
        val manifestNames = BackupLayout.manifestsNewestFirst(names)
        val highest = manifestNames.firstOrNull()?.let { BackupLayout.manifestGeneration(it) } ?: 0

        var manifest: BackupManifest? = null
        var skipped = 0
        var firstFailure: IOException? = null
        var authenticated = false
        var unprovenOurs = false
        val foreign = LinkedHashMap<String, DestinationFile>()
        fun markForeign(name: String) {
            foreign[name] = DestinationFile(name, sizes[name] ?: -1L)
        }
        for (name in manifestNames) {
            currentCoroutineContext().ensureActive()
            try {
                manifest = readManifest(destination, name, keys)
                authenticated = true
                break
            } catch (e: IOException) {
                if (e is BackupException.WrongPassphraseOrDamaged) unprovenOurs = true
                if (isForeignFailure(e)) markForeign(name)
                // A manifest that stopped half way (the run died while writing it), a damaged one, or
                // not a manifest at all: the generation before it is a whole snapshot, and the
                // indexes below cover anything newer. A failure of the folder itself is not damage:
                // falling back to an older snapshot then would write a newer one that forgets files.
                if (!isDamage(e)) throw e
                skipped++
                authenticated = authenticated || e is ManifestFormatException || e is BackupException.Damaged
                firstFailure = firstFailure ?: e
            }
        }

        val listedPacks = manifest?.packs?.mapTo(HashSet()) { it.name }.orEmpty()
        val recovered = ArrayList<PackIndex>()
        val leftovers = ArrayList<DestinationFile>()
        val reserved = HashSet<Int>()
        val indexNames = names.filter { BackupLayout.isIndexName(it) }.sorted()
        for (indexName in indexNames) {
            currentCoroutineContext().ensureActive()
            val number = BackupLayout.indexNumber(indexName) ?: continue
            val packName = BackupLayout.packName(number)
            if (packName in listedPacks) continue
            if (packName !in names) {
                reserved += number
                if (isForeign(destination, indexName, keys, BackupKind.PACK_INDEX)) {
                    markForeign(indexName)
                } else {
                    leftovers += DestinationFile(indexName, sizes[indexName] ?: -1L)
                }
                continue
            }
            val index = try {
                readIndex(destination, indexName, keys).also { authenticated = true }
            } catch (e: IOException) {
                if (!isDamage(e)) throw e
                if (e is BackupException.WrongPassphraseOrDamaged) unprovenOurs = true
                if (isForeignFailure(e)) markForeign(indexName)
                authenticated = authenticated || e is ManifestFormatException || e is BackupException.Damaged
                firstFailure = firstFailure ?: e
                null
            }
            val packLength = sizes[packName] ?: -1L
            if (index != null && index.pack.name == packName && (packLength < 0 || packLength == index.pack.fileBytes)) {
                recovered += index
            } else if (indexName !in foreign) {
                leftovers += DestinationFile(indexName, sizes[indexName] ?: -1L)
            }
        }

        val covered = listedPacks + recovered.map { it.pack.name }
        for (name in names.filter { BackupLayout.isPackName(it) }.sorted()) {
            if (name in covered) continue
            currentCoroutineContext().ensureActive()
            // A pack with no usable index is a leftover only if it is this set's (or not a backup file
            // at all); one that belongs to another set is that set's data, not ours to offer for deletion.
            if (name in foreign || isForeign(destination, name, keys, BackupKind.PACK)) {
                markForeign(name)
            } else {
                leftovers += DestinationFile(name, sizes[name] ?: -1L)
            }
        }

        if (!authenticated) {
            // No manifest or index opened with these keys. A pack's first segment can still prove the
            // key (the first few packs are tried; ones from another set or with no readable header are
            // not this set's and say nothing).
            for (pack in names.filter { BackupLayout.isPackName(it) }.sorted().take(PROOF_PACKS)) {
                currentCoroutineContext().ensureActive()
                try {
                    destination.open(pack).use { BackupStreams.open(it, keys, BackupKind.PACK) }
                    authenticated = true
                    break
                } catch (e: IOException) {
                    if (!isDamage(e)) throw e
                    if (e is BackupException.WrongPassphraseOrDamaged) unprovenOurs = true
                    if (isForeignFailure(e)) markForeign(pack)
                    firstFailure = firstFailure ?: e
                }
            }
        }
        // Something of this set is here and nothing of it opened: a wrong passphrase (or damage, which
        // GCM cannot tell apart). Nothing may be written with keys that were never proven.
        if (!authenticated && unprovenOurs) throw (firstFailure as? BackupException) ?: BackupException.WrongPassphraseOrDamaged()
        // Nothing opened, and what is here says it is a backup of someone else's keys. Not damage and
        // not a wrong guess at this set's passphrase: never write a second set beside it.
        if (!authenticated && foreign.isNotEmpty()) throw BackupException.AnotherBackupHere()

        return LoadedSet(
            listing = listing,
            manifest = manifest,
            skippedManifests = skipped,
            highestGeneration = highest,
            recovered = recovered,
            leftovers = leftovers,
            reservedPackNumbers = reserved,
            foreignFiles = foreign.values.toList(),
        )
    }

    /** Failures that mean the file is bad, as opposed to the folder being unreadable. */
    private fun isDamage(e: IOException): Boolean = e is BackupException || e is ManifestFormatException

    /**
     * Failures that come from a readable backup header which is not for these keys: another set, a
     * file of another kind, or a format this version does not know. Damage inside the body is not
     * among them, and neither is a file that is not a backup at all ([BackupException.NotABackup]).
     */
    private fun isForeignFailure(e: IOException): Boolean = e is BackupException && e !is BackupException.NotABackup &&
        e !is BackupException.WrongPassphraseOrDamaged && e !is BackupException.Damaged

    /** True when [name] starts with a genuine backup header that is not [kind] of the set [keys] open. */
    private fun isForeign(destination: BackupDestination, name: String, keys: BackupKeys, kind: BackupKind): Boolean = try {
        val header = destination.open(name).use { BackupStreams.readHeader(it) }
        header.kind != kind || !keys.matches(header)
    } catch (e: BackupException) {
        e !is BackupException.NotABackup
    }

    /**
     * The sections (tags, locks, pins, edits) of the newest manifest older than [loaded]'s that opens
     * and is whole, or null when there is none. Reads the file a line at a time and keeps only the
     * sections, so the older snapshot's file list is never held in memory. A restore uses it when
     * the newest snapshot carries less than the one before.
     */
    suspend fun olderSections(destination: BackupDestination, keys: BackupKeys, loaded: LoadedSet): List<ManifestSection>? {
        val newest = loaded.manifest?.header?.generation ?: return null
        val older = BackupLayout.manifestsNewestFirst(loaded.listing.map { it.name })
            .filter { (BackupLayout.manifestGeneration(it) ?: Int.MAX_VALUE) < newest }
        for (name in older) {
            currentCoroutineContext().ensureActive()
            try {
                val sections = ArrayList<ManifestSection>()
                BufferedInputStream(destination.open(name), 1 shl 16).use { raw ->
                    val opened = BackupStreams.open(raw, keys, BackupKind.MANIFEST)
                    InputStreamReader(opened.plaintext, Charsets.UTF_8).use { reader ->
                        ManifestReader.read(reader) { record -> if (record is ManifestRecord.Section) sections += record.section }
                    }
                }
                return sections
            } catch (e: IOException) {
                if (!isDamage(e)) throw e
            }
        }
        return null
    }

    private fun readManifest(destination: BackupDestination, name: String, keys: BackupKeys): BackupManifest =
        BufferedInputStream(destination.open(name), 1 shl 16).use { raw ->
            val opened = BackupStreams.open(raw, keys, BackupKind.MANIFEST)
            InputStreamReader(opened.plaintext, Charsets.UTF_8).use { ManifestReader.readAll(it) }
        }

    private fun readIndex(destination: BackupDestination, name: String, keys: BackupKeys): PackIndex =
        BufferedInputStream(destination.open(name), 1 shl 16).use { raw ->
            val opened = BackupStreams.open(raw, keys, BackupKind.PACK_INDEX)
            InputStreamReader(opened.plaintext, Charsets.UTF_8).use { PackIndexReader.read(it) }
        }
}

/**
 * Removes the leftovers the person was shown, and only those that are still leftovers. The folder
 * is listed and classified again first: between the preflight and the confirm a cloud-backed
 * folder may have finished syncing, and a file that was a leftover in the first listing can be a
 * pack of the real set in the second. A name that is no longer a leftover is kept, and so is any
 * file of another set ([LoadedSet.foreignFiles]).
 */
object LeftoverRemoval {
    /** Names actually deleted. Throws what [BackupSetLoader.load] throws, before anything is deleted. */
    suspend fun remove(destination: BackupDestination, keys: BackupKeys, shown: Collection<String>): List<String> {
        val fresh = BackupSetLoader.load(destination, keys)
        val stillLeftovers = fresh.leftovers.mapTo(HashSet()) { it.name }
        val removed = ArrayList<String>()
        for (name in shown) {
            currentCoroutineContext().ensureActive()
            if (name in stillLeftovers && BackupLayout.isBackupFileName(name) && destination.delete(name)) removed += name
        }
        return removed
    }
}
