package com.example.dietsnapai.ml

import android.graphics.Bitmap
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

object ImageUtils {

    @Suppress("all")
    fun bitmapToDepthFloatBuffer(bitmap: Bitmap): FloatBuffer {
        val targetSize = 518
        val scaledBitmap = Bitmap.createScaledBitmap(bitmap, targetSize, targetSize, true)

        val floatBuffer = ByteBuffer.allocateDirect(4 * 1 * 3 * targetSize * targetSize)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()

        val pixels = IntArray(targetSize * targetSize)
        scaledBitmap.getPixels(pixels, 0, targetSize, 0, 0, targetSize, targetSize)

        if (scaledBitmap != bitmap) {
            scaledBitmap.recycle()
        }

        val mean = floatArrayOf(0.485f, 0.456f, 0.406f)
        val std = floatArrayOf(0.229f, 0.224f, 0.225f)
        val channelStride = targetSize * targetSize

        for (i in pixels.indices) {
            val color = pixels[i]
            val r = (((color shr 16) and 0xFF) / 255.0f - mean[0]) / std[0]
            val g = (((color shr 8) and 0xFF) / 255.0f - mean[1]) / std[1]
            val b = ((color and 0xFF) / 255.0f - mean[2]) / std[2]

            floatBuffer.put(i, r)
            floatBuffer.put(channelStride + i, g)
            floatBuffer.put(2 * channelStride + i, b)
        }

        floatBuffer.rewind()
        return floatBuffer
    }

    /**
     * Resizes and normalizes bitmap to 1x3x640x640 [0.0..1.0] for YOLOv8.
     */
    @Suppress("all")
    fun bitmapToYoloFloatBuffer(bitmap: Bitmap): FloatBuffer {
        val targetSize = 640
        val scaledBitmap = Bitmap.createScaledBitmap(bitmap, targetSize, targetSize, true)

        val floatBuffer = ByteBuffer.allocateDirect(4 * 1 * 3 * targetSize * targetSize)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()

        val pixels = IntArray(targetSize * targetSize)
        scaledBitmap.getPixels(pixels, 0, targetSize, 0, 0, targetSize, targetSize)

        if (scaledBitmap != bitmap) {
            scaledBitmap.recycle()
        }

        val channelStride = targetSize * targetSize
        for (i in pixels.indices) {
            val color = pixels[i]
            val r = ((color shr 16) and 0xFF) / 255.0f
            val g = ((color shr 8) and 0xFF) / 255.0f
            val b = (color and 0xFF) / 255.0f

            floatBuffer.put(i, r)
            floatBuffer.put(channelStride + i, g)
            floatBuffer.put(2 * channelStride + i, b)
        }

        floatBuffer.rewind()
        return floatBuffer
    }
}