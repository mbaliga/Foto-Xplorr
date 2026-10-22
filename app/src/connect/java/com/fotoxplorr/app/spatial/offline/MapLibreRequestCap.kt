package com.fotoxplorr.app.spatial.offline

import com.fotoxplorr.feature.airemote.MapRequestCap

/**
 * The real [RequestCap]: the provider's limit of four requests in flight and a User-Agent that
 * names the app, applied to the map library through `MapRequestCap` (the one place that may hand
 * the library an HTTP client, see ADR-015). The limit is the provider's own number
 * ([OfflineMapLimits.TERMS_MAX_PARALLEL_REQUESTS]), not a lower one.
 */
internal object MapLibreRequestCap : RequestCap {

    /** Appended to the library's own User-Agent, which already carries the app's package and version. */
    const val USER_AGENT_SUFFIX = "Fotoz"

    override fun install(): Boolean =
        MapRequestCap.install(OfflineMapLimits.TERMS_MAX_PARALLEL_REQUESTS, USER_AGENT_SUFFIX)

    override fun restore() = MapRequestCap.restore()
}
