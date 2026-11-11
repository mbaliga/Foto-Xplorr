package com.fotoxplorr.app.aquarium

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint

/**
 * Draws one creature into a canvas. A seam, not an abstraction for its own sake: whether
 * `Canvas.drawBitmapMesh` holds 60 fps with a dozen sprites on a low-end phone is NOT verified, and
 * the fallback (per-segment `drawBitmap` with a matrix each, or `drawVertices` with a bitmap
 * shader) is a second implementation of this one function.
 */
internal interface CreatureRenderer {
    /**
     * Draws [creature] with its [sprite]. [pxPerUnit] converts tank units to pixels (the canvas
     * height); [waveScale] is the motion profile's; ([offsetX], [offsetY]) is where the tank's
     * top left sits on the canvas.
     */
    fun draw(
        canvas: Canvas,
        creature: Creature,
        sprite: Bitmap,
        pxPerUnit: Float,
        waveScale: Float,
        offsetX: Float = 0f,
        offsetY: Float = 0f,
    )
}

/**
 * The mesh warp: [SwimMesh] writes a 9 x 5 grid of vertices and `drawBitmapMesh` samples the sprite
 * through it. One pose, one vertex array and one paint are reused for every creature and every
 * frame, so drawing allocates nothing; a renderer therefore belongs to one thread (the UI thread).
 * Far creatures are a little fainter, which is the only depth cue besides size.
 */
internal class MeshCreatureRenderer : CreatureRenderer {
    private val pose = SwimPose()
    private val verts = FloatArray(SwimMesh.FLOATS)
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)

    override fun draw(
        canvas: Canvas,
        creature: Creature,
        sprite: Bitmap,
        pxPerUnit: Float,
        waveScale: Float,
        offsetX: Float,
        offsetY: Float,
    ) {
        if (sprite.isRecycled) return
        creature.writePose(pose, pxPerUnit, waveScale, offsetX, offsetY)
        SwimMesh.fill(verts, pose, creature.params.swim)
        paint.alpha = (255 * (0.7f + 0.3f * creature.z)).toInt()
        canvas.drawBitmapMesh(sprite, SwimMesh.COLS, SwimMesh.ROWS, verts, 0, null, 0, paint)
    }
}
