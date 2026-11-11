@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class, kotlin.experimental.ExperimentalNativeApi::class)

package com.fotoxplorr.ut.bridge

import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.nativeHeap
import kotlin.native.CName

/**
 * The C API's one shared status vocabulary, per WP8.1's brief: "for every entry point where you
 * must stub, return a documented sentinel... rather than a fake success value" -- generalised
 * here to every entry point, real or stubbed, so a caller (the future `:ut:app` Qt/C++ bridge)
 * never has to guess a magic number or risk it drifting between this file and a hand-copied
 * header constant. Every status-returning `@CName` function in this module returns one of these.
 *
 * Exported as accessor functions, not raw `const val`s exposed to C, because Kotlin/Native's C
 * header generator does not emit a stable C symbol for a top-level Kotlin constant the way it
 * does for a function -- an accessor is the reliable way for a real C caller (and this module's
 * own `linuxTest`) to read the same value without hand-copying a number into two places.
 */
object BridgeStatus {
    const val OK = 0
    /** A null/invalid handle was passed where an open library was required. */
    const val INVALID_HANDLE = -1
    /** The request JSON was missing, malformed, or missing a required field. */
    const val BAD_REQUEST = -2
    /** A well-formed request referred to something that does not exist (e.g. an unknown asset id). */
    const val NOT_FOUND = -3
    /**
     * The operation is a real, named part of this C API's surface, but nothing behind it exists
     * yet on this platform -- see this module's own KDoc on [fotoz_sync_start], [fotoz_ops_execute]
     * and the XMP branch of [fotoz_set_metadata] for exactly what and why. Never returned for "this
     * isn't a real operation at all" (that is [BAD_REQUEST]).
     */
    const val NOT_IMPLEMENTED = -4
    /** A real operation was attempted and failed for an environmental reason (a file could not be
     *  read/written, a path did not exist) -- not a bug in the request itself. */
    const val IO_ERROR = -5
    /** Anything else: an unexpected exception was caught at the C boundary rather than left to
     *  cross it (Kotlin/Native exceptions must never escape a `@CName` function). */
    const val INTERNAL_ERROR = -6
}

@CName("fotoz_status_ok")
fun fotoz_status_ok(): Int = BridgeStatus.OK

@CName("fotoz_status_invalid_handle")
fun fotoz_status_invalid_handle(): Int = BridgeStatus.INVALID_HANDLE

@CName("fotoz_status_bad_request")
fun fotoz_status_bad_request(): Int = BridgeStatus.BAD_REQUEST

@CName("fotoz_status_not_found")
fun fotoz_status_not_found(): Int = BridgeStatus.NOT_FOUND

@CName("fotoz_status_not_implemented")
fun fotoz_status_not_implemented(): Int = BridgeStatus.NOT_IMPLEMENTED

@CName("fotoz_status_io_error")
fun fotoz_status_io_error(): Int = BridgeStatus.IO_ERROR

@CName("fotoz_status_internal_error")
fun fotoz_status_internal_error(): Int = BridgeStatus.INTERNAL_ERROR

/**
 * Frees a C string this library returned from [fotoz_capabilities], [fotoz_query_page],
 * [fotoz_asset_detail] or the sync callback's message argument. Every such string is heap-
 * allocated by [toHeapCString] (never Kotlin/Native's own stack-scoped `.cstr`, which would be
 * invalid the instant the returning function's `memScoped` block ended) -- this is its one
 * matching free function. Safe to call with `null`.
 */
@CName("fotoz_free_string")
fun fotoz_free_string(pointer: CPointer<ByteVar>?) {
    if (pointer != null) nativeHeap.free(pointer.rawValue)
}
