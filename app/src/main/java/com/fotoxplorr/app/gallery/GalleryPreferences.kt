package com.fotoxplorr.app.gallery

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class GallerySort {
    NEWEST,
    OLDEST,
    NAME,
    SIZE,
}

enum class TimelineGrouping {
    DAY,
    MONTH,
    NONE,
}

enum class ThemeMode {
    SYSTEM,
    LIGHT,
    DARK,
}

enum class AccentPalette {
    VIOLET,
    OCEAN,
    FOREST,
    AMBER,
    MONOCHROME,
}

data class GalleryPreferencesState(
    val sort: GallerySort = GallerySort.NEWEST,
    val gridColumns: Int = DEFAULT_GRID_COLUMNS,
    val blurSensitive: Boolean = true,
    val hideSensitive: Boolean = false,
    val showVideos: Boolean = true,
    val timelineGrouping: TimelineGrouping = TimelineGrouping.DAY,
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val accentPalette: AccentPalette = AccentPalette.VIOLET,
    val slideshowIntervalSeconds: Int = DEFAULT_SLIDESHOW_INTERVAL_SECONDS,
    /**
     * Settings screen's "Default View": which destination opens when the app launches. Now
     * one of the nine primary destinations from the mockups, not one of the four retired
     * bottom-nav tabs. Existing installs that stored a bottom-nav value fall through
     * [enumValue]'s unknown-name guard and land back on PHOTOS, which is the closest
     * equivalent of the old TIMELINE default.
     */
    val defaultDestination: HyleDestination = HyleDestination.PHOTOS,
)

class GalleryPreferences(context: Context) {
    private val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
    private val state = MutableStateFlow(load())

    fun observe(): StateFlow<GalleryPreferencesState> = state.asStateFlow()

    fun setSort(sort: GallerySort) = update(
        state.value.copy(sort = sort),
    ) { putString(KEY_SORT, sort.name) }

    fun setGridColumns(columns: Int) {
        val safeColumns = columns.coerceIn(MIN_GRID_COLUMNS, MAX_GRID_COLUMNS)
        update(state.value.copy(gridColumns = safeColumns)) {
            putInt(KEY_GRID_COLUMNS, safeColumns)
        }
    }

    fun setBlurSensitive(enabled: Boolean) = update(
        state.value.copy(blurSensitive = enabled),
    ) { putBoolean(KEY_BLUR_SENSITIVE, enabled) }

    fun setHideSensitive(enabled: Boolean) = update(
        state.value.copy(hideSensitive = enabled),
    ) { putBoolean(KEY_HIDE_SENSITIVE, enabled) }

    fun setShowVideos(enabled: Boolean) = update(
        state.value.copy(showVideos = enabled),
    ) { putBoolean(KEY_SHOW_VIDEOS, enabled) }

    fun setTimelineGrouping(grouping: TimelineGrouping) = update(
        state.value.copy(timelineGrouping = grouping),
    ) { putString(KEY_TIMELINE_GROUPING, grouping.name) }

    fun setThemeMode(mode: ThemeMode) = update(
        state.value.copy(themeMode = mode),
    ) { putString(KEY_THEME_MODE, mode.name) }

    fun setAccentPalette(palette: AccentPalette) = update(
        state.value.copy(accentPalette = palette),
    ) { putString(KEY_ACCENT_PALETTE, palette.name) }

    fun setDefaultDestination(destination: HyleDestination) = update(
        state.value.copy(defaultDestination = destination),
    ) { putString(KEY_DEFAULT_DESTINATION, destination.name) }

    fun setSlideshowInterval(seconds: Int) {
        val safeSeconds = seconds.coerceIn(MIN_SLIDESHOW_INTERVAL_SECONDS, MAX_SLIDESHOW_INTERVAL_SECONDS)
        update(state.value.copy(slideshowIntervalSeconds = safeSeconds)) {
            putInt(KEY_SLIDESHOW_INTERVAL, safeSeconds)
        }
    }

    private fun update(
        updated: GalleryPreferencesState,
        write: android.content.SharedPreferences.Editor.() -> Unit,
    ) {
        state.value = updated
        preferences.edit().apply(write).apply()
    }

    private fun load(): GalleryPreferencesState = GalleryPreferencesState(
        sort = enumValue(KEY_SORT, GallerySort.NEWEST),
        gridColumns = preferences
            .getInt(KEY_GRID_COLUMNS, DEFAULT_GRID_COLUMNS)
            .coerceIn(MIN_GRID_COLUMNS, MAX_GRID_COLUMNS),
        blurSensitive = preferences.getBoolean(KEY_BLUR_SENSITIVE, true),
        hideSensitive = preferences.getBoolean(KEY_HIDE_SENSITIVE, false),
        showVideos = preferences.getBoolean(KEY_SHOW_VIDEOS, true),
        timelineGrouping = enumValue(KEY_TIMELINE_GROUPING, TimelineGrouping.DAY),
        themeMode = enumValue(KEY_THEME_MODE, ThemeMode.SYSTEM),
        accentPalette = enumValue(KEY_ACCENT_PALETTE, AccentPalette.VIOLET),
        slideshowIntervalSeconds = preferences
            .getInt(KEY_SLIDESHOW_INTERVAL, DEFAULT_SLIDESHOW_INTERVAL_SECONDS)
            .coerceIn(MIN_SLIDESHOW_INTERVAL_SECONDS, MAX_SLIDESHOW_INTERVAL_SECONDS),
        defaultDestination = enumValue(KEY_DEFAULT_DESTINATION, HyleDestination.PHOTOS),
    )

    private inline fun <reified T : Enum<T>> enumValue(key: String, fallback: T): T =
        preferences.getString(key, null)
            ?.let { stored -> enumValues<T>().firstOrNull { it.name == stored } }
            ?: fallback

    private companion object {
        const val PREFERENCES_NAME = "foto_xplorr_gallery"
        const val KEY_SORT = "sort"
        const val KEY_GRID_COLUMNS = "grid_columns"
        const val KEY_BLUR_SENSITIVE = "blur_sensitive"
        const val KEY_HIDE_SENSITIVE = "hide_sensitive"
        const val KEY_SHOW_VIDEOS = "show_videos"
        const val KEY_TIMELINE_GROUPING = "timeline_grouping"
        const val KEY_THEME_MODE = "theme_mode"
        const val KEY_ACCENT_PALETTE = "accent_palette"
        const val KEY_SLIDESHOW_INTERVAL = "slideshow_interval"
        const val KEY_DEFAULT_DESTINATION = "default_destination"
    }
}

const val MIN_GRID_COLUMNS = 2
const val MAX_GRID_COLUMNS = 7
const val DEFAULT_GRID_COLUMNS = 3
const val MIN_SLIDESHOW_INTERVAL_SECONDS = 2
const val MAX_SLIDESHOW_INTERVAL_SECONDS = 12
const val DEFAULT_SLIDESHOW_INTERVAL_SECONDS = 4
