package com.fotoxplorr.app.shell

import android.graphics.Bitmap
import androidx.lifecycle.SavedStateHandle
import com.fotoxplorr.app.media.MediaAsset
import com.fotoxplorr.app.openwith.mediaStoreRowAsset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The replace-the-original state machine as the editor drives it: a request, the write in flight,
 * then an outcome. Nothing may close the editor or point it elsewhere while the original is being
 * written or is damaged, and an outcome that no longer belongs to the open editor changes nothing.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34])
class EditorOverwriteStateMachineTest {

    private fun photo(id: Long): MediaAsset = mediaStoreRowAsset(
        rowId = id,
        contentUri = "content://media/external/images/media/$id",
        displayName = "IMG_$id.jpg",
        mimeType = "image/jpeg",
        bucketId = 1L,
        bucketName = "Camera",
        relativePath = "DCIM/Camera/",
        isTrashed = false,
    )

    private fun bitmap() = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888)

    private fun editorOn(asset: MediaAsset): AppStateViewModel =
        AppStateViewModel(SavedStateHandle()).also { it.setEditingAsset(asset) }

    private val safeFailure = OverwriteFailure("Android did not allow replacing the original photo.", originalAtRisk = false)
    private val damagedFailure = OverwriteFailure("The original may be damaged.", originalAtRisk = true)

    // ---- 1: the write itself ----

    @Test
    fun `Back and close do nothing while the replace is in flight`() {
        val a = photo(1)
        val vm = editorOn(a)

        assertTrue(vm.beginOverwrite(a, bitmap()))

        assertTrue(vm.overwriteInFlight)
        assertTrue(vm.editorCloseBlocked)
        assertFalse(vm.closeEditor())
        assertSame(a, vm.editingAsset)
        assertSame(a, vm.pendingOverwriteAsset)
        assertTrue("the bitmap is still held", vm.pendingOverwriteBitmap != null)
    }

    @Test
    fun `a write that fails after truncation while Back was pressed still lands on the banner`() {
        val a = photo(1)
        val vm = editorOn(a)
        vm.beginOverwrite(a, bitmap())

        assertFalse(vm.closeEditor())
        assertTrue(vm.recordOverwriteFailure(damagedFailure))

        assertSame(a, vm.editingAsset)
        assertTrue(vm.overwriteDeclined)
        assertTrue(vm.overwriteOriginalAtRisk)
        assertEquals("The original may be damaged.", vm.overwriteFailureMessage)
        assertFalse("not in flight any more, but still blocked as at risk", vm.overwriteInFlight)
        assertTrue(vm.editorCloseBlocked)
        assertFalse(vm.closeEditor())
    }

    @Test
    fun `a failure that left the original safe unblocks Back and keeps the banner`() {
        val a = photo(1)
        val vm = editorOn(a)
        vm.beginOverwrite(a, bitmap())

        vm.recordOverwriteFailure(safeFailure)

        assertTrue(vm.overwriteDeclined)
        assertFalse(vm.editorCloseBlocked)
        assertTrue(vm.closeEditor())
        assertNull(vm.pendingOverwriteAsset)
    }

    @Test
    fun `a success closes the editor and forgets everything`() {
        val a = photo(1)
        val vm = editorOn(a)
        vm.beginOverwrite(a, bitmap())

        assertTrue(vm.finishOverwriteSucceeded())

        assertNull(vm.editingAsset)
        assertNull(vm.editingAssetId)
        assertNull(vm.pendingOverwriteAsset)
        assertNull(vm.pendingOverwriteBitmap)
        assertFalse(vm.overwriteDeclined)
        assertFalse(vm.editorCloseBlocked)
    }

    @Test
    fun `a second request while one is in flight is refused`() {
        val a = photo(1)
        val vm = editorOn(a)
        val first = bitmap()
        vm.beginOverwrite(a, first)

        assertFalse(vm.beginOverwrite(a, bitmap()))

        assertSame(first, vm.pendingOverwriteBitmap)
    }

    @Test
    fun `a request for a photo the editor is not open on is refused`() {
        val vm = editorOn(photo(1))

        assertFalse(vm.beginOverwrite(photo(2), bitmap()))

        assertNull(vm.pendingOverwriteAsset)
        assertFalse(vm.editorCloseBlocked)
    }

    // ---- stale outcomes ----

    @Test
    fun `an outcome with nothing pending changes nothing`() {
        val vm = editorOn(photo(1))

        assertFalse(vm.recordOverwriteFailure(damagedFailure))
        assertFalse(vm.finishOverwriteSucceeded())

        assertFalse(vm.overwriteDeclined)
        assertFalse(vm.overwriteOriginalAtRisk)
        assertNull(vm.overwriteFailureMessage)
        assertEquals(photo(1).id, vm.editingAsset?.id)
    }

    @Test
    fun `a late failure from one editor does not show its banner over another editor`() {
        val a = photo(1)
        val b = photo(2)
        val vm = editorOn(a)
        vm.beginOverwrite(a, bitmap())
        // The request was abandoned (state cleared) and the editor moved on to B.
        vm.clearOverwriteFailure()
        vm.setEditingAsset(b)

        assertFalse(vm.recordOverwriteFailure(damagedFailure))

        assertSame(b, vm.editingAsset)
        assertFalse(vm.overwriteDeclined)
        assertFalse(vm.editorCloseBlocked)
    }

    @Test
    fun `an outcome for a different photo than the open one is ignored even if state is still held`() {
        val vm = editorOn(photo(2))
        vm.pendingOverwriteAsset = photo(1)
        vm.pendingOverwriteBitmap = bitmap()

        assertFalse(vm.overwriteOutcomeApplies())
        assertFalse(vm.recordOverwriteFailure(damagedFailure))
        assertFalse(vm.finishOverwriteSucceeded())

        assertEquals(photo(2).id, vm.editingAsset?.id)
        assertFalse(vm.overwriteDeclined)
    }

    // ---- 3: another photo arriving ----

    @Test
    fun `another photo is refused while a replace is in flight and the edit survives`() {
        val a = photo(1)
        val vm = editorOn(a)
        vm.beginOverwrite(a, bitmap())

        assertFalse(vm.setEditingAsset(photo(2)))

        assertSame(a, vm.editingAsset)
        assertSame(a, vm.pendingOverwriteAsset)
        assertTrue(vm.pendingOverwriteBitmap != null)
    }

    @Test
    fun `the same photo again is accepted while a replace is in flight`() {
        val a = photo(1)
        val vm = editorOn(a)
        vm.beginOverwrite(a, bitmap())

        assertTrue(vm.setEditingAsset(a))

        assertTrue(vm.overwriteInFlight)
    }

    @Test
    fun `another photo opens freely when nothing is in flight or at risk`() {
        val vm = editorOn(photo(1))

        assertTrue(vm.setEditingAsset(photo(2)))
        assertEquals(photo(2).id, vm.editingAsset?.id)
    }

    // ---- 4: the at-risk flag is sticky ----

    @Test
    fun `a retry that Android then refuses does not make a damaged original look safe`() {
        val a = photo(1)
        val vm = editorOn(a)
        vm.beginOverwrite(a, bitmap())
        vm.recordOverwriteFailure(damagedFailure)

        assertTrue(vm.beginOverwrite(a, bitmap()))
        assertTrue("still at risk while the retry runs", vm.overwriteOriginalAtRisk)
        vm.recordOverwriteFailure(safeFailure)

        assertTrue(vm.overwriteOriginalAtRisk)
        assertTrue(vm.editorCloseBlocked)
        assertFalse(vm.closeEditor())
    }

    @Test
    fun `a retry that succeeds clears the risk with everything else`() {
        val a = photo(1)
        val vm = editorOn(a)
        vm.beginOverwrite(a, bitmap())
        vm.recordOverwriteFailure(damagedFailure)
        vm.beginOverwrite(a, bitmap())

        assertTrue(vm.finishOverwriteSucceeded())

        assertFalse(vm.overwriteOriginalAtRisk)
        assertNull(vm.editingAsset)
    }

    // ---- 2: save a copy ----

    private fun failedAtRisk(): Pair<AppStateViewModel, MediaAsset> {
        val a = photo(1)
        val vm = editorOn(a)
        vm.beginOverwrite(a, bitmap())
        vm.recordOverwriteFailure(damagedFailure)
        return vm to a
    }

    @Test
    fun `a copy that fails keeps the banner the risk and the bitmap and can be retried`() {
        val (vm, a) = failedAtRisk()

        assertTrue(vm.beginCopyOfDeclinedOverwrite())
        vm.finishCopyOfDeclinedOverwrite(saved = false)

        assertTrue(vm.overwriteDeclined)
        assertTrue(vm.overwriteOriginalAtRisk)
        assertEquals("The original may be damaged.", vm.overwriteFailureMessage)
        assertSame(a, vm.pendingOverwriteAsset)
        assertTrue(vm.pendingOverwriteBitmap != null)
        assertSame(a, vm.editingAsset)
        assertFalse(vm.closeEditor())
        assertTrue("a retry is allowed", vm.beginCopyOfDeclinedOverwrite())
    }

    @Test
    fun `a copy that succeeds forgets the failure and closes the editor`() {
        val (vm, _) = failedAtRisk()

        vm.beginCopyOfDeclinedOverwrite()
        vm.finishCopyOfDeclinedOverwrite(saved = true)

        assertNull(vm.editingAsset)
        assertNull(vm.pendingOverwriteAsset)
        assertNull(vm.pendingOverwriteBitmap)
        assertFalse(vm.overwriteDeclined)
        assertFalse(vm.overwriteOriginalAtRisk)
        assertFalse(vm.overwriteCopySaving)
    }

    @Test
    fun `a second tap on Save a copy while the first is running does nothing`() {
        val (vm, _) = failedAtRisk()

        assertTrue(vm.beginCopyOfDeclinedOverwrite())
        assertTrue(vm.overwriteCopySaving)
        assertFalse(vm.beginCopyOfDeclinedOverwrite())
    }

    @Test
    fun `Back is blocked while a copy of a safe-original failure is being saved`() {
        val a = photo(1)
        val vm = editorOn(a)
        vm.beginOverwrite(a, bitmap())
        vm.recordOverwriteFailure(safeFailure)

        vm.beginCopyOfDeclinedOverwrite()

        assertTrue(vm.editorCloseBlocked)
        assertFalse(vm.closeEditor())
        vm.finishCopyOfDeclinedOverwrite(saved = false)
        assertTrue(vm.closeEditor())
    }

    @Test
    fun `Save a copy has nothing to save when no replace failed`() {
        val vm = editorOn(photo(1))

        assertFalse(vm.beginCopyOfDeclinedOverwrite())
        assertFalse(vm.overwriteCopySaving)
    }
}
