package com.fotoxplorr.app.gallery

import android.content.Context
import com.fotoxplorr.app.editor.EditorSaveMode
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
    /** Hold the screen awake while a photo is open. Off by default: it costs battery. */
    val keepScreenOn: Boolean = false,
    /** Play a slideshow in a random order rather than in the current sort order. */
    val slideshowShuffle: Boolean = false,
    /** Start a video as soon as it is opened, rather than waiting for a tap on play. */
    val autoplayVideos: Boolean = false,
    /**
     * Show the filmstrip of neighbouring photos along the bottom of an open photo. On by default,
     * which is the behaviour the viewer already had.
     */
    val showFilmstrip: Boolean = true,
    // ---- media behaviour ----
    /** Peek a photo large on long press, without leaving the grid. */
    val longPressPreview: Boolean = true,
    /** Play GIFs and animated images in the grid rather than showing a still frame. */
    val loopAnimations: Boolean = false,
    /** Crop grid thumbnails to fill their tile; off keeps each photo's own aspect ratio. */
    val fitToTile: Boolean = true,
    /**
     * What the editor's Save does. ASK by default, which is the owner's choice and also the only
     * default that cannot destroy a photograph before the user has understood the control.
     */
    val editorSaveMode: EditorSaveMode = EditorSaveMode.ASK,
    // ---- share defaults, edited in the advanced share sheet ----
    /**
     * Strip GPS/camera/timestamp EXIF from shared copies. TRUE by default on owner direction:
     * the safe thing should be what happens when nobody thinks about it.
     */
    val shareStripMetadata: Boolean = true,
    val shareWatermark: Boolean = false,
    /** Last frame chosen in the share sheet, remembered so a habit does not need re-picking. */
    val shareFrame: String = "NONE",
    /** The user's postmark, drawn on stamp-framed shares. */
    val shareSeal: String = "",
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

    fun setKeepScreenOn(enabled: Boolean) = update(
        state.value.copy(keepScreenOn = enabled),
    ) { putBoolean(KEY_KEEP_SCREEN_ON, enabled) }

    fun setSlideshowShuffle(enabled: Boolean) = update(
        state.value.copy(slideshowShuffle = enabled),
    ) { putBoolean(KEY_SLIDESHOW_SHUFFLE, enabled) }

    fun setAutoplayVideos(enabled: Boolean) = update(
        state.value.copy(autoplayVideos = enabled),
    ) { putBoolean(KEY_AUTOPLAY_VIDEOS, enabled) }

    fun setShowFilmstrip(enabled: Boolean) = update(
        state.value.copy(showFilmstrip = enabled),
    ) { putBoolean(KEY_SHOW_FILMSTRIP, enabled) }

    fun setLongPressPreview(enabled: Boolean) = update(
        state.value.copy(longPressPreview = enabled),
    ) { putBoolean(KEY_LONG_PRESS_PREVIEW, enabled) }

    fun setLoopAnimations(enabled: Boolean) = update(
        state.value.copy(loopAnimations = enabled),
    ) { putBoolean(KEY_LOOP_ANIMATIONS, enabled) }

    fun setFitToTile(enabled: Boolean) = update(
        state.value.copy(fitToTile = enabled),
    ) { putBoolean(KEY_FIT_TO_TILE, enabled) }

    fun setEditorSaveMode(mode: EditorSaveMode) = update(
        state.value.copy(editorSaveMode = mode),
    ) { putString(KEY_EDITOR_SAVE_MODE, mode.name) }

    fun setShareStripMetadata(enabled: Boolean) = update(
        state.value.copy(shareStripMetadata = enabled),
    ) { putBoolean(KEY_SHARE_STRIP, enabled) }

    fun setShareWatermark(enabled: Boolean) = update(
        state.value.copy(shareWatermark = enabled),
    ) { putBoolean(KEY_SHARE_WATERMARK, enabled) }

    fun setShareFrame(frame: String) = update(
        state.value.copy(shareFrame = frame),
    ) { putString(KEY_SHARE_FRAME, frame) }

    fun setShareSeal(seal: String) {
        val trimmed = seal.trim().take(MAX_SEAL_CHARS)
        update(state.value.copy(shareSeal = trimmed)) { putString(KEY_SHARE_SEAL, trimmed) }
    }

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
        // Every one of these defaults to the behaviour the app already had, which is not a
        // stylistic choice: CatalogueCharacterisationTest pins the projections by fingerprint,
        // so a new preference whose default changed what a destination contains would move a
        // golden -- and a golden that needs editing means the refactor changed behaviour.
        keepScreenOn = preferences.getBoolean(KEY_KEEP_SCREEN_ON, false),
        slideshowShuffle = preferences.getBoolean(KEY_SLIDESHOW_SHUFFLE, false),
        autoplayVideos = preferences.getBoolean(KEY_AUTOPLAY_VIDEOS, false),
        showFilmstrip = preferences.getBoolean(KEY_SHOW_FILMSTRIP, true),
        longPressPreview = preferences.getBoolean(KEY_LONG_PRESS_PREVIEW, true),
        loopAnimations = preferences.getBoolean(KEY_LOOP_ANIMATIONS, false),
        fitToTile = preferences.getBoolean(KEY_FIT_TO_TILE, true),
        editorSaveMode = enumValue(KEY_EDITOR_SAVE_MODE, EditorSaveMode.ASK),
        shareStripMetadata = preferences.getBoolean(KEY_SHARE_STRIP, true),
        shareWatermark = preferences.getBoolean(KEY_SHARE_WATERMARK, false),
        shareFrame = preferences.getString(KEY_SHARE_FRAME, null) ?: "NONE",
        shareSeal = preferences.getString(KEY_SHARE_SEAL, null).orEmpty(),
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
        const val KEY_KEEP_SCREEN_ON = "keep_screen_on"
        const val KEY_SLIDESHOW_SHUFFLE = "slideshow_shuffle"
        const val KEY_AUTOPLAY_VIDEOS = "autoplay_videos"
        const val KEY_SHOW_FILMSTRIP = "show_filmstrip"
        const val KEY_LONG_PRESS_PREVIEW = "long_press_preview"
        const val KEY_LOOP_ANIMATIONS = "loop_animations"
        const val KEY_EDITOR_SAVE_MODE = "editor_save_mode"
        const val KEY_FIT_TO_TILE = "fit_to_tile"
        const val KEY_SHARE_STRIP = "share_strip_metadata"
        const val KEY_SHARE_WATERMARK = "share_watermark"
        const val KEY_SHARE_FRAME = "share_frame"
        const val KEY_SHARE_SEAL = "share_seal"
        const val MAX_SEAL_CHARS = 12
    }
}

const val MIN_GRID_COLUMNS = 2
const val MAX_GRID_COLUMNS = 7
const val DEFAULT_GRID_COLUMNS = 3
const val MIN_SLIDESHOW_INTERVAL_SECONDS = 2
const val MAX_SLIDESHOW_INTERVAL_SECONDS = 12
const val DEFAULT_SLIDESHOW_INTERVAL_SECONDS = 4
