package com.fotoxplorr.app.viewer

import android.content.ComponentName
import android.content.ContentUris
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
import com.fotoxplorr.app.media.MediaId
import com.fotoxplorr.app.share.PreparedItem
import com.fotoxplorr.app.share.ShareFrame
import com.fotoxplorr.app.share.ShareOptions
import com.fotoxplorr.app.share.SharePreparer
import com.fotoxplorr.app.ui.FotoXplorrTheme
import dev.aarso.crashrecovery.CrashRecovery
import dev.aarso.crashrecovery.CrashRecoveryStyle
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Locale
import kotlinx.coroutines.withContext

/**
 * "Open with Foto Xplorr" (P0-18): the only entry point besides the launcher. A photo or video
 * tapped in another app -- the Files app, including on a USB drive -- lands here, never in
 * [FotoXplorrActivity], since this activity needs no gallery and must never touch this app's own
 * catalogue for a file that catalogue has never scanned.
 *
 * Two outcomes, decided once, up front, from the incoming URI alone:
 *  - it is a `content://media/...` row this app's own catalogue already has (someone opened a
 *    real library photo through another app's own picker) -- redirect straight to
 *    [FotoXplorrActivity] via [FotoXplorrActivity.EXTRA_OPEN_MEDIA_ID] and finish. Reusing that
 *    activity's own, already-correct favourite/sensitive/trash/tags/caption/location wiring is
 *    what "open that asset with all its normal actions instead" (the brief's own words) actually
 *    means -- a second, hand-rolled copy of that wiring here would drift from the real one the
 *    moment either changed, and would double the surface a real bug could hide in.
 *  - anything else -- an ad-hoc [MediaAsset] is built from the URI alone (via [buildExternalAsset],
 *    never written to any store) and shown in [ViewerScreen] with `catalogueActions = false`.
 */
class ExternalViewerActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (CrashRecovery.maybeShowRecovery(this, appLabel = "Foto Xplorr", style = EXTERNAL_CRASH_STYLE)) return
        enableEdgeToEdge()

        val uri = intent?.data
        if (uri == null) {
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
                val catalogedId = withContext(Dispatchers.IO) { catalogedMediaId(context, uri) }
                if (catalogedId != null) {
                    startActivity(
                        Intent(context, FotoXplorrActivity::class.java).apply {
                            putExtra(FotoXplorrActivity.EXTRA_OPEN_MEDIA_ID, catalogedId)
                        },
                    )
                    finish()
                    return@LaunchedEffect
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

/**
 * Foto Xplorr's accent, for the recovery screen -- identical values to [FotoXplorrActivity]'s own
 * private `CRASH_STYLE`. Not shared as a single top-level constant across the two files: neither
 * activity is the "owner" of the app's accent colour more than the other, and duplicating four
 * @ColorInt literals costs far less than the coupling a shared file would add for it.
 */
private val EXTERNAL_CRASH_STYLE = CrashRecoveryStyle.accent(
    light = 0xFF6E49B8.toInt(),
    onLight = 0xFFFFFFFF.toInt(),
    dark = 0xFFCBB4FF.toInt(),
    onDark = 0xFF221636.toInt(),
)

/**
 * Null unless [uri] is a `content://media/...` row this app's own catalogue has already scanned --
 * see [ExternalViewerActivity]'s own KDoc for what the caller does with either answer.
 * [LibraryRuntime.repository] is a local SQLite cache of MediaStore, not MediaStore itself, so
 * this is a fast, fully offline lookup, never a network or MediaStore round trip.
 */
private suspend fun catalogedMediaId(context: Context, uri: Uri): Long? {
    if (uri.authority != "media") return null
    val id = runCatching { ContentUris.parseId(uri) }.getOrNull() ?: return null
    val repository = LibraryRuntime.get(context).repository
    val isCataloged = repository.awaitLoaded().any { it.id == MediaId(id) }
    return id.takeIf { isCataloged }
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
        id = MediaId.EXTERNAL,
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
