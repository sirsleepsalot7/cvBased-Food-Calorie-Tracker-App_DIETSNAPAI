package com.example.dietsnapai.ml

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import android.os.Build
import android.util.Log
import java.io.ByteArrayInputStream
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
     * Uses Android ImageDecoder on API 28+ and single-stream readBytes fallback for maximum reliability.
     */
    fun loadSquareBitmapFromUri(context: Context, uri: Uri): Bitmap? {
        // Strategy 1: Modern ImageDecoder (API 28+) handles EXIF, hardware color spaces, and SAF URIs natively
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            try {
                val source = ImageDecoder.createSource(context.contentResolver, uri)
                val decodedBitmap = ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
                    decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                    decoder.isMutableRequired = true
                    val maxDim = maxOf(info.size.width, info.size.height)
                    if (maxDim > 1600) {
                        val scale = 1280f / maxDim
                        val targetW = (info.size.width * scale).toInt().coerceAtLeast(1)
                        val targetH = (info.size.height * scale).toInt().coerceAtLeast(1)
                        decoder.setTargetSize(targetW, targetH)
                    }
                }

                val minDim = minOf(decodedBitmap.width, decodedBitmap.height)
                val cropX = (decodedBitmap.width - minDim) / 2
                val cropY = (decodedBitmap.height - minDim) / 2
                val square = Bitmap.createBitmap(decodedBitmap, cropX, cropY, minDim, minDim)
                if (square != decodedBitmap) {
                    decodedBitmap.recycle()
                }
                val finalBitmap = if (square.config != Bitmap.Config.ARGB_8888) {
                    val copy = square.copy(Bitmap.Config.ARGB_8888, false)
                    square.recycle()
                    copy ?: square
                } else {
                    square
                }
                return finalBitmap
            } catch (e: Exception) {
                Log.w("DietSnapAI", "ImageDecoder failed, falling back to byte stream: ${e.message}")
            }
        }

        // Strategy 2: Single-stream readBytes fallback (reads stream ONCE to prevent SAF permission revocation)
        return try {
            val bytes = context.contentResolver.openInputStream(uri)?.use { stream ->
                stream.readBytes()
            } ?: return null

            if (bytes.isEmpty()) return null

            // 1. Read EXIF orientation from the byte array
            val orientation = try {
                ByteArrayInputStream(bytes).use { bais ->
                    val exif = ExifInterface(bais)
                    exif.getAttributeInt(
                        ExifInterface.TAG_ORIENTATION,
                        ExifInterface.ORIENTATION_NORMAL
                    )
                }
            } catch (e: Exception) {
                ExifInterface.ORIENTATION_NORMAL
            }

            val rotationDegrees = when (orientation) {
                ExifInterface.ORIENTATION_ROTATE_90 -> 90f
                ExifInterface.ORIENTATION_ROTATE_180 -> 180f
                ExifInterface.ORIENTATION_ROTATE_270 -> 270f
                else -> 0f
            }

            // 2. Decode bounds to determine sample size
            val boundsOptions = BitmapFactory.Options().apply {
                inJustDecodeBounds = true
            }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, boundsOptions)

            val origWidth = boundsOptions.outWidth
            val origHeight = boundsOptions.outHeight
            if (origWidth <= 0 || origHeight <= 0) return null

            val maxTargetDim = 1280
            var sampleSize = 1
            val maxDim = maxOf(origWidth, origHeight)
            while ((maxDim / sampleSize) > maxTargetDim * 1.5) {
                sampleSize *= 2
            }

            // 3. Decode the actual sampled bitmap from byte array
            val decodeOptions = BitmapFactory.Options().apply {
                inSampleSize = sampleSize
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }
            val sampledBitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, decodeOptions) ?: return null

            // 4. Rotate according to EXIF
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

            // 5. Center-crop to 1:1 square
            val minDim = minOf(rotatedBitmap.width, rotatedBitmap.height)
            val cropX = (rotatedBitmap.width - minDim) / 2
            val cropY = (rotatedBitmap.height - minDim) / 2
            val squareBitmap = Bitmap.createBitmap(rotatedBitmap, cropX, cropY, minDim, minDim)
            if (squareBitmap != rotatedBitmap) {
                rotatedBitmap.recycle()
            }

            val finalBitmap = if (squareBitmap.config != Bitmap.Config.ARGB_8888) {
                val copy = squareBitmap.copy(Bitmap.Config.ARGB_8888, false)
                squareBitmap.recycle()
                copy ?: squareBitmap
            } else {
                squareBitmap
            }

            finalBitmap
        } catch (e: Exception) {
            Log.e("DietSnapAI", "Failed to load image from URI: ${e.message}", e)
            null
        }
    }
}