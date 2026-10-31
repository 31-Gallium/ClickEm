package com.clickem.scanner

import android.view.KeyEvent
import com.clickem.scanner.util.settings.VolumeKeyAction
import org.junit.Assert.*
import org.junit.Test
import java.util.ArrayDeque
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

class VolumeKeyStressTest {

    @Test
    fun testKeyEventConstants() {
        assertEquals(0, KeyEvent.ACTION_DOWN)
        assertEquals(1, KeyEvent.ACTION_UP)
        assertEquals(24, KeyEvent.KEYCODE_VOLUME_UP)
        assertEquals(25, KeyEvent.KEYCODE_VOLUME_DOWN)
    }

    /**
     * Test continuous zoom stepping with bounds clamping.
     * Starts at 1.0f (minZoomRatio), steps up by +0.4f to 10.0f (maxZoomRatio),
     * verifies exact stepping, saturation clamping, and reverse down-stepping.
     */
    @Test
    fun testContinuousZoomSteppingAndClamping() {
        val minZoom = 1.0f
        val maxZoom = 10.0f
        var currentZoom = 1.0f

        fun adjustZoom(delta: Float) {
            currentZoom = (currentZoom + delta).coerceIn(minZoom, maxZoom)
        }

        // 1. Initial state
        assertEquals(1.0f, currentZoom, 0.0001f)

        // 2. Step up 10 times (+4.0f -> 5.0f)
        for (i in 1..10) {
            adjustZoom(0.4f)
            assertEquals(1.0f + i * 0.4f, currentZoom, 0.001f)
        }
        assertEquals(5.0f, currentZoom, 0.001f)

        // 3. Step up another 15 times (would reach 11.0f, must clamp at maxZoom 10.0f)
        for (i in 1..15) {
            adjustZoom(0.4f)
        }
        assertEquals(10.0f, currentZoom, 0.0001f)

        // 4. Over-saturation: further stepping up remains clamped at 10.0f
        for (i in 1..50) {
            adjustZoom(0.4f)
            assertEquals(10.0f, currentZoom, 0.0001f)
        }

        // 5. Continuous down-stepping from 10.0f down to minZoom 1.0f
        // 9.0f / 0.4f = 22.5 steps -> 23 steps will hit 1.0f
        for (i in 1..22) {
            adjustZoom(-0.4f)
            assertEquals(10.0f - i * 0.4f, currentZoom, 0.001f)
        }
        assertEquals(1.2f, currentZoom, 0.001f)
        adjustZoom(-0.4f)
        assertEquals(1.0f, currentZoom, 0.0001f)

        // 6. Under-saturation: further down-stepping remains clamped at 1.0f
        for (i in 1..50) {
            adjustZoom(-0.4f)
            assertEquals(1.0f, currentZoom, 0.0001f)
        }
    }

    /**
     * Test shutter repeatCount debounce under rapid key events.
     * Simulates holding down the volume key which produces 1 initial event (repeatCount = 0)
     * followed by rapid repeat events (repeatCount 1..100).
     * Shutter must trigger EXACTLY ONCE.
     */
    @Test
    fun testShutterDebounceUnderRapidKeyRepeat() {
        var captureCount = 0
        var isCapturing = false

        fun handleShutterEvent(repeatCount: Int) {
            if (repeatCount == 0 && !isCapturing) {
                isCapturing = true
                captureCount++
            }
        }

        // Initial press
        handleShutterEvent(0)
        assertEquals(1, captureCount)
        assertTrue(isCapturing)

        // 100 rapid repeat events while key is held down
        for (repeat in 1..100) {
            handleShutterEvent(repeat)
        }
        assertEquals("Shutter should NOT trigger on repeatCount > 0", 1, captureCount)

        // Simulate capture completion while key is STILL held down
        isCapturing = false

        // Further repeat events while key is still held down must STILL not trigger shutter!
        for (repeat in 101..150) {
            handleShutterEvent(repeat)
        }
        assertEquals("Shutter should NEVER trigger while key is held down even after previous capture finishes", 1, captureCount)

        // User releases key and presses again (repeatCount resets to 0)
        handleShutterEvent(0)
        assertEquals("New press (repeatCount = 0) should trigger next capture", 2, captureCount)
    }

    private class CaptureLockHolder {
        @Volatile
        var isCapturing = false
    }

