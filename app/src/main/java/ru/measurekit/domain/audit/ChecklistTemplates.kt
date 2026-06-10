package ru.measurekit.domain.audit

/**
 * Шаблоны чек-листов аудита для РБУ / ДСУ / Карьер + структура хранения ответов.
 */
object ChecklistTemplates {

    /** Идентификатор шаблона. */
    enum class TemplateKind(val code: String, val label: String) {
        RBU("RBU",       "Аудит РБУ"),
        DSU("DSU",       "Аудит ДСУ"),
        QUARRY("QUARRY", "Аудит карьера"),
    }

    enum class Severity(val label: String) {
        CRITICAL("Критично"),
        MAJOR("Существенно"),
        MINOR("Незначительно"),
    }

    /** Цвет ARGB long для удобства использования в Compose (Color(answer.color)). */
    enum class Answer(val label: String, val color: Long) {
        OK     ("Соответствует",  0xFF1B5E20),
        NOTE   ("Замечание",      0xFFF9A825),
        FAIL   ("Несоответствие", 0xFFC62828),
        NA     ("Не применимо",   0xFF757575),
        PENDING("Не проверено",   0xFFBDBDBD),
    }

    /** Один пункт чек-листа. hint и gostRef — non-null для удобства использования. */
    data class Item(
        val id: String,
        val question: String,
        val severity: Severity,
        val hint: String = "",
        val gostRef: String? = null,
        val photoRequired: Boolean = false,
    )

    /** Раздел. Поле названо `name` для совместимости с UI-кодом. */
    data class Section(
        val name: String,
        val items: List<Item>,
    )

    /**
     * Ответ пользователя на пункт чек-листа.
     * Хранится в mutableStateMapOf, сохраняется в JSON при завершении проверки.
     */
    data class ItemAnswer(
        val itemId: String,
        val answer: Answer = Answer.PENDING,
        val comment: String = "",
        val photoPath: String? = null,
        val timestamp: Long = 0L,
        val gpsLat: Double? = null,
        val gpsLon: Double? = null,
    )

    /** Шаблон-обёртка (хранит kind + sections). */
    data class ChecklistTemplate(
        val kind: TemplateKind,
        val sections: List<Section>,
    ) {
        val totalItems: Int get() = sections.sumOf { it.items.size }
    }

