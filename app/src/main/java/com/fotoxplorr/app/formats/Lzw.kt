package com.fotoxplorr.app.formats

/**
 * The LZW variant the GIF spec defines: variable-width codes starting at `colorDepth + 1` bits
 * and growing up to 12, a Clear Code that resets the dictionary (also emitted when the
 * dictionary fills at 4096 entries), an End-of-Information code, and -- unlike the classic Unix
 * `compress` this is descended from -- codes packed into bytes LEAST-significant-bit first.
 *
 * [compress] and [decompress] are exact inverses of one operation, not two independently-tested
 * halves: [GifEncoderTest]'s round trip (encode, then decode with [GifDecoder]) is what actually
 * proves this is correct, because a bug that is symmetric between the two (the same wrong
 * code-width boundary in both, say) would make each look internally consistent on its own.
 *
 * Kept separate from [GifEncoder]/[GifDecoder] because both need it and it is the one piece
 * genuinely shared between "turn frames into bytes" and "turn bytes into frames" -- everything
 * else about a GIF file (the container, the colour table, per-frame metadata) differs between
 * writing and reading.
 */
internal object LzwGif {

    /** GIF requires the LZW minimum code size to be at least 2, even for a 2-colour image. */
    private const val MIN_CODE_SIZE = 2
    private const val MAX_CODE_SIZE = 12
    private const val MAX_DICTIONARY_ENTRIES = 1 shl MAX_CODE_SIZE

    /** Compress [indices] (palette indices, one per pixel) into GIF-style LZW data, using
     *  [colorDepth] as the base code size (the byte a GIF image block stores just before this
     *  data -- see [GifEncoder]). */
    fun compress(indices: IntArray, colorDepth: Int): ByteArray {
        val baseCodeSize = colorDepth.coerceAtLeast(MIN_CODE_SIZE)
        val clearCode = 1 shl baseCodeSize
        val endCode = clearCode + 1
        val writer = BitWriter()

        var codeSize = baseCodeSize + 1
        var nextCode = endCode + 1
        // Keyed on (prefixCode shl 8) or nextSymbol -- both fit comfortably (codes stay under
        // 4096, symbols under 256), so this avoids allocating a List<Int> per dictionary entry
        // the way a naive "sequence so far" key would.
        var dictionary = HashMap<Int, Int>()

        writer.writeCode(clearCode, codeSize)

        if (indices.isEmpty()) {
            writer.writeCode(endCode, codeSize)
            return writer.toByteArray()
        }

        var prefix = indices[0]
        for (i in 1 until indices.size) {
            val symbol = indices[i]
            val key = (prefix shl 8) or symbol
            val known = dictionary[key]
            if (known != null) {
                prefix = known
                continue
            }

            writer.writeCode(prefix, codeSize)
            dictionary[key] = nextCode
            nextCode++
            if (codeSize < MAX_CODE_SIZE && nextCode == (1 shl codeSize)) {
                codeSize++
            }
            if (nextCode == MAX_DICTIONARY_ENTRIES) {
                // Dictionary exhausted -- start over rather than pretending it can keep growing.
                writer.writeCode(clearCode, codeSize)
                dictionary = HashMap()
                nextCode = endCode + 1
                codeSize = baseCodeSize + 1
            }
            prefix = symbol
        }
        writer.writeCode(prefix, codeSize)
        writer.writeCode(endCode, codeSize)
        return writer.toByteArray()
    }

