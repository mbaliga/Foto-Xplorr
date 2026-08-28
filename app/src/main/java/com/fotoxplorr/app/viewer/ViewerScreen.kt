@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package com.fotoxplorr.app.viewer

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroidSize
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateRotation
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.fotoxplorr.app.lift.LiftOverlay
import com.fotoxplorr.app.media.MediaAsset
import com.fotoxplorr.app.media.MediaImage
import dev.aarso.cellshell.ParkStyle
import dev.aarso.cellshell.SpatialShell
import dev.aarso.cellshell.rememberSpatialController
import kotlin.math.PI
import kotlin.math.abs
import kotlinx.coroutines.delay

/**
 * The full-screen viewer, as a spatial shell with three rooms around it: settings at the top,
 * the nine photo actions at the right, and what this photo is (and where it was taken) at the
 * bottom, in [PhotoDetailRoom] -- with the photo itself still alive on the parked card behind
 * whichever one is open. The bottom room replaces two separate surfaces that used to say
 * overlapping things about the same file: a bottom `MetadataPanel` behind an "Info" button, and a
 * full-screen Material details screen behind a "Details" button. Two buttons, two layouts and two
 * half-answers about one photo is exactly the drift `docs/fonebrew-navigation.md` describes; the
 * room is the single answer.
 *
 * The left edge has no room, so the shell refuses drags from it and draws no peek there.
 */
