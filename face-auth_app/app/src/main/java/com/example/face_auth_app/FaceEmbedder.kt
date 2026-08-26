package com.example.face_auth_app

import android.content.Context
import android.graphics.Bitmap
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import java.io.File
import java.io.FileOutputStream
import java.nio.FloatBuffer
import kotlin.math.sqrt

class FaceEmbedder(context: Context) {

    private val environment = OrtEnvironment.getEnvironment()
    private val session: OrtSession

    init {
        val modelFile = File(context.filesDir, "w600k_r50.onnx")

        // Copy model only once.
        if (!modelFile.exists()) {
            context.assets.open("w600k_r50.onnx").use { input ->
                FileOutputStream(modelFile).use { output ->
                    input.copyTo(output, bufferSize = 1024 * 1024)
                }
            }
        }

        val options = OrtSession.SessionOptions()

        session = environment.createSession(
            modelFile.absolutePath,
            options
        )
    }

    fun getEmbedding(bitmap: Bitmap): FloatArray {

        val resized = Bitmap.createScaledBitmap(
            bitmap,
            112,
            112,
            true
        )

        val input = FloatArray(3 * 112 * 112)

        var rIndex = 0
        var gIndex = 112 * 112
        var bIndex = 2 * 112 * 112

        for (y in 0 until 112) {
            for (x in 0 until 112) {

                val pixel = resized.getPixel(x, y)

                val r = (pixel shr 16) and 0xFF
                val g = (pixel shr 8) and 0xFF
                val b = pixel and 0xFF

                input[rIndex++] =
                    (r - 127.5f) / 127.5f

                input[gIndex++] =
                    (g - 127.5f) / 127.5f

                input[bIndex++] =
                    (b - 127.5f) / 127.5f
            }
        }

        val tensor = OnnxTensor.createTensor(
            environment,
            FloatBuffer.wrap(input),
            longArrayOf(1, 3, 112, 112)
        )

        val results = session.run(
            mapOf("input.1" to tensor)
        )

        val output = results[0].value as Array<FloatArray>

        val embedding = output[0]

        tensor.close()
        results.close()

        return l2Normalize(embedding)
    }

    private fun l2Normalize(
        vector: FloatArray
    ): FloatArray {

        var sum = 0.0

        for (v in vector) {
            sum += v.toDouble() * v.toDouble()
        }

        val norm = sqrt(sum)

        return FloatArray(vector.size) {
            (vector[it] / norm).toFloat()
        }
    }

    fun close() {
        session.close()
    }
}