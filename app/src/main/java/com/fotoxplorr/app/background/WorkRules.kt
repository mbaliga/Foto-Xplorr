package com.fotoxplorr.app.background

/**
 * The user's own rules for when indexing, recognition and every other heavy, time-taking pass
 * are allowed to run in the background -- written by them, in Settings, not hard-coded (owner:
 * *"we need to be able to show a user that indexing or any of these more time-taking activities
 * will continue in the background when the phone is idle and between certain hours and battery
 * is above x%, all of which should be writable -- basically a rule builder with values
 * exposed"*).
 *
 * This type and [WorkRuleEvaluator] are the whole rule builder's brain, and deliberately hold no
 * Android import at all: every field here is a plain value a JVM test can construct directly, so
 * "does 22..6 include 2am" is answered by running a test in milliseconds, not by setting a
 * device's clock and battery and watching what happens. [com.fotoxplorr.app.background.BackgroundScheduler]
 * is the thin Android layer that turns this into an actual `android.app.job.JobScheduler` job --
 * see its KDoc for the two-layer split ("JobScheduler wakes it up on a coarse guess;
 * [WorkRuleEvaluator] decides for real") that is this feature's entire architecture.
 *
 * @param requireIdle Wait for [DeviceState.idle] -- Android's own "nothing else is happening"
 *   signal (`PowerManager.isDeviceIdleMode`) -- before running. OFF by default: idle can mean
 *   "screen off for a while" long before it means Doze's deep maintenance window, and most heavy
 *   passes do not need to wait that long for that little benefit.
 * @param requireCharging Wait for the charger. OFF by default -- [minBatteryPercent] below is
 *   the gentler, ON-by-default alternative that protects the battery without requiring anyone to
 *   plug in at all.
 * @param minBatteryPercent Do not run below this percentage, UNLESS the phone is charging (see
 *   [WorkRuleEvaluator.evaluate] for why charging always satisfies this one outright). Defaults
 *   to [DEFAULT_MIN_BATTERY_PERCENT]. `0` disables the check entirely (`batteryPercent >= 0` is
 *   always true), which is a legitimate, explicit "I don't care" rather than a value that needs
 *   special-casing.
 * @param activeHoursStart The active-hours window's start, 0..23. May be numerically GREATER
 *   than [activeHoursEnd] -- that is not an error, it is how an overnight window like 22..6 is
 *   written. See [WorkRuleEvaluator.isWithinActiveHours] for exactly what "inside the window"
 *   means, including the [activeHoursStart] == [activeHoursEnd] case.
 * @param activeHoursEnd The window's end, 0..23, inclusive. Defaults to [MAX_HOUR_OF_DAY] so
 *   that, paired with [activeHoursStart] defaulting to [MIN_HOUR_OF_DAY], a fresh install's
 *   window is the full day -- see [hasActiveHoursWindow].
 * @param onlyOnUnmetered Skip a pass entirely on a metered connection. Only ever meaningful in
 *   the `connect` build flavour: `offline` has no network permission of any kind (see
 *   [BackgroundScheduler]'s KDoc), so there is no metered connection to avoid or unmetered one to
 *   wait for. The settings screen disables this control rather than hiding it when
 *   `BuildConfig.NETWORK_FEATURES` is false, so the reason it does nothing is stated, not
 *   silently true.
 * @param enabled The master switch. FALSE means every rule above is ignored and
 *   [WorkRuleEvaluator.evaluate] always returns [WorkVerdict.Allowed] -- "run whenever", the same
 *   as before this feature existed. This is what lets a user who wants none of this go back to
 *   exactly that with one tap, rather than having to zero out five separate controls.
 */
data class WorkRules(
    val requireIdle: Boolean = false,
    val requireCharging: Boolean = false,
    val minBatteryPercent: Int = DEFAULT_MIN_BATTERY_PERCENT,
    val activeHoursStart: Int = MIN_HOUR_OF_DAY,
    val activeHoursEnd: Int = MAX_HOUR_OF_DAY,
    val onlyOnUnmetered: Boolean = false,
    val enabled: Boolean = true,
)

