package com.fotoxplorr.app.viewer

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import com.fotoxplorr.app.media.MediaAsset
import com.fotoxplorr.app.recognition.TextBlock
import com.fotoxplorr.app.recognition.TextLayoutProvider
import com.fotoxplorr.app.recognition.recognitionRevision
import com.fotoxplorr.app.textselect.TextLayout
import com.fotoxplorr.app.textselect.TextSelection

/**
 * What the Select text mode knows: the layout of the photo's text, the selection over it, and the
 * finger trail while a stroke is being drawn. Shared by the overlay (inside the zoom layer, which
 * draws and reads gestures) and the toolbar (outside it, which reads the selection and acts on
 * it), so it is hoisted into [ViewerScreen] and handed to both.
 *
 * Two layouts, one visible at a time. [blockLayout] is made at once from the whole-block boxes the
 * recognition index stored, so tap-a-block works the moment the mode opens; [wordLayout] arrives
 * from [TextLayoutProvider] a moment later and replaces it ([layout] prefers it). A selection made
 * on the block layout survives that swap: the selection is held together with the layout it was
 * made on and re-expressed in the current one on every read ([TextSelection.carriedOver]), so no
 * frame ever pairs word indices with the wrong layout.
 */
@Stable
internal class TextSelectState(initialBlockLayout: TextLayout = TextLayout.empty(1, 1)) {
    /** The layout from the stored index: one tappable "word" per block. */
    var blockLayout: TextLayout by mutableStateOf(initialBlockLayout)
        private set

    /** The word-level layout from the on-demand pass, or null before it lands or when it found nothing. */
    var wordLayout: TextLayout? by mutableStateOf(null)
        private set

    /** Whether the word-level pass is still running. */
    var working: Boolean by mutableStateOf(false)

    /** The finger trail of a stroke in progress, in the overlay's own pixels; empty otherwise. */
    var trail: List<Offset> by mutableStateOf(emptyList())

    /** A selection together with the layout its indices refer to. */
    class Held internal constructor(internal val layout: TextLayout, internal val selection: TextSelection)

    private var held: Held by mutableStateOf(Held(initialBlockLayout, TextSelection.NONE))

    /** The layout in force: word level once it has arrived, otherwise the block-only one. */
    val layout: TextLayout get() = wordLayout ?: blockLayout

    /** The selection, always expressed in [layout]. */
    val selection: TextSelection
        get() {
            val h = held
            return if (h.layout === layout) h.selection else h.selection.carriedOver(h.layout, layout)
        }

    /** Whether there is any text to select in the layout in force. */
    val hasText: Boolean get() = !layout.isEmpty

    /** Replace the selection (with one built on [layout]). */
    fun select(next: TextSelection) {
        held = Held(layout, next)
    }

    /** The selection as it stands, to put back with [restore] if a gesture is abandoned. */
    fun mark(): Held = Held(layout, selection)

    fun restore(mark: Held) {
        held = mark
    }

    /** Give the state a new layout made from the stored index (the photo's frame size became known). */
    fun useBlockLayout(next: TextLayout) {
        if (next !== blockLayout) blockLayout = next
    }

    /**
     * Give the state the word-level layout that arrived. An empty one (the pass found nothing at
     * 2048 px) is ignored, so stored blocks that were found at a lower resolution stay selectable.
     */
    fun useWordLayout(next: TextLayout?) {
        wordLayout = next?.takeIf { !it.isEmpty }
    }
}

/**
 * The [TextSelectState] for [asset] while [active], reading the words in the background.
 *
 * A fresh state each time the mode opens (and per photo), so a selection never outlives the mode.
 * The block-only layout is built from [blocks] in the UPRIGHT [frameWidth] x [frameHeight] frame
 * the stored boxes are normalised to (TRAPS 48); the word-level one comes from [provider] with its
 * own frame, and a layout the provider already holds is used as soon as the mode opens.
 */
@Composable
internal fun rememberTextSelectState(
    active: Boolean,
    asset: MediaAsset,
    animated: Boolean,
    blocks: List<TextBlock>,
    frameWidth: Int,
    frameHeight: Int,
    provider: TextLayoutProvider?,
): TextSelectState {
    val blockLayout = remember(active, blocks, frameWidth, frameHeight) {
        if (active) TextLayoutProvider.blockOnly(blocks, frameWidth, frameHeight) else TextLayout.empty(1, 1)
    }
    val state = remember(asset.id, active) { TextSelectState(blockLayout) }
    if (active) {
        // The frame size can change under an open mode (the EXIF read landing); the first layout
        // went in through the constructor so the first frame already has it.
        LaunchedEffect(state, blockLayout) { state.useBlockLayout(blockLayout) }
        val revision = asset.recognitionRevision()
        LaunchedEffect(state, asset.id, revision, provider) {
            if (provider == null) return@LaunchedEffect
            provider.peek(asset)?.let { state.useWordLayout(it) }
            if (state.wordLayout != null) return@LaunchedEffect
            state.working = true
            try {
                state.useWordLayout(provider.layoutFor(asset, animated))
            } finally {
                state.working = false
            }
        }
    }
    return state
}

/**
 * Whether the viewer offers Select text (the actions room's row and the details card's line) at
 * all: the photo can be read ([canLift], a still raster image) and the mode would have something
 * to show, either a word reader to ask ([hasLayoutProvider]) or whole blocks the index stored
 * ([storedBlockCount]). With neither, the mode could only open on "No selectable text found", a
 * dead end for a photo opened from another app that has no index and, without a reader, no way to
 * get words.
 */
internal fun selectTextOffered(canLift: Boolean, hasLayoutProvider: Boolean, storedBlockCount: Int): Boolean =
    canLift && (hasLayoutProvider || storedBlockCount > 0)

/** The words the mode says, kept here so they are in one place and testable without a screen. */
internal object TextSelectCopy {
    const val HINT = "Tap a block, or draw over the words you want"
    const val NOTHING_FOUND = "No selectable text found"
    const val LOOKING = "Looking for text…"
    const val READING_WORDS = "Reading the words…"

    /** What the overlay says to a screen reader, which cannot draw: the buttons are the way. */
    const val OVERLAY_DESCRIPTION =
        "Text on the photo. Drawing is not available with a screen reader: use Select all, then Copy, below."

    /**
     * The status line under the photo, also the toolbar's live region: how many are selected, or
     * what to do, or that there is nothing to select. [wordLevel] false means each selectable
     * piece is a whole block of text. A photo in a script the bundled recogniser does not read
     * (anything but Latin) lands on [NOTHING_FOUND], which says what is true without claiming
     * that something broke.
     */
    fun status(selected: Int, wordLevel: Boolean, hasText: Boolean, working: Boolean): String = when {
        selected > 0 -> count(selected, wordLevel) + " selected"
        hasText && working && !wordLevel -> "$HINT. $READING_WORDS"
        hasText -> HINT
        working -> LOOKING
        else -> NOTHING_FOUND
    }

    /** What the status line says after Copy. */
    fun copied(selected: Int, wordLevel: Boolean): String = "Copied " + count(selected, wordLevel)

    fun count(selected: Int, wordLevel: Boolean): String {
        val noun = if (wordLevel) "word" else "block"
        return if (selected == 1) "1 $noun" else "$selected ${noun}s"
    }
}
