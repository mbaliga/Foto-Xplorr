package com.fotoxplorr.app.gallery

import android.content.ContentResolver
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.DocumentsContract
import android.provider.MediaStore
import androidx.annotation.RequiresApi
import com.fotoxplorr.app.media.MediaAsset
import com.fotoxplorr.app.metadata.DefaultSidecarService
import com.fotoxplorr.app.metadata.SafParentResolver
import com.fotoxplorr.app.metadata.SafSidecarDirectory
import com.fotoxplorr.app.metadata.SidecarAsset
import com.fotoxplorr.app.metadata.SidecarDirectory
import com.fotoxplorr.app.metadata.SidecarEntry
import com.fotoxplorr.app.metadata.SidecarNameMatch
import com.fotoxplorr.app.metadata.SidecarParent
import com.fotoxplorr.app.metadata.SidecarWriteResult
import com.fotoxplorr.core.formats.MediaFormat
import com.fotoxplorr.core.metadata.SidecarNaming
import com.fotoxplorr.core.metadata.SidecarPolicy

/*
 * Where a cull's XMP sidecars go: the sidecar layer's own route (SidecarLocator over a
 * SidecarDirectory, written through DefaultSidecarService), with the folder reached through a SAF
 * tree the user granted -- the one way scoped storage lets an app create a file beside a photo it
 * does not own. The photo's MediaStore VOLUME and relative path pick the tree, so a photo on an SD
 * card never reads or writes the primary volume's folder of the same name. No grant (or no volume
 * or path, as on API 26-28 where MediaStore has no RELATIVE_PATH) means SidecarParent.NoGrant, which
 * the service reports as NeedsFolderGrant and the cull shows as not saved. None of it has run on a
 * device.
 */

/** A persisted folder grant: the tree's uri, its document id (`volume:path`) and whether it may write. */
internal data class FolderGrant(val treeUri: String, val treeDocumentId: String, val write: Boolean)

/** The granted folder a photo's sidecar lives in, or null when no grant covers it. */
internal data class GrantedFolder(val grant: FolderGrant, val parentDocumentId: String)

/**
 * The grant that covers [relativePath] on [volume]: a writable grant before a read-only one, then
 * the narrowest tree. Null when the volume or path is unknown (never guessed as the primary volume)
 * or no grant covers it.
 */
internal fun grantedFolderFor(volume: String?, relativePath: String?, grants: List<FolderGrant>): GrantedFolder? {
    if (volume.isNullOrEmpty() || relativePath.isNullOrEmpty()) return null
    return grants
        .sortedWith(compareByDescending<FolderGrant> { it.write }.thenByDescending { it.treeDocumentId.length })
        .firstNotNullOfOrNull { grant ->
            SafParentResolver.parentDocumentId(grant.treeDocumentId, volume, relativePath)
                ?.let { GrantedFolder(grant, it) }
        }
}

/**
 * A folder listed ONCE: [find] answers a miss from the listing without touching the storage again,
 * and [mayHoldAny] says whether any of some names is there at all. A cull's first pass over a
 * 10,000-photo folder is then one listing, not 10,000. A file this app writes is added to the
 * listing; a file someone else adds during the cull is not seen until the cull is opened again.
 */
internal class ListedDirectory(private val inner: SidecarDirectory) : SidecarDirectory {
    private val lock = Any()
    private var listed = false
    private var names: List<String>? = null
    private var lowerNames: Set<String> = emptySet()

    override val uri: String get() = inner.uri
    override val canCreate: Boolean get() = inner.canCreate

    override fun listNames(): List<String>? = synchronized(lock) {
        if (!listed) {
            names = inner.listNames()
            lowerNames = names.orEmpty().mapTo(HashSet()) { it.lowercase() }
            listed = true
        }
        names
    }

    /** False only when the folder was listed and none of [fileNames] is in it (ignoring case). */
    fun mayHoldAny(fileNames: List<String>): Boolean {
        if (listNames() == null) return true
        val lower = synchronized(lock) { lowerNames }
        return fileNames.any { it.lowercase() in lower }
    }

    override fun find(fileName: String): SidecarEntry? {
        val all = listNames() ?: return inner.find(fileName)
        if (SidecarNameMatch.indexOf(all, fileName) == null) return null
        return inner.find(fileName)
    }

    override fun read(entry: SidecarEntry): ByteArray? = inner.read(entry)

    override fun write(fileName: String, existing: SidecarEntry?, bytes: ByteArray): Boolean {
        val ok = inner.write(fileName, existing, bytes)
        if (ok) {
            val written = existing?.fileName ?: fileName
            synchronized(lock) {
                val current = names
                if (listed && current != null && SidecarNameMatch.indexOf(current, written) == null) {
                    names = current + written
                    lowerNames = lowerNames + written.lowercase()
                }
            }
        }
        return ok
    }
}

/**
 * The cull's sidecar route over [assets]. [grants] and [volumes] are read lazily (both are IO) and
 * [directory] builds the folder for a granted parent; each folder is built and listed once.
 */
