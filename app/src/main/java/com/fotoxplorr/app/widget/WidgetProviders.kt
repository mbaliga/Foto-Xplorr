package com.fotoxplorr.app.widget

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The six providers a launcher lists. Each is a thin shell over [FotozWidgets]: the work runs off
 * the main thread under `goAsync()`, and the broadcast is finished within
 * [FotozWidgets.UPDATE_BUDGET_MS] whatever the work is doing. A widget whose update could not finish
 * in that time says so in words instead of staying on "Loading", and the redraw is retried in a job,
 * which a broadcast's ten seconds do not bound.
 *
 * Exported, as every App Widget provider must be for a launcher to bind it; nothing else the
 * widgets add is exported.
 */
internal abstract class FotozWidgetProvider(private val kind: WidgetKind) : AppWidgetProvider() {

    override fun onReceive(context: Context, intent: Intent) {
        // Placed, resized, removed or asked to update: any cached "no widget is placed" is stale.
        FotozWidgets.placementChanged()
        super.onReceive(context, intent)
    }

    override fun onDeleted(context: Context, appWidgetIds: IntArray) {
        FotozWidgets.forget(appWidgetIds)
    }

    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        runAsync(context, appWidgetIds) { FotozWidgets.update(context, kind, appWidgetIds) }
    }

    override fun onAppWidgetOptionsChanged(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetId: Int,
        newOptions: Bundle,
    ) {
        // API 31+ already holds all three layouts and the launcher switches between them itself.
        // Below that, the one layout drawn was picked from the old size, so draw again.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            runAsync(context, intArrayOf(appWidgetId)) { FotozWidgets.update(context, kind, intArrayOf(appWidgetId)) }
        }
    }

    override fun onDisabled(context: Context) {
        if (kind.dateDependent) runAsync(context, ids = null) { FotozWidgets.reconcileDayJob(context) }
    }

    /**
     * Runs [block] off the main thread and finishes the broadcast within the budget, whatever
     * [block] is doing. The budget runs on a separate coroutine from the work on purpose: a timeout
     * cannot interrupt a blocking read inside the work (an image decoder, MediaMetadataRetriever,
     * SQLite), so it could not bound `finish()` if it wrapped the work itself.
     *
     * Work still running at the deadline is left to finish: it may yet draw the right content, and
     * the retry queued here waits for it on the update lock instead of reading everything twice at
     * once. The budget includes waiting for that lock behind another redraw, which is why the error
     * is drawn only on widgets nothing has drawn since this request arrived.
     */
    protected fun runAsync(context: Context, ids: IntArray?, block: suspend () -> Unit) {
        val pending = goAsync()
        val app = context.applicationContext
        val requestedAt = SystemClock.elapsedRealtime()
        val failed = AtomicBoolean(false)
        val work = FotozWidgets.scope.launch {
            try {
                block()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (t: Throwable) {
                Log.w("FotozWidgetProvider", "${kind.name} widget update failed", t)
                failed.set(true)
            }
        }
        FotozWidgets.scope.launch {
            try {
                val finished = withTimeoutOrNull(FotozWidgets.UPDATE_BUDGET_MS) { work.join() } != null
                if (!finished || failed.get()) {
                    if (ids != null) FotozWidgets.showErrorUnlessDrawnSince(app, ids, requestedAt)
                    FotozWidgets.requestRefresh(app)
                }
            } finally {
                pending.finish()
            }
        }
    }
}

internal class FavouriteWidgetProvider : FotozWidgetProvider(WidgetKind.FAVOURITE)

/**
 * Also listens for the clock, time-zone and language changing (all three reach a manifest receiver
 * on every API level this app supports), since "today" and every printed date depend on them. One
 * provider listening is enough: it redraws every placed widget.
 */
internal class OnThisDayWidgetProvider : FotozWidgetProvider(WidgetKind.ON_THIS_DAY) {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_TIME_CHANGED,
            Intent.ACTION_TIMEZONE_CHANGED,
            Intent.ACTION_LOCALE_CHANGED,
            -> runAsync(context, ids = null) { FotozWidgets.updateAll(context) }
            else -> super.onReceive(context, intent)
        }
    }
}

internal class RecentWidgetProvider : FotozWidgetProvider(WidgetKind.RECENT)

internal class LibraryWidgetProvider : FotozWidgetProvider(WidgetKind.LIBRARY)

internal class CollectionsWidgetProvider : FotozWidgetProvider(WidgetKind.COLLECTIONS)

internal class IndexingWidgetProvider : FotozWidgetProvider(WidgetKind.INDEXING)
