package com.fotoxplorr.app.background

import android.content.Context
import android.os.BatteryManager
import android.os.PowerManager
import java.util.Calendar

/**
 * Reads the live [DeviceState] [WorkRuleEvaluator.evaluate] needs, at the moment it is called.
 * Called exactly once per platform wake-up, from inside [BackgroundWorkJobService.onStartJob] --
 * never cached, because the entire reason the job re-checks instead of trusting whatever
 * [WorkRules] said last time is that any of this can have changed since then.
 *
 * Three system services, no permission beyond what the app already has for other reasons, and
 * deliberately NO `ConnectivityManager` call anywhere in this function -- see [unmetered] below,
 * and [BackgroundScheduler]'s KDoc for the full reasoning: this app's `offline` flavour holds no
 * `ACCESS_NETWORK_STATE` permission (a Gradle gate fails the build if it ever gains one -- see
 * `verifyOfflineManifest` in `app/build.gradle.kts`), so a function shared by both flavours must
 * not depend on it existing.
 */
fun readDeviceState(context: Context): DeviceState {
    val batteryManager = context.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager
    val powerManager = context.getSystemService(Context.POWER_SERVICE) as? PowerManager

    return DeviceState(
        batteryPercent = batteryManager
            ?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
            ?.takeIf { it in MIN_BATTERY_READING..MAX_BATTERY_READING }
            // Fail CLOSED, not open: if the platform will not tell us the battery level, assume
            // the worst (empty) rather than the best (full). This function backs a feature whose
            // whole purpose is protecting a battery nobody is watching -- assuming "must be fine"
            // on a read failure is exactly backwards for that purpose, even though it is the
            // friendlier-looking fallback.
            ?: FALLBACK_BATTERY_PERCENT_WHEN_UNREADABLE,
        charging = batteryManager?.isCharging ?: false,
        idle = powerManager?.isDeviceIdleMode ?: false,
        // Always true. NOT a stub -- this is the honest value. Unlike battery percent or the
        // active-hours window, "unmetered network" is something android.app.job.JobScheduler can
        // express EXACTLY (JobInfo.NETWORK_TYPE_UNMETERED), so BackgroundScheduler requests that
        // constraint directly whenever WorkRules.onlyOnUnmetered is on (connect flavour only --
        // see BackgroundScheduler). The platform enforces it before onStartJob is ever called:
        // if this function is running at all under that constraint, the network genuinely is
        // unmetered right now, by the OS's own guarantee, and re-deriving that fact ourselves
        // would need exactly the ConnectivityManager call this app is built to never make. When
        // onlyOnUnmetered is off, this value is never consulted by WorkRuleEvaluator at all, so
        // reporting `true` costs nothing there either.
        unmetered = true,
        hourOfDay = Calendar.getInstance().get(Calendar.HOUR_OF_DAY),
    )
}

private const val MIN_BATTERY_READING = 0
private const val MAX_BATTERY_READING = 100

/** See the "fail closed" comment above -- this is 0, not 100, on purpose. */
private const val FALLBACK_BATTERY_PERCENT_WHEN_UNREADABLE = 0
