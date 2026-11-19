package com.fotoxplorr.app.hyle

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.toggleableState
import androidx.compose.ui.state.ToggleableState
import dev.aarso.hyle.component.HyleCapToggle
import dev.aarso.hyle.theme.LocalHyleColors
import dev.aarso.hyle.theme.lightHyleColors

/**
 * The app's on/off switch: Hyle's [HyleCapToggle] (the slant-cap two-option switch) with the
 * options "Off" and "On". This replaces the local parallelogram-knob `HyleToggle`; the owner
 * retired both switch designs in favour of the cap toggle (2026-11-19).
 *
 * The cap toggle is a light tray with an ink cap in either theme, so it is given the light Hyle
 * palette here regardless of the room it sits in -- the same "one fixed control, whatever it
 * sits on" rule the old toggle followed.
 *
 * A tap anywhere on it flips the state (not just on the uncovered half): this is a boolean, so
 * there is no "select the side I tapped" -- the big target is the point.
 *
 * @param onCheckedChange null draws it display-only, for a row that is itself the one switch
 *   (the row carries the tap and the semantics; this carries the picture). Display-only claims
 *   no role, no click and no toggle state, so a screen reader finds one control, not two.
 */
@Composable
fun HyleOnOff(
    checked: Boolean,
    onCheckedChange: ((Boolean) -> Unit)?,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    description: String? = null,
) {
    val interactive = onCheckedChange != null
    CompositionLocalProvider(LocalHyleColors provides lightHyleColors()) {
        HyleCapToggle(
            optionA = "Off",
            optionB = "On",
            selected = if (checked) 1 else 0,
            onSelect = if (interactive) ({ _: Int -> onCheckedChange?.invoke(!checked) }) else null,
            modifier = modifier.clearAndSetSemantics {
                if (description != null) contentDescription = description
                stateDescription = if (checked) "On" else "Off"
                if (interactive) {
                    role = Role.Switch
                    toggleableState = ToggleableState(checked)
                    if (enabled) onClick { onCheckedChange?.invoke(!checked); true } else disabled()
                }
            },
            enabled = enabled,
        )
    }
}
