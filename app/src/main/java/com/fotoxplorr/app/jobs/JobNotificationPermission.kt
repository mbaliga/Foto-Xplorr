package com.fotoxplorr.app.jobs

import android.os.Build

/**
 * Whether to show Android 13+'s notification prompt now. [JobForegroundService]'s progress
 * notification is silently hidden without POST_NOTIFICATIONS, and nothing else in the app asks
 * for it, so the first job that runs does: once, and only where the permission exists at all.
 *
 * "Once" is the app's own flag ([alreadyAsked]) rather than Android's: after two refusals the
 * system stops showing the prompt anyway, and a person who said no should not be asked again on
 * every conversion.
 */
internal fun shouldAskForJobNotifications(sdkInt: Int, granted: Boolean, alreadyAsked: Boolean): Boolean =
    sdkInt >= Build.VERSION_CODES.TIRAMISU && !granted && !alreadyAsked

/**
 * Whether the result delivered for the notification prompt is the person's answer, as opposed to a
 * request the system dropped. A grant is an answer. A refusal is one only if the system now offers
 * a rationale for the permission, which it does after the person turned the prompt down once and
 * not for a request that never showed one (another permission request was pending, the activity
 * was not in front): that one must be asked again, not recorded as asked.
 */
internal fun notificationPromptWasAnswered(granted: Boolean, shouldShowRationale: Boolean): Boolean =
    granted || shouldShowRationale
