package com.fotoxplorr.app.background

import android.app.job.JobInfo
import android.app.job.JobScheduler
import android.content.ComponentName
import android.content.Context
import com.fotoxplorr.app.BuildConfig
import java.util.concurrent.TimeUnit

/**
 * Arms and disarms the ONE platform job Foto Xplorr uses for every rule-gated background pass,
 * via the platform's own `android.app.job.JobScheduler` -- no WorkManager, no new dependency
 * (this app may not add one: the `offline` flavour's Gradle gates fail the build on any network
 * or scheduling library reaching its classpath, and `JobScheduler` is a framework class, not a
 * dependency at all).
 *
 * ## The whole architecture, in one place
 *
 * `JobScheduler` cannot express "battery above 47%", and it has no notion of "between 22:00 and
 * 06:00" at all -- its constraints are coarse, OS-defined booleans, not the arbitrary values a
 * rule builder is meant to expose. So this is a TWO-LAYER design:
 *
 * 1. **This class** turns [WorkRules] into the nearest `JobInfo` constraints JobScheduler
 *    actually has, and asks the platform to wake [BackgroundWorkJobService] when they hold.
 * 2. **[BackgroundWorkJobService]**, once woken, re-reads live [DeviceState]
 *    (`readDeviceState`) and asks [WorkRuleEvaluator] the REAL question, with the user's exact
 *    numbers. If the answer is [WorkVerdict.Blocked], the job records the reason and returns --
 *    it does not run the heavy pass, and it does not need to reschedule anything by hand,
 *    because this is a PERIODIC job (see [POLL_INTERVAL_MS]): the same platform tick that woke
 *    it this time wakes it again next period regardless of what it did this time.
 *
 * Each of the four `WorkRules` fields maps onto that split differently, and the difference
 * matters:
 *
 * - **[WorkRules.requireIdle] / [WorkRules.requireCharging]** -- `JobScheduler` has an EXACT
 *   match for both (`setRequiresDeviceIdle`, `setRequiresCharging`), reading the very same OS
 *   signals [DeviceState.idle] / [DeviceState.charging] do. Requested only when the matching
 *   rule is on. Still re-checked live in the evaluator anyway, for the small race between
 *   "JobScheduler decided to start this" and "this line of code runs" (screen wakes, charger is
 *   pulled, in that gap) and because [DeviceState] needs these values regardless for the
 *   battery-while-charging rule below.
 * - **[WorkRules.minBatteryPercent]** -- no `JobScheduler` equivalent exists for an arbitrary
 *   percentage, so this is checked ENTIRELY by the live evaluator. `setRequiresBatteryNotLow` IS
 *   still requested here, but UNCONDITIONALLY -- an OS-owned floor around 15-20% (device-
 *   dependent, and not a number this app can read or control) applied regardless of what the
 *   user typed. This can sit BELOW a very permissive user setting, and when it does, it silently
 *   wins: a "battery above 5%" rule still will not wake the job below the OS's own floor. That
 *   is disclosed in the settings screen's caption under the battery control (see SettingsTabs.kt)
 *   rather than hidden, because pretending this app can tell JobScheduler to ignore its own idea
 *   of "critically low" would be exactly the guarantee-the-OS-does-not-give this feature must
 *   never imply. Requesting it is still a strict improvement over not requesting it: it can only
 *   ever suppress a wake-up the evaluator was going to block anyway at a battery level nobody
 *   wants background work running at regardless of their chosen threshold.
 * - **[WorkRules.activeHoursStart] / [WorkRules.activeHoursEnd]** -- no `JobScheduler` time-of-
 *   day constraint exists at all. Entirely evaluator-only: the periodic wake-up is the only
 *   mechanism, so a window that just opened is noticed within one [POLL_INTERVAL_MS], not
 *   instantly. Good enough for a feature about multi-hour indexing passes, not good enough for
 *   anything that needs to start within seconds of the window opening -- this is not that.
 * - **[WorkRules.onlyOnUnmetered]** -- the ONE rule `JobScheduler` expresses exactly
 *   (`NETWORK_TYPE_UNMETERED`), so unlike the other three, nothing is re-checked live for it at
 *   all: [readDeviceState] reports `unmetered = true` unconditionally, which is honest precisely
 *   BECAUSE the platform already guaranteed it before the job could start. Only ever requested
 *   when [BuildConfig.NETWORK_FEATURES] is true (the `connect` flavour) -- `offline` has no
 *   network permission of any kind, so asking JobScheduler to gate on network type there would
 *   describe a condition the build can never satisfy, turning a background pass permanently off
 *   by a route nobody could see in the settings screen.
 *
 * ## Where scheduling is armed from
 *
 * Two places, and both are needed. `FotoXplorrApplication.startBackgroundWork()` calls [reconcile]
 * at process start, so a fresh install obeys its rules without anyone ever opening Settings — the
 * job also being what registers the runner that does the actual work, which a `JobService` started
 * with no Activity alive would otherwise wake up without. The settings screen's BACKGROUND tab
 * calls it again on every edit, so a changed rule takes effect immediately rather than at next
 * launch.
 *
 * A reboot is covered by [JobInfo.Builder.setPersisted], not by a third call site: the app
 * declares `RECEIVE_BOOT_COMPLETED` for that and registers no boot receiver, so there is no code
 * of this app's running at boot at all — the platform simply restores a job it already knew
 * about.
 */
