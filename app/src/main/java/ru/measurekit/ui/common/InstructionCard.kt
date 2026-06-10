package ru.measurekit.ui.common

import android.content.Context
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/**
 * Раскрывающаяся карточка с краткой инструкцией для аудиторских модулей.
 *
 * Принцип работы:
 *   - При первом открытии экрана карточка раскрыта (пользователь видит инструкцию)
 *   - После того как пользователь её свернёт - факт сохраняется в SharedPreferences
 *     по ключу moduleKey, и при следующих открытиях карточка показывается СВЁРНУТОЙ
 *   - Заголовок всегда виден, можно раскрыть обратно одним тапом
 *
 * Это даёт лучший компромисс: новичок сразу видит инструкцию, опытный
 * пользователь не отвлекается. Свёрнутый заголовок занимает мало места.
 *
 * @param moduleKey уникальный ключ модуля (для запоминания состояния)
 *                  Например "stockpile", "doser_check", "checklist_rbu"
 * @param sections список разделов инструкции - каждый с заголовком (например
 *                 "🎯 Когда использовать") и текстом
 */
@Composable
fun InstructionCard(
    moduleKey: String,
    title: String = "Краткая инструкция",
    sections: List<InstructionSection>,
    modifier: Modifier = Modifier,
) {
    val ctx = LocalContext.current
    val prefs = remember(moduleKey) {
        ctx.getSharedPreferences("instruction_state", Context.MODE_PRIVATE)
    }

    // По умолчанию раскрыта, но если пользователь её хоть раз свернул — теперь свёрнута.
    var expanded by remember(moduleKey) {
        val seen = prefs.getBoolean("seen_$moduleKey", false)
        mutableStateOf(!seen)
    }

    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer
        ),
    ) {
        Column {
            // Заголовок-кнопка (тап = свернуть/раскрыть)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable {
                        expanded = !expanded
                        if (!expanded) {
                            // Запоминаем что пользователь увидел инструкцию
                            prefs.edit().putBoolean("seen_$moduleKey", true).apply()
                        }
                    }
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    Icons.Filled.Info,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSecondaryContainer,
                    modifier = Modifier.size(20.dp),
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                    modifier = Modifier.weight(1f),
                )
                Icon(
                    if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                    contentDescription = if (expanded) "Свернуть" else "Раскрыть",
                    tint = MaterialTheme.colorScheme.onSecondaryContainer,
                )
            }

            // Содержимое
            AnimatedVisibility(
                visible = expanded,
                enter = expandVertically() + fadeIn(),
                exit = shrinkVertically() + fadeOut(),
            ) {
                Column(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    for (section in sections) {
                        Column {
                            Text(
                                text = section.title,
                                style = MaterialTheme.typography.labelLarge,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSecondaryContainer,
                            )
                            Spacer(Modifier.height(2.dp))
                            Text(
                                text = section.body,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSecondaryContainer,
                            )
                        }
                    }
                    Spacer(Modifier.height(4.dp))
                }
            }
        }
    }
}

/** Секция инструкции: заголовок и текст. Текст можно делать многострочным через \n. */
data class InstructionSection(
    val title: String,
    val body: String,
)

/**
 * Готовые наборы инструкций по модулям.
 * Размещены здесь чтобы тексты не загромождали код экранов.
 */
object Instructions {

    /* ------------- ОБМЕР ШТАБЕЛЯ ------------- */
    val STOCKPILE = listOf(
        InstructionSection(
            title = "🎯 Когда использовать",
            body = "Аудит остатков нерудных на ДСУ или карьере. " +
                    "Сверка фактического объёма штабеля с учётной базой за 2-3 минуты, " +
                    "без вызова геодезиста. Точность ±10% — достаточна для контроля.",
        ),
        InstructionSection(
            title = "📋 Как замерить",
            body = "1. Сделайте фото штабеля для отчёта (опционально, но рекомендуется).\n" +
                    "2. Выберите материал — насыпная плотность подставится автоматически.\n" +
                    "3. Выберите форму штабеля:\n" +
                    "    • Конус — округлая «горка» на ровной площадке.\n" +
                    "    • Усечённый конус — верх плоский (отсыпали \"усечкой\").\n" +
                    "    • Призма — длинный штабель на ленте, в сечении трапеция.\n" +
                    "    • Параллелепипед — штабель в боксах с бортами.\n" +
                    "4. Замерьте рулеткой нужные размеры (приложение покажет какие).\n" +
                    "5. Объём, масса по плотности и масса с уплотнением рассчитаются сами.\n" +
                    "6. Заполните примечание (партия, поставщик, № штабеля) → Сохранить.",
        ),
        InstructionSection(
            title = "⚖ Норматив",
            body = "Действующий ГОСТ: коэффициент уплотнения щебня не более 1.10. " +
                    "Применяется при приёмке: V_насыпной × 1.1. " +
                    "Углы естественного откоса (35-38° для щебня, 32-35° для песка) " +
                    "автоматически подсказываются по выбранному материалу. " +
                    "Если измеренная высота сильно превышает H = R × tg(α) — штабель " +
                    "спрессован или промёрзший, нужна повторная проверка.",
        ),
    )

