package com.fotoxplorr.app.background

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Persists the user's [WorkRules] to this app's own `SharedPreferences`, and keeps a
 * [StateFlow] of them for the settings screen to render.
 *
 * Same shape as [com.fotoxplorr.app.gallery.GalleryPreferences]: load once into a
 * [MutableStateFlow] in the constructor, then keep that flow and disk in lock-step on every
 * write, so reading [observe] never blocks a composition on I/O and the value in memory is
 * always what was last written, not what happens to still be cached. Deliberately does NOT also
 * arm or disarm [BackgroundScheduler] on write -- that stays the settings screen's job (a
 * `LaunchedEffect` keyed on the observed rules; see SettingsTabs.kt's BACKGROUND tab), the same
 * way this class has no opinion on what happens to the grid when [com.fotoxplorr.app.gallery.GalleryPreferences]
 * changes. A preferences store's whole job is being a truthful, low-latency mirror of what was
 * saved; reaching out to schedule a platform job from inside a `SharedPreferences.Editor.apply()`
 * callback would make a store that can partially fail (write succeeds, scheduling throws) in a
 * way callers have no reason to expect from something named "Store".
 */
class WorkRulesStore(context: Context) {
    private val preferences = context.applicationContext
        .getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
    private val state = MutableStateFlow(load())

    fun observe(): StateFlow<WorkRules> = state.asStateFlow()

    fun setEnabled(enabled: Boolean) = update(
        state.value.copy(enabled = enabled),
    ) { putBoolean(KEY_ENABLED, enabled) }

    fun setRequireIdle(enabled: Boolean) = update(
        state.value.copy(requireIdle = enabled),
    ) { putBoolean(KEY_REQUIRE_IDLE, enabled) }

    fun setRequireCharging(enabled: Boolean) = update(
        state.value.copy(requireCharging = enabled),
    ) { putBoolean(KEY_REQUIRE_CHARGING, enabled) }

    fun setMinBatteryPercent(percent: Int) {
        val safePercent = percent.coerceIn(MIN_BATTERY_PERCENT_ALLOWED, MAX_BATTERY_PERCENT_ALLOWED)
        update(state.value.copy(minBatteryPercent = safePercent)) {
            putInt(KEY_MIN_BATTERY_PERCENT, safePercent)
        }
    }

    fun setActiveHoursStart(hour: Int) {
        val safeHour = hour.coerceIn(MIN_HOUR_OF_DAY, MAX_HOUR_OF_DAY)
        update(state.value.copy(activeHoursStart = safeHour)) { putInt(KEY_HOURS_START, safeHour) }
    }

    fun setActiveHoursEnd(hour: Int) {
        val safeHour = hour.coerceIn(MIN_HOUR_OF_DAY, MAX_HOUR_OF_DAY)
        update(state.value.copy(activeHoursEnd = safeHour)) { putInt(KEY_HOURS_END, safeHour) }
    }

    fun setOnlyOnUnmetered(enabled: Boolean) = update(
        state.value.copy(onlyOnUnmetered = enabled),
    ) { putBoolean(KEY_ONLY_ON_UNMETERED, enabled) }

    private fun update(updated: WorkRules, write: SharedPreferences.Editor.() -> Unit) {
        state.value = updated
        preferences.edit().apply(write).apply()
    }

    private fun load(): WorkRules = WorkRules(
        requireIdle = preferences.getBoolean(KEY_REQUIRE_IDLE, false),
        requireCharging = preferences.getBoolean(KEY_REQUIRE_CHARGING, false),
        minBatteryPercent = preferences
            .getInt(KEY_MIN_BATTERY_PERCENT, DEFAULT_MIN_BATTERY_PERCENT)
            .coerceIn(MIN_BATTERY_PERCENT_ALLOWED, MAX_BATTERY_PERCENT_ALLOWED),
        activeHoursStart = preferences
            .getInt(KEY_HOURS_START, MIN_HOUR_OF_DAY)
            .coerceIn(MIN_HOUR_OF_DAY, MAX_HOUR_OF_DAY),
        activeHoursEnd = preferences
            .getInt(KEY_HOURS_END, MAX_HOUR_OF_DAY)
            .coerceIn(MIN_HOUR_OF_DAY, MAX_HOUR_OF_DAY),
        onlyOnUnmetered = preferences.getBoolean(KEY_ONLY_ON_UNMETERED, false),
        // ON by default: an install that never opens this settings tab should behave exactly
        // like one that opened it and left every rule at its sane default, not like the feature
        // was never applied at all.
        enabled = preferences.getBoolean(KEY_ENABLED, true),
    )

    private companion object {
        const val PREFERENCES_NAME = "foto_xplorr_background_work"
        const val KEY_ENABLED = "enabled"
        const val KEY_REQUIRE_IDLE = "require_idle"
        const val KEY_REQUIRE_CHARGING = "require_charging"
        const val KEY_MIN_BATTERY_PERCENT = "min_battery_percent"
        const val KEY_HOURS_START = "active_hours_start"
        const val KEY_HOURS_END = "active_hours_end"
        const val KEY_ONLY_ON_UNMETERED = "only_on_unmetered"
    }
}