    /* ============================================================
       РБУ
       ============================================================ */
    val RBU = ChecklistTemplate(
        kind = TemplateKind.RBU,
        sections = listOf(
            Section("1. Документация и аккредитация", listOf(
                Item("rbu.1.1", "Технический паспорт установки",
                    Severity.MAJOR, "Сверить тип, год выпуска, серийный номер с табличкой"),
                Item("rbu.1.2", "Разрешение на эксплуатацию (Ростехнадзор / орган надзора)",
                    Severity.CRITICAL, "Срок действия не истёк", photoRequired = true),
                Item("rbu.1.3", "Свидетельство о поверке весовых дозаторов",
                    Severity.CRITICAL, "Действительное на момент аудита", "ГОСТ 8.523-2004", photoRequired = true),
                Item("rbu.1.4", "Журнал замесов / выпуска бетонной смеси",
                    Severity.CRITICAL, "Заполняется по каждому замесу, сшит, пронумерован"),
                Item("rbu.1.5", "Журнал пооперационного контроля",
                    Severity.MAJOR, "Контроль подвижности, температуры, времени смешения"),
                Item("rbu.1.6", "Карты подбора (рецепты) на все классы",
                    Severity.CRITICAL, "Утверждены, действующие, по каждому классу", "СП 28.13330", photoRequired = true),
                Item("rbu.1.7", "График ППР оборудования",
                    Severity.MINOR, "С отметками о выполнении"),
                Item("rbu.1.8", "Договоры на поставку цемента / заполнителей / добавок",
                    Severity.MAJOR, "С аккредитованными поставщиками"),
                Item("rbu.1.9", "Сертификаты на партии цемента",
                    Severity.CRITICAL, "На каждую партию", "ГОСТ 30515", photoRequired = true),
                Item("rbu.1.10", "Сертификаты на партии химических добавок",
                    Severity.MAJOR, "Срок годности не истёк"),
            )),
            Section("2. Лаборатория", listOf(
                Item("rbu.2.1", "Аккредитация / аттестация лаборатории",
                    Severity.CRITICAL, "Свидетельство в актуальной версии", "ГОСТ ISO/IEC 17025-2019", photoRequired = true),
                Item("rbu.2.2", "Пресс для испытания кубиков на сжатие",
                    Severity.CRITICAL, "С действующей поверкой", photoRequired = true),
                Item("rbu.2.3", "Установка контроля подвижности (конус Абрамса)",
                    Severity.CRITICAL, "Чистая, без сколов", "ГОСТ 10181"),
                Item("rbu.2.4", "Сита для ситового анализа",
                    Severity.MAJOR, "Полный набор по ГОСТ", "ГОСТ 8269.0"),
                Item("rbu.2.5", "Камера нормального твердения / пропарочная",
                    Severity.CRITICAL, "Поддержание температуры и влажности по нормам", photoRequired = true),
                Item("rbu.2.6", "Сушильный шкаф для определения влажности",
                    Severity.MAJOR, "Регулировка 105±5°C", "ГОСТ 8735"),
                Item("rbu.2.7", "Журнал испытаний контрольных образцов",
                    Severity.CRITICAL, "По каждой партии"),
                Item("rbu.2.8", "Журнал входного контроля материалов",
                    Severity.CRITICAL, "Цемент, песок, щебень, добавки — каждая партия"),
                Item("rbu.2.9", "Квалификация лаборанта",
                    Severity.MAJOR, "Аттестация с действующим сроком"),
            )),
            Section("3. Оборудование РБУ", listOf(
                Item("rbu.3.1", "Состояние смесителя",
                    Severity.MAJOR, "Чистый, без значительного износа лопастей", photoRequired = true),
                Item("rbu.3.2", "Состояние весовых дозаторов",
                    Severity.CRITICAL, "Без видимых деформаций, тарированы", photoRequired = true),
                Item("rbu.3.3", "Контроль точности дозирования (тестовый замес)",
                    Severity.CRITICAL, "В пределах допуска ГОСТ 7473", "ГОСТ 7473-2010 п.5.3.3"),
                Item("rbu.3.4", "Состояние бункеров и силосов",
                    Severity.MAJOR, "Герметичны, без следов утечки", photoRequired = true),
                Item("rbu.3.5", "Транспортные системы (шнеки / ленты)",
                    Severity.MAJOR, "Работают без посторонних шумов и просыпей"),
                Item("rbu.3.6", "Система подачи воды и добавок",
                    Severity.CRITICAL, "Без подтёков, насосы дозирующие исправны"),
                Item("rbu.3.7", "Пульт управления, мнемосхема",
                    Severity.MINOR, "Все показатели читаемы", photoRequired = true),
                Item("rbu.3.8", "Аварийная сигнализация и блокировки",
                    Severity.CRITICAL, "Тестовый прогон по каждому блоку"),
            )),
            Section("4. Технологический контроль", listOf(
                Item("rbu.4.1", "Соответствие фактического замеса карте подбора",
                    Severity.CRITICAL, "Сверить вес каждого компонента с уставкой"),
                Item("rbu.4.2", "Учёт влажности заполнителей при дозировке воды",
                    Severity.CRITICAL, "Должна быть процедура корректировки", "ГОСТ 8735"),
                Item("rbu.4.3", "Контроль подвижности на отгрузке",
                    Severity.MAJOR, "По каждому отгрузу или партии 5-10 м³"),
                Item("rbu.4.4", "Отбор контрольных образцов (кубики)",
                    Severity.CRITICAL, "По каждой партии класса B... согласно периодичности", "ГОСТ 10180"),
                Item("rbu.4.5", "Маркировка отгрузочных документов",
                    Severity.MAJOR, "Класс, подвижность, объём, время выпуска"),
            )),
            Section("5. Безопасность и экология", listOf(
                Item("rbu.5.1", "СИЗ у персонала",
                    Severity.MAJOR, "Каски, очки, респираторы при работе с цементом"),
                Item("rbu.5.2", "Аспирация и пылеулавливание",
                    Severity.MAJOR, "Фильтры на силосах работают"),
                Item("rbu.5.3", "Площадка для мойки миксеров",
                    Severity.MAJOR, "С отстойниками, без слива в почву"),
                Item("rbu.5.4", "Учёт сбросов воды и осадков",
                    Severity.MINOR, "Журнал, лимиты"),
            )),
        )
    )

