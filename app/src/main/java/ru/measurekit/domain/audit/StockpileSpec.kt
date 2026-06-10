package ru.measurekit.domain.audit

import kotlin.math.PI
import kotlin.math.tan

/**
 * Справочник для модуля обмера штабелей нерудных материалов на карьере / складе.
 *
 * Базовые источники:
 *  - ГОСТ 8267-93 — щебень и гравий из плотных горных пород
 *  - ГОСТ 8736-2014 — пески строительные
 *  - ГОСТ 25607-2009 — материалы нерудные для щебёночных и гравийных оснований
 *  - Действующий ГОСТ — коэффициент уплотнения щебня не более 1.1 (применяется
 *    при приёмке: V_насыпной × 1.1 при доставке).
 *
 * Углы естественного откоса — приведены справочные значения, в реальности
 * зависят от влажности и крупности фракции, поэтому пользователь может корректировать.
 */
object StockpileSpec {

    /** Тип материала с его насыпной плотностью и углом откоса. */
    enum class Material(
        val label: String,
        val bulkDensity: Double,          // т/м³ насыпная плотность
        val angleOfReposeDeg: Double,     // угол естественного откоса, градусы
        val gostRef: String,
    ) {
        // Известняковый щебень (типичен для AKKUYU и средней полосы РФ)
        LIMESTONE_5_10  ("Щебень известн. 5-10", 1.40, 37.0, "ГОСТ 8267"),
        LIMESTONE_10_20 ("Щебень известн. 10-20", 1.36, 37.0, "ГОСТ 8267"),
        LIMESTONE_20_40 ("Щебень известн. 20-40", 1.32, 38.0, "ГОСТ 8267"),
        LIMESTONE_40_70 ("Щебень известн. 40-70", 1.30, 38.0, "ГОСТ 8267"),

        // Гранитный щебень (плотнее)
        GRANITE_5_10  ("Щебень гранит. 5-10",  1.45, 37.0, "ГОСТ 8267"),
        GRANITE_10_20 ("Щебень гранит. 10-20", 1.42, 37.0, "ГОСТ 8267"),
        GRANITE_20_40 ("Щебень гранит. 20-40", 1.40, 38.0, "ГОСТ 8267"),

        // Гравий
        GRAVEL_5_10  ("Гравий 5-10",  1.50, 35.0, "ГОСТ 8267"),
        GRAVEL_10_20 ("Гравий 10-20", 1.48, 35.0, "ГОСТ 8267"),
        GRAVEL_20_40 ("Гравий 20-40", 1.45, 36.0, "ГОСТ 8267"),

        // Пески
        SAND_NATURAL  ("Песок природн.", 1.50, 32.0, "ГОСТ 8736"),
        SAND_CRUSHED  ("Песок дроблёный", 1.55, 35.0, "ГОСТ 8736"),

        // Прочее
        SCREENING     ("Отсев дробления", 1.40, 35.0, "ГОСТ 8267"),
        PGS           ("ПГС (песчано-гравийная)", 1.65, 33.0, "ГОСТ 23735");

        companion object { fun all(): List<Material> = entries.toList() }
    }

    /** Форма штабеля. */
    enum class Shape(val label: String) {
        CONE("Конус"),                       // правильный конус (округлый штабель)
        FRUSTUM("Усечённый конус"),           // часто отсыпают «усечкой»
        TRAPEZOID_PRISM("Призма трапец."),    // длинный штабель, в сечении трапеция
        BOX("Параллелепипед");                // штабель в боксах с бортами

        companion object { fun all(): List<Shape> = entries.toList() }
    }

