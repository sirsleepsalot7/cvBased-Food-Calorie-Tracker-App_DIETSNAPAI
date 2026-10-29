package com.example.dietsnapai.ml

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import android.util.Log
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

    /**
     * Loads an image from a Content URI, corrects EXIF orientation, and extracts
     * a 1:1 centered square crop to preserve optical calibration with YOLO and Depth models.
     */
    fun loadSquareBitmapFromUri(context: Context, uri: Uri): Bitmap? {
        return try {
            // 1. Read bounds to prevent OutOfMemory on high-res camera photos
            val boundsOptions = BitmapFactory.Options().apply {
                inJustDecodeBounds = true
            }
            context.contentResolver.openInputStream(uri)?.use { stream ->
                BitmapFactory.decodeStream(stream, null, boundsOptions)
            } ?: return null

            val origWidth = boundsOptions.outWidth
            val origHeight = boundsOptions.outHeight
            if (origWidth <= 0 || origHeight <= 0) return null

            // Calculate sample size to keep max dimension ~1280px (optimal for 640x640 model while saving RAM)
            val maxTargetDim = 1280
            var sampleSize = 1
            val maxDim = maxOf(origWidth, origHeight)
            while ((maxDim / sampleSize) > maxTargetDim * 1.5) {
                sampleSize *= 2
            }

            // 2. Decode the sampled bitmap
            val decodeOptions = BitmapFactory.Options().apply {
                inSampleSize = sampleSize
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }
            val sampledBitmap = context.contentResolver.openInputStream(uri)?.use { stream ->
                BitmapFactory.decodeStream(stream, null, decodeOptions)
            } ?: return null

            // 3. Read EXIF orientation to correct rotated phone photos
            val orientation = try {
                context.contentResolver.openInputStream(uri)?.use { stream ->
                    val exif = ExifInterface(stream)
                    exif.getAttributeInt(
                        ExifInterface.TAG_ORIENTATION,
                        ExifInterface.ORIENTATION_NORMAL
                    )
                } ?: ExifInterface.ORIENTATION_NORMAL
            } catch (e: Exception) {
                ExifInterface.ORIENTATION_NORMAL
            }

            val rotationDegrees = when (orientation) {
                ExifInterface.ORIENTATION_ROTATE_90 -> 90f
                ExifInterface.ORIENTATION_ROTATE_180 -> 180f
                ExifInterface.ORIENTATION_ROTATE_270 -> 270f
                else -> 0f
            }

            val rotatedBitmap = if (rotationDegrees != 0f) {
                val matrix = Matrix().apply { postRotate(rotationDegrees) }
                val rotated = Bitmap.createBitmap(sampledBitmap, 0, 0, sampledBitmap.width, sampledBitmap.height, matrix, true)
                if (rotated != sampledBitmap) {
                    sampledBitmap.recycle()
                }
                rotated
            } else {
                sampledBitmap
            }

            // 4. Center-crop to 1:1 square to maintain camera metric scaling
            val minDim = minOf(rotatedBitmap.width, rotatedBitmap.height)
            val cropX = (rotatedBitmap.width - minDim) / 2
            val cropY = (rotatedBitmap.height - minDim) / 2
            val squareBitmap = Bitmap.createBitmap(rotatedBitmap, cropX, cropY, minDim, minDim)
            if (squareBitmap != rotatedBitmap) {
                rotatedBitmap.recycle()
            }

            squareBitmap
        } catch (e: Exception) {
            Log.e("DietSnapAI", "Failed to load image from URI", e)
            null
        }
    }
}