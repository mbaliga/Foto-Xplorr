package com.fotoxplorr.app.backup

/**
 * Builds a file's [QuickHash] from the bytes as they stream past, so the backup writer gets it for
 * free instead of reading the head and tail a second time. Holds at most two samples of memory:
 * the first [QuickHash.SAMPLE] bytes and a ring of the last [QuickHash.SAMPLE].
 */
class QuickHashCollector {
    private val head = ByteArray(QuickHash.SAMPLE)
    private var headFilled = 0
    private val ring = ByteArray(QuickHash.SAMPLE)
    private var ringNext = 0
    private var total = 0L

    fun update(buffer: ByteArray, offset: Int, length: Int) {
        if (length <= 0) return
        if (headFilled < head.size) {
            val take = minOf(length, head.size - headFilled)
            System.arraycopy(buffer, offset, head, headFilled, take)
            headFilled += take
        }
        // Only the last SAMPLE bytes of this chunk can still be in the ring afterwards.
        var source = offset + if (length > ring.size) length - ring.size else 0
        var left = offset + length - source
        while (left > 0) {
            val n = minOf(left, ring.size - ringNext)
            System.arraycopy(buffer, source, ring, ringNext, n)
            ringNext = (ringNext + n) % ring.size
            source += n
            left -= n
        }
        total += length
    }

    val bytesSeen: Long get() = total

    /** The hash of everything passed to [update], as if [QuickHash.of] had been given the whole file. */
    fun result(): String {
        val size = total
        val headBytes = head.copyOf(QuickHash.headLength(size))
        val tailLength = QuickHash.tailLength(size)
        val tail = ByteArray(tailLength)
        // The ring's oldest byte is at ringNext once it has wrapped, else at 0.
        val stored = minOf(size, ring.size.toLong()).toInt()
        val oldest = if (size >= ring.size) ringNext else 0
        val firstWanted = stored - tailLength
        for (i in 0 until tailLength) tail[i] = ring[(oldest + firstWanted + i) % ring.size]
        return QuickHash.of(size, headBytes, tail)
    }
}
