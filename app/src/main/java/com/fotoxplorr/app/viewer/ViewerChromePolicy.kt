package com.fotoxplorr.app.viewer

import com.fotoxplorr.app.gallery.TransparencyBackground

/**
 * The viewer's "what is drawn, and when" rules, as pure functions.
 *
 * [ViewerScreen] used to hold these as inline boolean expressions in the middle of a thousand-line
 * composable, which is how `!asset.isVideo && chromeVisible && ...` came to gate the filmstrip on
 * the type of the photo and nobody could see the decision without reading the layout around it.
 * Each rule lives here instead, named for the decision it makes, so a JVM test can pin it
 * (`ViewerChromePolicyTest`) and the screen reads as a list of decisions rather than a pile of
 * conditions.
 *
 * Nothing in this object touches Compose, Android or a clock.
 */
internal object ViewerChromePolicy {

    /**
     * Whether the chrome drawn over the photo (the position chip with its Close button) is up.
     *
     * Hidden in picture-in-picture, where the whole window is a small video and a Close button in
     * its corner would be a control for a window nobody can reach, and hidden while a viewer mode
     * ([modeActive], see [ViewerMode.ownsScreen]: the lift editor or text selection) owns the
     * screen, since each brings toolbars of its own.
     */
    fun chromeShown(chromeVisible: Boolean, inPictureInPicture: Boolean, modeActive: Boolean = false): Boolean =
        chromeVisible && !inPictureInPicture && !modeActive

    /**
     * Whether the favourite heart is drawn in the chrome row beside the position chip.
     *
     * Exactly when the chrome is up ([chromeShown], so it is gone in picture-in-picture and
     * under the lift editor with the rest of it) and the photo is one of ours to mark: the same
     * gate as the actions room's Favourite row, [catalogueActions] and not [isExternal]. A photo
     * opened from another app is not in the catalogue, so there is nothing for a heart to
     * toggle. Deliberately not a function of the type of the asset: a video can be a favourite.
     */
    fun favouriteShown(chromeShown: Boolean, catalogueActions: Boolean, isExternal: Boolean): Boolean =
        chromeShown && catalogueActions && !isExternal

    /**
     * What TalkBack reads as the heart's state. The glyph carries the state by shape (filled or
     * outlined), not by colour alone, and this carries it for people who cannot see either.
     */
    fun favouriteStateDescription(isFavorite: Boolean): String =
        if (isFavorite) "Favourited" else "Not a favourite"

    // ── the backdrop behind a see-through photo (owner, #17) ──────────────────────────────────

    /**
     * Whether the transparent-background button is in the chrome row: the chrome is up and the
     * photo on view declares transparency ([hasAlpha], from the header sniff). Never for a video
     * (a frame has no alpha to show through). Not gated on the catalogue: the choice is a view
     * option, so a sticker opened from another app gets it too.
     */
    fun transparencyControlShown(chromeShown: Boolean, isVideo: Boolean, hasAlpha: Boolean): Boolean =
        chromeShown && !isVideo && hasAlpha

    /**
     * Whether the backdrop is drawn behind the photo. It is image content, not chrome, so it does
     * not wait for the chrome; it is withdrawn while the lift editor owns the screen (that draws
     * its own stage) and never exists for a video.
     */
    fun transparencyBackdropShown(isVideo: Boolean, hasAlpha: Boolean, lifting: Boolean): Boolean =
        !isVideo && hasAlpha && !lifting

    /**
     * The button's spoken label, naming the current choice and what a tap does, so the answer is
     * not carried by the glyph's picture alone.
     */
    fun transparencyControlDescription(current: TransparencyBackground): String =
        "Transparent background: ${current.label()} (tap for ${current.next().label()})"

    /**
     * Whether the filmstrip is drawn.
     *
     * **Deliberately not a function of the current asset.** The strip is roll-level navigation,
     * and it DRIVES the viewer live: the moment a different thumbnail is nearest the centre the
     * host swaps the current asset. If the strip were gated on the type of that asset, scrubbing
     * onto a video would remove the strip from under the finger and cancel the drag -- which is
     * exactly what the owner reported (*"When I scrub through the filmstrip and stop on a video,
     * the video player progress bar etc. replaces the filmstrip so scrubbing through is no longer
     * possible"*). There is no `isVideo` parameter here so that nobody can reintroduce the gate
     * without changing this signature, and `ViewerChromePolicyTest` pins that shape.
     */
    fun filmstripShown(
        chromeVisible: Boolean,
        inPictureInPicture: Boolean,
        showFilmstripPreference: Boolean,
        rollSize: Int,
        modeActive: Boolean = false,
    ): Boolean = chromeShown(chromeVisible, inPictureInPicture, modeActive) && showFilmstripPreference && rollSize > 1

    /**
     * How far above the bottom edge the video transport must sit so it clears the filmstrip: the
     * strip's resting band when it is shown, nothing when it is not. Derived from the strip's own
     * constant so the two cannot drift apart. The bulge (the band growing while a finger is on it)
     * is NOT added: the transport dims and pauses while the strip is being scrubbed instead, so
     * the taller band is allowed to cover it.
     */
    fun videoBottomInsetDp(filmstripShown: Boolean): Int =
        if (filmstripShown) FilmstripGeometry.REST_BAND_DP else 0

