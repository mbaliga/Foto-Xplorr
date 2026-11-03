package com.fotoxplorr.app.sync

import android.content.Context
import android.content.Intent
import android.net.Uri
import com.fotoxplorr.app.ai.EncryptedSecretStore
import com.fotoxplorr.app.jobs.JobRunner
import com.fotoxplorr.app.media.MediaAsset
import com.fotoxplorr.app.media.openForLocationRead
import com.fotoxplorr.app.media.uriForLocationRead
import java.io.File
import java.io.IOException

/**
 * The Android seam of sync: the pieces that need a Context. Everything else in this package is plain
 * Kotlin tested on the JVM; this file is NOT run by any test and has never run on a device.
 */
object SyncAndroid {
    /**
     * Keeps the read+write grant of a folder the person just picked, so a run started later (or after a
     * restart) can still write to it. Returns false when the platform refused. Call with the Uri from
     * the system folder picker, before saving it into [SyncDestinationConfig.folderTreeUri].
     */
    fun takeFolderGrant(context: Context, treeUri: Uri): Boolean = runCatching {
        context.applicationContext.contentResolver.takePersistableUriPermission(
            treeUri,
            Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
        )
    }.isSuccess

    /**
     * Builds the service the app uses. [browsable] is the browsable library list (TRAPS #33) and must
     * keep the same instance until it changes; [jobRunner] is the activity's runner (null where there
     * is none, which makes [SyncService.start] refuse).
     */
    fun createService(
        context: Context,
        browsable: () -> List<MediaAsset>,
        jobRunner: () -> JobRunner?,
    ): SyncService {
        val app = context.applicationContext
        val directory = File(app.filesDir, "sync")
        val secrets = KeystoreSyncSecrets(EncryptedSecretStore(app))
        val kinds = if (SyncTransports.available) SyncDestinationKind.entries.toSet() else setOf(SyncDestinationKind.FOLDER)
        return DefaultSyncService(
            configStore = FileSyncConfigStore(File(directory, "destination.txt")),
            secrets = secrets,
            manifestStores = { id -> FileSyncManifestStore(File(directory, "manifest-${id.filter { it.isLetterOrDigit() }}.txt")) },
            library = browsable,
            fileSource = { config, snapshot -> MediaStoreSyncFileSource(app, snapshot, includeLocation = config.encryptEnabled) },
            providers = AndroidSyncProviderFactory(app),
            revoker = if (SyncTransports.available) DefaultOAuthRevoker(SyncTransports.create()) else OAuthRevoker.NONE,
            network = { SyncConnectivity.current(app) },
            runner = jobRunner,
            supportedKinds = kinds,
        )
    }
}

/** [SyncSecrets] on the Keystore-backed store the AI keys use (same store, ids prefixed `sync:`). */
class KeystoreSyncSecrets(private val store: EncryptedSecretStore) : SyncSecrets {
    override fun get(id: String): CharArray? = store.get(prefix(id))
    override fun put(id: String, value: CharArray) = store.put(prefix(id), value)
    override fun remove(id: String) = store.remove(prefix(id))
    private fun prefix(id: String) = "sync:$id"
}

class AndroidSyncProviderFactory(private val context: Context) : SyncProviderFactory {
    override fun create(config: SyncDestinationConfig, secret: CharArray?): SyncProvider = try {
        when (config.kind) {
            SyncDestinationKind.FOLDER -> {
                val uri = Uri.parse(config.folderTreeUri ?: throw SyncException(SyncFailure.DESTINATION_GONE, SyncCopy.FOLDER_GONE))
                SafFolderProvider(SafTreeFileSystem(context, uri))
            }
            SyncDestinationKind.WEBDAV -> {
                if (!SyncTransports.available) throw SyncException(SyncFailure.UNAVAILABLE_BUILD, SyncCopy.NEEDS_CONNECT_BUILD)
                val settings = config.webDav ?: throw SyncException(SyncFailure.REJECTED, SyncCopy.NO_DESTINATION)
                WebDavProvider(settings, secret ?: CharArray(0), SyncTransports.create())
            }
            SyncDestinationKind.S3 -> {
                if (!SyncTransports.available) throw SyncException(SyncFailure.UNAVAILABLE_BUILD, SyncCopy.NEEDS_CONNECT_BUILD)
                val settings = config.s3 ?: throw SyncException(SyncFailure.REJECTED, SyncCopy.NO_DESTINATION)
                S3CompatibleProvider(settings, secret ?: CharArray(0), SyncTransports.create())
            }
            SyncDestinationKind.GDRIVE -> {
                if (!SyncTransports.available) throw SyncException(SyncFailure.UNAVAILABLE_BUILD, SyncCopy.NEEDS_CONNECT_BUILD)
                val settings = config.oauth ?: throw SyncException(SyncFailure.REJECTED, SyncCopy.NO_DESTINATION)
                DriveProvider(settings.clientId, secret ?: throw SyncException(SyncFailure.AUTH, SyncCopy.SIGN_IN_AGAIN), SyncTransports.create())
            }
            SyncDestinationKind.DROPBOX -> {
                if (!SyncTransports.available) throw SyncException(SyncFailure.UNAVAILABLE_BUILD, SyncCopy.NEEDS_CONNECT_BUILD)
                val settings = config.oauth ?: throw SyncException(SyncFailure.REJECTED, SyncCopy.NO_DESTINATION)
                DropboxProvider(settings.clientId, secret ?: throw SyncException(SyncFailure.AUTH, SyncCopy.SIGN_IN_AGAIN), SyncTransports.create())
            }
        }
    } finally {
        // The providers copy what they keep; the caller's buffer dies here, as the secrets contract says.
        secret?.fill('\u0000')
    }
}

/**
 * Opens a candidate's bytes from MediaStore, the original file. By default the platform's own view is
 * read, which on Android 10+ may leave out the photo's GPS (TRAPS #31: a plain copy leaving the app must
 * not launder the unredacted original). When [includeLocation] is set (only for an ENCRYPTED destination,
 * like the encrypted backup) the unredacted original is asked for, so the copy keeps its location.
 */
class MediaStoreSyncFileSource(
    context: Context,
    snapshot: List<MediaAsset>,
    private val includeLocation: Boolean,
) : SyncFileSource {
    private val appContext = context.applicationContext
    private val byId: Map<Long, MediaAsset> = snapshot.associateBy { it.id.value }

    override fun open(candidate: SyncCandidate): OpenedLocalFile? {
        val asset = byId[candidate.mediaId] ?: return null
        val plain: Uri = asset.contentUri
        val descriptor = try {
            if (includeLocation) {
                openForLocationRead(appContext.uriForLocationRead(plain), plain) { uri ->
                    appContext.contentResolver.openAssetFileDescriptor(uri, "r")
                }?.first
            } else {
                appContext.contentResolver.openAssetFileDescriptor(plain, "r")
            }
        } catch (e: IOException) {
            null
        } catch (e: SecurityException) {
            null
        } ?: return null
        val stream = try {
            descriptor.createInputStream()
        } catch (e: IOException) {
            runCatching { descriptor.close() }
            return null
        }
        // The length the engine promised the destination is the catalogue's size; a provider that reports a
        // different length now means the file changed, and the upload fails its own byte count and is retried.
        return OpenedLocalFile(stream, descriptor.length.takeIf { it >= 0 } ?: candidate.sizeBytes)
    }
}
