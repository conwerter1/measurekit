package ru.measurekit.domain.audit

/**
 * Справочник для модуля «Паспорт замеса» (контроль выпуска бетонной смеси).
 *
 * Поддерживает два набора нормативов:
 *   1. ГОСТ 26633-2015 (общестроительный бетон) — обычные классы прочности B7.5...B40
 *   2. СП 28.13330 «Защита строительных конструкций от коррозии» — повышенные требования
 *      для особо ответственных конструкций (АЭС, портовые, мостовые),
 *      применяется на AKKUYU NÜKLEER.
 *
 * Минимальные расходы цемента и максимальное В/Ц приведены справочно для типовых
 * условий эксплуатации; реальные значения берутся из карты подбора, выданной
 * проектной организацией. Этот справочник нужен для предварительного контроля
 * аудитором — что заявленные пропорции хотя бы в принципе вписываются в нормативы.
 */
object MixerSpec {

    /** Класс бетона по прочности на сжатие. */
    enum class ConcreteClass(
        val label: String,
        val minCementGost: Int,        // min расход цемента ГОСТ 26633, кг/м³
        val minCementSp28: Int,        // min расход цемента СП 28.13330, кг/м³ (жёстче)
        val maxWcGost: Double,         // max В/Ц ГОСТ 26633
        val maxWcSp28: Double,         // max В/Ц СП 28.13330
    ) {
        B7_5("B7.5",  180, 200, 0.75, 0.65),
        B15 ("B15",   220, 240, 0.65, 0.55),
        B20 ("B20",   250, 280, 0.60, 0.50),
        B25 ("B25",   280, 300, 0.55, 0.50),
        B30 ("B30",   300, 320, 0.55, 0.45),
        B35 ("B35",   320, 340, 0.50, 0.42),
        B40 ("B40",   340, 360, 0.50, 0.40),
        B45 ("B45",   360, 380, 0.45, 0.40),
        B50 ("B50",   380, 400, 0.45, 0.38),
        B60 ("B60",   400, 420, 0.40, 0.35);  // на AKKUYU именно B60 с max В/Ц=0.35

        companion object { fun all(): List<ConcreteClass> = entries.toList() }
    }

    /** Какой нормативный документ применяется при проверке. */
    enum class NormSet(val label: String) {
        GOST_26633("ГОСТ 26633-2015 (общестроит.)"),
        SP_28_13330("СП 28.13330 (особо отв.)"),
    }

    /** Марка цемента по прочности. */
    enum class CementGrade(val label: String, val rkN: Double) {
        CEM_32_5("ЦЕМ 32.5",  32.5),
        CEM_42_5("ЦЕМ 42.5",  42.5),
        CEM_52_5("ЦЕМ 52.5",  52.5),
    }

    /** Параметры замеса введённые пользователем. */
    data class BatchInput(
        val concreteClass: ConcreteClass,
        val volumeM3: Double,                    // объём замеса
        val cementKg: Double,                    // фактически загруженный цемент
        val cementGrade: CementGrade,
        val sandKg: Double,                      // песок
        val crushedKg: Double,                   // щебень суммарный
        val waterKg: Double,                     // вода
        val admixtureKg: Double = 0.0,           // суммарно химические добавки
        val normSet: NormSet,
    )

    data class CheckLine(
        val title: String,
        val expected: String,
        val actual: String,
        val ok: Boolean,
        val severity: Severity,
    ) { enum class Severity { CRITICAL, WARNING, INFO } }

    data class BatchReport(
        val cementPerM3Kg: Double,
        val wcRatio: Double,                     // фактическое В/Ц
        val sandToCrushedRatio: Double,          // Щ/П (соотношение)
        val checks: List<CheckLine>,
        val passed: Boolean,
    )

