@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.fotoxplorr.ut.bridge

import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.nativeHeap
import kotlinx.cinterop.set
import kotlinx.cinterop.toKString
import kotlinx.coroutines.runBlocking

/**
 * ADR-013 point 2: "opaque handles and byte buffers only" -- these are the two small, shared
 * mechanics every `@CName` entry point in this module builds on, so the marshalling rule (how a
 * Kotlin string becomes a C string and back) lives in exactly one place.
 */

/**
 * Reads a nul-terminated UTF-8 C string [this] gave us, or `null` for a null pointer -- every
 * `CPointer<ByteVar>?` input parameter in this module's `@CName` functions goes through this,
 * never a bare `.toKString()`, so a null input is a documented [BridgeStatus.BAD_REQUEST], never
 * a native crash.
 */
internal fun CPointer<ByteVar>?.readUtf8OrNull(): String? = this?.toKString()

/**
 * Heap-allocates a nul-terminated UTF-8 copy of [this] that outlives the returning function's own
 * stack frame -- unlike Kotlin/Native's `String.cstr`, which is only valid inside the `memScoped`
 * block that created it. The caller (a real C/C++ caller, or this module's own `linuxTest`) owns
 * the result and must release it via [fotoz_free_string] exactly once.
 */
internal fun String.toHeapCString(): CPointer<ByteVar> {
    val bytes = encodeToByteArray()
    val pointer = nativeHeap.allocArray<ByteVar>(bytes.size + 1)
    for (i in bytes.indices) pointer[i] = bytes[i]
    pointer[bytes.size] = 0
    return pointer
}

/**
 * Bridges this module's `suspend`-DAO calls (every Room query is `suspend`) onto the plain
 * synchronous `@CName` functions the C ABI needs. `runBlocking` is a real, ordinary part of
 * kotlinx-coroutines-core on Kotlin/Native (not a JVM-only assumption) -- the same bridge every
 * `jvmTest`/`desktopTest` in this constellation already uses for the identical reason, just on
 * the calling side instead of the test side.
 */
internal fun <T> runBridge(block: suspend () -> T): T = runBlocking { block() }

/**
 * Wraps any thrown exception into [BridgeStatus.INTERNAL_ERROR] plus its message, per this
 * module's own rule (see `BridgeStatus.kt`'s KDoc): a Kotlin/Native exception must never cross a
 * `@CName` function's boundary. Used by every entry point that returns a status [Int].
 */
internal inline fun runCatchingStatus(block: () -> Int): Int = try {
    block()
} catch (t: Throwable) {
    BridgeStatus.INTERNAL_ERROR
}

/**
 * Same rule as [runCatchingStatus], for an entry point that returns a JSON string: an unexpected
 * exception becomes a well-formed `{"error": ...}` payload (see [BridgeJson]), never a crash and
 * never a silent `null` (this module's own convention reserves `null` for an invalid *handle*,
 * not an internal failure -- see each `@CName` function's own KDoc).
 */
internal inline fun runCatchingJson(block: () -> String): CPointer<ByteVar> = try {
    block().toHeapCString()
} catch (t: Throwable) {
    BridgeJson.encodeError(t.message ?: "internal error").toHeapCString()
}
