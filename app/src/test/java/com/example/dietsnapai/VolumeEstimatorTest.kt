package com.example.dietsnapai

import android.graphics.RectF
import com.example.dietsnapai.db.FoodNutritionRepository
import com.example.dietsnapai.ml.Calibrator
import com.example.dietsnapai.ml.DetectionResult
import com.example.dietsnapai.ml.VolumeEstimator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.cos
import kotlin.math.sin

class VolumeEstimatorTest {

    private val testIntrinsics = Calibrator.CameraIntrinsics(
        focalLengthMm = 4.25f,
        sensorWidthMm = 5.6f,
        sensorHeightMm = 4.2f
    )

    private fun makeRectF(left: Float, top: Float, right: Float, bottom: Float): RectF {
        val r = RectF()
        r.left = left
        r.top = top
        r.right = right
        r.bottom = bottom
        return r
    }

    private fun createBananaMask(radiusPx: Int, arcDegrees: Double, chordPx: Double): BooleanArray {
        val mask = BooleanArray(160 * 160)
        val steps = 100
        for (i in 0..steps) {
            val t = (i.toDouble() / steps) * Math.PI * (arcDegrees / 180.0)
            val cx = 35 + (cos(t) * chordPx)
            val cy = 35 + (sin(t) * chordPx * 1.05)
            for (dy in -radiusPx..radiusPx) {
                for (dx in -radiusPx..radiusPx) {
                    if (dx * dx + dy * dy <= radiusPx * radiusPx) {
                        val px = (cx + dx).toInt().coerceIn(0, 159)
                        val py = (cy + dy).toInt().coerceIn(0, 159)
                        mask[py * 160 + px] = true
                    }
                }
            }
        }
        return mask
    }

    private fun createCircleMask(centerX: Int, centerY: Int, radius: Int): BooleanArray {
        val mask = BooleanArray(160 * 160)
        for (y in (centerY - radius)..(centerY + radius)) {
            for (x in (centerX - radius)..(centerX + radius)) {
                val dx = x - centerX
                val dy = y - centerY
                if (dx * dx + dy * dy <= radius * radius) {
                    val px = x.coerceIn(0, 159)
                    val py = y.coerceIn(0, 159)
                    mask[py * 160 + px] = true
                }
            }
        }
        return mask
    }

    @Test
    fun testBananaContinuousDynamicScaling() {
        // 1. Small banana (radius ~3px, chord ~25px)
        val smallMask = createBananaMask(radiusPx = 3, arcDegrees = 90.0, chordPx = 25.0)
        val smallItem = DetectionResult(46, "banana", 0.88f, makeRectF(50f, 50f, 250f, 250f), smallMask)

        // 2. Large Cavendish banana (radius ~5px, chord ~36px)
        val largeMask = createBananaMask(radiusPx = 5, arcDegrees = 110.0, chordPx = 36.0)
        val largeItem = DetectionResult(46, "banana", 0.92f, makeRectF(50f, 50f, 400f, 400f), largeMask)

        val depthMap = Array(518) { FloatArray(518) { 0.95f } }

        val smallResult = VolumeEstimator.computePhysicalVolume(depthMap, smallItem, listOf(smallItem), testIntrinsics)
        val largeResult = VolumeEstimator.computePhysicalVolume(depthMap, largeItem, listOf(largeItem), testIntrinsics)

        val profile = FoodNutritionRepository.getNutritionalProfile("banana")
        val smallMacros = FoodNutritionRepository.calculateMacroBreakdown(profile, smallResult.volumeCm3)
        val largeMacros = FoodNutritionRepository.calculateMacroBreakdown(profile, largeResult.volumeCm3)

        println("Small Banana: width=${smallResult.widthCm}cm, length=${smallResult.lengthCm}cm, volume=${smallResult.volumeCm3} cm3, weight=${smallMacros.weightGrams}g, calories=${smallMacros.calories} kcal")
        println("Large Banana: width=${largeResult.widthCm}cm, length=${largeResult.lengthCm}cm, volume=${largeResult.volumeCm3} cm3, weight=${largeMacros.weightGrams}g, calories=${largeMacros.calories} kcal")

        // Must scale dynamically and continuously with real mask dimensions (NO artificial clamping)
        assertTrue("Large banana must have greater volume than small banana", largeResult.volumeCm3 > smallResult.volumeCm3 * 1.5f)
        assertTrue("Small banana weight (${smallMacros.weightGrams}g) should be in realistic 30g..70g range", smallMacros.weightGrams in 30f..70f)
        assertTrue("Large banana weight (${largeMacros.weightGrams}g) should be in realistic 85g..145g range", largeMacros.weightGrams in 85f..145f)
        assertTrue("Banana weight must definitely NEVER be 215g!", largeMacros.weightGrams < 155f)
    }

