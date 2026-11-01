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

    /**
     * Computes the safe bottom inset (in pixels) for navigation bars, dynamically
     * accommodating 3-button navigation bars (recents, home, back) as well as gesture bars.
     */
    fun getSafeBottomInset(context: Context, insets: androidx.core.view.WindowInsetsCompat? = null): Int {
        var bottomNav = 0
        if (insets != null) {
            val navBars = insets.getInsets(
                androidx.core.view.WindowInsetsCompat.Type.navigationBars() or
                androidx.core.view.WindowInsetsCompat.Type.systemBars()
            )
            bottomNav = navBars.bottom
        }
        if (bottomNav <= 0) {
            try {
                val resourceId = context.resources.getIdentifier("navigation_bar_height", "dimen", "android")
                if (resourceId > 0) {
                    bottomNav = context.resources.getDimensionPixelSize(resourceId)
                }
            } catch (_: Exception) {}
        }
        return bottomNav
    }
}

