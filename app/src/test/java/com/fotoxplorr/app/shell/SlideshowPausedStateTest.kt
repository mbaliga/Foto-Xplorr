package com.fotoxplorr.app.shell

import androidx.lifecycle.SavedStateHandle
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The slideshow's pause (owner, #16) lives beside [AppStateViewModel.slideshowActive] and ends
 * with it: every place in the host that stops the slideshow by assigning `slideshowActive = false`
 * must also end a pause, and a new slideshow must start playing, not held.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SlideshowPausedStateTest {

    private fun viewModel() = AppStateViewModel(SavedStateHandle())

    @Test
    fun `a slideshow starts playing`() {
        val vm = viewModel()
        vm.slideshowActive = true
        assertTrue(vm.slideshowActive)
        assertFalse(vm.slideshowPaused)
    }

    @Test
    fun `pausing holds the slideshow active`() {
        val vm = viewModel()
        vm.slideshowActive = true
        vm.slideshowPaused = true
        assertTrue("paused is not stopped", vm.slideshowActive)
        assertTrue(vm.slideshowPaused)
    }

    @Test
    fun `stopping a paused slideshow clears the pause`() {
        val vm = viewModel()
        vm.slideshowActive = true
        vm.slideshowPaused = true
        vm.slideshowActive = false
        assertFalse(vm.slideshowActive)
        assertFalse("the next slideshow must not start held", vm.slideshowPaused)
        vm.slideshowActive = true
        assertFalse(vm.slideshowPaused)
    }

    @Test
    fun `the actions room's toggle stops a paused slideshow`() {
        val vm = viewModel()
        vm.slideshowActive = true
        vm.slideshowPaused = true
        // The host's onToggleSlideshow is `slideshowActive = !slideshowActive`.
        vm.slideshowActive = !vm.slideshowActive
        assertFalse(vm.slideshowActive)
        assertFalse(vm.slideshowPaused)
    }
}
