package ru.measurekit.domain

import kotlin.math.abs

/**
 * Единицы длины с конверсиями к канонической единице — миллиметру.
 * Все значения в БД и внутри логики хранятся в мм; в UI преобразуются по выбору пользователя.
 */
enum class LengthUnit(val label: String, val mmPerUnit: Double) {
    MM("мм", 1.0),
    CM("см", 10.0),
    INCH("дюйм", 25.4);

    /** Перевод миллиметров в выбранные единицы */
    fun fromMm(mm: Double): Double = mm / mmPerUnit

    /** Перевод выбранных единиц в миллиметры */
    fun toMm(value: Double): Double = value * mmPerUnit

    /** Краткое форматирование значения с подходящей точностью */
    fun format(mm: Double): String {
        val v = fromMm(mm)
        val formatted = when (this) {
            MM -> "%.1f".format(abs(v))
            CM -> "%.2f".format(abs(v))
            INCH -> "%.3f".format(abs(v))
        }
        val sign = if (v < 0) "-" else ""
        return "$sign$formatted $label"
    }

    companion object {
        /** По умолчанию — миллиметры */
        val Default = MM
    }
}
