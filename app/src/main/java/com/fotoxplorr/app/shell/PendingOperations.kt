package com.fotoxplorr.app.shell

/**
 * The three things Android's own trash/restore/delete consent sheet can be asked to do. Public
 * (previously a private enum inside `FotoXplorrActivity.kt`) so [AppStateViewModel] can hold which
 * one is in flight across a rotation, alongside the ids it is in flight for.
 */
enum class PendingMediaOperation { TRASH, RESTORE, DELETE }

/** Which of the two document-tree operations `treeLauncher`'s callback is completing. */
enum class PendingTreeOperation { COPY, MOVE }