    @Test
    fun testAppleContinuousDynamicScaling() {
        // Small apple (radius 9px in 160x160 space)
        val smallMask = createCircleMask(80, 80, 9)
        val smallItem = DetectionResult(47, "apple", 0.90f, makeRectF(250f, 250f, 390f, 390f), smallMask)

        // Large apple (radius 14px in 160x160 space)
        val largeMask = createCircleMask(80, 80, 14)
        val largeItem = DetectionResult(47, "apple", 0.95f, makeRectF(200f, 200f, 440f, 440f), largeMask)

        val depthMap = Array(518) { FloatArray(518) { 0.95f } }

        val smallResult = VolumeEstimator.computePhysicalVolume(depthMap, smallItem, listOf(smallItem), testIntrinsics)
        val largeResult = VolumeEstimator.computePhysicalVolume(depthMap, largeItem, listOf(largeItem), testIntrinsics)

        val profile = FoodNutritionRepository.getNutritionalProfile("apple")
        val smallMacros = FoodNutritionRepository.calculateMacroBreakdown(profile, smallResult.volumeCm3)
        val largeMacros = FoodNutritionRepository.calculateMacroBreakdown(profile, largeResult.volumeCm3)

        println("Small Apple: diameter=${smallResult.widthCm}cm, volume=${smallResult.volumeCm3} cm3, weight=${smallMacros.weightGrams}g, calories=${smallMacros.calories} kcal")
        println("Large Apple: diameter=${largeResult.widthCm}cm, volume=${largeResult.volumeCm3} cm3, weight=${largeMacros.weightGrams}g, calories=${largeMacros.calories} kcal")

        // Sphere volume scales with R^3 -> (14/9)^3 ~ 3.75x volume difference!
        assertTrue("Large apple volume should scale cubically with radius", largeResult.volumeCm3 > smallResult.volumeCm3 * 2.0f)
        assertTrue("Small apple weight (${smallMacros.weightGrams}g) should be between 40g and 90g", smallMacros.weightGrams in 40f..90f)
        assertTrue("Large apple weight (${largeMacros.weightGrams}g) should be between 140g and 290g", largeMacros.weightGrams in 140f..290f)
    }