@Composable
fun ViewerScreen(
    asset: MediaAsset,
    position: Int,
    total: Int,
    isFavorite: Boolean,
    isSensitive: Boolean,
    hasPrevious: Boolean,
    hasNext: Boolean,
    canMoveToTrash: Boolean,
    slideshowActive: Boolean,
    slideshowIntervalSeconds: Int,
    onToggleSlideshow: () -> Unit,
    onToggleFavorite: () -> Unit,
    onToggleSensitive: () -> Unit,
    onShare: () -> Unit,
    onEdit: () -> Unit,
    onOpenWith: () -> Unit,
    onMoveToTrash: () -> Unit,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onClose: () -> Unit,
    /** Blur-sensitive preference, surfaced in the top room so it is adjustable where it bites. */
    blurSensitive: Boolean = true,
    /** Hold the screen awake while this screen is up. */
    keepScreenOn: Boolean = false,
    /** Whether the filmstrip appears along the bottom with the rest of the chrome. */
    showFilmstrip: Boolean = true,
    /** A location the user placed by hand, for a photo whose file carries no GPS tag. */
    manualLatitude: Double? = null,
    manualLongitude: Double? = null,
    onSetLocation: ((Double, Double) -> Unit)? = null,
    onClearLocation: (() -> Unit)? = null,
    /** Play a slideshow in a random order rather than the browsing order. */
    slideshowShuffle: Boolean = false,
    /**
     * Text the offline recognition pass read out of THIS photo, positioned.
     *
     * Defaulted to empty so every existing call site keeps working and a photo with no recognised
     * text simply has no text layer, rather than an invisible one intercepting taps.
     */
    liveTextBlocks: List<com.fotoxplorr.app.recognition.TextBlock> = emptyList(),
    /**
     * Search the library for text read off this photo, from the details room's Search pill.
     *
     * Leaving the viewer is the caller's job, not this screen's: the pill hands over a string and
     * says nothing about where the results should appear, so a caller that wants search results
     * on some other surface is not fighting a close() this screen decided to do on its behalf.
     * Null leaves the pill visibly disabled -- see [com.fotoxplorr.app.lens.LensCard].
     */
    onSearchLibrary: ((String) -> Unit)? = null,
    /** This photo's tags and caption, for the details room. See [PhotoDetailRoom]. */
    tags: Set<String> = emptySet(),
    autoTags: Set<String> = emptySet(),
    onRemoveTag: ((String) -> Unit)? = null,
    caption: String = "",
    captionIsMachineWritten: Boolean = false,
    onSetCaption: ((String) -> Unit)? = null,
    /** Let GIFs and animated images play. */
    loopAnimations: Boolean = false,
    /** Start videos without waiting for a tap on play. */
    autoplayVideos: Boolean = false,
    onSetSlideshowInterval: (Int) -> Unit = {},
    onSetBlurSensitive: (Boolean) -> Unit = {},
    onSetShowFilmstrip: (Boolean) -> Unit = {},
    onSetKeepScreenOn: (Boolean) -> Unit = {},
    onSetSlideshowShuffle: (Boolean) -> Unit = {},
    onSetLoopAnimations: (Boolean) -> Unit = {},
    onSetAutoplayVideos: (Boolean) -> Unit = {},
    /**
     * The assets being paged through, feeding the filmstrip in the bottom room. Empty is a safe
     * fallback -- the strip hides itself below two items.
     */
    relatedAssets: List<MediaAsset> = emptyList(),
    /** Jump straight to an asset in [relatedAssets], from the filmstrip. */
    onSelectAsset: (MediaAsset) -> Unit = {},
) {
    // Chrome starts HIDDEN and stays hidden across a swipe.
    //
    // It used to default to visible AND be keyed on asset.id, so it reset to visible on every
    // photo change -- immersive was unreachable for more than one photo at a time. Not keyed on
    // the asset any more: a preference to see the position counter is a preference about the
    // viewer, not about a file (owner, 2026-08-14: "Immersive! Immersive! Immersive!").
    var chromeVisible by remember { mutableStateOf(false) }

    var scale by remember(asset.id) { mutableFloatStateOf(1f) }
    var offsetX by remember(asset.id) { mutableFloatStateOf(0f) }
    var offsetY by remember(asset.id) { mutableFloatStateOf(0f) }
    var rotation by remember(asset.id) { mutableFloatStateOf(0f) }
    var containerSize by remember { mutableStateOf(IntSize.Zero) }
    // Long-press-to-lift. Keyed on asset.id like every other per-photo gesture state here, so
    // paging to the next photo cannot leave a stale "picking a subject" overlay armed over a
    // photo the user never long-pressed.
    var liftActive by remember(asset.id) { mutableStateOf(false) }
    val shell = rememberSpatialController()

    // Read here rather than inside the room: the shell only composes a room once it is slightly
    // open, so reading it there would start the EXIF load on the first pixel of the pull and
    // leave the card empty for the rest of the gesture.
    val context = LocalContext.current
    var exif by remember(asset.id) { mutableStateOf(ImageExifDetails()) }
    LaunchedEffect(asset.id) {
        exif = readImageExifDetails(context, asset)
    }

    // A room is not a back-stack entry, but Back is the gesture people reach for to leave one.
    // Disabled at home so the activity's own handler still closes the viewer.
    BackHandler(enabled = !shell.atHome) { shell.closeAll() }

    // Cleared on dispose, not just toggled off: leaving FLAG_KEEP_SCREEN_ON set behind a closed
    // viewer would hold the whole app awake for as long as it stayed in the foreground.
    val view = LocalView.current
    DisposableEffect(keepScreenOn, view) {
        view.keepScreenOn = keepScreenOn
        onDispose { view.keepScreenOn = false }
    }

    LaunchedEffect(asset.id, slideshowActive, slideshowIntervalSeconds, shell.anyRoomVisible) {
        if (slideshowActive && total > 1 && !shell.anyRoomVisible) {
            delay(slideshowIntervalSeconds.coerceAtLeast(2) * 1_000L)
            onNext()
        }
    }

    SpatialShell(
        controller = shell,
        accentColor = MaterialTheme.colorScheme.primary,
        // The rooms sit on the same black the photo does, so opening one reads as the surface
        // moving rather than as a different screen appearing behind it.
        scrimColor = Color.Black,
        cardColor = Color.Black,
        modifier = Modifier.fillMaxSize(),
        // Shrink AND swivel (owner, 2026-08-14). The card turns about the hinge edge that
        // stays on screen, so opening a room reads as a panel swinging away rather than a
        // rectangle sliding off -- the Magic Portal shape. The shrink is kept; the swivel is
        // added to it.
        parkStyle = ParkStyle.SWIVEL,
        top = {
            ViewerSettingsRoom(
                slideshowIntervalSeconds = slideshowIntervalSeconds,
                blurSensitive = blurSensitive,
                showFilmstrip = showFilmstrip,
                keepScreenOn = keepScreenOn,
                slideshowShuffle = slideshowShuffle,
                loopAnimations = loopAnimations,
                autoplayVideos = autoplayVideos,
                onSetSlideshowInterval = onSetSlideshowInterval,
                onSetBlurSensitive = onSetBlurSensitive,
                onSetShowFilmstrip = onSetShowFilmstrip,
                onSetKeepScreenOn = onSetKeepScreenOn,
                onSetSlideshowShuffle = onSetSlideshowShuffle,
                onSetLoopAnimations = onSetLoopAnimations,
                onSetAutoplayVideos = onSetAutoplayVideos,
            )
        },
        right = {
            ViewerActionsRoom(
                isFavorite = isFavorite,
                isSensitive = isSensitive,
                canMoveToTrash = canMoveToTrash,
                slideshowActive = slideshowActive,
                // Every action closes the room on its way out. Leaving it open would mean the
                // result of the action -- a favourite mark, a trash confirmation -- landing
                // behind the panel that triggered it.
                onToggleSlideshow = { shell.closeAll(); onToggleSlideshow() },
                onToggleFavorite = onToggleFavorite,
                onToggleSensitive = onToggleSensitive,
                onShare = { shell.closeAll(); onShare() },
                onEdit = { shell.closeAll(); onEdit() },
                onOpenWith = { shell.closeAll(); onOpenWith() },
                onMoveToTrash = { shell.closeAll(); onMoveToTrash() },
            )
        },
        bottom = {
            PhotoDetailRoom(
                asset = asset,
                exif = exif,
                isFavorite = isFavorite,
                isSensitive = isSensitive,
                manualLatitude = manualLatitude,
                manualLongitude = manualLongitude,
                onSetLocation = onSetLocation,
                onClearLocation = onClearLocation,
                // Derived from the blocks this screen already receives rather than taken as a
                // second parameter: two sources for the same text is how they drift apart, and
                // the overlay and the details card must never disagree about what the photo says.
                recognizedText = liveTextBlocks.joinToString("\n") { it.text },
                onSearchLibrary = onSearchLibrary,
                tags = tags,
                autoTags = autoTags,
                onRemoveTag = onRemoveTag,
                caption = caption,
                captionIsMachineWritten = captionIsMachineWritten,
                onSetCaption = onSetCaption,
                // NEGATED, and this is not cosmetic. The bottom room opens with vProgress
                // running NEGATIVE (SpatialMotion's sign convention), while PlaceMorph.stagger
                // clamps its input to 0..1 -- so feeding the raw value would hold every stagger
                // at zero and render the plate and its text at alpha 0. The room would be
                // *invisible*, with nothing thrown and PlaceMorphTest still green.
                reveal = { -shell.vProgress },
            )
        },
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black)
                .onSizeChanged { containerSize = it }
                // ONE gesture handler, not three stacked ones.
                //
                // This screen used to layer detectHorizontalDragGestures, detectTapGestures and
                // transformable as three separate pointerInput nodes. `transformable` was last,
                // so it was innermost and saw events first; its detector computes pan from the
                // CENTROID, which for a single finger is that finger, so a one-finger drag was
                // read as a pan and consumed before the swipe detector ever ran. Swipe-to-page
                // was therefore unreachable code. And because pan was discarded at scale 1, the
                // photo did not move either -- which is why the screen read as having no
                // gestures at all rather than as having a broken one.
                //
                // Arbitrating zoom, rotate, pan and page in a single loop is the only way to
                // decide between them with the whole picture: pointer count and current scale
                // both matter, and neither is visible to a detector that has already consumed.
                .pointerInput(asset.id) {
                    detectViewerGestures(
                        scaleProvider = { scale },
                        onTransform = { zoomChange, panChange, rotationChange ->
                            val next = (scale * zoomChange).coerceIn(MIN_SCALE, MAX_SCALE)
                            rotation += rotationChange
                            if (next <= 1.001f && rotation == 0f) {
                                offsetX = 0f
                                offsetY = 0f
                            } else {
                                offsetX += panChange.x
                                offsetY += panChange.y
                            }
                            scale = next
                        },
                        onPage = { forward ->
                            if (forward && hasNext) onNext() else if (!forward && hasPrevious) onPrevious()
                        },
                        onGestureEnd = {
                            // Free rotation while the fingers are down, squared up on release:
                            // a photo resting at 7 degrees reads as a bug, not as a choice.
                            rotation = snapRotation(rotation)
                            if (scale <= 1.001f) {
                                offsetX = 0f
                                offsetY = 0f
                            }
                        },
                    )
                }
                .pointerInput(asset.id) {
                    detectTapGestures(
                        onTap = { chromeVisible = !chromeVisible },
                        onDoubleTap = {
                            if (!asset.isVideo) {
                                if (scale > 1.05f || rotation != 0f) {
                                    scale = 1f
                                    rotation = 0f
                                    offsetX = 0f
                                    offsetY = 0f
                                } else {
                                    scale = DOUBLE_TAP_SCALE
                                }
                            }
                        },
                        // Long-press enters lift mode. Added as a THIRD callback on this SAME
                        // detectTapGestures call, deliberately not as a second, separate
                        // `pointerInput { detectTapGestures(...) }` block: two independent
                        // detectTapGestures detectors racing to consume the same down is exactly
                        // the "several stacked detectors" failure this file's header comment
                        // already describes fixing once for zoom/pan/page vs tap. One detector
                        // with three callbacks has no such race -- detectTapGestures arbitrates
                        // tap vs double-tap vs long-press internally, against its own single
                        // stream, and this screen never has to reason about ordering between two
                        // detectTapGestures instances at all.
                        //
                        // Guarded off whenever LiveTextOverlay is up (chrome visible AND this
                        // photo has recognised text): that layer is a later sibling inside the
                        // same photo Box below, so it is hit-tested first and would already be
                        // consuming every tap and long-press within its bounds -- including ones
                        // on bare photo, per its own "a tap on bare photo clears [selection]"
                        // behaviour. Without this guard a long press over live text would not
                        // reliably do EITHER thing (not select text, since LiveTextOverlay's own
                        // onLongPress only acts when the point is actually on a text block; not
                        // lift, since the down was already consumed) -- a dead zone rather than a
                        // conflict, and worse than either. So: lift is simply unavailable while
                        // the Live Text layer is showing for this photo. Toggling chrome off (or
                        // moving to a photo with no recognised text) reaches it again.
                        onLongPress = {
                            if (!asset.isVideo && !(chromeVisible && liveTextBlocks.isNotEmpty())) {
                                liftActive = true
                            }
                        },
                    )
                },
            contentAlignment = Alignment.Center,
        ) {
            if (asset.isVideo) {
                VideoPlayer(
                    asset = asset,
                    modifier = Modifier.fillMaxSize(),
                    chromeVisible = chromeVisible,
                    autoplayVideos = autoplayVideos,
                )
            } else {
                // The photo and its text layer share ONE transform box. Putting the overlay
                // inside the same graphicsLayer is what keeps the OCR boxes glued to the words
                // under pinch, drag and rotate without the overlay knowing anything about the
                // current gesture state -- the only arrangement that cannot drift out of
                // alignment as the gesture code changes.
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .graphicsLayer {
                            scaleX = scale
                            scaleY = scale
                            rotationZ = rotation
                            translationX = offsetX
                            translationY = offsetY
                        },
                ) {
                    MediaImage(
                        asset = asset,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Fit,
                    )

                    // Text found by the offline recognition pass, selectable in place. Only while
                    // the chrome is up: the viewer's whole premise is that a photo is unobstructed
                    // until asked otherwise, and tap targets over the picture are an obstruction
                    // even when they are invisible.
                    if (chromeVisible && liveTextBlocks.isNotEmpty()) {
                        LiveTextOverlay(
                            blocks = liveTextBlocks,
                            imageWidth = asset.width,
                            imageHeight = asset.height,
                        )
                    }

                    // Same transform box as MediaImage and LiveTextOverlay, for the same reason
                    // LiveTextOverlay is here: a tap has to land on the right pixel of the
                    // SOURCE photo regardless of the current pinch/pan/rotate state, and putting
                    // this inside the shared graphicsLayer is what keeps that true without this
                    // composable knowing anything about scale, offset or rotation itself.
                    // Renders nothing at all while `liftActive` is false -- see LiftOverlay's own
                    // KDoc for why that, not an internal armed/disarmed flag, is the mechanism
                    // that keeps this from ever contending with the gestures above.
                    LiftOverlay(
                        asset = asset,
                        active = liftActive,
                        onDismiss = { liftActive = false },
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }

            // All that is left over the photo, and only when asked for: where you are in the
            // run. The nine actions are the right room, the facts are the bottom room, and the
            // settings are the top room -- none of them costs the photo a pixel until pulled.
            if (chromeVisible) {
                ViewerPositionChip(
                    asset = asset,
                    position = position,
                    total = total,
                    onClose = onClose,
                    modifier = Modifier.align(Alignment.TopCenter).statusBarsPadding(),
                )
            }

            // Only meaningful once the user has actually zoomed in, and now also gated on the
            // chrome: it was the one piece of UI that drew over the photo regardless.
            if (chromeVisible && !asset.isVideo && scale > 1.05f) {
                ZoomMinimap(
                    asset = asset,
                    scale = scale,
                    offsetX = offsetX,
                    offsetY = offsetY,
                    containerSize = containerSize,
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .navigationBarsPadding()
                        .padding(16.dp),
                )
            }

            // Back on the photo itself, not in the details room (owner, second round,
            // 2026-08-14: "The filmstrip has to appear not in the details view but in the view
            // where the photo is selected"). Gated on the same chrome flag as everything else
            // drawn over the photo, for the same reason: immersive means immersive by default,
            // and the strip is unambiguously chrome.
            //
            // Also suppressed for video: VideoPlayer draws its own bottom chrome while a video is
            // showing (the key-moment pill/menu and a scrubber over the VIDEO's own timeline),
            // anchored to this exact same screen edge. Leaving this roll-level filmstrip on too
            // would stack two scrubbers fighting for the same band at the bottom of the screen.
            // The roll is still reachable while a video plays -- the swipe-to-page gesture above
            // is not gated on asset type -- just not via this particular thumbnail strip. Not
            // verified on a device (none available in this environment); if that trade-off turns
            // out wrong once someone can look at it, this is a one-token `!asset.isVideo` to drop.
            if (!asset.isVideo && chromeVisible && showFilmstrip && relatedAssets.size > 1) {
                FilmstripScrubber(
                    assets = relatedAssets,
                    currentIndex = position - 1,
                    onSelect = onSelectAsset,
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .navigationBarsPadding(),
                )
            }
        }
    }
}

