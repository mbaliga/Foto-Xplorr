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
 * The failed-overwrite state belongs to one photo's editor: Back cannot walk away from an original
 * that may be damaged, and nothing of it survives into the next editor.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34])
class EditorOverwriteStateTest {

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

    private fun viewModel() = AppStateViewModel(SavedStateHandle())

    private fun AppStateViewModel.failOverwrite(asset: MediaAsset, atRisk: Boolean) {
        pendingOverwriteAsset = asset
        pendingOverwriteBitmap = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888)
        overwriteFailureMessage = "failed"
        overwriteOriginalAtRisk = atRisk
        overwriteDeclined = true
    }

    private fun AppStateViewModel.assertFailureCleared() {
        assertNull(pendingOverwriteAsset)
        assertNull(pendingOverwriteBitmap)
        assertFalse(overwriteDeclined)
        assertNull(overwriteFailureMessage)
        assertFalse(overwriteOriginalAtRisk)
    }

    @Test
    fun `Back does nothing while the original is at risk`() {
        val vm = viewModel()
        val a = photo(1)
        vm.setEditingAsset(a)
        vm.failOverwrite(a, atRisk = true)

        assertTrue(vm.editorCloseBlocked)
        assertFalse(vm.closeEditor())

        assertSame(a, vm.editingAsset)
        assertTrue("the banner stays up", vm.overwriteDeclined)
        assertTrue(vm.overwriteOriginalAtRisk)
        assertSame(a, vm.pendingOverwriteAsset)
        assertTrue("the bitmap that Save a copy needs is still held", vm.pendingOverwriteBitmap != null)
    }

    @Test
    fun `Back closes a declined overwrite that left the original safe and forgets it`() {
        val vm = viewModel()
        val a = photo(1)
        vm.setEditingAsset(a)
        vm.failOverwrite(a, atRisk = false)

        assertFalse(vm.editorCloseBlocked)
        assertTrue(vm.closeEditor())

        assertNull(vm.editingAsset)
        vm.assertFailureCleared()
    }

    @Test
    fun `Back closes an editor that never failed`() {
        val vm = viewModel()
        vm.setEditingAsset(photo(1))

        assertTrue(vm.closeEditor())
        assertNull(vm.editingAsset)
        assertNull(vm.editingAssetId)
    }

    @Test
    fun `the next editor on another photo starts clean`() {
        val vm = viewModel()
        val a = photo(1)
        vm.setEditingAsset(a)
        vm.failOverwrite(a, atRisk = false)

        assertTrue(vm.setEditingAsset(photo(2)))

        assertEquals(photo(2).id, vm.editingAsset?.id)
        vm.assertFailureCleared()
        assertFalse(vm.editorCloseBlocked)
    }

    @Test
    fun `another photo cannot replace an editor whose original is at risk`() {
        val vm = viewModel()
        val a = photo(1)
        vm.setEditingAsset(a)
        vm.failOverwrite(a, atRisk = true)

        assertFalse(vm.setEditingAsset(photo(2)))

        assertSame(a, vm.editingAsset)
        assertTrue(vm.overwriteDeclined)
        assertSame(a, vm.pendingOverwriteAsset)
    }

    @Test
    fun `reopening an editor after it was closed starts clean even on the same photo`() {
        val vm = viewModel()
        val a = photo(1)
        vm.setEditingAsset(a)
        vm.failOverwrite(a, atRisk = false)
        vm.setEditingAsset(null)

        vm.setEditingAsset(a)

        vm.assertFailureCleared()
    }

    @Test
    fun `setting the same photo again while it is open keeps its failure`() {
        val vm = viewModel()
        val a = photo(1)
        vm.setEditingAsset(a)
        vm.failOverwrite(a, atRisk = true)

        vm.setEditingAsset(a)

        assertTrue(vm.overwriteDeclined)
        assertSame(a, vm.pendingOverwriteAsset)
    }

    @Test
    fun `a successful save that closes the editor leaves no failure behind`() {
        val vm = viewModel()
        val a = photo(1)
        vm.setEditingAsset(a)
        vm.failOverwrite(a, atRisk = true)

        vm.clearOverwriteFailure()
        assertTrue(vm.closeEditor())

        vm.assertFailureCleared()
    }
}
