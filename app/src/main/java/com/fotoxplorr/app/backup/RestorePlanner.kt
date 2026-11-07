package com.fotoxplorr.app.backup

import com.fotoxplorr.app.media.MediaKind
import com.fotoxplorr.app.media.mediaStoreRelativePath
import java.util.Locale

/**
 * A file already on the phone, as the restore planner needs it. [dateTakenMillis] and
 * [dateModifiedSeconds] are not used to plan; the id remap matches photos by them
 * ([MediaIdentity]) and a restored edit recipe is stored against the file's revision.
 */
data class RestoreLocalFile(
    val mediaId: Long,
    val displayName: String,
    val relativePath: String?,
    val sizeBytes: Long,
    val dateTakenMillis: Long = 0L,
    val dateModifiedSeconds: Long = 0L,
) {
    val identity: MediaIdentity get() = MediaIdentity(displayName, sizeBytes, dateTakenMillis, relativePath?.takeIf { it.isNotEmpty() })
}

enum class RestoreAction {
    /** Written under its own name in its own folder. */
    RESTORE,

    /** Something else already has that name there: written as `name (restored).ext`. */
    RESTORE_AS_COPY,

    /** The same bytes are already on the phone ([RestoreItem.matchedMediaId]); nothing is written. */
    SKIP_ALREADY_HERE,

    /** Not an image or a video; a restore writes only those. */
    SKIP_UNSUPPORTED,

    /**
     * A photo from a protected folder that this Android version cannot be told to put in a folder
     * of its own (before Android 10), so it could not be kept behind its lock. Left out, not
     * written somewhere it would be visible ([ProtectedPlacement]).
     */
    SKIP_NEEDS_FOLDER,

    /** The backup knows this photo but the pack holding it was not in the folder ([EntryFlag.MISSING]). */
    SKIP_MISSING,
}

class RestoreItem(
    val entry: ManifestEntry,
    val action: RestoreAction,
    val kind: MediaKind?,
    /** A sanitised MediaStore RELATIVE_PATH ("DCIM/Camera/"); empty for a skipped item. */
    val targetRelativePath: String,
    val targetDisplayName: String,
    val matchedMediaId: Long?,
)

class RestorePlan(val items: List<RestoreItem>) {
    val toWrite: List<RestoreItem> get() = items.filter { it.action == RestoreAction.RESTORE || it.action == RestoreAction.RESTORE_AS_COPY }
    val bytesToWrite: Long get() = toWrite.sumOf { it.entry.sizeBytes }
    fun count(action: RestoreAction): Int = items.count { it.action == action }

    /** Old MediaStore id to the id of the identical file already on the phone, for [IdRemap]. */
    fun alreadyHereIdMap(): Map<Long, Long> =
        items.filter { it.action == RestoreAction.SKIP_ALREADY_HERE && it.matchedMediaId != null }
            .associate { it.entry.mediaId to it.matchedMediaId!! }
}

/**
 * Plans a restore, and never overwrites anything. A file counts as already here when a local
 * file has the same size and SHA-256 and a name the restore itself could have given it (the
 * original name, or `name (restored).ext`, `name (restored 2).ext`): that is what makes a second
 * run write nothing, including for files the first run had to rename. Anything else that already
 * has the target name in the target folder (compared ignoring case, as the phone's storage does)
 * makes the new file a `(restored)` copy beside it.
 *
 * The manifest is untrusted input: names and folders are sanitised ([sanitiseRelativePath],
 * [sanitiseDisplayName]) so no entry can climb out of the media folders or smuggle in a path
 * separator, and only image and video types are written.
 *
 * Two phases because hashing is the expensive part: [candidatesToHash] says which local files
 * could be a match, the caller hashes just those, and [plan] takes the result. A local file
 * without a hash is treated as different, which can only produce a copy, never an overwrite.
 */
