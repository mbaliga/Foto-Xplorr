package com.fotoxplorr.app.sources.previews

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * What the person chose about previews, per source: the cap in bytes (0 = off) and whether the
 * bounded background pass keeps them filled ("keep previews"). Beside, not inside, the existing
 * stores; losing it resets both to their defaults and loses no photo and no preview.
 */
interface PreviewSettings {
    fun capBytes(sourceRow: Long): Long
    fun keep(sourceRow: Long): Boolean
    fun setCapBytes(sourceRow: Long, capBytes: Long)
    fun setKeep(sourceRow: Long, keep: Boolean)
    fun forget(sourceRow: Long)

    /** Changes to the settings, so a screen can redraw. */
    fun changes(): StateFlow<Int>
}

class InMemoryPreviewSettings : PreviewSettings {
    private val caps = HashMap<Long, Long>()
    private val keeps = HashMap<Long, Boolean>()
    private val version = MutableStateFlow(0)

    @Synchronized override fun capBytes(sourceRow: Long): Long = caps[sourceRow] ?: PreviewPolicy.DEFAULT_CAP_BYTES

    @Synchronized override fun keep(sourceRow: Long): Boolean = keeps[sourceRow] ?: true

    @Synchronized override fun setCapBytes(sourceRow: Long, capBytes: Long) { caps[sourceRow] = capBytes.coerceAtLeast(0L); version.update { it + 1 } }

    @Synchronized override fun setKeep(sourceRow: Long, keep: Boolean) { keeps[sourceRow] = keep; version.update { it + 1 } }

    @Synchronized override fun forget(sourceRow: Long) { caps.remove(sourceRow); keeps.remove(sourceRow); version.update { it + 1 } }

    override fun changes(): StateFlow<Int> = version.asStateFlow()
}

/** One SharedPreferences file of its own, `fotoz-source-previews`; nothing is written until the person changes something. */
class PrefsPreviewSettings(context: Context) : PreviewSettings {
    private val prefs = context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)
    private val version = MutableStateFlow(0)

    override fun capBytes(sourceRow: Long): Long = prefs.getLong("$sourceRow.cap", PreviewPolicy.DEFAULT_CAP_BYTES).coerceAtLeast(0L)

    override fun keep(sourceRow: Long): Boolean = prefs.getBoolean("$sourceRow.keep", true)

    override fun setCapBytes(sourceRow: Long, capBytes: Long) {
        prefs.edit().putLong("$sourceRow.cap", capBytes.coerceAtLeast(0L)).apply(); version.update { it + 1 }
    }

    override fun setKeep(sourceRow: Long, keep: Boolean) {
        prefs.edit().putBoolean("$sourceRow.keep", keep).apply(); version.update { it + 1 }
    }

    override fun forget(sourceRow: Long) {
        prefs.edit().remove("$sourceRow.cap").remove("$sourceRow.keep").apply(); version.update { it + 1 }
    }

    override fun changes(): StateFlow<Int> = version.asStateFlow()

    private companion object {
        const val FILE = "fotoz-source-previews"
    }
}
