@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.fotoxplorr.ut.bridge

import com.fotoxplorr.core.index.DirEntry
import com.fotoxplorr.core.index.DirectoryLister
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.pointed
import kotlinx.cinterop.ptr
import kotlinx.cinterop.toKString
import platform.posix.S_IFDIR
import platform.posix.S_IFLNK
import platform.posix.S_IFMT
import platform.posix.closedir
import platform.posix.dirent
import platform.posix.lstat
import platform.posix.opendir
import platform.posix.readdir
import platform.posix.stat

/**
 * `:core:index`'s [DirectoryLister] over `platform.posix` (`opendir`/`readdir`/`lstat`). `lstat`,
 * not `stat`: a symbolic link is reported as a link and never followed, which is what lets
 * `LocalDirSource` skip links and so never loop or escape the chosen root. An entry whose `lstat`
 * fails (raced away, permission) is silently left out of the listing, not reported as unreadable:
 * only a directory that cannot be *opened* is `null`.
 */
internal object PosixDirectoryLister : DirectoryLister {
    override fun list(path: String): List<DirEntry>? {
        val dir = opendir(path) ?: return null
        val out = ArrayList<DirEntry>()
        try {
            while (true) {
                val ent = readdir(dir) ?: break
                val name = ent.pointed_name()
                if (name == "." || name == "..") continue
                val child = if (path == "/") "/$name" else "$path/$name"
                memScoped {
                    val st = alloc<stat>()
                    if (lstat(child, st.ptr) != 0) return@memScoped
                    val fmt = st.st_mode.toInt() and S_IFMT
                    out += DirEntry(
                        name = name,
                        isDirectory = fmt == S_IFDIR,
                        isSymlink = fmt == S_IFLNK,
                        sizeBytes = st.st_size,
                        mtimeMs = st.st_mtim.tv_sec * 1000L + st.st_mtim.tv_nsec / 1_000_000L,
                    )
                }
            }
        } finally {
            closedir(dir)
        }
        return out
    }

    private fun CPointer<dirent>.pointed_name(): String = pointed.d_name.toKString()
}
