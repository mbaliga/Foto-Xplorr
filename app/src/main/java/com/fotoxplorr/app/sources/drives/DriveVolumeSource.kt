package com.fotoxplorr.app.sources.drives

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.storage.StorageManager
import android.os.storage.StorageVolume
import android.provider.DocumentsContract
import androidx.core.content.ContextCompat

/**
 * What the platform can tell about storage, behind a seam a test can fake. The production one is
 * [SystemStorageApi]; every Android-version question lives there, where lint can see the guards.
 */
interface StorageApi {
    /** Every volume the platform lists, mounted or not. */
    fun volumes(): List<RawVolume>

    /**
     * Calls [onChange] whenever any volume's state changes, and returns the way to stop. Null when
     * this Android has no such callback (before Android 11), so the caller falls back to broadcasts.
     */
    fun registerCallback(onChange: () -> Unit): (() -> Unit)?

    /** The folder picker already pointed at the drive; the person still chooses and grants. */
    fun pickerIntent(volume: DriveVolume): Intent
}

/** Turns "something changed in storage" into [DriveEvent.Snapshot]s. Nothing is read from a drive. */
class DriveVolumeSource(
    private val context: Context,
    private val api: StorageApi = SystemStorageApi(context),
) {
    private var stop: (() -> Unit)? = null

    val isRunning: Boolean get() = stop != null

    /** Reports the current drives once, then every change. Calling it twice without [stop] does nothing. */
    fun start(report: (DriveEvent) -> Unit) {
        if (stop != null) return
        val push = { report(DriveEvent.Snapshot(DriveVolumes.fromRaw(api.volumes()))) }
        val unregister = api.registerCallback(push)
        stop = unregister ?: registerBroadcasts(push)
        push()
    }

    fun stop() {
        stop?.invoke()
        stop = null
    }

    fun pickerIntent(volume: DriveVolume): Intent = api.pickerIntent(volume)

    /**
     * Before Android 11: the mount broadcasts, plus USB attach and detach (a drive pulled from a
     * cable is gone before the mount broadcast can say so). Each one only asks for a fresh snapshot.
     */
    private fun registerBroadcasts(push: () -> Unit): () -> Unit {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) = push()
        }
        val media = IntentFilter().apply {
            addAction(Intent.ACTION_MEDIA_MOUNTED)
            addAction(Intent.ACTION_MEDIA_UNMOUNTED)
            addAction(Intent.ACTION_MEDIA_EJECT)
            addAction(Intent.ACTION_MEDIA_BAD_REMOVAL)
            addAction(Intent.ACTION_MEDIA_REMOVED)
            addDataScheme("file")
        }
        val usb = IntentFilter().apply {
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
        }
        val app = context.applicationContext
        ContextCompat.registerReceiver(app, receiver, media, ContextCompat.RECEIVER_NOT_EXPORTED)
        ContextCompat.registerReceiver(app, receiver, usb, ContextCompat.RECEIVER_NOT_EXPORTED)
        return { runCatching { app.unregisterReceiver(receiver) } }
    }
}

class SystemStorageApi(context: Context) : StorageApi {
    private val app: Context = context.applicationContext
    private val manager = app.getSystemService(Context.STORAGE_SERVICE) as StorageManager
    private val executor = ContextCompat.getMainExecutor(app)

    override fun volumes(): List<RawVolume> = manager.storageVolumes.map { v ->
        RawVolume(
            uuid = v.uuid,
            description = runCatching { v.getDescription(app) }.getOrNull().orEmpty(),
            removable = v.isRemovable,
            primary = v.isPrimary,
            mounted = v.state == Environment.MEDIA_MOUNTED || v.state == Environment.MEDIA_MOUNTED_READ_ONLY,
        )
    }

    override fun registerCallback(onChange: () -> Unit): (() -> Unit)? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null
        val callback = object : StorageManager.StorageVolumeCallback() {
            override fun onStateChanged(volume: StorageVolume) = onChange()
        }
        manager.registerStorageVolumeCallback(executor, callback)
        return { manager.unregisterStorageVolumeCallback(callback) }
    }

    override fun pickerIntent(volume: DriveVolume): Intent {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val platform = manager.storageVolumes.firstOrNull { DriveIds.normalise(it.uuid) == volume.uuid }
            platform?.createOpenDocumentTreeIntent()?.let { return it }
        }
        return DrivePicker.genericIntent(volume)
    }
}

/** The picker intent that works on every Android this app runs on. */
object DrivePicker {
    const val EXTERNAL_STORAGE_AUTHORITY = "com.android.externalstorage.documents"

    /** `ACTION_OPEN_DOCUMENT_TREE` that starts at the drive's root (a hint the picker may ignore). */
    fun genericIntent(volume: DriveVolume): Intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
        putExtra(DocumentsContract.EXTRA_INITIAL_URI, rootUri(volume.uuid))
    }

    fun rootUri(uuid: String): Uri = DocumentsContract.buildDocumentUri(EXTERNAL_STORAGE_AUTHORITY, "$uuid:")
}
