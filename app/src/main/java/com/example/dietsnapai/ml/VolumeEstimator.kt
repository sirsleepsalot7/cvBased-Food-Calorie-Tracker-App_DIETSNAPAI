package com.example.dietsnapai.ml

import android.graphics.RectF

object VolumeEstimator {

    /**
     * Integrates segmented food region against surrounding surface plane depth.
     * depthMap: 518x518 normalized relative inverse depth from Depth Anything V2.
     * foodBox: Bounding box mapped to 518x518 coordinates.
     * mmPerPixel: Scale factor calculated via optical camera calibration.
     * className: COCO class name used to apply geometry shape masking factors.
     */
    fun computePhysicalVolume(
        depthMap: Array<FloatArray>,
        foodBox: RectF,
        mmPerPixel: Float,
        className: String = "food"
    ): Float {
        val startX = foodBox.left.toInt().coerceIn(0, 517)
        val endX = foodBox.right.toInt().coerceIn(0, 517)
        val startY = foodBox.top.toInt().coerceIn(0, 517)
        val endY = foodBox.bottom.toInt().coerceIn(0, 517)

        if (startX >= endX || startY >= endY) return 120f

        // Sample background surface depth around all four edges of the object box
        val bgSamples = mutableListOf<Float>()
        val pad = 12

        val topY = (startY - pad).coerceAtLeast(0)
        for (x in startX..endX step 4) bgSamples.add(depthMap[topY][x])

        val bottomY = (endY + pad).coerceAtMost(517)
        for (x in startX..endX step 4) bgSamples.add(depthMap[bottomY][x])

        val leftX = (startX - pad).coerceAtLeast(0)
        for (y in startY..endY step 4) bgSamples.add(depthMap[y][leftX])

        val rightX = (endX + pad).coerceAtMost(517)
        for (y in startY..endY step 4) bgSamples.add(depthMap[y][rightX])

        val baseSurfaceDepth = if (bgSamples.isNotEmpty()) bgSamples.average().toFloat() else 0f

        // Shape/Mask coverage factor based on geometry of object class
        val shapeFactor = when (className.lowercase()) {
            "banana", "carrot", "hot dog" -> 0.35f       // Slender / crescent
            "orange", "apple", "donut" -> 0.65f         // Spherical / circular
            "pizza", "sandwich", "cake" -> 0.60f        // Flat / triangular wedge
            "bowl", "cup", "bottle" -> 0.65f            // Cylindrical
            else -> 0.50f
        }

        var totalVolumeMm3 = 0.0f
        val pixelAreaMm2 = mmPerPixel * mmPerPixel
        val depthScaleConstantMm = 105.0f // Calibrated height scaling factor
        
        val minElevationThreshold = 0.02f
        var elevatedPixelCount = 0

        for (y in startY until endY) {
            for (x in startX until endX) {
                val currentDepth = depthMap[y][x]
                val relativeHeight = currentDepth - baseSurfaceDepth
                if (relativeHeight > minElevationThreshold) {
                    val heightMm = relativeHeight * depthScaleConstantMm
                    totalVolumeMm3 += (pixelAreaMm2 * heightMm)
                    elevatedPixelCount++
                }
            }
        }

        val rawVolumeCm3 = if (elevatedPixelCount > 10) {
            (totalVolumeMm3 / 1000f) * shapeFactor
        } else {
            // Fallback estimation using calibrated bounding box geometry
            val boxWidthMm = foodBox.width() * mmPerPixel
            val boxHeightMm = foodBox.height() * mmPerPixel
            val estimatedAreaMm2 = boxWidthMm * boxHeightMm * shapeFactor
            val estimatedHeightMm = minOf(boxWidthMm, boxHeightMm) * 0.65f
            (estimatedAreaMm2 * estimatedHeightMm) / 1000f
        }

        return rawVolumeCm3.coerceIn(30f, 800f)
    }
}
