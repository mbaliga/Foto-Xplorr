package com.fotoxplorr.app.chroma

/** Which of the three steps of the flow the person is on. */
internal enum class ChromaStep(val number: Int) {
    /** Optional: place a white card and measure it. */
    CARD(1),

    /** Hold the phone near the surface, keep still, measure. */
    AIM(2),

    /** The colour is shown. */
    RESULT(3),
    ;

    companion object {
        /**
         * The step for [state]: the result once there is one ([resultShown]); the card step while a card is
         * wanted and not yet measured (a phone with no flash cannot take one); the aiming step otherwise.
         */
        fun of(state: CaptureState, useCard: Boolean, cardKept: Boolean, resultShown: Boolean): ChromaStep = when {
            resultShown -> RESULT
            state.cameraFacts?.hasFlash == false -> AIM
            useCard && !cardKept -> CARD
            else -> AIM
        }
    }
}

/**
 * What the result card shows for one colour: the report (swatch, words, numbers, nearest names), how
 * the reading was made, and the extra things the measurement admitted. All of it is derived from the
 * saved colour, so a reading looks the same when it is shown again from the history.
 *
 * A reading whose [kind] does not measure lightness ([ReadingKind.measuresLightness]) shows no lightness
 * word and no lightness number, and says so.
 *
 * @property notes the cautions of the live measurement ("assumes the same distance"), preceded by the
 *   lightness note where that applies; for a reading shown again later it is the lightness note alone (or
 *   empty), because the history keeps the colour and how it was made, not the cautions.
 */
internal class ReadingView(val report: ColourReport, val kind: ReadingKind, val notes: List<String>) {
    /** One line on how far to trust it; never colour alone. */
    val accuracy: String
        get() = when (kind) {
            ReadingKind.APPROXIMATE -> ChromaCopy.ACCURACY_APPROXIMATE
            ReadingKind.CALIBRATED -> ChromaCopy.ACCURACY_CALIBRATED
            ReadingKind.ROOM -> ChromaCopy.AMBIENT_NOTE
        }

    val hex: String get() = report.hex
    val rgbLine: String get() = ChromaCopy.rgbLine(report.red, report.green, report.blue)
    val labLine: String
        get() = if (kind.measuresLightness) {
            ChromaCopy.labLine(report.lab.l, report.lab.a, report.lab.b)
        } else {
            ChromaCopy.labLineNoLightness(report.lab.a, report.lab.b)
        }
    val lchLine: String
        get() = if (kind.measuresLightness) {
            ChromaCopy.lchLine(report.lch.l, report.lch.c, report.lch.h)
        } else {
            ChromaCopy.hueLine(report.lch.h)
        }

    /** The nearest names, each with its CIEDE2000 distance. */
    val nearestLines: List<String> get() = report.nearest.map { ChromaCopy.nearest(it.name, it.deltaE) }

    /** What a screen reader says for the swatch: the words and the hex, so the colour is never only seen. */
    val swatchDescription: String get() = "${report.description}, ${report.hex}"

    companion object {
        /**
         * The view of [saved]. When [outcome] is the live measurement of that same colour its cautions
         * come with it; for any other (or no) outcome there are none.
         */
        fun of(saved: SavedReading, outcome: Outcome? = null): ReadingView {
            val live = when {
                outcome is Outcome.Reading && outcome.reading.report.argb == saved.argb ->
                    outcome.reading.measurement.cautions.map { it.message }
                else -> emptyList()
            }
            val notes = if (saved.kind.measuresLightness) live else (listOf(ChromaCopy.LIGHTNESS_NOT_MEASURED) + live).distinct()
            return ReadingView(ColourReport.of(saved.argb, lightnessMeasured = saved.kind.measuresLightness), saved.kind, notes)
        }

        /** The colour to keep for a finished measurement, or null when [outcome] is not a colour (a refusal, a card kept). */
        fun savedFrom(outcome: Outcome?, atMillis: Long): SavedReading? = when (outcome) {
            is Outcome.Reading -> SavedReading(
                outcome.reading.report.argb,
                if (outcome.withCard) ReadingKind.CALIBRATED else ReadingKind.APPROXIMATE,
                atMillis,
            )
            is Outcome.RoomLight -> SavedReading(outcome.report.argb, ReadingKind.ROOM, atMillis)
            else -> null
        }
    }
}

/** Two readings side by side and what CIEDE2000 says of them. */
internal class CompareView(val first: ReadingView, val second: ReadingView, val verdict: SwatchVerdict) {
    /**
     * True when either is not a card-corrected colour; the verdict then leaves lightness out of the
     * comparison and cannot claim a fine difference.
     */
    val eitherApproximate: Boolean get() = first.kind != ReadingKind.CALIBRATED || second.kind != ReadingKind.CALIBRATED

    val differenceLine: String get() = ChromaCopy.differenceLine(verdict.deltaE)

    companion object {
        fun of(a: SavedReading, b: SavedReading): CompareView {
            val va = ReadingView.of(a)
            val vb = ReadingView.of(b)
            val approx = !a.kind.measuresLightness || !b.kind.measuresLightness
            return CompareView(
                va,
                vb,
                SwatchCompare.compare(va.report.lab, vb.report.lab, eitherApproximate = approx, ignoreLightness = approx),
            )
        }

        /** The readings that can be compared with [subject]: every other one, newest first. */
        fun candidates(subject: SavedReading, history: List<SavedReading>): List<SavedReading> =
            history.filter { it != subject }.sortedByDescending { it.atMillis }
    }
}
