package com.fotoxplorr.app.viewer

import android.content.Context
import com.fotoxplorr.app.media.MediaId

/** Skip remembering a position this close to the very end -- resuming "from 0:00 before the end"
 *  is indistinguishable from having finished the video, and the store clears it instead (see
 *  [ResumePositionStore.save]) so a finished video never shows a stale "Resume from..." chip. */
private const val NEAR_END_SLACK_MS = 2_000L

/** Skip remembering a position this close to the very start -- "Resume from 0:01" is noise, not a
 *  meaningful resume point, and starting fresh is exactly the same experience anyway. */
private const val NEAR_START_SLACK_MS = 2_000L

/** Pure decision: is [positionMs] into a [durationMs] video worth remembering as a resume point?
 *  Extracted so the boundary cases (near-start, near-end, a zero/unknown duration) are testable
 *  without a real [android.content.SharedPreferences] instance. */
fun shouldRememberPosition(positionMs: Long, durationMs: Long): Boolean =
    durationMs > 0L && positionMs > NEAR_START_SLACK_MS && positionMs < durationMs - NEAR_END_SLACK_MS

/**
 * Per-video playback position, keyed by [MediaId], backing the viewer's "Resume from 1:23" chip.
 * A single flat `SharedPreferences` file rather than SQLite: the whole store is one `Long` per
 * video the user has ever partially watched, which is exactly what a preferences file is for, and
 * every write is a single key (see `docs/TRAPS.md` #25/#26 for why that matters: no
 * read-modify-write of a shared collection here to lose a concurrent write to).
 */
class ResumePositionStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** The remembered position for [mediaId], or null if there is none (never watched partway,
     *  already finished, or already resumed and cleared). */
    fun get(mediaId: MediaId): Long? {
        val value = prefs.getLong(keyFor(mediaId), NO_VALUE)
        return value.takeIf { it >= 0L }
    }

    /** Called on every meaningful position change (pause, seek, screen leave). Clears the entry
     *  outright once [shouldRememberPosition] says the position is not worth keeping, rather than
     *  leaving a stale near-the-end value that would show "Resume from 12:58" on a video the user
     *  already watched to completion. */
    fun save(mediaId: MediaId, positionMs: Long, durationMs: Long) {
        if (shouldRememberPosition(positionMs, durationMs)) {
            prefs.edit().putLong(keyFor(mediaId), positionMs).apply()
        } else {
            clear(mediaId)
        }
    }

    /** Called once playback runs to its natural end -- see [VideoPlayer]'s own completion
     *  handling -- so a finished video never offers to resume itself. */
    fun clear(mediaId: MediaId) {
        prefs.edit().remove(keyFor(mediaId)).apply()
    }

    private fun keyFor(mediaId: MediaId): String = "resume_${mediaId.value}"

    private companion object {
        const val PREFS_NAME = "video_resume_positions"
        const val NO_VALUE = -1L
    }
}
