package com.fotoxplorr.app.backup

import com.fotoxplorr.app.backup.BackupTestSupport.newKeys
import com.fotoxplorr.app.editor.JsonValue
import com.fotoxplorr.app.editor.parseJson
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking

/**
 * A phone for the restore tests: photos by id, locks, and a writer that behaves like MediaStore
 * where it matters (a new id for every file, nothing visible until it is committed) with the
 * faults a real one has (a refused create, a failed write, a full disk).
 */
internal class FakePhone(override val canChooseFolder: Boolean = true) : RestoreTarget {
    class Photo(
        val id: Long,
        val name: String,
        val dir: String,
        val bytes: ByteArray,
        val taken: Long = 0,
        val modified: Long = 500,
    )

    val photos = LinkedHashMap<Long, Photo>()
    val locked = HashSet<String>()
    var nextId = 9_000L

    /** Everything that happened, in order, shared with [RecordingApplier] so a test can assert order. */
    val events = ArrayList<String>()
    val aborted = ArrayList<String>()

    var refuseCreate: (RestoreItem) -> Boolean = { false }

    /** A write to this file fails after this many bytes (a storage error that is not "full"). */
    var failWriteAfter: (RestoreItem) -> Long? = { null }

    /** The phone is full once all photos on it plus the pending one reach this many bytes. */
    var capacityBytes: Long = Long.MAX_VALUE

    /** Called when a file is committed, before the next one starts; a test cancels from here. */
    var onCommit: (Photo) -> Unit = {}

    fun put(id: Long, name: String, dir: String, bytes: ByteArray, taken: Long = 0, modified: Long = 500): FakePhone =
        also { photos[id] = Photo(id, name, dir, bytes, taken, modified) }

    fun find(name: String): List<Photo> = photos.values.filter { it.name == name }

    override fun localFiles(): List<RestoreLocalFile> =
        photos.values.map { RestoreLocalFile(it.id, it.name, it.dir, it.bytes.size.toLong(), it.taken, it.modified) }

    override fun sha256Of(file: RestoreLocalFile): String? = photos[file.mediaId]?.let { sha(it.bytes) }

    override fun lockedFolders(): Set<String> = locked.toSet()

    override fun begin(item: RestoreItem): PendingRestoreFile {
        if (refuseCreate(item)) throw IOException("Android would not create the file")
        val label = "${item.targetRelativePath}${item.targetDisplayName}"
        val failAt = failWriteAfter(item)
        val buffer = object : ByteArrayOutputStream() {
            override fun write(b: ByteArray, off: Int, len: Int) {
                if (failAt != null && size() + len > failAt) throw IOException("write failed: EIO")
                val used = photos.values.sumOf { it.bytes.size.toLong() } + size() + len
                if (used > capacityBytes) throw IOException("write failed: ENOSPC (No space left on device)")
                super.write(b, off, len)
            }
        }
        return object : PendingRestoreFile {
            override val output: OutputStream = buffer
            override fun commit(): CommittedFile {
                val id = nextId++
                val photo = Photo(id, item.targetDisplayName, item.targetRelativePath, buffer.toByteArray(), item.entry.dateTakenMillis, 7_000 + id)
                photos[id] = photo
                events += "write:$label"
                onCommit(photo)
                return CommittedFile(id, photo.modified)
            }

            override fun abort() {
                aborted += label
            }
        }
    }
}

internal class RecordingApplier(private val events: MutableList<String> = ArrayList()) : RestoreApplier {
    val metadata = ArrayList<MetadataBackup.PreparedRestore>()
    val locks = ArrayList<List<BackupSections.Lock>>()
    val pins = ArrayList<BackupSections.Pin>()
    val recipes = ArrayList<RestoredRecipe>()
    val awaited = ArrayList<Set<Long>>()
    var failMetadata = false

    override suspend fun applyMetadata(prepared: MetadataBackup.PreparedRestore) {
        events += "metadata"
        if (failMetadata) throw IOException("could not write the library")
        metadata += prepared
    }

    override suspend fun applyLocks(locks: List<BackupSections.Lock>): LockResult {
        events += "locks"
        this.locks += locks
        return LockResult(locks.size, 0)
    }

    val sensitive = ArrayList<Set<Long>>()

    override suspend fun markSensitive(ids: Set<Long>) {
        events += "sensitive"
        sensitive += ids
    }

