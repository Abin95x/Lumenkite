package com.skyroll.app.explore

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PointF
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import kotlin.math.sqrt

/**
 * Turns a face into a 128-number "fingerprint" with OpenCV's SFace model (Apache-2.0).
 * Two photos of the same person give fingerprints pointing the same way (high cosine similarity).
 */
class FaceEmbedder(context: Context) : AutoCloseable {
    private val env = OrtEnvironment.getEnvironment()
    private val session: OrtSession = env.createSession(
        context.assets.open("sface_int8.onnx").use { it.readBytes() },
        OrtSession.SessionOptions().apply { setIntraOpNumThreads(2) },
    )
    private val inputName = session.inputNames.first()

    /**
     * [landmarks]: two eyes, nose, two mouth corners (any order within each pair).
     * Returns null if the face can't be aligned.
     */
    fun embed(image: Bitmap, eyes: List<PointF>, nose: PointF, mouth: List<PointF>): FloatArray? {
        if (eyes.size != 2 || mouth.size != 2) return null
        val e = eyes.sortedBy { it.x }
        val m = mouth.sortedBy { it.x }
        val src = listOf(e[0], e[1], nose, m[0], m[1])
        val aligned = align(image, src) ?: return null
        val input = toTensorData(aligned)
        aligned.recycle()
        OnnxTensor.createTensor(env, input, longArrayOf(1, 3, SIZE.toLong(), SIZE.toLong())).use { tensor ->
            session.run(mapOf(inputName to tensor)).use { out ->
                @Suppress("UNCHECKED_CAST")
                val raw = (out[0].value as Array<FloatArray>)[0]
                return normalize(raw)
            }
        }
    }

    /** Warps the face so the 5 points land on the standard 112×112 template (similarity transform). */
    private fun align(image: Bitmap, src: List<PointF>): Bitmap? {
        val n = src.size
        val sx = src.sumOf { it.x.toDouble() } / n
        val sy = src.sumOf { it.y.toDouble() } / n
        val dx = TEMPLATE.sumOf { it.x.toDouble() } / n
        val dy = TEMPLATE.sumOf { it.y.toDouble() } / n
        var num1 = 0.0; var num2 = 0.0; var den = 0.0
        for (i in 0 until n) {
            val xs = src[i].x - sx; val ys = src[i].y - sy
            val xd = TEMPLATE[i].x - dx; val yd = TEMPLATE[i].y - dy
            num1 += xs * xd + ys * yd
            num2 += xs * yd - ys * xd
            den += xs * xs + ys * ys
        }
        if (den < 1e-6) return null
        val a = num1 / den
        val b = num2 / den
        val tx = dx - (a * sx - b * sy)
        val ty = dy - (b * sx + a * sy)
        val matrix = Matrix().apply {
            setValues(floatArrayOf(a.toFloat(), (-b).toFloat(), tx.toFloat(), b.toFloat(), a.toFloat(), ty.toFloat(), 0f, 0f, 1f))
        }
        val out = Bitmap.createBitmap(SIZE, SIZE, Bitmap.Config.ARGB_8888)
        Canvas(out).drawBitmap(image, matrix, Paint(Paint.FILTER_BITMAP_FLAG))
        return out
    }

    /** NCHW float RGB in 0..255, as OpenCV's FaceRecognizerSF feeds the model. */
    private fun toTensorData(bmp: Bitmap): FloatBuffer {
        val px = IntArray(SIZE * SIZE)
        bmp.getPixels(px, 0, SIZE, 0, 0, SIZE, SIZE)
        val buf = FloatBuffer.allocate(3 * SIZE * SIZE)
        val plane = SIZE * SIZE
        for (i in 0 until plane) {
            val c = px[i]
            buf.put(i, ((c shr 16) and 0xFF).toFloat())
            buf.put(plane + i, ((c shr 8) and 0xFF).toFloat())
            buf.put(2 * plane + i, (c and 0xFF).toFloat())
        }
        buf.rewind()
        return buf
    }

    override fun close() {
        session.close()
    }

    companion object {
        private const val SIZE = 112

        /** ArcFace/SFace reference landmark positions in a 112×112 crop. */
        private val TEMPLATE = listOf(
            PointF(38.2946f, 51.6963f), PointF(73.5318f, 51.5014f), PointF(56.0252f, 71.7366f),
            PointF(41.5493f, 92.3655f), PointF(70.7299f, 92.2041f),
        )

        /** OpenCV's recommended "same person" cosine threshold for SFace. */
        const val SAME_PERSON = 0.363f

        fun normalize(v: FloatArray): FloatArray {
            var s = 0f
            for (x in v) s += x * x
            val n = sqrt(s).takeIf { it > 1e-6f } ?: return v
            return FloatArray(v.size) { v[it] / n }
        }

        fun cosine(a: FloatArray, b: FloatArray): Float {
            var s = 0f
            for (i in a.indices) s += a[i] * b[i]
            return s
        }

        fun toBytes(v: FloatArray): ByteArray =
            ByteBuffer.allocate(v.size * 4).order(ByteOrder.LITTLE_ENDIAN).apply { v.forEach { putFloat(it) } }.array()

        fun fromBytes(b: ByteArray): FloatArray {
            val bb = ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN)
            return FloatArray(b.size / 4) { bb.getFloat() }
        }
    }
}