    @Test
    fun testMultiFruitSceneWithOverlappingOcclusion() {
        // Multi-fruit cluster: Banana in the background, Apple in the foreground partially occluding banana
        val bananaMask = createBananaMask(radiusPx = 4, arcDegrees = 110.0, chordPx = 34.0)
        val bananaBox = makeRectF(120f, 120f, 380f, 380f)

        // Apple positioned to overlap banana's lower boundary
        val appleMask = createCircleMask(65, 65, 12)
        val appleBox = makeRectF(220f, 220f, 340f, 340f)

        val bananaItem = DetectionResult(46, "banana", 0.88f, bananaBox, bananaMask)
        val appleItem = DetectionResult(47, "apple", 0.92f, appleBox, appleMask)

        // Depth map: Table is at 1.40m, Banana is at 1.05m, and Apple (closer) is at 0.75m
        val depthMap = Array(518) { FloatArray(518) { 1.40f } }
        val dScale = 518f / 640f

        val bTop = (bananaBox.top * dScale).toInt().coerceIn(0, 517)
        val bBot = (bananaBox.bottom * dScale).toInt().coerceIn(0, 517)
        val bLeft = (bananaBox.left * dScale).toInt().coerceIn(0, 517)
        val bRight = (bananaBox.right * dScale).toInt().coerceIn(0, 517)
        for (y in bTop..bBot) {
            for (x in bLeft..bRight) {
                depthMap[y][x] = 1.05f
            }
        }

        val aTop = (appleBox.top * dScale).toInt().coerceIn(0, 517)
        val aBot = (appleBox.bottom * dScale).toInt().coerceIn(0, 517)
        val aLeft = (appleBox.left * dScale).toInt().coerceIn(0, 517)
        val aRight = (appleBox.right * dScale).toInt().coerceIn(0, 517)
        for (y in aTop..aBot) {
            for (x in aLeft..aRight) {
                depthMap[y][x] = 0.75f // Apple is closer to camera
            }
        }

        // Process the entire scene simultaneously in ONE GO
        val sceneResults = VolumeEstimator.processScene(depthMap, listOf(bananaItem, appleItem), testIntrinsics)
        assertEquals(2, sceneResults.size)

        val bananaRes = sceneResults.first { it.candidate.className == "banana" }.volumeResult
        val appleRes = sceneResults.first { it.candidate.className == "apple" }.volumeResult

        println("Banana occluded: ${bananaRes.isOccluded}, ratio: ${bananaRes.occlusionRatio}, volume: ${bananaRes.volumeCm3} cm3")
        println("Apple occluded: ${appleRes.isOccluded}, volume: ${appleRes.volumeCm3} cm3")

        // Apple is in foreground -> not occluded
        assertFalse("Apple should NOT be occluded as it is the closest object", appleRes.isOccluded)
        // Banana is in background -> occluded by apple
        assertTrue("Banana should be detected as occluded by the nearer Apple", bananaRes.isOccluded)
        assertTrue("Banana occlusion ratio should be > 0", bananaRes.occlusionRatio > 0f)
        assertTrue("Banana volume should be realistic (50..135 cm3)", bananaRes.volumeCm3 in 50f..135f)
    }

    @Test
    fun testBottleVolumeRealistic() {
        val bottleMask = BooleanArray(160 * 160)
        for (y in 45..115) {
            for (x in 70..90) {
                bottleMask[y * 160 + x] = true
            }
        }
        val bottleBox = makeRectF(280f, 180f, 360f, 460f)
        val bottleItem = DetectionResult(39, "bottle", 0.85f, bottleBox, bottleMask)

        val depthMap = Array(518) { FloatArray(518) { 0.95f } }
        val result = VolumeEstimator.computePhysicalVolume(depthMap, bottleItem, listOf(bottleItem), testIntrinsics)
        val profile = FoodNutritionRepository.getNutritionalProfile("bottle")
        val macros = FoodNutritionRepository.calculateMacroBreakdown(profile, result.volumeCm3)

        println("Bottle volume: ${result.volumeCm3} cm3, weight: ${macros.weightGrams}g")

        // Bottle: ~400-600 ml / g
        assertTrue("Bottle volume should be between 350 and 650 cm3", result.volumeCm3 in 350f..650f)
        assertTrue("Bottle weight should be between 350 and 650g", macros.weightGrams in 350f..650f)
    }

