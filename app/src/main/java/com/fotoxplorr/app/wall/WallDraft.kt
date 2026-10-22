package com.fotoxplorr.app.wall

import com.fotoxplorr.app.media.MediaAsset

/** The four steps, in order. [ordinal] is what is saved. */
internal enum class WallStep { WALL, SCALE, PICTURES, HANG }

/**
 * One picture hung on the wall photo. The centre is a share of the wall photo's WIDTH on both axes
 * ([cx] 0 is the left edge, [cy] 0 the top edge, and 1 is a whole photo-width down), so it does not
 * depend on the resolution the photo is drawn at; the size is real, in cm. Recalibrating therefore
 * keeps what you hung where it hangs and changes how big it looks.
 */
internal data class WallFrame(
    val assetId: Long,
    val cx: Float,
    val cy: Float,
    val wCm: Float,
    val hCm: Float,
    val rotationDeg: Float = 0f,
)

/**
 * Everything the person has set up, small enough to survive the process being killed as one string
 * ([encode] / [decode], kept by one `rememberSaveable`).
 *
 * Saved: the step, the wall photo and the pictures (by id), the calibration, the frames and their
 * place, size and turn, the style, mat, and units. NOT saved: decoded pictures (decoded again),
 * the two taps of a reference being marked, text half-typed in a field, and scroll positions.
 */
internal data class WallDraft(
    val step: WallStep = WallStep.WALL,
    val wallId: Long? = null,
    val wallWidthCm: Float = 0f,
    val distanceCm: Float = 0f,
    val kind: SetKind = SetKind.SINGLE,
    val pictures: List<Long> = emptyList(),
    val frames: List<WallFrame> = emptyList(),
    val selected: Int = 0,
    val style: FrameStyle = FrameStyle.THIN_BLACK,
    val matCm: Float = 0f,
    val inches: Boolean = false,
) {
    val isCalibrated: Boolean get() = wallWidthCm > 0f

    /** What each picture's border adds on every side (cm). */
    val borderCm: Float get() = style.moldingCm + matCm

    fun withFrame(index: Int, change: (WallFrame) -> WallFrame): WallDraft =
        if (index !in frames.indices) this else copy(frames = frames.toMutableList().also { it[index] = change(frames[index]) })

    /**
     * Drops what is no longer in [visible] (a photo moved to a locked folder, trashed, or deleted
     * since the draft was saved). A lost wall photo sends the person back to choose another.
     */
    fun reconciled(visible: Set<Long>): WallDraft {
        val wallGone = wallId != null && wallId !in visible
        val kept = frames.filter { it.assetId in visible }
        val next = copy(
            wallId = if (wallGone) null else wallId,
            wallWidthCm = if (wallGone) 0f else wallWidthCm,
            pictures = pictures.filter { it in visible },
            frames = kept,
            selected = selected.coerceIn(0, maxOf(0, kept.lastIndex)),
        )
        return when {
            next.wallId == null -> next.copy(step = WallStep.WALL)
            next.step == WallStep.HANG && next.frames.isEmpty() -> next.copy(step = WallStep.PICTURES)
            else -> next
        }
    }

    /** Hangs [pictures] in the arrangement of [kind], at the biggest size that fits, or nothing if the wall is not calibrated. The step is the caller's. */
    fun arranged(wallHeightCm: Float): WallDraft {
        if (!isCalibrated || pictures.isEmpty()) return copy(frames = emptyList())
        val n = pictures.size.coerceAtMost(kind.capacity)
        val size = SetLayouts.biggestThatFits(kind, n, wallWidthCm, wallHeightCm, borderCm)
        val boxes = SetLayouts.boxes(kind, n, size, borderCm)
        val centreX = wallWidthCm / 2
        val centreY = wallHeightCm * 0.45f
        return copy(
            frames = boxes.mapIndexed { i, box ->
                WallFrame(pictures[i], (centreX + box.dxCm) / wallWidthCm, (centreY + box.dyCm) / wallWidthCm, box.wCm, box.hCm)
            },
            selected = 0,
        )
    }

    fun encode(): String = buildString {
        append("1;").append(step.ordinal).append(';').append(wallId ?: "").append(';')
        append(wallWidthCm).append(';').append(distanceCm).append(';').append(kind.key).append(';')
        append(selected).append(';').append(style.key).append(';').append(matCm).append(';')
        append(if (inches) 1 else 0).append(';').append(pictures.joinToString(","))
        append(';')
        append(frames.joinToString("|") { "${it.assetId},${it.cx},${it.cy},${it.wCm},${it.hCm},${it.rotationDeg}" })
    }

    companion object {
        /** The draft [encode] wrote, or null for anything that is not one (a version this build does not know, a cut-off string). */
        fun decode(text: String): WallDraft? = runCatching {
            val f = text.split(';')
            if (f.size != 12 || f[0] != "1") return null
            WallDraft(
                step = WallStep.entries[f[1].toInt()],
                wallId = f[2].takeIf { it.isNotEmpty() }?.toLong(),
                wallWidthCm = f[3].toFloat(),
                distanceCm = f[4].toFloat(),
                kind = SetKind.fromKey(f[5]) ?: return null,
                selected = f[6].toInt(),
                style = FrameStyle.fromKey(f[7]) ?: return null,
                matCm = f[8].toFloat(),
                inches = f[9] == "1",
                pictures = f[10].split(',').filter { it.isNotEmpty() }.map { it.toLong() },
                frames = f[11].split('|').filter { it.isNotEmpty() }.map {
                    val p = it.split(',')
                    WallFrame(p[0].toLong(), p[1].toFloat(), p[2].toFloat(), p[3].toFloat(), p[4].toFloat(), p[5].toFloat())
                },
            )
        }.getOrNull()
    }
}

/** Stills only: the wall photo and the pictures are photographs, and a video has no print size. */
internal fun List<MediaAsset>.stills(): List<MediaAsset> = filter { !it.isVideo && !it.isTrashed }
