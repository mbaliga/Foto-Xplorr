package com.fotoxplorr.core.metadata

/**
 * A destination for bytes, portable across every target this module builds for. The JVM-only
 * entry point ([com.fotoxplorr.core.metadata.strip], jvmMain) adapts a real `java.io.OutputStream`
 * to this; a native target or a test can supply anything else that can accept bytes.
 */
fun interface ByteSink {
    fun write(bytes: ByteArray, offset: Int, length: Int)
}

fun ByteSink.write(bytes: ByteArray) = write(bytes, 0, bytes.size)
