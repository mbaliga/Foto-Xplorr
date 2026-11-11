package com.fotoxplorr.app.viewer.slideshow

import android.content.Context

/** The slideshow's look in the app's own preferences: a way of viewing, one key per setting. */
class SlideshowStyleStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("viewer_slideshow_style", Context.MODE_PRIVATE)

    fun load(): SlideshowStyle = SlideshowStyle(
        transition = prefs.getString(KEY_TRANSITION, null)
            ?.let { name -> SlideshowTransition.values().firstOrNull { it.name == name } }
            ?: SlideshowTransition.FADE,
        kenBurns = prefs.getBoolean(KEY_KEN_BURNS, false),
    )

    fun setTransition(t: SlideshowTransition) { prefs.edit().putString(KEY_TRANSITION, t.name).apply() }

    fun setKenBurns(on: Boolean) { prefs.edit().putBoolean(KEY_KEN_BURNS, on).apply() }

    private companion object {
        const val KEY_TRANSITION = "transition"
        const val KEY_KEN_BURNS = "ken_burns"
    }
}
