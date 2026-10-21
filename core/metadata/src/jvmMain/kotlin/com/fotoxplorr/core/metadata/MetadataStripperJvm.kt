package com.fotoxplorr.core.metadata

import java.io.InputStream
import java.io.OutputStream

/**
 * The JVM-only entry point [MetadataStripper] itself no longer has a member for (ADR-010 WP1.2:
 * the object's own API is [ByteSink]-based so it also compiles for native targets). An extension
 * function resolves at call sites exactly like the member it replaces -- `MetadataStripper.strip(
 * input, output)` reads identically whether it is a member or an extension -- so every existing
 * caller needs only an updated import, not a changed call.
 *
 * Reads all of [input] into memory -- every format [MetadataStripper] handles needs to look back
 * (a WebP RIFF size, a GIF trailer) or forward (JPEG's next-marker scan) past what a single
 * streaming pass could hold, and the images this pipeline handles (camera photos, not
 * multi-gigabyte RAW masters) are small enough that this is not a meaningful memory concern.
 */
fun MetadataStripper.strip(input: InputStream, output: OutputStream): MetadataStripper.StripResult {
    val sink = ByteSink { bytes, offset, length -> output.write(bytes, offset, length) }
    return stripBytes(input.readBytes(), sink)
}