object RestorePlanner {
    const val MAX_NAME_BYTES = 255
    private const val MAX_PATH_SEGMENTS = 32
    private const val FALLBACK_NAME = "restored"
    private val illegal = "\\:*?\"<>|".toSet()

    fun candidatesToHash(entries: Collection<ManifestEntry>, local: Collection<RestoreLocalFile>): List<RestoreLocalFile> {
        val bySize = HashMap<Long, MutableList<ManifestEntry>>()
        entries.forEach { bySize.getOrPut(it.sizeBytes) { ArrayList() } += it }
        return local.filter { file ->
            bySize[file.sizeBytes]?.any { isRestoredNameOf(sanitiseDisplayName(it.displayName, it.entryId), file.displayName) } == true
        }
    }

    fun plan(
        entries: Collection<ManifestEntry>,
        local: Collection<RestoreLocalFile>,
        localSha256: Map<Long, String>,
    ): RestorePlan {
        val taken = HashSet<String>()
        local.forEach { taken += nameKey(normaliseDir(it.relativePath), it.displayName) }
        val hashed = HashMap<Pair<Long, String>, MutableList<RestoreLocalFile>>()
        for (file in local) {
            val sha = localSha256[file.mediaId] ?: continue
            hashed.getOrPut(file.sizeBytes to sha) { ArrayList() } += file
        }

        val items = ArrayList<RestoreItem>(entries.size)
        for (entry in entries.sortedBy { it.entryId }) {
            if (EntryFlag.MISSING in entry.flags) {
                items += RestoreItem(entry, RestoreAction.SKIP_MISSING, kindOf(entry.mimeType), "", "", null)
                continue
            }
            val kind = kindOf(entry.mimeType)
            if (kind == null) {
                items += RestoreItem(entry, RestoreAction.SKIP_UNSUPPORTED, null, "", "", null)
                continue
            }
            val name = sanitiseDisplayName(entry.displayName, entry.entryId)
            val dir = mediaStoreRelativePath(sanitiseRelativePath(entry.relativePath).ifEmpty { null }, kind)

            val here = hashed[entry.sizeBytes to entry.sha256]?.firstOrNull { isRestoredNameOf(name, it.displayName) }
            if (here != null) {
                items += RestoreItem(entry, RestoreAction.SKIP_ALREADY_HERE, kind, "", "", here.mediaId)
                continue
            }

            var target = name
            var action = RestoreAction.RESTORE
            var attempt = 0
            while (!taken.add(nameKey(dir, target))) {
                attempt++
                action = RestoreAction.RESTORE_AS_COPY
                target = restoredName(name, attempt)
            }
            items += RestoreItem(entry, action, kind, dir, target, null)
        }
        return RestorePlan(items)
    }

    /** `name.ext` becomes `name (restored).ext`, then `name (restored 2).ext`, and so on. */
    fun restoredName(name: String, attempt: Int): String {
        require(attempt >= 1) { "Attempts start at 1" }
        val (base, ext) = splitExtension(name)
        val suffix = if (attempt == 1) " (restored)" else " (restored $attempt)"
        return truncateUtf8(base, MAX_NAME_BYTES - utf8Length(ext) - utf8Length(suffix)) + suffix + ext
    }

    /** True if [local] is [name] itself or a `(restored)` rename of it (case-insensitive). */
    fun isRestoredNameOf(name: String, local: String): Boolean {
        if (name.equals(local, ignoreCase = true)) return true
        val (base, ext) = splitExtension(name)
        val (localBase, localExt) = splitExtension(local)
        if (!ext.equals(localExt, ignoreCase = true)) return false
        val match = restoredSuffix.matchEntire(localBase) ?: return false
        val suffix = " (restored${match.groupValues[2]})"
        // restoredName shortens the base when the whole name would pass 255 bytes.
        val expected = truncateUtf8(base, MAX_NAME_BYTES - utf8Length(ext) - utf8Length(suffix))
        return match.groupValues[1].equals(expected, ignoreCase = true)
    }

