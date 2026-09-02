package com.fotoxplorr.app

import android.content.Context
import com.fotoxplorr.app.background.BackgroundWorkRunner
import com.fotoxplorr.app.curate.AutoCurationPass
import com.fotoxplorr.app.media.MediaAsset
import com.fotoxplorr.app.organize.LibraryStore
import com.fotoxplorr.app.privacy.PrivateFolderStore
import com.fotoxplorr.app.media.SqliteMediaRepository
import com.fotoxplorr.app.moments.VideoMomentIndexer
import com.fotoxplorr.app.moments.VideoMomentStore
import com.fotoxplorr.app.recognition.RecognitionIndexer
import com.fotoxplorr.app.recognition.RecognitionStore
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/**
 * The heavy work the background rules exist to schedule.
 *
 * `com.fotoxplorr.app.background` deliberately knows only WHEN work may run — it holds the rules,
 * the evaluator and the job, and nothing about photographs. This class is the WHAT, and it lives
 * at the app's composition root rather than in either feature package because it spans both of
 * them: recognition indexing over images and key-moment detection over videos. Putting it inside
 * `recognition/` would have made that package reach into `moments/` for reasons that have nothing
 * to do with recognition.
 *
 * ## Why it reads the repository instead of scanning
 * [SqliteMediaRepository] is the library as this app last saw it. A background pass that started
 * by scanning MediaStore would be doing the expensive discovery work over again on every wake, and
 * would race the foreground scan that already owns that job. Whatever the last foreground session
 * found is exactly the right input here: anything newer will be picked up by the scan that runs
 * next time the app is opened, and then indexed on the wake after that.
 *
 * ## Why both indexers are safe to call unconditionally
 * Each is idempotent and cheap when there is nothing to do. [RecognitionIndexer] visits only
 * assets whose stored result is missing or stale; [VideoMomentIndexer] skips any video already
 * marked scanned. So "run the pending work" needs no separate notion of what is pending — asking
 * the indexers is the same question, and they are the ones that can answer it correctly.
 */
class LibraryBackgroundWork(context: Context) : BackgroundWorkRunner {
    private val appContext = context.applicationContext

    override suspend fun runPendingWork(): String? {
        // awaitLoaded, NOT observeAll().first(): the repository loads asynchronously after
        // construction, and a StateFlow's first value on a fresh instance is the empty list it
        // was initialised with. See SqliteMediaRepository.awaitLoaded for the failure this was.
        val assets = SqliteMediaRepository(appContext).awaitLoaded()
        if (assets.isEmpty()) return null

        currentCoroutineContext().ensureActive()
        val recognised = indexRecognition(assets)

        currentCoroutineContext().ensureActive()
        val scanned = indexMoments(assets)

        return summarise(recognised, scanned)
    }

    /**
     * Recognise, then curate. The order matters: [AutoCurationPass] reads what recognition just
     * wrote, so running it first would curate the previous pass's results and leave everything
     * indexed this period untagged until the next wake.
     */
    private suspend fun indexRecognition(assets: List<MediaAsset>): Int {
        val store = RecognitionStore(appContext)
        store.reload()
        val indexed = RecognitionIndexer(appContext, store).index(assets).getOrDefault(0)

        currentCoroutineContext().ensureActive()
        // LibraryStore.get, never a second instance: the Activity writes to the same preferences
        // file from the main thread, and two instances over one file lose writes -- see the
        // class's own KDoc. Locked folders are read so the pass leaves their contents alone.
        AutoCurationPass(LibraryStore.get(appContext)).run(
            assets = assets,
            recognition = store.observe().value,
            lockedFolders = PrivateFolderStore(appContext).observeLockedFolders().value,
        )
        return indexed
    }

    /**
     * Key moments for videos that have never been through the detector.
     *
     * One video at a time, with a cancellation check between each, because unlike the recognition
     * pass this loop is the thing holding the job's execution window open: sampling a long video
     * is seconds of work, and the platform can reclaim the window mid-library. Checking between
     * videos means a reclaim costs at most the one in flight, and the rest are simply still
     * unscanned next period — which is exactly the state they were already in.
     */
    private suspend fun indexMoments(assets: List<MediaAsset>): Int {
        val videos = assets.filter { it.isVideo && !it.isTrashed }
        if (videos.isEmpty()) return 0

        val store = VideoMomentStore(appContext)
        store.reload()
        val indexer = VideoMomentIndexer(appContext, store)
        var scanned = 0
        for (video in videos) {
            currentCoroutineContext().ensureActive()
            if (store.hasBeenScanned(video.id)) continue
            // A single unreadable file must not end the pass for every video behind it in the
            // list. The indexer already turns an unreadable video into "scanned, no moments"
            // rather than a failure (see its KDoc), so anything that still throws here is
            // genuinely unexpected and belongs to that one file, not to the run.
            runCatching { indexer.index(video) }
            scanned++
        }
        return scanned
    }

    /**
     * A short line for the status surface, or null for "there was nothing to do".
     *
     * Null rather than "0 photos, 0 videos": [BackgroundWorkRunner.runPendingWork]'s contract
     * makes "nothing pending" a distinct answer precisely so a quiet, fully-indexed library reads
     * as quiet instead of as a run that achieved nothing.
     */
    private fun summarise(recognised: Int, scanned: Int): String? = when {
        recognised == 0 && scanned == 0 -> null
        scanned == 0 -> "Indexed $recognised ${plural(recognised, "photo", "photos")}"
        recognised == 0 -> "Scanned $scanned ${plural(scanned, "video", "videos")} for key moments"
        else -> "Indexed $recognised ${plural(recognised, "photo", "photos")}, " +
            "scanned $scanned ${plural(scanned, "video", "videos")}"
    }

    private fun plural(count: Int, one: String, many: String) = if (count == 1) one else many
}
