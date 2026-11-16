package com.fotoxplorr.app.sources.drives

/** Everything the lifecycle reacts to. Plain data, so a JVM test can replay any sequence of plugs. */
sealed interface DriveEvent {
    /** The platform's current list of mounted drives; the machine diffs it against what it saw last. */
    data class Snapshot(val mounted: List<DriveVolume>) : DriveEvent

    data class Connected(val volume: DriveVolume) : DriveEvent

    data class Disconnected(val uuid: String) : DriveEvent

    /** The uuids of drives that already have a source (kept in sync with the runtime's list). */
    data class SourcesChanged(val known: Set<String>) : DriveEvent

    data class Chose(val uuid: String, val choice: DriveChoice) : DriveEvent

    /** The folder picker for [uuid] came back; [added] is false when the person left it. */
    data class PickerClosed(val uuid: String, val added: Boolean) : DriveEvent

    /** Undo of "Never for this drive". */
    data class AskAgain(val uuid: String) : DriveEvent

    data class SettingsLoaded(val settings: Map<String, DriveSettings>) : DriveEvent
}

/** What the outside world must do. The machine never touches Android, files or the database. */
sealed interface DriveEffect {
    /**
     * Mark the sources on this drive OFFLINE or ONLINE. Never a delete. [indexNow] is only ever true
     * when the person turned "read again when it is there" on for this drive.
     */
    data class SetReachable(val uuid: String, val reachable: Boolean, val indexNow: Boolean = false) : DriveEffect

    /** Stop a read that is running on this drive (it keeps its checkpoint). */
    data class StopIndex(val uuid: String) : DriveEffect

    /** Open the folder picker pointed at the drive. Only ever the result of the person pressing Add. */
    data class OpenPicker(val volume: DriveVolume) : DriveEffect

    data class SaveSettings(val uuid: String, val settings: DriveSettings) : DriveEffect
}

/**
 * The machine's whole memory. [offers] is the queue the card shows from the front; [snoozed] holds
 * drives the person said "Not now" to, until they are unplugged (the next plug asks again).
 */
data class DriveState(
    val mounted: Map<String, DriveVolume> = emptyMap(),
    val known: Set<String> = emptySet(),
    val settings: Map<String, DriveSettings> = emptyMap(),
    val offers: List<DriveVolume> = emptyList(),
    val snoozed: Set<String> = emptySet(),
    val picking: Set<String> = emptySet(),
)

data class DriveStep(val state: DriveState, val effects: List<DriveEffect> = emptyList())

/**
 * "Drive connected -> offer -> the person chose" as a pure function. The rules, in one place:
 *
 *  - A drive whose uuid already has a source is RECOGNISED: it is marked reachable again, nothing is
 *    offered, nothing is duplicated, and a read starts only when the person turned it on for that drive.
 *  - An unknown drive is offered once per plug, unless the person said "Never for this drive".
 *  - Nothing here starts a scan or a permission prompt: the only way to [DriveEffect.OpenPicker] is [DriveChoice.ADD].
 *  - Unplugging stops a running read, marks the drive's sources OFFLINE and forgets any open offer.
 *    Nothing is ever deleted, so a replug finds everything as it was.
 */
object DriveStateMachine {
    fun reduce(state: DriveState, event: DriveEvent): DriveStep = when (event) {
        is DriveEvent.SettingsLoaded -> DriveStep(state.copy(settings = event.settings))
        is DriveEvent.Snapshot -> snapshot(state, event.mounted)
        is DriveEvent.Connected -> connected(state, event.volume)
        is DriveEvent.Disconnected -> disconnected(state, DriveIds.normalise(event.uuid) ?: event.uuid)
        is DriveEvent.SourcesChanged -> sourcesChanged(state, event.known.mapNotNullTo(HashSet()) { DriveIds.normalise(it) })
        is DriveEvent.Chose -> chose(state, event)
        is DriveEvent.PickerClosed -> pickerClosed(state, event)
        is DriveEvent.AskAgain -> askAgain(state, event.uuid)
    }

    private fun snapshot(state: DriveState, now: List<DriveVolume>): DriveStep {
        val nowIds = now.map { it.uuid }.toSet()
        var s = state
        val effects = ArrayList<DriveEffect>()
        for (gone in state.mounted.keys - nowIds) {
            val step = disconnected(s, gone)
            s = step.state
            effects += step.effects
        }
        for (v in now) {
            if (v.uuid in state.mounted) continue
            val step = connected(s, v)
            s = step.state
            effects += step.effects
        }
        // A source whose drive is not there at all (first look after a launch) is offline.
        for (uuid in s.known) if (uuid !in nowIds && uuid !in state.mounted) effects += DriveEffect.SetReachable(uuid, reachable = false)
        return DriveStep(s, effects)
    }

