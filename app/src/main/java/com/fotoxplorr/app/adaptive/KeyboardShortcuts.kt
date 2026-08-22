package com.fotoxplorr.app.adaptive

/**
 * The physical keys the gallery gives a meaning to, named independently of any UI toolkit.
 *
 * `androidx.compose.ui.input.key.Key` would do this job directly, but reading it here would put
 * a Compose-UI type in a package this task's own hard constraint keeps pure Kotlin (so the
 * dispatch table below is a plain `when` a JVM test can drive without Robolectric). The call
 * site in GalleryScreen -- which already imports Compose's `Key` for the real event -- does the
 * one-line mapping from `Key.Enter` etc. into this enum before calling [galleryShortcutFor].
 */
enum class GalleryShortcutKey {
    ARROW_UP,
    ARROW_DOWN,
    ARROW_LEFT,
    ARROW_RIGHT,
    ENTER,
    ESCAPE,
    DELETE,
    SLASH,
    LETTER_A,
}

/** A direction key was pressed, moving the keyboard-focused tile within the grid. */
enum class MoveDirection { UP, DOWN, LEFT, RIGHT }

/** What a keystroke means to the gallery, decoupled from which physical key produced it. */
sealed interface GalleryShortcut {
    data class MoveSelection(val direction: MoveDirection) : GalleryShortcut
    data object OpenFocused : GalleryShortcut
    data object CloseOrClear : GalleryShortcut
    data object TrashSelected : GalleryShortcut
    data object SelectAll : GalleryShortcut
    data object FocusSearch : GalleryShortcut
}

/**
 * The gallery's keyboard shortcut table: arrows move the keyboard cursor, Enter opens it,
 * Escape backs out (of a selection, of search, of the open photo -- the call site decides which,
 * same as the existing `BackHandler`), Delete trashes the current selection, Ctrl+A selects
 * everything in view, and `/` jumps to search -- the six the owner asked for, in one place so
 * a seventh can be added here rather than wherever the next call site happens to need one.
 *
 * Returns `null` for a key this table has no opinion about, rather than a default -- an unbound
 * key must fall through to whatever the OS or a text field would otherwise do with it (typing
 * `/` while the search field already has focus, for instance), which only works if this function
 * stays out of the way instead of swallowing it.
 *
 * [ctrlPressed] only changes the outcome for `A` (bare `A` is not a shortcut -- it would fight
 * typing into the search box) and is otherwise ignored: Ctrl+Enter and Enter open the same
 * thing, because there is nothing a modifier could sensibly change about "open".
 */
fun galleryShortcutFor(key: GalleryShortcutKey, ctrlPressed: Boolean): GalleryShortcut? = when {
    key == GalleryShortcutKey.LETTER_A && ctrlPressed -> GalleryShortcut.SelectAll
    key == GalleryShortcutKey.LETTER_A -> null
    key == GalleryShortcutKey.SLASH -> GalleryShortcut.FocusSearch
    key == GalleryShortcutKey.ARROW_UP -> GalleryShortcut.MoveSelection(MoveDirection.UP)
    key == GalleryShortcutKey.ARROW_DOWN -> GalleryShortcut.MoveSelection(MoveDirection.DOWN)
    key == GalleryShortcutKey.ARROW_LEFT -> GalleryShortcut.MoveSelection(MoveDirection.LEFT)
    key == GalleryShortcutKey.ARROW_RIGHT -> GalleryShortcut.MoveSelection(MoveDirection.RIGHT)
    key == GalleryShortcutKey.ENTER -> GalleryShortcut.OpenFocused
    key == GalleryShortcutKey.ESCAPE -> GalleryShortcut.CloseOrClear
    key == GalleryShortcutKey.DELETE -> GalleryShortcut.TrashSelected
    else -> null
}
