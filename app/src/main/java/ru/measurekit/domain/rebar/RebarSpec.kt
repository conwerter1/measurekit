package ru.measurekit.domain.rebar

/**
 * Справочник арматуры с погонными массами по ГОСТ 5781-82 / ГОСТ 34028-2016.
 *
 * Погонная масса в кг/м зависит ТОЛЬКО от номинального диаметра,
 * не зависит от класса (А240, А400, А500С). Класс определяет прочностные
 * характеристики (предел текучести), но геометрия одна.
 *
 * Турецкие классы (для AKKUYU): TS 708 - B420C, B500C
 *  - B420C аналог нашего А400
 *  - B500C аналог нашего А500С
 */
object RebarSpec {

    /** Класс арматуры (определяет прочность). */
    enum class RebarClass(val label: String, val description: String) {
        A240("А240 (А-I)", "гладкая, низкоуглеродистая"),
        A300("А300 (А-II)", "периодическая"),
        A400("А400 (А-III)", "периодическая, ст3, ст5"),
        A500C("А500С", "свариваемая, осн. рабочая"),
        B500C("B500C", "Турция (TS 708) ≈ А500С"),
        B420C("B420C", "Турция (TS 708) ≈ А400"),
        Bp1("Bp-I", "проволока холоднотянутая");

        companion object {
            fun all(): List<RebarClass> = entries.toList()
        }
    }

    /**
     * Стандартные диаметры в мм по ГОСТ 5781-82.
     * Погонная масса рассчитывается как π/4 × d² × 7.85 г/см³.
     */
    data class Diameter(val mm: Int, val massKgPerM: Double) {
        val label: String get() = "Ø$mm"
    }

    val DIAMETERS: List<Diameter> = listOf(
        Diameter(6, 0.222),
        Diameter(8, 0.395),
        Diameter(10, 0.617),
        Diameter(12, 0.888),
        Diameter(14, 1.208),
        Diameter(16, 1.578),
        Diameter(18, 1.998),
        Diameter(20, 2.466),
        Diameter(22, 2.984),
        Diameter(25, 3.853),
        Diameter(28, 4.834),
        Diameter(32, 6.310),
        Diameter(36, 7.990),
        Diameter(40, 9.870),
    )

    /** Стандартная длина связки в метрах. На AKKUYU обычно 11.7 (мерные стержни). */
    const val STANDARD_BUNDLE_LENGTH_M = 11.7

    /**
     * Расчёт массы пучка по числу стержней, диаметру и длине.
     *
     * Применяется простая формула: m = N × q × L,
     * где q - погонная масса (кг/м), N - количество стержней, L - длина (м).
     *
     * Допуски ГОСТ 5781-82: ±2.5% по массе для номинального профиля,
     * поэтому реальная масса связки может отличаться от расчётной на ±2.5%.
     */
    fun calculateBundleMass(
        count: Int,
        diameterMm: Int,
        lengthM: Double
    ): BundleMassResult {
        val diameter = DIAMETERS.firstOrNull { it.mm == diameterMm }
            ?: error("Диаметр $diameterMm мм не в справочнике")
        val nominalKg = count * diameter.massKgPerM * lengthM
        // ГОСТ 5781 допускает ±2.5% на массу
        val tolerance = 0.025
        return BundleMassResult(
            count = count,
            diameter = diameter,
            lengthM = lengthM,
            nominalKg = nominalKg,
            minKg = nominalKg * (1.0 - tolerance),
            maxKg = nominalKg * (1.0 + tolerance),
        )
    }

    data class BundleMassResult(
        val count: Int,
        val diameter: Diameter,
        val lengthM: Double,
        val nominalKg: Double,
        val minKg: Double,
        val maxKg: Double,
    )
}
