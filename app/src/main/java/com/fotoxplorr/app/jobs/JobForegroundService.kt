package com.fotoxplorr.app.jobs

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.fotoxplorr.app.hyle.ActivityKind
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
        val type = foregroundTypeFor(jobs.map { it.kind }, Build.VERSION.SDK_INT)
        // ServiceCompat drops the type below Android 10, where the three-argument call does not exist.
        ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, type)
    }

    /**
     * Android 15 gives a data-sync service a time budget (about six hours in a day) and calls this
     * when it is spent; the service must stop at once or the system raises an error. The job itself
     * lives in the ViewModel and is not cancelled here, but without its notification the process may
     * be stopped, which an encrypted backup survives by design: run it again and it carries on.
     */
    override fun onTimeout(startId: Int, fgsType: Int) {
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
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
        /**
         * The foreground-service type to start with, as a bit mask (0 below Android 10, which has none).
         * A backup copies bytes to a folder: that is data sync, on every version. Media processing is
         * for the transcodes, and is what Android 15 and later get for every other kind of job, as
         * before. A mix of the two asks for both; both are declared in the manifest.
         */
        internal fun foregroundTypeFor(kinds: Collection<ActivityKind>, sdk: Int): Int {
            if (sdk < Build.VERSION_CODES.Q) return 0
            if (sdk < Build.VERSION_CODES.VANILLA_ICE_CREAM) return ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            val backup = kinds.any { it == ActivityKind.BACKING_UP }
            val other = kinds.any { it != ActivityKind.BACKING_UP }
            return when {
                backup && other -> ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC or ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROCESSING
                backup -> ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
                else -> ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROCESSING
            }
        }

        private const val CHANNEL_ID = "foto_xplorr_jobs"
        private const val NOTIFICATION_ID = 4210
        private const val PERCENT_MAX = 100
        private const val ACTION_CANCEL = "com.fotoxplorr.app.jobs.action.CANCEL"
        private const val EXTRA_JOB_ID = "jobId"

        /**
         * Idempotent: safe to call every time the job list goes from empty to non-empty. Never
         * throws: from the background Android 12+ can refuse a foreground start, and the job
         * itself runs on regardless (it lives in the ViewModel), only without its notification.
         */
        fun start(context: Context) {
            runCatching {
                ContextCompat.startForegroundService(context, Intent(context, JobForegroundService::class.java))
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, JobForegroundService::class.java))
        }
    }
}