/**
 * Zoom, rotate, pan and page, arbitrated in one pass.
 *
 * Compose ships `detectTransformGestures`, but it cannot express this screen's rule: a
 * **one-finger** drag at rest pages to the next photo, while the same drag once zoomed pans the
 * image, and two fingers always transform. That decision needs the pointer count and the current
 * scale together, and any detector that consumes first has already thrown the choice away.
 *
 * Nothing is consumed until the gesture passes touch slop, which is what leaves single taps and
 * double taps to the tap detector layered beside this one.
 *
 * @param scaleProvider read live rather than captured: the scale changes during the gesture this
 *   very function is driving, and a captured copy would decide "is it zoomed?" using a value from
 *   before the pinch.
 * @param onPage forward = true means "next photo".
 */
private suspend fun PointerInputScope.detectViewerGestures(
    scaleProvider: () -> Float,
    onTransform: (zoomChange: Float, panChange: Offset, rotationChange: Float) -> Unit,
    onPage: (forward: Boolean) -> Unit,
    onGestureEnd: () -> Unit,
) {
    awaitEachGesture {
        var zoom = 1f
        var pan = Offset.Zero
        var rotation = 0f
        var pastSlop = false
        var maxPointers = 1
        var pagingTravel = 0f
        val slop = viewConfiguration.touchSlop

        // requireUnconsumed = false, and the down is deliberately NOT consumed: the tap detector
        // beside this one has to see the same down or taps stop working entirely.
        awaitFirstDown(requireUnconsumed = false)
        do {
            val event = awaitPointerEvent()
            if (event.changes.any { it.isConsumed }) break
            maxPointers = maxOf(maxPointers, event.changes.count { it.pressed })

            val zoomChange = event.calculateZoom()
            val rotationChange = event.calculateRotation()
            val panChange = event.calculatePan()

            if (!pastSlop) {
                zoom *= zoomChange
                rotation += rotationChange
                pan += panChange
                val centroidSize = event.calculateCentroidSize(useCurrent = false)
                val zoomMotion = abs(1f - zoom) * centroidSize
                val rotationMotion = abs(rotation * PI.toFloat() / 180f) * centroidSize
                if (zoomMotion > slop || rotationMotion > slop || pan.getDistance() > slop) {
                    pastSlop = true
                }
            }

            if (pastSlop) {
                val zoomed = scaleProvider() > 1.001f
                // One finger, not zoomed: this is a page turn, and the image must not move with
                // it -- the photo slides only when there is something to slide within.
                if (maxPointers == 1 && !zoomed) {
                    pagingTravel += panChange.x
                } else {
                    onTransform(zoomChange, panChange, rotationChange)
                }
                event.changes.forEach { if (it.positionChanged()) it.consume() }
            }
        } while (event.changes.any { it.pressed })

        if (pastSlop && maxPointers == 1 && abs(pagingTravel) >= SWIPE_THRESHOLD_PX) {
            // Drag left => the next photo comes in from the right.
            onPage(pagingTravel < 0f)
        }
        onGestureEnd()
    }
}

