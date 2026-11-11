@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class, kotlin.experimental.ExperimentalNativeApi::class)

package com.fotoxplorr.ut.bridge

import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.CFunction
import kotlinx.cinterop.COpaquePointer
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.invoke
import kotlin.native.CName

/**
 * MASTER-PLAN.md's own C API line names `sync_start(source, callback)`. Checked before writing
 * this, per WP8.1's brief: Phase 1's real sync engine (`:core:index`'s `Source`/`SyncEngine`,
 * WP1.4, "done (core scope)") is pure Kotlin and IS native-ready (`compileKotlinLinuxX64`/
 * `compileKotlinLinuxArm64` green, per MASTER-PROGRESS.md's module-readiness table) -- but its
 * only real [com.fotoxplorr.core.index.Source] implementation,
 * `com.fotoxplorr.app.index.AndroidMediaStoreSource`, lives in `:app` and wires directly to
 * `android.provider.MediaStore`. There is no Linux directory (or any other) [Source]
 * implementation anywhere in this codebase -- ADR-013 point 5's `LINUX_DIR` source is real
 * *schema* (`SourceKind` already lists it in ADR-011 section 2), but zero lines of the source
 * itself exist. Porting or writing one is real, separate, non-trivial work this WP does not
 * attempt (see this WP's own brief: "rather than porting Android-specific sync logic under time
 * pressure").
 *
 * So this is a deliberate, honest stub: it validates [handle] and, if [callback] is non-null,
 * invokes it exactly once with [BridgeStatus.NOT_IMPLEMENTED] and a JSON reason -- proving the
 * C-function-pointer callback ABI itself actually works (this module's own `linuxTest` calls this
 * with a real `staticCFunction`-built callback and asserts it fires with that exact code), which
 * is real, useful groundwork even though sync itself is not.
 *
 * [sourceJson] is read (so a malformed/missing value is still a documented [BridgeStatus.BAD_REQUEST]
 * before the not-implemented path, not skipped over) but not otherwise interpreted -- there is no
 * source kind on this platform yet for it to describe.
 *
 * Ownership note for [callback]'s own message pointer, since it differs from every other string
 * this module returns: it is valid ONLY for the duration of the callback invocation -- this
 * function frees it itself immediately afterward, on the same call stack, before returning.
 * [callback] must copy the bytes if it needs them afterward; it must never call
 * [fotoz_free_string] on it itself.
 *
 * @return the same status code passed to [callback], or [BridgeStatus.INVALID_HANDLE] /
 * [BridgeStatus.BAD_REQUEST] before [callback] is ever invoked -- this function never invokes
 * [callback] more than once, and never after returning.
 */
@CName("fotoz_sync_start")
fun fotoz_sync_start(
    handle: COpaquePointer?,
    sourceJson: CPointer<ByteVar>?,
    callback: CPointer<CFunction<(Int, CPointer<ByteVar>?, COpaquePointer?) -> Unit>>?,
    userData: COpaquePointer?,
): Int = runCatchingStatus {
    if (handle.asLibraryHandleOrNull() == null) return@runCatchingStatus BridgeStatus.INVALID_HANDLE
    if (sourceJson.readUtf8OrNull() == null) return@runCatchingStatus BridgeStatus.BAD_REQUEST

    val reason = BridgeJson.encodeError(
        "sync is not implemented on this platform: Phase 1's real Source implementation " +
            "(AndroidMediaStoreSource) is Android-only, and no Linux Source exists yet",
    )
    val message = reason.toHeapCString()
    try {
        callback?.invoke(BridgeStatus.NOT_IMPLEMENTED, message, userData)
    } finally {
        fotoz_free_string(message)
    }
    BridgeStatus.NOT_IMPLEMENTED
}
