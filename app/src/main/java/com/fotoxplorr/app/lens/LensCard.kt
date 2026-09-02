package com.fotoxplorr.app.lens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Stop
import androidx.compose.material.icons.outlined.Translate
import androidx.compose.material.icons.outlined.VolumeUp
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.fotoxplorr.app.media.MediaAsset
import com.fotoxplorr.app.media.MediaImage
import com.fotoxplorr.app.ui.HyleGrotesk
import com.fotoxplorr.app.viewer.CARD_BACKGROUND
import com.fotoxplorr.app.viewer.CARD_HEADER_BACKGROUND
import com.fotoxplorr.app.viewer.MUTED_TEXT
import com.fotoxplorr.app.viewer.PRIMARY_TEXT
import com.fotoxplorr.app.viewer.SECONDARY_TEXT
import kotlinx.coroutines.launch
import java.util.Locale

/**
 * "Search inside this photo" -- whole-image actions over text this app's own on-device OCR
 * already found when the photo was indexed.
 *
 * This is the details panel's counterpart to
 * [com.fotoxplorr.app.viewer.LiveTextOverlay], not a replacement for it: the overlay lets a
 * finger land on one word, drawn directly over the photo, for selecting and copying a run of
 * text; this card acts on the WHOLE recognised string at once, from the panel where the photo's
 * other facts already live. Modelled on the equivalent panel in Google Photos, with one
 * deliberate difference: that panel credits "Google Lens" as the text recogniser's provider.
 * This app is not Lens, licenses no Lens API, and sends nothing anywhere to produce this text --
 * [com.fotoxplorr.app.recognition.RecognitionIndexer] runs ML Kit's on-device TEXT_RECOGNITION
 * model when the library is indexed, entirely locally, and this card's subtitle says exactly
 * that instead of borrowing a name that is not this app's to use.
 *
 * The four actions are drawn as a wrapping grid of square tiles, not a scrolling strip. They were
 * a [Row] with `horizontalScroll` until the owner saw it rendered: at 440dp the fourth action was
 * sliced through the middle of its own word at the card's right edge, and the only way to reach
 * it was a sideways drag nothing on screen advertised. A control that has to be discovered by
 * swiping is a control most people never find, and four fixed actions are not a list -- there is
 * no fifth one coming, so there is nothing to scroll TO. [LensActionTile] and the [FlowRow] below
 * size themselves to be wholly visible instead, and stack onto a second line on a narrow device
 * rather than hiding anything off the edge.
 *
 * What backs each action:
 *  - **Copy text** -- the clipboard, offline, identical in both flavors.
 *  - **Search** -- searches the user's OWN library via [onSearchLibrary] (primary), with a
 *    secondary, plainly-labelled hand-off to a web search underneath the tile grid.
 *  - **Listen** -- [android.speech.tts.TextToSpeech], a platform API with no library dependency,
 *    identical in both flavors; see [LensSpeaker] for how an unavailable engine or an
 *    uninstalled language voice is turned into a visible message rather than a silent no-op.
 *  - **Translate** -- real on-device translation in `connect` ([TranslateMode.ON_DEVICE]); a
 *    "Translate with…" hand-off to an installed translator app in `offline`, and in `connect`
 *    too if an actual translation attempt fails. See [TextTranslator] for why the flavor split
 *    is unavoidable.
 *
 * @param recognizedText the photo's OCR text, flattened -- see
 *   [com.fotoxplorr.app.recognition.RecognitionIndex.textOf]. Blank hides the whole card; see
 *   [LensActions.plan] for why an empty card is the honest choice there, not four dead tiles.
 * @param onSearchLibrary runs [com.fotoxplorr.app.search] against the user's library for the
 *   text built by [LensSearchQuery.buildQuery]. Null leaves the Search tile visibly disabled --
 *   the same shape [com.fotoxplorr.app.viewer.PhotoDetailRoom] already uses for
 *   `onSetLocation == null` -- rather than the tile silently doing nothing when tapped; the
 *   secondary web-search link below the grid works with or without this callback, since it
 *   needs no in-app search surface to reach.
 */