internal class CullSidecarRoute(
    assets: List<MediaAsset>,
    private val grants: () -> List<FolderGrant>,
    private val volumes: (Collection<Long>) -> Map<Long, String>,
    private val directory: (GrantedFolder) -> SidecarDirectory,
) {
    private val byId = assets.associateBy { it.id.value }
    private val lock = Any()
    private var grantList: List<FolderGrant>? = null
    private var volumeMap: Map<Long, String>? = null
    private val folders = HashMap<String, ListedDirectory?>()

    private fun grantList(): List<FolderGrant> = synchronized(lock) {
        grantList ?: runCatching(grants).getOrDefault(emptyList()).also { grantList = it }
    }

    private fun volumeOf(id: Long): String? = synchronized(lock) {
        (volumeMap ?: runCatching { volumes(byId.keys) }.getOrDefault(emptyMap()).also { volumeMap = it })[id]
    }

    private fun folderOf(asset: MediaAsset): ListedDirectory? {
        val volume = volumeOf(asset.id.value)
        val granted = grantedFolderFor(volume, asset.relativePath, grantList()) ?: return null
        val key = "${granted.grant.treeUri}|${granted.parentDocumentId}"
        return synchronized(lock) {
            folders.getOrPut(key) { runCatching { ListedDirectory(directory(granted)) }.getOrNull() }
        }
    }

    /** The sidecar layer's view of photo [id]; null for an id not in this cull. */
    fun resolve(id: Long): SidecarAsset? {
        val asset = byId[id] ?: return null
        val folder = folderOf(asset)
        return SidecarAsset(
            fileName = asset.displayName,
            source = SidecarPolicy.Source(isRaw = asset.format is MediaFormat.Raw, embedWritable = false),
            parent = if (folder != null) SidecarParent.Directory(folder) else SidecarParent.NoGrant,
        )
    }

    /** True when photo [id] certainly has no sidecar: its folder's one listing holds neither name. */
    fun absent(id: Long): Boolean {
        val asset = byId[id] ?: return true
        val folder = folderOf(asset) ?: return true
        return !folder.mayHoldAny(SidecarNaming.candidateSidecarFileNames(asset.displayName))
    }

    /** A folder was just granted: read the grants again and list the folders afresh. */
    fun refresh() {
        synchronized(lock) {
            grantList = null
            folders.clear()
        }
    }
}

/** What a cull writes to, and how it learns that it needs a folder grant. */
internal class CullSession(
    val store: WriteThroughAnnotationStore,
    private val sidecarStore: SidecarAnnotationStore?,
    private val route: CullSidecarRoute?,
) {
    /** The last write was refused for want of a folder grant (the UI offers the folder picker). */
    fun needsFolderGrant(): Boolean = sidecarStore?.lastWrite == SidecarWriteResult.NeedsFolderGrant

    /** After a grant: the folders are looked up again and the sidecars that were out of reach are read. */
    fun onFolderGranted() {
        route?.refresh()
        store.rereadMisses()
    }
}

/** A durable side that keeps nothing: culling with sidecars off is memory only, and says so. */
private object NoDurableStore : AnnotationStore {
    override fun get(id: Long): com.fotoxplorr.core.metadata.SidecarAnnotations? = null
    override fun put(id: Long, annotations: com.fotoxplorr.core.metadata.SidecarAnnotations): Boolean = false
}

/** The cull's store ([contentResolver] is only asked for with [sidecars] on): memory always, plus the photo's XMP sidecar through a granted folder when [sidecars] is on. */
internal fun cullSession(
    assets: List<MediaAsset>,
    sidecars: Boolean,
    contentResolver: () -> ContentResolver,
): CullSession {
    if (!sidecars) return CullSession(WriteThroughAnnotationStore(NoDurableStore), null, null)
    val resolver = contentResolver()
    val route = CullSidecarRoute(
        assets = assets,
        grants = { persistedFolderGrants(resolver) },
        volumes = { ids -> mediaStoreVolumes(resolver, ids) },
        directory = { folder ->
            SafSidecarDirectory(resolver, Uri.parse(folder.grant.treeUri), folder.parentDocumentId, folder.grant.write)
        },
    )
    val service = DefaultSidecarService(policy = { SidecarPolicy(writeEnabled = true) })
    val sidecarStore = SidecarAnnotationStore(service, absent = route::absent, resolve = route::resolve)
    return CullSession(WriteThroughAnnotationStore(sidecarStore), sidecarStore, route)
}

/** The folder trees this app holds a persisted grant on. */
private fun persistedFolderGrants(resolver: ContentResolver): List<FolderGrant> =
    resolver.persistedUriPermissions
        .filter { it.isReadPermission && DocumentsContract.isTreeUri(it.uri) }
        .mapNotNull { permission ->
            runCatching { DocumentsContract.getTreeDocumentId(permission.uri) }.getOrNull()
                ?.let { FolderGrant(permission.uri.toString(), it, permission.isWritePermission) }
        }

/** Takes the grant the folder picker returned, read and write, so it outlives the process. */
internal fun keepFolderGrant(resolver: ContentResolver, tree: Uri): Boolean = runCatching {
    resolver.takePersistableUriPermission(tree, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
}.isSuccess

/**
 * Each photo's MediaStore volume, in batches of ids. API 29+ only: before that MediaStore has no
 * volume (nor relative path) column, and the photo stays without a sidecar route.
 */
private fun mediaStoreVolumes(resolver: ContentResolver, ids: Collection<Long>): Map<Long, String> =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) queryVolumes(resolver, ids) else emptyMap()

@RequiresApi(Build.VERSION_CODES.Q)
private fun queryVolumes(resolver: ContentResolver, ids: Collection<Long>): Map<Long, String> {
    val out = HashMap<Long, String>(ids.size * 2)
    val uri = MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL)
    val projection = arrayOf(MediaStore.MediaColumns._ID, MediaStore.MediaColumns.VOLUME_NAME)
    ids.chunked(VOLUME_BATCH).forEach { chunk ->
        val selection = "${MediaStore.MediaColumns._ID} IN (${chunk.joinToString(",")})"
        runCatching {
            resolver.query(uri, projection, selection, null, null)?.use { c ->
                while (c.moveToNext()) c.getString(1)?.let { out[c.getLong(0)] = it }
            }
        }
    }
    return out
}

private const val VOLUME_BATCH = 500
