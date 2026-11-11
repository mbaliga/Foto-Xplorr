package com.fotoxplorr.app.hyle

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.error as semanticsError
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.fotoxplorr.app.ui.HyleGrotesk
import com.fotoxplorr.app.ui.LocalAccentOnLight

/**
 * The five states the Hyle field draws, as the owner's control-sheet lays them out. They are two
 * independent axes — focus and validity — plus a disabled terminal, but the sheet names the useful
 * combinations, so the component takes the axes and derives the look.
 */
data class HyleFieldStyle(
    val focused: Boolean = false,
    val error: Boolean = false,
    val mandatory: Boolean = false,
    val enabled: Boolean = true,
)

/**
 * The Hyle field: a text row wearing the language's corner tab. A small leaning **wedge** sits at
 * the top-left, poking above the field and carrying the state's colour — dark when idle, the chosen
 * accent when focused, danger-red (with a `!`) when the value is invalid. The field's own top-left corner
 * is cut back to seat it, so the tab reads as *part of* the field rather than a sticker on it.
 *
 * A focused or invalid field also takes a coloured hairline border; a required field shows a `*`
 * at the trailing edge. Colours come from [dev.aarso.hyle.tokens.HyleTokens] so the field, the
 * toggle and the edge notches all speak with one palette.
 *
 * This is the chrome only — pass the label/value as [content], or use [HyleTextField] for an
 * editable field. Rendered as a light control to match the sheet; it reads on either theme because
 * its own surface and ink are explicit.
 */