    override suspend fun applyPins(pins: List<BackupSections.Pin>): Int {
        events += "pins"
        this.pins += pins
        return pins.size
    }

    override suspend fun applyRecipes(recipes: List<RestoredRecipe>): Int {
        events += "recipes"
        this.recipes += recipes
        return recipes.size
    }

    override suspend fun awaitCatalogue(ids: Set<Long>) {
        events += "await"
        awaited += ids
    }
}

internal fun sha(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

/** A backup written by the real [BackupJob] into a plain directory, and the facts to restore it. */
internal class MadeBackup(val destination: DirectoryDestination, val candidates: List<BackupCandidate>, val source: FakeSource) {
    fun loaded(keys: BackupKeys = newKeys()): LoadedSet = keys.use { runBlocking { BackupSetLoader.load(destination, it) } }

    fun set(): RestoreSet = RestoreSet.from(loaded())

    fun packs(): List<String> = destination.names().filter { BackupLayout.isPackName(it) }.sorted()
}

internal object RestoreFixtures {
    val library = """
        {"schema":1,
         "collections":[{"id":"c1","name":"Trip","createdAtMillis":5,"mediaIds":[1,2]}],
         "tags":{"beach":[1,3]},
         "captions":{"2":"Second photo"},
         "archivedIds":[3]}
    """.trimIndent()

    fun backup(
        dir: File,
        count: Int = 7,
        size: Int = 300,
        transform: (List<BackupCandidate>) -> List<BackupCandidate> = { it },
        sections: ((List<BackupCandidate>) -> List<ManifestSection>)? = null,
    ): MadeBackup {
        val destination = DirectoryDestination(dir)
        val (plain, source) = JobFixtures.library(count, size)
        val candidates = transform(plain)
        val extra = (sections ?: ::defaultSections)(candidates)
        val keys = newKeys()
        val prep = try {
            runBlocking { BackupPreparer.prepare(destination, keys, candidates, BackupOptions(), source, JobFixtures.tinyLimits) }
        } finally {
            keys.close()
        }
        runBlocking {
            BackupJob(destination, source, newKeys(), appVersion = "9.9.9", sections = { extra }, io = Dispatchers.Unconfined).run(prep)
        }
        return MadeBackup(destination, candidates, source)
    }

    fun defaultSections(candidates: List<BackupCandidate>): List<ManifestSection> {
        val catalogue = candidates.associate { it.mediaId to MediaIdentity(it.displayName, it.sizeBytes, it.dateTakenMillis, it.relativePath) }
        val metadata = MetadataBackup.buildFile(parseJson(library)!!, setOf(2L), setOf(3L), catalogue, 1_700_000_000_000L).json
        return BackupSections.build(metadata, emptyList(), emptyList(), emptyList())
    }

    fun sections(
        candidates: List<BackupCandidate>,
        locks: List<BackupSections.Lock> = emptyList(),
        pins: List<BackupSections.Pin> = emptyList(),
        recipes: List<BackupSections.Recipe> = emptyList(),
    ): List<ManifestSection> {
        val catalogue = candidates.associate { it.mediaId to MediaIdentity(it.displayName, it.sizeBytes, it.dateTakenMillis, it.relativePath) }
        val metadata: JsonValue = MetadataBackup.buildFile(parseJson(library)!!, setOf(2L), setOf(3L), catalogue, 1_700_000_000_000L).json
        return BackupSections.build(metadata, locks, pins, recipes)
    }

    fun prepare(set: RestoreSet, phone: FakePhone, scope: RestoreScope = RestoreScope.EVERYTHING): RestorePreparation =
        runBlocking { RestorePreparer.prepare(set, scope, phone, io = Dispatchers.Unconfined) }

    fun job(made: MadeBackup, phone: FakePhone, applier: RestoreApplier, keys: BackupKeys = newKeys()): RestoreJob =
        RestoreJob(made.destination, keys, phone, applier, io = Dispatchers.Unconfined)

    fun restore(made: MadeBackup, phone: FakePhone, applier: RestoreApplier = RecordingApplier(phone.events), scope: RestoreScope = RestoreScope.EVERYTHING): RestoreOutcome {
        val prep = prepare(made.set(), phone, scope)
        return runBlocking { job(made, phone, applier).run(prep) }
    }
}
