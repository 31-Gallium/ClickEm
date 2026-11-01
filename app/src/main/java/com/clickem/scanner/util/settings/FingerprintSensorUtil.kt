package com.clickem.scanner.util.settings

import android.content.Context
import android.content.pm.PackageManager
import androidx.core.hardware.fingerprint.FingerprintManagerCompat

/**
 * Intelligent hardware detection utility to identify whether a device's fingerprint
 * scanner is physically located (side-mounted on power key or rear capacitive pad)
 * vs integrated under the OLED display (UDFPS / Optical / Ultrasonic FOD).
 *
 * For under-screen sensors, passive fingerprint shutter listening is disabled by default
 * to prevent on-screen viewfinder/control touches from triggering accidental scan captures.
 */
object FingerprintSensorUtil {

    enum class SensorLocation {
        PHYSICAL_SIDE_OR_REAR,
        UNDER_DISPLAY,
        UNKNOWN
    }

    /**
     * Determines the physical layout placement of the biometric sensor.
     */
    fun getSensorLocation(context: Context): SensorLocation {
        // 1. Framework Resource Overlays (AOSP standard config)
        val resResult = checkFrameworkResources(context)
        if (resResult != SensorLocation.UNKNOWN) {
            return resResult
        }

        // 2. PackageManager hardware features (Samsung / Vendor specifics)
        val pmResult = checkPackageManagerFeatures(context)
        if (pmResult != SensorLocation.UNKNOWN) {
            return pmResult
        }

        // 3. OEM System Properties (Qualcomm, MediaTek, Xiaomi, OnePlus/Oppo, Transsion, Vivo)
        val propResult = checkSystemProperties()
        if (propResult != SensorLocation.UNKNOWN) {
            return propResult
        }

        // 4. Hidden Framework APIs via reflection (FingerprintManager.getSensorPropertiesInternal)
        val apiResult = checkInternalSensorProperties(context)
        if (apiResult != SensorLocation.UNKNOWN) {
            return apiResult
        }

        // 5. Fallback heuristic: If fingerprint hardware exists on device, treat as standard physical
        return if (hasFingerprintHardware(context)) {
            SensorLocation.PHYSICAL_SIDE_OR_REAR
        } else {
            SensorLocation.UNKNOWN
        }
    }

    fun isUnderDisplay(context: Context): Boolean {
        return getSensorLocation(context) == SensorLocation.UNDER_DISPLAY
    }

    fun isPhysicalSideOrRear(context: Context): Boolean {
        return getSensorLocation(context) == SensorLocation.PHYSICAL_SIDE_OR_REAR
    }

    fun hasFingerprintHardware(context: Context): Boolean {
        return try {
            val pm = context.packageManager
            val hasFeature = pm.hasSystemFeature(PackageManager.FEATURE_FINGERPRINT) ||
                    pm.hasSystemFeature("android.hardware.fingerprint")
            val compat = FingerprintManagerCompat.from(context).isHardwareDetected
            hasFeature || compat
        } catch (_: Exception) {
            false
        }
    }

    private fun checkFrameworkResources(context: Context): SensorLocation {
        return try {
            val res = context.resources

            // Check AOSP config_udfps_sensor_props (Android 11+ / 12+)
            val udfpsPropsId = res.getIdentifier("config_udfps_sensor_props", "array", "android")
            if (udfpsPropsId != 0) {
                val array = res.getIntArray(udfpsPropsId)
                if (array.isNotEmpty()) {
                    return SensorLocation.UNDER_DISPLAY
                }
            }

            // Check config_hasUdfpsFingerprintSensor or config_hasOpticalFingerprintSensor
            val udfpsBoolId = res.getIdentifier("config_hasUdfpsFingerprintSensor", "bool", "android")
            if (udfpsBoolId != 0 && res.getBoolean(udfpsBoolId)) {
                return SensorLocation.UNDER_DISPLAY
            }

            val opticalBoolId = res.getIdentifier("config_hasOpticalFingerprintSensor", "bool", "android")
            if (opticalBoolId != 0 && res.getBoolean(opticalBoolId)) {
                return SensorLocation.UNDER_DISPLAY
            }

            // Check config_is_side_fingerprint
            val sideBoolId = res.getIdentifier("config_is_side_fingerprint", "bool", "android")
            if (sideBoolId != 0 && res.getBoolean(sideBoolId)) {
                return SensorLocation.PHYSICAL_SIDE_OR_REAR
            }

            SensorLocation.UNKNOWN
        } catch (_: Exception) {
            SensorLocation.UNKNOWN
        }
    }