    @Test
    fun testOverlappingBananasProduceDistinctDynamicWeights() {
        // Banana 1: Horizontal crossing banana (closer to camera)
        val b1Mask = BooleanArray(160 * 160)
        for (i in 0..100) {
            val t = (i.toDouble() / 100) * Math.PI
            val cx = 80.0 + (i - 50) * 0.7
            val cy = 80.0 + sin(t) * 8.0
            for (dy in -4..4) {
                for (dx in -4..4) {
                    if (dx * dx + dy * dy <= 16) {
                        val px = (cx + dx).toInt().coerceIn(0, 159)
                        val py = (cy + dy).toInt().coerceIn(0, 159)
                        b1Mask[py * 160 + px] = true
                    }
                }
            }
        }
        val b1Box = makeRectF(160f, 260f, 480f, 380f)
        val b1Item = DetectionResult(46, "banana", 0.91f, b1Box, b1Mask)

        // Banana 2: Vertical banana crossing beneath Banana 1 (deeper in background)
        val b2Mask = BooleanArray(160 * 160)
        for (i in 0..100) {
            val t = (i.toDouble() / 100) * Math.PI
            val cx = 80.0 + sin(t) * 9.0
            val cy = 85.0 + (i - 50) * 0.62
            for (dy in -4..4) {
                for (dx in -4..4) {
                    if (dx * dx + dy * dy <= 20) {
                        val px = (cx + dx).toInt().coerceIn(0, 159)
                        val py = (cy + dy).toInt().coerceIn(0, 159)
                        b2Mask[py * 160 + px] = true
                    }
                }
            }
        }
        val b2Box = makeRectF(260f, 200f, 380f, 480f)
        val b2Item = DetectionResult(46, "banana", 0.87f, b2Box, b2Mask)

        // Depth map: Banana 1 is closer (0.85m), Banana 2 is deeper (1.10m)
        val depthMap = Array(518) { FloatArray(518) { 1.40f } }
        val dScale = 518f / 640f

        val b2Top = (b2Box.top * dScale).toInt().coerceIn(0, 517)
        val b2Bot = (b2Box.bottom * dScale).toInt().coerceIn(0, 517)
        val b2Left = (b2Box.left * dScale).toInt().coerceIn(0, 517)
        val b2Right = (b2Box.right * dScale).toInt().coerceIn(0, 517)
        for (y in b2Top..b2Bot) {
            for (x in b2Left..b2Right) {
                depthMap[y][x] = 1.10f
            }
        }

        val b1Top = (b1Box.top * dScale).toInt().coerceIn(0, 517)
        val b1Bot = (b1Box.bottom * dScale).toInt().coerceIn(0, 517)
        val b1Left = (b1Box.left * dScale).toInt().coerceIn(0, 517)
        val b1Right = (b1Box.right * dScale).toInt().coerceIn(0, 517)
        for (y in b1Top..b1Bot) {
            for (x in b1Left..b1Right) {
                depthMap[y][x] = 0.85f // Banana 1 is closer
            }
        }

        val sceneResults = VolumeEstimator.processScene(depthMap, listOf(b1Item, b2Item), testIntrinsics)
        assertEquals(2, sceneResults.size)

        val profile = FoodNutritionRepository.getNutritionalProfile("banana")
        val b1Macros = FoodNutritionRepository.calculateMacroBreakdown(profile, sceneResults[0].volumeResult.volumeCm3)
        val b2Macros = FoodNutritionRepository.calculateMacroBreakdown(profile, sceneResults[1].volumeResult.volumeCm3)

        println("Banana 1: vol=${sceneResults[0].volumeResult.volumeCm3} cm3, wt=${b1Macros.weightGrams}g, isOcc=${sceneResults[0].volumeResult.isOccluded}")
        println("Banana 2: vol=${sceneResults[1].volumeResult.volumeCm3} cm3, wt=${b2Macros.weightGrams}g, isOcc=${sceneResults[1].volumeResult.isOccluded}")

        // Banana 1 (closer) should NOT be occluded
        assertFalse("Banana 1 is in front, should not be occluded", sceneResults[0].volumeResult.isOccluded)
        // Banana 2 (further) MUST be detected as occluded
        assertTrue("Banana 2 is behind, MUST be detected as occluded", sceneResults[1].volumeResult.isOccluded)

        // Neither banana should be hardcoded to 113g!
        assertFalse("Banana 1 weight (${b1Macros.weightGrams}g) must not clamp to 113.3g", kotlin.math.abs(b1Macros.weightGrams - 113.3f) < 0.2f)
        assertFalse("Banana 2 weight (${b2Macros.weightGrams}g) must not clamp to 113.3g", kotlin.math.abs(b2Macros.weightGrams - 113.3f) < 0.2f)

        // Overlapping bananas must produce DISTINCT weights derived from their actual dimensions
        val weightDiff = kotlin.math.abs(b1Macros.weightGrams - b2Macros.weightGrams)
        assertTrue("Overlapping bananas must have distinct weights based on their size (diff was $weightDiff)", weightDiff > 5.0f)

        // Both weights must be in natural biological range
        assertTrue("Banana 1 weight must be realistic (60g..135g)", b1Macros.weightGrams in 60f..135f)
        assertTrue("Banana 2 weight must be realistic (60g..135g)", b2Macros.weightGrams in 60f..135f)
    }

