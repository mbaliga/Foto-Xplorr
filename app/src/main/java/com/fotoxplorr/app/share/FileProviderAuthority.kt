package com.fotoxplorr.app.share

import android.content.Context

/**
 * The single source of truth for this app's `FileProvider` authority string, matching
 * `AndroidManifest.xml`'s declared `<provider android:authorities="${applicationId}.files">`
 * exactly -- `${applicationId}` is a manifest placeholder resolved at build time to the running
 * variant's actual application id (`.debug`/`.connect` suffix included where one applies), and
 * [Context.packageName] at runtime always equals that same running variant's application id, so
 * the two stay in sync for every build type without this function needing to know about suffixes
 * itself.
 *
 * `ZipExporter` used to build `"${packageName}.fileprovider"` inline instead -- a name the
 * manifest has never declared -- so every zip export threw `IllegalArgumentException` from
 * `FileProvider.getUriForFile`, after the whole archive had already been written (P0-06). Every
 * `FileProvider.getUriForFile` call in this app goes through this one function now
 * ([com.fotoxplorr.app.share.SharePreparer], [ZipExporter], `lift.StickerExporter`,
 * `moments.MomentFrameExporter`, `moments.ClipExporter`), so the string can never drift out of
 * sync with the manifest in only one call site again. `FileProviderAuthorityTest` pins the
 * manifest side of this contract.
 */
fun fileProviderAuthority(context: Context): String = "${context.packageName}.files"