    private val restoredSuffix = Regex("^(.+) \\(restored( [0-9]{1,6})?\\)$")

    /**
     * Image or video, and a type that looks like one (`type/subtype`, no spaces or control
     * characters): the manifest is untrusted and the type goes straight into a MediaStore insert.
     */
    fun kindOf(mimeType: String): MediaKind? = when {
        !safeMime.matches(mimeType) -> null
        mimeType.startsWith("image/", ignoreCase = true) -> MediaKind.IMAGE
        mimeType.startsWith("video/", ignoreCase = true) -> MediaKind.VIDEO
        else -> null
    }

    private val safeMime = Regex("^[A-Za-z0-9]{1,32}/[A-Za-z0-9][A-Za-z0-9.+_-]{0,95}$")

    /**
     * A MediaStore RELATIVE_PATH made safe: forward slashes only, no empty, `.` or `..` segments
     * (they are dropped, not followed), no leading slash (an absolute path becomes relative), no
     * characters the media folders reject, at most 32 segments, a trailing slash. An empty result
     * means "no usable folder"; the caller then falls back to the app's own folder.
     */
    fun sanitiseRelativePath(raw: String): String {
        val segments = raw.replace('\\', '/').split('/')
            .map { sanitiseSegment(it) }
            .filter { it.isNotEmpty() }
            .take(MAX_PATH_SEGMENTS)
        return if (segments.isEmpty()) "" else segments.joinToString("/", postfix = "/")
    }

    /** A file name: the part after the last separator, with the same character rules, never empty. */
    fun sanitiseDisplayName(raw: String, entryId: Long): String {
        val last = raw.substringAfterLast('/').substringAfterLast('\\')
        val clean = sanitiseSegment(last)
        return clean.ifEmpty { "$FALLBACK_NAME-$entryId" }
    }

    private fun sanitiseSegment(raw: String): String {
        val replaced = buildString {
            raw.forEach { c ->
                when {
                    c.code < 0x20 || c.code == 0x7F -> append('_')
                    c in illegal -> append('_')
                    Character.getType(c) == Character.FORMAT.toInt() -> append('_') // bidi overrides and the like
                    else -> append(c)
                }
            }
        }.trim().trimEnd('.', ' ')
        if (replaced.isEmpty()) return ""
        // A leading dot would hide the file from the media scanner.
        val unhidden = if (replaced.startsWith(".")) "_" + replaced.trimStart('.') else replaced
        // Keep the extension: a name cut in the middle of it would lose the type.
        val (base, ext) = splitExtension(unhidden)
        return truncateUtf8(base, MAX_NAME_BYTES - utf8Length(ext)) + ext
    }

    private fun splitExtension(name: String): Pair<String, String> {
        val dot = name.lastIndexOf('.')
        return if (dot > 0 && dot < name.length - 1 && name.length - dot <= 12) name.substring(0, dot) to name.substring(dot) else name to ""
    }

    private fun normaliseDir(relativePath: String?): String {
        val trimmed = relativePath.orEmpty().replace('\\', '/').trimStart('/')
        return if (trimmed.isEmpty() || trimmed.endsWith("/")) trimmed else "$trimmed/"
    }

    private fun nameKey(dir: String, name: String): String = (dir + name).lowercase(Locale.ROOT)

    private fun utf8Length(text: String): Int = text.toByteArray(Charsets.UTF_8).size

    private fun truncateUtf8(text: String, maxBytes: Int): String {
        if (maxBytes <= 0) return ""
        var bytes = 0
        var end = 0
        while (end < text.length) {
            val cp = text.codePointAt(end)
            val size = when {
                cp < 0x80 -> 1
                cp < 0x800 -> 2
                cp < 0x10000 -> 3
                else -> 4
            }
            if (bytes + size > maxBytes) break
            bytes += size
            end += Character.charCount(cp)
        }
        return text.substring(0, end)
    }
}