    /* ============================================================
       ДСУ
       ============================================================ */
    val DSU = ChecklistTemplate(
        kind = TemplateKind.DSU,
        sections = listOf(
            Section("1. Документация ДСУ", listOf(
                Item("dsu.1.1", "Технический паспорт установки",
                    Severity.MAJOR, "Тип дробилок, производительность"),
                Item("dsu.1.2", "Технологическая схема ДСУ",
                    Severity.MAJOR, "С указанием стадий дробления и сит", photoRequired = true),
                Item("dsu.1.3", "График ППР оборудования",
                    Severity.MAJOR, "С отметками о выполнении"),
                Item("dsu.1.4", "Журнал работы ДСУ",
                    Severity.MAJOR, "Заполняется по сменам"),
                Item("dsu.1.5", "Журнал отгрузки готовой продукции",
                    Severity.CRITICAL, "По фракциям и потребителям"),
                Item("dsu.1.6", "Учёт расхода сырья vs выход готового",
                    Severity.MAJOR, "Баланс с допуском технологических потерь"),
            )),
            Section("2. Лаборатория ДСУ", listOf(
                Item("dsu.2.1", "Аккредитация лаборатории",
                    Severity.CRITICAL, "Если есть собственная — проверить статус", "ГОСТ ISO/IEC 17025"),
                Item("dsu.2.2", "Набор сит для контроля фракции",
                    Severity.CRITICAL, "Полный комплект по ГОСТ 8269.0", "ГОСТ 8269.0", photoRequired = true),
                Item("dsu.2.3", "Прибор контроля прочности (дробимости)",
                    Severity.MAJOR, "Барабан истирания / пресс", "ГОСТ 8269.0"),
                Item("dsu.2.4", "Журнал ситового анализа",
                    Severity.CRITICAL, "По каждой партии или периодически"),
                Item("dsu.2.5", "Журнал входного контроля сырья",
                    Severity.MAJOR, "Если ДСУ принимает сырьё извне"),
                Item("dsu.2.6", "Аттестация лаборанта",
                    Severity.MAJOR, "С действующим сроком"),
            )),
            Section("3. Оборудование ДСУ", listOf(
                Item("dsu.3.1", "Состояние первичной дробилки",
                    Severity.MAJOR, "Износ щёк / молотков / конусов в норме", photoRequired = true),
                Item("dsu.3.2", "Состояние вторичной/третичной дробилки",
                    Severity.MAJOR, "Если есть в схеме"),
                Item("dsu.3.3", "Состояние сит (грохотов)",
                    Severity.CRITICAL, "Без рваных участков", photoRequired = true),
                Item("dsu.3.4", "Натяжение и износ сит",
                    Severity.MAJOR, "Сита натянуты, без провисания"),
                Item("dsu.3.5", "Питатели",
                    Severity.MAJOR, "Равномерная подача, без забивки"),
                Item("dsu.3.6", "Конвейерные ленты",
                    Severity.MAJOR, "Целостность, центровка, скорость", photoRequired = true),
                Item("dsu.3.7", "Системы орошения / пылеподавления",
                    Severity.MAJOR, "Форсунки работают, давление в норме"),
                Item("dsu.3.8", "Аспирация",
                    Severity.MAJOR, "Фильтры на узлах перегруза"),
            )),
            Section("4. Качество готовой продукции", listOf(
                Item("dsu.4.1", "Зерновой состав по фракциям",
                    Severity.CRITICAL, "Соответствие ГОСТ 8267 по каждой фракции", "ГОСТ 8267"),
                Item("dsu.4.2", "Содержание зёрен слабых пород",
                    Severity.CRITICAL, "В пределах ГОСТ для марки", "ГОСТ 8269.0"),
                Item("dsu.4.3", "Лещадность (содержание игловатых)",
                    Severity.MAJOR, "Не более установленных групп", "ГОСТ 8269.0"),
                Item("dsu.4.4", "Содержание пылевидных и глинистых",
                    Severity.MAJOR, "В пределах ГОСТ"),
                Item("dsu.4.5", "Маркировка штабелей",
                    Severity.MAJOR, "Таблички с фракцией, маркой, объёмом", photoRequired = true),
                Item("dsu.4.6", "Раздельность хранения фракций",
                    Severity.CRITICAL, "Без смешивания между собой", photoRequired = true),
            )),
            Section("5. Складское хозяйство", listOf(
                Item("dsu.5.1", "Состояние площадки складирования",
                    Severity.MAJOR, "Твёрдое покрытие, без замусоривания", photoRequired = true),
                Item("dsu.5.2", "Объёмы остатков по фракциям",
                    Severity.MAJOR, "Сверить с учётной базой"),
                Item("dsu.5.3", "Весовое оборудование на отгрузке",
                    Severity.CRITICAL, "Автомобильные весы с поверкой", photoRequired = true),
                Item("dsu.5.4", "Журнал отгрузки",
                    Severity.MAJOR, "По автомобилям, с № ТТН"),
            )),
        )
    )

