package com.fotoxplorr.app.sources.drives

import android.content.Context
import android.content.Intent
import android.hardware.usb.UsbManager
import android.net.Uri
import android.provider.DocumentsContract
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** A storage API the test steers: the volumes now, and whether this "Android" has the volume callback. */
private class FakeStorageApi(private val hasCallback: Boolean) : StorageApi {
    var raw: List<RawVolume> = emptyList()
    private var onChange: (() -> Unit)? = null
    var registered = false
    override fun volumes(): List<RawVolume> = raw
    override fun registerCallback(onChange: () -> Unit): (() -> Unit)? {
        if (!hasCallback) return null
        this.onChange = onChange
        registered = true
        return { registered = false; this.onChange = null }
    }
    override fun pickerIntent(volume: DriveVolume): Intent = DrivePicker.genericIntent(volume)
    fun fire() = onChange?.invoke()
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DriveVolumeSourceTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private fun sd(mounted: Boolean = true) = RawVolume("1234-abcd", "SD", removable = true, primary = false, mounted = mounted)
    private val internal = RawVolume(null, "Internal", removable = false, primary = true, mounted = true)

    @Test
    fun `the volume callback reports the first look and every change as snapshots`() {
        val api = FakeStorageApi(hasCallback = true).apply { raw = listOf(internal) }
        val events = ArrayList<DriveEvent>()
        val source = DriveVolumeSource(context, api)
        source.start { events += it }
        assertTrue(api.registered)
        assertEquals(listOf<DriveEvent>(DriveEvent.Snapshot(emptyList())), events)
        api.raw = listOf(internal, sd())
        api.fire()
        api.raw = listOf(internal, sd(mounted = false))
        api.fire()
        assertEquals(
            listOf(DriveEvent.Snapshot(emptyList()), DriveEvent.Snapshot(listOf(DriveVolume("1234-ABCD", "SD"))), DriveEvent.Snapshot(emptyList())),
            events,
        )
        source.stop()
        assertFalse(api.registered)
    }

    @Test
    fun `starting twice registers once and a stopped source hears nothing`() {
        val api = FakeStorageApi(hasCallback = true)
        val events = ArrayList<DriveEvent>()
        val source = DriveVolumeSource(context, api)
        source.start { events += it }
        source.start { events += it }
        assertEquals(1, events.size)
        source.stop()
        api.fire()
        assertEquals(1, events.size)
        assertFalse(source.isRunning)
    }

    @Test
    fun `without the callback the mount and usb broadcasts drive the same snapshots`() {
        val api = FakeStorageApi(hasCallback = false)
        val events = ArrayList<DriveEvent>()
        val source = DriveVolumeSource(context, api)
        source.start { events += it }
        assertFalse(api.registered)
        events.clear()

        api.raw = listOf(sd())
        context.sendBroadcast(Intent(Intent.ACTION_MEDIA_MOUNTED).setData(Uri.parse("file:///storage/1234-ABCD")))
        shadowOf(android.os.Looper.getMainLooper()).idle()
        assertEquals(listOf<DriveEvent>(DriveEvent.Snapshot(listOf(DriveVolume("1234-ABCD", "SD")))), events)

        events.clear()
        api.raw = emptyList()
        context.sendBroadcast(Intent(UsbManager.ACTION_USB_DEVICE_DETACHED))
        shadowOf(android.os.Looper.getMainLooper()).idle()
        assertEquals(listOf<DriveEvent>(DriveEvent.Snapshot(emptyList())), events)

        events.clear()
        api.raw = listOf(sd())
        context.sendBroadcast(Intent(Intent.ACTION_MEDIA_EJECT).setData(Uri.parse("file:///storage/1234-ABCD")))
        shadowOf(android.os.Looper.getMainLooper()).idle()
        source.stop()
        context.sendBroadcast(Intent(Intent.ACTION_MEDIA_UNMOUNTED).setData(Uri.parse("file:///storage/1234-ABCD")))
        shadowOf(android.os.Looper.getMainLooper()).idle()
        assertEquals("one for the eject, none after stop", 1, events.size)
    }

    @Test
    fun `fed into the controller a plug and an unplug end with the card withdrawn and nothing scanned`() {
        val api = FakeStorageApi(hasCallback = true)
        val store = InMemoryDriveSettingsStore()
        val calls = ArrayList<String>()
        val port = object : DriveSourcePort {
            override fun knownVolumes() = kotlinx.coroutines.flow.emptyFlow<Set<String>>()
            override suspend fun setReachable(uuid: String, reachable: Boolean, indexNow: Boolean) { calls += "r $uuid $reachable" }
            override suspend fun stopIndex(uuid: String) { calls += "s $uuid" }
        }
        val controller = DriveController(store, port, kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Unconfined)).also { it.start() }
        val source = DriveVolumeSource(context, api)
        source.start(controller::dispatch)
        api.raw = listOf(sd())
        api.fire()
        assertEquals(listOf(DriveVolume("1234-ABCD", "SD")), controller.offers.value)
        api.raw = emptyList()
        api.fire()
        assertTrue(controller.offers.value.isEmpty())
        assertTrue("no source, so nothing was marked, scanned or saved", calls.isEmpty() && store.all().value.isEmpty())
        source.stop()
    }

    @Test
    fun `the generic picker intent points at the drive root and asks for a tree`() {
        val i = DrivePicker.genericIntent(DriveVolume("1234-ABCD", "SD"))
        assertEquals(Intent.ACTION_OPEN_DOCUMENT_TREE, i.action)
        val initial = i.getParcelableExtra<Uri>(DocumentsContract.EXTRA_INITIAL_URI)
        assertEquals("1234-ABCD:", DocumentsContract.getDocumentId(initial))
        assertNull("it asks for nothing beyond what the picker grants", i.data)
    }
}