    private fun connected(state: DriveState, volume: DriveVolume): DriveStep {
        val uuid = volume.uuid
        val s = state.copy(mounted = state.mounted + (uuid to volume))
        val mine = s.settings[uuid] ?: DriveSettings()
        if (uuid in s.known) {
            return DriveStep(
                s.copy(offers = s.offers.filterNot { it.uuid == uuid }),
                listOf(DriveEffect.SetReachable(uuid, reachable = true, indexNow = mine.indexOnConnect)),
            )
        }
        if (mine.never || uuid in s.snoozed || uuid in s.picking || s.offers.any { it.uuid == uuid }) return DriveStep(s)
        return DriveStep(s.copy(offers = s.offers + volume))
    }

    private fun disconnected(state: DriveState, uuid: String): DriveStep {
        val s = state.copy(
            mounted = state.mounted - uuid,
            offers = state.offers.filterNot { it.uuid == uuid },
            snoozed = state.snoozed - uuid,
            picking = state.picking - uuid,
        )
        if (uuid !in state.known) return DriveStep(s)
        return DriveStep(s, listOf(DriveEffect.StopIndex(uuid), DriveEffect.SetReachable(uuid, reachable = false)))
    }

    private fun sourcesChanged(state: DriveState, known: Set<String>): DriveStep {
        // A drive whose source the person just removed is not offered again until it is replugged.
        val removed = (state.known - known).filter { it in state.mounted }.toSet()
        val s = state.copy(
            known = known,
            offers = state.offers.filterNot { it.uuid in known },
            snoozed = state.snoozed + removed,
            picking = state.picking - known,
        )
        // A source that has just become known meets the drive as it is now: there (back online) or not (offline).
        val effects = (known - state.known).map { uuid ->
            if (uuid in state.mounted) DriveEffect.SetReachable(uuid, true, (state.settings[uuid] ?: DriveSettings()).indexOnConnect)
            else DriveEffect.SetReachable(uuid, false)
        }
        return DriveStep(s, effects)
    }

    private fun chose(state: DriveState, e: DriveEvent.Chose): DriveStep {
        val uuid = DriveIds.normalise(e.uuid) ?: e.uuid
        val volume = state.offers.firstOrNull { it.uuid == uuid } ?: return DriveStep(state)
        val offers = state.offers.filterNot { it.uuid == uuid }
        val base = (state.settings[uuid] ?: DriveSettings()).copy(label = volume.label, asked = true)
        return when (e.choice) {
            DriveChoice.ADD -> {
                val next = base.copy(never = false)
                DriveStep(
                    state.copy(offers = offers, picking = state.picking + uuid, settings = state.settings + (uuid to next)),
                    listOf(DriveEffect.SaveSettings(uuid, next), DriveEffect.OpenPicker(volume)),
                )
            }
            DriveChoice.NOT_NOW -> DriveStep(
                state.copy(offers = offers, snoozed = state.snoozed + uuid, settings = state.settings + (uuid to base)),
                listOf(DriveEffect.SaveSettings(uuid, base)),
            )
            DriveChoice.NEVER -> {
                val next = base.copy(never = true)
                DriveStep(
                    state.copy(offers = offers, settings = state.settings + (uuid to next)),
                    listOf(DriveEffect.SaveSettings(uuid, next)),
                )
            }
        }
    }

    private fun pickerClosed(state: DriveState, e: DriveEvent.PickerClosed): DriveStep {
        val uuid = DriveIds.normalise(e.uuid) ?: e.uuid
        val snoozed = if (e.added) state.snoozed else state.snoozed + uuid
        return DriveStep(state.copy(picking = state.picking - uuid, snoozed = snoozed))
    }

    private fun askAgain(state: DriveState, rawUuid: String): DriveStep {
        val uuid = DriveIds.normalise(rawUuid) ?: rawUuid
        val next = (state.settings[uuid] ?: DriveSettings()).copy(never = false)
        val volume = state.mounted[uuid]
        val offers = if (volume != null && uuid !in state.known && state.offers.none { it.uuid == uuid }) state.offers + volume else state.offers
        return DriveStep(
            state.copy(settings = state.settings + (uuid to next), offers = offers, snoozed = state.snoozed - uuid),
            listOf(DriveEffect.SaveSettings(uuid, next)),
        )
    }
}