    /* ============================================================
       Карьер
       ============================================================ */
    val QUARRY = ChecklistTemplate(
        kind = TemplateKind.QUARRY,
        sections = listOf(
            Section("1. Документы карьера", listOf(
                Item("q.1.1", "Лицензия на пользование недрами",
                    Severity.CRITICAL, "Срок действия, границы участка", photoRequired = true),
                Item("q.1.2", "Утверждённый проект разработки",
                    Severity.CRITICAL, "Соответствие фактических работ проекту"),
                Item("q.1.3", "Согласование с органами Ростехнадзора",
                    Severity.CRITICAL, "Декларация / план развития горных работ"),
                Item("q.1.4", "Лимиты и нормы потерь",
                    Severity.MAJOR, "Утверждённые на текущий период"),
                Item("q.1.5", "Журнал учёта добычи",
                    Severity.CRITICAL, "Маркшейдерская съёмка по периодам"),
                Item("q.1.6", "План ликвидации и рекультивации",
                    Severity.MAJOR, "С финансовым обеспечением"),
            )),
            Section("2. Геология и маркшейдерия", listOf(
                Item("q.2.1", "Маркшейдерская съёмка отработанных запасов",
                    Severity.CRITICAL, "Свежая (≤ 6 месяцев), с актом"),
                Item("q.2.2", "Геологическая съёмка / опробование",
                    Severity.MAJOR, "Соответствие сорта руды/породы паспортам"),
                Item("q.2.3", "Контроль положения горных выработок vs границ",
                    Severity.CRITICAL, "Без выхода за лицензионные границы"),
            )),
            Section("3. Безопасность горных работ", listOf(
                Item("q.3.1", "Углы откоса уступов",
                    Severity.CRITICAL, "Соответствие проекту, без нависов", photoRequired = true),
                Item("q.3.2", "Высота уступов",
                    Severity.CRITICAL, "В пределах паспорта забоя"),
                Item("q.3.3", "Ширина рабочих площадок",
                    Severity.CRITICAL, "Достаточна для маневра"),
                Item("q.3.4", "Состояние подъездных дорог",
                    Severity.MAJOR, "Проезжаемость, водоотвод, отсыпка", photoRequired = true),
                Item("q.3.5", "Освещение в тёмное время",
                    Severity.MAJOR, "В местах работ ночной смены"),
                Item("q.3.6", "Складирование вскрышных пород",
                    Severity.MAJOR, "В пределах отвалов проекта"),
            )),
            Section("4. Учёт и отгрузка", listOf(
                Item("q.4.1", "Весы автомобильные на выезде",
                    Severity.CRITICAL, "Поверены, журнал ведётся", photoRequired = true),
                Item("q.4.2", "Журнал отгрузки горной массы",
                    Severity.CRITICAL, "По каждому автомобилю"),
                Item("q.4.3", "Соответствие заявленных vs фактических объёмов",
                    Severity.CRITICAL, "По периоду — отгрузки vs учёт добычи"),
                Item("q.4.4", "Маркшейдерское закрытие периода",
                    Severity.CRITICAL, "Акт обмера в пределах нормы"),
            )),
        )
    )

    fun byKind(kind: TemplateKind): ChecklistTemplate = when (kind) {
        TemplateKind.RBU -> RBU
        TemplateKind.DSU -> DSU
        TemplateKind.QUARRY -> QUARRY
    }
}
