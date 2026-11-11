package com.fotoxplorr.app.sources.saf

import android.content.ContentResolver
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.DocumentsContract

/** One persisted tree grant this app holds. */
data class SafGrant(
    val treeUri: String,
    val rootDocumentId: String,
    val canRead: Boolean,
    val canWrite: Boolean,
    val persistedTimeMillis: Long,
)

/** Pure arithmetic of the platform's cap on persisted grants. The pool is shared with backup, sync, sidecars and the cull route. */
object SafGrantPool {
    /** Documented as 128 before Android 11 and 512 from it; not verified on a device. */
    fun limitFor(sdkInt: Int): Int = if (sdkInt >= Build.VERSION_CODES.R) 512 else 128

    /** Grants kept free for the other features that share the pool. */
    const val RESERVE: Int = 8

    /** A new folder source is refused once the pool is within [RESERVE] of the cap. */
    fun mayTakeAnother(held: Int, limit: Int): Boolean = held < limit - RESERVE
}

sealed interface GrantOutcome {
    data class Taken(val held: Int, val limit: Int) : GrantOutcome
    data class Refused(val reason: String) : GrantOutcome
}

/**
 * The persisted-grant side of a folder source. The only things it can do to a grant are take one
 * and forget one: it never touches a document, so a revoked or unreachable grant can only be
 * reported ([isHeld], [SafAccess.healthAfter]), never "cleaned up" by deleting what was indexed.
 * Not run on a device.
 *
 * It reads the read-only grant of a photo folder (`FLAG_GRANT_READ_URI_PERMISSION`) and keeps any
 * write flag another feature already holds. The existing helpers (`SyncAndroid.takeFolderGrant`,
 * `keepFolderGrant`) take read and write together and fail when the picker offered only read,
 * which is why this does its own call; the listing of grants follows `persistedFolderGrants`.
 */
class SafGrantRegistry(
    private val resolver: ContentResolver,
    private val sdkInt: Int = Build.VERSION.SDK_INT,
) {
    /** Every persisted tree grant the app holds, whichever feature took it. */
    fun list(): List<SafGrant> = resolver.persistedUriPermissions
        .filter { DocumentsContract.isTreeUri(it.uri) }
        .mapNotNull { p ->
            runCatching { DocumentsContract.getTreeDocumentId(p.uri) }.getOrNull()?.let {
                SafGrant(p.uri.toString(), it, p.isReadPermission, p.isWritePermission, p.persistedTime)
            }
        }

    fun count(): Int = list().size

    val limit: Int get() = SafGrantPool.limitFor(sdkInt)

    /** True while the platform still lets this app read [treeUri]. A tree that was never granted or was revoked is false. */
    fun isHeld(treeUri: Uri): Boolean = isHeld(treeUri.toString())

    fun isHeld(treeUri: String): Boolean = list().any { it.treeUri == treeUri && it.canRead }

    /** Keeps the grant the folder picker just returned, refusing with a plain sentence near the cap. */
    fun take(treeUri: Uri): GrantOutcome {
        val held = count()
        if (!isHeld(treeUri) && !SafGrantPool.mayTakeAnother(held, limit)) return GrantOutcome.Refused(SafCopy.NEAR_GRANT_LIMIT)
        val ok = runCatching {
            resolver.takePersistableUriPermission(treeUri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }.isSuccess
        return if (ok) GrantOutcome.Taken(count(), limit) else GrantOutcome.Refused(SafCopy.GRANT_REFUSED)
    }

    /**
     * Gives the platform its slot back. Call it only when no other feature (backup, sync, sidecars)
     * uses the same tree: the grant is shared. Removing a source forgets access and nothing else.
     */
    fun forget(treeUri: Uri): Boolean = runCatching {
        resolver.releasePersistableUriPermission(treeUri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }.isSuccess
}
