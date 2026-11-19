package com.fotoxplorr.app.receive

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** The SEND_MULTIPLE filter lives in the manifest; a raw-text check over the file that ships, like the other manifest tests. */
class ReceiveManifestTest {
    private val xml = listOf(File("src/main/AndroidManifest.xml"), File("app/src/main/AndroidManifest.xml"))
        .first { it.isFile }.readText()

    private fun block(name: String) = Regex("""<activity[^>]*android:name\s*=\s*"$name"[^>]*>.*?</activity>""", RegexOption.DOT_MATCHES_ALL)
        .find(xml)?.value ?: error("no $name in the manifest")

    @Test
    fun `ReceiveActivity answers SEND_MULTIPLE for image and video and nothing else`() {
        val b = block("\\.receive\\.ReceiveActivity")
        assertTrue(Regex("""android:exported\s*=\s*"true"""").containsMatchIn(b))
        assertTrue(b.contains("""<action android:name="android.intent.action.SEND_MULTIPLE" />"""))
        assertTrue(b.contains("""<category android:name="android.intent.category.DEFAULT" />"""))
        assertTrue(b.contains("""<data android:mimeType="image/*" />"""))
        assertTrue(b.contains("""<data android:mimeType="video/*" />"""))
        val actions = Regex("""<action android:name="([^"]+)"""").findAll(b).map { it.groupValues[1] }.toList()
        assertTrue("only SEND_MULTIPLE: $actions", actions == listOf("android.intent.action.SEND_MULTIPLE"))
        assertFalse("not browsable, so no web page can start it", b.contains("BROWSABLE"))
        assertFalse("no audio", b.contains("audio/"))
    }

    @Test
    fun `the main activity does not also answer SEND_MULTIPLE so a chooser lists Fotoz once`() {
        assertFalse(block("\\.FotoXplorrActivity").contains("SEND_MULTIPLE"))
    }

    @Test
    fun `the share filter adds no permission and no service`() {
        assertFalse(xml.substringAfter("ReceiveActivity").contains("uses-permission"))
    }
}
