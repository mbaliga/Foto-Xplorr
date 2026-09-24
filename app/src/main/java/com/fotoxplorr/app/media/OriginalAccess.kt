package com.fotoxplorr.app.media

import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.core.content.ContextCompat

/**
 * The uri a caller reading location-bearing metadata should read, together with whether that
 * read actually asked the platform for the unredacted original.
 *
 * Android 10+ strips GPS (and a few other) tags from a [MediaStore] uri's EXIF unless the reader
 * both holds `ACCESS_MEDIA_LOCATION` and calls [MediaStore.setRequireOriginal] on that uri first
 * (see "Location information in media files" in the shared-storage docs). This app requested the
 * permission but never called that method, so every location read on Android 10+ silently saw no
 * GPS at all, and the absence was then cached forever (P0-02, defect: original access).
 */
data class LocationReadUri(val uri: Uri, val original: Boolean)

/**
 * Builds the uri to read for location-bearing metadata (GPS EXIF tags, camera capture direction,
 * and any other field the platform redacts under scoped storage).
 *
 * Never use this for a share, export, zip or "send it out" path: [MediaStore.setRequireOriginal]
 * exists to let an app read what it needs to show the user their own photo's real metadata, not
 * to launder a redacted uri into one that leaks location to another app or a remote server. Those
 * paths must keep reading (or writing) the plain uri exactly as they do today.
 *
 * [MediaStore.setRequireOriginal] can itself throw ([UnsupportedOperationException] if the
 * platform refuses, [SecurityException] if the permission check races and loses) when the
 * returned uri is later opened, not from this call -- a caller must catch both on open and retry
 * once with the plain uri, reporting `original = false` for that retry.
 */
fun Context.uriForLocationRead(uri: Uri): LocationReadUri {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return LocationReadUri(uri, original = false)
    if (uri.authority != MediaStore.AUTHORITY) return LocationReadUri(uri, original = false)
    val granted = ContextCompat.checkSelfPermission(
        this,
        android.Manifest.permission.ACCESS_MEDIA_LOCATION,
    ) == PackageManager.PERMISSION_GRANTED
    if (!granted) return LocationReadUri(uri, original = false)
    return LocationReadUri(MediaStore.setRequireOriginal(uri), original = true)
}

/**
 * Opens [requested] for reading, retrying once with [plainUri] (reporting `original = false`)
 * exactly when [open] throws [UnsupportedOperationException] or [SecurityException] for a
 * `requested.original` read -- [uriForLocationRead]'s documented contract, factored out here so
 * every caller (currently `GeoMetadataRepository` and `readImageExifDetails`) implements the
 * retry identically. Any other failure (a plain missing file, say) is not retried: a different
 * uri for the same file won't help. [open] returning null (rather than throwing) is treated the
 * same as a failure -- also not retried, since that is not the specific case this contract covers.
 */
inline fun <T : Any> openForLocationRead(
    requested: LocationReadUri,
    plainUri: Uri,
    open: (Uri) -> T?,
): Pair<T, Boolean>? {
    val primary = runCatching { open(requested.uri) }
    primary.getOrNull()?.let { return it to requested.original }
    val failure = primary.exceptionOrNull()
    if (requested.original && (failure is UnsupportedOperationException || failure is SecurityException)) {
        runCatching { open(plainUri) }.getOrNull()?.let { return it to false }
    }
    return null
}