    /**
     * Анализ замеса. Считает фактические показатели и сверяет с нормативами.
     */
    fun analyze(input: BatchInput): BatchReport {
        val cls = input.concreteClass
        val v = input.volumeM3
        val cementPerM3 = if (v > 0) input.cementKg / v else 0.0
        val wc = if (input.cementKg > 0) input.waterKg / input.cementKg else 0.0
        val sandToCrushed = if (input.crushedKg > 0) input.sandKg / input.crushedKg else 0.0

        val minCement = if (input.normSet == NormSet.SP_28_13330) cls.minCementSp28 else cls.minCementGost
        val maxWc     = if (input.normSet == NormSet.SP_28_13330) cls.maxWcSp28      else cls.maxWcGost

        val checks = mutableListOf<CheckLine>()

        // Проверка 1: минимальный расход цемента
        checks.add(CheckLine(
            title = "Расход цемента",
            expected = "≥ $minCement кг/м³ (${input.normSet.label})",
            actual = "${"%.0f".format(cementPerM3)} кг/м³",
            ok = cementPerM3 >= minCement,
            severity = CheckLine.Severity.CRITICAL,
        ))

        // Проверка 2: водоцементное
        checks.add(CheckLine(
            title = "В/Ц соотношение",
            expected = "≤ $maxWc",
            actual = "%.3f".format(wc),
            ok = wc > 0 && wc <= maxWc,
            severity = CheckLine.Severity.CRITICAL,
        ))

        // Проверка 3: соотношение Щ/П — типичное в диапазоне 1.2 ÷ 2.0
        val sandRatioOk = sandToCrushed in 1.0..2.5
        checks.add(CheckLine(
            title = "Соотношение Щ/П",
            expected = "1.0 ÷ 2.5 (типично)",
            actual = "%.2f".format(sandToCrushed),
            ok = sandRatioOk,
            severity = CheckLine.Severity.WARNING,
        ))

        // Проверка 4: соответствие марки цемента классу бетона
        val cementGradeOk = when (cls) {
            ConcreteClass.B7_5, ConcreteClass.B15 ->
                input.cementGrade != CementGrade.CEM_52_5  // 52.5 для низких классов избыточно, но не критично
            ConcreteClass.B45, ConcreteClass.B50, ConcreteClass.B60 ->
                input.cementGrade != CementGrade.CEM_32_5  // 32.5 для высоких классов недостаточно
            else -> true
        }
        checks.add(CheckLine(
            title = "Марка цемента",
            expected = "Соответствует классу ${cls.label}",
            actual = input.cementGrade.label,
            ok = cementGradeOk,
            severity = CheckLine.Severity.WARNING,
        ))

        val passed = checks.filter { it.severity == CheckLine.Severity.CRITICAL }.all { it.ok }

        return BatchReport(
            cementPerM3Kg = cementPerM3,
            wcRatio = wc,
            sandToCrushedRatio = sandToCrushed,
            checks = checks,
            passed = passed,
        )
    }
}

/**
 * Справочник для модуля «Влажность заполнителя».
 *
 * Метод определения по ГОСТ 8735-88 (песок) и ГОСТ 8269.0-97 (щебень):
 *  W% = (m_влажн - m_сухой) / m_сухой × 100%
 *
 * Где m_сухой получают сушкой пробы при 105±5 °C до постоянной массы.
 *
 * Этот модуль нужен на РБУ для контроля корректировки воды затворения:
 * если песок имеет влажность 5%, то на 1000 кг песка приходится ~50 кг свободной воды,
 * которая должна вычитаться из отдозированной воды.
 */
object MoistureSpec {

    /** Тип заполнителя для подсказки нормальной влажности. */
    enum class AggregateKind(
        val label: String,
        val typicalRangeMin: Double,    // % влажности (типичный диапазон в работе)
        val typicalRangeMax: Double,
    ) {
        SAND     ("Песок",          0.0, 7.0),
        CRUSHED  ("Щебень",         0.0, 2.0),
        SCREENING("Отсев",          0.0, 5.0),
        GRAVEL   ("Гравий",         0.0, 3.0),
    }

    data class MoistureResult(
        val kind: AggregateKind,
        val wetMassG: Double,        // m_влажн (грамм)
        val dryMassG: Double,        // m_сухой (грамм)
        val freeWaterG: Double,      // свободная вода (грамм)
        val moisturePercent: Double, // W%
        val warning: String?,        // предупреждение если выше типичной нормы
    )

    fun calculate(kind: AggregateKind, wetG: Double, dryG: Double): MoistureResult? {
        if (wetG <= 0 || dryG <= 0 || dryG > wetG) return null
        val freeWater = wetG - dryG
        val w = freeWater / dryG * 100.0
        val warn = when {
            w > kind.typicalRangeMax -> "Выше нормы — необходимо корректировать воду затворения"
            w < kind.typicalRangeMin -> "Ниже нормы — материал пересушен"
            else -> null
        }
        return MoistureResult(kind, wetG, dryG, freeWater, w, warn)
    }
}