/**
 * Squares a free rotation up to the nearest quarter turn, normalised to 0/90/180/270.
 *
 * Pure so the wrap-around cases can be asserted: the interesting ones are negative angles and
 * anything past a full turn, where a naive round-to-90 leaves 350 degrees sitting at 360 rather
 * than at 0 and the photo then animates the long way round on the next gesture.
 */
internal fun snapRotation(degrees: Float): Float {
    val snapped = (Math.round(degrees / 90f) * 90).toFloat()
    val wrapped = snapped % 360f
    val positive = if (wrapped < 0f) wrapped + 360f else wrapped
    // `%` returns -0.0 for exact negative multiples of 360, and -0.0 is NOT equal to 0.0 once
    // boxed (Float.equals compares bit patterns), so an unnormalised result can silently fail a
    // set-membership or map-key check even though it draws identically. Adding zero canonicalises
    // it: -0.0 + 0.0 is +0.0.
    return positive + 0f
}

/**
 * The only thing that draws over the photo, and only when the chrome is asked for: which shot
 * this is, and the way out.
 *
 * What it replaces was a full-width 78%-opaque plate carrying a filename, a counter and nine
 * text buttons, present by default.
 */
@Composable
private fun ViewerPositionChip(
    asset: MediaAsset,
    position: Int,
    total: Int,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.padding(8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        IconButton(
            onClick = onClose,
            modifier = Modifier
                .background(Color.Black.copy(alpha = 0.55f), CircleShape)
                .size(36.dp),
        ) {
            Icon(Icons.Outlined.Close, contentDescription = "Close", tint = Color.White)
        }
        Text(
            text = "$position / $total",
            color = Color.White,
            style = MaterialTheme.typography.labelLarge,
            modifier = Modifier
                .background(Color.Black.copy(alpha = 0.55f), CircleShape)
                .padding(horizontal = 12.dp, vertical = 8.dp),
        )
    }
}

