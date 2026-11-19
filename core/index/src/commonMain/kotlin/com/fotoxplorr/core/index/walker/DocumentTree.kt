package com.fotoxplorr.core.index.walker

/**
 * The `DocumentsContract.Document.FLAG_*` bits the walker reads, copied as plain numbers so the
 * walker stays platform-free (the Android lister passes the provider's flags through unchanged).
 */
object DocumentFlags {
    const val SUPPORTS_THUMBNAIL: Int = 1
    const val VIRTUAL_DOCUMENT: Int = 1 shl 9
}

/**
 * One child of a directory as the provider listed it: the full projection of ONE query (document
 * id, display name, mime type, size, last modified, flags). [sizeBytes] is -1 and
 * [lastModifiedMillis] is 0 when the provider did not say.
 */
data class DocumentEntry(
    val documentId: String,
    val name: String,
    val mimeType: String,
    val isDirectory: Boolean,
    val sizeBytes: Long = -1L,
    val lastModifiedMillis: Long = 0L,
    val flags: Int = 0,
) {
    /** A document the provider makes up on request (a cloud app's file): reading it may download it. */
    val isVirtual: Boolean get() = flags and DocumentFlags.VIRTUAL_DOCUMENT != 0
}

/** Why a read of the tree failed. The walker maps all of them to a Failed status; the caller decides what each means. */
enum class TreeFailure {
    /** The grant was taken away (by the person or the system). Never "provider unavailable". */
    REVOKED,

    /** The storage is not there right now (a drive that is not plugged in). Not an error and not a revoke. */
    UNREACHABLE,

    /** The provider said it could not answer (EXTRA_ERROR, an exception, a null cursor). */
    ERROR,
}

class TreeAccessException(val failure: TreeFailure, message: String) : Exception(message)

/**
 * What the walker needs from a folder tree. Implemented over `DocumentsContract` on Android and by
 * an in-memory fake in tests. Blocking calls: the walker runs on a worker thread.
 */
interface DocumentTree {
    /** The document id of the directory the walk starts from. */
    val rootDocumentId: String

    /**
     * Every child of [directoryId] from ONE query. Must THROW [TreeAccessException] when the
     * provider reports an error or cannot answer, never return an empty list for a failure: an
     * empty folder and an unreadable one lead to opposite decisions (TRAPS #8).
     */
    fun children(directoryId: String): List<DocumentEntry>

    /**
     * At most [maxBytes] leading bytes of the file, fewer for a shorter file; null when the file
     * cannot be read. Never reads the whole file.
     */
    fun readHead(documentId: String, maxBytes: Int): ByteArray?
}
