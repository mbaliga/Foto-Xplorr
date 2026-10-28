package com.fotoxplorr.app.chroma

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Which thing a measurement is of. */
internal enum class ShotRole { SURFACE, CARD }

/**
 * Runs the camera through one measurement at a time and keeps [state] and [outcome] for the screen.
 *
 * ## What a measurement does
 * 1. Unless a lock is wanted to be reused, the light is locked ([CameraSource.lockLight]).
 * 2. Shot 1 with the flash off, shot 2 with it on, both under that lock.
 * 3. The camera's flash and focus are released, then the two checks that need the facts of the
 *    frames: the gyroscope saw no movement between the shots ([MotionWindow.moved]) and the camera
 *    says the exposure and white balance did not change ([LockCheck]).
 * 4. Off the main thread, [ChromaMeter] turns the two frames into an [Outcome]. Unless a card is kept to be
 *    used under the held light, the light is then let go of (so the preview and its live guidance come back).
 *
 * ## The card
 * A card is only good for the exposure it was shot under, so the card's PATCHES (numbers, no
 * picture) are kept while that lock is held, and dropped the moment the lock is let go of, redone,
 * or the job stopped. The surface is then measured with `relock = false` to reuse the lock.
 *
 * ## What is kept
 * Frames are in memory only and are never written anywhere: this class has no way to (it holds no
 * Context, no ContentResolver, no file). Both pictures are overwritten with zeros as soon as the
 * outcome is worked out, on every path out (cancel, failure, stop included). The outcome holds
 * numbers and words only.
 *
 * Call everything from one thread (the main thread). [stop] releases the camera and is safe to call
 * at any time and more than once.
 */
