package com.example.dietsnapai.ml

import android.content.Context
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.InputStream
import java.nio.FloatBuffer

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

        val yoloBytes = loadModelBytes("yolov8n_seg.onnx")
        yoloSession = ortEnv.createSession(yoloBytes, sessionOptions)

        val depthBytes = loadModelBytes("depth_anything_v2_518.onnx")
        depthSession = ortEnv.createSession(depthBytes, sessionOptions)

        isInitialized = true
    }

    private fun loadModelBytes(filename: String): ByteArray {
        val inputStream: InputStream = context.assets.open(filename)
        val bytes = inputStream.readBytes()
        inputStream.close()
        return bytes
    }

    fun estimateDepth(inputBuffer: FloatBuffer): Array<FloatArray>? {
        val session = depthSession ?: return null
        val shape = longArrayOf(1, 3, 518, 518)
        val tensor = OnnxTensor.createTensor(ortEnv, inputBuffer, shape)

        val results = session.run(mapOf("image" to tensor))
        @Suppress("UNCHECKED_CAST")
        val outputTensor = results[0].value as? Array<Array<FloatArray>>

        tensor.close()
        results.close()

        return outputTensor?.get(0)
    }

    /**
     * Runs YOLOv8-seg inference on a 1x3x640x640 FloatBuffer.
     * Returns the raw output tensor shape: [1, 116, 8400].
     */
    fun detectFood(inputBuffer: FloatBuffer): Array<Array<FloatArray>>? {
        val session = yoloSession ?: return null
        val shape = longArrayOf(1, 3, 640, 640)
        val tensor = OnnxTensor.createTensor(ortEnv, inputBuffer, shape)

        val results = session.run(mapOf("images" to tensor))
        @Suppress("UNCHECKED_CAST")
        val output = results[0].value as? Array<Array<FloatArray>>

        tensor.close()
        results.close()

        return output
    }

    fun close() {
        yoloSession?.close()
        depthSession?.close()
        ortEnv.close()
    }
}