package ru.measurekit.ui.registry

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AccountTree
import androidx.compose.material.icons.outlined.Architecture
import androidx.compose.material.icons.outlined.Camera
import androidx.compose.material.icons.outlined.CropFree
import androidx.compose.material.icons.outlined.Domain
import androidx.compose.material.icons.outlined.Factory
import androidx.compose.material.icons.outlined.Foundation
import androidx.compose.material.icons.outlined.Layers
import androidx.compose.material.icons.outlined.LocalFireDepartment
import androidx.compose.material.icons.outlined.Square
import androidx.compose.material.icons.outlined.Straighten
import androidx.compose.material.icons.outlined.Terrain
import androidx.compose.material.icons.outlined.ViewInAr
import androidx.compose.material.icons.outlined.ViewQuilt
import androidx.compose.material.icons.outlined.WbSunny
import androidx.compose.ui.graphics.vector.ImageVector

/**
 * Реестр всех модулей приложения. ОДИН ИСТОЧНИК ИСТИНЫ:
 * чтобы добавить новый модуль — добавьте AppModule(...) в [ModuleRegistry.all]
 * и зарегистрируйте composable-рендер в [MeasureKitApp].
 *
 * Главное меню, экран категории и навигация рендерятся прямо из этого списка —
 * не нужно править ничего больше.
 */

/** Категория модуля для группировки в главном меню. */
enum class Category(
    val id: String,
    val title: String,
    val subtitle: String,
    val icon: ImageVector,
) {
    MEASUREMENT(
        id = "measurement",
        title = "Измерения",
        subtitle = "Линейка, дальномер, площадь, арматура",
        icon = Icons.Outlined.Straighten,
    ),
    AUDIT(
        id = "audit",
        title = "Аудит",
        subtitle = "РБУ · ДСУ · Карьер",
        icon = Icons.Outlined.Factory,
    ),
    JOURNAL(
        id = "journal",
        title = "Журнал",
        subtitle = "История измерений и проверок",
        icon = Icons.Outlined.ViewQuilt,
    ),
}

/**
 * Описание одного модуля в приложении.
 *
 * @param id уникальный идентификатор (используется как часть route в навигации)
 * @param title название в подменю
 * @param subtitle короткое описание (1 строка) — что делает модуль
 * @param icon иконка outlined (Material Icons Outlined)
 * @param category раздел главного меню
 * @param route путь для NavController (без аргументов)
 * @param badge короткий бейдж справа (например "ГОСТ 7473" или "PDF") или null
 * @param enabled выключенные модули показываются полупрозрачно с пометкой "скоро"
 */
data class AppModule(
    val id: String,
    val title: String,
    val subtitle: String,
    val icon: ImageVector,
    val category: Category,
    val route: String,
    val badge: String? = null,
    val enabled: Boolean = true,
)

object ModuleRegistry {

    /**
     * Все модули приложения. Порядок здесь = порядок в меню.
     * При добавлении нового модуля — пропишите его и зарегистрируйте composable
     * в MeasureKitApp.kt (одной строкой `composable(route) { YourScreen(...) }`).
     */
    val all: List<AppModule> = listOf(

        // ============== ИЗМЕРЕНИЯ ==============
        AppModule(
            id = "ruler",
            title = "Линейка",
            subtitle = "Виртуальная линейка на экране",
            icon = Icons.Outlined.Straighten,
            category = Category.MEASUREMENT,
            route = "ruler",
        ),
        AppModule(
            id = "level",
            title = "Уровень",
            subtitle = "Пузырьковый уровень по акселерометру",
            icon = Icons.Outlined.WbSunny,
            category = Category.MEASUREMENT,
            route = "level",
        ),
        AppModule(
            id = "protractor",
            title = "Транспортир",
            subtitle = "Угол наклона / поворота",
            icon = Icons.Outlined.Architecture,
            category = Category.MEASUREMENT,
            route = "protractor",
        ),
        AppModule(
            id = "rangefinder",
            title = "Дальномер",
            subtitle = "Расстояние и высота через камеру",
            icon = Icons.Outlined.CropFree,
            category = Category.MEASUREMENT,
            route = "rangefinder",
        ),
        AppModule(
            id = "photo",
            title = "Фото с эталоном",
            subtitle = "Замер по фото с известным предметом",
            icon = Icons.Outlined.Camera,
            category = Category.MEASUREMENT,
            route = "photo",
        ),
        AppModule(
            id = "area",
            title = "Площадь",
            subtitle = "Площадь полигона по фото с эталоном",
            icon = Icons.Outlined.Square,
            category = Category.MEASUREMENT,
            route = "area",
        ),
        AppModule(
            id = "rebar",
            title = "Связка / штабель",
            subtitle = "Подсчёт арматуры, труб, кругляка (YOLO)",
            icon = Icons.Outlined.ViewInAr,
            category = Category.MEASUREMENT,
            route = "rebar",
            badge = "AI",
        ),

        // ============== АУДИТ ==============
        AppModule(
            id = "stockpile",
            title = "Обмер штабеля",
            subtitle = "Объём и масса нерудных по геометрии",
            icon = Icons.Outlined.Terrain,
            category = Category.AUDIT,
            route = "audit/stockpile",
            badge = "ГОСТ 8267",
        ),
        AppModule(
            id = "rbu",
            title = "Контроль РБУ",
            subtitle = "Дозатор · Замес · Влажность",
            icon = Icons.Outlined.LocalFireDepartment,
            category = Category.AUDIT,
            route = "audit/rbu",
            badge = "ГОСТ 7473",
        ),
        AppModule(
            id = "checklist_rbu",
            title = "Чек-лист РБУ",
            subtitle = "Полная проверка → PDF-акт с фото",
            icon = Icons.Outlined.Layers,
            category = Category.AUDIT,
            route = "audit/checklist/RBU",
            badge = "PDF",
        ),
        AppModule(
            id = "checklist_dsu",
            title = "Чек-лист ДСУ",
            subtitle = "Дробильно-сортировочный узел → PDF-акт",
            icon = Icons.Outlined.AccountTree,
            category = Category.AUDIT,
            route = "audit/checklist/DSU",
            badge = "PDF",
        ),
        AppModule(
            id = "checklist_quarry",
            title = "Чек-лист карьера",
            subtitle = "Лицензии · маркшейдерия · безопасность",
            icon = Icons.Outlined.Domain,
            category = Category.AUDIT,
            route = "audit/checklist/QUARRY",
            badge = "PDF",
        ),

        // ============== ЖУРНАЛ ==============
        AppModule(
            id = "history",
            title = "История",
            subtitle = "Все сохранённые замеры и проверки + ZIP-выгрузка",
            icon = Icons.Outlined.Foundation,
            category = Category.JOURNAL,
            route = "history",
        ),
    )

    /** Все модули заданной категории, в порядке регистрации. */
    fun byCategory(c: Category): List<AppModule> = all.filter { it.category == c }

    /** Модуль по id (или null если не найден). */
    fun byId(id: String): AppModule? = all.firstOrNull { it.id == id }

    /** Количество модулей в категории — для счётчика на главном экране. */
    fun count(c: Category): Int = byCategory(c).size
}
