package com.clickem.scanner

import com.clickem.scanner.util.update.UpdateManager
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateManagerTest {

    @Test
    fun testSemanticVersionComparison() {
        // Newer remote versions
        assertTrue(UpdateManager.isNewerVersion("1.0.0", "1.0.1"))
        assertTrue(UpdateManager.isNewerVersion("v1.0.0", "v1.0.1"))
        assertTrue(UpdateManager.isNewerVersion("1.0.9", "1.0.10"))
        assertTrue(UpdateManager.isNewerVersion("1.0.0", "v2.0.0"))
        assertTrue(UpdateManager.isNewerVersion("1.1.5", "1.2.0"))

        // Same or older versions
        assertFalse(UpdateManager.isNewerVersion("1.0.0", "1.0.0"))
        assertFalse(UpdateManager.isNewerVersion("1.0.0", "v1.0.0"))
        assertFalse(UpdateManager.isNewerVersion("1.0.1", "1.0.0"))
        assertFalse(UpdateManager.isNewerVersion("1.2.0", "1.1.9"))
        assertFalse(UpdateManager.isNewerVersion("2.0.0", "1.9.9"))
    }
}
