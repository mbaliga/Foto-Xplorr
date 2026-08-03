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

data class GalleryPreferencesState(
    val sort: GallerySort = GallerySort.NEWEST,
    val gridColumns: Int = DEFAULT_GRID_COLUMNS,
    val blurSensitive: Boolean = true,
)

class GalleryPreferences(context: Context) {
    private val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
    private val state = MutableStateFlow(load())

    fun observe(): StateFlow<GalleryPreferencesState> = state.asStateFlow()

    fun setSort(sort: GallerySort) {
        val updated = state.value.copy(sort = sort)
        state.value = updated
        preferences.edit().putString(KEY_SORT, sort.name).apply()
    }

    fun setGridColumns(columns: Int) {
        val safeColumns = columns.coerceIn(MIN_GRID_COLUMNS, MAX_GRID_COLUMNS)
        val updated = state.value.copy(gridColumns = safeColumns)
        state.value = updated
        preferences.edit().putInt(KEY_GRID_COLUMNS, safeColumns).apply()
    }

    fun setBlurSensitive(enabled: Boolean) {
        state.value = state.value.copy(blurSensitive = enabled)
        preferences.edit().putBoolean(KEY_BLUR_SENSITIVE, enabled).apply()
    }

    private fun load(): GalleryPreferencesState {
        val sort = preferences.getString(KEY_SORT, null)
            ?.let { stored -> GallerySort.entries.firstOrNull { it.name == stored } }
            ?: GallerySort.NEWEST
        val columns = preferences
            .getInt(KEY_GRID_COLUMNS, DEFAULT_GRID_COLUMNS)
            .coerceIn(MIN_GRID_COLUMNS, MAX_GRID_COLUMNS)
        return GalleryPreferencesState(
            sort = sort,
            gridColumns = columns,
            blurSensitive = preferences.getBoolean(KEY_BLUR_SENSITIVE, true),
        )
    }

    private companion object {
        const val PREFERENCES_NAME = "foto_xplorr_gallery"
        const val KEY_SORT = "sort"
        const val KEY_GRID_COLUMNS = "grid_columns"
        const val KEY_BLUR_SENSITIVE = "blur_sensitive"
    }
}

const val MIN_GRID_COLUMNS = 2
const val MAX_GRID_COLUMNS = 6
const val DEFAULT_GRID_COLUMNS = 3
