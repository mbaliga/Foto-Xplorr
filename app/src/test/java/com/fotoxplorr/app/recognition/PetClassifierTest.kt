package com.fotoxplorr.app.recognition

import org.junit.Assert.assertEquals
import org.junit.Test

class PetClassifierTest {

    private fun labels(vararg pairs: Pair<String, Float>) =
        pairs.map { ImageLabel(it.first, it.second) }

    @Test
    fun `no labels is not a pet`() {
        assertEquals(PetVerdict.NONE, PetClassifier.classify(emptyList()))
    }

    @Test
    fun `confident cat label wins`() {
        assertEquals(PetVerdict.CAT, PetClassifier.classify(labels("Cat" to 0.91f)))
    }

    @Test
    fun `confident dog label wins`() {
        assertEquals(PetVerdict.DOG, PetClassifier.classify(labels("Dog" to 0.77f)))
    }

    @Test
    fun `label matching is case and whitespace insensitive`() {
        assertEquals(PetVerdict.CAT, PetClassifier.classify(labels("  KITTEN " to 0.8f)))
    }

    @Test
    fun `higher confidence species decides when both are present`() {
        val verdict = PetClassifier.classify(labels("Cat" to 0.66f, "Dog" to 0.88f))
        assertEquals(PetVerdict.DOG, verdict)
    }

    @Test
    fun `weak species label alone is rejected`() {
        assertEquals(PetVerdict.NONE, PetClassifier.classify(labels("Dog" to 0.58f)))
    }

    @Test
    fun `weak species label plus generic animal is accepted`() {
        val verdict = PetClassifier.classify(labels("Dog" to 0.58f, "Animal" to 0.7f))
        assertEquals(PetVerdict.DOG, verdict)
    }

    @Test
    fun `fur alone is not a pet`() {
        assertEquals(PetVerdict.NONE, PetClassifier.classify(labels("Fur" to 0.93f)))
    }

    @Test
    fun `fur plus animal reads as some pet`() {
        val verdict = PetClassifier.classify(labels("Fur" to 0.72f, "Animal" to 0.69f))
        assertEquals(PetVerdict.OTHER_PET, verdict)
    }

    @Test
    fun `other companion animals are recognised`() {
        assertEquals(PetVerdict.OTHER_PET, PetClassifier.classify(labels("Rabbit" to 0.7f)))
        assertEquals(PetVerdict.OTHER_PET, PetClassifier.classify(labels("Parrot" to 0.65f)))
    }

    @Test
    fun `unrelated confident labels stay out of pets`() {
        val verdict = PetClassifier.classify(
            labels("Food" to 0.95f, "Table" to 0.9f, "Plant" to 0.8f),
        )
        assertEquals(PetVerdict.NONE, verdict)
    }

    @Test
    fun `isPet reflects the verdict`() {
        assertEquals(false, PetVerdict.NONE.isPet)
        assertEquals(true, PetVerdict.CAT.isPet)
        assertEquals(true, PetVerdict.OTHER_PET.isPet)
    }
}