@Composable
fun HyleField(
    style: HyleFieldStyle,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    // The focus state is the accent role: the field is a fixed LIGHT control in every theme mode, so
    // it takes the chosen accent's light-mode form (see LocalAccentOnLight), not a hard-coded violet.
    val focusAccent = LocalAccentOnLight.current
    val accent = when {
        !style.enabled -> WEDGE_DISABLED
        style.error -> DANGER
        style.focused -> focusAccent
        else -> WEDGE_IDLE
    }
    val border = when {
        !style.enabled -> Color.Transparent
        style.error && style.focused -> DANGER
        style.focused -> focusAccent
        style.error -> DANGER.copy(alpha = 0.5f)
        else -> Color.Transparent
    }
    val fill = if (style.enabled) FIELD_FILL else FIELD_FILL_DISABLED

    Box(modifier = modifier.fillMaxWidth()) {
        // Field body. The rounded rect carries the fill and (when set) the coloured hairline; the
        // top-left cut and the wedge are drawn over it.
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = WEDGE_RISE.dp)
                .heightIn(min = FIELD_H.dp)
                .background(fill, RoundedCornerShape(FIELD_RADIUS.dp))
                .then(
                    if (border != Color.Transparent) {
                        Modifier.border(FIELD_BORDER.dp, border, RoundedCornerShape(FIELD_RADIUS.dp))
                    } else {
                        Modifier
                    }
                )
                .padding(start = CONTENT_START.dp, end = CONTENT_END.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.weight(1f)) { content() }
            if (style.mandatory) {
                Text(
                    text = "*",
                    color = accent,
                    style = TextStyle(fontFamily = HyleGrotesk, fontSize = 22.sp, fontWeight = FontWeight.Medium),
                )
            }
        }

        // The wedge tab, drawn last so it sits above the field's top edge at the top-left. Fixed
        // size gives the parallelogram and the `!` a canvas to live in.
        Box(
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(start = WEDGE_LEFT.dp)
                .size(width = (WEDGE_W + WEDGE_SKEW).dp, height = (WEDGE_RISE + WEDGE_INTO).dp)
                .drawBehind {
                    drawWedge(accent)
                    // The `!` is drawn, not typed: a white stroke + dot, so it keeps its weight at
                    // this size where a glyph would go spindly.
                    if (style.error) drawBang()
                },
        )
    }
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawWedge(color: Color) {
    val w = WEDGE_W.dp.toPx()
    val h = size.height
    val skew = WEDGE_SKEW.dp.toPx()
    val r = WEDGE_RADIUS.dp.toPx()
    // A right-leaning parallelogram with softly rounded ends.
    val p = Path().apply {
        moveTo(skew + r, 0f)
        lineTo(w, 0f)
        lineTo(w - skew, h - r)
        quadraticBezierTo(w - skew, h, w - skew - r, h)
        lineTo(r, h)
        quadraticBezierTo(0f, h, 0f, h - r)
        lineTo(skew, r)
        quadraticBezierTo(skew, 0f, skew + r, 0f)
        close()
    }
    drawPath(p, color)
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawBang() {
    val cx = WEDGE_W.dp.toPx() / 2f + WEDGE_SKEW.dp.toPx() / 2f
    val stroke = 2.6.dp.toPx()
    val top = size.height * 0.24f
    val stemBottom = size.height * 0.60f
    drawLine(Color.White, Offset(cx, top), Offset(cx, stemBottom), strokeWidth = stroke)
    drawCircle(Color.White, radius = stroke * 0.62f, center = Offset(cx, size.height * 0.74f))
}

/**
 * An editable [HyleField]: a text field wearing the Hyle chrome, per the edge-control spec's
 * "Tab field" (Hyle-Design-System `docs/design/flat-edge-controls.md` section 3, commit 40ca614 on
 * its design branch; the geometry below is that table). Focus lights the wedge and border in the
 * chosen accent; [error] (or a non-null [errorText]) turns them danger-red and shows the `!`;
 * [mandatory] adds the trailing `*`. Optional [leading]/[trailing] slots ride inside the field.
 *
 * **Focus colour, deliberately not the spec's.** The spec's table gives a violet wedge with a blue
 * `#6E97E8` hairline; this app draws both in the chosen accent ([LocalAccentOnLight]) so the field
 * follows the owner's accent setting like every other control. That is the documented rule in
 * [HyleField] ("the focus state is the accent role"), kept on purpose.
 *
 * The one field for the whole app. On top of the chrome it gives:
 * - an optional [label] above the field and an [errorText] line below it (the error is said in
 *   words too, never colour alone, and is exposed to TalkBack as the field's error);
 * - [keyboardOptions]/[keyboardActions] for numeric and decimal keyboards and the IME action;
 * - a single line that never shows a clipped start: unfocused, a long value is drawn with an
 *   ellipsis at its END; focused, the line scrolls with the caret as any text field does;
 * - [clearable]: a 48 dp clear button while there is text and the field is enabled;
 * - a 48 dp minimum height (the chrome is 52), growing with [minLines] for a multi-line field;
 * - a disabled state, and a TalkBack name ([contentDescription], else [label], else [placeholder]).
 *
 * The first nine parameters are the original signature; every later one has a default.
 */
@Composable
fun HyleTextField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String = "",
    enabled: Boolean = true,
    error: Boolean = false,
    mandatory: Boolean = false,
    leading: (@Composable () -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
    label: String? = null,
    errorText: String? = null,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    keyboardActions: KeyboardActions = KeyboardActions.Default,
    singleLine: Boolean = true,
    minLines: Int = 1,
    visualTransformation: VisualTransformation = VisualTransformation.None,
    clearable: Boolean = false,
    monospace: Boolean = false,
    contentDescription: String? = null,
    /** A test tag for the editable node itself (the tag on [modifier] lands on the wrapping column). */
    fieldTag: String? = null,
) {
    var focused by remember { mutableStateOf(false) }
    val ownFocus = remember { FocusRequester() }
    val invalid = error || errorText != null
    val name = contentDescription ?: label ?: placeholder.takeIf { it.isNotEmpty() }
    val fontFamily = if (monospace) FontFamily.Monospace else HyleGrotesk
    val ink = if (enabled) FIELD_INK else FIELD_INK_FAINT
    val textStyle = TextStyle(fontFamily = fontFamily, fontSize = 17.sp, color = ink)
    // Unfocused, a long single line is drawn by a Text that can end in an ellipsis; the real field
    // sits over it with transparent ink so a tap still focuses it. Password and other transformed
    // fields keep the real field's own drawing.
    val ellipsised = singleLine && !focused && value.isNotEmpty() && visualTransformation == VisualTransformation.None
    Column(modifier = modifier.fillMaxWidth()) {
        if (!label.isNullOrBlank()) {
            Text(
                text = label,
                color = FIELD_LABEL,
                style = TextStyle(fontFamily = HyleGrotesk, fontSize = 13.sp, fontWeight = FontWeight.Medium),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(start = 4.dp, bottom = 2.dp),
            )
        }
        HyleField(
            style = HyleFieldStyle(focused = focused, error = invalid, mandatory = mandatory, enabled = enabled),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (leading != null) {
                    leading()
                    Spacer(Modifier.width(8.dp))
                }
                Box(Modifier.weight(1f).padding(vertical = if (singleLine) 0.dp else 12.dp)) {
                    if (ellipsised) {
                        Text(
                            text = value,
                            style = textStyle,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.fillMaxWidth().clearAndSetSemantics { },
                        )
                    }
                    BasicTextField(
                        value = value,
                        onValueChange = onValueChange,
                        enabled = enabled,
                        singleLine = singleLine,
                        minLines = minLines,
                        textStyle = if (ellipsised) textStyle.copy(color = Color.Transparent) else textStyle,
                        cursorBrush = SolidColor(LocalAccentOnLight.current),
                        keyboardOptions = keyboardOptions,
                        keyboardActions = keyboardActions,
                        visualTransformation = visualTransformation,
                        modifier = Modifier
                            .fillMaxWidth()
                            .focusRequester(ownFocus)
                            .onFocusChanged { focused = it.isFocused }
                            .then(if (fieldTag != null) Modifier.testTag(fieldTag) else Modifier)
                            .semantics {
                                if (name != null) this.contentDescription = name
                                if (invalid) semanticsError(errorText ?: "Invalid value")
                            },
                    )
                    if (value.isEmpty() && placeholder.isNotEmpty()) {
                        Text(
                            text = placeholder,
                            color = FIELD_INK_FAINT,
                            style = TextStyle(fontFamily = fontFamily, fontSize = 17.sp),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.clearAndSetSemantics { },
                        )
                    }
                }
                if (clearable && enabled && value.isNotEmpty()) {
                    IconButton(
                        onClick = {
                            onValueChange("")
                            // Clearing is editing: leave the caret in the field, keyboard up.
                            runCatching { ownFocus.requestFocus() }
                        },
                        modifier = Modifier.size(HYLE_FIELD_MIN_TARGET.dp),
                    ) {
                        Icon(
                            Icons.Outlined.Close,
                            contentDescription = "Clear" + (label?.let { " $it" } ?: ""),
                            tint = WEDGE_IDLE,
                        )
                    }
                }
                if (trailing != null) {
                    Spacer(Modifier.width(8.dp))
                    trailing()
                }
            }
        }
        if (errorText != null) {
            Text(
                text = errorText,
                color = DANGER_INK,
                style = TextStyle(fontFamily = HyleGrotesk, fontSize = 13.sp),
                modifier = Modifier.padding(start = 4.dp, top = 2.dp),
            )
        }
    }
}