    // ── chrome the first time a video opens (owner decision) ──────────────────────────────────

    /**
     * Whether the chrome is already up when the viewer first composes for an asset. Up for a
     * video, down for a photo: immersive stays the default for stills (owner, 2026-08-14:
     * "Immersive! Immersive! Immersive!"), but a video opened into a bare black screen with no
     * visible way to start, pause or seek it is what read as "Fotoz is not playing videos"
     * (owner, #1). Showing the transport the first time a video opens is the owner's decision;
     * after that it is theirs to hide with a tap.
     */
    fun initialChromeVisible(isVideo: Boolean): Boolean = revealChromeForVideo(isVideo, alreadyRevealed = false)

    /**
     * Whether landing on this asset should bring the chrome up: a video, and only the FIRST one
     * this viewer session shows. [alreadyRevealed] is remembered by the screen for its lifetime, so
     * a person who hides the chrome and pages to the next clip is not shown it again against their
     * wishes, while one who opened on a photo still meets the transport the first time a video
     * turns up.
     */
    fun revealChromeForVideo(isVideo: Boolean, alreadyRevealed: Boolean): Boolean = isVideo && !alreadyRevealed

    // ── the slideshow waits for a video ───────────────────────────────────────────────────────

    /**
     * Whether the slideshow's countdown to the next photo should be running.
     *
     * Stopped while any room is open (the person is reading something), while a viewer mode owns
     * the screen ([modeActive]: an advance would throw a half-made lift or text selection away),
     * and while a video is playing
     * or about to (an advance after the interval would cut it off mid-scene). When the video ends,
     * or is paused, the countdown starts afresh, so a clip plays out and then gets its normal
     * interval before the slideshow moves on.
     *
     * Also stopped while the host is not running ([hostRunning], see [slideshowHostRunning]). The
     * countdown is a coroutine in the composition, which outlives the activity being stopped: after
     * Home or the lock key it would still fire, step onto a video, and let that video's player
     * arm behind a dark screen. [hostRunning] defaults to true so a caller with no lifecycle (a
     * preview) behaves as before.
     */
    fun slideshowMayAdvance(
        slideshowActive: Boolean,
        total: Int,
        anyRoomVisible: Boolean,
        videoPlaybackActive: Boolean,
        modeActive: Boolean,
        hostRunning: Boolean = true,
        paused: Boolean = false,
    ): Boolean =
        slideshowActive && total > 1 && !anyRoomVisible && !videoPlaybackActive && !modeActive && hostRunning && !paused

    /**
     * Whether the hosting activity is running for the slideshow's purposes: at least STARTED (on
     * screen, even if not focused), or in picture-in-picture. The PiP clause is belt and braces --
     * a PiP activity is paused, not stopped, so it is STARTED anyway -- but the window is still
     * visibly playing and a slideshow there is meant to keep going, so it is stated rather than
     * left to that platform detail.
     */
    fun slideshowHostRunning(lifecycleStarted: Boolean, inPictureInPicture: Boolean): Boolean =
        lifecycleStarted || inPictureInPicture

    // ── the slideshow bar (owner, #16) ────────────────────────────────────────────────────────

    /**
     * Whether the slideshow bar (previous, play or pause, next) is drawn at the top: a slideshow
     * is up, there is somewhere to go ([total] above one), and the chrome is up ([chromeShown],
     * so it goes in picture-in-picture and under a viewer mode with the rest). Not a function
     * of a paused state: a paused slideshow is exactly when the bar is needed, to resume it.
     */
    fun slideshowBarShown(slideshowActive: Boolean, total: Int, chromeShown: Boolean): Boolean =
        slideshowActive && total > 1 && chromeShown

    /**
     * Whether the chrome may fold itself away after [SLIDESHOW_CHROME_HIDE_MS] during a slideshow,
     * so the photos are seen bare between touches.
     *
     * Not while paused (the bar is how it is resumed, and the person is plainly looking at the
     * controls), not over a video (its transport and the bar stay up for the clip), not while a
     * room is open or a viewer mode is up (those own the screen), and not once it is already down.
     */
    fun slideshowChromeMayAutoHide(
        slideshowActive: Boolean,
        paused: Boolean,
        chromeVisible: Boolean,
        isVideo: Boolean,
        anyRoomVisible: Boolean,
        modeActive: Boolean,
    ): Boolean = slideshowActive && !paused && chromeVisible && !isVideo && !anyRoomVisible && !modeActive

    /** How long the chrome stays up during a running slideshow before it folds away. */
    const val SLIDESHOW_CHROME_HIDE_MS = 3_000L

    /** The chip row's height: a 48dp button inside 8dp of padding. */
    const val CHIP_ROW_DP = 64

