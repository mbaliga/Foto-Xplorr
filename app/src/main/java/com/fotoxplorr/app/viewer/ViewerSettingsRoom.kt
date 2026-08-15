package com.fotoxplorr.app.viewer

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Remove
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.fotoxplorr.app.gallery.MAX_SLIDESHOW_INTERVAL_SECONDS
import com.fotoxplorr.app.gallery.MIN_SLIDESHOW_INTERVAL_SECONDS

/**
 * The viewer's TOP room: the handful of settings that are about *looking at a photo*, put where
 * you are while looking at one (owner, 2026-08-14: *"make the settings room at the top"*).
 *
 * Deliberately not the app's whole settings surface. Everything here changes what the screen the
 * user is currently on does; anything that does not belong to the act of viewing belongs in the
 * gallery's settings room, and duplicating it in two places is how two settings screens end up
 * disagreeing about the same preference.
 */
@Composable
fun ViewerSettingsRoom(
    slideshowIntervalSeconds: Int,
    blurSensitive: Boolean,
    onSetSlideshowInterval: (Int) -> Unit,
    onSetBlurSensitive: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(Color.Black)
            // The shell insets a top room by its band at the BOTTOM (that is where the parked
            // card sits), so the system bar to clear here is the navigation bar, not the status
            // bar — the opposite of what a top-anchored surface usually wants.
            .navigationBarsPadding()
            .padding(horizontal = 24.dp, vertical = 32.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        Text(
            text = "VIEWING",
            color = Color.White.copy(alpha = 0.5f),
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Bold,
        )

        Stepper(
            label = "Slideshow interval",
            value = "${slideshowIntervalSeconds}s",
            onDecrease = { onSetSlideshowInterval(slideshowIntervalSeconds - 1) },
            onIncrease = { onSetSlideshowInterval(slideshowIntervalSeconds + 1) },
            canDecrease = slideshowIntervalSeconds > MIN_SLIDESHOW_INTERVAL_SECONDS,
            canIncrease = slideshowIntervalSeconds < MAX_SLIDESHOW_INTERVAL_SECONDS,
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Column(Modifier.padding(end = 16.dp)) {
                Text("Blur sensitive photos", color = Color.White, style = MaterialTheme.typography.bodyLarge)
                Text(
                    "Applies in the grid. A photo you have opened deliberately is never blurred.",
                    color = Color.White.copy(alpha = 0.55f),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Switch(checked = blurSensitive, onCheckedChange = onSetBlurSensitive)
        }

        Text(
            text = "Pinch to zoom · two fingers to rotate · swipe to move between photos · " +
                "drag up from the bottom for this photo's details",
            color = Color.White.copy(alpha = 0.45f),
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

@Composable
private fun Stepper(
    label: String,
    value: String,
    onDecrease: () -> Unit,
    onIncrease: () -> Unit,
    canDecrease: Boolean,
    canIncrease: Boolean,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(label, color = Color.White, style = MaterialTheme.typography.bodyLarge)
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onDecrease, enabled = canDecrease) {
                Icon(
                    Icons.Outlined.Remove,
                    contentDescription = "Shorter",
                    tint = Color.White.copy(alpha = if (canDecrease) 1f else 0.3f),
                    modifier = Modifier.size(20.dp),
                )
            }
            Text(
                text = value,
                color = Color.White,
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.padding(horizontal = 8.dp),
            )
            IconButton(onClick = onIncrease, enabled = canIncrease) {
                Icon(
                    Icons.Outlined.Add,
                    contentDescription = "Longer",
                    tint = Color.White.copy(alpha = if (canIncrease) 1f else 0.3f),
                    modifier = Modifier.size(20.dp),
                )
            }
        }
    }
}
