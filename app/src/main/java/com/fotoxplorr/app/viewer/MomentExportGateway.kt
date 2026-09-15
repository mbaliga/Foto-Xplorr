package com.fotoxplorr.app.viewer

import android.content.Context
import android.net.Uri
import com.fotoxplorr.app.media.MediaAsset
import com.fotoxplorr.app.moments.ClipExporter
import com.fotoxplorr.app.moments.MomentFrameExporter

/**
 * Seam between this chrome's "Share moment" / "Create clip" actions and the two export operations
 * `com.fotoxplorr.app.moments.MomentFrameExporter.exportFrame(asset, positionMs): Result<Uri>` and
 * `com.fotoxplorr.app.moments.ClipExporter.exportClip(asset, startMs, endMs): Result<Uri>`.
 *
 * A sibling, concurrently-written change was still adding those two classes' FILES while this one
 * was first being built -- only the `VideoMoment`/`VideoMomentStore` contract in
 * `moments/VideoMoment.kt` existed at the start of this work, and calling either exporter directly
 * from [VideoPlayer] before they landed would have failed `:app:compileOfflineDebugKotlin` with an
 * unresolved reference, for a reason that would have had nothing to do with THIS feature's own
 * correctness. Both have since landed (checked directly against `moments/MomentFrameExporter.kt`
 * and `moments/ClipExporter.kt` in this working tree, not assumed), so [DefaultMomentExportGateway]
 * below now calls them for real -- but the interface stays: [VideoPlayer] depends on
 * [MomentExportGateway] rather than on the two concrete classes directly, which is what let this
 * file absorb that landing as a change to ONE class's body, with zero changes anywhere in
 * [VideoPlayer], [KeyMomentBar], or their tests.
 */
internal interface MomentExportGateway {
    /** Mirrors `MomentFrameExporter.exportFrame(asset, positionMs)` exactly: same parameter
     *  order, same [Result] shape. */
    suspend fun exportFrame(asset: MediaAsset, positionMs: Long): Result<Uri>

    /** Mirrors `ClipExporter.exportClip(asset, startMs, endMs)` exactly. */
    suspend fun exportClip(asset: MediaAsset, startMs: Long, endMs: Long): Result<Uri>
}

/**
 * Delegates straight through to the real exporters. Constructing both up front (rather than lazily
 * per call) costs nothing -- both constructors do is resolve `context.applicationContext` and a
 * `FileProvider` authority string, no I/O -- and keeps every call site a one-line forward.
 */
internal class DefaultMomentExportGateway(context: Context) : MomentExportGateway {
    private val frameExporter = MomentFrameExporter(context)
    private val clipExporter = ClipExporter(context)

    override suspend fun exportFrame(asset: MediaAsset, positionMs: Long): Result<Uri> =
        frameExporter.exportFrame(asset, positionMs)

    override suspend fun exportClip(asset: MediaAsset, startMs: Long, endMs: Long): Result<Uri> =
        clipExporter.exportClip(asset, startMs, endMs)
}
