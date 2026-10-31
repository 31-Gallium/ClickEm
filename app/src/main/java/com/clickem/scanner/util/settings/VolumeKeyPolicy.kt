package com.clickem.scanner.util.settings

import android.view.KeyEvent

/**
 * Pure business policy component governing hardware volume key handling for the camera.
 *
 * Centralizes key consumption decisions, zoom delta stepping, and shutter debouncing
 * so that production views ([com.clickem.scanner.ui.camera.CameraView]) and unit tests
 * share identical, verified logic without code duplication or facade mocks.
 */
object VolumeKeyPolicy {

    /** Default discrete zoom step applied per volume key press or repeat tick. */
    const val DEFAULT_ZOOM_DELTA: Float = 0.4f

    /**
     * Checks if the given Android keycode corresponds to a hardware volume key.
     *
     * @param keyCode The Android [KeyEvent] keycode.
     * @return `true` if [KeyEvent.KEYCODE_VOLUME_UP] or [KeyEvent.KEYCODE_VOLUME_DOWN], `false` otherwise.
     */
    fun isVolumeKey(keyCode: Int): Boolean {
        return keyCode == KeyEvent.KEYCODE_VOLUME_UP || keyCode == KeyEvent.KEYCODE_VOLUME_DOWN
    }

    /**
     * Calculates the directional zoom adjustment delta for a given keycode.
     *
     * @param keyCode The Android [KeyEvent] keycode.
     * @return `+0.4f` for [KeyEvent.KEYCODE_VOLUME_UP], `-0.4f` for [KeyEvent.KEYCODE_VOLUME_DOWN], or `0.0f` for non-volume keys.
     */
    fun calculateZoomDelta(keyCode: Int): Float {
        return when (keyCode) {
            KeyEvent.KEYCODE_VOLUME_UP -> DEFAULT_ZOOM_DELTA
            KeyEvent.KEYCODE_VOLUME_DOWN -> -DEFAULT_ZOOM_DELTA
            else -> 0.0f
        }
    }

    /**
     * Determines whether the key event should be consumed and prevented from propagating
     * to the system volume / audio manager.
     *
     * @param keyCode The Android [KeyEvent] keycode.
     * @param action The user's active [VolumeKeyAction] preference.
     * @return `true` if the key is a volume key and [action] is [VolumeKeyAction.ZOOM] or [VolumeKeyAction.SHUTTER];
     *         `false` if [action] is [VolumeKeyAction.OFF] or if [keyCode] is not a volume key.
     */
    fun shouldConsumeKey(keyCode: Int, action: VolumeKeyAction): Boolean {
        if (action == VolumeKeyAction.OFF) return false
        return isVolumeKey(keyCode)
    }

    /**
     * Determines whether a shutter capture should be triggered for a key press.
     *
     * Enforces debouncing: only the initial press ([repeatCount] == 0) triggers capture,
     * suppressing continuous bursts when holding down the key ([repeatCount] > 0),
     * and preventing re-entrant captures while a previous capture is in flight ([isCapturing] == true).
     *
     * @param repeatCount The repeat count from [KeyEvent.getRepeatCount].
     * @param isCapturing Whether an asynchronous camera capture is currently executing.
     * @return `true` if shutter capture should fire, `false` otherwise.
     */
    fun shouldTriggerShutter(repeatCount: Int, isCapturing: Boolean): Boolean {
        return repeatCount == 0 && !isCapturing
    }

    /**
     * Helper to compute bounded zoom ratio given current ratio and delta.
     *
     * @param currentZoom Current zoom ratio.
     * @param delta Zoom adjustment delta (e.g. from [calculateZoomDelta]).
     * @param minZoom Minimum allowable zoom ratio.
     * @param maxZoom Maximum allowable zoom ratio.
     * @return Bounded zoom ratio coerced to [[minZoom], [maxZoom]].
     */
    fun calculateClampedZoom(currentZoom: Float, delta: Float, minZoom: Float, maxZoom: Float): Float {
        val safeMin = if (!minZoom.isFinite() || minZoom <= 0f) 1.0f else minZoom
        if (!currentZoom.isFinite()) return safeMin
        if (!minZoom.isFinite() || !maxZoom.isFinite() || !delta.isFinite()) {
            return currentZoom.coerceAtLeast(safeMin)
        }
        val safeMax = if (maxZoom < safeMin) safeMin else maxZoom
        return (currentZoom.coerceIn(safeMin, safeMax) + delta).coerceIn(safeMin, safeMax)
    }

    /**
     * Converts a zoom ratio within [[minZoom], [maxZoom]] to CameraX's hyperbolic crop-width
     * linear zoom in [0.0f, 1.0f]:
     * `linearZoom = (1/minZoom - 1/zoomRatio) / (1/minZoom - 1/maxZoom)`
     */
    fun calculateLinearZoomFromRatio(zoomRatio: Float, minZoom: Float, maxZoom: Float): Float {
        if (!zoomRatio.isFinite() || !minZoom.isFinite() || !maxZoom.isFinite()) return 0.0f
        val safeMin = if (minZoom <= 0f) 1.0f else minZoom.coerceAtLeast(0.1f)
        val safeMax = if (maxZoom < safeMin) safeMin else maxZoom
        if (safeMax <= safeMin + 0.0001f) return 0.0f
        val clamped = zoomRatio.coerceIn(safeMin, safeMax)
        val invMin = 1.0f / safeMin
        val invMax = 1.0f / safeMax
        val invCur = 1.0f / clamped
        val denom = (invMin - invMax).coerceAtLeast(0.00001f)
        return ((invMin - invCur) / denom).coerceIn(0.0f, 1.0f)
    }
}
