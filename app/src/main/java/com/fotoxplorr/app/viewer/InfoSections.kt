package com.fotoxplorr.app.viewer

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.KeyboardArrowUp
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.onLongClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.fotoxplorr.app.ui.HyleGrotesk
import kotlinx.coroutines.delay

/**
 * The detail sections [MetadataSections.build] returns, drawn as the rows of the room's
 * Information block once it is expanded: each section a header that folds its rows away (File and
 * Image start open, the rest closed -- [MetaSection.initiallyExpanded]), each row a label and a value
 * in the same hairline-and-grey idiom as the three always-visible rows above them.
 *
 * Reading: a row is ONE accessibility node that says "label, value" -- the two texts are not two
 * stops -- and a failed read's row is read the same way, so "Photo details, Couldn't read" is never
 * heard as a fact about the photo. A section header says whether it is expanded and what a tap does.
 *
 * Copying: a long press on a row puts [MetaRow.copyText] on the clipboard and the row's value is
 * replaced by "Copied" for a moment (a polite live region, so it is announced as well as seen -- the
 * system's own clipboard confirmation only exists on newer Android). The same copy is offered as the
 * row's long-click action and as a custom accessibility action, for a reader who cannot long press.
 * A failed-read row has nothing worth copying and offers neither. There is deliberately no
 * "copy all": a block of every detail would carry the photo's coordinates out of the app in one
 * gesture (TRAPS 31); each row is copied on purpose, by its own owner.
 *
 * Every header and every row is at least 48 dp tall, the touch-target minimum, so a value that wraps
 * onto a second line grows its row rather than clipping.
 *
 * Stateless apart from which sections are folded and which row says "Copied": both are per photo
 * ([resetKey]) so a swipe to the next photo cannot carry a fold or a notice onto it.
 */
@Composable
internal fun InfoSections(
    model: MetadataModel,
    resetKey: Any,
    modifier: Modifier = Modifier,
) {
    val clipboard = LocalClipboardManager.current
    val folded = remember(resetKey) { mutableStateMapOf<SectionId, Boolean>() }
    var copiedKey by remember(resetKey) { mutableStateOf<String?>(null) }
    LaunchedEffect(copiedKey) {
        if (copiedKey != null) {
            delay(COPIED_NOTICE_MS)
            copiedKey = null
        }
    }
    Column(modifier.fillMaxWidth()) {
        for (section in model.sections) {
            key(section.id) {
                val open = folded[section.id] ?: section.initiallyExpanded
                SectionHeader(
                    title = section.title,
                    open = open,
                    onToggle = { folded[section.id] = !open },
                )
                if (open) {
                    for (row in section.rows) {
                        key(row.label) {
                            val rowKey = "${section.id.name}/${row.label}"
                            DetailRow(
                                row = row,
                                copied = copiedKey == rowKey,
                                onCopy = {
                                    clipboard.setText(AnnotatedString(row.copyText))
                                    copiedKey = rowKey
                                },
                            )
                        }
                    }
                }
            }
        }
        if (model.loading) {
            // The rest of the file is still being read: say so rather than ending the list as if
            // this were all the photo had (TRAPS 32).
            Text(
                text = "Reading more details…",
                color = ROW_LABEL,
                style = TextStyle(fontFamily = HyleGrotesk, fontSize = 13.sp),
                modifier = Modifier
                    .padding(top = 8.dp)
                    .semantics { liveRegion = LiveRegionMode.Polite },
            )
        }
    }
}

@Composable
private fun SectionHeader(title: String, open: Boolean, onToggle: () -> Unit) {
    Column(Modifier.fillMaxWidth()) {
        Box(Modifier.fillMaxWidth().height(1.dp).background(HAIRLINE))
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = TARGET_HEIGHT)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClickLabel = if (open) "Fold $title" else "Unfold $title",
                    role = Role.Button,
                    onClick = onToggle,
                )
                .semantics(mergeDescendants = true) {
                    contentDescription = title
                    stateDescription = if (open) "Expanded" else "Collapsed"
                },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = title,
                color = SECTION_TEXT,
                style = TextStyle(fontFamily = HyleGrotesk, fontSize = 14.sp, fontWeight = FontWeight.Bold),
                modifier = Modifier.weight(1f),
            )
            Icon(
                imageVector = if (open) Icons.Outlined.KeyboardArrowUp else Icons.Outlined.KeyboardArrowDown,
                contentDescription = null,
                tint = ROW_LABEL,
                modifier = Modifier.size(20.dp),
            )
        }
    }
}

@Composable
private fun DetailRow(row: MetaRow, copied: Boolean, onCopy: () -> Unit) {
    val copyable = !row.failure && row.copyText.isNotBlank()
    val copyLabel = "Copy ${row.label}"
    Column(Modifier.fillMaxWidth()) {
        Box(Modifier.fillMaxWidth().height(1.dp).background(HAIRLINE))
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = TARGET_HEIGHT)
                .then(
                    if (copyable) {
                        Modifier.pointerInput(row.copyText) { detectTapGestures(onLongPress = { onCopy() }) }
                    } else {
                        Modifier
                    },
                )
                .clearAndSetSemantics {
                    contentDescription = if (copied) "${row.label}, copied" else "${row.label}, ${row.value}"
                    if (copied) liveRegion = LiveRegionMode.Polite
                    if (copyable) {
                        onLongClick(label = copyLabel) {
                            onCopy()
                            true
                        }
                        customActions = listOf(
                            CustomAccessibilityAction(copyLabel) {
                                onCopy()
                                true
                            },
                        )
                    }
                }
                // After the semantics, so the node (and what a screen reader outlines) is the whole
                // 48 dp row, not the padded middle of it.
                .padding(vertical = ROW_VERTICAL_PAD),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                text = row.label,
                color = ROW_LABEL,
                style = TextStyle(fontFamily = HyleGrotesk, fontSize = 13.sp),
                maxLines = 2,
                modifier = Modifier.widthIn(max = LABEL_MAX_WIDTH),
            )
            Text(
                text = if (copied) "Copied" else row.value,
                color = if (copied) MaterialTheme.colorScheme.primary else ROW_VALUE,
                style = TextStyle(fontFamily = HyleGrotesk, fontSize = 13.sp, fontWeight = FontWeight.Medium),
                textAlign = TextAlign.End,
                maxLines = VALUE_MAX_LINES,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

/** The touch-target minimum, for a header and for a row. */
private val TARGET_HEIGHT = 48.dp
private val ROW_VERTICAL_PAD = 6.dp
private val LABEL_MAX_WIDTH = 140.dp
private const val VALUE_MAX_LINES = 4
private const val COPIED_NOTICE_MS = 1_800L