@Composable
fun LensCard(
    asset: MediaAsset,
    recognizedText: String,
    modifier: Modifier = Modifier,
    onSearchLibrary: ((String) -> Unit)? = null,
) {
    // Matches the check LensActions.plan makes of hasRecognizedText: a blank string is "no text"
    // by the same definition on both sides of that boundary. See StatusBlock in PhotoDetailRoom
    // for the identical "nothing to say, so nothing to draw" precedent this follows.
    if (recognizedText.isBlank()) return

    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val scope = rememberCoroutineScope()
    // Keyed on the photo, not just the Context: see rememberLensSpeaker's own KDoc for why a
    // swipe to another photo must always get a fresh engine rather than possibly inheriting one
    // still mid-sentence on the last photo's text.
    val speaker = rememberLensSpeaker(key = asset.id)
    val translator = remember { AppTextTranslator() }
    // Best-effort, not detected -- see TextTranslator.DEFAULT_SOURCE_LANGUAGE_TAG and
    // LensSpeaker.speak's own KDoc for the identical limitation on the Listen side.
    val deviceLanguageTag = remember { Locale.getDefault().toLanguageTag() }
    val searchQuery = remember(recognizedText) { LensSearchQuery.buildQuery(recognizedText) }

    val plan = LensActions.plan(
        hasRecognizedText = true,
        ttsReadiness = speaker.readiness,
        translatorAvailable = translator.available,
    )

    // Reset per photo (keyed on asset.id) for the same reason the speaker is: without the key, a
    // composable slot reused across a swipe could show "Copied" or a translated RESULT that
    // actually belongs to the photo the person just navigated away from, silently misattributed
    // to the new one -- exactly the kind of claim this feature must never make.
    var statusMessage by remember(asset.id) { mutableStateOf<String?>(null) }
    var translateState by remember(asset.id) { mutableStateOf<TranslateUiState>(TranslateUiState.Idle) }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(9.dp))
            .background(CARD_BACKGROUND),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(CARD_HEADER_BACKGROUND)
                .padding(horizontal = 14.dp, vertical = 11.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "Search inside this photo",
                color = PRIMARY_TEXT,
                style = TextStyle(fontFamily = HyleGrotesk, fontSize = 15.sp, fontWeight = FontWeight.Medium),
            )
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            MediaImage(
                asset = asset,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .size(44.dp)
                    .clip(RoundedCornerShape(6.dp)),
            )
            Column {
                Text(
                    text = "Select text from the image",
                    color = SECONDARY_TEXT,
                    style = TextStyle(fontFamily = HyleGrotesk, fontSize = 14.sp),
                )
                Text(
                    text = "On-device text recognition",
                    color = MUTED_TEXT,
                    style = TextStyle(fontFamily = HyleGrotesk, fontSize = 12.sp),
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
        }

        FlowRow(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp),
            horizontalArrangement = Arrangement.spacedBy(TILE_GAP),
            // Only earned when a device is narrow enough to wrap, but it has to be declared here
            // or the second line would sit flush against the first.
            verticalArrangement = Arrangement.spacedBy(TILE_GAP),
        ) {
            LensActionTile(icon = Icons.Outlined.ContentCopy, label = "Copy text", enabled = true) {
                clipboard.setText(AnnotatedString(recognizedText))
                translateState = TranslateUiState.Idle
                statusMessage = "Copied"
            }

            LensActionTile(icon = Icons.Outlined.Search, label = "Search", enabled = onSearchLibrary != null) {
                statusMessage = null
                onSearchLibrary?.invoke(searchQuery)
            }

            val speaking = speaker.outcome == SpeechOutcome.Speaking
            LensActionTile(
                icon = if (speaking) Icons.Outlined.Stop else Icons.Outlined.VolumeUp,
                label = if (speaking) "Stop" else "Listen",
                enabled = plan.listenEnabled,
            ) {
                statusMessage = null
                if (speaking) speaker.stop() else speaker.speak(recognizedText, deviceLanguageTag)
            }

            LensActionTile(icon = Icons.Outlined.Translate, label = "Translate", enabled = true) {
                statusMessage = null
                if (plan.translateMode == TranslateMode.ON_DEVICE) {
                    translateState = TranslateUiState.Loading
                    scope.launch {
                        val result = translator.translate(recognizedText, deviceLanguageTag)
                        translateState = result.fold(
                            onSuccess = { TranslateUiState.Result(it.text) },
                            onFailure = {
                                // The on-device attempt genuinely failed (timed out, no network
                                // to fetch a model, unsupported pair) -- fall back to the same
                                // hand-off the offline flavor always uses, rather than just
                                // reporting failure and stranding the person with nothing to do.
                                val launched = LensHandoff.launch(
                                    context,
                                    LensHandoff.translateIntent(context, recognizedText),
                                    "Translate with…",
                                )
                                if (launched) {
                                    TranslateUiState.Idle
                                } else {
                                    TranslateUiState.Message(
                                        it.message ?: "Couldn't translate this photo's text.",
                                    )
                                }
                            },
                        )
                    }
                } else {
                    val launched = LensHandoff.launch(
                        context,
                        LensHandoff.translateIntent(context, recognizedText),
                        "Translate with…",
                    )
                    if (!launched) {
                        translateState = TranslateUiState.Message("No translator app is installed.")
                    }
                }
            }
        }

        // Speech's own failure message is kept separate from statusMessage/translateState below
        // rather than sharing one slot: it can be true at the same time as either of them (Listen
        // fails, then Translate is tried next) and one tile's explanation must not evict another's.
        val speechOutcome = speaker.outcome
        if (speechOutcome is SpeechOutcome.Unavailable) {
            LensFootnote(speechOutcome.message)
        }
        when (val state = translateState) {
            is TranslateUiState.Loading -> LensFootnote("Translating…")
            is TranslateUiState.Result -> LensFootnote(state.text, emphasise = true)
            is TranslateUiState.Message -> LensFootnote(state.text)
            TranslateUiState.Idle -> Unit
        }
        statusMessage?.let { LensFootnote(it) }

        // The optional web hand-off this feature's spec allows ("MAY offer"), kept visually
        // secondary and separate from the four tiles the reference always draws, and labelled
        // honestly up front: unlike the in-library Search tile above it, this one sends the
        // photo's text off-device.
        Text(
            text = "Search the web instead — leaves Foto Xplorr",
            color = MUTED_TEXT,
            style = TextStyle(fontFamily = HyleGrotesk, fontSize = 12.sp),
            modifier = Modifier
                .fillMaxWidth()
                .clickable {
                    statusMessage = null
                    val launched = LensHandoff.launch(context, LensHandoff.webSearchIntent(searchQuery), null)
                    if (!launched) statusMessage = "No app on this device can search the web."
                }
                .padding(horizontal = 14.dp, vertical = 10.dp),
        )
    }
}