    private fun checkPackageManagerFeatures(context: Context): SensorLocation {
        return try {
            val pm = context.packageManager
            if (pm.hasSystemFeature("com.samsung.android.fingerprint.optical") ||
                pm.hasSystemFeature("com.samsung.android.fingerprint.ultrasonic") ||
                pm.hasSystemFeature("android.hardware.fingerprint.udfps")
            ) {
                SensorLocation.UNDER_DISPLAY
            } else {
                SensorLocation.UNKNOWN
            }
        } catch (_: Exception) {
            SensorLocation.UNKNOWN
        }
    }

    private fun checkSystemProperties(): SensorLocation {
        return try {
            val systemPropertiesClass = Class.forName("android.os.SystemProperties")
            val getMethod = systemPropertiesClass.getMethod("get", String::class.java, String::class.java)

            fun getProp(key: String): String {
                return (getMethod.invoke(null, key, "") as? String).orEmpty().lowercase()
            }

            val propKeys = listOf(
                "ro.hardware.fingerprint",
                "ro.vendor.fingerprint.type",
                "persist.vendor.fingerprint.type",
                "ro.boot.fingerprint.type",
                "ro.vendor.fod.enable",
                "ro.vendor.udfps.enable",
                "vendor.fingerprint.udfps",
                "sys.fp.type",
                "vendor.fp.sensor",
                "ro.oplus.display.udfps.location",
                "ro.transsion.fp.type",
                "ro.vendor.transsion.fingerprint"
            )

            for (key in propKeys) {
                val value = getProp(key)
                if (value.isNotEmpty()) {
                    if (value.contains("udfps") || value.contains("fod") ||
                        value.contains("optical") || value.contains("ultrasonic") ||
                        value.contains("under_display") || value == "1" || value == "true"
                    ) {
                        return SensorLocation.UNDER_DISPLAY
                    }
                    if (value.contains("side") || value.contains("rear") ||
                        value.contains("power") || value.contains("capacitive")
                    ) {
                        return SensorLocation.PHYSICAL_SIDE_OR_REAR
                    }
                }
            }
            SensorLocation.UNKNOWN
        } catch (_: Exception) {
            SensorLocation.UNKNOWN
        }
    }

    private fun checkInternalSensorProperties(context: Context): SensorLocation {
        return try {
            val fpService = context.getSystemService(Context.FINGERPRINT_SERVICE) ?: return SensorLocation.UNKNOWN
            val method = fpService.javaClass.getMethod("getSensorPropertiesInternal")
            val sensorList = method.invoke(fpService) as? List<*> ?: return SensorLocation.UNKNOWN

            for (sensor in sensorList) {
                if (sensor == null) continue
                val clazz = sensor.javaClass

                try {
                    val isUdfpsMethod = clazz.getMethod("isAnyUdfpsType")
                    val isUdfps = isUdfpsMethod.invoke(sensor) as? Boolean
                    if (isUdfps == true) return SensorLocation.UNDER_DISPLAY
                } catch (_: Exception) {}

                try {
                    val isSideFpsMethod = clazz.getMethod("isAnySidefpsType")
                    val isSideFps = isSideFpsMethod.invoke(sensor) as? Boolean
                    if (isSideFps == true) return SensorLocation.PHYSICAL_SIDE_OR_REAR
                } catch (_: Exception) {}

                try {
                    val field = clazz.getDeclaredField("sensorType").apply { isAccessible = true }
                    val type = field.getInt(sensor)
                    when (type) {
                        2, 3 -> return SensorLocation.UNDER_DISPLAY
                        1, 4, 5 -> return SensorLocation.PHYSICAL_SIDE_OR_REAR
                    }
                } catch (_: Exception) {}
            }
            SensorLocation.UNKNOWN
        } catch (_: Exception) {
            SensorLocation.UNKNOWN
        }
    }

    fun getSensorLocationLabel(context: Context): String {
        return when (getSensorLocation(context)) {
            SensorLocation.PHYSICAL_SIDE_OR_REAR -> "Physical Sensor (Side / Rear)"
            SensorLocation.UNDER_DISPLAY -> "Under-Screen Sensor (In-Display)"
            SensorLocation.UNKNOWN -> "Physical Hardware Sensor"
        }
    }
}
