@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class, kotlin.experimental.ExperimentalNativeApi::class)

package com.fotoxplorr.ut.bridge

import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import com.fotoxplorr.core.db.FotozDatabase
import com.fotoxplorr.core.db.FotozDatabaseConstructor
import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.COpaquePointer
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.StableRef
import kotlinx.cinterop.asStableRef
import kotlinx.coroutines.Dispatchers
import kotlin.native.CName

/**
 * What [fotoz_library_open] hands back to C as an opaque pointer -- never a Kotlin object graph
 * crossing the boundary directly (ADR-013 point 2). Holds exactly what every other `@CName`
 * function in this module needs: the real, open [FotozDatabase] (ADR-011's schema, WP1.3/1.5's
 * DAOs) this bridge is honestly built on.
 */
internal class LibraryHandle(val db: FotozDatabase) {
    fun close() = db.close()
}

/** Recovers the [LibraryHandle] a [COpaquePointer] from [fotoz_library_open] wraps, or `null` for
 *  a null/foreign pointer -- every other `@CName` function in this module goes through this
 *  rather than an unchecked `asStableRef<LibraryHandle>().get()`, so a null/stale handle is a
 *  documented [BridgeStatus.INVALID_HANDLE] everywhere, never a crash. */
internal fun COpaquePointer?.asLibraryHandleOrNull(): LibraryHandle? {
    if (this == null) return null
    return try {
        asStableRef<LibraryHandle>().get()
    } catch (t: Throwable) {
        null
    }
}

/**
 * Opens (or creates) `fotoz.db` at [path] -- ADR-013 point 2: "Room KMP with the bundled SQLite
 * driver gives the same `fotoz.db` on every platform." [path] is the full path to the database
 * FILE (e.g. `/home/alice/.local/share/fotoxplorr/fotoz.db`), not a directory: resolving the
 * right XDG data directory and creating it if missing is the future `:ut:app` Qt shell's job
 * (ADR-013 point 5's `LINUX_DIR`-adjacent concern), not this module's -- SQLite/Room will fail to
 * open a path whose parent directory does not already exist, and that failure is reported here
 * as `null`, not silently papered over by creating directories a caller may not have wanted
 * created.
 *
 * Real, not stubbed: this is exactly [com.fotoxplorr.core.db.FotozDatabase]'s own KSP-generated
 * `FotozDatabaseConstructor` (proven native-ready by `:core:db`'s own `compileKotlinLinuxX64`/
 * `compileKotlinLinuxArm64`, MASTER-PROGRESS.md's module-readiness table), the identical
 * construction `app/.../LibraryRuntime.kt` uses for Android and `:core:db`'s own `desktopTest`
 * uses for the JVM -- only the entry point (`Room.databaseBuilder(name, factory)`, the
 * `jvmNative`-shared overload with no `Context`) differs, because there is no `Context` on Linux.
 *
 * @return an opaque, non-null handle on success; `null` if [path] is null, or if Room/SQLite
 * failed to open it (a missing parent directory, an unwritable path, a corrupt file it could not
 * even validate). This is this module's one deliberate exception to "null is only for a
 * structural failure" (see `BridgeJson.kt`'s KDoc) -- there is no open [FotozDatabase] yet to
 * report a JSON error through, so a boolean-shaped null is the only honest signal available.
 */
@CName("fotoz_library_open")
fun fotoz_library_open(path: CPointer<ByteVar>?): COpaquePointer? {
    val dbPath = path.readUtf8OrNull() ?: return null
    val db = try {
        Room.databaseBuilder<FotozDatabase>(
            name = dbPath,
            factory = { FotozDatabaseConstructor.initialize() },
        )
            .setDriver(BundledSQLiteDriver())
            // Dispatchers.IO is `internal` on Kotlin/Native's own actual (unlike the JVM, where
            // :core:db's own desktopTest/jvmTest and app/.../LibraryRuntime.kt all use it) --
            // Dispatchers.Default is the real, public equivalent available here.
            .setQueryCoroutineContext(Dispatchers.Default)
            .build()
    } catch (t: Throwable) {
        return null
    }
    return try {
        // Room's own `.build()` does NOT open the underlying SQLite connection eagerly -- it is
        // only opened lazily, on first real access. Confirmed by this module's own `linuxTest`:
        // without this, `fotoz_library_open` returned a non-null "handle" even for a path whose
        // parent directory does not exist, contradicting this function's own documented contract
        // ("null... if Room/SQLite failed to open it"). A cheap, real query forces that open now,
        // so a bad path is reported here, at open time, not on some arbitrary later call.
        runBridge { db.assetDao().count() }
        StableRef.create(LibraryHandle(db)).asCPointer()
    } catch (t: Throwable) {
        try {
            db.close()
        } catch (closeError: Throwable) {
            // Already failing to open; a failure to close too is not a new, separately
            // reportable condition here.
        }
        null
    }
}

/** Closes the database [handle] refers to and releases the native reference. Safe to call with
 *  `null`; safe to call at most once per handle (a second call is a caller bug -- the same
 *  contract as `FotozDatabase.close()` itself). */
@CName("fotoz_library_close")
fun fotoz_library_close(handle: COpaquePointer?) {
    if (handle == null) return
    try {
        val ref = handle.asStableRef<LibraryHandle>()
        ref.get().close()
        ref.dispose()
    } catch (t: Throwable) {
        // Closing must never throw across the C boundary (BridgeStatus.kt's rule) -- a
        // double-close or a foreign pointer here is a caller bug this function reports by simply
        // doing nothing further, not by crashing the host process.
    }
}
