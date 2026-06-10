package ru.measurekit.domain.rebar

/**
 * Универсальный справочник материалов для модуля "Связка / штабель".
 *
 * Поддерживаемые типы:
 *  - Арматура (ГОСТ 5781-82, ГОСТ 34028-2016)
 *  - Труба стальная ВГП (ГОСТ 3262-75)
 *  - Труба стальная электросварная (ГОСТ 10704-91)
 *  - Профильная труба квадратная (ГОСТ 30245-2003)
 *  - Кругляк / лесоматериал (ГОСТ 9462-88, объёмный расчёт)
 *  - Своё (ввод погонной массы вручную)
 *
 * Каждый тип материала диктует:
 *   - какую модель загружать (rebar / multi)
 *   - какой класс детекции считать целевым
 *   - формулу расчёта массы/объёма
 *   - список типоразмеров
 */
object MaterialSpec {

    /** Тип материала. */
    enum class MaterialType(
        val label: String,
        val detectorModel: DetectorKind,
        val targetClass: Int,  // class index в multi-модели; для REBAR не имеет значения
        val unit: String,      // "кг" для массы, "м³" для объёма
    ) {
        REBAR("Арматура", DetectorKind.REBAR_SPECIALIZED, 0, "кг"),
        PIPE_STEEL("Труба стальная", DetectorKind.MULTI, 1, "кг"),
        PIPE_PROFILE("Труба профильная", DetectorKind.MULTI, 1, "кг"),
        ROUND_LOG("Кругляк/брёвна", DetectorKind.MULTI, 2, "м³"),
        CUSTOM("Своё", DetectorKind.MULTI, -1, "кг");  // -1 = принять всё

        companion object {
            fun all(): List<MaterialType> = entries.toList()
        }
    }

    /** Какой ONNX-файл нужен для этого материала. */
    enum class DetectorKind(val assetName: String) {
        /** Специализированная модель Huawei rebar (98.4% mAP) - только для арматуры */
        REBAR_SPECIALIZED("rebar_detector.onnx"),
        /** Мульти-модель (rebar/pipe/log) - для всего остального */
        MULTI("multi_detector.onnx"),
    }

    /* ============= АРМАТУРА (ГОСТ 5781-82) ============= */

    /** Класс арматуры. Не влияет на массу - только на прочность. */
    enum class RebarClass(val label: String) {
        A240("А240 (А-I)"),
        A300("А300 (А-II)"),
        A400("А400 (А-III)"),
        A500C("А500С"),
        B500C("B500C"),  // Турция TS 708 ≈ А500С
        B420C("B420C"),  // Турция TS 708 ≈ А400
        Bp1("Bp-I");

        companion object { fun all(): List<RebarClass> = entries.toList() }
    }

    data class Diameter(val mm: Int, val massKgPerM: Double) {
        val label: String get() = "Ø$mm"
    }

    /** Стандартные диаметры арматуры по ГОСТ 5781-82. */
    val REBAR_DIAMETERS: List<Diameter> = listOf(
        Diameter(6, 0.222),  Diameter(8, 0.395),  Diameter(10, 0.617),
        Diameter(12, 0.888), Diameter(14, 1.208), Diameter(16, 1.578),
        Diameter(18, 1.998), Diameter(20, 2.466), Diameter(22, 2.984),
        Diameter(25, 3.853), Diameter(28, 4.834), Diameter(32, 6.310),
        Diameter(36, 7.990), Diameter(40, 9.870),
    )

    /* ============= ТРУБЫ СТАЛЬНЫЕ ВГП (ГОСТ 3262-75) ============= */

    /**
     * Водогазопроводные трубы.
     * Размер указывается по условному проходу (Ду), но реальные диаметры разные:
     *   Ду 15 = наружный 21.3мм, толщина стенки 2.8мм, масса 1.28 кг/м
     */
    data class PipeSize(
        val labelDn: String,       // "Ду 25" - условный проход
        val outerDiameterMm: Double,
        val wallThicknessMm: Double,
        val massKgPerM: Double,
    ) {
        val label: String get() = labelDn
    }

    /** Распространённые ВГП по ГОСТ 3262-75 (обыкновенные, средние стенки). */
    val PIPE_STEEL_SIZES: List<PipeSize> = listOf(
        PipeSize("Ду 15 (21.3мм)", 21.3, 2.8, 1.28),
        PipeSize("Ду 20 (26.8мм)", 26.8, 2.8, 1.66),
        PipeSize("Ду 25 (33.5мм)", 33.5, 3.2, 2.39),
        PipeSize("Ду 32 (42.3мм)", 42.3, 3.2, 3.09),
        PipeSize("Ду 40 (48.0мм)", 48.0, 3.5, 3.84),
        PipeSize("Ду 50 (60.0мм)", 60.0, 3.5, 4.88),
        PipeSize("Ду 65 (75.5мм)", 75.5, 4.0, 7.05),
        PipeSize("Ду 80 (88.5мм)", 88.5, 4.0, 8.34),
        PipeSize("Ду 100 (114.0мм)", 114.0, 4.5, 12.15),
        PipeSize("Ду 125 (140.0мм)", 140.0, 4.5, 15.04),
        PipeSize("Ду 150 (165.0мм)", 165.0, 4.5, 17.81),
    )

    /* ============= ПРОФИЛЬНАЯ ТРУБА (ГОСТ 30245-2003) ============= */

    /** Квадратная профильная труба. Аналогично есть прямоугольная, можно расширить. */
    data class ProfileSize(
        val sizeMm: String,        // "40x40x3" - сторона x сторона x толщина
        val massKgPerM: Double,
    ) {
        val label: String get() = sizeMm
    }