    /* ------------- ПРОВЕРКА ДОЗАТОРА ------------- */
    val DOSER_CHECK = listOf(
        InstructionSection(
            title = "🎯 Когда использовать",
            body = "При проверке точности дозирования на РБУ. Проводится с поверителем: " +
                    "контрольное взвешивание дозируемого компонента на эталонных весах, " +
                    "сравнение с уставкой пульта. Делается выборочно по каждому из 5-7 " +
                    "дозаторов в начале проверки.",
        ),
        InstructionSection(
            title = "📋 Как проверить",
            body = "1. Выберите вид дозатора — допуск ±2% или ±3% подставится по ГОСТ 7473.\n" +
                    "2. Введите номинал по карте подбора (или уставке пульта оператора).\n" +
                    "3. Введите фактический вес по показанию поверителя.\n" +
                    "4. Сделайте фото дисплея весов — приложение умеет распознавать " +
                    "цифры с фото (нажмите «Распознать») и подставлять их в поле.\n" +
                    "5. Светофор покажет результат:\n" +
                    "    • 🟢 Зелёный — отклонение в пределах половины допуска.\n" +
                    "    • 🟡 Жёлтый — выше половины допуска (предупреждение).\n" +
                    "    • 🔴 Красный — превышен допуск ГОСТ. Замечание в акт.\n" +
                    "6. Сохраните → перейдите к следующему дозатору.",
        ),
        InstructionSection(
            title = "⚖ Норматив (ГОСТ 7473-2010 п.5.3.3)",
            body = "Погрешность дозирования весовыми дозаторами не должна превышать:\n" +
                    "    • ±2% для цемента, воды, химических и минеральных добавок\n" +
                    "    • ±3% для заполнителей (песок, щебень)\n" +
                    "    • ±2% по объёму для пористых заполнителей\n" +
                    "Поверка дозаторов — по ГОСТ 8.523-2004.",
        ),
    )

    /* ------------- ПАСПОРТ ЗАМЕСА ------------- */
    val MIXER_BATCH = listOf(
        InstructionSection(
            title = "🎯 Когда использовать",
            body = "Контроль фактического выпуска бетонной смеси на РБУ. " +
                    "Аудитор запрашивает у оператора показания пульта по последним " +
                    "1-3 замесам класса B25/B30/B60 и сверяет состав с нормативами " +
                    "минимального расхода цемента и максимального В/Ц.",
        ),
        InstructionSection(
            title = "📋 Как заполнить",
            body = "1. Выберите норматив:\n" +
                    "    • СП 28.13330 — для AKKUYU и других особо ответственных " +
                    "конструкций (АЭС, портовые, мостовые) — жёсткие требования.\n" +
                    "    • ГОСТ 26633 — общестроительный бетон, обычные объекты.\n" +
                    "2. Класс бетона (B7.5...B60) — норма расхода цемента и max В/Ц " +
                    "подставятся автоматически.\n" +
                    "3. Марка цемента (ЦЕМ 32.5/42.5/52.5).\n" +
                    "4. Состав замеса в кг по показаниям пульта: цемент, песок, щебень, " +
                    "вода, добавки.\n" +
                    "5. Расход цемента (кг/м³), В/Ц и проверки появятся автоматически.\n" +
                    "6. Зелёная карточка → состав соответствует нормативу. " +
                    "Красная → есть критичные отклонения.",
        ),
        InstructionSection(
            title = "⚖ Норматив",
            body = "СП 28.13330 для B60 (AKKUYU): мин. цемент 420 кг/м³, max В/Ц 0.35. " +
                    "ГОСТ 26633 для B30: мин. цемент 300 кг/м³, max В/Ц 0.55. " +
                    "Соотношение Щ/П в норме 1.0÷2.5.\n" +
                    "ГОСТ 7473-2010 — общие требования к бетонным смесям. " +
                    "ГОСТ 10180 — отбор и испытание контрольных образцов (кубики).",
        ),
    )

