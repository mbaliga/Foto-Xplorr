package com.fotoxplorr.app.backup

import com.fotoxplorr.app.gallery.folderIdentity
import com.fotoxplorr.app.media.mediaStoreRelativePath

/**
 * Decides where the photos that were behind a folder lock go back to, so that a restore never
 * leaves one visible. A lock belongs to a FOLDER (`path:dcim/private`), not to a photo; a photo is
 * protected because of where it sits. A restore that put it somewhere else would quietly unlock it.
 *
 * The place a restored file lands is not always the place it came from: MediaStore only accepts
 * `DCIM` and `Pictures` for images (and `Movies` for video), so a photo from `Download/Private/`
 * would go to the app's fallback folder with everything else ([mediaStoreRelativePath]). For every
 * entry flagged [EntryFlag.WAS_PROTECTED] this works out where it would go:
 *
 *  - the same folder it came from: the lock record for that folder is applied and that is all (when
 *    the phone has photos in that folder and no lock on it, this is not done: see [arrange]);
 *  - anywhere else: the photo is given a folder of its own under [RELOCATED_ROOT] that keeps the
 *    old path (`Pictures/Fotoz/Restored protected/Download/Private/`), and the SAME lock (same salt
 *    and hash, so the same password) is applied to that folder. Nothing that was not protected is
 *    swept behind a lock, because no other photo lives there;
 *  - before Android 10 the folder cannot be chosen at all, so the photo is left out and counted.
 *
 * A photo whose folder has no lock record in the backup is restored as it is and counted in
 * [Arranged.withoutLock]: there is nothing to put back. Applying a lock never replaces a lock the
 * phone already has ([com.fotoxplorr.app.privacy.PrivateFolderStore.restoreLocks]).
 *
 * Pure: entries in, entries and locks out.
 */
object ProtectedPlacement {
    const val RELOCATED_ROOT = "Pictures/Fotoz/Restored protected/"

    class Arranged(
        /** The entries to plan, with a protected photo's folder changed where it had to be. */
        val entries: List<ManifestEntry>,
        /** Protected photos left out because the folder cannot be chosen on this Android version. */
        val heldBack: List<ManifestEntry>,
        /**
         * The locks that go with the folders protected photos may land in, one per folder. Which of
         * them are applied is decided once the plan says what is really written there.
         */
        val locks: List<BackupSections.Lock>,
        /** Protected photos with no lock record in the backup. */
        val withoutLock: Int,
        /** Protected photos that go to a folder of their own instead of the one they came from. */
        val relocated: Int,
    )

    /** The key the library uses for a folder, from its MediaStore relative path. */
    fun lockKey(relativePath: String?): String = folderIdentity(bucketName = null, relativePath = relativePath).key.value

    /**
     * @param lockedHere the folder keys this phone already has a lock on, and [inUse] the keys of
     *   every folder it has photos in. A folder with photos in it that is NOT locked here is never
     *   locked by a restore (the person may have taken its protection off on purpose): the protected
     *   photos go to a folder of their own instead.
     */
    fun arrange(
        entries: List<ManifestEntry>,
        locks: List<BackupSections.Lock>,
        canChooseFolder: Boolean,
        lockedHere: Set<String> = emptySet(),
        inUse: Set<String> = emptySet(),
    ): Arranged {
        val byKey = locks.associateBy { it.folderKey }
        val out = ArrayList<ManifestEntry>(entries.size)
        val held = ArrayList<ManifestEntry>()
        val toApply = LinkedHashMap<String, BackupSections.Lock>()
        var withoutLock = 0
        var relocated = 0
        for (entry in entries) {
            if (EntryFlag.WAS_PROTECTED !in entry.flags) {
                out += entry
                continue
            }
            val kind = RestorePlanner.kindOf(entry.mimeType)
            val original = lockKey(entry.relativePath)
            val lock = byKey[original]
            if (kind == null || EntryFlag.MISSING in entry.flags) {
                // Never written, so never visible; the planner reports it as it would any other.
                out += entry
                continue
            }
            if (lock == null) {
                withoutLock++
                out += entry
                continue
            }
            if (!canChooseFolder) {
                held += entry
                continue
            }
            val clean = RestorePlanner.sanitiseRelativePath(entry.relativePath)
            val natural = mediaStoreRelativePath(clean.ifEmpty { null }, kind)
            if (lockKey(natural) == original && (original in lockedHere || original !in inUse)) {
                toApply.putIfAbsent(original, lock)
                out += entry
            } else {
                // Sanitised once more as a whole: the planner does, and the key must be of what it will use.
                val own = RestorePlanner.sanitiseRelativePath(RELOCATED_ROOT + clean)
                val ownKey = lockKey(own)
                toApply.putIfAbsent(ownKey, BackupSections.Lock(ownKey, lock.saltBase64, lock.hashBase64))
                out += entry.copy(relativePath = own)
                relocated++
            }
        }
        return Arranged(out, held, toApply.values.toList(), withoutLock, relocated)
    }
}
