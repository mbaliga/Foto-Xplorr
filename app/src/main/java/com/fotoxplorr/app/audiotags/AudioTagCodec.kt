package com.fotoxplorr.app.audiotags

import java.io.InputStream
import java.io.OutputStream

/**
 * Reads and writes [AudioTags] across MP3 (ID3v2), MP4/M4A (`ilst`) and FLAC (Vorbis comments) —
 * the one entry point every caller (tests, [com.fotoxplorr.app.audiotags.AudioTagWriter]) uses;
 * nothing outside this package should reach for [Id3v2Codec], [Mp4TagCodec] or [FlacTagCodec]
 * directly. Format is sniffed from the bytes themselves (magic numbers), never a filename or MIME
 * type — the same reasoning [com.fotoxplorr.app.audio.AndroidAudioMediaStoreScanner] already
 * applies to `MIME_TYPE`: what MediaStore or a file extension CLAIMS a file is and what its bytes
 * actually are can disagree, and only the bytes are trustworthy enough to parse.
 */
object AudioTagCodec {

    fun read(bytes: ByteArray): AudioTags? = when (detectFormat(bytes)) {
        AudioFormat.MP3 -> Id3v2Codec.read(bytes)
        AudioFormat.MP4 -> Mp4TagCodec.read(bytes)
        AudioFormat.FLAC -> FlacTagCodec.read(bytes)
        null -> null
    }

    fun read(stream: InputStream): AudioTags? = read(stream.readBytes())

    /**
     * Writes [tags] into a copy of [input], to [output]. A `null` field in [tags] means "leave
     * this field exactly as [input] already has it" — resolved once here, against [input]'s own
     * existing tags, BEFORE the format-specific writer ever runs, so [Id3v2Codec], [Mp4TagCodec]
     * and [FlacTagCodec] each only ever have to write a single, fully-resolved [AudioTags] rather
     * than re-implement this merge three times. There is currently no way to explicitly CLEAR a
     * field back to blank — see `docs/audio-playback.md`'s tag-editing limits.
     *
     * @throws UnsupportedAudioFormatException if [input] is not a recognised MP3/MP4/FLAC file.
     */
    fun write(input: ByteArray, output: OutputStream, tags: AudioTags) {
        val format = detectFormat(input) ?: throw UnsupportedAudioFormatException()
        val merged = mergeTags(read(input), tags)
        when (format) {
            AudioFormat.MP3 -> Id3v2Codec.write(input, output, merged)
            AudioFormat.MP4 -> Mp4TagCodec.write(input, output, merged)
            AudioFormat.FLAC -> FlacTagCodec.write(input, output, merged)
        }
    }
}

class UnsupportedAudioFormatException :
    Exception("Not a recognised MP3 (ID3v2), MP4/M4A or FLAC file")

internal enum class AudioFormat { MP3, MP4, FLAC }

internal fun detectFormat(bytes: ByteArray): AudioFormat? = when {
    bytes.size >= 4 && bytes[0] == 'f'.code.toByte() && bytes[1] == 'L'.code.toByte() &&
        bytes[2] == 'a'.code.toByte() && bytes[3] == 'C'.code.toByte()
    -> AudioFormat.FLAC
    // MP4/M4A: a `ftyp` box almost always comes right after the leading 4-byte size field of the
    // very first box -- true for every encoder this app is likely to meet, streaming-optimised or
    // not, since `ftyp` is required to be the first box in the file by the ISO base media spec.
    bytes.size >= 8 && String(bytes, 4, 4, Charsets.US_ASCII) == "ftyp"
    -> AudioFormat.MP4
    bytes.size >= 3 && bytes[0] == 'I'.code.toByte() && bytes[1] == 'D'.code.toByte() && bytes[2] == '3'.code.toByte()
    -> AudioFormat.MP3
    // A bare MPEG audio frame sync (11 set bits) with no ID3v2 tag at all -- still MP3, just
    // untagged; writing to one CREATES its first ID3v2 tag rather than requiring one to exist.
    bytes.size >= 2 && (bytes[0].toInt() and 0xFF) == 0xFF && (bytes[1].toInt() and 0xE0) == 0xE0
    -> AudioFormat.MP3
    else -> null
}

internal fun mergeTags(existing: AudioTags?, edits: AudioTags): AudioTags = AudioTags(
    title = edits.title ?: existing?.title,
    artist = edits.artist ?: existing?.artist,
    album = edits.album ?: existing?.album,
    year = edits.year ?: existing?.year,
    trackNumber = edits.trackNumber ?: existing?.trackNumber,
    genre = edits.genre ?: existing?.genre,
    coverArt = edits.coverArt ?: existing?.coverArt,
)
