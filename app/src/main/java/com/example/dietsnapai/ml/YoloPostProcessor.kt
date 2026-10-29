package com.example.dietsnapai.ml

import android.graphics.RectF

data class DetectionResult(
    val classIndex: Int,
    val className: String,
    val confidence: Float,
    val box: RectF,
    val mask: BooleanArray? = null, // 160x160 binary mask
    val maskWidth: Int = 160,
    val maskHeight: Int = 160,
    var isOccluded: Boolean = false,
    var occlusionRatio: Float = 0f,
    var amodalScale: Float = 1.0f
)

object YoloPostProcessor {

    val COCO_CLASSES = listOf(
        "person", "bicycle", "car", "motorcycle", "airplane", "bus", "train", "truck", "boat", "traffic light",
        "fire hydrant", "stop sign", "parking meter", "bench", "bird", "cat", "dog", "horse", "sheep", "cow",
        "elephant", "bear", "zebra", "giraffe", "backpack", "umbrella", "handbag", "tie", "suitcase", "frisbee",
        "skis", "snowboard", "sports ball", "kite", "baseball bat", "baseball glove", "skateboard", "surfboard", "tennis racket", "bottle",
        "wine glass", "cup", "fork", "knife", "spoon", "bowl", "banana", "apple", "sandwich", "orange",
        "broccoli", "carrot", "hot dog", "pizza", "donut", "cake", "chair", "couch", "potted plant", "bed",
        "dining table", "toilet", "tv", "laptop", "mouse", "remote", "keyboard", "cell phone", "microwave", "oven",
        "toaster", "sink", "refrigerator", "book", "clock", "vase", "scissors", "teddy bear", "hair drier", "toothbrush"
    )

    val FOOD_CLASSES = setOf(
        "banana", "apple", "sandwich", "orange", "broccoli", "carrot",
        "hot dog", "pizza", "donut", "cake", "bowl", "cup", "bottle"
    )

    fun processOutput(
        yoloOutput: YoloModelOutput,
        confThreshold: Float = 0.20f,
        iouThreshold: Float = 0.45f
    ): List<DetectionResult> {
        val output0 = yoloOutput.output0
        val protoMasks = yoloOutput.protoMasks
        val numPredictions = 8400
        val numClasses = 80
        val numMasks = 32

        val candidates = mutableListOf<DetectionResult>()

        for (i in 0 until numPredictions) {
            var maxScore = 0f
            var bestClass = -1

            for (c in 0 until numClasses) {
                val score = output0[(4 + c) * numPredictions + i]
                if (score > maxScore) {
                    maxScore = score
                    bestClass = c
                }
            }

            if (maxScore >= confThreshold && bestClass in COCO_CLASSES.indices) {
                val label = COCO_CLASSES[bestClass]
                if (label.lowercase() !in FOOD_CLASSES) continue

                val cx = output0[0 * numPredictions + i]
                val cy = output0[1 * numPredictions + i]
                val w = output0[2 * numPredictions + i]
                val h = output0[3 * numPredictions + i]

                val left = (cx - w / 2f).coerceIn(0f, 640f)
                val top = (cy - h / 2f).coerceIn(0f, 640f)
                val right = (cx + w / 2f).coerceIn(0f, 640f)
                val bottom = (cy + h / 2f).coerceIn(0f, 640f)

                val box = RectF(left, top, right, bottom)
                if ((box.right - box.left) < 8f || (box.bottom - box.top) < 8f) continue

                var mask: BooleanArray? = null
                if (protoMasks != null && protoMasks.size >= numMasks * 160 * 160) {
                    val coefs = FloatArray(numMasks) { m ->
                        output0[(84 + m) * numPredictions + i]
                    }
                    mask = decodeMask(coefs, protoMasks, box)
                }
                if (mask == null || mask.count { it } < 15) {
                    mask = VolumeEstimator.generateDefaultMask160(box)
                }

                candidates.add(
                    DetectionResult(
                        classIndex = bestClass,
                        className = label,
                        confidence = maxScore,
                        box = box,
                        mask = mask
                    )
                )
            }
        }

        val nmsResults = applyEnhancedNms(candidates, iouThreshold)
        return nmsResults
    }

    private fun decodeMask(coefs: FloatArray, protoMasks: FloatArray, box: RectF): BooleanArray {
        val mask = BooleanArray(160 * 160)
        val bx1 = ((box.left / 640f) * 160f).toInt().coerceIn(0, 159)
        val by1 = ((box.top / 640f) * 160f).toInt().coerceIn(0, 159)
        val bx2 = ((box.right / 640f) * 160f).toInt().coerceIn(0, 159)
        val by2 = ((box.bottom / 640f) * 160f).toInt().coerceIn(0, 159)

        for (py in by1..by2) {
            val rowOffset = py * 160
            for (px in bx1..bx2) {
                val pIdx = rowOffset + px
                var logit = 0f
                for (m in 0 until 32) {
                    logit += coefs[m] * protoMasks[m * 25600 + pIdx]
                }
                if (logit > 0f) {
                    mask[pIdx] = true
                }
            }
        }
        return mask
    }