class BackgroundScheduler(context: Context) {
    private val appContext = context.applicationContext
    private val jobScheduler: JobScheduler? =
        appContext.getSystemService(Context.JOB_SCHEDULER_SERVICE) as? JobScheduler

    /**
     * Makes the platform schedule match [rules]. Cheap and idempotent -- safe to call on every
     * composition of the settings screen and again after every single edit, which is exactly how
     * it is used: `JobScheduler.schedule` with the same job id REPLACES whatever was pending
     * rather than stacking a duplicate, so calling this ten times with the same [rules] leaves
     * exactly one job scheduled, not ten.
     *
     * Never throws: `JobScheduler.schedule` can fail for reasons outside this app's control (the
     * platform enforces a per-app limit on scheduled jobs), and a settings screen must not crash
     * because the OS declined a background job.
     */
    fun reconcile(rules: WorkRules) {
        val scheduler = jobScheduler ?: return

        if (!rules.enabled) {
            scheduler.cancel(JOB_ID)
            BackgroundWorkStatusCenter.publish(BackgroundWorkStatus.Unrestricted)
            return
        }

        val builder = JobInfo.Builder(JOB_ID, ComponentName(appContext, BackgroundWorkJobService::class.java))
            .setPeriodic(POLL_INTERVAL_MS)
            // Unconditional OS-owned floor -- see this class's KDoc for why this is requested
            // regardless of WorkRules.minBatteryPercent, and why that is disclosed rather than
            // silently overriding a more permissive user setting.
            .setRequiresBatteryNotLow(true)
            // Survive a reboot. Requires RECEIVE_BOOT_COMPLETED, which the manifest declares for
            // exactly this and nothing else -- there is no boot receiver. Without it a phone that
            // restarts overnight silently stops honouring the rules until the app is next opened,
            // which is precisely the situation the rules exist for: work happening while nobody is
            // looking at the app.
            .setPersisted(true)

        if (rules.requireIdle) builder.setRequiresDeviceIdle(true)
        if (rules.requireCharging) builder.setRequiresCharging(true)
        if (rules.onlyOnUnmetered && BuildConfig.NETWORK_FEATURES) {
            builder.setRequiredNetworkType(JobInfo.NETWORK_TYPE_UNMETERED)
        }

        val result = runCatching { scheduler.schedule(builder.build()) }.getOrDefault(JobScheduler.RESULT_FAILURE)
        BackgroundWorkStatusCenter.publish(
            if (result == JobScheduler.RESULT_SUCCESS) BackgroundWorkStatus.Pending else BackgroundWorkStatus.Unknown,
        )
    }

    /** Removes the platform job entirely. Used only if this app ever needs a harder stop than
     *  [WorkRules.enabled] = false (which already routes through [reconcile] above); nothing
     *  calls this today. */
    fun cancel() {
        jobScheduler?.cancel(JOB_ID)
        BackgroundWorkStatusCenter.publish(BackgroundWorkStatus.Unknown)
    }

    private companion object {
        // Arbitrary, but stable and unique to this app: nothing else in Foto Xplorr schedules a
        // JobScheduler job today (there was none before this feature), so any fixed value works;
        // this one just avoids 0 and small round numbers a future unrelated job might reach for
        // first.
        const val JOB_ID = 20_918

        // Well above JobInfo.getMinPeriodMillis() (15 minutes, the platform floor -- a shorter
        // value here would simply be clamped up to it). Frequent enough that a rule which just
        // became satisfiable -- battery crossed the threshold, the active-hours window opened --
        // is noticed within the hour; coarse enough that the wake-ups are not themselves a
        // battery cost anyone would notice. A shorter retry specifically after a Blocked verdict
        // was considered and rejected: JobScheduler.schedule() with the same job id REPLACES the
        // pending job, so a hand-rolled "check again in five minutes" would cancel this periodic
        // timer, not add to it, and making the two coexist would need a second job id and twice
        // the bookkeeping for a feature whose entire premise is "this is not urgent". The
        // periodic cadence alone is the honest, simple answer.
        val POLL_INTERVAL_MS = TimeUnit.MINUTES.toMillis(30)
    }
}
