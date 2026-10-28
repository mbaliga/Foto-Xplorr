package com.fotoxplorr.app.reel

import android.content.Context
import android.net.Uri

/** What the screen needs to know about the sound file the person picked, so a test gives it answers. */
internal interface ReelSoundFiles {
    /** Keep the right to read the file after a restart; false when the provider will not allow it. */
    fun keep(uri: String): Boolean

    /** Let go of the right [keep] took, for a file no reel uses any more. Harmless for a file never kept. */
    fun release(uri: String)

    /** The file's name as the provider gives it, or null. */
    fun nameOf(uri: String): String?
}

internal class AndroidSoundFiles(private val context: Context) : ReelSoundFiles {
    override fun keep(uri: String): Boolean = SoundtrackAccess.keep(context, Uri.parse(uri))

    override fun release(uri: String) = SoundtrackAccess.release(context, Uri.parse(uri))

    override fun nameOf(uri: String): String? = SoundtrackAccess.displayName(context, Uri.parse(uri))
}

/**
 * The system keeps only so many read rights per app (128 before Android 11, 512 from it) and drops the oldest past
 * that, which could be the backup folder's. A sound file is [ReelSoundFiles.keep]t once per pick, so one that is
 * replaced, removed or belongs to a deleted reel is let go here, but only when no reel still lays it.
 */
internal object ReelSoundGrants {
    /**
     * Release each of [candidates] that no reel keeps, and return those released. A file is kept by [open], the reel
     * on screen as it is now (null when none is), and by every saved reel in [library] except [openSavedId], the open
     * reel's own file, which may still name a file the person has just replaced. A reel that cannot be read keeps
     * nothing. Blocking: it reads the library, so call it off the main thread. Not remembered across a restart:
     * a file let go of late, or never (the process died first), stays held until a later pick or delete reaches it.
     */
    fun releaseUnused(candidates: Set<String>, open: ReelProject?, openSavedId: String?, library: ReelLibrary, sounds: ReelSoundFiles): Set<String> {
        if (candidates.isEmpty()) return emptySet()
        val unused = candidates.toMutableSet()
        open?.soundtrack?.uri?.let { unused -= it }
        for (saved in library.list().reels) {
            if (unused.isEmpty()) break
            if (!saved.readable || saved.id == openSavedId) continue
            val kept = (library.load(saved.id) as? ReelLoad.Loaded)?.decoded?.project?.soundtrack?.uri ?: continue
            unused -= kept
        }
        unused.forEach { runCatching { sounds.release(it) } }
        return unused
    }
}
