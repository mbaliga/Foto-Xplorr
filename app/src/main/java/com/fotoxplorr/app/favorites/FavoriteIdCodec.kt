package com.fotoxplorr.app.favorites

import com.fotoxplorr.app.media.MediaId

internal object FavoriteIdCodec {
    fun decode(values: Set<String>?): Set<MediaId> = values
        .orEmpty()
        .mapNotNullTo(linkedSetOf()) { value ->
            value.toLongOrNull()
                ?.takeIf { it >= 0L }
                ?.let(::MediaId)
        }

    fun encode(ids: Set<MediaId>): Set<String> = ids
        .asSequence()
        .map { it.value }
        .filter { it >= 0L }
        .sorted()
        .mapTo(linkedSetOf(), Long::toString)
}
