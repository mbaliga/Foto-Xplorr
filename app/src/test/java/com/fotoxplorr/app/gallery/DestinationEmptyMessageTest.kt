package com.fotoxplorr.app.gallery

import com.fotoxplorr.app.recognition.RecognitionProgress
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The recognition destinations must not collapse "not started", "found nothing" and "failed"
 * into one vague sentence -- that is exactly what the placeholder they replaced did.
 */
class DestinationEmptyMessageTest {

    private val idle = RecognitionProgress()

    @Test
    fun `a finished pass that found nothing says so per destination`() {
        assertEquals(
            "No cats, dogs or other pets found in your photos",
            destinationEmptyMessage(HyleDestination.PETS, idle),
        )
        assertEquals(
            "No faces found in your photos",
            destinationEmptyMessage(HyleDestination.PEOPLE, idle),
        )
        assertEquals(
            "No identity documents found in your photos",
            destinationEmptyMessage(HyleDestination.IDENTITY, idle),
        )
    }

    @Test
    fun `a running pass reports progress instead of claiming nothing was found`() {
        val running = RecognitionProgress(running = true, completed = 12, total = 400)
        val message = destinationEmptyMessage(HyleDestination.PEOPLE, running)
        assertTrue(message, message.contains("12 of 400"))
        assertTrue(message, !message.contains("No faces"))
    }

    @Test
    fun `a running pass without a total still says it is working`() {
        val running = RecognitionProgress(running = true)
        assertEquals(
            "Looking through your photos on this device…",
            destinationEmptyMessage(HyleDestination.PETS, running),
        )
    }

    @Test
    fun `a failed pass surfaces the failure rather than an empty result`() {
        val failed = RecognitionProgress(message = "out of memory")
        val message = destinationEmptyMessage(HyleDestination.IDENTITY, failed)
        assertTrue(message, message.contains("out of memory"))
        assertTrue(message, !message.contains("No identity documents"))
    }

    @Test
    fun `a failure outranks a still-running flag`() {
        val both = RecognitionProgress(running = true, total = 10, message = "model unavailable")
        val message = destinationEmptyMessage(HyleDestination.PETS, both)
        assertTrue(message, message.contains("model unavailable"))
    }

    @Test
    fun `non-recognition destinations keep the plain message in every state`() {
        listOf(
            HyleDestination.PHOTOS, HyleDestination.VIDEOS, HyleDestination.SCREENSHOTS,
            HyleDestination.FAVOURITES, HyleDestination.PLACES, HyleDestination.PROTECTED,
        ).forEach { destination ->
            assertEquals("Nothing here yet", destinationEmptyMessage(destination, idle))
            assertEquals(
                "Nothing here yet",
                destinationEmptyMessage(destination, RecognitionProgress(running = true, total = 5)),
            )
        }
    }
}

class DestinationEmptyMessageDistinctnessTest {

    /**
     * DestinationContent renders either the grid or this message, never both. TimelineScreen
     * has its own generic "No media matches the current filters" for an empty list, and for a
     * while both were rendered -- stacking two contradictory empty states and pushing the
     * useful one off-screen. If any of these ever collapses into a generic string, the two
     * have been confused again.
     */
    @Test
    fun `recognition destinations never fall back to a generic message`() {
        val states = listOf(
            RecognitionProgress(),
            RecognitionProgress(running = true),
            RecognitionProgress(running = true, completed = 3, total = 9),
            RecognitionProgress(message = "boom"),
        )
        listOf(HyleDestination.PETS, HyleDestination.PEOPLE, HyleDestination.IDENTITY)
            .forEach { destination ->
                states.forEach { progress ->
                    val message = destinationEmptyMessage(destination, progress)
                    assertTrue(message, message.isNotBlank())
                    assertTrue(message, message != "No media matches the current filters")
                    assertTrue(message, message != "Nothing here yet")
                }
            }
    }

    @Test
    fun `each recognition destination says something different when empty`() {
        val messages = listOf(
            HyleDestination.PETS, HyleDestination.PEOPLE, HyleDestination.IDENTITY,
        ).map { destinationEmptyMessage(it, RecognitionProgress()) }
        assertEquals(messages.size, messages.toSet().size)
    }
}

class HyleDestinationTest {

    @Test
    fun `the nine mockup destinations exist in the mockups order`() {
        assertEquals(
            listOf(
                "Pets", "People", "Identity", "Screenshots", "Photos",
                "Videos", "Favourites", "Places", "Protected",
            ),
            HyleDestination.entries.map { it.label },
        )
    }

    @Test
    fun `the default view is Photos`() {
        assertEquals(HyleDestination.PHOTOS, GalleryPreferencesState().defaultDestination)
    }
}