/**
 * The device facts [WorkRuleEvaluator.evaluate] needs in order to answer "right now" -- the live
 * counterpart to the user's static [WorkRules].
 *
 * Every field is something the Android side reads fresh each time the platform job wakes up (see
 * `readDeviceState` in `DeviceStateReader.kt`); none of it is cached or trusted from a previous
 * run, because the whole point of re-checking inside the job is that any of these can have
 * changed since [WorkRules] was last edited or since JobScheduler last woke the process.
 *
 * @param batteryPercent 0..100, from `BatteryManager`.
 * @param charging Whether the device is currently on any power source (USB, AC or wireless) --
 *   also satisfies [WorkRules.minBatteryPercent] outright regardless of [batteryPercent]; see
 *   [WorkRuleEvaluator.evaluate].
 * @param idle Android's own device-idle signal (`PowerManager.isDeviceIdleMode`), not merely
 *   "screen off" -- see [WorkRules.requireIdle].
 * @param unmetered Whether the current network is NOT metered, i.e. safe to use freely. Always
 *   `true` as produced by this app's own Android glue, and that is not a placeholder -- read
 *   [BackgroundScheduler]'s KDoc for why this app has no legal way to check this itself and why
 *   `true` is the honest value to report whenever it matters. Kept as a real field here, rather
 *   than hard-coding the check away, so [WorkRuleEvaluator] stays a general function that can be
 *   tested against `false` too, independent of what today's one caller happens to supply.
 * @param hourOfDay The device's local wall-clock hour, 0..23 (`Calendar.HOUR_OF_DAY`) -- local
 *   time deliberately, because a window like "22:00 to 06:00" is written and read against the
 *   clock on the wall, not against UTC.
 */
data class DeviceState(
    val batteryPercent: Int,
    val charging: Boolean,
    val idle: Boolean,
    val unmetered: Boolean,
    val hourOfDay: Int,
)

/**
 * The answer to "should heavy background work run right now" -- and, when the answer is no, WHY,
 * in words a person can read without knowing what a JobScheduler constraint is.
 *
 * [Blocked.reason] is not a debugging aid, it is the feature. "Waiting until battery is above
 * 50% (now 34%)" is the difference between a background pass people trust and one that just
 * looks broken on the days it happens not to run -- see [WorkRuleEvaluator.evaluate] for how
 * every reason is built, always naming both the rule and the current value that failed it.
 */
sealed interface WorkVerdict {
    /** Every configured rule is satisfied right now (or [WorkRules.enabled] is false). */
    object Allowed : WorkVerdict

    /** @param reason Names the specific rule that blocked this AND the current value that failed it. */
    data class Blocked(val reason: String) : WorkVerdict
}

/**
 * Decides [WorkVerdict] from [WorkRules] and [DeviceState]. Pure, total, and side-effect free --
 * every branch is reachable from a JVM unit test with no Android in the picture; see
 * `WorkRuleEvaluatorTest`.
 */
object WorkRuleEvaluator {

