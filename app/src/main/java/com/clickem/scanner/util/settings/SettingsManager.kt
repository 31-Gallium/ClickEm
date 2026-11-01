package com.clickem.scanner.util.settings

import android.content.Context
import android.content.SharedPreferences
import android.content.pm.PackageManager
import androidx.biometric.BiometricManager
import androidx.core.hardware.fingerprint.FingerprintManagerCompat
import com.clickem.scanner.model.FilterMode

enum class VolumeKeyAction {
    SHUTTER,
    ZOOM,
    OFF
}

enum class ExportQuality(val displayName: String, val maxDimension: Int, val jpegQuality: Int) {
    LOW("Low", 1600, 80),
    MEDIUM("Medium", 2400, 88),
    HIGH("Native / High", 8192, 98)
}

class SettingsManager(private val context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences("clickem_settings", Context.MODE_PRIVATE)

    var cameraAspectRatio: String
        get() = prefs.getString(KEY_ASPECT_RATIO, "4:3") ?: "4:3"
        set(value) = prefs.edit().putString(KEY_ASPECT_RATIO, value).apply()

    var volumeKeyAction: VolumeKeyAction
        get() {
            val name = prefs.getString(KEY_VOLUME_ACTION, VolumeKeyAction.ZOOM.name)
            return try {
                VolumeKeyAction.valueOf(name ?: VolumeKeyAction.ZOOM.name)
            } catch (_: Exception) {
                VolumeKeyAction.ZOOM
            }
        }
        set(value) = prefs.edit().putString(KEY_VOLUME_ACTION, value.name).apply()

    var isFingerprintShutterEnabled: Boolean
        get() {
            if (!prefs.contains(KEY_FINGERPRINT_SHUTTER)) {
                val isUnderDisplay = FingerprintSensorUtil.isUnderDisplay(context)
                val isSupported = isFingerprintHardwareSupported()
                val defaultVal = isSupported && !isUnderDisplay
                prefs.edit().putBoolean(KEY_FINGERPRINT_SHUTTER, defaultVal).apply()
                return defaultVal
            }
            return prefs.getBoolean(KEY_FINGERPRINT_SHUTTER, false)
        }
        set(value) = prefs.edit().putBoolean(KEY_FINGERPRINT_SHUTTER, value).apply()

    var defaultFilterMode: FilterMode
        get() {
            val name = prefs.getString(KEY_DEFAULT_FILTER, FilterMode.MAGIC_COLOR.name)
            return try {
                FilterMode.valueOf(name ?: FilterMode.MAGIC_COLOR.name)
            } catch (_: Exception) {
                FilterMode.MAGIC_COLOR
            }
        }
        set(value) = prefs.edit().putString(KEY_DEFAULT_FILTER, value.name).apply()

    var defaultExportQuality: ExportQuality
        get() {
            val name = prefs.getString(KEY_EXPORT_QUALITY, ExportQuality.HIGH.name)
            return try {
                ExportQuality.valueOf(name ?: ExportQuality.HIGH.name)
            } catch (_: Exception) {
                ExportQuality.HIGH
            }
        }
        set(value) = prefs.edit().putString(KEY_EXPORT_QUALITY, value.name).apply()

    var exportFolderUri: String?
        get() = prefs.getString(KEY_EXPORT_FOLDER_URI, null)
        set(value) = prefs.edit().putString(KEY_EXPORT_FOLDER_URI, value).apply()

    var exportFolderPath: String
        get() = prefs.getString(KEY_EXPORT_FOLDER_PATH, "/Downloads/ClickEm/") ?: "/Downloads/ClickEm/"
        set(value) = prefs.edit().putString(KEY_EXPORT_FOLDER_PATH, value).apply()

    fun resetExportFolder() {
        prefs.edit()
            .remove(KEY_EXPORT_FOLDER_URI)
            .remove(KEY_EXPORT_FOLDER_PATH)
            .apply()
    }

    /**
     * Multi-level hardware check verified for Tecno Pova, Infinix, MediaTek, and Samsung/Pixel devices:
     * Checks PackageManager feature flags, AndroidX BiometricManager, and FingerprintManagerCompat.
     */
    fun isFingerprintHardwareSupported(): Boolean {
        val pm = context.packageManager
        val hasFeature = pm.hasSystemFeature(PackageManager.FEATURE_FINGERPRINT) ||
                pm.hasSystemFeature("android.hardware.fingerprint")

        val biometricManager = BiometricManager.from(context)
        val canAuth = biometricManager.canAuthenticate(
            BiometricManager.Authenticators.BIOMETRIC_STRONG or BiometricManager.Authenticators.BIOMETRIC_WEAK
        )
        val isBiometricAvailable = canAuth != BiometricManager.BIOMETRIC_ERROR_NO_HARDWARE

        val compatSupported = try {
            FingerprintManagerCompat.from(context).isHardwareDetected
        } catch (_: Exception) {
            false
        }

        return hasFeature || isBiometricAvailable || compatSupported
    }

    companion object {
        private const val KEY_ASPECT_RATIO = "pref_aspect_ratio"
        private const val KEY_VOLUME_ACTION = "pref_volume_action"
        private const val KEY_FINGERPRINT_SHUTTER = "pref_fingerprint_shutter"
        private const val KEY_DEFAULT_FILTER = "pref_default_filter"
        private const val KEY_EXPORT_QUALITY = "pref_export_quality"
        private const val KEY_EXPORT_FOLDER_URI = "pref_export_folder_uri"
        private const val KEY_EXPORT_FOLDER_PATH = "pref_export_folder_path"
    }
}