    /* ------------- ВЛАЖНОСТЬ ------------- */
    val MOISTURE = listOf(
        InstructionSection(
            title = "🎯 Когда использовать",
            body = "При проверке корректировки воды затворения на РБУ. " +
                    "Если песок имеет влажность 5%, то на 1000 кг песка приходится " +
                    "~50 кг свободной воды, которую оператор обязан вычитать из " +
                    "отдозированной воды. Без этой корректировки получится бетон " +
                    "с завышенным В/Ц — не соответствует карте подбора.",
        ),
        InstructionSection(
            title = "📋 Как замерить",
            body = "1. Лаборант отбирает пробу заполнителя.\n" +
                    "2. Взвешивает влажную пробу — m_влажн (грамм).\n" +
                    "3. Сушит при 105±5°C до постоянной массы (по ГОСТ 8735).\n" +
                    "4. Взвешивает сухую пробу — m_сухой (грамм).\n" +
                    "5. Введите тип заполнителя, m_влажн и m_сухой.\n" +
                    "6. Приложение покажет W%, свободную воду в литрах на тонну, " +
                    "и предупредит если влажность выше нормы.",
        ),
        InstructionSection(
            title = "⚖ Норматив (ГОСТ 8735-88 / ГОСТ 8269.0-97)",
            body = "Формула: W% = (m_влажн − m_сухой) / m_сухой × 100%.\n" +
                    "Типичная влажность:\n" +
                    "    • Песок 0-7% (выше — нужна корректировка воды)\n" +
                    "    • Щебень 0-2%\n" +
                    "    • Отсев 0-5%\n" +
                    "    • Гравий 0-3%\n" +
                    "Если значение выше нормы — оператор РБУ должен пересчитать " +
                    "воду затворения. Без этого замес не соответствует карте подбора.",
        ),
    )

    /* ------------- ЧЕК-ЛИСТ (общий) ------------- */
    val CHECKLIST_GENERIC = listOf(
        InstructionSection(
            title = "🎯 Что это",
            body = "Готовый шаблон проверки, составленный по ГОСТ ISO/IEC 17025-2019, " +
                    "ГОСТ Р ИСО 19011-2021, ГОСТ 7473-2010 и др. Все пункты " +
                    "сгруппированы по разделам с указанием критичности и ссылкой " +
                    "на нормативный документ.",
        ),
        InstructionSection(
            title = "📋 Как пройти",
            body = "1. Заполните реквизиты сверху: объект, организацию, ФИО аудитора. " +
                    "GPS-координаты определятся автоматически.\n" +
                    "2. Идите по пунктам по порядку:\n" +
                    "    • ✓ Норма — соответствует требованию\n" +
                    "    • ⚠ Замечание — мелкое отступление, можно устранить\n" +
                    "    • ✗ Несоответствие — критично, оформляется в акт\n" +
                    "    • N/A — пункт не применим к этому объекту\n" +
                    "3. К пункту с критичным отклонением — обязательно фото-доказательство " +
                    "(✗ или критичное замечание) и комментарий с описанием.\n" +
                    "4. По пунктам со значком 📷 фото обязательно по умолчанию.\n" +
                    "5. После прохождения всех пунктов — «Завершить и создать PDF-акт».",
        ),
        InstructionSection(
            title = "📑 Что получится",
            body = "Готовый PDF-акт со всеми пунктами проверки, цветным светофором по " +
                    "разделам, фото-доказательствами с GPS и timestamp, и итоговой " +
                    "сводкой. Можно поделиться по email/WhatsApp/Telegram сразу " +
                    "с объекта или сохранить в Историю для последующего редактирования.",
        ),
    )

    /* ------------- ЧЕК-ЛИСТ РБУ — особенности ------------- */
    val CHECKLIST_RBU = CHECKLIST_GENERIC + InstructionSection(
        title = "🏭 Особенности РБУ",
        body = "Главные критичные пункты: поверка дозаторов, аккредитация лаборатории, " +
                "карты подбора, журнал замесов, отбор кубиков. На AKKUYU дополнительно: " +
                "контроль соответствия СП 28.13330 (особо ответственные), сертификаты " +
                "цемента на каждую партию. Перед запуском чек-листа полезно сначала " +
                "сделать обмер дозаторов (модуль «Проверка РБУ»).",
    )

    val CHECKLIST_DSU = CHECKLIST_GENERIC + InstructionSection(
        title = "⚙ Особенности ДСУ",
        body = "Главные критичные пункты: лицензия и проект, состояние сит, журнал " +
                "ситового анализа, раздельность хранения фракций (ГОСТ 8267), весовое " +
                "оборудование на отгрузке. Отдельно проверяйте маркировку штабелей " +
                "(модуль «Обмер штабеля» поможет сверить остатки).",
    )

    val CHECKLIST_QUARRY = CHECKLIST_GENERIC + InstructionSection(
        title = "⛏ Особенности карьера",
        body = "Главные критичные пункты: лицензия на пользование недрами, " +
                "маркшейдерская съёмка (≤6 месяцев), углы откоса уступов, " +
                "соответствие проекту разработки, журнал отгрузки. Положение горных " +
                "выработок не должно выходить за лицензионные границы — проверяется " +
                "сравнением маркшейдерской съёмки с границами лицензии.",
    )
}
