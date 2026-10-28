package com.fotoxplorr.app.reel

import com.fotoxplorr.app.media.MediaAsset
import com.fotoxplorr.core.model.MediaId
import java.io.File
import java.io.IOException
import java.util.Collections

/** Things the editor and screen tests share: library items, a library in memory, and fakes for the export seams. */
internal object ReelScreenKit {
    const val DAY = 24L * 3_600_000L

    /** 2025-06-15 12:00 UTC. */
    const val BASE_DATE = 1_750_000_000_000L

    fun photo(id: Long, date: Long = BASE_DATE + id * 60_000L, width: Int = 4000, height: Int = 3000, favourite: Boolean = false) = MediaAsset(
        id = MediaId(id), contentUriString = "content://media/external/images/media/$id", displayName = "IMG_$id.jpg",
        mimeType = "image/jpeg", bucketName = null, dateTakenMillis = date, dateModifiedSeconds = 0,
        width = width, height = height, sizeBytes = 1, relativePath = "DCIM/Camera/", isFavorite = favourite, isTrashed = false,
    )

    fun video(id: Long, durationMs: Long = 12_000L, date: Long = BASE_DATE + id * 60_000L) = MediaAsset(
        id = MediaId(id), contentUriString = "content://media/external/video/media/$id", displayName = "VID_$id.mp4",
        mimeType = "video/mp4", bucketName = null, dateTakenMillis = date, dateModifiedSeconds = 0,
        width = 1920, height = 1080, sizeBytes = 1, relativePath = "DCIM/Camera/", isFavorite = false, isTrashed = false,
        durationMillis = durationMs,
    )

    fun stills(vararg ids: Long) = ReelProject(items = ids.mapIndexed { i, id -> StillItem("r${i + 1}", id) })
}

/** A [ReelLibrary] in memory, with switches for the ways a disk fails. */
internal class MemoryReelLibrary : ReelLibrary {
    class Entry(val project: ReelProject, val modified: Long, val readable: Boolean = true, val decoded: ReelDecoded? = null)

    val entries: MutableMap<String, Entry> = Collections.synchronizedMap(LinkedHashMap())
    val saves: MutableList<Pair<String?, ReelProject>> = Collections.synchronizedList(ArrayList())
    @Volatile var result: ((String?) -> ReelSave)? = null
    @Volatile var clock = 1_000L

    override fun list(): SavedReels = SavedReels(
        entries.entries.sortedByDescending { it.value.modified }.map { (id, e) ->
            if (e.readable) {
                SavedReel(id, e.project.name.ifBlank { ReelCopy.DEFAULT_NAME }, e.modified, e.project.items.size, e.project.totalDurationMs, true)
            } else {
                SavedReel(id, ReelCopy.DAMAGED_REEL, e.modified, 0, 0L, false)
            }
        },
    )

    override fun load(id: String): ReelLoad {
        val e = entries[id] ?: return ReelLoad.Missing
        if (!e.readable) return ReelLoad.Unreadable
        return ReelLoad.Loaded(id, e.decoded ?: ReelDecoded(e.project, 0, false))
    }

    override fun save(id: String?, project: ReelProject): ReelSave {
        saves += id to project
        result?.let { forced -> return forced(id) }
        val target = id ?: ReelProjectStore.newId()
        entries[target] = Entry(project, ++clock)
        return ReelSave.Saved(target)
    }

    override fun delete(id: String): Boolean = entries.remove(id) != null

    fun put(project: ReelProject, modified: Long = ++clock, readable: Boolean = true, decoded: ReelDecoded? = null): String {
        val id = ReelProjectStore.newId()
        entries[id] = Entry(project, modified, readable, decoded)
        return id
    }
}

/** A host over a list of library items that writes small placeholder files. */
internal class ScreenHost(val library: Map<Long, MediaAsset>) : ReelHost {
    val blocked = HashSet<Long>()
    @Volatile var probe: SoundtrackProbe = SoundtrackProbe.Usable(120_000L)
    val probed = Collections.synchronizedList(ArrayList<String>())

    override fun asset(mediaId: Long): MediaAsset? = library[mediaId]
    override fun mayUsePhoto(asset: MediaAsset): Boolean = asset.id.value !in blocked
    override fun mayUseVideo(asset: MediaAsset): Boolean = asset.id.value !in blocked
    override suspend fun shapeOf(asset: MediaAsset): Float? = 1.5f

    override suspend fun stagePhoto(asset: MediaAsset, spec: StageSpec, into: File): StagedPicture? {
        into.writeBytes(ByteArray(64))
        return StagedPicture(into, 2000, 1500)
    }

    override suspend fun stageCard(text: String, backgroundArgb: Int, frame: ReelFrame, into: File): StagedPicture {
        into.writeBytes(ByteArray(16))
        return StagedPicture(into, frame.width, frame.height)
    }

    override suspend fun probeSoundtrack(uri: String): SoundtrackProbe = probe.also { probed += uri }
}

internal class ScreenEncoder : ReelEncoder {
    val plans = Collections.synchronizedList(ArrayList<ReelRenderPlan>())
    @Volatile var can = true
    @Volatile var gate: (suspend () -> Unit)? = null

    override fun canEncode(): Boolean = can

    override suspend fun encode(plan: ReelRenderPlan, staged: ReelStaged, output: File, onProgress: (Float) -> Unit) {
        plans += plan
        onProgress(0.4f)
        gate?.invoke()
        output.writeBytes(ByteArray(48))
        onProgress(1f)
    }
}

internal class ScreenSink(val dir: File) : ReelSink {
    val published = Collections.synchronizedList(ArrayList<String>())
    @Volatile var failWith: IOException? = null
    @Volatile var sweeps = 0

    override fun stagingDirectory(): File = dir.apply { mkdirs() }

    override fun sweepStaging() {
        sweeps++
    }

    override suspend fun publish(staged: File, stem: String, dateTakenMillis: Long, stillAllowed: () -> Boolean, onProgress: (Float) -> Unit): String {
        if (!stillAllowed()) throw ReelProtectedException()
        failWith?.let { throw it }
        published += stem
        onProgress(1f)
        return "content://media/external/video/media/${900 + published.size}"
    }
}

internal class FakeSoundFiles : ReelSoundFiles {
    var keeps = true
    val kept = ArrayList<String>()
    var names = HashMap<String, String>()

    override fun keep(uri: String): Boolean = keeps.also { kept += uri }

    override fun nameOf(uri: String): String? = names[uri]

    val released = java.util.Collections.synchronizedList(ArrayList<String>())

    override fun release(uri: String) {
        released += uri
    }
}
