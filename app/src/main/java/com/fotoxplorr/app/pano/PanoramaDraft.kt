package com.fotoxplorr.app.pano

import com.fotoxplorr.app.media.MediaAsset
import com.fotoxplorr.core.model.MediaId

/** The two steps of the panorama screen, in order. The name is what is saved. */
internal enum class PanoramaStep { PICK, ARRANGE }

/**
 * What the person has set up on the panorama screen, small enough to survive the process being
 * killed as one string ([encode] / [decode], kept by one `rememberSaveable`).
 *
 * Saved: the step, the chosen photos by id in the order they sit in the strip, and whether the
 * person has arranged that order by hand. NOT saved: thumbnails, the preview picture, the size
 * estimate, a message about a photo that did not match, and a save in progress (a killed process
 * ends it; nothing half-written is left, see [PanoramaWriter]). All of those come back by pressing
 * Preview or Save again.
 *
 * The order is date taken, then name, until the person moves a photo ([arranged]); after that a newly
 * chosen photo goes to the end and nothing is sorted behind their back until they ask ([sortedByDate]).
 * A sweep is left to right or right to left, so the screen never reorders on its own once told.
 *
 * Every function is pure so a plain JUnit test holds the screen's rules.
 */
internal data class PanoramaDraft(
    val step: PanoramaStep = PanoramaStep.PICK,
    val ids: List<Long> = emptyList(),
    val arranged: Boolean = false,
) {
    /** Whether the chosen photos are a number the job accepts. */
    val canJoin: Boolean get() = ids.size in PanoGeometry.MIN_PHOTOS..PanoGeometry.MAX_PHOTOS

    val isFull: Boolean get() = ids.size >= PanoGeometry.MAX_PHOTOS

    /** Whether Back has a step of its own to take here, before the host closes the experiment. */
    val hasInnerStep: Boolean get() = step != PanoramaStep.PICK

    /** The chosen photos that are still in [byId], in strip order. */
    fun assets(byId: Map<Long, MediaAsset>): List<MediaAsset> = ids.mapNotNull(byId::get)

    /** [asset] chosen if it was not (and there is room), unchosen if it was. */
    fun toggled(asset: MediaAsset, byId: Map<Long, MediaAsset>): PanoramaDraft {
        val id = asset.id.value
        if (id in ids) return copy(ids = ids - id)
        if (isFull) return this
        val added = copy(ids = ids + id)
        return if (arranged) added else added.sortedByDate(byId)
    }

    /** The photo at [index] swapped with its neighbour [by] places away (-1 left, +1 right); unchanged at the ends. */
    fun moved(index: Int, by: Int): PanoramaDraft {
        if (!canMove(index, by)) return this
        val next = ids.toMutableList()
        next[index] = ids[index + by]
        next[index + by] = ids[index]
        return copy(ids = next, arranged = true)
    }

    fun canMove(index: Int, by: Int): Boolean = index in ids.indices && index + by in ids.indices && by != 0

    /** [id] taken out, the rest in the same order. With nothing left there is nothing to arrange, so back to choosing. */
    fun removed(id: Long): PanoramaDraft {
        if (id !in ids) return this
        val left = ids - id
        return copy(ids = left, step = if (left.isEmpty()) PanoramaStep.PICK else step)
    }

    /** The chosen photos in date taken then name order, as if the person had never moved one. */
    fun sortedByDate(byId: Map<Long, MediaAsset>): PanoramaDraft {
        val order = Comparator<Long> { a, b ->
            val x = byId[a]
            val y = byId[b]
            when {
                x == null || y == null -> compareValues(x == null, y == null)
                else -> byDateThenName.compare(x, y)
            }
        }
        return copy(ids = ids.sortedWith(order), arranged = false)
    }

    /**
     * The draft against the photos the gallery offers now: one that was locked, trashed or removed since
     * drops out. An empty [available] is a library still loading as much as an empty one, so it is never
     * taken as "everything is gone" (that would wipe a restored draft).
     */
    fun reconciled(available: Set<Long>): PanoramaDraft {
        if (available.isEmpty()) return this
        val kept = ids.filter { it in available }
        if (kept.size == ids.size) return this
        return copy(ids = kept, step = if (kept.isEmpty()) PanoramaStep.PICK else step)
    }

    /** Back's inner step: from arranging to choosing, with the choice kept. */
    fun backed(): PanoramaDraft = if (step == PanoramaStep.ARRANGE) copy(step = PanoramaStep.PICK) else this

    fun encode(): String = "$FORMAT;${step.name};${if (arranged) 1 else 0};${ids.joinToString(",")}"

    companion object {
        private const val FORMAT = "1"

        private val byDateThenName: Comparator<MediaAsset> =
            compareBy<MediaAsset>({ it.dateTakenMillis }, { it.displayName }, { it.id.value })

        /** Saved text read back. Anything damaged or from another format gives an empty draft, never a crash. */
        fun decode(text: String): PanoramaDraft {
            val parts = text.split(';')
            if (parts.size != 4 || parts[0] != FORMAT) return PanoramaDraft()
            val step = PanoramaStep.entries.firstOrNull { it.name == parts[1] } ?: return PanoramaDraft()
            val arranged = when (parts[2]) {
                "1" -> true
                "0" -> false
                else -> return PanoramaDraft()
            }
            val ids = if (parts[3].isEmpty()) {
                emptyList()
            } else {
                parts[3].split(',').map { it.toLongOrNull() ?: return PanoramaDraft() }
            }
            val held = ids.distinct().take(PanoGeometry.MAX_PHOTOS)
            return PanoramaDraft(if (held.isEmpty()) PanoramaStep.PICK else step, held, arranged)
        }

        /**
         * The library row that is the panorama just saved at [uri], if the gallery's list has it yet. Matched by
         * the row id at the end of the uri (the volume part of a MediaStore uri is spelled several ways), then
         * by the whole string.
         */
        fun savedAsset(assets: List<MediaAsset>, uri: String): MediaAsset? {
            val id = uri.substringAfterLast('/').toLongOrNull()?.let(::MediaId)
            return assets.firstOrNull { (id != null && it.id == id) || (uri.isNotEmpty() && it.contentUriString == uri) }
        }
    }
}

