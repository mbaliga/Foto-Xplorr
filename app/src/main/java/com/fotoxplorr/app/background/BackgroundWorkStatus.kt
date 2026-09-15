package com.fotoxplorr.app.background

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * What Foto Xplorr's one background job is doing right now, for the settings screen's "right
 * now" status line (SettingsTabs.kt's BACKGROUND tab).
 *
 * Deliberately a DIFFERENT shape from [com.fotoxplorr.app.recognition.RecognitionProgress] and
 * [com.fotoxplorr.app.hyle.BackgroundActivity], and not merged with either, because it answers a
 * different question. Those describe how far through the library a pass has got --
 * `completed`/`total`. This describes whether the GATE that lets a pass start is open or shut,
 * and -- the entire point of this type -- WHY, when it is shut. The two are independent: the
 * gate can be wide open with nothing behind it ([Pending], zero photos pending), or shut with a
 * large backlog waiting ([Blocked], thousands pending). A single merged state would have to
 * describe both halves of that at once, and conflating "is it allowed to run" with "how much is
 * left" is exactly how a genuinely stuck gate gets misread as a slow scan, or the reverse.
 *
 * Held in [BackgroundWorkStatusCenter] -- see that object's KDoc for why this needs true
 * process-wide singleton semantics that [WorkRulesStore] deliberately does not.
 */
sealed interface BackgroundWorkStatus {
    /** Nothing has reported in yet: a fresh process, or the platform job has never fired. */
    object Unknown : BackgroundWorkStatus

    /** [WorkRules.enabled] is false. No gate at all -- there is nothing here to wait on. */
    object Unrestricted : BackgroundWorkStatus

    /** Scheduled with the platform; satisfied the last time it was checked; waiting its turn. */
    object Pending : BackgroundWorkStatus

    /** The rules were satisfied and the job is doing its pass right now. */
    object Running : BackgroundWorkStatus

    /**
     * The rules were NOT satisfied the last time the job ran.
     *
     * @param reason [WorkVerdict.Blocked.reason] verbatim -- see [WorkRuleEvaluator.evaluate]
     *   for why this is written for a person, not a log line, and always names both the rule and
     *   the current value that failed it.
     * @param checkedAt [System.currentTimeMillis] at the moment this was decided, so the status
     *   line can honestly say "as of" a time rather than imply this is watched continuously. It
     *   is not -- the platform checks roughly once per [BackgroundScheduler]'s poll interval, not
     *   live -- and showing a stale reason as though it were current would be exactly the kind of
     *   guarantee the OS does not give that this feature must never imply.
     */
    data class Blocked(val reason: String, val checkedAt: Long) : BackgroundWorkStatus
}

/**
 * [BackgroundWorkStatus] in the user's own terms -- no "JobScheduler", no "constraint", nothing
 * that assumes the reader knows what either of those are.
 *
 * A tiny, pure mapping, kept separate from the [BackgroundWorkStatus] type itself so it can be
 * pinned by `BackgroundWorkStatusTest` independent of how the status is produced or stored.
 */
fun BackgroundWorkStatus.describe(): String = when (this) {
    BackgroundWorkStatus.Unknown -> "Not checked yet."
    BackgroundWorkStatus.Unrestricted ->
        "Background rules are off -- indexing runs whenever the system schedules it, with nothing held back."
    BackgroundWorkStatus.Pending -> "Scheduled. Waiting for the system to give it a turn."
    BackgroundWorkStatus.Running -> "Running now."
    is BackgroundWorkStatus.Blocked -> reason
}

/**
 * The one live [BackgroundWorkStatus] for the app's whole background job.
 *
 * A plain `object`, not an injectable class, and that is a narrower choice than it looks. Compare
 * [WorkRulesStore]: that class may safely be constructed more than once -- the settings screen's
 * tab does this on every composition, matching the existing PRO tab's `LocalProEntitlement`
 * pattern in SettingsTabs.kt -- because every instance loads the same truth from the same
 * SharedPreferences file, so two instances can only ever disagree about WHEN each learns of a
 * change the other made, never about what was actually saved.
 *
 * Status has no backing store to reconverge from that way: it is purely in-memory, and it is
 * written by whichever process is running [BackgroundWorkJobService] at the time, which is not
 * the same construction as whatever is reading it from the settings screen -- the system starts
 * the job service on its own schedule, independent of any UI. If each side held its own
 * `MutableStateFlow`, an update from the job would simply never reach the screen's flow at all,
 * not merely arrive late the way two SharedPreferences-backed instances can. A single process-
 * wide `object` is what guarantees the job and the settings composable are reading and writing
 * the SAME flow: [BackgroundWorkJobService] declares no `android:process` in the manifest, so it
 * runs in this app's default process, and the JVM hands every reader of this object the one
 * instance that exists for the life of that process.
 */
object BackgroundWorkStatusCenter {
    private val state = MutableStateFlow<BackgroundWorkStatus>(BackgroundWorkStatus.Unknown)

    val status: StateFlow<BackgroundWorkStatus> = state.asStateFlow()

    /**
     * Not private: [BackgroundScheduler] and [BackgroundWorkJobService] both publish updates
     * from outside this file. Nothing else in the app should call this -- everyone else only
     * ever reads [status].
     */
    internal fun publish(update: BackgroundWorkStatus) {
        state.value = update
    }
}