    /**
     * The single source of truth for "is now a good time". Called from exactly one real place
     * ([com.fotoxplorr.app.background.BackgroundWorkJobService], each time the platform wakes
     * the job), plus everywhere the settings screen wants a live preview of what the current
     * rules would decide right now.
     *
     * @return [WorkVerdict.Allowed] when [WorkRules.enabled] is false OR every active rule is
     *   currently satisfied. Otherwise [WorkVerdict.Blocked] naming the FIRST rule that failed,
     *   checked in a fixed order -- see the comments inline below for why that order is not
     *   arbitrary and not alphabetical. A caller that wants to know about every unmet rule at
     *   once, not just the first, can call this repeatedly against [rules] copies with the
     *   already-reported rule relaxed; nothing here needs to change to support that, and no
     *   caller in this app currently wants it -- one clear reason beats a wall of five.
     */
    fun evaluate(rules: WorkRules, state: DeviceState): WorkVerdict {
        if (!rules.enabled) return WorkVerdict.Allowed

        // Checked hours -> idle -> charging -> battery -> network, and that order is chosen for
        // how STABLE each signal is, not for how the fields happen to be declared on WorkRules.
        // Idle is the NOISIEST thing on this list -- picking the phone up to check a
        // notification flips it false, setting it back down flips it true again, all inside a
        // few seconds -- so it is checked only after the hours-scale gate (the active-hours
        // window changes at most twice a day) and before the two power gates (charging state and
        // battery percent move over minutes, not seconds). Checking idle FIRST would make the
        // status line's reason flicker between "waiting for idle" and whatever the real, slow
        // reason is, dozens of times while someone just glances at their phone -- accurate at
        // each instant and useless as a sentence to read.
        if (!isWithinActiveHours(state.hourOfDay, rules.activeHoursStart, rules.activeHoursEnd)) {
            return WorkVerdict.Blocked(
                "Waiting for the active hours window, ${formatHourOfDay(rules.activeHoursStart)} to " +
                    "${formatHourOfDay(rules.activeHoursEnd)} (it's ${formatHourOfDay(state.hourOfDay)} now)",
            )
        }
        if (rules.requireIdle && !state.idle) {
            return WorkVerdict.Blocked("Waiting until the phone is idle (it's in use right now)")
        }
        if (rules.requireCharging && !state.charging) {
            return WorkVerdict.Blocked("Waiting for the charger (running on battery now)")
        }
        // Charging satisfies the battery-percent rule OUTRIGHT, independent of requireCharging.
        // A phone on the charger is not the phone this rule exists to protect, whatever number
        // was picked -- without this override, plugging in at 12% with the threshold set to 50%
        // would leave work sitting BLOCKED at exactly the moment the block is least justified,
        // and the reason line would read as actively wrong ("waiting for battery... while
        // plugged into the wall") rather than merely unhelpful.
        if (!state.charging && state.batteryPercent < rules.minBatteryPercent) {
            return WorkVerdict.Blocked(
                "Waiting for battery above ${rules.minBatteryPercent}% (now ${state.batteryPercent}%)",
            )
        }
        if (rules.onlyOnUnmetered && !state.unmetered) {
            return WorkVerdict.Blocked("Waiting for Wi-Fi (on a metered connection right now)")
        }
        return WorkVerdict.Allowed
    }

    /**
     * Whether [hour] (0..23) falls inside the active-hours window from [start] to [end], where
     * BOTH ends are inclusive and the window may WRAP PAST MIDNIGHT.
     *
     * Wrapping is the classic bug this function exists specifically to get right, which is also
     * why it is its own small function instead of one line inlined into [evaluate]: an overnight
     * window like 22..6 has `start > end`, and Kotlin's `a..b` range is EMPTY whenever `a > b` --
     * so the naive version (`hour in start..end`) silently blocks every hour, all day long, for
     * exactly the window shape ("run overnight") this feature exists to support. The fix is
     * branching on which of [start]/[end] is larger, and using OR instead of a single range test
     * once it wraps: `hour >= start || hour <= end` is true for 22, 23, 0, 1 ... up to [end], and
     * false for everything strictly between [end] and [start] -- see `WorkRuleEvaluatorTest` for
     * this pinned down in both directions (hours that must be INCLUDED by a wrapping window, and
     * the hour in its middle that must be EXCLUDED).
     *
     * [start] == [end] is defined as ALWAYS-ON, not "on for exactly that one hour" and not
     * "never". A single-hour window is a strange thing for a rule builder to produce on purpose
     * -- it is what dragging both handles of a range control to the same point looks like, which
     * reads as "I haven't narrowed this down" rather than "only between 3 and 4am". Always-on is
     * also what a fresh install ships with ([WorkRules]'s `0..23` default), so this keeps a
     * user-dragged zero-width window meaning the same thing as the shipped default instead of one
     * being a surprising special case of the other.
     *
     * Both endpoints are INCLUSIVE: a window of 22..6 covers the entire 22:00 hour and the entire
     * 06:00 hour, excluding only from 07:00 onward. An exclusive-end convention would have been
     * equally defensible -- this had to be SOME fixed choice, and inclusive-both-ends is the one
     * made here and pinned down by test.
     */
    internal fun isWithinActiveHours(hour: Int, start: Int, end: Int): Boolean {
        if (start == end) return true
        return if (start < end) hour in start..end else hour >= start || hour <= end
    }
}

