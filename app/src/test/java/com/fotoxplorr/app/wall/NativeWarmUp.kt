package com.fotoxplorr.app.wall

import android.graphics.Bitmap
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import com.fotoxplorr.app.media.DecodeLimits
import com.fotoxplorr.app.media.decodeUpright
import kotlinx.coroutines.runBlocking
import java.io.FileOutputStream
import kotlin.io.path.createTempFile

/**
 * Runs the app's own decode once, on a worker thread as the app does, before a screen that decodes
 * is composed.
 *
 * Robolectric's native runtime fills its JNI class table the first time native image code runs.
 * When that first use is a worker thread of a freshly started test JVM, racing the main thread
 * still composing, the JVM aborts with "Class not found: java/nio/..." (exit 134, no test result).
 * It was a rare flake in this package's compose tests (seen once while the wall screen was built)
 * and became reliable once the screen also created the app's view model on the main thread; a full
 * run never hits it because an earlier test has done the same thing. Doing it here makes each test
 * class independent of what ran before it. Not a workaround for anything in the app.
 */
internal fun warmUpNativeImageDecoder() {
    val file = createTempFile("fx-warm", ".jpg").toFile().also { it.deleteOnExit() }
    FileOutputStream(file).use { Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888).compress(Bitmap.CompressFormat.JPEG, 80, it) }
    runBlocking { decodeUpright(ApplicationProvider.getApplicationContext(), Uri.fromFile(file), DecodeLimits(64, 4096)) }
}
