package com.fotoxplorr.app.widget

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.ZoneId
import java.util.concurrent.ConcurrentHashMap

/**
 * The home-screen widgets' one entry point: the stores call [requestRefresh] when what a widget
 * shows has changed, and the providers and the jobs call [update]/[updateAll].
 *
 * Updates are pushed, never polled (`updatePeriodMillis` is 0 for every widget). Every pushed
 * redraw runs in a JobScheduler job ([WidgetRefreshJob]), so it survives the process being frozen
 * or killed right after the write that asked for it, and nothing is read or decoded unless at least
 * one widget is placed. No network, no new permission, no scheduling library: JobScheduler alone
 * (see WidgetJobs.kt).
 */
internal object FotozWidgets {
    private const val TAG = "FotozWidgets"

    /** One update has to finish well inside a broadcast receiver's window. */
    const val UPDATE_BUDGET_MS = 8_000L

    /** How long "no widget is placed" is trusted before asking the launcher service again. */
    private const val NONE_PLACED_TRUST_MS = 5_000L

    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    // Updates run one at a time: six providers can be asked to update together (after a reboot, an
    // app update), and serialising them keeps the decoded bitmaps of only one in memory at once.
    private val updateLock = Mutex()

    /** Elapsed-realtime until which a "none placed" answer is reused (see [mayBePlaced]). */
    @Volatile private var nonePlacedUntil = 0L

    /** When each widget was last drawn with real content, in elapsed realtime (see [showErrorUnlessDrawnSince]). */
    private val drawnAt = ConcurrentHashMap<Int, Long>()

    /**
     * Something a widget may show has changed. Safe from any thread, never throws, and cheap: at
     * most one JobScheduler call per redraw. [urgent] is for a change that takes something off the
     * home screen (a photo flagged sensitive, archived or protected): it is drawn without the short
     * wait that lets a burst of writes share one redraw.
     */
    fun requestRefresh(context: Context, urgent: Boolean = false) {
        runCatching { WidgetRefreshJob.request(context, urgent) }
            .onFailure { Log.w(TAG, "could not request a widget redraw", it) }
    }

    /**
     * Whether any widget of this app is placed. A "no" is reused for a few seconds so a burst of
     * store writes with no widget placed does not ask the launcher service each time; any provider
     * callback (a widget being placed included) forgets it at once ([placementChanged]).
     */
    fun mayBePlaced(app: Context): Boolean {
        val now = SystemClock.elapsedRealtime()
        if (now < nonePlacedUntil) return false
        val manager = AppWidgetManager.getInstance(app) ?: return false
        val any = WidgetKind.entries.any { placedIds(app, manager, it).isNotEmpty() }
        if (!any) nonePlacedUntil = now + NONE_PLACED_TRUST_MS
        return any
    }

    /** A provider heard from the launcher: whatever was cached about placement may be out of date. */
    fun placementChanged() {
        nonePlacedUntil = 0L
    }

    /** Widgets the launcher removed: nothing to remember about them. */
    fun forget(ids: IntArray) {
        ids.forEach { drawnAt.remove(it) }
    }

    fun providerFor(kind: WidgetKind): Class<out AppWidgetProvider> = when (kind) {
        WidgetKind.FAVOURITE -> FavouriteWidgetProvider::class.java
        WidgetKind.ON_THIS_DAY -> OnThisDayWidgetProvider::class.java
        WidgetKind.RECENT -> RecentWidgetProvider::class.java
        WidgetKind.LIBRARY -> LibraryWidgetProvider::class.java
        WidgetKind.COLLECTIONS -> CollectionsWidgetProvider::class.java
        WidgetKind.INDEXING -> IndexingWidgetProvider::class.java
    }

    /** Every placed widget of every kind, from one read of the stores. */
    suspend fun updateAll(context: Context, rescheduleDayJob: Boolean = true) {
        val app = context.applicationContext
        val manager = AppWidgetManager.getInstance(app) ?: return
        val placed = WidgetKind.entries
            .associateWith { placedIds(app, manager, it) }
            .filterValues { it.isNotEmpty() }
        if (placed.isNotEmpty()) updateLock.withLock { push(app, manager, placed) }
        if (rescheduleDayJob) WidgetDayJob.reconcile(app, needed = placed.keys.any { it.dateDependent })
    }

