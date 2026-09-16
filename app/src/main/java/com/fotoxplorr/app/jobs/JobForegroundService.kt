package com.fotoxplorr.app.jobs

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch

/**
 * The notification a [JobRunner] job shows while the app may not be in the foreground.
 *
 * A `LifecycleService` (from `androidx.lifecycle:lifecycle-service`) rather than a plain
 * `Service`, purely so its own `lifecycleScope` can collect [JobRunnerRegistry.jobs] without this
 * class hand-rolling a coroutine scope and its own cancellation.
 *
 * [start] and [stop] are the only public entry points a caller needs: [start] is idempotent (a
 * second call while already running just lets the collector redraw the notification with whatever
 * changed), and the service stops itself the moment the job list actually goes empty, in
 * [refresh], as well as whenever [stop] is called directly.
 */
class JobForegroundService : LifecycleService() {

    override fun onCreate() {
        super.onCreate()
        ensureChannel()
        lifecycleScope.launch {
            JobRunnerRegistry.jobs.collect { jobs -> refresh(jobs) }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        if (intent?.action == ACTION_CANCEL) {
            intent.getStringExtra(EXTRA_JOB_ID)?.let { JobRunnerRegistry.find(it)?.cancel?.invoke() }
        }
        // A running job's own coroutine survives independently of this service (it lives in the
        // ViewModel's scope) -- START_NOT_STICKY is correct because there is nothing useful for
        // the system to redeliver if this process is killed and restarted: the jobs themselves
        // would already be gone with it.
        return START_NOT_STICKY
    }

    private fun refresh(jobs: List<RunningJob>) {
        if (jobs.isEmpty()) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return
        }
        val notification = buildNotification(jobs)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROCESSING)
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun buildNotification(jobs: List<RunningJob>) = NotificationCompat.Builder(this, CHANNEL_ID)
        .setSmallIcon(android.R.drawable.stat_sys_upload)
        .setOngoing(true)
        .setOnlyAlertOnce(true)
        .setContentTitle(
            if (jobs.size == 1) jobs.first().title else "${jobs.size} background jobs",
        )
        .apply {
            val determinate = jobs.filter { it.progress != null }
            when {
                jobs.size > 1 -> setContentText(jobs.joinToString(", ") { it.kind.label })
                determinate.isNotEmpty() -> {
                    val percent = ((determinate.first().progress ?: 0f) * PERCENT_MAX).toInt()
                    setProgress(PERCENT_MAX, percent, false)
                }
                else -> setProgress(0, 0, true)
            }
            // Only one job's cancel action fits a single notification action; with several
            // running the notification still names them all above, but stopping one individually
            // means opening the app -- the shade's own per-row cancel already covers that case.
            if (jobs.size == 1) addAction(0, "Cancel", cancelIntent(jobs.first().id))
        }
        .build()

    private fun cancelIntent(jobId: String): PendingIntent {
        val intent = Intent(this, JobForegroundService::class.java).apply {
            action = ACTION_CANCEL
            putExtra(EXTRA_JOB_ID, jobId)
        }
        return PendingIntent.getService(
            this,
            jobId.hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Background work", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Conversions, exports, and copy/move progress"
            },
        )
    }

    companion object {
        private const val CHANNEL_ID = "foto_xplorr_jobs"
        private const val NOTIFICATION_ID = 4210
        private const val PERCENT_MAX = 100
        private const val ACTION_CANCEL = "com.fotoxplorr.app.jobs.action.CANCEL"
        private const val EXTRA_JOB_ID = "jobId"

        /** Idempotent: safe to call every time the job list goes from empty to non-empty. */
        fun start(context: Context) {
            ContextCompat.startForegroundService(context, Intent(context, JobForegroundService::class.java))
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, JobForegroundService::class.java))
        }
    }
}
