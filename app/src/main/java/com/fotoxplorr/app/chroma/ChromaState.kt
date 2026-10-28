package com.fotoxplorr.app.chroma

/**
 * Where the capture is. One value at a time; a screen draws from it and nothing else.
 *
 * Idle (camera closed) -> Opening -> Ready (preview) -> Locking -> Locked -> Ambient (shot 1, flash
 * off) -> Flash (shot 2, flash on) -> Done; Failed or Cancelled from any step in between. The
 * permission has three states of its own before Opening. [reduce] is the whole table.
 */
internal sealed interface CaptureState {
    data object Idle : CaptureState

    /** The permission has not been asked for yet (or is being asked for now). */
    data object NeedsPermission : CaptureState

    data object PermissionDenied : CaptureState

    data object Opening : CaptureState

    /** The preview is running and nothing is held. */
    class Ready(val facts: CameraFacts) : CaptureState

    /** The torch is on and the camera is choosing and then holding an exposure. */
    class Locking(val facts: CameraFacts) : CaptureState

    /** The exposure is held; a measurement can start without choosing it again. */
    class Locked(val facts: CameraFacts) : CaptureState

    /** Shot 1 of 2: the flash is off. */
    class Ambient(val facts: CameraFacts) : CaptureState

    /** Shot 2 of 2: the flash is on. */
    class Flash(val facts: CameraFacts) : CaptureState

    /** Both shots are in and the answer is being worked out, or has been. */
    class Done(val facts: CameraFacts) : CaptureState

    class Failed(val failure: CaptureFailure, val facts: CameraFacts?) : CaptureState

    class Cancelled(val facts: CameraFacts) : CaptureState

    /** The camera's facts once it is open, else null. */
    val cameraFacts: CameraFacts?
        get() = when (this) {
            is Ready -> facts
            is Locking -> facts
            is Locked -> facts
            is Ambient -> facts
            is Flash -> facts
            is Done -> facts
            is Failed -> facts
            is Cancelled -> facts
            else -> null
        }
}

/** What can happen to the capture. */
internal sealed interface CaptureEvent {
    data object PermissionNeeded : CaptureEvent
    data object PermissionGranted : CaptureEvent
    data object PermissionRefused : CaptureEvent
    class Opened(val facts: CameraFacts) : CaptureEvent
    data object LockRequested : CaptureEvent
    data object LightLocked : CaptureEvent
    data object ShootRequested : CaptureEvent

    /** Shot 1 is in. With [ambientOnly] there is no second shot and the capture is finished. */
    class AmbientTaken(val ambientOnly: Boolean) : CaptureEvent
    data object FlashTaken : CaptureEvent
    class Fail(val failure: CaptureFailure) : CaptureEvent

    /** The person pressed Cancel. */
    data object Cancel : CaptureEvent

    /** The screen was left, paused or stopped: the camera is released. */
    data object Stopped : CaptureEvent

    /** Back to the preview, or to the held light when [keepLock] and one is held. */
    class Reset(val keepLock: Boolean) : CaptureEvent
}

/** The one transition table. An event that does not fit the state is ignored (the state is returned unchanged). */
internal fun reduce(state: CaptureState, event: CaptureEvent): CaptureState {
    if (event is CaptureEvent.Stopped) return CaptureState.Idle
    return when (state) {
        CaptureState.Idle -> when (event) {
            CaptureEvent.PermissionNeeded -> CaptureState.NeedsPermission
            CaptureEvent.PermissionGranted -> CaptureState.Opening
            CaptureEvent.PermissionRefused -> CaptureState.PermissionDenied
            else -> state
        }
        CaptureState.NeedsPermission, CaptureState.PermissionDenied -> when (event) {
            CaptureEvent.PermissionNeeded -> CaptureState.NeedsPermission
            CaptureEvent.PermissionGranted -> CaptureState.Opening
            CaptureEvent.PermissionRefused -> CaptureState.PermissionDenied
            else -> state
        }
        CaptureState.Opening -> when (event) {
            is CaptureEvent.Opened -> CaptureState.Ready(event.facts)
            is CaptureEvent.Fail -> CaptureState.Failed(event.failure, null)
            else -> state
        }
        is CaptureState.Ready -> when (event) {
            CaptureEvent.LockRequested -> CaptureState.Locking(state.facts)
            is CaptureEvent.Fail -> CaptureState.Failed(event.failure, state.facts)
            else -> state
        }
        is CaptureState.Locking -> when (event) {
            CaptureEvent.LightLocked -> CaptureState.Locked(state.facts)
            CaptureEvent.Cancel -> CaptureState.Cancelled(state.facts)
            is CaptureEvent.Fail -> CaptureState.Failed(event.failure, state.facts)
            else -> state
        }
        is CaptureState.Locked -> when (event) {
            CaptureEvent.ShootRequested -> CaptureState.Ambient(state.facts)
            CaptureEvent.LockRequested -> CaptureState.Locking(state.facts)
            is CaptureEvent.Reset -> CaptureState.Ready(state.facts)
            is CaptureEvent.Fail -> CaptureState.Failed(event.failure, state.facts)
            else -> state
        }
        is CaptureState.Ambient -> when (event) {
            is CaptureEvent.AmbientTaken ->
                if (event.ambientOnly) CaptureState.Done(state.facts) else CaptureState.Flash(state.facts)
            CaptureEvent.Cancel -> CaptureState.Cancelled(state.facts)
            is CaptureEvent.Fail -> CaptureState.Failed(event.failure, state.facts)
            else -> state
        }
        is CaptureState.Flash -> when (event) {
            CaptureEvent.FlashTaken -> CaptureState.Done(state.facts)
            CaptureEvent.Cancel -> CaptureState.Cancelled(state.facts)
            is CaptureEvent.Fail -> CaptureState.Failed(event.failure, state.facts)
            else -> state
        }
        is CaptureState.Done -> when (event) {
            is CaptureEvent.Reset -> if (event.keepLock) CaptureState.Locked(state.facts) else CaptureState.Ready(state.facts)
            is CaptureEvent.Fail -> CaptureState.Failed(event.failure, state.facts)
            else -> state
        }
        is CaptureState.Cancelled -> when (event) {
            is CaptureEvent.Reset -> CaptureState.Ready(state.facts)
            else -> state
        }
        is CaptureState.Failed -> when (event) {
            // A failure that closed the camera leaves nothing to go back to; the screen opens it again.
            is CaptureEvent.Reset ->
                if (state.facts != null && !state.failure.closesCamera) CaptureState.Ready(state.facts) else CaptureState.Idle
            CaptureEvent.PermissionGranted -> CaptureState.Opening
            else -> state
        }
    }
}

/** What a finished measurement shows. */
internal sealed interface Outcome {
    /** A colour. [withCard] says whether a card normalised the flash. */
    class Reading(val reading: ChromaReading, val withCard: Boolean) : Outcome

    /** A colour read without a flash: the room's own light is still in it. */
    class RoomLight(val report: ColourReport) : Outcome

    /** The card's two shots are in and kept as numbers; the surface is next. */
    data object CardKept : Outcome

    class Refused(val reason: Refusal) : Outcome
}
