package com.fotoxplorr.core.model

import kotlin.jvm.JvmInline

/**
 * ADR-011 §2: `asset.format_id`, a stable string identifier for what [:core:formats'
 * MediaFormat][com.fotoxplorr.core.formats] classifies a file as. An opaque wrapper here, not an
 * enum: `:core:model` has no dependency on `:core:formats` (ADR-010 §2's dependency chain runs
 * the other way, `model ← formats`), so the actual `MediaFormat -> FormatId` mapping lives in
 * `:core:formats` instead, alongside the classifier it describes.
 */
@JvmInline
value class FormatId(val value: String)