    @Test
    fun testOverlappingApplesReconstructFullSphere() {
        // Apple 1: In front (radius 12px, centered at 70, 75)
        val a1Mask = createCircleMask(70, 75, 12)
        val a1Box = makeRectF(230f, 250f, 330f, 350f)
        val a1Item = DetectionResult(47, "apple", 0.93f, a1Box, a1Mask)

        // Apple 2: Deeper in background (radius 13px, centered at 82, 75), overlapping Apple 1 by ~40%
        val a2Mask = createCircleMask(82, 75, 13)
        val a2Box = makeRectF(280f, 250f, 380f, 350f)
        val a2Item = DetectionResult(47, "apple", 0.89f, a2Box, a2Mask)

        val depthMap = Array(518) { FloatArray(518) { 1.40f } }
        val dScale = 518f / 640f

        val a2Top = (a2Box.top * dScale).toInt().coerceIn(0, 517)
        val a2Bot = (a2Box.bottom * dScale).toInt().coerceIn(0, 517)
        val a2Left = (a2Box.left * dScale).toInt().coerceIn(0, 517)
        val a2Right = (a2Box.right * dScale).toInt().coerceIn(0, 517)
        for (y in a2Top..a2Bot) {
            for (x in a2Left..a2Right) {
                depthMap[y][x] = 1.10f
            }
        }

        val a1Top = (a1Box.top * dScale).toInt().coerceIn(0, 517)
        val a1Bot = (a1Box.bottom * dScale).toInt().coerceIn(0, 517)
        val a1Left = (a1Box.left * dScale).toInt().coerceIn(0, 517)
        val a1Right = (a1Box.right * dScale).toInt().coerceIn(0, 517)
        for (y in a1Top..a1Bot) {
            for (x in a1Left..a1Right) {
                depthMap[y][x] = 0.80f // Apple 1 is closer
            }
        }

        val sceneResults = VolumeEstimator.processScene(depthMap, listOf(a1Item, a2Item), testIntrinsics)
        assertEquals(2, sceneResults.size)

        val profile = FoodNutritionRepository.getNutritionalProfile("apple")
        val a1Macros = FoodNutritionRepository.calculateMacroBreakdown(profile, sceneResults[0].volumeResult.volumeCm3)
        val a2Macros = FoodNutritionRepository.calculateMacroBreakdown(profile, sceneResults[1].volumeResult.volumeCm3)

        println("Apple 1 (front): dia=${sceneResults[0].volumeResult.widthCm}cm, wt=${a1Macros.weightGrams}g, isOcc=${sceneResults[0].volumeResult.isOccluded}")
        println("Apple 2 (back):  dia=${sceneResults[1].volumeResult.widthCm}cm, wt=${a2Macros.weightGrams}g, isOcc=${sceneResults[1].volumeResult.isOccluded}")

        assertFalse("Apple 1 is in front", sceneResults[0].volumeResult.isOccluded)
        assertTrue("Apple 2 is occluded", sceneResults[1].volumeResult.isOccluded)

        // Occluded Apple 2 must NOT shrink to a small fraction of its weight
        assertTrue("Apple 2 weight (${a2Macros.weightGrams}g) must remain realistic and not collapse (120g..240g)", a2Macros.weightGrams in 120f..240f)
        assertTrue("Apple 1 weight (${a1Macros.weightGrams}g) must be realistic (60g..150g)", a1Macros.weightGrams in 60f..150f)
    }
}