private sealed interface TranslateUiState {
    data object Idle : TranslateUiState
    data object Loading : TranslateUiState
    data class Result(val text: String) : TranslateUiState
    data class Message(val text: String) : TranslateUiState
}

/**
 * One square action tile: icon over label, both centred, sized so the whole set is visible at once.
 *
 * This was `LensPill` -- a horizontal icon-and-label lozenge -- and the rename is not cosmetic: a
 * pill is as wide as its own text, which is precisely why four of them overflowed the card and
 * had to be scrolled. A tile that fixes its own width instead lets the card do the arithmetic in
 * advance, and a fixed edge is what makes the four read as one set of equals rather than four
 * differently-sized buttons whose widths accidentally rank them by label length.
 *
 * Its surface, disabled tone and icon tint are the pill's, unchanged -- the card's visual
 * language did not need fixing, only its geometry. The corner radius is the one thing that could
 * not survive the change of shape: 20dp was there to round a 38dp-tall lozenge into a true pill,
 * and on an 84dp square it reads as a blob. It steps down to the card's own 9dp instead, which
 * puts the tiles in the same family as the surface holding them rather than inventing a third
 * radius between that and the 6dp thumbnail beside them.
 *
 * Dimmed rather than removed when [enabled] is false -- see [LensCard]'s own KDoc on
 * `onSearchLibrary` for why a visibly-disabled control, not a hidden or silently inert one, is
 * this feature's answer to "not currently wired up".
 */