/** The smallest touch target the field's own controls (the clear button) take, in dp. */
const val HYLE_FIELD_MIN_TARGET = 48

// ---- geometry & palette (from the owner's control-sheet mockup) ----
private const val FIELD_H = 52
private const val FIELD_RADIUS = 14
private const val FIELD_BORDER = 2
private const val CONTENT_START = 22
private const val CONTENT_END = 18

private const val WEDGE_W = 15
private const val WEDGE_SKEW = 5
private const val WEDGE_RADIUS = 3
private const val WEDGE_RISE = 10   // how far the wedge pokes above the field
private const val WEDGE_INTO = 26   // how far it runs down into/alongside the field
private const val WEDGE_LEFT = 10

// Fixed on purpose (reference design / semantic): the light control's neutrals and the Hyle danger
// red. The focus wedge, border and cursor are NOT here; they follow the accent (LocalAccentOnLight).
private val FIELD_FILL = Color(0xFFF1F1F2)
private val FIELD_FILL_DISABLED = Color(0xFFF4F4F5)
private val DANGER = Color(0xFFE5564B)
// The error line sits on the room (often dark), not on the light field, so it is a lighter red.
private val DANGER_INK = Color(0xFFFF8A80)
private val FIELD_LABEL = Color(0xFFB8B8C0)
private val WEDGE_IDLE = Color(0xFF3A3A44)
private val WEDGE_DISABLED = Color(0xFFC7C7CC)
private val FIELD_INK = Color(0xFF2A2A30)
private val FIELD_INK_FAINT = Color(0xFF9A9AA2)
