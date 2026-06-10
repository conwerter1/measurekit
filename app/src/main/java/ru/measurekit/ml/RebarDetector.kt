package ru.measurekit.ml

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import java.io.File
import java.nio.FloatBuffer
import kotlin.math.max
import kotlin.math.min

/**
 * Детектор торцов на основе YOLO11n ONNX моделей.
 *
 * Поддерживает ДВЕ модели:
 *  - "rebar_detector.onnx" - специализированная для арматуры (Huawei rebar, mAP50=98.4%, 1 класс)
 *  - "multi_detector.onnx" - универсальная (rebar/pipe/log, 3 класса, mAP50~73%)
 *
 * Контекст использования:
 *  - REBAR детектор: режим "Арматура" - максимальная точность для основной аудиторской задачи
 *  - MULTI детектор: режим "Труба/Кругляк/Своё" - распознаёт несколько типов с приличной точностью
 *
 * Архитектура моделей одинакова, отличается только число классов в выходе:
 *  - REBAR:  output [1, 5, 2100]    - cx, cy, w, h, conf
 *  - MULTI:  output [1, 4+nc, 2100] - cx, cy, w, h, conf_class0, conf_class1, conf_class2
 *      где nc=3 (rebar_end, pipe_end, log_end)
 */
class RebarDetector(
    context: Context,
    private val modelAsset: String,
    /** Сколько классов у этой модели (1 для rebar, 3 для multi). */
    private val numClasses: Int,
) {

    companion object {
        private const val TAG = "RebarDetector"
        private const val INPUT_SIZE = 320
        const val DEFAULT_CONF_THRESHOLD = 0.25f
        const val DEFAULT_IOU_THRESHOLD = 0.45f
    }

    /**
     * Детекция в координатах исходного фото.
     * @param classId 0 для rebar-модели всегда; для multi-модели определяет распознанный класс
     */
    data class Detection(
        val cx: Float,
        val cy: Float,
        val w: Float,
        val h: Float,
        val confidence: Float,
        val classId: Int = 0,
    ) {
        val left: Float get() = cx - w / 2
        val top: Float get() = cy - h / 2
        val right: Float get() = cx + w / 2
        val bottom: Float get() = cy + h / 2
    }

    private val env: OrtEnvironment by lazy { OrtEnvironment.getEnvironment() }

    @Volatile
    private var session: OrtSession? = null

    @Synchronized
    fun ensureLoaded(context: Context) {
        if (session != null) return
        Log.d(TAG, "Loading ONNX model from assets: $modelAsset")
        val t0 = System.currentTimeMillis()
        val modelFile = extractAssetIfNeeded(context)
        val opts = OrtSession.SessionOptions().apply {
            setIntraOpNumThreads(2)
            setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
        }
        session = env.createSession(modelFile.absolutePath, opts)
        val dt = System.currentTimeMillis() - t0
        Log.d(TAG, "Model $modelAsset loaded in $dt ms")
    }

    private fun extractAssetIfNeeded(context: Context): File {
        val dst = File(context.filesDir, modelAsset)
        if (dst.exists() && dst.length() > 1_000_000L) return dst
        context.assets.open(modelAsset).use { input ->
            dst.outputStream().use { output -> input.copyTo(output) }
        }
        return dst
    }

    /**
     * Запуск детекции.
     * @param classFilter если задан, в результат пойдут только детекции с этим classId
     */
    fun detect(
        bitmap: Bitmap,
        confThreshold: Float = DEFAULT_CONF_THRESHOLD,
        iouThreshold: Float = DEFAULT_IOU_THRESHOLD,
        classFilter: Int? = null,
    ): List<Detection> {
        val sess = session ?: error("Detector not loaded - call ensureLoaded() first")
        val srcW = bitmap.width
        val srcH = bitmap.height

        val letterbox = letterbox(bitmap, INPUT_SIZE)
        val scale = letterbox.scale
        val padX = letterbox.padX
        val padY = letterbox.padY
        val resized = letterbox.bitmap

        val inputArray = bitmapToCHWFloat(resized)
        val shape = longArrayOf(1, 3, INPUT_SIZE.toLong(), INPUT_SIZE.toLong())
        val tensor = OnnxTensor.createTensor(env, FloatBuffer.wrap(inputArray), shape)

        val t0 = System.currentTimeMillis()
        val outputs = sess.run(mapOf("images" to tensor))
        val outputTensor = outputs[0] as OnnxTensor
        val rawOutput = outputTensor.floatBuffer
        val infMs = System.currentTimeMillis() - t0
        Log.d(TAG, "Inference: $infMs ms")

        // Output: [1, 4+numClasses, 2100]
        // Layout: row 0..3 = bbox (cx, cy, w, h), row 4..4+nc-1 = class confidences
        val numBoxes = 2100
        val candidates = mutableListOf<Detection>()
        for (i in 0 until numBoxes) {
            val cx = rawOutput.get(0 * numBoxes + i)
            val cy = rawOutput.get(1 * numBoxes + i)
            val w = rawOutput.get(2 * numBoxes + i)
            val h = rawOutput.get(3 * numBoxes + i)

            // Найти класс с максимальной уверенностью
            var bestCls = 0
            var bestConf = rawOutput.get(4 * numBoxes + i)
            for (c in 1 until numClasses) {
                val conf = rawOutput.get((4 + c) * numBoxes + i)
                if (conf > bestConf) {
                    bestConf = conf
                    bestCls = c
                }
            }

            if (bestConf < confThreshold) continue
            if (classFilter != null && bestCls != classFilter) continue

            val origCx = (cx - padX) / scale
            val origCy = (cy - padY) / scale
            val origW = w / scale
            val origH = h / scale

            if (origCx < 0 || origCy < 0 || origW < 1 || origH < 1) continue
            if (origCx > srcW || origCy > srcH) continue

            candidates.add(Detection(origCx, origCy, origW, origH, bestConf, bestCls))
        }

        outputs.close()
        tensor.close()

        val final = nms(candidates, iouThreshold)
        Log.d(TAG, "Detections: ${candidates.size} candidates -> ${final.size} after NMS (filter=$classFilter)")
        return final
    }

    fun release() {
        session?.close()
        session = null
    }

    /* ---------------- Помощники ---------------- */

    private data class LetterboxResult(
        val bitmap: Bitmap, val scale: Float, val padX: Float, val padY: Float,
    )

    private fun letterbox(src: Bitmap, target: Int): LetterboxResult {
        val srcW = src.width.toFloat()
        val srcH = src.height.toFloat()
        val scale = min(target / srcW, target / srcH)
        val newW = (srcW * scale).toInt()
        val newH = (srcH * scale).toInt()
        val padX = (target - newW) / 2f
        val padY = (target - newH) / 2f
        val resized = Bitmap.createScaledBitmap(src, newW, newH, true)
        val out = Bitmap.createBitmap(target, target, Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(out)
        canvas.drawColor(android.graphics.Color.rgb(114, 114, 114))
        canvas.drawBitmap(resized, padX, padY, null)
        if (resized !== src) resized.recycle()
        return LetterboxResult(out, scale, padX, padY)
    }

    private fun bitmapToCHWFloat(bm: Bitmap): FloatArray {
        val w = bm.width
        val h = bm.height
        val pixels = IntArray(w * h)
        bm.getPixels(pixels, 0, w, 0, 0, w, h)
        val out = FloatArray(3 * w * h)
        val chSize = w * h
        for (i in pixels.indices) {
            val px = pixels[i]
            val r = ((px shr 16) and 0xFF) / 255f
            val g = ((px shr 8) and 0xFF) / 255f
            val b = (px and 0xFF) / 255f
            out[i] = r
            out[chSize + i] = g
            out[2 * chSize + i] = b
        }
        return out
    }

    private fun nms(boxes: List<Detection>, iouThreshold: Float): List<Detection> {
        val sorted = boxes.sortedByDescending { it.confidence }.toMutableList()
        val keep = mutableListOf<Detection>()
        while (sorted.isNotEmpty()) {
            val best = sorted.removeAt(0)
            keep.add(best)
            sorted.removeAll { iou(best, it) > iouThreshold }
        }
        return keep
    }

    private fun iou(a: Detection, b: Detection): Float {
        val xLeft = max(a.left, b.left)
        val yTop = max(a.top, b.top)
        val xRight = min(a.right, b.right)
        val yBottom = min(a.bottom, b.bottom)
        if (xRight < xLeft || yBottom < yTop) return 0f
        val intersect = (xRight - xLeft) * (yBottom - yTop)
        val areaA = a.w * a.h
        val areaB = b.w * b.h
        val union = areaA + areaB - intersect
        return if (union <= 0) 0f else intersect / union
    }
}
