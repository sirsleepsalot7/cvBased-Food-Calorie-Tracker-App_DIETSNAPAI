package com.example.dietsnapai.ml

import android.content.Context
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.InputStream
import java.nio.FloatBuffer

data class YoloModelOutput(
    val output0: FloatArray,
    val protoMasks: FloatArray? = null
)

class ModelEngine(private val context: Context) {

    private val ortEnv: OrtEnvironment = OrtEnvironment.getEnvironment()
    private var yoloSession: OrtSession? = null
    private var depthSession: OrtSession? = null
    var isInitialized = false
        private set

    suspend fun initSessions() = withContext(Dispatchers.IO) {
        val sessionOptions = OrtSession.SessionOptions().apply {
            setIntraOpNumThreads(4)
            setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
        }

        val yoloFile = findAssetFile("yolov8n_seg.onnx", "yolov8n-seg.onnx")
        val yoloBytes = loadModelBytes(yoloFile)
        yoloSession = ortEnv.createSession(yoloBytes, sessionOptions)

        val depthFile = findAssetFile("depth_anything_v2_518.onnx", "depth_anything_v2_metric_indoor_small.onnx")
        val depthBytes = loadModelBytes(depthFile)
        depthSession = ortEnv.createSession(depthBytes, sessionOptions)

        isInitialized = true
    }

    private fun findAssetFile(vararg names: String): String {
        val assetList = context.assets.list("") ?: emptyArray()
        for (name in names) {
            if (name in assetList) return name
        }
        return names[0]
    }

    private fun loadModelBytes(filename: String): ByteArray {
        val inputStream: InputStream = context.assets.open(filename)
        val bytes = inputStream.readBytes()
        inputStream.close()
        return bytes
    }

    /**
     * Estimates metric / relative depth map with size 518x518.
     */
    fun estimateDepth(inputBuffer: FloatBuffer): Array<FloatArray>? {
        val session = depthSession ?: return null
        val shape = longArrayOf(1, 3, 518, 518)
        val tensor = OnnxTensor.createTensor(ortEnv, inputBuffer, shape)
        val inputName = session.inputNames.iterator().next()

        return try {
            val results = session.run(mapOf(inputName to tensor))
            val outputTensor = results[0] as? OnnxTensor
            if (outputTensor != null) {
                val fb = outputTensor.floatBuffer
                val depthMap = Array(518) { FloatArray(518) }
                for (y in 0 until 518) {
                    for (x in 0 until 518) {
                        depthMap[y][x] = if (fb.hasRemaining()) fb.get() else 0f
                    }
                }
                depthMap
            } else {
                null
            }
        } catch (e: Exception) {
            e.printStackTrace()
            null
        } finally {
            tensor.close()
        }
    }

    /**
     * Runs YOLOv8-seg inference on a 1x3x640x640 FloatBuffer.
     * Extracts output0 ([1, 116, 8400]) and protoMasks ([1, 32, 160, 160]).
     */
    fun detectFood(inputBuffer: FloatBuffer): YoloModelOutput? {
        val session = yoloSession ?: return null
        val shape = longArrayOf(1, 3, 640, 640)
        val tensor = OnnxTensor.createTensor(ortEnv, inputBuffer, shape)
        val inputName = session.inputNames.iterator().next()

        return try {
            val results = session.run(mapOf(inputName to tensor))
            var detTensor: OnnxTensor? = null
            var protoTensor: OnnxTensor? = null

            for (i in 0 until results.size()) {
                val t = results[i] as? OnnxTensor ?: continue
                val dims = t.info.shape
                if (dims.size == 3 && dims[1] >= 84L) {
                    detTensor = t
                } else if (dims.size == 4 && dims[1] == 32L) {
                    protoTensor = t
                }
            }

            if (detTensor == null && results.size() > 0) {
                detTensor = results[0] as? OnnxTensor
                if (results.size() > 1) {
                    protoTensor = results[1] as? OnnxTensor
                }
            }

            if (detTensor == null) return null

            val detFb = detTensor.floatBuffer
            val out0 = FloatArray(detFb.remaining())
            detFb.get(out0)

            val protoMasks = protoTensor?.let {
                val pfb = it.floatBuffer
                val pArr = FloatArray(pfb.remaining())
                pfb.get(pArr)
                pArr
            }

            YoloModelOutput(output0 = out0, protoMasks = protoMasks)
        } catch (e: Exception) {
            e.printStackTrace()
            null
        } finally {
            tensor.close()
        }
    }

    fun close() {
        yoloSession?.close()
        depthSession?.close()
        ortEnv.close()
    }
}