    /**
     * How far down the chip row sits while the slideshow bar is above it: the bar's own height,
     * so the two stack instead of overlapping (they are too wide to share a line on a phone).
     */
    fun chipRowDropDp(slideshowBarShown: Boolean): Float = if (slideshowBarShown) SlideshowBarGeometry.BAR_HEIGHT_DP else 0f

    /**
     * How much of the top the chrome occupies below the status bar, for a video's "Resume from"
     * chip to sit under it rather than on top of it: nothing when the chrome is down, the chip
     * row when it is up, and the slideshow bar as well when that is up. Rounded up so it clears.
     */
    fun videoTopInsetDp(chromeShown: Boolean, slideshowBarShown: Boolean): Int =
        if (!chromeShown) {
            0
        } else {
            CHIP_ROW_DP + kotlin.math.ceil(chipRowDropDp(slideshowBarShown)).toInt()
        }

    // ── lifting a subject ─────────────────────────────────────────────────────────────────────

    /**
     * Whether the image should be treated as animated: the recognition pass says so, or it is a
     * GIF (almost every real GIF is animated, and a still one costs nothing extra through the
     * animated decode path with one image on screen). The same answer drives the image view's
     * `animate` flag and whether a subject can be lifted, so a photo that plays is never offered
     * for lifting as if it were a single frame.
     */
    fun isAnimatedImage(animated: Boolean, mimeType: String): Boolean =
        animated || mimeType.equals("image/gif", ignoreCase = true)

    /**
     * Whether a long press on the photo starts a lift.
     *
     * On for anything [canLift] says can be lifted, whether or not the photo has recognised text
     * and whether or not the chrome is up. It used to be off while the Live Text layer was up,
     * because that layer was always composed over a photo with text and swallowed every tap and
     * long press on it (TRAPS 50). The text layer now renders nothing until the person opens
     * Select text, so there is no dead zone to avoid.
     *
     * Off while a viewer mode already owns the screen ([modeActive]): a long press inside text
     * selection stays inside it, and opening the lift editor from there would be a mode change
     * nobody asked for. A mode is left (Done, Back) before the other is opened.
     */
    fun longPressStartsLift(canLift: Boolean, modeActive: Boolean = false): Boolean = canLift && !modeActive

    /**
     * Whether a one-finger drag, a double tap and a plain tap on the photo are the viewer's (page
     * turn, pan, zoom, toggle the chrome) or belong to the mode on screen. Text selection reads a
     * one-finger drag as a stroke over words and a tap as selecting a block, so while it is up the
     * viewer's own one-finger gestures are off; a pinch is still the viewer's, in every mode.
     */
    fun oneFingerGesturesAreViewers(modeActive: Boolean): Boolean = !modeActive

    /**
     * Whether the rooms should be withdrawn (their slots passed as null, so the shell neither
     * composes them nor lets an edge drag open one) because a viewer mode owns the screen: the lift
     * editor, or text selection with a stroke being drawn near an edge.
     *
     * Waits for the shell to be [atHome]: a mode started from a room first closes that room, and
     * removing its slot on the spot would make the room vanish while the card is still sliding
     * back over it.
     */
    fun roomsWithdrawn(modeActive: Boolean, atHome: Boolean): Boolean = modeActive && atHome

    // ── the shrink-to-map flight ──────────────────────────────────────────────────────────────

    /**
     * Whether the photo-to-pin flier is composed, and with it whether the details room's pin waits
     * beneath it (`PhotoDetailRoom.flightActive`). One decision for both so they cannot disagree:
     * a room waiting for a flier that is not there leaves its pin invisible.
     *
     * - [topPulling]: the details room (the TOP room) is at least slightly open.
     * - [photoAtRest]: the photo is neither zoomed nor rotated. The flier starts from the photo's
     *   resting rectangle, so a zoomed or turned photo has nowhere honest to lift off from; it
     *   falls back to the in-plate shrink the room plays on its own.
     * - [viewportWidth] / [viewportHeight]: the shell's measured size, zero before first layout.
     */
    fun flightActive(topPulling: Boolean, photoAtRest: Boolean, viewportWidth: Int, viewportHeight: Int): Boolean =
        topPulling && photoAtRest && viewportWidth > 0 && viewportHeight > 0

    /** Whether the photo is at rest: not zoomed in, not turned. [scale] is the pinch scale. */
    fun photoAtRest(scale: Float, rotationDegrees: Float): Boolean = scale <= 1.001f && rotationDegrees == 0f

    /**
     * Whether the photo has a place, as the details room's place block decides it: a real
     * embedded fix wins, otherwise a hand-placed one. The flier lands in the state the pin is
     * drawn in (a located photo as itself, a placeless one dimmed on the ghost pin), so it must
     * agree with the room about which of the two this is.
     */
    fun placeKnown(
        exifLatitude: Double?,
        exifLongitude: Double?,
        manualLatitude: Double?,
        manualLongitude: Double?,
    ): Boolean = PlaceMorph.isDrawable(exifLatitude, exifLongitude) ||
        PlaceMorph.isDrawable(manualLatitude, manualLongitude)
}
