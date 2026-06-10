package ru.measurekit.ui.screen.rebar

import android.content.Context
import ru.measurekit.domain.rebar.MaterialSpec
import ru.measurekit.ml.RebarDetector

/**
 * Singleton-кэш для двух детекторов (rebar и multi).
 * Каждая модель грузится один раз при первом обращении.
 */
object RebarDetectorHolder {

    @Volatile
    private var rebarInstance: RebarDetector? = null

    @Volatile
    private var multiInstance: RebarDetector? = null

    /**
     * Возвращает детектор для указанного типа материала.
     * Для арматуры используется специализированная модель (98.4% mAP),
     * для всего остального - универсальная мульти-модель.
     */
    fun getForMaterial(context: Context, material: MaterialSpec.MaterialType): RebarDetector {
        val kind = material.detectorModel
        return when (kind) {
            MaterialSpec.DetectorKind.REBAR_SPECIALIZED -> getRebar(context)
            MaterialSpec.DetectorKind.MULTI -> getMulti(context)
        }
    }

    private fun getRebar(context: Context): RebarDetector {
        return rebarInstance ?: synchronized(this) {
            rebarInstance ?: RebarDetector(
                context = context.applicationContext,
                modelAsset = "rebar_detector.onnx",
                numClasses = 1,
            ).also {
                it.ensureLoaded(context.applicationContext)
                rebarInstance = it
            }
        }
    }

    private fun getMulti(context: Context): RebarDetector {
        return multiInstance ?: synchronized(this) {
            multiInstance ?: RebarDetector(
                context = context.applicationContext,
                modelAsset = "multi_detector.onnx",
                numClasses = 3,
            ).also {
                it.ensureLoaded(context.applicationContext)
                multiInstance = it
            }
        }
    }

    /** Старый метод для обратной совместимости - возвращает rebar модель. */
    fun get(context: Context): RebarDetector = getRebar(context)
}