/**
 * A small semi-transparent rectangle showing where the current pinch-zoomed viewport sits
 * within the full image. The math assumes [MediaImage] is laid out to fill the container
 * (ContentScale.Fit, centred) and that the pinch-zoom graphicsLayer scales/translates about
 * that same centre -- true for how this screen drives `scale`/`offsetX`/`offsetY` above, but
 * this has only been checked against that code, not against a running app (no device/emulator
 * available in this environment).
 */
@Composable
private fun ZoomMinimap(
    asset: MediaAsset,
    scale: Float,
    offsetX: Float,
    offsetY: Float,
    containerSize: IntSize,
    modifier: Modifier = Modifier,
) {
    val containerW = containerSize.width.toFloat()
    val containerH = containerSize.height.toFloat()
    if (containerW <= 0f || containerH <= 0f) return

    val imageAspect = if (asset.width > 0 && asset.height > 0) {
        asset.width.toFloat() / asset.height.toFloat()
    } else {
        1f
    }
    val containerAspect = containerW / containerH
    val fittedWidth = if (imageAspect > containerAspect) containerW else containerH * imageAspect
    val fittedHeight = if (imageAspect > containerAspect) containerW / imageAspect else containerH
    val centerX = containerW / 2f
    val centerY = containerH / 2f

    val leftFraction = (0.5f + (0f - centerX - offsetX) / scale / fittedWidth).coerceIn(0f, 1f)
    val rightFraction = (0.5f + (containerW - centerX - offsetX) / scale / fittedWidth).coerceIn(0f, 1f)
    val topFraction = (0.5f + (0f - centerY - offsetY) / scale / fittedHeight).coerceIn(0f, 1f)
    val bottomFraction = (0.5f + (containerH - centerY - offsetY) / scale / fittedHeight).coerceIn(0f, 1f)

    Box(
        modifier = modifier
            .size(width = 72.dp, height = (72.dp / imageAspect.coerceIn(0.4f, 2.5f)))
            .background(Color.Black.copy(alpha = 0.45f))
            .border(1.dp, Color.White.copy(alpha = 0.55f)),
    ) {
        Box(
            Modifier
                .fillMaxSize()
                .drawBehind {
                    val strokeWidthPx = 1.5.dp.toPx()
                    val left = (leftFraction * size.width).coerceAtMost(size.width - strokeWidthPx)
                    val top = (topFraction * size.height).coerceAtMost(size.height - strokeWidthPx)
                    val right = (rightFraction * size.width).coerceAtLeast(left + strokeWidthPx)
                    val bottom = (bottomFraction * size.height).coerceAtLeast(top + strokeWidthPx)
                    drawRect(
                        color = Color.White,
                        topLeft = Offset(left, top),
                        size = Size(right - left, bottom - top),
                        style = Stroke(width = strokeWidthPx),
                    )
                },
        )
    }
}

private const val SWIPE_THRESHOLD_PX = 180f

private const val MIN_SCALE = 1f
private const val MAX_SCALE = 6f
private const val DOUBLE_TAP_SCALE = 2.5f
