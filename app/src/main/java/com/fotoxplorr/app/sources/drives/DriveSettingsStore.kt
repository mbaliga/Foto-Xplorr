package com.fotoxplorr.app.sources.drives

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** Where [DriveSettings] live, by drive uuid. An interface so the rules run in JVM tests without a preferences file. */
interface DriveSettingsStore {
    fun all(): StateFlow<Map<String, DriveSettings>>
    fun get(uuid: String): DriveSettings
    fun update(uuid: String, change: (DriveSettings) -> DriveSettings)
    fun forget(uuid: String)
}

class InMemoryDriveSettingsStore : DriveSettingsStore {
    private val state = MutableStateFlow<Map<String, DriveSettings>>(emptyMap())
    override fun all(): StateFlow<Map<String, DriveSettings>> = state.asStateFlow()
    override fun get(uuid: String): DriveSettings = state.value[uuid] ?: DriveSettings()
    override fun update(uuid: String, change: (DriveSettings) -> DriveSettings) {
        state.update { it + (uuid to change(it[uuid] ?: DriveSettings())) }
    }
    override fun forget(uuid: String) {
        state.update { it - uuid }
    }
}

/**
 * [DriveSettingsStore] over one SharedPreferences file of its own, `fotoz-drives`. A new file:
 * nothing existing is read or changed. Nothing is written until a drive is answered, so a phone that
 * never meets a drive never gets the file. Rebuildable: losing it only means a drive is asked about again.
 */
class PrefsDriveSettingsStore(context: Context) : DriveSettingsStore {
    private val prefs = context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)
    private val state: MutableStateFlow<Map<String, DriveSettings>> by lazy { MutableStateFlow(load()) }

    override fun all(): StateFlow<Map<String, DriveSettings>> = state.asStateFlow()

    override fun get(uuid: String): DriveSettings = state.value[uuid] ?: DriveSettings()

    @Synchronized
    override fun update(uuid: String, change: (DriveSettings) -> DriveSettings) {
        val next = change(get(uuid))
        prefs.edit()
            .putString("$uuid$SEP$LABEL", next.label)
            .putBoolean("$uuid$SEP$NEVER", next.never)
            .putBoolean("$uuid$SEP$ASKED", next.asked)
            .putBoolean("$uuid$SEP$INCLUDED", next.included)
            .putBoolean("$uuid$SEP$ON_CONNECT", next.indexOnConnect)
            .putInt("$uuid$SEP$PREVIEWS", next.previews.wire)
            .apply()
        state.update { it + (uuid to next) }
    }

    @Synchronized
    override fun forget(uuid: String) {
        if (uuid !in state.value) return
        val e = prefs.edit()
        for (k in KEYS) e.remove("$uuid$SEP$k")
        e.apply()
        state.update { it - uuid }
    }

    private fun load(): Map<String, DriveSettings> {
        val ids = prefs.all.keys.mapNotNull { k -> k.substringBefore(SEP, "").takeIf { it.isNotEmpty() && k.contains(SEP) } }.toSet()
        return ids.associateWith { id ->
            DriveSettings(
                label = prefs.getString("$id$SEP$LABEL", "") ?: "",
                never = prefs.getBoolean("$id$SEP$NEVER", false),
                asked = prefs.getBoolean("$id$SEP$ASKED", false),
                included = prefs.getBoolean("$id$SEP$INCLUDED", true),
                indexOnConnect = prefs.getBoolean("$id$SEP$ON_CONNECT", false),
                previews = PreviewPolicy.fromWire(prefs.getInt("$id$SEP$PREVIEWS", PreviewPolicy.AS_VIEWED.wire)),
            )
        }
    }

    private companion object {
        const val FILE = "fotoz-drives"
        const val SEP = "|"
        const val LABEL = "label"
        const val NEVER = "never"
        const val ASKED = "asked"
        const val INCLUDED = "included"
        const val ON_CONNECT = "onConnect"
        const val PREVIEWS = "previews"
        val KEYS = listOf(LABEL, NEVER, ASKED, INCLUDED, ON_CONNECT, PREVIEWS)
    }
}
