package dev.phonebridge.android.tools.system

import android.content.Context
import android.content.pm.PackageManager
import android.hardware.camera2.CameraAccessException
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import dev.phonebridge.core.tools.ToolException

/** Torch control through CameraManager (no CAMERA permission needed). Binder IPC: call on Dispatchers.IO. */
internal object Flashlight {
    const val NO_FLASH = "This phone has no flashlight."

    fun unavailableReason(context: Context): String? =
        if (context.packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_FLASH)) null else NO_FLASH

    fun set(context: Context, on: Boolean) {
        val cm = context.getSystemService(CameraManager::class.java) ?: throw ToolException("Camera service is not available on this phone.")
        try {
            cm.setTorchMode(findTorchCamera(cm), on)
        } catch (e: CameraAccessException) {
            throw ToolException(
                when (e.reason) {
                    CameraAccessException.CAMERA_IN_USE, CameraAccessException.MAX_CAMERAS_IN_USE ->
                        "The camera is in use by another app, so the flashlight can't be switched. Ask the user to close the camera app and retry."
                    CameraAccessException.CAMERA_DISABLED ->
                        "The camera is disabled by a device policy, so the flashlight can't be used."
                    else -> "The flashlight could not be switched (camera error ${e.reason}). Retry in a moment."
                },
            )
        } catch (e: IllegalArgumentException) {
            throw ToolException(NO_FLASH)
        } catch (e: SecurityException) {
            throw ToolException("Android refused flashlight access: ${e.message}")
        }
    }

    /** A camera with a flash unit, back-facing preferred (that is the "flashlight" users mean). */
    private fun findTorchCamera(cm: CameraManager): String {
        val withFlash = cm.cameraIdList.filter { id ->
            runCatching { cm.getCameraCharacteristics(id).get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true }.getOrDefault(false)
        }
        return withFlash.firstOrNull { id ->
            runCatching { cm.getCameraCharacteristics(id).get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK }
                .getOrDefault(false)
        } ?: withFlash.firstOrNull() ?: throw ToolException(NO_FLASH)
    }
}
