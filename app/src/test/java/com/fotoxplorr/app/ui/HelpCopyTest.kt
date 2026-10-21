package com.fotoxplorr.app.ui

import com.fotoxplorr.app.BuildConfig
import java.io.File
import java.lang.reflect.Modifier
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Holds [HelpCopy] to its brief (owner feedback #6, "the help text is too verbose"): every string
 * has a length ceiling for its kind, none is blank, none is jargon, and the sentences that make a
 * privacy or data-safety claim still make it. The ceilings are the point: a new caption that does
 * not fit is a reason to shorten it, not to raise the number.
 */
class HelpCopyTest {

    /** Every `const val String` declared directly on [holder], by name. */
    private fun constants(holder: Class<*>): Map<String, String> =
        holder.declaredFields
            .filter { Modifier.isStatic(it.modifiers) && it.type == String::class.java }
            .associate { it.name to (it.also { f -> f.isAccessible = true }.get(null) as String) }

    private val captions get() = constants(HelpCopy.Caption::class.java)
    private val hints get() = constants(HelpCopy.Hint::class.java)
    private val notes get() = constants(HelpCopy.Note::class.java)

    @Test
    fun `reflection really finds the constants`() {
        // Guards the guards: an empty map would make every ceiling below pass for nothing.
        assertTrue("captions: ${captions.size}", captions.size >= 30)
        assertTrue("hints: ${hints.size}", hints.size >= 60)
        assertTrue("notes: ${notes.size}", notes.size >= 8)
    }

    @Test
    fun `no string is blank`() {
        (captions + hints + notes).forEach { (name, text) ->
            assertTrue("$name is blank", text.isNotBlank())
            assertEquals("$name has stray outer whitespace", text.trim(), text)
        }
    }

    @Test
    fun `captions stay at or under 60 characters`() {
        captions.forEach { (name, text) ->
            assertTrue("$name is ${text.length} characters: $text", text.length <= CAPTION_MAX)
        }
    }

    @Test
    fun `hints stay at or under 80 characters`() {
        hints.forEach { (name, text) ->
            assertTrue("$name is ${text.length} characters: $text", text.length <= HINT_MAX)
        }
    }

    @Test
    fun `notes stay at or under 160 characters`() {
        notes.forEach { (name, text) ->
            assertTrue("$name is ${text.length} characters: $text", text.length <= NOTE_MAX)
        }
    }

    @Test
    fun `no string uses a double hyphen or a technical word`() {
        val jargon = listOf(
            "embedding", "tflite", "mediapipe", "hillshade", "gyro", "taxonomy", "semantic",
            "opengl", "unmetered", "authorization", "geotagged", "metadata",
        )
        (captions + hints + notes).forEach { (name, text) ->
            assertTrue("$name uses a double hyphen: $text", !text.contains("--"))
            jargon.forEach { word ->
                assertTrue("$name uses \"$word\": $text", !text.contains(word, ignoreCase = true))
            }
        }
    }

    @Test
    fun `the sentences that make a privacy claim still make it`() {
        // Trimmed, not softened: each of these is a fact about what leaves the device or what
        // cannot be undone, and shortening the words must not shorten the claim.
        assertTrue(HelpCopy.Note.OFFLINE_STATEMENT.contains("INTERNET"))
        assertTrue(HelpCopy.Note.OFFLINE_STATEMENT.contains("no INTERNET permission"))
        assertTrue(HelpCopy.Note.CONNECT_STATEMENT.contains("only for features that need it"))
        assertTrue(HelpCopy.Hint.READS_GPS_LOCALLY.contains("on this device"))
        assertTrue(HelpCopy.Hint.READS_GPS_LOCALLY.contains("Nothing is uploaded"))
        assertTrue(HelpCopy.Hint.PERMISSION_ASK.contains("Nothing is uploaded"))
        assertTrue(HelpCopy.Hint.LOCATION_ONLY_HERE.contains("Nothing leaves the device"))
        assertTrue(HelpCopy.Hint.SIMILARITY_DOWNLOAD.contains("stay on this device"))
        assertTrue(HelpCopy.Note.SIMILARITY_OFFLINE.contains("stay on this device"))
        assertTrue(HelpCopy.Hint.AI_FIND_LOOKALIKES.contains("on this device"))
        assertTrue(HelpCopy.Hint.BILLING.contains("Nothing is charged yet"))
        assertTrue(HelpCopy.Hint.BILLING.contains("on this device"))
        assertTrue(HelpCopy.Hint.PROTECTED_FOLDERS.contains("Passwords are never stored"))
        assertTrue(HelpCopy.Hint.STRIP_METADATA.contains("On by default"))
        assertTrue(HelpCopy.Hint.STRIP_METADATA.contains("GPS"))
        assertTrue(HelpCopy.Caption.SAVE_REPLACE.contains("can't be recovered"))
        assertTrue(HelpCopy.Caption.TAGS_SUGGESTED.contains("permanent"))
        // The plain tags-and-collections file: not photos, and not encrypted. "Not your photos" is
        // said only about this option; the encrypted backup's own copy says the opposite.
        assertTrue(HelpCopy.Hint.BACKUP.contains("Not your photos"))
        assertTrue(HelpCopy.Hint.BACKUP_NOT_ENCRYPTED.startsWith("Not encrypted"))
        assertTrue(HelpCopy.Hint.BACKUP_NOT_ENCRYPTED.contains("can read your captions and tags"))
        assertTrue(HelpCopy.Hint.BACKUP_RESTORE.contains("Replacing needs a second confirmation"))
    }

    @Test
    fun `protected folders are said to be an access lock and not encryption`() {
        // The only in-app statement of the README's rule that private folders are an access gate:
        // other apps with photo permission can still read the same files. It was trimmed away once.
        val note = HelpCopy.Note.PROTECTED_NOT_ENCRYPTED
        assertTrue(note, note.contains("lock access inside Fotoz"))
        assertTrue(note, note.contains("do not encrypt your files"))
        // ...and the destination card carries the short form of it next to the count.
        assertEquals("3 protected · in-app lock only", HelpCopy.protectedCount(3))
        assertEquals("0 protected · in-app lock only", HelpCopy.protectedCount(0))
    }

    @Test
    fun `the places paragraph in the offline build says nothing here goes online`() {
        val places = HelpCopy.placesPrivacy(networkFeatures = false)
        assertTrue(places, places.contains("runs on this device"))
        assertTrue(places, places.contains("goes online"))
        assertTrue(places, places.contains("uploads your photos"))
        // The offline build has no network at all, so it must not name anything that can use it.
        assertFalse(places, places.contains("AI services"))
        assertFalse(places, places.contains("Street map"))
        assertFalse(places, places.contains("download"))
        assertTrue(places, places.length <= NOTE_MAX)
    }

    @Test
    fun `the places paragraph in the connect build lists everything that uses the network`() {
        val places = HelpCopy.placesPrivacy(networkFeatures = true)
        assertTrue(places, places.contains("Nothing here uploads your photos"))
        assertTrue(places, places.contains("Street map tiles"))
        assertTrue(places, places.contains("one-time similarity model download"))
        assertTrue(places, places.contains("AI services you add"))
        assertTrue(places, places.length <= NOTE_MAX)
        // Not the exhaustive-sounding "Only the Street map and AI services" it replaced.
        assertFalse(places, places.contains("Connect build"))
    }

    @Test
    fun `the places paragraph follows the flavour this build was compiled as`() {
        assertEquals(
            HelpCopy.placesPrivacy(BuildConfig.NETWORK_FEATURES),
            if (BuildConfig.NETWORK_FEATURES) HelpCopy.Note.PLACES_PRIVACY_CONNECT else HelpCopy.Note.PLACES_PRIVACY_OFFLINE,
        )
        if (!BuildConfig.NETWORK_FEATURES) {
            // The Offline build also says remote AI is unavailable; the paragraph must not contradict it.
            assertFalse(HelpCopy.placesPrivacy(BuildConfig.NETWORK_FEATURES).contains("AI services"))
        }
    }

    @Test
    fun `the places paragraphs keep every boundary they state`() {
        assertTrue(HelpCopy.Note.PLACES_LOCATION.contains("only by Spatial compass"))

        val ai = HelpCopy.Note.AI_SERVICE
        assertTrue(ai, ai.contains("Nothing is sent until you turn it on and ask"))
        assertTrue(ai, ai.contains("encrypted"))
        assertTrue(ai, ai.contains("on this device"))

        val key = HelpCopy.Note.KEY_PRIVACY
        assertTrue(key, key.contains("encrypted"))
        assertTrue(key, key.contains("only to the address below"))
        assertTrue(key, key.contains("only when used"))
    }

    @Test
    fun `composed strings obey the ceiling of the group they sit in`() {
        assertTrue(HelpCopy.Note.KEY_PRIVACY_KEEP, HelpCopy.Note.KEY_PRIVACY_KEEP.length <= NOTE_MAX)
        assertTrue(HelpCopy.Hint.ADD_BY_HAND, HelpCopy.Hint.ADD_BY_HAND.length <= HINT_MAX)
        assertTrue(HelpCopy.Hint.NO_LOCATION_IN_PHOTO.length <= HINT_MAX)
        assertTrue(HelpCopy.Hint.NO_PHOTO_LOCATION.length <= HINT_MAX)
        val tooLarge = HelpCopy.savedCopyTooLarge(" at reduced size (4000 × 3000)")
        assertEquals(
            "Saved a copy at reduced size (4000 × 3000). The original is too large to replace.",
            tooLarge,
        )
        assertEquals("Saved a copy. This format can't be replaced.", HelpCopy.savedCopyFormat())
        assertEquals("Overnight, 22:00 to 06:00.", HelpCopy.overnight("22:00", "06:00"))
    }

    @Test
    fun `no caption literal in the app is long or built from several pieces`() {
        // The constants above cannot see a caption typed straight into a composable. Scan for the
        // two ways one creeps back: a literal over the caption ceiling, or one glued together over
        // several lines.
        val root = File("src/main/java/com/fotoxplorr/app")
        assertTrue("run from the app module: ${root.absolutePath}", root.isDirectory)
        val literal = Regex("""\bcaption = "((?:[^"\\]|\\.)*)"(\s*\+)?\s*$""")
        val offenders = root.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .flatMap { file ->
                file.readLines().mapIndexedNotNull { index, line ->
                    val match = literal.find(line.trimEnd().trimEnd(',')) ?: return@mapIndexedNotNull null
                    val text = match.groupValues[1]
                    val glued = match.groupValues[2].isNotEmpty()
                    if (glued || text.length > CAPTION_MAX) "${file.name}:${index + 1} $text" else null
                }
            }
            .toList()
        assertTrue("captions over $CAPTION_MAX characters or glued together:\n" + offenders.joinToString("\n"), offenders.isEmpty())
    }

    private companion object {
        const val CAPTION_MAX = 60
        const val HINT_MAX = 80
        const val NOTE_MAX = 160
    }
}