@Composable
private fun LensActionTile(icon: ImageVector, label: String, enabled: Boolean, onClick: () -> Unit) {
    Column(
        modifier = Modifier
            .width(TILE_EDGE)
            // A FLOOR, not a fixed square. `aspectRatio(1f)` made the tile exactly TILE_EDGE tall
            // whatever was inside it, and `clip` below then cut off anything that did not fit --
            // which is precisely the defect this rewrite exists to remove, moved from the right
            // edge of a scrolling row to the bottom edge of a tile. Only the text scales with the
            // system font setting; the 22dp icon and the paddings are fixed dp, so at a large
            // accessibility scale a label that wraps to two lines needs more room than a square
            // has. With a minimum it is a square at every ordinary setting and grows only when
            // the alternative would be slicing a word in half.
            .heightIn(min = TILE_EDGE)
            .clip(RoundedCornerShape(9.dp))
            .background(if (enabled) TILE_BACKGROUND else TILE_BACKGROUND_DISABLED)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 6.dp, vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            icon,
            // contentDescription = label, not null: matches ActionButton in LiveTextOverlay.kt,
            // this card's sibling feature -- see this file's own top KDoc on the relationship
            // between the two. Consistent with that established precedent rather than deviating
            // from it on this file's own judgement call about TalkBack merging behaviour, which
            // is not something either of us can verify without a device in this environment.
            contentDescription = label,
            tint = if (enabled) PRIMARY_TEXT else MUTED_TEXT,
            // 16dp was sized to stand beside a label at the pill's own height. Carrying it into
            // a tile nearly five times as wide, with nothing beside it, leaves a speck floating
            // in an empty square -- on a tile the icon is the thing being pointed at, not a
            // bullet in front of the text.
            modifier = Modifier.size(22.dp),
        )
        Text(
            text = label,
            color = if (enabled) PRIMARY_TEXT else MUTED_TEXT,
            // 12sp, down from the pill's 13sp: under an icon this is a caption rather than the
            // control's own inline name, and it is the size the card's other secondary lines
            // ("On-device text recognition") already speak at. It also buys "Copy text" enough
            // room to stay on one line at the default font scale.
            style = TextStyle(fontFamily = HyleGrotesk, fontSize = 12.sp),
            textAlign = TextAlign.Center,
            // Two lines allowed, because the whole point of this rewrite was that no label gets
            // cut off: at a large system font scale a label wraps inside its tile, and the tile
            // grows to hold it (see heightIn above) rather than clipping the second line. The
            // ellipsis is only a backstop for a future label longer than these four.
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 7.dp),
        )
    }
}

/** A small status line under the tile grid: a copy confirmation, a translated result, or an
 *  honest explanation of why an action did not happen. */
@Composable
private fun LensFootnote(text: String, emphasise: Boolean = false) {
    Text(
        text = text,
        color = if (emphasise) SECONDARY_TEXT else MUTED_TEXT,
        style = TextStyle(fontFamily = HyleGrotesk, fontSize = 12.sp),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 6.dp),
    )
}

/**
 * The tile edge, chosen so all four actions fit on one line of the reference 440dp device.
 *
 * The width available to the grid on that device: 440 screen - 20 room padding on each side = 400
 * card, - 14 card padding on each side = 372dp. Four tiles and the three [TILE_GAP]s between them
 * come to 4x84 + 3x8 = 360dp, which clears 372 with 12dp to spare -- enough that a slightly wider
 * system gutter or a rounding difference cannot push the fourth tile off the edge again, which is
 * the exact failure this replaced. 87dp is the true ceiling (4x87 + 24 = 372, exactly flush) and
 * 88 already overflows; sizing to the millimetre a card's padding happens to leave is how the
 * previous layout got into trouble, so this takes the round number below it.
 *
 * A narrower device is not a problem to solve here: at 360dp wide the row has 292dp, three tiles
 * (3x84 + 2x8 = 268) fit, and the FlowRow drops the fourth onto a second line where it is still
 * whole and still reachable without a gesture.
 */
private val TILE_EDGE = 84.dp

/** Used between tiles on both axes, so a wrapped grid has even gutters rather than a tighter
 *  seam in one direction than the other. */
private val TILE_GAP = 8.dp

private val TILE_BACKGROUND = Color(0xFF262626)
private val TILE_BACKGROUND_DISABLED = Color(0xFF1A1A1A)
