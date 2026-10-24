package com.example.dietsnapai.ml

import android.graphics.RectF

data class DetectionResult(
    val classIndex: Int,
    val className: String,
    val confidence: Float,
    val box: RectF
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
        output: Array<Array<FloatArray>>,
        confThreshold: Float = 0.25f,
        iouThreshold: Float = 0.45f
    ): List<DetectionResult> {
        val candidates = mutableListOf<DetectionResult>()
        val numPredictions = 8400
        val numClasses = 80

        for (i in 0 until numPredictions) {
            var maxScore = 0f
            var bestClass = -1

            for (c in 0 until numClasses) {
                val score = output[0][4 + c][i]
                if (score > maxScore) {
                    maxScore = score
                    bestClass = c
                }
            }

            if (maxScore >= confThreshold && bestClass in COCO_CLASSES.indices) {
                val cx = output[0][0][i]
                val cy = output[0][1][i]
                val w = output[0][2][i]
                val h = output[0][3][i]

                val left = (cx - w / 2f).coerceIn(0f, 640f)
                val top = (cy - h / 2f).coerceIn(0f, 640f)
                val right = (cx + w / 2f).coerceIn(0f, 640f)
                val bottom = (cy + h / 2f).coerceIn(0f, 640f)

                val label = COCO_CLASSES[bestClass]

                candidates.add(
                    DetectionResult(
                        classIndex = bestClass,
                        className = label,
                        confidence = maxScore,
                        box = RectF(left, top, right, bottom)
                    )
                )
            }
        }

        return applyNms(candidates, iouThreshold)
    }

    fun findFoodObjects(detections: List<DetectionResult>): List<DetectionResult> {
        return detections.filter { it.className.lowercase() in FOOD_CLASSES }
    }

    private fun applyNms(boxes: List<DetectionResult>, iouThreshold: Float): List<DetectionResult> {
        val sorted = boxes.sortedByDescending { it.confidence }.toMutableList()
        val selected = mutableListOf<DetectionResult>()

        while (sorted.isNotEmpty()) {
            val best = sorted.removeAt(0)
            selected.add(best)

            val iterator = sorted.iterator()
            while (iterator.hasNext()) {
                val other = iterator.next()
                if (best.classIndex == other.classIndex && computeIou(best.box, other.box) >= iouThreshold) {
                    iterator.remove()
                }
            }
        }

        return selected
    }

    private fun computeIou(a: RectF, b: RectF): Float {
        val interLeft = maxOf(a.left, b.left)
        val interTop = maxOf(a.top, b.top)
        val interRight = minOf(a.right, b.right)
        val interBottom = minOf(a.bottom, b.bottom)

        if (interRight < interLeft || interBottom < interTop) return 0f
        val interArea = (interRight - interLeft) * (interBottom - interTop)
        val unionArea = (a.width() * a.height()) + (b.width() * b.height()) - interArea
        return if (unionArea > 0f) interArea / unionArea else 0f
    }
}
