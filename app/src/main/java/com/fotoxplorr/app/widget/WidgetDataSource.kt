package com.fotoxplorr.app.widget

import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import com.fotoxplorr.app.LibraryRuntime
import com.fotoxplorr.app.background.BackgroundWorkStatusCenter
import com.fotoxplorr.app.favorites.FavoriteStore
import com.fotoxplorr.app.mediaReadPermissions
import com.fotoxplorr.app.moments.VideoMomentStore
import com.fotoxplorr.app.organize.LibraryStore
import com.fotoxplorr.app.privacy.PrivateFolderStore
import com.fotoxplorr.app.privacy.SensitiveStore
import com.fotoxplorr.app.recognition.RecognitionStore
import kotlinx.coroutines.flow.first

/**
 * Reads the app's real stores for the widgets, in the widget's own process context: never from an
 * Activity, a ViewModel or anything a screen holds.
 *
 * - The catalogue comes from [LibraryRuntime.get]'s one repository, through `awaitLoaded()` (TRAPS
 *   #21: a fresh repository's StateFlow first holds an empty list), never a second repository.
 * - [LibraryStore.get] is the one process-wide instance (TRAPS #26). The favourite, sensitive and
 *   protected-folder stores are read-only here; each loads its SharedPreferences synchronously in
 *   its constructor, and the platform shares one SharedPreferences object per file per process, so
 *   a fresh instance sees every write the app has applied.
 * - Protected folders: only the LIST of protected folders is read. Which ones the app session has
 *   unlocked is never asked; see [WidgetPrivacy].
 * - Identity documents come from [RecognitionStore.identityDocumentIds], and how fresh the catalogue
 *   is from [LibraryReadStamp].
 */
internal object WidgetDataSource {
    @Volatile private var recognitionStore: RecognitionStore? = null
    @Volatile private var momentStore: VideoMomentStore? = null

    suspend fun load(context: Context): WidgetLibrary {
        val app = context.applicationContext
        val access = hasMediaReadAccess(app)
        val library = LibraryStore.get(app).observe().value
        return WidgetLibrary(
            // No permission: draw nothing from a catalogue the app may no longer read.
            assets = if (access) LibraryRuntime.get(app).repository.awaitLoaded() else emptyList(),
            favoriteIds = FavoriteStore(app).observe().first(),
            sensitiveIds = SensitiveStore(app).observe().value,
            archivedIds = library.archivedIds,
            lockedFolders = PrivateFolderStore(app).observeLockedFolders().value,
            collections = library.collections,
            hasMediaAccess = access,
            // Straight from the recognition database, not its in-memory index (which a fresh
            // instance holds empty, TRAPS #21). An unreadable database draws nothing at all
            // (the caller shows the error state) rather than risk drawing an identity document.
            identityIds = if (access) recognition(app).identityDocumentIds() else emptySet(),
            libraryReadAtMillis = LibraryReadStamp.read(app),
        )
    }

    /** The indexing widget's numbers; only read when that widget is placed. */
    suspend fun indexing(context: Context, library: WidgetLibrary): WidgetState<IndexingContent> {
        if (!library.hasMediaAccess) return WidgetState.NoAccess
        val app = context.applicationContext
        val photosPending = recognition(app).pendingAssets(indexingPhotos(library)).size
        val moments = moments(app)
        val videosScanned = indexingVideos(library).count { moments.hasBeenScanned(it.id) }
        val activity = indexingActivity(
            status = BackgroundWorkStatusCenter.status.value,
            scan = LibraryRuntime.get(app).scanState.value,
        )
        return indexingState(library, photosPending, videosScanned, activity)
    }

    // One read-only instance each per process, so repeated widget updates do not open a new
    // SQLite connection every time.
    private fun recognition(app: Context): RecognitionStore =
        recognitionStore ?: synchronized(this) {
            recognitionStore ?: RecognitionStore(app).also { recognitionStore = it }
        }

    private fun moments(app: Context): VideoMomentStore =
        momentStore ?: synchronized(this) {
            momentStore ?: VideoMomentStore(app).also { momentStore = it }
        }
}

/** The same permission set the Activity asks for (full or Android 14+ selected-photos access). */
internal fun hasMediaReadAccess(context: Context): Boolean =
    mediaReadPermissions().any { permission ->
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
    }