    /** The given instances of one kind (a provider's onUpdate or options change). */
    suspend fun update(context: Context, kind: WidgetKind, ids: IntArray) {
        if (ids.isEmpty()) return
        val app = context.applicationContext
        // Armed first, before waiting for the lock or reading anything: a slow first draw after a
        // reboot must not also leave the date widgets without their midnight redraw.
        if (kind.dateDependent) WidgetDayJob.reconcile(app, needed = true)
        val manager = AppWidgetManager.getInstance(app) ?: return
        updateLock.withLock { push(app, manager, mapOf(kind to ids)) }
    }

    /** Arms or cancels the midnight redraw to match the date-dependent widgets that are placed. */
    fun reconcileDayJob(context: Context) {
        val app = context.applicationContext
        val manager = AppWidgetManager.getInstance(app) ?: return
        val needed = WidgetKind.entries.filter { it.dateDependent }.any { placedIds(app, manager, it).isNotEmpty() }
        WidgetDayJob.reconcile(app, needed)
    }

    /**
     * The error state for a provider request that ran out of time or failed, except on widgets
     * another pass has drawn since [sinceElapsed]: an update that was only waiting its turn behind a
     * redraw must not paint "Couldn't load" over what that redraw just drew correctly.
     */
    fun showErrorUnlessDrawnSince(context: Context, ids: IntArray, sinceElapsed: Long) {
        val stale = ids.filter { (drawnAt[it] ?: Long.MIN_VALUE) < sinceElapsed }
        if (stale.isNotEmpty()) showError(context, stale.toIntArray())
    }

    /** Draws the error state in words, without reading anything (a timed-out or failed update). */
    fun showError(context: Context, ids: IntArray) {
        val app = context.applicationContext
        val manager = AppWidgetManager.getInstance(app) ?: return
        val renderer = WidgetRenderer(app, { _, _ -> null }, 0L, System.currentTimeMillis(), ZoneId.systemDefault(), withImages = false)
        ids.forEach { id ->
            runCatching { manager.updateAppWidget(id, (renderer.error(id) as WidgetViews.Single).views) }
        }
    }

    private fun placedIds(context: Context, manager: AppWidgetManager, kind: WidgetKind): IntArray =
        runCatching { manager.getAppWidgetIds(ComponentName(context, providerFor(kind))) }.getOrNull() ?: IntArray(0)

    private suspend fun push(app: Context, manager: AppWidgetManager, placed: Map<WidgetKind, IntArray>) {
        val library = try {
            WidgetDataSource.load(app)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (t: Throwable) {
            Log.w(TAG, "could not read the library for widgets", t)
            placed.values.forEach { showError(app, it) }
            return
        }
        val metrics = app.resources.displayMetrics
        val budget = bitmapBudgetBytes(metrics.widthPixels, metrics.heightPixels)
        val now = System.currentTimeMillis()
        val zone = ZoneId.systemDefault()
        val fontScale = app.resources.configuration.fontScale
        for ((kind, ids) in placed) {
            // One picture cache per kind: shared by that kind's instances (one decode per photo),
            // released before the next kind decodes its own.
            val renderer = WidgetRenderer(app, WidgetThumbnails(app)::load, budget, now, zone)
            val indexing = if (kind == WidgetKind.INDEXING) {
                try {
                    WidgetDataSource.indexing(app, library)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (t: Throwable) {
                    Log.w(TAG, "could not read indexing progress", t)
                    showError(app, ids)
                    continue
                }
            } else {
                null
            }
            for (id in ids) {
                val views = try {
                    renderer.render(kind, id, library, indexing)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (t: Throwable) {
                    Log.w(TAG, "could not draw a ${kind.name} widget", t)
                    renderer.error(id)
                }
                val options = manager.getAppWidgetOptions(id)
                try {
                    manager.updateAppWidget(id, views.toRemoteViews(kind, options, fontScale))
                    drawnAt[id] = SystemClock.elapsedRealtime()
                } catch (tooLarge: IllegalArgumentException) {
                    // The platform refused the bitmaps (its cap is per screen size). Draw the same
                    // words with placeholders rather than leaving the widget on its last state.
                    Log.w(TAG, "widget update over the bitmap limit; drawing without pictures", tooLarge)
                    val plain = WidgetRenderer(app, { _, _ -> null }, budget, now, zone, withImages = false)
                        .render(kind, id, library, indexing)
                    runCatching { manager.updateAppWidget(id, plain.toRemoteViews(kind, options, fontScale)) }
                }
            }
        }
    }
}
