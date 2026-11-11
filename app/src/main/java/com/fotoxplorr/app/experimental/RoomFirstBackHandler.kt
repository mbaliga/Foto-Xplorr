package com.fotoxplorr.app.experimental

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue

/**
 * The gallery's own [BackHandler], made to be asked before anything inside an open experiment
 * whenever a shell room (the nav or options room) has just been opened over it.
 *
 * The dispatcher asks the callback registered last first, and the gallery's handler is composed
 * before the Experimental section, so an experiment's handlers (the host's close, an inner step,
 * the compass's and the 3D wall's close) were all asked ahead of "close the room": Back closed the
 * experiment underneath a room that stayed open. A handler is registered when it enters the
 * composition, so this one is left and re-entered each time [roomOpen] becomes true; it is then
 * the newest callback and the room is closed first, whichever experiment is open and whatever
 * handler it has. Nothing else about the order changes: closed, it is as old as the screen.
 *
 * The re-entry happens one frame after the room opens (a [LaunchedEffect]); a Back press cannot
 * arrive in that gap. Only a Robolectric test has run this: the device check is an edge drag
 * opening a room over an open experiment, then Back.
 *
 * @param roomOpen whether a shell room is open now.
 * @param enabled whether the handler takes Back at all, as for [BackHandler].
 */
@Composable
fun RoomFirstBackHandler(roomOpen: Boolean, enabled: Boolean, onBack: () -> Unit) {
    var generation by remember { mutableIntStateOf(0) }
    LaunchedEffect(roomOpen) { if (roomOpen) generation++ }
    key(generation) { BackHandler(enabled = enabled, onBack = onBack) }
}