    /**
     * Объём для разных форм штабеля.
     * @param dims размеры в метрах: для CONE [радиус_основания, высота];
     *             FRUSTUM [r_низа, r_верха, высота];
     *             TRAPEZOID_PRISM [длина, ширина_низа, ширина_верха, высота];
     *             BOX [длина, ширина, высота].
     * @return объём в м³ или null при неверных размерах.
     */
    fun volume(shape: Shape, vararg dims: Double): Double? {
        if (dims.any { it <= 0 }) return null
        return when (shape) {
            Shape.CONE -> {
                if (dims.size < 2) return null
                val r = dims[0]; val h = dims[1]
                PI * r * r * h / 3.0
            }
            Shape.FRUSTUM -> {
                if (dims.size < 3) return null
                val r1 = dims[0]; val r2 = dims[1]; val h = dims[2]
                PI * h / 3.0 * (r1*r1 + r1*r2 + r2*r2)
            }
            Shape.TRAPEZOID_PRISM -> {
                if (dims.size < 4) return null
                val l = dims[0]; val a = dims[1]; val b = dims[2]; val h = dims[3]
                l * (a + b) / 2.0 * h
            }
            Shape.BOX -> {
                if (dims.size < 3) return null
                dims[0] * dims[1] * dims[2]
            }
        }
    }

    /**
     * Подсказка какая высота возможна при данном радиусе основания и угле откоса:
     * h = r × tan(α). Полезна как контрольный замер: если измеренная высота сильно
     * больше — штабель явно с «горкой» или промёрзший.
     */
    fun expectedConeHeight(material: Material, baseRadiusM: Double): Double {
        val angleRad = material.angleOfReposeDeg * PI / 180.0
        return baseRadiusM * tan(angleRad)
    }

    /** Коэффициент уплотнения по действующему ГОСТ — не более 1.10. */
    const val COMPACTION_MAX = 1.10

    /**
     * Расчёт массы штабеля.
     * @param applyCompaction если true — масса с учётом уплотнения (приёмочная);
     *                        если false — масса по насыпной плотности (учётная).
     */
    fun mass(volumeM3: Double, material: Material, applyCompaction: Boolean = false): Double {
        val k = if (applyCompaction) COMPACTION_MAX else 1.0
        return volumeM3 * material.bulkDensity * k
    }

    data class StockpileResult(
        val material: Material,
        val shape: Shape,
        val volumeM3: Double,
        val massNominalT: Double,         // по насыпной плотности
        val massWithCompactionT: Double,  // с уплотнением k=1.1
        val toleranceT: Double,           // ±10% от номинала (геом. погрешность обмера)
        val description: String,
    ) {
        val massMinT: Double get() = massNominalT - toleranceT
        val massMaxT: Double get() = massNominalT + toleranceT
    }

    /** Полный расчёт по штабелю. */
    fun calculate(
        material: Material,
        shape: Shape,
        vararg dims: Double,
    ): StockpileResult? {
        val v = volume(shape, *dims) ?: return null
        val m = mass(v, material, applyCompaction = false)
        val mC = mass(v, material, applyCompaction = true)
        val tol = m * 0.10  // ±10% — реалистичная погрешность обмера штабеля рулеткой
        val dimText = when (shape) {
            Shape.CONE -> "R=${"%.2f".format(dims[0])}м H=${"%.2f".format(dims[1])}м"
            Shape.FRUSTUM -> "R₁=${"%.2f".format(dims[0])} R₂=${"%.2f".format(dims[1])} H=${"%.2f".format(dims[2])}м"
            Shape.TRAPEZOID_PRISM -> "L=${"%.2f".format(dims[0])} a=${"%.2f".format(dims[1])} b=${"%.2f".format(dims[2])} H=${"%.2f".format(dims[3])}м"
            Shape.BOX -> "${"%.2f".format(dims[0])}×${"%.2f".format(dims[1])}×${"%.2f".format(dims[2])}м"
        }
        return StockpileResult(
            material = material,
            shape = shape,
            volumeM3 = v,
            massNominalT = m,
            massWithCompactionT = mC,
            toleranceT = tol,
            description = "${material.label}, ${shape.label}, $dimText, ρ=${material.bulkDensity} т/м³",
        )
    }
}