/**
 * A sentence from the job turned into what the strip can act on: the words for the person, and the
 * 1-based photo it is about (so the screen can offer to remove it), or null when it is about the whole set.
 * The job words its failures from [PanoCopy], and this reads them back from the same object, so a
 * change to one is caught by the other's test.
 */
internal class PanoramaProblem(val photo: Int?, val text: String) {
    companion object {
        fun of(message: String): PanoramaProblem {
            for (n in 1..PanoGeometry.MAX_PHOTOS) {
                if (message == PanoCopy.cannotJoin(n)) return PanoramaProblem(n, PanoCopy.noOverlap(n))
                if (message == PanoCopy.cannotRead(n)) return PanoramaProblem(n, message)
            }
            return PanoramaProblem(null, message)
        }
    }
}

/**
 * The size a panorama will be saved at, worked out from the preview so the person sees it before pressing Save.
 *
 * The preview is the same job drawn small; it reports the canvas it drew on, the part of it that was cropped to
 * and the scale it was drawn at. The full spread those imply is put through the same [PanoGeometry.planPlaced] the
 * save will use, so the budget, the editor's ceilings and the never-enlarge rule are the ones that apply. The crop
 * the save finds is made on a finer grid than the preview's, so this is "about", to a few pixels.
 */
internal object PanoramaEstimate {
    fun exportSize(
        canvasWidth: Int,
        canvasHeight: Int,
        cropWidth: Int,
        cropHeight: Int,
        previewScale: Double,
        budget: Long = PanoGeometry.DEFAULT_MAX_PIXELS,
    ): PanoSize? {
        if (canvasWidth <= 0 || canvasHeight <= 0 || cropWidth <= 0 || cropHeight <= 0) return null
        if (!previewScale.isFinite() || previewScale <= 0.0) return null
        val fullWidth = Math.round(canvasWidth / previewScale).coerceAtLeast(1L)
        val fullHeight = Math.round(canvasHeight / previewScale).coerceAtLeast(1L)
        if (fullWidth > Int.MAX_VALUE || fullHeight > Int.MAX_VALUE) return null
        val plan = runCatching {
            PanoGeometry.planPlaced(listOf(PanoSize(fullWidth.toInt(), fullHeight.toInt())), listOf(PanoPoint(0, 0)), budget)
        }.getOrNull() ?: return null
        val factor = plan.scale / previewScale
        return PanoSize(
            Math.round(cropWidth * factor).coerceIn(1L, plan.canvasWidth.toLong()).toInt(),
            Math.round(cropHeight * factor).coerceIn(1L, plan.canvasHeight.toLong()).toInt(),
        )
    }
}
