package com.fotoxplorr.app.backup

import java.io.BufferedInputStream
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

enum class ProblemKind {
    /** The manifest lists this pack and the folder does not have it. */
    PACK_MISSING,

    /** The folder's length for the pack is not the length the manifest recorded. */
    PACK_SIZE,

    /** The pack stopped authenticating part way: damage, truncation or tampering. */
    PACK_DAMAGED,

    /** A file's bytes are not the bytes the manifest recorded for it. */
    FILE_MISMATCH,

    /** The manifest lists a file the readable part of its pack does not contain. */
    FILE_NOT_FOUND,
}

class VerifyProblem(val kind: ProblemKind, val packName: String, val fileName: String?, val detail: String)

class VerifyReport(
    val generation: Int,
    val packsChecked: Int,
    val filesVerified: Int,
    val bytesChecked: Long,
    val problems: List<VerifyProblem>,
    /** Newer manifests that did not open (the last run died while writing one). */
    val skippedManifests: Int,
    /** Finished packs that no readable manifest lists (the last run's), checked through their indexes; included in [packsChecked]. */
    val recoveredPacks: Int = 0,
    /** Files in the folder Fotoz cannot use (a pack with no index, an index that does not open); they were not checked. */
    val leftovers: Int = 0,
) {
    val isClean: Boolean get() = problems.isEmpty()

    /** True when "everything is intact" would claim more than the check saw: it fell back, covered packs no snapshot lists, or skipped files. */
    val hasCaveats: Boolean get() = skippedManifests > 0 || recoveredPacks > 0 || leftovers > 0
}

/**
 * "Check backup": reads every pack of the newest manifest through the authenticating stream (so a
 * flipped bit, a truncation or a swapped segment fails) and compares each file's SHA-256 and
 * length with the manifest. Everything is held in memory a buffer at a time and discarded. No
 * plaintext is written anywhere, which is why this does not "test restore" into a folder
 * (ADR-014): a restore of a protected photo into the cache would leave it on disk.
 *
 * [loaded] is the set as the caller already opened it (the passphrase check), so the manifest is
 * not decrypted twice. It proves the files are intact and the passphrase is right.
 *
 * Two kinds of pack are read: those the newest readable manifest lists, and the finished packs of
 * a run that died before its manifest ([LoadedSet.recovered], known from their encrypted index).
 * When the newest manifest could not be read the check fell back to the one before, which is why
 * the report carries [VerifyReport.skippedManifests] and [VerifyReport.recoveredPacks]: the person
 * is told what the check stood on. A pack that is damaged is reported once, with the files after
 * the damage counted as not found, not as individually bad.
 */
object BackupVerifier {
    suspend fun verify(
        destination: BackupDestination,
        keys: BackupKeys,
        onProgress: (Float) -> Unit = {},
        io: CoroutineDispatcher = Dispatchers.IO,
        loaded: LoadedSet? = null,
    ): VerifyReport = withContext(io) {
        try {
            check(destination, keys, onProgress, loaded ?: BackupSetLoader.load(destination, keys))
        } finally {
            keys.close()
        }
    }

