package com.fotoxplorr.core.model

import kotlin.jvm.JvmInline

/**
 * ADR-011 §2/§3: the app-owned, stable identity of a catalogue row (`asset.asset_id`),
 * replacing [MediaId] (the old MediaStore-`_ID`-keyed identity) as the real primary key from
 * WP1.3 on. `0` is Room's own "not yet inserted, autogenerate one" sentinel -- never a real id.
 */
@JvmInline
value class AssetId(val value: Long)

/** ADR-011 §2: `source.source_id`, one row per place files live (a MediaStore volume in Phase 1;
 *  SAF/USB/network sources from Phase 3). `0` is the same "not yet inserted" sentinel as
 *  [AssetId]. */
@JvmInline
value class SourceId(val value: Long)
