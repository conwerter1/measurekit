package ru.measurekit.domain.audit

import kotlin.math.abs

/**
 * Справочник проверки дозаторов РБУ.
 *
 * Источники:
 *  - ГОСТ 7473-2010 п. 5.3.3:
 *      «Погрешность дозирования исходных материалов весовыми дозаторами не должна
 *       превышать ±2% для цемента, воды, химических и минеральных добавок,
 *       ±3% — для заполнителей. Погрешность дозирования пористых заполнителей
 *       не должна превышать ±2% по объёму.»
 *  - ГОСТ 8.523-2004 — поверка дозаторов весовых дискретного действия
 *  - ГОСТ 10223-97 — дозаторы весовые дискретного действия (типы, общие требования)
 */
object DoserSpec {

    /** Вид дозируемого материала с допуском по ГОСТ 7473-2010. */
    enum class DoserKind(
        val label: String,
        val toleranceFraction: Double,    // допуск в долях (0.02 = ±2%)
        val gostRef: String,
    ) {
        CEMENT          ("Цемент",            0.02, "ГОСТ 7473-2010 п.5.3.3"),
        WATER           ("Вода",              0.02, "ГОСТ 7473-2010 п.5.3.3"),
        ADMIXTURE_CHEM  ("Хим. добавка (СП)", 0.02, "ГОСТ 7473-2010 п.5.3.3"),
        ADMIXTURE_MINERAL("Мин. добавка",     0.02, "ГОСТ 7473-2010 п.5.3.3"),
        AGGREGATE_FINE  ("Песок (мелкий)",    0.03, "ГОСТ 7473-2010 п.5.3.3"),
        AGGREGATE_COARSE("Щебень (крупный)",  0.03, "ГОСТ 7473-2010 п.5.3.3"),
        AGGREGATE_POROUS("Пористый запол.",   0.02, "ГОСТ 7473-2010 п.5.3.3 (объём)");

        companion object { fun all(): List<DoserKind> = entries.toList() }
    }

    /** Светофор результата проверки. */
    enum class Verdict(val label: String) {
        OK("Соответствует"),
        WARNING("Предел допуска"),
        FAIL("Превышен допуск"),
    }

    data class DoserCheckResult(
        val kind: DoserKind,
        val nominalKg: Double,
        val actualKg: Double,
        val absoluteErrorKg: Double,
        val relativeErrorFraction: Double,    // в долях, может быть отрицательной
        val toleranceFraction: Double,
        val verdict: Verdict,
    ) {
        val relativeErrorPercent: Double get() = relativeErrorFraction * 100.0
        val tolerancePercent: Double get() = toleranceFraction * 100.0
        val isUnderweight: Boolean get() = actualKg < nominalKg
    }

    /**
     * Проверка одного замера дозатора.
     * @param nominalKg номинал по карте подбора / уставке.
     * @param actualKg фактический вес по контрольному взвешиванию.
     */
    fun check(kind: DoserKind, nominalKg: Double, actualKg: Double): DoserCheckResult {
        val abs_err = actualKg - nominalKg
        val rel_err = if (nominalKg > 0) abs_err / nominalKg else 0.0
        val absRelErr = abs(rel_err)

        val tol = kind.toleranceFraction
        val verdict = when {
            absRelErr > tol -> Verdict.FAIL
            absRelErr > tol * 0.5 -> Verdict.WARNING  // предупреждение если более половины допуска
            else -> Verdict.OK
        }

        return DoserCheckResult(
            kind = kind,
            nominalKg = nominalKg,
            actualKg = actualKg,
            absoluteErrorKg = abs_err,
            relativeErrorFraction = rel_err,
            toleranceFraction = tol,
            verdict = verdict,
        )
    }
}