internal class ChromaJob(
    private val source: CameraSource,
    private val motion: MotionWindow,
    private val scope: CoroutineScope,
    private val compute: CoroutineDispatcher = Dispatchers.Default,
) {
    private val _state = MutableStateFlow<CaptureState>(CaptureState.Idle)
    val state: StateFlow<CaptureState> = _state.asStateFlow()

    private val _outcome = MutableStateFlow<Outcome?>(null)
    val outcome: StateFlow<Outcome?> = _outcome.asStateFlow()

    private val _cardKept = MutableStateFlow(false)

    /** True while a card's patches are held for the current lock. */
    val cardKept: StateFlow<Boolean> = _cardKept.asStateFlow()

    private var work: Job? = null
    private var card: CardPatches? = null
    private var cardFacts: ShotFacts? = null

    private fun dispatch(event: CaptureEvent) {
        _state.update { reduce(it, event) }
    }

    // ---- permission and opening ----------------------------------------------------------------------

    /** The permission has not been granted: the screen shows the reason and asks. */
    fun needPermission() = dispatch(CaptureEvent.PermissionNeeded)

    /** The answer to the permission question; granting it opens the camera. */
    fun permissionResult(granted: Boolean) {
        if (granted) open() else dispatch(CaptureEvent.PermissionRefused)
    }

    /** Opens the camera. Only from a closed state; otherwise nothing happens. */
    fun open() {
        val s = _state.value
        if (s !is CaptureState.Idle && s !is CaptureState.NeedsPermission &&
            s !is CaptureState.PermissionDenied && s !is CaptureState.Failed
        ) {
            return
        }
        dispatch(CaptureEvent.PermissionGranted)
        if (_state.value != CaptureState.Opening) return
        work = scope.launch {
            try {
                source.open()
                if (source.facts.lockKind == LockKind.NONE) {
                    source.close()
                    dispatch(CaptureEvent.Fail(CaptureFailure.NO_LOCK))
                } else {
                    dispatch(CaptureEvent.Opened(source.facts))
                }
            } catch (c: CancellationException) {
                throw c
            } catch (p: CameraProblem) {
                source.close()
                dispatch(CaptureEvent.Fail(p.failure))
            } catch (e: Exception) {
                source.close()
                dispatch(CaptureEvent.Fail(CaptureFailure.FAILED))
            }
        }
    }

    // ---- measuring ------------------------------------------------------------------------------------

    /**
     * Measures [role].
     *
     * @param ambientOnly take only the flash-off shot (a phone with no flash, or a person who chose
     *   to read the room's light); the outcome is then a [Outcome.RoomLight].
     * @param relock choose and hold the light afresh. False reuses the held light, and is only
     *   honoured when one is held (state Locked); it is how the surface is measured under the
     *   card's light.
     */
    fun measure(role: ShotRole, ambientOnly: Boolean = false, relock: Boolean = true) {
        val s = _state.value
        if (work?.isActive == true) return
        if (s !is CaptureState.Ready && s !is CaptureState.Locked) return
        val lockFirst = s is CaptureState.Ready || relock
        _outcome.value = null
        work = scope.launch { run(s.cameraFacts!!, role, ambientOnly, lockFirst) }
    }

    private suspend fun run(facts: CameraFacts, role: ShotRole, ambientOnly: Boolean, lockFirst: Boolean) {
        val shots = ArrayList<Shot>(2)
        try {
            if (lockFirst) {
                dropCard()
                dispatch(CaptureEvent.LockRequested)
                source.lockLight(torch = facts.hasFlash && !ambientOnly)
                dispatch(CaptureEvent.LightLocked)
            }
            dispatch(CaptureEvent.ShootRequested)
            source.focus(torch = facts.hasFlash && !ambientOnly)
            motion.beginWindow()
            val ambient = source.shoot(torch = false).also { shots += it }
            dispatch(CaptureEvent.AmbientTaken(ambientOnly))
            val flash = if (ambientOnly) null else source.shoot(torch = true).also { shots += it }
            source.endShots()
            motion.endWindow()
            if (motion.moved) throw CameraProblem(CaptureFailure.MOVED)
            if (flash != null && LockCheck.slipped(ambient.facts, flash.facts)) throw CameraProblem(CaptureFailure.LOCK_SLIPPED)
            val held = cardFacts
            if (!lockFirst && held != null && LockCheck.slipped(held, ambient.facts)) throw CameraProblem(CaptureFailure.LOCK_SLIPPED)
            if (flash != null) dispatch(CaptureEvent.FlashTaken)

            val heldCard = card
            val (result, newCard) = withContext(compute) { conclude(role, ambient, flash, heldCard) }
            if (newCard != null) {
                // Kept as numbers for this lock; the pictures are cleared in the finally below.
                card = newCard
                cardFacts = flash!!.facts
                _cardKept.value = true
            }
            // The held light is only for a card to be used under; with none kept it would leave the preview at
            // the torch-lit exposure and the live guidance frozen.
            if (card == null) releaseLight()
            _outcome.value = result
            if (newCard != null) dispatch(CaptureEvent.Reset(keepLock = true))
        } catch (c: CancellationException) {
            motion.endWindow()
            dropCard()
            withContext(NonCancellable) { runCatching { source.endShots(); source.unlock() } }
            throw c
        } catch (p: CameraProblem) {
            fail(p.failure)
        } catch (e: Exception) {
            fail(CaptureFailure.FAILED)
        } finally {
            motion.endWindow()
            shots.forEach { it.clear() }
        }
    }

    /**
     * Works out the answer, and the card's patches when [role] is the card and it read well. Runs off the
     * main thread and touches no field of the job: it only reads what it is handed.
     */
    private fun conclude(role: ShotRole, ambient: Shot, flash: Shot?, heldCard: CardPatches?): Pair<Outcome, CardPatches?> {
        if (flash == null) {
            return when (val r = AmbientOnly.read(ambient.frame)) {
                is Answer.Value -> Outcome.RoomLight(r.value)
                is Answer.Refused -> Outcome.Refused(r.reason)
            } to null
        }
        val pair = FlashPair(ambient.frame, flash.frame, distanceMetres = flash.distanceMetres)
        return when (role) {
            ShotRole.CARD -> when (val c = ChromaMeter.sampleCard(CardPair(pair))) {
                is Answer.Value -> Outcome.CardKept to c.value
                is Answer.Refused -> Outcome.Refused(c.reason) to null
            }
            ShotRole.SURFACE -> when (val r = ChromaMeter.measureAgainst(pair, heldCard)) {
                is Answer.Value -> Outcome.Reading(r.value, withCard = heldCard != null) to null
                is Answer.Refused -> Outcome.Refused(r.reason) to null
            }
        }
    }

    private suspend fun fail(failure: CaptureFailure) {
        dropCard()
        dispatch(CaptureEvent.Fail(failure))
        withContext(NonCancellable) {
            if (failure.closesCamera) {
                source.close()
            } else {
                runCatching { source.endShots(); source.unlock() }
            }
        }
    }

    private suspend fun releaseLight() {
        try {
            source.unlock()
        } catch (c: CancellationException) {
            throw c
        } catch (e: Exception) {
            // A camera that is already gone has no light to let go of.
        }
    }

    private fun dropCard() {
        card = null
        cardFacts = null
        _cardKept.value = false
    }

    // ---- after a measurement ----------------------------------------------------------------------------

    /**
     * Back to the preview (or, with a card kept, to the held light). Clears the outcome. From Failed it
     * goes to Idle when the camera had to be closed, and the screen then opens it again.
     */
    fun again() {
        _outcome.value = null
        dispatch(CaptureEvent.Reset(keepLock = card != null))
    }

    /** Lets go of the held light and any card measured under it. */
    fun forgetCard() {
        if (_state.value is CaptureState.Locked && work?.isActive != true) {
            dropCard()
            dispatch(CaptureEvent.Reset(keepLock = false))
            scope.launch { runCatching { source.unlock() } }
        } else {
            dropCard()
        }
    }

    /** The person pressed Cancel during a measurement. The camera stays open, the pictures are cleared. */
    fun cancel() {
        val s = _state.value
        // Once both shots are in, the answer takes a moment and there is nothing left to cancel.
        if (work?.isActive != true || (s !is CaptureState.Locking && s !is CaptureState.Ambient && s !is CaptureState.Flash)) return
        dispatch(CaptureEvent.Cancel)
        work?.cancel()
        work = null
    }

    /** The screen was paused, stopped or left: everything is let go of. Idempotent. */
    fun stop() {
        work?.cancel()
        work = null
        dropCard()
        motion.endWindow()
        source.close()
        _outcome.value = null
        dispatch(CaptureEvent.Stopped)
    }
}
