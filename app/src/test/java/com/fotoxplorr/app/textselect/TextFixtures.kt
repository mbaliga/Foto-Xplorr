package com.fotoxplorr.app.textselect

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * Hand-placed text for the selection tests, described in FRAME PIXELS of a 1024 x 512 frame (a
 * 2:1 landscape, on purpose: a square frame cannot tell a normalised-units mistake from a right
 * answer; powers of two so that normalising and scaling back are exact and boundary cases can be
 * pinned without a tolerance) and converted to the normalised 0..1 corners an adapter would hand over.
 *
 *   block 0   L0  y 100..130   PLATFORM x100..300   9 x320..350        words 0, 1
 *             L1  y 150..180   Northern x100..260   line x280..350     words 2, 3
 *   block 1   L2  y 300..330   Exit x600..680  only x700..780  left x800..880   words 4, 5, 6
 *
 * Lines 20 px apart, so a finger radius under 10 can pass between them.
 */
internal const val FRAME_W = 1024
internal const val FRAME_H = 512

internal fun norm(q: Quad): Quad = q.scaled(1f / FRAME_W, 1f / FRAME_H)

internal fun box(left: Float, top: Float, right: Float, bottom: Float): Quad = Quad.fromBounds(left, top, right, bottom)

internal fun rawWord(text: String, left: Float, top: Float, right: Float, bottom: Float, lang: String? = null) =
    RawWord(text, norm(box(left, top, right, bottom)), lang)

/** A quad of [w] x [h] centred on ([cx], [cy]) turned [degrees] clockwise on screen, in text order. */
internal fun turned(cx: Float, cy: Float, w: Float, h: Float, degrees: Float): Quad {
    val a = degrees * PI.toFloat() / 180f
    val ux = cos(a); val uy = sin(a)
    val vx = -uy; val vy = ux
    fun at(u: Float, v: Float) = Pt(cx + u * ux + v * vx, cy + u * uy + v * vy)
    return Quad(at(-w / 2, -h / 2), at(w / 2, -h / 2), at(w / 2, h / 2), at(-w / 2, h / 2))
}

internal fun page(): TextLayout = TextLayout.build(
    listOf(
        RawBlock(
            listOf(
                RawLine(listOf(rawWord("PLATFORM", 100f, 100f, 300f, 130f), rawWord("9", 320f, 100f, 350f, 130f))),
                RawLine(listOf(rawWord("Northern", 100f, 150f, 260f, 180f), rawWord("line", 280f, 150f, 350f, 180f))),
            ),
        ),
        RawBlock(
            listOf(
                RawLine(
                    listOf(
                        rawWord("Exit", 600f, 300f, 680f, 330f),
                        rawWord("only", 700f, 300f, 780f, 330f),
                        rawWord("left", 800f, 300f, 880f, 330f),
                    ),
                ),
            ),
        ),
    ),
    FRAME_W, FRAME_H,
)

/** One horizontal line of single-letter-ish words, each 40 wide with a 10 gap, from x = 100. */
internal fun lineOf(vararg texts: String, top: Float = 100f, bottom: Float = 130f): RawLine =
    RawLine(texts.mapIndexed { i, t -> rawWord(t, 100f + i * 50f, top, 140f + i * 50f, bottom) })

internal fun layoutOf(vararg blocks: RawBlock): TextLayout = TextLayout.build(blocks.toList(), FRAME_W, FRAME_H)

internal fun fx(x: Float): Float = x / FRAME_W

internal fun fy(y: Float): Float = y / FRAME_H
