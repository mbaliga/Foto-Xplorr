package com.fotoxplorr.app.sources.drives

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/** What the controller needs from the sources side, so the lifecycle runs in tests without a database. */
interface DriveSourcePort {
    /** The normalised uuids of drives that have a source, whenever that list changes. */
    fun knownVolumes(): Flow<Set<String>>

    /** Marks the drive's sources OFFLINE or back ONLINE and, when [indexNow] or the source asks for it, reads them again. */
    suspend fun setReachable(uuid: String, reachable: Boolean, indexNow: Boolean)

    /** Stops a read running on the drive (its checkpoint stays). */
    suspend fun stopIndex(uuid: String)
}

/**
 * Runs [DriveStateMachine] against the world: events in, effects out, in order, on one coroutine.
 * It starts nothing on its own account: no scan, no picker, no permission request is reachable
 * except through [DriveEvent.Chose] with [DriveChoice.ADD], which is the person pressing Add.
 */
class DriveController(
    private val store: DriveSettingsStore,
    private val port: DriveSourcePort,
    private val scope: CoroutineScope,
) {
    private val lock = Any()
    private var state = DriveState(settings = store.all().value)
    private val offersState = MutableStateFlow<List<DriveVolume>>(emptyList())
    private val effects = Channel<DriveEffect>(Channel.UNLIMITED)
    private val pickers = MutableSharedFlow<DriveVolume>(extraBufferCapacity = 8)
    private var started = false

    /** The drives waiting for an answer; the card shows the first. Empty most of the time. */
    val offers: StateFlow<List<DriveVolume>> = offersState.asStateFlow()

    /** Emits once per press of Add: the host opens the folder picker for this drive. */
    val pickerRequests: SharedFlow<DriveVolume> = pickers.asSharedFlow()

    /** Drives the person said "Never for this drive" to, for the list that lets them change their mind. */
    fun ignored(): Flow<List<IgnoredDrive>> = store.all().map { all ->
        all.filterValues { it.never }.map { (uuid, s) -> IgnoredDrive(uuid, s.label.ifBlank { uuid }) }.sortedBy { it.uuid }
    }

    /** Begins consuming effects and following the runtime's list of drives that have a source. Idempotent. */
    fun start() {
        synchronized(lock) { if (started) return; started = true }
        scope.launch { for (e in effects) runCatching { handle(e) } }
        scope.launch { port.knownVolumes().collect { dispatch(DriveEvent.SourcesChanged(it)) } }
    }

    fun dispatch(event: DriveEvent) {
        val step = synchronized(lock) {
            DriveStateMachine.reduce(state, event).also {
                state = it.state
                offersState.value = it.state.offers
            }
        }
        for (e in step.effects) effects.trySend(e)
    }

    fun choose(uuid: String, choice: DriveChoice) = dispatch(DriveEvent.Chose(uuid, choice))

    fun pickerClosed(uuid: String, added: Boolean) = dispatch(DriveEvent.PickerClosed(uuid, added))

    fun askAgain(uuid: String) = dispatch(DriveEvent.AskAgain(uuid))

    private suspend fun handle(effect: DriveEffect) {
        when (effect) {
            is DriveEffect.SaveSettings -> store.update(effect.uuid) { effect.settings }
            is DriveEffect.OpenPicker -> pickers.tryEmit(effect.volume)
            is DriveEffect.SetReachable -> port.setReachable(effect.uuid, effect.reachable, effect.indexNow)
            is DriveEffect.StopIndex -> port.stopIndex(effect.uuid)
        }
    }
}
