package com.fotoxplorr.app.audiotags

/**
 * The tag fields this app can read and write, across every format [AudioTagCodec] understands.
 * Deliberately the SMALL common subset every one of ID3v2, MP4 `ilst` and FLAC Vorbis comments can
 * represent (see each codec's own doc for its format-specific frame/atom/field names) rather than a
 * superset of everything any one format supports — a field only [AudioTagWriter] can plausibly ask
 * every format to persist is one this app can offer a single "Edit tags" sheet for at all.
 *
 * `null` means "leave this field as the file already has it" for [AudioTagCodec.write] — an edit
 * that touches only the title must not blank out an existing artist, album or cover.
 * [coverArt] is compared and hashed by content ([equals]/[hashCode] below), not by reference: two
 * [AudioTags] built from the same bytes down two different code paths (a round-tripped read, say)
 * must compare equal, which a data class's default reference-identity `ByteArray.equals` would
 * defeat for exactly the case the round-trip tests below depend on.
 */
data class AudioTags(
    val title: String? = null,
    val artist: String? = null,
    val album: String? = null,
    val year: Int? = null,
    val trackNumber: Int? = null,
    val genre: String? = null,
    val coverArt: ByteArray? = null,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is AudioTags) return false
        return title == other.title &&
            artist == other.artist &&
            album == other.album &&
            year == other.year &&
            trackNumber == other.trackNumber &&
            genre == other.genre &&
            (coverArt?.contentEquals(other.coverArt) ?: (other.coverArt == null))
    }

    override fun hashCode(): Int {
        var result = title?.hashCode() ?: 0
        result = 31 * result + (artist?.hashCode() ?: 0)
        result = 31 * result + (album?.hashCode() ?: 0)
        result = 31 * result + (year ?: 0)
        result = 31 * result + (trackNumber ?: 0)
        result = 31 * result + (genre?.hashCode() ?: 0)
        result = 31 * result + (coverArt?.contentHashCode() ?: 0)
        return result
    }
}