    fun findFoodObjects(detections: List<DetectionResult>): List<DetectionResult> {
        return detections.filter { it.className.lowercase() in FOOD_CLASSES }
    }

    /**
     * Advanced NMS that handles:
     * 1. Multi-fruit bunches / touching fruits (preserves touching distinct items)
     * 2. Hallucinatory / merged enveloper boxes (suppresses giant duplicate boxes via IoMin)
     * 3. Cross-class duplicate detections on the same fruit
     */
    private fun applyEnhancedNms(boxes: List<DetectionResult>, iouThreshold: Float): List<DetectionResult> {
        val sorted = boxes.sortedByDescending { it.confidence }.toMutableList()
        val selected = mutableListOf<DetectionResult>()

        while (sorted.isNotEmpty()) {
            val best = sorted.removeAt(0)
            selected.add(best)

            val iterator = sorted.iterator()
            while (iterator.hasNext()) {
                val other = iterator.next()
                val boxIou = computeIou(best.box, other.box)
                val boxIoMin = computeIoMin(best.box, other.box)

                val maskIoMin = if (best.mask != null && other.mask != null) {
                    computeMaskIoMin(best.mask, other.mask)
                } else null

                val shouldSuppress = if (best.classIndex == other.classIndex) {
                    if (maskIoMin != null) {
                        // True Mask-IoMin: Overlapping distinct fruits (e.g. crossing bananas)
                        // share high box IoU but low mask overlap (< 35%). Suppress only if masks duplicate (>= 40%).
                        maskIoMin >= 0.40f
                    } else {
                        boxIoMin >= 0.75f || boxIou >= iouThreshold
                    }
                } else {
                    // Different classes for the exact same physical fruit
                    if (maskIoMin != null) {
                        maskIoMin >= 0.50f
                    } else {
                        boxIoMin >= 0.85f || boxIou >= 0.65f
                    }
                }

                if (shouldSuppress) {
                    iterator.remove()
                }
            }
        }

        return selected
    }

    /**
     * Resolves minor overlapping pixel conflicts between adjacent/overlapping fruits.
     * Higher-confidence items claim contested pixels.
     */
    private fun resolveMaskOverlaps(items: List<DetectionResult>): List<DetectionResult> {
        if (items.size <= 1) return items
        val claimed = BooleanArray(160 * 160)

        return items.map { item ->
            val m = item.mask
            if (m != null) {
                val resolved = BooleanArray(160 * 160)
                var count = 0
                for (i in 0 until 160 * 160) {
                    if (m[i] && !claimed[i]) {
                        resolved[i] = true
                        claimed[i] = true
                        count++
                    }
                }
                if (count > 25) {
                    item.copy(mask = resolved)
                } else {
                    item
                }
            } else {
                item
            }
        }
    }

    private fun computeIou(a: RectF, b: RectF): Float {
        val interLeft = maxOf(a.left, b.left)
        val interTop = maxOf(a.top, b.top)
        val interRight = minOf(a.right, b.right)
        val interBottom = minOf(a.bottom, b.bottom)

        if (interRight < interLeft || interBottom < interTop) return 0f
        val interArea = (interRight - interLeft) * (interBottom - interTop)
        val areaA = (a.right - a.left) * (a.bottom - a.top)
        val areaB = (b.right - b.left) * (b.bottom - b.top)
        val unionArea = areaA + areaB - interArea
        return if (unionArea > 0f) interArea / unionArea else 0f
    }

    private fun computeIoMin(a: RectF, b: RectF): Float {
        val interLeft = maxOf(a.left, b.left)
        val interTop = maxOf(a.top, b.top)
        val interRight = minOf(a.right, b.right)
        val interBottom = minOf(a.bottom, b.bottom)

        if (interRight < interLeft || interBottom < interTop) return 0f
        val interArea = (interRight - interLeft) * (interBottom - interTop)
        val areaA = (a.right - a.left) * (a.bottom - a.top)
        val areaB = (b.right - b.left) * (b.bottom - b.top)
        val minArea = minOf(areaA, areaB)
        return if (minArea > 0f) interArea / minArea else 0f
    }

    private fun computeMaskIoMin(maskA: BooleanArray, maskB: BooleanArray): Float {
        var inter = 0
        var countA = 0
        var countB = 0
        val size = minOf(maskA.size, maskB.size)
        for (i in 0 until size) {
            val a = maskA[i]
            val b = maskB[i]
            if (a) countA++
            if (b) countB++
            if (a && b) inter++
        }
        val minCount = minOf(countA, countB)
        return if (minCount > 0) inter.toFloat() / minCount else 0f
    }
}
