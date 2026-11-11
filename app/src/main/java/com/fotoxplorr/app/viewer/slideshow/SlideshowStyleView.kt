package com.fotoxplorr.app.viewer.slideshow

import android.content.Context
import android.provider.Settings
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import com.fotoxplorr.app.ui.RoomEyebrow
import com.fotoxplorr.app.ui.RoomRow
import com.fotoxplorr.app.ui.RoomStyle
import com.fotoxplorr.app.ui.RoomToggle

/** Whether the system's animations are on (Developer options / accessibility "remove animations" sets the scale to 0). */
internal fun systemAnimationsEnabled(context: Context): Boolean =
    runCatching {
        Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) > 0f
    }.getOrDefault(true)

/** The view-options room's slideshow-look group: a tap steps the transition; the drift is a toggle. */
@Composable
internal fun ColumnScope.SlideshowStyleRoomSection(
    style: SlideshowStyle,
    onSetTransition: (SlideshowTransition) -> Unit,
    onSetKenBurns: (Boolean) -> Unit,
) {
    RoomEyebrow("SLIDESHOW LOOK")
    RoomRow(
        label = SlideshowStyle.Copy.TRANSITION_ROW,
        caption = SlideshowStyle.Copy.TRANSITION_CAPTION,
        onClick = { onSetTransition(style.transition.next()) },
    ) {
        Text(
            text = style.transition.label,
            color = RoomStyle.Ink,
            style = RoomStyle.Row,
            modifier = Modifier.testTag(SLIDESHOW_TRANSITION_VALUE_TAG),
        )
    }
    RoomRow(
        label = SlideshowStyle.Copy.KEN_BURNS_ROW,
        caption = SlideshowStyle.Copy.KEN_BURNS_CAPTION,
        onClick = { onSetKenBurns(!style.kenBurns) },
    ) { RoomToggle(style.kenBurns, onSetKenBurns) }
}

internal const val SLIDESHOW_TRANSITION_VALUE_TAG = "viewer-slideshow-transition-value"
