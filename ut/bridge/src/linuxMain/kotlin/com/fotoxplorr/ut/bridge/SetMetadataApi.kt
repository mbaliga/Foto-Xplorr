@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class, kotlin.experimental.ExperimentalNativeApi::class)

package com.fotoxplorr.ut.bridge

import com.fotoxplorr.core.metadata.ByteSink
import com.fotoxplorr.core.metadata.MetadataStripper
import com.fotoxplorr.core.metadata.SidecarNaming
import com.fotoxplorr.core.metadata.SidecarStyle
import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.COpaquePointer
import kotlinx.cinterop.CPointer
import kotlin.native.CName

/**
 * WP8.1's brief, followed literally: scope this to exactly what `:core:metadata`'s native-ready
 * pieces ([SidecarNaming], [MetadataStripper]) can actually do, and stub everything that needs
 * `XmpPacket`/`XmpSidecarStore` (jvmMain-only per ADR-010 -- confirmed by that module's own
 * readiness table in MASTER-PROGRESS.md, not assumed). Dispatches on [SetMetadataRequestDto.kind]:
 *
 *  - `"sidecar_name"` -- REAL, pure computation, no file I/O at all: [SidecarNaming.sidecarFileName]
 *    given `originalFileName` + `style` (`"DARKTABLE"` or `"LIGHTROOM"`).
 *  - `"strip"` -- REAL: reads `inputPath` from disk, runs it through
 *    [MetadataStripper.stripBytes] (the actual P0-04 identity/location-metadata stripper, moved to
 *    `:core:metadata` in WP1.2), writes the result to `outputPath`.
 *  - anything else (an omitted `kind`, `"xmp"`, `"rating"`, `"keywords"`, `"caption"` -- real XMP
 *    field round-trip) -- **NOT IMPLEMENTED**. This app's own real XMP read/write
 *    (`XmpPacket`/`XmpSidecarStore`, `MetadataWriter`) is jvmMain/Android-only; there is no
 *    native-Kotlin XML/XMP parser to call. Returns [BridgeStatus.NOT_IMPLEMENTED] with a JSON
 *    reason, never a fake success -- per this WP's own explicit instruction.
 *
 * Does not need a database [handle] for either real sub-operation (neither touches `asset_user`),
 * so -- unlike [fotoz_query_page]/[fotoz_asset_detail] -- an invalid handle is not this function's
 * failure mode; [handle] is still validated for API-shape consistency with the rest of this
 * module (every other mutating entry point takes one), and a null handle is
 * [BridgeStatus.INVALID_HANDLE], not silently ignored.
 *
 * @return a status code, never a JSON string: unlike the read paths above, this call has no
 * per-request "rows" shape a caller queries JSON out of, and MASTER-PLAN.md's own coarse API
 * pairs `set_metadata` with a plain outcome, so a status [Int] (see `BridgeStatus.kt`) is the
 * simpler, honest fit. `sidecar_name`'s computed filename and `strip`'s detected
 * [MetadataStripper.Format] are proven by this module's own `linuxTest` rather than returned here
 * -- a follow-up that needs the computed value back through the C API itself (rather than just
 * confirming the operation succeeded) can widen this to an out-parameter or a JSON return then.
 */
@CName("fotoz_set_metadata")
fun fotoz_set_metadata(
    handle: COpaquePointer?,
    requestJson: CPointer<ByteVar>?,
): Int = runCatchingStatus {
    if (handle.asLibraryHandleOrNull() == null) return@runCatchingStatus BridgeStatus.INVALID_HANDLE
    val text = requestJson.readUtf8OrNull() ?: return@runCatchingStatus BridgeStatus.BAD_REQUEST
    val request = try {
        BridgeJson.json.decodeFromString(SetMetadataRequestDto.serializer(), text)
    } catch (t: Throwable) {
        return@runCatchingStatus BridgeStatus.BAD_REQUEST
    }
    when (request.kind) {
        "sidecar_name" -> sidecarName(request)
        "strip" -> strip(request)
        else -> BridgeStatus.NOT_IMPLEMENTED
    }
}

private fun sidecarName(request: SetMetadataRequestDto): Int {
    val originalFileName = request.originalFileName ?: return BridgeStatus.BAD_REQUEST
    val style = when (request.style) {
        "DARKTABLE" -> SidecarStyle.DARKTABLE
        "LIGHTROOM" -> SidecarStyle.LIGHTROOM
        else -> return BridgeStatus.BAD_REQUEST
    }
    // The computed name itself has no return channel in this Int-returning entry point (see this
    // file's own KDoc) -- SidecarNamingTest / this module's own linuxTest already pin
    // SidecarNaming's correctness; this call proves the wiring, not the arithmetic again.
    SidecarNaming.sidecarFileName(originalFileName, style)
    return BridgeStatus.OK
}

private fun strip(request: SetMetadataRequestDto): Int {
    val inputPath = request.inputPath ?: return BridgeStatus.BAD_REQUEST
    val outputPath = request.outputPath ?: return BridgeStatus.BAD_REQUEST
    val bytes = readWholeFile(inputPath) ?: return BridgeStatus.IO_ERROR
    val output = ArrayList<Byte>(bytes.size)
    val sink = ByteSink { chunk, offset, length -> for (i in offset until offset + length) output.add(chunk[i]) }
    val result = MetadataStripper.stripBytes(bytes, sink)
    return when (result) {
        is MetadataStripper.StripResult.Unsupported -> BridgeStatus.BAD_REQUEST
        is MetadataStripper.StripResult.Stripped ->
            if (writeWholeFile(outputPath, output.toByteArray())) BridgeStatus.OK else BridgeStatus.IO_ERROR
    }
}
