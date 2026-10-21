package com.fotoxplorr.app.viewer

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.exifinterface.media.ExifInterface
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.fotoxplorr.app.FotoXplorrActivity
import com.fotoxplorr.app.LibraryRuntime
import com.fotoxplorr.app.gallery.GalleryPreferences
import com.fotoxplorr.app.media.MediaAsset
import com.fotoxplorr.app.OPEN_REQUEST_HIDDEN_MESSAGE
import com.fotoxplorr.app.OPEN_REQUEST_UNVERIFIED_MESSAGE
import com.fotoxplorr.app.gallery.browsableAssets
import com.fotoxplorr.app.openwith.ExternalAssetLoader
import com.fotoxplorr.app.openwith.MediaStoreUri
import com.fotoxplorr.app.openwith.OpenRequestLookup
import com.fotoxplorr.app.openwith.classifyMediaStoreUri
import com.fotoxplorr.app.openwith.lookupUncataloguedRow
import com.fotoxplorr.app.organize.LibraryStore
import com.fotoxplorr.app.privacy.PrivateFolderStore
import com.fotoxplorr.app.privacy.SensitiveStore
import com.fotoxplorr.app.openwith.syntheticNegativeMediaId
import com.fotoxplorr.app.share.PreparedItem
import com.fotoxplorr.app.share.ShareFrame
import com.fotoxplorr.app.share.ShareOptions
import com.fotoxplorr.app.share.SharePreparer
import com.fotoxplorr.app.ui.FotoXplorrTheme
import com.fotoxplorr.app.ui.crashRecoveryStyle
import com.fotoxplorr.core.model.MediaId
import dev.aarso.crashrecovery.CrashRecovery
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Locale
import kotlinx.coroutines.withContext

/**
 * "Open with Fotoz" (P0-18): the single VIEW target (FotoXplorrActivity only receives EDIT and
 * SEND). A photo or video tapped in another app -- the Files app, including on a USB drive --
 * lands here, never in [FotoXplorrActivity], since this activity needs no gallery and must never
 * touch this app's own catalogue for a file that catalogue has never scanned. The manifest filter
 * is `content://` only, and [onCreate] re-checks the scheme for an explicit intent.
 *
 * Two outcomes, decided once, up front, from the incoming URI alone:
 *  - it is a `content://media/...` row this app's own catalogue already has (someone opened a
 *    real library photo through another app's own picker) -- redirect straight to
 *    [FotoXplorrActivity] via [FotoXplorrActivity.EXTRA_OPEN_MEDIA_ID] and finish. Reusing that
 *    activity's own, already-correct favourite/sensitive/trash/tags/caption/location wiring is
 *    what "open that asset with all its normal actions instead" (the brief's own words) actually
 *    means -- a second, hand-rolled copy of that wiring here would drift from the real one the
 *    moment either changed, and would double the surface a real bug could hide in. That activity
 *    also owns the privacy rule: a photo the library is hiding (locked, archived, trashed,
 *    sensitive-hidden) is NOT opened there, only a short notice is shown. This activity must
 *    never fall back to showing a catalogued row itself -- that would be the side door.
 *  - anything else -- an ad-hoc [MediaAsset] is built from the URI alone (via [buildExternalAsset],
 *    never written to any store) and shown in [ViewerScreen] with `catalogueActions = false`.
 */
class ExternalViewerActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (CrashRecovery.maybeShowRecovery(this, appLabel = "Fotoz", style = crashRecoveryStyle(this))) return
        enableEdgeToEdge()

        val uri = intent?.data
        // The manifest only routes content:// here, but this activity is exported and an explicit
        // intent can carry any Uri; a file:// one would be read with this app's own permissions.
        if (uri == null || !uri.scheme.equals("content", ignoreCase = true)) {
            finish()
            return
        }
        val intentMimeType = intent?.type

        setContent {
            val context = LocalContext.current
            val scope = rememberCoroutineScope()
            var asset by remember { mutableStateOf<MediaAsset?>(null) }
            val galleryPreferences = remember { GalleryPreferences(applicationContext) }
            val preferences by galleryPreferences.observe().collectAsStateWithLifecycle()

            LaunchedEffect(uri) {
                when (val decision = withContext(Dispatchers.IO) { decideOpen(context, uri) }) {
                    is ExternalOpenDecision.Redirect -> {
                        startActivity(
                            Intent(context, FotoXplorrActivity::class.java).apply {
                                putExtra(FotoXplorrActivity.EXTRA_OPEN_MEDIA_ID, decision.mediaId)
                            },
                        )
                        finish()
                        return@LaunchedEffect
                    }
                    is ExternalOpenDecision.Refuse -> {
                        Toast.makeText(context, decision.message, Toast.LENGTH_LONG).show()
                        finish()
                        return@LaunchedEffect
                    }
                    ExternalOpenDecision.OpenExternally -> Unit
                }
                asset = runCatching {
                    withContext(Dispatchers.IO) { buildExternalAsset(context, uri, intentMimeType) }
                }.getOrElse {
                    Toast.makeText(context, "Couldn't open this file.", Toast.LENGTH_LONG).show()
                    finish()
                    return@LaunchedEffect
                }
            }

            // Nothing drawn while resolving (a local SQLite lookup, then at most a bounds decode
            // or an EXIF read) -- both are fast enough that a spinner would only flash, and the
            // redirect branch above never reaches this composition at all.
            asset?.let { current ->
                FotoXplorrTheme(preferences) {
                    Surface(modifier = Modifier.fillMaxSize()) {
                        ViewerScreen(
                            asset = current,
                            position = 1,
                            total = 1,
                            isFavorite = false,
                            isSensitive = false,
                            hasPrevious = false,
                            hasNext = false,
                            canMoveToTrash = false,
                            slideshowActive = false,
                            slideshowIntervalSeconds = 5,
                            onToggleSlideshow = {},
                            onToggleFavorite = {},
                            onToggleSensitive = {},
                            onShare = {
                                shareExternalAsset(current, scope) { message ->
                                    Toast.makeText(this@ExternalViewerActivity, message, Toast.LENGTH_SHORT).show()
                                }
                            },
                            onEdit = {},
                            onOpenWith = { openExternalAssetWith(current) },
                            onMoveToTrash = {},
                            onPrevious = {},
                            onNext = {},
                            onClose = { finish() },
                            catalogueActions = false,
                            // ViewerScreen's default is "autoplay on": without this an externally
                            // opened video would ignore the user turning autoplay off in settings.
                            autoplayVideos = preferences.autoplayVideos,
                        )
                    }
                }
            }
        }
    }

    /** Mirrors [FotoXplorrActivity.openExternally]'s `ACTION_VIEW` branch exactly, for the same
     *  reason: excluding this very activity from the chooser stops a photo already open here from
     *  offering itself right back through "Open with". */
    private fun openExternalAssetWith(asset: MediaAsset) {
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(asset.contentUri, asset.mimeType.ifBlank { "*/*" })
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        val chooser = Intent.createChooser(intent, "Open with").apply {
            putExtra(
                Intent.EXTRA_EXCLUDE_COMPONENTS,
                arrayOf(ComponentName(this@ExternalViewerActivity, ExternalViewerActivity::class.java)),
            )
        }
        runCatching { startActivity(chooser) }
            .onFailure { Toast.makeText(this, "No compatible app was found.", Toast.LENGTH_SHORT).show() }
    }

    /**
     * Prepares [asset] through [SharePreparer] -- the same metadata-stripping pipeline every
     * in-app share uses, which already works on any readable URI and never assumes the asset is
     * catalogued -- and hands the result to the system share sheet. No frame, no watermark: this
     * activity has no [com.fotoxplorr.app.gallery.GalleryPreferences] session of its own to read a
     * chosen frame from, and a bare, unbranded copy is the one choice that needs no such context.
     */
    private fun shareExternalAsset(asset: MediaAsset, scope: CoroutineScope, onMessage: (String) -> Unit) {
        scope.launch {
            val sharePreparer = SharePreparer(this@ExternalViewerActivity)
            val options = ShareOptions(frame = ShareFrame.NONE)
            sharePreparer.prepare(listOf(asset), options, animatedIds = emptySet()).fold(
                onSuccess = { prepared ->
                    val ready = prepared.filterIsInstance<PreparedItem.Ready>().firstOrNull()
                    if (ready == null) {
                        onMessage("Could not prepare this file to share.")
                        return@fold
                    }
                    val intent = Intent(Intent.ACTION_SEND).apply {
                        type = ready.mimeType
                        putExtra(Intent.EXTRA_STREAM, ready.uri)
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                    runCatching { startActivity(Intent.createChooser(intent, "Share")) }
                        .onFailure { onMessage("No compatible sharing app was found.") }
                },
                onFailure = { onMessage(it.message ?: "Could not prepare this file to share.") },
            )
        }
    }
}

/** What [ExternalViewerActivity] does with an incoming Uri; see [decideOpen]. */
private sealed interface ExternalOpenDecision {
    /** A catalogued photo: hand over to [FotoXplorrActivity], which owns the privacy rules. */
    data class Redirect(val mediaId: Long) : ExternalOpenDecision

    /** Not a library photo at all: read it as the external file it is. */
    data object OpenExternally : ExternalOpenDecision

    /** A library photo that must not be shown, or one that cannot be checked: say so, open nothing. */
    data class Refuse(val message: String) : ExternalOpenDecision
}

