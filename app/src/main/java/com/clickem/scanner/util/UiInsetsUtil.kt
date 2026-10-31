package com.clickem.scanner.util

import android.content.Context

object UiInsetsUtil {
    /**
     * Computes the guaranteed minimum safe top inset (in pixels) to prevent UI
     * elements from colliding with camera punch-holes or notification notches.
     */
    fun getSafeTopInset(context: Context): Int {
        val density = context.resources.displayMetrics.density
        var minTop = (42 * density).toInt()
        try {
            val resourceId = context.resources.getIdentifier("status_bar_height", "dimen", "android")
            if (resourceId > 0) {
                minTop = maxOf(minTop, context.resources.getDimensionPixelSize(resourceId))
            }
        } catch (_: Exception) {}
        return minTop
    }
}