/** Lowest battery percentage the rule builder's stepper allows -- "any battery is fine". */
const val MIN_BATTERY_PERCENT_ALLOWED = 0

/** Highest battery percentage the rule builder's stepper allows. */
const val MAX_BATTERY_PERCENT_ALLOWED = 100

/** The battery stepper's step size in the settings UI. */
const val BATTERY_PERCENT_STEP = 5

/** Shipped default: matches the owner's spec verbatim ("battery >= 20%"). */
const val DEFAULT_MIN_BATTERY_PERCENT = 20

/** Lowest valid hour-of-day value. */
const val MIN_HOUR_OF_DAY = 0

/** Highest valid hour-of-day value. */
const val MAX_HOUR_OF_DAY = 23

/** [hour] (0..23, out-of-range values clamped) as a 24-hour clock label -- "22:00", "06:00". */
fun formatHourOfDay(hour: Int): String =
    "%02d:00".format(hour.coerceIn(MIN_HOUR_OF_DAY, MAX_HOUR_OF_DAY))

/**
 * False when [WorkRules.activeHoursStart]/[WorkRules.activeHoursEnd] restrict nothing -- either
 * because they are equal (see [WorkRuleEvaluator.isWithinActiveHours] for why that means
 * always-on) or because they spell out the full day, 0 to 23, explicitly, which is the shipped
 * default and means the same thing by a different route. Used by the settings screen's plain-
 * language summary ([summarize]) to leave the hours window out of the sentence entirely rather
 * than announcing a restriction that does not actually restrict anything.
 */
val WorkRules.hasActiveHoursWindow: Boolean
    get() = activeHoursStart != activeHoursEnd &&
        !(activeHoursStart == MIN_HOUR_OF_DAY && activeHoursEnd == MAX_HOUR_OF_DAY)

/**
 * [rules] in plain language, for the settings screen's live summary -- e.g. "Indexing runs when
 * the phone is idle, between 22:00 and 06:00, with battery above 50%." Built from the same
 * fields [WorkRuleEvaluator.evaluate] reads, so the sentence and the actual behaviour cannot
 * drift apart the way a hand-written, separately-maintained description eventually would.
 */
fun summarize(rules: WorkRules): String {
    if (!rules.enabled) {
        return "Background rules are off -- indexing and other heavy passes run whenever the " +
            "system schedules them."
    }
    val clauses = buildList {
        if (rules.requireIdle) add("when the phone is idle")
        if (rules.hasActiveHoursWindow) {
            add("between ${formatHourOfDay(rules.activeHoursStart)} and ${formatHourOfDay(rules.activeHoursEnd)}")
        }
        if (rules.requireCharging) add("while charging")
        if (rules.minBatteryPercent > MIN_BATTERY_PERCENT_ALLOWED) {
            add("with battery above ${rules.minBatteryPercent}%")
        }
        if (rules.onlyOnUnmetered) add("on Wi-Fi")
    }
    return if (clauses.isEmpty()) {
        "Indexing runs anytime, with no conditions."
    } else {
        "Indexing runs " + clauses.joinToString(", ") + "."
    }
}