/**
 * Decides, once, what to do with [uri] -- see [ExternalViewerActivity]'s own KDoc.
 * [LibraryRuntime.repository] is a local SQLite cache of MediaStore, not MediaStore itself, so the
 * catalogue lookup is a fast, fully offline read.
 *
 * A `content://media` Uri is never simply opened as an external file: that would read the photo
 * with this app's own media grant and skip the lock. One the catalogue holds is redirected (the
 * other activity applies the rules). One it does not hold yet -- a photo written a moment ago,
 * inside the scan debounce -- is looked up in MediaStore itself and held to the same rules by its
 * folder identity; a locked folder here stays locked, because this activity's own
 * [PrivateFolderStore] has nothing unlocked (the unlocked set lives in memory in the main
 * activity), which errs on the side of refusing. A media Uri with no readable row, or a row
 * MediaStore will not describe, is refused.
 */
private suspend fun decideOpen(context: Context, uri: Uri): ExternalOpenDecision =
    when (val ref = classifyMediaStoreUri(uri.toString())) {
        MediaStoreUri.NotMediaStore -> ExternalOpenDecision.OpenExternally
        MediaStoreUri.Unreadable -> ExternalOpenDecision.Refuse(OPEN_REQUEST_UNVERIFIED_MESSAGE)
        is MediaStoreUri.Row -> {
            val runtime = LibraryRuntime.get(context)
            if (runtime.repository.awaitLoaded().any { it.id == MediaId(ref.id) }) {
                ExternalOpenDecision.Redirect(ref.id)
            } else {
                val stores = OpenGuardStores(context)
                when (lookupUncataloguedRow(ExternalAssetLoader(context).readMediaStoreRow(ref.id), stores::browsable)) {
                    OpenRequestLookup.Unknown -> {
                        runtime.requestScan(false)
                        ExternalOpenDecision.OpenExternally
                    }
                    OpenRequestLookup.Hidden -> ExternalOpenDecision.Refuse(OPEN_REQUEST_HIDDEN_MESSAGE)
                    is OpenRequestLookup.Found, OpenRequestLookup.Unverified ->
                        ExternalOpenDecision.Refuse(OPEN_REQUEST_UNVERIFIED_MESSAGE)
                }
            }
        }
    }

/** The stores `browsableAssets` reads, opened fresh for the one check [decideOpen] makes. */
private class OpenGuardStores(context: Context) {
    private val library = LibraryStore.get(context)
    private val sensitive = SensitiveStore(context)
    private val privateFolders = PrivateFolderStore(context)
    private val preferences = GalleryPreferences(context)

    fun browsable(list: List<MediaAsset>): List<MediaAsset> = browsableAssets(
        list,
        library.observe().value.archivedIds,
        sensitive.observe().value,
        privateFolders.observeLockedFolders().value,
        privateFolders.observeUnlockedFolders().value,
        preferences.observe().value.hideSensitive,
    )
}

/**
 * Builds an ad-hoc [MediaAsset] straight from [uri] -- every field either read directly off the
 * URI (never off this app's own catalogue, which has never seen this file) or, failing that,
 * defaulted honestly. Never throws for a field this app simply cannot determine; only a
 * completely unreadable [uri] should ever fail this, and does so by propagating that one
 * exception rather than returning a half-built asset.
 */
