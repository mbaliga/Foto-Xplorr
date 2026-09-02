package com.fotoxplorr.benchmarks

import androidx.benchmark.macro.CompilationMode
import androidx.benchmark.macro.FrameTimingMetric
import androidx.benchmark.macro.StartupMode
import androidx.benchmark.macro.StartupTimingMetric
import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Direction
import androidx.test.uiautomator.Until
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * FX-005 device benchmarks: cold startup, grid scroll, and the left-room open.
 *
 * `[OWNER]`-run only — see ../README.md. The room-open benchmark drives the same edge
 * drag a finger would, so it exercises the SpatialShell drag path, not `open()`.
 */
@RunWith(AndroidJUnit4::class)
class FotoXplorrBenchmarks {

    @get:Rule
    val benchmarkRule = MacrobenchmarkRule()

    @Test
    fun coldStartup() = benchmarkRule.measureRepeated(
        packageName = TARGET_PACKAGE,
        metrics = listOf(StartupTimingMetric()),
        iterations = 5,
        startupMode = StartupMode.COLD,
        compilationMode = CompilationMode.Partial(),
    ) {
        pressHome()
        startActivityAndWait()
    }

    @Test
    fun gridScroll() = benchmarkRule.measureRepeated(
        packageName = TARGET_PACKAGE,
        metrics = listOf(FrameTimingMetric()),
        iterations = 5,
        startupMode = StartupMode.WARM,
        compilationMode = CompilationMode.Partial(),
    ) {
        startActivityAndWait()
        val grid = device.findObject(By.scrollable(true)) ?: return@measureRepeated
        repeat(3) {
            grid.fling(Direction.DOWN)
            device.waitForIdle()
        }
        repeat(3) {
            grid.fling(Direction.UP)
            device.waitForIdle()
        }
    }

    @Test
    fun roomOpenTransition() = benchmarkRule.measureRepeated(
        packageName = TARGET_PACKAGE,
        metrics = listOf(FrameTimingMetric()),
        iterations = 5,
        startupMode = StartupMode.WARM,
        compilationMode = CompilationMode.Partial(),
    ) {
        startActivityAndWait()
        // Edge drag from the left: the rail room reveal, as a finger does it.
        val height = device.displayHeight / 2
        device.swipe(2, height, device.displayWidth / 2, height, 20)
        device.wait(Until.hasObject(By.text("Photos")), 2_000)
        device.pressBack()
        device.waitForIdle()
    }

    private companion object {
        // The offline flavor's debug id once FX-010 lands; adjust if benchmarking connect.
        const val TARGET_PACKAGE = "com.fotoxplorr.app.debug"
    }
}