    val PROFILE_SIZES: List<ProfileSize> = listOf(
        ProfileSize("20x20x2", 1.05),
        ProfileSize("25x25x2", 1.36),
        ProfileSize("30x30x2", 1.68),
        ProfileSize("40x40x2", 2.31),
        ProfileSize("40x40x3", 3.28),
        ProfileSize("50x50x3", 4.20),
        ProfileSize("60x60x3", 5.14),
        ProfileSize("60x60x4", 6.66),
        ProfileSize("80x80x4", 9.17),
        ProfileSize("80x80x5", 11.28),
        ProfileSize("100x100x5", 14.42),
        ProfileSize("100x100x6", 17.05),
    )

    /* ============= КРУГЛЯК / ЛЕСОМАТЕРИАЛ ============= */

    /**
     * Для брёвен расчёт идёт не по массе, а по ОБЪЁМУ (м³).
     * Объём ствола = π * (d/2)² * L, где d - средний диаметр.
     * На складе обычно сортируют по диаметру в верхнем отрубе (тонкий конец).
     */
    data class LogSize(val avgDiameterCm: Int) {
        val label: String get() = "Ø$avgDiameterCm см"
        /** Объём одного ствола в м³ */
        fun volumePerLog(lengthM: Double): Double {
            val r = avgDiameterCm / 200.0  // см -> м, делим на 2 (радиус)
            return Math.PI * r * r * lengthM
        }
    }

    val LOG_DIAMETERS: List<LogSize> = (10..40 step 2).map { LogSize(it) }

    /* ============= СТАНДАРТНЫЕ ДЛИНЫ ============= */

    /** Стандартные длины. На AKKUYU арматура обычно 11.7 м (мерные). Брёвна - 6 м. */
    fun standardLength(material: MaterialType): Double = when (material) {
        MaterialType.REBAR -> 11.7
        MaterialType.PIPE_STEEL, MaterialType.PIPE_PROFILE -> 6.0
        MaterialType.ROUND_LOG -> 6.0
        MaterialType.CUSTOM -> 6.0
    }

    /* ============= РАСЧЁТ ============= */

    sealed class CalcResult {
        abstract val count: Int
        abstract val unit: String
        abstract val nominal: Double
        abstract val tolerance: Double  // допуск в долях единицы (например 0.025 = 2.5%)
        val min: Double get() = nominal * (1.0 - tolerance)
        val max: Double get() = nominal * (1.0 + tolerance)
        abstract val description: String
    }

    /** Результат расчёта массы (кг). */
    data class MassResult(
        override val count: Int,
        val massKgPerM: Double,
        val lengthM: Double,
        override val nominal: Double,
        override val tolerance: Double,
        override val description: String,
    ) : CalcResult() {
        override val unit: String = "кг"
    }

    /** Результат расчёта объёма (м³) - для брёвен. */
    data class VolumeResult(
        override val count: Int,
        val avgDiameterCm: Int,
        val lengthM: Double,
        override val nominal: Double,
        override val tolerance: Double,
        override val description: String,
    ) : CalcResult() {
        override val unit: String = "м³"
    }

    /**
     * Универсальный калькулятор.
     */
    fun calculate(
        material: MaterialType,
        count: Int,
        lengthM: Double,
        rebarDiameterMm: Int? = null,
        pipeSize: PipeSize? = null,
        profileSize: ProfileSize? = null,
        logSize: LogSize? = null,
        customMassKgPerM: Double? = null,
    ): CalcResult? {
        if (count <= 0 || lengthM <= 0) return null

        return when (material) {
            MaterialType.REBAR -> {
                val d = REBAR_DIAMETERS.firstOrNull { it.mm == rebarDiameterMm } ?: return null
                val mass = count * d.massKgPerM * lengthM
                MassResult(count, d.massKgPerM, lengthM, mass, 0.025,
                    "$count × ${"%.3f".format(d.massKgPerM)} кг/м × ${"%.2f".format(lengthM)} м")
            }
            MaterialType.PIPE_STEEL -> {
                val s = pipeSize ?: return null
                val mass = count * s.massKgPerM * lengthM
                MassResult(count, s.massKgPerM, lengthM, mass, 0.05,  // ВГП ±5%
                    "$count × ${"%.2f".format(s.massKgPerM)} кг/м × ${"%.2f".format(lengthM)} м")
            }
            MaterialType.PIPE_PROFILE -> {
                val s = profileSize ?: return null
                val mass = count * s.massKgPerM * lengthM
                MassResult(count, s.massKgPerM, lengthM, mass, 0.05,
                    "$count × ${"%.2f".format(s.massKgPerM)} кг/м × ${"%.2f".format(lengthM)} м")
            }
            MaterialType.ROUND_LOG -> {
                val s = logSize ?: return null
                val volPerLog = s.volumePerLog(lengthM)
                val totalVol = count * volPerLog
                VolumeResult(count, s.avgDiameterCm, lengthM, totalVol, 0.10,  // ±10% для лесоматериала
                    "$count × ${"%.4f".format(volPerLog)} м³ (Ø${s.avgDiameterCm}см × ${"%.2f".format(lengthM)}м)")
            }
            MaterialType.CUSTOM -> {
                val q = customMassKgPerM ?: return null
                val mass = count * q * lengthM
                MassResult(count, q, lengthM, mass, 0.05,
                    "$count × ${"%.3f".format(q)} кг/м × ${"%.2f".format(lengthM)} м")
            }
        }
    }
}