    /** Decompress GIF-style LZW [data] back into exactly [expectedPixelCount] palette indices. */
    fun decompress(data: ByteArray, colorDepth: Int, expectedPixelCount: Int): IntArray {
        val baseCodeSize = colorDepth.coerceAtLeast(MIN_CODE_SIZE)
        val clearCode = 1 shl baseCodeSize
        val endCode = clearCode + 1
        val reader = BitReader(data)

        val output = IntArray(expectedPixelCount)
        var outPos = 0
        fun emit(sequence: IntArray) {
            // Clamped, not asserted: this decoder runs on real files from a user's photo
            // library, not just data this app produced itself, and a corrupt or truncated GIF
            // must degrade to a short/garbled decode rather than crash the app with an
            // ArrayIndexOutOfBoundsException over one bad frame.
            val n = sequence.size.coerceAtMost(output.size - outPos)
            System.arraycopy(sequence, 0, output, outPos, n)
            outPos += n
        }

        // Codes 0..clearCode-1 are literal single-symbol "roots" with no dictionary entry;
        // codes from (endCode + 1) up are compound and stored as (prefix code, last symbol) --
        // a code, not a copy of its expansion, so expanding a long-since-common sequence is one
        // array walk instead of a chain of string concatenations.
        var codeSize = baseCodeSize + 1
        var nextCode = endCode + 1
        val prefixOf = IntArray(MAX_DICTIONARY_ENTRIES)
        val lastOf = IntArray(MAX_DICTIONARY_ENTRIES)

        fun sequenceFor(code: Int): IntArray {
            var c = code
            val reversed = ArrayList<Int>()
            while (c >= clearCode + 2) {
                reversed.add(lastOf[c])
                c = prefixOf[c]
            }
            reversed.add(c)
            reversed.reverse()
            return reversed.toIntArray()
        }

        fun resetDictionary() {
            nextCode = endCode + 1
            codeSize = baseCodeSize + 1
        }

        val opening = reader.readCode(codeSize)
        require(opening == clearCode) { "GIF LZW data must open with a Clear Code, got $opening" }
        resetDictionary()
        var code = reader.readCode(codeSize) ?: return output.copyOf(outPos)
        if (code == endCode) return output.copyOf(outPos)

        var previousCode = code
        var previousSequence = sequenceFor(code)
        emit(previousSequence)

        while (outPos < expectedPixelCount) {
            val next = reader.readCode(codeSize) ?: break
            if (next == endCode) break
            if (next == clearCode) {
                resetDictionary()
                val afterClear = reader.readCode(codeSize) ?: break
                if (afterClear == endCode) break
                previousCode = afterClear
                previousSequence = sequenceFor(afterClear)
                emit(previousSequence)
                continue
            }

            val sequence = when {
                next < nextCode -> sequenceFor(next)
                // The one special case LZW decoding needs: a code the encoder emitted before
                // this decoder has assigned it a dictionary entry yet, because the encoder adds
                // it to ITS dictionary and can immediately reuse it one symbol later. It is
                // always resolvable as "the previous sequence, plus its own first symbol" --
                // that is the only sequence consistent with how the encoder could have just
                // produced this exact code.
                next == nextCode -> previousSequence + previousSequence[0]
                else -> error("invalid LZW code $next (next unused code is $nextCode)")
            }
            emit(sequence)

            // New dictionary entry: the previous sequence with the new sequence's first symbol
            // appended -- the standard LZW identity, and the exact inverse of how GifEncoder's
            // compressor built the same entry from the writing side.
            if (nextCode < MAX_DICTIONARY_ENTRIES) {
                prefixOf[nextCode] = previousCode
                lastOf[nextCode] = sequence[0]
                nextCode++
                // `- 1` here, where the compressor's equivalent check has no offset, is not a
                // typo -- it is what keeps the two in step. The compressor adds a dictionary
                // entry in lockstep with every code it WRITES (write, then immediately add).
                // The decompressor structurally can't do that: it only learns a new entry once
                // it has decoded the code AFTER the one that entry describes, so its entry count
                // is always exactly one behind the compressor's at the equivalent point in the
                // stream. Bumping the code width one dictionary slot early exactly cancels that
                // one-entry lag; checking at the same threshold the compressor uses (no `- 1`)
                // reads every code from here on one bit short, which either scrambles every
                // remaining pixel or -- once a code exceeds what the (still too narrow) reader
                // could ever produce -- throws the "invalid LZW code" error below.
                if (codeSize < MAX_CODE_SIZE && nextCode == (1 shl codeSize) - 1) {
                    codeSize++
                }
            }

            previousCode = next
            previousSequence = sequence
        }
        return output.copyOf(outPos)
    }

    private class BitWriter {
        private val out = java.io.ByteArrayOutputStream()
        private var current = 0
        private var bitCount = 0

        fun writeCode(code: Int, bits: Int) {
            var value = code
            repeat(bits) {
                current = current or ((value and 1) shl bitCount)
                value = value shr 1
                bitCount++
                if (bitCount == 8) {
                    out.write(current)
                    current = 0
                    bitCount = 0
                }
            }
        }

        fun toByteArray(): ByteArray {
            if (bitCount > 0) {
                out.write(current)
                current = 0
                bitCount = 0
            }
            return out.toByteArray()
        }
    }

    private class BitReader(private val data: ByteArray) {
        private var bytePos = 0
        private var bitPos = 0

        /** Returns null once the underlying bytes are exhausted -- a well-formed stream never
         *  hits this (it reads an End-of-Information code first), so this only fires on
         *  truncated/corrupt input, which the caller treats as "stop decoding" rather than
         *  a crash. */
        fun readCode(bits: Int): Int? {
            var value = 0
            for (i in 0 until bits) {
                if (bytePos >= data.size) return null
                val bit = (data[bytePos].toInt() shr bitPos) and 1
                value = value or (bit shl i)
                bitPos++
                if (bitPos == 8) {
                    bitPos = 0
                    bytePos++
                }
            }
            return value
        }
    }
}