    private suspend fun check(destination: BackupDestination, keys: BackupKeys, onProgress: (Float) -> Unit, loaded: LoadedSet): VerifyReport {
        val manifest = loaded.manifest
        if (manifest == null && loaded.recovered.isEmpty()) {
            throw BackupRefused("This folder has no finished backup in it yet, so there is nothing to check.")
        }

        val sizes = loaded.listing.associate { it.name to it.sizeBytes }
        val byPack = HashMap<String, List<ManifestEntry>>()
        manifest?.entries?.groupBy { it.packName }?.let { byPack.putAll(it) }
        // A recovered pack is not in the manifest, so its files are the ones its own index lists.
        loaded.recovered.forEach { byPack[it.pack.name] = it.entries }
        val packs = (manifest?.packs.orEmpty() + loaded.recovered.map { it.pack }).distinctBy { it.name }.sortedBy { it.name }
        val totalBytes = packs.sumOf { it.fileBytes }.coerceAtLeast(1)
        val problems = ArrayList<VerifyProblem>()
        var verified = 0
        var bytes = 0L
        var checked = 0
        var passed = 0L
        val buffer = ByteArray(BUFFER)

        for (pack in packs) {
            currentCoroutineContext().ensureActive()
            val entries = byPack[pack.name].orEmpty()
            val listed = sizes[pack.name]
            if (listed == null) {
                problems += VerifyProblem(ProblemKind.PACK_MISSING, pack.name, null, "The pack is not in the folder")
                entries.forEach { problems += VerifyProblem(ProblemKind.FILE_NOT_FOUND, pack.name, it.displayName, "Its pack is missing") }
                passed += pack.fileBytes
                onProgress((passed.toDouble() / totalBytes).toFloat().coerceAtMost(1f))
                continue
            }
            checked++
            if (listed >= 0 && listed != pack.fileBytes) {
                problems += VerifyProblem(ProblemKind.PACK_SIZE, pack.name, null, "The pack is $listed bytes, the backup recorded ${pack.fileBytes}")
            }
            val wanted = entries.associateBy { it.entryId }
            val seen = HashSet<Long>()
            var damaged: String? = null
            try {
                BufferedInputStream(destination.open(pack.name), 1 shl 16).use { raw ->
                    val opened = BackupStreams.open(raw, keys, BackupKind.PACK)
                    bytes += readRecords(opened.plaintext, buffer, wanted, seen, pack.name, problems) { verified++ }
                }
            } catch (e: IOException) {
                if (e !is BackupException && e !is ManifestFormatException) throw e
                damaged = when (e) {
                    is BackupException.WrongPassphraseOrDamaged -> "The start of the pack does not open"
                    else -> "The pack is damaged or cut short"
                }
            }
            if (damaged != null) {
                problems += VerifyProblem(ProblemKind.PACK_DAMAGED, pack.name, null, damaged)
            }
            for (entry in entries) {
                if (entry.entryId !in seen) {
                    problems += VerifyProblem(ProblemKind.FILE_NOT_FOUND, pack.name, entry.displayName, "Not found in the readable part of its pack")
                }
            }
            passed += pack.fileBytes
            onProgress((passed.toDouble() / totalBytes).toFloat().coerceAtMost(1f))
        }
        return VerifyReport(
            generation = manifest?.header?.generation ?: 0,
            packsChecked = checked,
            filesVerified = verified,
            bytesChecked = bytes,
            problems = problems,
            skippedManifests = loaded.skippedManifests,
            recoveredPacks = loaded.recovered.size,
            leftovers = loaded.leftovers.size,
        )
    }

    /** Walks the records of one pack; returns the file bytes hashed. Throws what the stream throws. */
    private suspend fun readRecords(
        input: InputStream,
        buffer: ByteArray,
        wanted: Map<Long, ManifestEntry>,
        seen: MutableSet<Long>,
        packName: String,
        problems: MutableList<VerifyProblem>,
        onVerified: () -> Unit,
    ): Long {
        var hashed = 0L
        val header = ByteArray(PackRecord.HEADER_BYTES)
        val trailer = ByteArray(PackRecord.TRAILER_BYTES)
        while (true) {
            currentCoroutineContext().ensureActive()
            val first = input.read(header, 0, 1)
            if (first < 0) return hashed
            readFully(input, header, 1)
            val (entryId, size) = PackRecord.decodeHeader(header)
                ?: throw BackupException.Damaged(IOException("A pack record has an impossible header"))
            val digest = MessageDigest.getInstance("SHA-256")
            var left = size
            while (left > 0) {
                currentCoroutineContext().ensureActive()
                val n = input.read(buffer, 0, minOf(buffer.size.toLong(), left).toInt())
                if (n < 0) throw BackupException.Damaged(IOException("A pack record is cut short"))
                digest.update(buffer, 0, n)
                left -= n
            }
            hashed += size
            readFully(input, trailer, 0)
            val computed = digest.digest()
            val entry = wanted[entryId]
            if (entry == null) continue // an older version of a changed file, or a file that failed: not in the manifest
            seen += entryId
            val sha = computed.joinToString("") { "%02x".format(it.toInt() and 0xFF) }
            when {
                !MessageDigest.isEqual(computed, trailer) ->
                    problems += VerifyProblem(ProblemKind.FILE_MISMATCH, packName, entry.displayName, "Its bytes do not match the checksum stored with them")
                size != entry.sizeBytes || sha != entry.sha256 ->
                    problems += VerifyProblem(ProblemKind.FILE_MISMATCH, packName, entry.displayName, "Its bytes are not the ones the backup recorded")
                else -> onVerified()
            }
        }
    }

    private fun readFully(input: InputStream, into: ByteArray, from: Int) {
        var filled = from
        while (filled < into.size) {
            val n = input.read(into, filled, into.size - filled)
            if (n < 0) throw BackupException.Damaged(IOException("A pack record is cut short"))
            filled += n
        }
    }

    private const val BUFFER = 256 * 1024
}
