@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class, kotlin.experimental.ExperimentalNativeApi::class)

package com.fotoxplorr.ut.bridge

import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.COpaquePointer
import kotlinx.cinterop.CPointer
import kotlin.native.CName

/**
 * MASTER-PLAN.md's own C API line names an `ops_*` family (its Fylz sibling's journal/staging/
 * recycle "operation engine"). Checked before writing this, per WP8.1's brief: **Fotoz has no
 * such thing, at any phase, in this codebase.** `:core:organize` only holds planning-stage pure
 * functions -- [com.fotoxplorr.core.organize.BulkRenamePlanner] (computes a rename plan),
 * [com.fotoxplorr.core.organize.SweepPolicy] and
 * [com.fotoxplorr.core.organize.ScanPlan] -- none of which execute anything; the file operations
 * that actually move/copy/delete bytes (`MediaFileOperations.copyToTree` and friends) are plain
 * `suspend fun`s in `:app`, Android-only, with no journal, staging area or recycle semantics of
 * their own (WP1.6's own survey confirms this -- "a long copy is cancelled, not paused" -- and
 * ADR-011 section 6's Trash is a database flag, not an operation-engine concept).
 *
 * So this is a single, honest stub covering the whole `ops_*` family with one entry point, rather
 * than inventing several named operations with nothing real behind any of them. Always returns
 * [BridgeStatus.NOT_IMPLEMENTED]. [opJson] is read (a malformed body is still
 * [BridgeStatus.BAD_REQUEST]) but not otherwise acted on.
 */
@CName("fotoz_ops_execute")
fun fotoz_ops_execute(handle: COpaquePointer?, opJson: CPointer<ByteVar>?): Int = runCatchingStatus {
    if (handle.asLibraryHandleOrNull() == null) return@runCatchingStatus BridgeStatus.INVALID_HANDLE
    val text = opJson.readUtf8OrNull() ?: return@runCatchingStatus BridgeStatus.BAD_REQUEST
    try {
        BridgeJson.json.decodeFromString(OpsRequestDto.serializer(), text)
    } catch (t: Throwable) {
        return@runCatchingStatus BridgeStatus.BAD_REQUEST
    }
    BridgeStatus.NOT_IMPLEMENTED
}
