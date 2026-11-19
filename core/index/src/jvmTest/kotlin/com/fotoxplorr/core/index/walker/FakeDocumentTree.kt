package com.fotoxplorr.core.index.walker

/** An in-memory tree: directory id to children. [onChildren] runs before each query (to inject failures). */
class FakeDocumentTree(override val rootDocumentId: String = "root") : DocumentTree {
    val dirs = HashMap<String, MutableList<DocumentEntry>>()
    val heads = HashMap<String, ByteArray>()
    var onChildren: (String) -> Unit = {}
    var childrenCalls = 0
    val queried = ArrayList<String>()
    var headReads = 0

    init { dirs[rootDocumentId] = ArrayList() }

    fun dir(parent: String, id: String, name: String = id): String {
        dirs.getOrPut(parent) { ArrayList() } += DocumentEntry(id, name, "vnd.android.document/directory", true)
        dirs.getOrPut(id) { ArrayList() }
        return id
    }

    fun file(parent: String, id: String, name: String, mime: String = "image/jpeg", size: Long = 1000, flags: Int = 0, head: ByteArray? = null) {
        dirs.getOrPut(parent) { ArrayList() } += DocumentEntry(id, name, mime, false, size, 1_700_000_000_000L, flags)
        if (head != null) heads[id] = head
    }

    override fun children(directoryId: String): List<DocumentEntry> {
        childrenCalls++
        queried += directoryId
        onChildren(directoryId)
        return dirs[directoryId] ?: throw TreeAccessException(TreeFailure.ERROR, "no such directory")
    }

    override fun readHead(documentId: String, maxBytes: Int): ByteArray? {
        headReads++
        return heads[documentId]?.let { if (it.size > maxBytes) it.copyOf(maxBytes) else it }
    }

    companion object {
        /** [top] x [sub] x [perDir] files in nested directories (top/sub), 2 levels deep. */
        fun big(top: Int, sub: Int, perDir: Int): FakeDocumentTree {
            val t = FakeDocumentTree()
            for (a in 0 until top) {
                val da = t.dir("root", "a$a")
                for (b in 0 until sub) {
                    val db = t.dir(da, "a$a/b$b", "b$b")
                    for (f in 0 until perDir) t.file(db, "a$a/b$b/f$f", "IMG_${a}_${b}_$f.jpg")
                }
            }
            return t
        }
    }
}

val PNG_HEAD = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)
val JPEG_HEAD = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte(), 0, 0x10)
val RAF_HEAD = "FUJIFILMCCD-RAW 0201".encodeToByteArray()
val TIFF_HEAD = byteArrayOf(0x49, 0x49, 0x2A, 0x00, 8, 0, 0, 0)
