package com.fotoxplorr.app.viewer

import android.net.Uri

/**
 * What [VideoPlayer] is doing right now, for a host that wants to take playback over externally
 * -- the shape a future `MediaSessionService` needs to keep this exact video playing in the
 * background/notification after the viewer itself goes away. [VideoPlayer] reports this through
 * its `onSnapshotChanged` parameter on every meaningful playback-state change (play/pause/seek and
 * on each position-poll tick while playing); the default is a no-op so nothing has to wire this up
 * to get the rest of this file's features.
 */
data class PlaybackSnapshot(val contentUri: Uri, val positionMs: Long, val playing: Boolean)