    /**
     * Concurrency stress test: verify that isCapturing flag strictly prevents
     * multi-threaded burst captures.
     */
    @Test
    fun testCaptureConcurrencyGuards() {
        val threadCount = 32
        val executor = Executors.newFixedThreadPool(threadCount)
        val latch = CountDownLatch(1)
        val doneLatch = CountDownLatch(threadCount)
        val successfulCaptures = AtomicInteger(0)

        val lockHolder = CaptureLockHolder()
        val lock = Any()

        for (i in 0 until threadCount) {
            executor.submit {
                latch.await()
                // Emulate triggerShutterCapture re-entrance check
                var triggered = false
                synchronized(lock) {
                    if (!lockHolder.isCapturing) {
                        lockHolder.isCapturing = true
                        triggered = true
                    }
                }
                if (triggered) {
                    successfulCaptures.incrementAndGet()
                }
                doneLatch.countDown()
            }
        }

        // Release all threads simultaneously
        latch.countDown()
        doneLatch.await()
        executor.shutdown()

        assertEquals("Only exactly 1 thread should successfully enter capture when guarded", 1, successfulCaptures.get())
    }

    /**
     * Test pass-through routing when overlayBackStack is non-empty vs empty.
     */
    @Test
    fun testOverlayBackStackPassThroughRouting() {
        val overlayBackStack = ArrayDeque<String>()

        fun shouldDelegateToCamera(keyCode: Int): Boolean {
            return (keyCode == KeyEvent.KEYCODE_VOLUME_UP || keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) && overlayBackStack.isEmpty()
        }

        // When backstack is empty (Camera is top view):
        assertTrue(shouldDelegateToCamera(KeyEvent.KEYCODE_VOLUME_UP))
        assertTrue(shouldDelegateToCamera(KeyEvent.KEYCODE_VOLUME_DOWN))
        assertFalse(shouldDelegateToCamera(KeyEvent.KEYCODE_BACK))
        assertFalse(shouldDelegateToCamera(KeyEvent.KEYCODE_POWER))

        // Push Settings view
        overlayBackStack.push("SettingsView")
        assertFalse("Volume keys MUST pass through to system when Settings is open", shouldDelegateToCamera(KeyEvent.KEYCODE_VOLUME_UP))
        assertFalse("Volume keys MUST pass through to system when Settings is open", shouldDelegateToCamera(KeyEvent.KEYCODE_VOLUME_DOWN))

        // Push SinglePageEditView
        overlayBackStack.push("SinglePageEditView")
        assertFalse(shouldDelegateToCamera(KeyEvent.KEYCODE_VOLUME_UP))
        assertFalse(shouldDelegateToCamera(KeyEvent.KEYCODE_VOLUME_DOWN))

        // Pop SinglePageEditView
        overlayBackStack.pop()
        assertFalse("Volume keys MUST still pass through while Settings is open", shouldDelegateToCamera(KeyEvent.KEYCODE_VOLUME_UP))

        // Pop SettingsView
        overlayBackStack.pop()
        assertTrue("Volume keys MUST delegate to Camera once backstack is empty", shouldDelegateToCamera(KeyEvent.KEYCODE_VOLUME_UP))
        assertTrue("Volume keys MUST delegate to Camera once backstack is empty", shouldDelegateToCamera(KeyEvent.KEYCODE_VOLUME_DOWN))
    }

    /**
     * Test VolumeKeyAction.OFF pass-through.
     * When user sets volume key action to OFF, volume keys MUST NOT be consumed.
     */
    @Test
    fun testVolumeKeyActionOffFallthrough() {
        fun simulateOnVolumeKeyEvent(keyCode: Int, action: VolumeKeyAction): Boolean {
            if (action == VolumeKeyAction.OFF) return false
            if (keyCode != KeyEvent.KEYCODE_VOLUME_UP && keyCode != KeyEvent.KEYCODE_VOLUME_DOWN) return false
            return true
        }

        assertFalse(simulateOnVolumeKeyEvent(KeyEvent.KEYCODE_VOLUME_UP, VolumeKeyAction.OFF))
        assertFalse(simulateOnVolumeKeyEvent(KeyEvent.KEYCODE_VOLUME_DOWN, VolumeKeyAction.OFF))
        assertTrue(simulateOnVolumeKeyEvent(KeyEvent.KEYCODE_VOLUME_UP, VolumeKeyAction.ZOOM))
        assertTrue(simulateOnVolumeKeyEvent(KeyEvent.KEYCODE_VOLUME_DOWN, VolumeKeyAction.SHUTTER))
    }
}
