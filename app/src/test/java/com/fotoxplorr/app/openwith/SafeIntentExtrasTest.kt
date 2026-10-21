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

    @Test
    fun `after sanitising the extras are dropped and every read answers its default`() {
        val intent = hostileIntent().withSafeExtras()

        assertEquals(-1L, intent.getLongExtra("com.fotoxplorr.app.EXTRA_OPEN_MEDIA_ID", -1L))
        assertEquals(false, intent.getBooleanExtra(Intent.EXTRA_ALLOW_MULTIPLE, false))
        assertNull(intent.extras)
    }

    @Test
    fun `an intent with readable extras is left exactly as it was`() {
        val intent = delivered {
            putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
            putExtra("id", 42L)
        }.withSafeExtras()

        assertEquals(42L, intent.getLongExtra("id", -1L))
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