internal fun buildExternalAsset(context: Context, uri: Uri, intentMimeType: String?): MediaAsset {
    val resolver = context.contentResolver

    var queriedDisplayName: String? = null
    var sizeBytes = 0L
    runCatching {
        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)
            ?.use { cursor ->
                if (cursor.moveToFirst()) {
                    cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME).takeIf { it >= 0 }?.let { index ->
                        queriedDisplayName = cursor.getString(index)
                    }
                    cursor.getColumnIndex(OpenableColumns.SIZE).takeIf { it >= 0 }?.let { index ->
                        if (!cursor.isNull(index)) sizeBytes = cursor.getLong(index)
                    }
                }
            }
    }

    val resolverMimeType = runCatching { resolver.getType(uri) }.getOrNull()
    val identity = resolveExternalAssetIdentity(
        queriedDisplayName = queriedDisplayName,
        uriLastPathSegment = uri.lastPathSegment,
        intentMimeType = intentMimeType,
        resolverMimeType = resolverMimeType,
    )

    var width = 0
    var height = 0
    var durationMillis = 0L
    if (identity.mimeType.startsWith("video/", ignoreCase = true)) {
        runCatching {
            val retriever = MediaMetadataRetriever()
            try {
                retriever.setDataSource(context, uri)
                width = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)
                    ?.toIntOrNull() ?: 0
                height = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)
                    ?.toIntOrNull() ?: 0
                durationMillis = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                    ?.toLongOrNull() ?: 0L
            } finally {
                retriever.release()
            }
        }
    } else {
        runCatching {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
            if (bounds.outWidth > 0 && bounds.outHeight > 0) {
                width = bounds.outWidth
                height = bounds.outHeight
            }
        }
    }

    val dateTakenMillis = runCatching {
        resolver.openInputStream(uri)?.use { stream ->
            ExifInterface(stream).getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL)
        }
    }.getOrNull()?.let(::parseExifDateTimeOriginal) ?: System.currentTimeMillis()

    return MediaAsset(
        // A per-URI id, not MediaId.EXTERNAL: every external asset sharing one id made every
        // external video share one resume-position key, so a clip resumed at another clip's
        // position. Negative by construction, so it can never collide with a catalogue row.
        id = syntheticNegativeMediaId(uri.toString()),
        contentUriString = uri.toString(),
        displayName = identity.displayName,
        mimeType = identity.mimeType,
        bucketName = null,
        bucketId = null,
        dateTakenMillis = dateTakenMillis,
        dateModifiedSeconds = dateTakenMillis / 1_000L,
        width = width,
        height = height,
        sizeBytes = sizeBytes,
        durationMillis = durationMillis,
        relativePath = null,
        isFavorite = false,
        isTrashed = false,
        // Handed to this app, not owned by it: see MediaAsset.isExternal. VideoPlayer keys its
        // "no catalogue writes" rule (key moments, resume positions) off this flag.
        isExternal = true,
    )
}

/** [ExternalViewerActivity.buildExternalAsset]'s two resolved fields, together: a real file's
 *  display name and MIME type are either both known or both worth falling back on together. */
internal data class ExternalAssetIdentity(val displayName: String, val mimeType: String)

/**
 * The pure part of building an ad-hoc asset (P0-18's own brief, verbatim): resolving a safe
 * display name and MIME type from whatever `OpenableColumns`, `Intent.type` and
 * `ContentResolver.getType` happened to report, none of which is guaranteed present or accurate
 * for a URI from another app's own provider. Takes plain values rather than a `Uri`/`Context` so
 * it needs no Robolectric to test.
 */
internal fun resolveExternalAssetIdentity(
    queriedDisplayName: String?,
    uriLastPathSegment: String?,
    intentMimeType: String?,
    resolverMimeType: String?,
): ExternalAssetIdentity {
    val displayName = queriedDisplayName?.trim()?.takeIf { it.isNotEmpty() }
        ?: uriLastPathSegment?.trim()?.takeIf { it.isNotEmpty() }
        ?: "Untitled"
    val extension = displayName.substringAfterLast('.', missingDelimiterValue = "").lowercase()
    val mimeType = intentMimeType?.trim()?.takeIf { it.isNotEmpty() && it != "*/*" }
        ?: resolverMimeType?.trim()?.takeIf { it.isNotEmpty() && it != "*/*" }
        ?: EXTENSION_MIME_FALLBACKS[extension]
        ?: "application/octet-stream"
    return ExternalAssetIdentity(displayName = displayName, mimeType = mimeType)
}

/** A deliberately small last resort -- the manifest's own intent filter already restricts this
 *  activity to image or video MIME types, so Android's own intent resolution means
 *  [intentMimeType] or [resolverMimeType] realistically covers every real case; this only ever
 *  matters for the rare provider that answers a bare wildcard for both. */
private val EXTENSION_MIME_FALLBACKS: Map<String, String> = mapOf(
    "jpg" to "image/jpeg",
    "jpeg" to "image/jpeg",
    "png" to "image/png",
    "gif" to "image/gif",
    "webp" to "image/webp",
    "heic" to "image/heic",
    "heif" to "image/heif",
    "bmp" to "image/bmp",
    "mp4" to "video/mp4",
    "mov" to "video/quicktime",
    "webm" to "video/webm",
    "mkv" to "video/x-matroska",
    "3gp" to "video/3gpp",
)

/**
 * Parses EXIF's own `DateTimeOriginal` format (`"yyyy:MM:dd HH:mm:ss"`, no timezone) directly.
 * `androidx.exifinterface`'s own `ExifInterface.getDateTimeOriginal()` does exactly this
 * internally, but lint's `RestrictedApi` check confirms it is `@RestrictTo(LIBRARY)` -- restricted
 * to that library's own module, not actually public despite being callable -- so this reads the
 * raw tag string via the ordinary, public `getAttribute` and parses it here instead.
 */
internal fun parseExifDateTimeOriginal(raw: String): Long? = runCatching {
    SimpleDateFormat("yyyy:MM:dd HH:mm:ss", Locale.US).parse(raw)?.time
}.getOrNull()
