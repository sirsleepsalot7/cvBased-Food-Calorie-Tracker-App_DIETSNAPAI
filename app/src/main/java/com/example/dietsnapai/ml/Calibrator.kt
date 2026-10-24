package com.example.dietsnapai.ml

import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.util.Log

object Calibrator {
    private const val TAG = "DietSnapCalibrator"

    data class CameraIntrinsics(
        val focalLengthMm: Float,
        val sensorWidthMm: Float,
        val sensorHeightMm: Float
    )

    fun getCameraIntrinsics(context: Context, cameraId: String = "0"): CameraIntrinsics {
        val manager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        return try {
            val characteristics = manager.getCameraCharacteristics(cameraId)
            val focalLengths = characteristics.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)
            val focalLengthMm = focalLengths?.firstOrNull() ?: 4.25f
            val sensorSize = characteristics.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE)
            val sensorWidth = sensorSize?.width ?: 5.6f
            val sensorHeight = sensorSize?.height ?: 4.2f
            CameraIntrinsics(focalLengthMm, sensorWidth, sensorHeight)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to query camera characteristics: ${e.message}")
            CameraIntrinsics(4.25f, 5.6f, 4.2f)
        }
    }

    fun calculateMmPerPixel(
        distanceMeters: Float,
        focalLengthMm: Float,
        sensorWidthMm: Float,
        imageWidthPx: Int = 640
    ): Float {
        val focalLengthPx = (focalLengthMm / sensorWidthMm) * imageWidthPx
        return (distanceMeters * 1000f) / focalLengthPx
    }

    fun calculateCmPerPixel(
        distanceMeters: Float,
        focalLengthMm: Float,
        sensorWidthMm: Float,
        imageWidthPx: Int = 640
    ): Float {
        return calculateMmPerPixel(distanceMeters, focalLengthMm, sensorWidthMm, imageWidthPx) / 10.0f
    }
}
