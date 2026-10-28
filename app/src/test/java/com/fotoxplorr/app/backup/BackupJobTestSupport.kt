package com.fotoxplorr.app.backup

import java.io.ByteArrayInputStream
import java.io.File
import java.io.FilterOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest

/**
 * A backup folder that is a plain directory, with the faults a real one has: a listing that lies,
 * a disk that fills, a provider that renames. Names are the files' own, so a test can read them.
 */
internal class DirectoryDestination(val dir: File) : BackupDestination {
    override val label: String = dir.name

    /** Bytes the folder will accept in total before every write fails with "No space left on device". */
    var capacityBytes: Long = Long.MAX_VALUE

    /** When set, [list] answers this instead of the truth (a listing that failed quietly). */
    var listingOverride: List<DestinationFile>? = null

    /** When set, [list] throws it. */
    var listFailure: IOException? = null

    /** When set, [open] of a name throws this. */
    var openFailure: ((String) -> IOException?)? = null

    /** Names whose writes fail with a full disk, whatever the capacity. */
    var failWritesTo: Set<String> = emptySet()

    var freeReported: Long? = null
    val created = ArrayList<String>()
    val deleted = ArrayList<String>()

    private fun used(): Long = dir.listFiles().orEmpty().sumOf { it.length() }

    override fun list(): List<DestinationFile> {
        listFailure?.let { throw it }
        listingOverride?.let { return it }
        return dir.listFiles().orEmpty().filter { it.isFile }.map { DestinationFile(it.name, it.length()) }
    }

    override fun open(name: String): InputStream {
        openFailure?.invoke(name)?.let { throw it }
        val file = File(dir, name)
        if (!file.isFile) throw IOException("This file is not in the folder")
        return file.inputStream()
    }

    override fun create(name: String): OutputStream {
        val file = File(dir, name)
        if (file.exists()) throw IOException("A file with this name is already in the folder")
        created += name
        val raw = file.outputStream()
        return object : FilterOutputStream(raw) {
            override fun write(b: Int) {
                check(1)
                out.write(b)
            }

            override fun write(b: ByteArray, off: Int, len: Int) {
                check(len)
                out.write(b, off, len)
            }

            private fun check(extra: Int) {
                if (name in failWritesTo || used() + extra > capacityBytes) throw IOException("write failed: ENOSPC (No space left on device)")
            }
        }
    }

    override fun sizeOf(name: String): Long = File(dir, name).takeIf { it.isFile }?.length() ?: -1L

    override fun delete(name: String): Boolean {
        deleted += name
        return File(dir, name).delete()
    }

    override fun freeBytes(): Long? = freeReported

    fun names(): Set<String> = dir.listFiles().orEmpty().map { it.name }.toSet()

    fun bytes(name: String): ByteArray = File(dir, name).readBytes()

    fun sha(name: String): String = MessageDigest.getInstance("SHA-256").digest(bytes(name)).joinToString("") { "%02x".format(it) }

    /** name -> content hash of every file, to prove a later run changed none of them. */
    fun snapshot(): Map<String, String> = names().associateWith { sha(it) }
}

/** Photos for the writer, with the surprises a phone has. */
internal class FakeSource(private val files: MutableMap<Long, ByteArray> = HashMap()) : BackupSource {
    var original = true

    /** Called as a file is opened; a test cancels the job from here. */
    var onOpen: (BackupCandidate) -> Unit = {}

    /** What the provider says the length is, if not the truth. */
    var reportedLength: (Long, ByteArray) -> Long = { _, bytes -> bytes.size.toLong() }

    /** Wraps the stream, to make it fail or run short. */
    var wrap: (Long, InputStream) -> InputStream = { _, stream -> stream }

    val missing = HashSet<Long>()
    val opened = ArrayList<Long>()

    fun put(id: Long, bytes: ByteArray): FakeSource = also { files[id] = bytes }

    fun bytes(id: Long): ByteArray = files.getValue(id)

    override fun open(candidate: BackupCandidate): OpenedSource? {
        onOpen(candidate)
        if (candidate.mediaId in missing) return null
        val bytes = files[candidate.mediaId] ?: return null
        opened += candidate.mediaId
        return OpenedSource(wrap(candidate.mediaId, ByteArrayInputStream(bytes)), reportedLength(candidate.mediaId, bytes), original)
    }
}

internal object JobFixtures {
    fun candidate(id: Long, name: String = "IMG_$id.jpg", size: Long, path: String = "DCIM/Camera/", modified: Long = 1_000 + id, flags: (BackupCandidate) -> BackupCandidate = { it }) =
        flags(
            BackupCandidate(
                mediaId = id,
                displayName = name,
                relativePath = path,
                mimeType = "image/jpeg",
                sizeBytes = size,
                dateTakenMillis = 1_700_000_000_000L + id,
                dateModifiedSeconds = modified,
            ),
        )

    /** [count] photos of [size] bytes each, with the matching candidates. */
    fun library(count: Int, size: Int = 300, source: FakeSource = FakeSource()): Pair<List<BackupCandidate>, FakeSource> {
        val candidates = (1..count).map { id ->
            source.put(id.toLong(), BackupTestSupport.plaintext(size, seed = id))
            candidate(id.toLong(), size = size.toLong())
        }
        return candidates to source
    }

    /** Small packs under the tiny segment size: a handful of files per pack. */
    val tinyLimits = PackLimits(targetBytes = 1_400, hardCapBytes = 200_000, segmentSize = BackupTestSupport.SEGMENT)
}
