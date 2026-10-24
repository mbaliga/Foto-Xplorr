package com.fotoxplorr.app.openwith

import android.content.Intent
import android.os.Parcel
import android.os.Parcelable
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** A hostile extra must not turn into a crash in whichever activity reads the intent first. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SafeIntentExtrasTest {

    /** Writes fine, but cannot be read back: stands in for a class this app cannot unpack. */
    class Hostile : Parcelable {
        override fun describeContents() = 0
        override fun writeToParcel(dest: Parcel, flags: Int) = dest.writeInt(1)

        companion object {
            @JvmField
            val CREATOR = object : Parcelable.Creator<Hostile> {
                override fun createFromParcel(source: Parcel): Hostile = throw IllegalStateException("hostile")
                override fun newArray(size: Int): Array<Hostile?> = arrayOfNulls(size)
            }
        }
    }

    /** The intent as another process delivers it: packed, and only unpacked when read. */
    private fun delivered(build: Intent.() -> Unit): Intent {
        val parcel = Parcel.obtain()
        try {
            Intent("test.ACTION").apply(build).writeToParcel(parcel, 0)
            parcel.setDataPosition(0)
            return Intent.CREATOR.createFromParcel(parcel)
        } finally {
            parcel.recycle()
        }
    }

    private fun hostileIntent() = delivered {
        putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
        putExtra("com.fotoxplorr.app.EXTRA_OPEN_MEDIA_ID", 42L)
        putExtra("hostile", Hostile())
    }

    @Test
    fun `a hostile extra really does throw when it is read`() {
        // Newer Android unpacks each value only when its own key is read, so it is the hostile
        // key that throws there; older versions threw from any read. Either way it must throw.
        @Suppress("DEPRECATION")
        val precondition = runCatching { hostileIntent().extras!!.get("hostile") }
        assertTrue("the test's hostile extra must be hostile: $precondition", precondition.isFailure)
    }

    private val openId = "com.fotoxplorr.app.EXTRA_OPEN_MEDIA_ID"

    @Test
    fun `a hostile extra under an unknown key is dropped unread and the allowed extras survive`() {
        val intent = hostileIntent().withSafeExtras()

        assertEquals(42L, intent.getLongExtra(openId, -1L))
        assertEquals(true, intent.getBooleanExtra(Intent.EXTRA_ALLOW_MULTIPLE, false))
        assertEquals(setOf(openId, Intent.EXTRA_ALLOW_MULTIPLE), intent.extras!!.keySet())
    }

    @Test
    fun `a hostile value under the stream key is never instantiated and cannot throw later`() {
        val intent = delivered {
            putExtra(openId, 42L)
            putExtra(Intent.EXTRA_STREAM, Hostile())
        }.withSafeExtras()

        // Whatever the typed getter made of it (nothing, on newer Android), a reader sees a plain
        // "no stream" and no exception, now or on a later relaunch.
        @Suppress("DEPRECATION")
        assertNull(runCatching { intent.getParcelableExtra<android.net.Uri>(Intent.EXTRA_STREAM) }.getOrThrow())
        assertEquals(setOf(openId), intent.extras?.keySet().orEmpty())
    }

    @Test
    fun `a hostile element in the stream list is never instantiated and cannot throw later`() {
        val intent = delivered {
            putExtra(openId, 42L)
            putParcelableArrayListExtra(Intent.EXTRA_STREAM, arrayListOf(Hostile()))
        }.withSafeExtras()

        @Suppress("DEPRECATION")
        assertNull(runCatching { intent.getParcelableArrayListExtra<android.net.Uri>(Intent.EXTRA_STREAM) }.getOrThrow())
        assertTrue(intent.extras?.keySet().orEmpty().all { it == openId })
    }

    @Test
    fun `keys the app does not read are dropped even when they are harmless`() {
        val intent = delivered {
            putExtra(openId, 7L)
            putExtra("note", "hello")
            putExtra("id", 42L)
        }.withSafeExtras()

        assertEquals(setOf(openId), intent.extras!!.keySet())
        assertEquals(7L, intent.getLongExtra(openId, -1L))
    }

    @Test
    fun `an intent with only unknown extras ends up with none`() {
        val intent = delivered { putExtra("note", "hello") }.withSafeExtras()

        assertNull(intent.extras)
    }

    @Test
    fun `a stream uri and a list of stream uris are kept`() {
        val one = android.net.Uri.parse("content://media/external/images/media/1")
        val two = android.net.Uri.parse("content://media/external/images/media/2")

        val single = delivered { putExtra(Intent.EXTRA_STREAM, one) }.withSafeExtras()
        @Suppress("DEPRECATION")
        assertEquals(one, single.getParcelableExtra<android.net.Uri>(Intent.EXTRA_STREAM))

        val several = delivered { putParcelableArrayListExtra(Intent.EXTRA_STREAM, arrayListOf(one, two)) }.withSafeExtras()
        @Suppress("DEPRECATION")
        assertEquals(listOf(one, two), several.getParcelableArrayListExtra<android.net.Uri>(Intent.EXTRA_STREAM))
    }

    @Test
    fun `an allowed key holding the wrong type reads as absent`() {
        val intent = delivered {
            putExtra(openId, "not a number")
            putExtra(Intent.EXTRA_ALLOW_MULTIPLE, "yes")
            putExtra(Intent.EXTRA_STREAM, "content://x")
        }.withSafeExtras()

        assertEquals(-1L, intent.getLongExtra(openId, -1L))
        assertEquals(false, intent.getBooleanExtra(Intent.EXTRA_ALLOW_MULTIPLE, false))
        assertNull(intent.extras)
    }

    @Test
    fun `an intent with readable allowed extras is left reading exactly as it did`() {
        val intent = delivered {
            putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
            putExtra(openId, 42L)
        }.withSafeExtras()

        assertEquals(42L, intent.getLongExtra(openId, -1L))
        assertEquals(true, intent.getBooleanExtra(Intent.EXTRA_ALLOW_MULTIPLE, false))
    }

    @Test
    fun `an intent with no extras is fine`() {
        val intent = Intent("test.ACTION").withSafeExtras()

        assertNull(intent.extras)
        assertEquals("test.ACTION", intent.action)
    }

    @Test
    fun `sanitising keeps the data and action of a hostile intent`() {
        val intent = hostileIntent().apply { data = android.net.Uri.parse("content://media/external/images/media/7") }.withSafeExtras()

        assertEquals("content://media/external/images/media/7", intent.data.toString())
        assertEquals("test.ACTION", intent.action)
    }
}
