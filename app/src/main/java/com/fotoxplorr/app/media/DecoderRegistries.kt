package com.fotoxplorr.app.media

import android.os.Build
import com.fotoxplorr.core.formats.DecoderProvider
import com.fotoxplorr.core.formats.DecoderRegistry
import com.fotoxplorr.core.formats.raw.RawPreviewProvider
import com.fotoxplorr.core.formats.simple.SimpleDecoderProvider

/**
 * The app's one [DecoderRegistry]: the platform provider plus whatever packs or extractors have
 * been [install]ed. Installing rebuilds the registry (ordering is by priority and id, so the order
 * of installation never matters) and the next tile that asks sees the new answer.
 */
object DecoderRegistries {
    private val installed = mutableListOf<DecoderProvider>()

    @Volatile
    private var cached: DecoderRegistry? = null

    fun current(): DecoderRegistry = cached ?: synchronized(this) {
        cached ?: DecoderRegistry(listOf(PlatformDecoderProvider(Build.VERSION.SDK_INT, AndroidMimeProbe), SimpleDecoderProvider(), RawPreviewProvider()) + installed)
            .also { cached = it }
    }

    /** Adds [provider]; a second provider with the same id replaces the first. */
    fun install(provider: DecoderProvider) = synchronized(this) {
        installed.removeAll { it.id == provider.id }
        installed.add(provider)
        cached = null
    }
}
