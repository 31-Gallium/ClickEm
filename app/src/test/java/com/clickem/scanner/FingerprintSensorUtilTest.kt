package com.clickem.scanner

import com.clickem.scanner.util.settings.FingerprintSensorUtil
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class FingerprintSensorUtilTest {

    @Test
    fun testSensorLocationEnumValues() {
        val locations = FingerprintSensorUtil.SensorLocation.values()
        assertEquals(3, locations.size)
        assertTrue(locations.contains(FingerprintSensorUtil.SensorLocation.PHYSICAL_SIDE_OR_REAR))
        assertTrue(locations.contains(FingerprintSensorUtil.SensorLocation.UNDER_DISPLAY))
        assertTrue(locations.contains(FingerprintSensorUtil.SensorLocation.UNKNOWN))
    }

    @Test
    fun testFingerprintSensorUtilSourceIntegrity() {
        val file = File("src/main/java/com/clickem/scanner/util/settings/FingerprintSensorUtil.kt")
        assertTrue("FingerprintSensorUtil.kt must exist", file.exists())
        val code = file.readText()

        assertTrue("Must support AOSP config_udfps_sensor_props", code.contains("config_udfps_sensor_props"))
        assertTrue("Must support config_hasUdfpsFingerprintSensor", code.contains("config_hasUdfpsFingerprintSensor"))
        assertTrue("Must support config_hasOpticalFingerprintSensor", code.contains("config_hasOpticalFingerprintSensor"))
        assertTrue("Must support Samsung optical/ultrasonic features", code.contains("com.samsung.android.fingerprint.optical"))
        assertTrue("Must inspect SystemProperties for udfps and fod", code.contains("ro.vendor.fingerprint.type"))
        assertTrue("Must inspect internal HIDL/AIDL getSensorPropertiesInternal", code.contains("getSensorPropertiesInternal"))
        assertTrue("Must provide isUnderDisplay check", code.contains("fun isUnderDisplay"))
        assertTrue("Must provide isPhysicalSideOrRear check", code.contains("fun isPhysicalSideOrRear"))
    }
}
