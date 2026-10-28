package com.fotoxplorr.app.reel

import android.content.Context
import android.net.Uri

/** What the screen needs to know about the sound file the person picked, so a test gives it answers. */
internal interface ReelSoundFiles {
    /** Keep the right to read the file after a restart; false when the provider will not allow it. */
    fun keep(uri: String): Boolean

    /** The file's name as the provider gives it, or null. */
    fun nameOf(uri: String): String?
}

internal class AndroidSoundFiles(private val context: Context) : ReelSoundFiles {
    override fun keep(uri: String): Boolean = SoundtrackAccess.keep(context, Uri.parse(uri))

    override fun nameOf(uri: String): String? = SoundtrackAccess.displayName(context, Uri.parse(uri))
}
