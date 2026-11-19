package com.fotoxplorr.app.sources.previews

import com.fotoxplorr.app.media.MediaAsset
import com.fotoxplorr.app.sources.drives.PreviewPolicy as DrivePreviewPolicy
import com.fotoxplorr.app.sources.runtime.SourceStatus
import com.fotoxplorr.app.sources.ui.PreviewCost
import com.fotoxplorr.core.index.sources.SourceState
import kotlinx.coroutines.delay

/** The cap choices a person gets per source, in bytes counted the way a drive's label counts (1 MB = 1,000,000). */
object PreviewCaps {
    const val OFF: Long = 0L
    const val MB_250: Long = 250_000_000L
    const val GB_1: Long = PreviewPolicy.DEFAULT_CAP_BYTES
    const val GB_2: Long = 2_000_000_000L

    val choices: List<Long> = listOf(OFF, MB_250, GB_1, GB_2)

    fun label(capBytes: Long): String = when (capBytes) {
        OFF -> PreviewsCopy.CAP_OFF
        MB_250 -> PreviewsCopy.CAP_250
        GB_1 -> PreviewsCopy.CAP_1G
        GB_2 -> PreviewsCopy.CAP_2G
        else -> PreviewsCopy.CAP_CUSTOM
    }

    /** The choice that is selected for a stored cap: the exact one, else the largest choice not above it. */
    fun selected(capBytes: Long): Long = choices.lastOrNull { it <= capBytes } ?: OFF
}

/** What the keep switch and the cap chips show for one source. [keepShown] is false while previews are off, whatever was stored. */
data class PreviewControls(val keepShown: Boolean, val keepEnabled: Boolean, val cap: Long) {
    companion object {
        fun of(keep: Boolean, capBytes: Long): PreviewControls {
            val cap = PreviewCaps.selected(capBytes)
            val on = !PreviewPolicy.isOff(cap)
            return PreviewControls(keepShown = keep && on, keepEnabled = on, cap = cap)
        }
    }
}

/**
 * How a drive's remembered [DrivePreviewPolicy] becomes the two per-source settings. The drive's
 * policy is the person's standing wish for that drive; the per-source card is where they fine-tune it.
 *
 * - NONE: previews off (cap 0, so nothing is written and what exists is evicted), keep off.
 * - AS_VIEWED: keep off (no background pass), cap on (previews are made as tiles are looked at).
 * - ALL: keep on (the bounded pass fills them while the drive is there), cap on.
 *
 * "Cap on" means the cap the person already has if it is above 0, else the 1 GB default, so a
 * larger or smaller cap chosen on the card survives. The mapping is applied when a recognised drive
 * reconnects, and only if the policy differs from the one last applied to that source: a switch the
 * person flipped on the card is not undone by every plug-in, only by a new answer for the drive.
 */
object DrivePreviewMapping {
    fun target(policy: DrivePreviewPolicy, currentCap: Long): Pair<Boolean, Long> {
        val onCap = if (PreviewPolicy.isOff(currentCap)) PreviewPolicy.DEFAULT_CAP_BYTES else currentCap
        return when (policy) {
            DrivePreviewPolicy.NONE -> false to PreviewCaps.OFF
            DrivePreviewPolicy.AS_VIEWED -> false to onCap
            DrivePreviewPolicy.ALL -> true to onCap
        }
    }

    /** Applies [policy] to every row whose last applied policy differs; returns the rows that changed. [enforce] evicts after a cap went down. */
    suspend fun apply(policy: DrivePreviewPolicy, rows: List<Long>, settings: PreviewSettings, enforce: suspend (Long) -> Unit): List<Long> {
        val changed = ArrayList<Long>()
        for (row in rows) {
            if (settings.appliedDrivePolicy(row) == policy.wire) continue
            val (keep, cap) = target(policy, settings.capBytes(row))
            settings.setKeep(row, keep)
            settings.setCapBytes(row, cap)
            settings.setAppliedDrivePolicy(row, policy.wire)
            enforce(row)
            changed += row
        }
        return changed
    }
}

/** The storage panel's [PreviewCost] over what the store reports; a source with nothing stored answers null ("not kept"). */
class StorePreviewCost(private val sizes: () -> Map<Long, Long>) : PreviewCost {
    override fun bytesFor(sourceRow: Long): Long? = sizes()[sourceRow]?.takeIf { it > 0L }
}

/**
 * Runs the bounded background pass with the right input and only when there is something to do.
 * TRAPS #33: [drive] applies the activity's browsable filter itself to whatever list it is handed,
 * so the pass can never see a locked, archived or sensitive-hidden photo even if a caller passes
 * the merged catalogue.
 */
object PreviewPassDriver {
    fun interface Pass {
        suspend fun run(browsable: List<MediaAsset>, onlineRows: Set<Long>, limit: Int): Int
    }

    const val MAX_ROUNDS = 40
    const val PAUSE_MS = 1_500L

    /** Rows of the sources that can be read right now. OFFLINE, REVOKED and ERROR sources are not. */
    fun onlineRows(statuses: List<SourceStatus>): Set<Long> =
        statuses.filter { it.info.state == SourceState.ONLINE }.mapTo(HashSet()) { it.info.row }

    /**
     * Rounds of at most [limit] previews, a [pause] between them, until a round makes fewer than
     * [limit] (everything wanted is stored) or [maxRounds] is reached; the next change of the list
     * starts again. With no online source, or no source asset in [assets], nothing runs and
     * [browsable] is not called. Returns the previews made.
     */
    suspend fun drive(
        assets: List<MediaAsset>,
        onlineRows: Set<Long>,
        browsable: (List<MediaAsset>) -> List<MediaAsset>,
        pass: Pass,
        limit: Int = PreviewPass.DEFAULT_LIMIT,
        maxRounds: Int = MAX_ROUNDS,
        pause: suspend () -> Unit = { delay(PAUSE_MS) },
    ): Int {
        if (onlineRows.isEmpty() || assets.none { it.sourceRef != null }) return 0
        val list = browsable(assets)
        var total = 0
        repeat(maxRounds) {
            val made = pass.run(list, onlineRows, limit)
            total += made
            if (made < limit) return total
            pause()
        }
        return total
    }
}
