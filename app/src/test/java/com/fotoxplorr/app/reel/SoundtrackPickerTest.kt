package com.fotoxplorr.app.reel

import android.app.Activity
import android.content.Intent
import android.net.Uri
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** The sound-file picker is the system's own document screen: one audio file, no permission. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SoundtrackPickerTest {
    private val context = RuntimeEnvironment.getApplication()

    @Test
    fun `it opens the system's document picker for audio files that can be opened`() {
        val intent = PickSoundtrack().createIntent(context, Unit)
        assertEquals(Intent.ACTION_OPEN_DOCUMENT, intent.action)
        assertEquals("audio/*", intent.type)
        assertTrue(intent.hasCategory(Intent.CATEGORY_OPENABLE))
        assertFalse("one file, not a folder of music", intent.getBooleanExtra(Intent.EXTRA_ALLOW_MULTIPLE, false))
    }

    @Test
    fun `it asks to read the file and to keep the right to, nothing more`() {
        val flags = PickSoundtrack().createIntent(context, Unit).flags
        assertTrue(flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
        assertTrue(flags and Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION != 0)
        assertEquals("no write", 0, flags and Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
    }

    @Test
    fun `the answer is the file's address, or null when the person backed out`() {
        val uri = Uri.parse("content://com.android.providers.media.documents/document/audio%3A42")
        val picker = PickSoundtrack()
        assertEquals(uri, picker.parseResult(Activity.RESULT_OK, Intent().setData(uri)))
        assertNull(picker.parseResult(Activity.RESULT_CANCELED, Intent().setData(uri)))
        assertNull(picker.parseResult(Activity.RESULT_OK, null))
        assertNull(picker.parseResult(Activity.RESULT_OK, Intent()))
    }

    @Test
    fun `keeping the right to read a file is a no when the platform refuses it`() {
        val refusing = object : android.content.ContextWrapper(context) {
            override fun getContentResolver(): android.content.ContentResolver = object : android.content.ContentResolver(context) {
                override fun takePersistableUriPermission(uri: Uri, modeFlags: Int) {
                    throw SecurityException("no persistable grant for $uri")
                }
            }
        }
        assertFalse(SoundtrackAccess.keep(refusing, Uri.parse("content://nobody/document/1")))
    }

    @Test
    fun `keeping the right to read a file is a yes when the platform takes it`() {
        assertTrue(SoundtrackAccess.keep(context, Uri.parse("content://com.android.providers.media.documents/document/audio%3A42")))
    }

    @Test
    fun `a name the provider will not give is null, not a crash`() {
        assertNull(SoundtrackAccess.displayName(context, Uri.parse("content://nobody/document/1")))
    }

    @Test
    fun `this app asks for no audio or storage permission`() {
        val info = context.packageManager.getPackageInfo(context.packageName, android.content.pm.PackageManager.GET_PERMISSIONS)
        val asked = info.requestedPermissions?.toList().orEmpty()
        assertFalse(asked.toString(), asked.any { it.contains("READ_MEDIA_AUDIO") })
        assertFalse(asked.toString(), asked.any { it.contains("RECORD_AUDIO") })
    }
}
