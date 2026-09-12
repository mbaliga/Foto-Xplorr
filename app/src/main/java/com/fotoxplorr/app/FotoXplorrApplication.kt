package com.fotoxplorr.app

import android.app.Application
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.disk.DiskCache
import coil3.memory.MemoryCache
import coil3.request.crossfade
import com.fotoxplorr.app.background.BackgroundScheduler
import com.fotoxplorr.app.background.BackgroundWorkRunnerRegistry
import com.fotoxplorr.app.background.WorkRulesStore
import dev.aarso.crashrecovery.CrashRecovery
import okio.Path.Companion.toOkioPath

/**
 * Installs Hyle's optional dev.aarso:crash-recovery utility (own Gradle module, zero
 * dependency on :hyle, plain android.widget views) -- captures a device-only launch/runtime
 * crash and shows a recovery screen on the next launch instead of the app's real content.
 * See FotoXplorrActivity.onCreate() for the matching maybeShowRecovery() call.
 *
 * Also owns the app's single Coil [ImageLoader]. Until this existed the app had NO image-loader
 * configuration at all, so every surface ran on Coil's defaults -- which is the wrong shape for
 * this app specifically: a library of tens of thousands of local photos, scrolled fast, where
 * the same thumbnails are revisited constantly.
 */
class FotoXplorrApplication : Application(), SingletonImageLoader.Factory {
    override fun onCreate() {
        super.onCreate()
        CrashRecovery.install(this, appLabel = "Foto Xplorr")
        startBackgroundWork()
    }

    /**
     * Hands the background scheduler the work it is meant to schedule, and arms it.
     *
     * Both halves belong here rather than in a screen. The runner is a process-wide slot read by
     * a `JobService` the platform may start with no Activity alive at all, so registering it from
     * a composable would mean the job waking up into a process that has no idea what to do; and
     * arming only from the Settings tab (where it also happens, on every edit) would leave a fresh
     * install running unrestricted until someone happened to open that tab — the exact opposite of
     * what a person setting battery and quiet-hours rules is asking for.
     *
     * Reading the stored rules is a SharedPreferences load, which is why this can be synchronous
     * on the main thread here; [BackgroundScheduler.reconcile] itself only talks to `JobScheduler`.
     */
    private fun startBackgroundWork() {
        BackgroundWorkRunnerRegistry.runner = LibraryBackgroundWork(this)
        // A failure to arm must not take the app down on launch: the rules are a background
        // convenience, and an app that will not start because JobScheduler refused a job is a
        // far worse outcome than one whose background pass waits until Settings is next opened.
        runCatching { BackgroundScheduler(this).reconcile(WorkRulesStore(this).observe().value) }
    }

    /**
     * The one loader every [coil3.compose.AsyncImage] in the app resolves to.
     *
     * Three deliberate departures from the defaults, all of them about scrolling a very large
     * local library rather than about loading a handful of network images:
     *
     * - **A bigger memory cache.** The default is a modest slice of app memory, sized for apps
     *   that show a few images at a time. A grid of 22k photos evicts that in a couple of
     *   flings, so every scroll back up re-decodes from scratch -- which is exactly what
     *   "stuttery" feels like. 30% is generous without crowding out the bitmaps Compose itself
     *   is holding for the visible frames.
     *
     * - **A disk cache, sized for thumbnails.** Coil's disk cache exists mainly for network
     *   images and buys nothing for a `content://` URI *as a fetch*; the expensive part here is
     *   never the read, it is decoding a 12-megapixel JPEG down to a grid cell. Caching the
     *   decoded-and-downsampled result means that cost is paid once per photo for the life of
     *   the cache instead of once per time it scrolls past.
     *
     * - **Crossfade off.** A fade is an animation per cell; at a fast fling that is dozens of
     *   concurrent animations doing nothing the user asked for. Thumbnails should appear, not
     *   perform.
     */
    override fun newImageLoader(context: PlatformContext): ImageLoader =
        ImageLoader.Builder(context)
            .memoryCache {
                MemoryCache.Builder()
                    .maxSizePercent(this, MEMORY_CACHE_FRACTION)
                    .build()
            }
            .diskCache {
                DiskCache.Builder()
                    .directory(cacheDir.resolve("photo_thumbnails").toOkioPath())
                    .maxSizeBytes(DISK_CACHE_BYTES)
                    .build()
            }
            .crossfade(false)
            .build()

    private companion object {
        /** Share of app memory the decoded-bitmap cache may hold. */
        const val MEMORY_CACHE_FRACTION = 0.30

        /**
         * 256 MB of downsampled thumbnails. At roughly 40-60 KB per cached grid thumbnail this
         * holds the whole of a very large library, so a full re-scroll never re-decodes.
         */
        const val DISK_CACHE_BYTES = 256L * 1024 * 1024
    }
}
