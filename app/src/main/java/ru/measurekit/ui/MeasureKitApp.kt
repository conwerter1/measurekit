package ru.measurekit.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import ru.measurekit.domain.audit.ChecklistTemplates
import ru.measurekit.ui.registry.Category
import ru.measurekit.ui.screen.area.AreaScreen
import ru.measurekit.ui.screen.audit.ChecklistReportScreen
import ru.measurekit.ui.screen.audit.ChecklistScreen
import ru.measurekit.ui.screen.audit.RbuAuditScreen
import ru.measurekit.ui.screen.audit.StockpileScreen
import ru.measurekit.ui.screen.history.HistoryScreen
import ru.measurekit.ui.screen.home.CategoryScreen
import ru.measurekit.ui.screen.home.HomeScreen
import ru.measurekit.ui.screen.level.LevelScreen
import ru.measurekit.ui.screen.photo.PhotoMeasureScreen
import ru.measurekit.ui.screen.protractor.ProtractorScreen
import ru.measurekit.ui.screen.rangefinder.RangefinderScreen
import ru.measurekit.ui.screen.rebar.RebarScreen
import ru.measurekit.ui.screen.ruler.RulerScreen

/**
 * Корневой компонент навигации. Маршруты:
 *
 *   "home"                                -> HomeScreen (3 раздела)
 *   "category/{categoryId}"               -> CategoryScreen (подменю одной категории)
 *
 *   далее идут конкретные модули (ruler, level, ... audit/stockpile, audit/rbu,
 *   audit/checklist/{kind}, audit/checklist_report/{recordId}, history).
 *
 * При добавлении нового модуля — добавьте его в ModuleRegistry и пропишите
 * соответствующий composable(route) ниже.
 */
@Composable
fun MeasureKitApp(modifier: Modifier = Modifier) {
    val nav = rememberNavController()

    NavHost(
        navController = nav,
        startDestination = "home",
        modifier = modifier,
    ) {
        // ============== Главное меню + подменю ==============
        composable("home") {
            HomeScreen(
                onCategoryClick = { category ->
                    nav.navigate("category/${category.id}")
                }
            )
        }

        composable(
            route = "category/{categoryId}",
            arguments = listOf(navArgument("categoryId") { type = NavType.StringType }),
        ) { entry ->
            val cId = entry.arguments?.getString("categoryId") ?: Category.MEASUREMENT.id
            val cat = Category.entries.firstOrNull { it.id == cId } ?: Category.MEASUREMENT
            CategoryScreen(
                category = cat,
                onBack = { nav.popBackStack() },
                onModuleClick = { module ->
                    nav.navigate(module.route)
                }
            )
        }

        // ============== ИЗМЕРЕНИЯ ==============
        composable("ruler") { RulerScreen(onBack = { nav.popBackStack() }) }
        composable("level") { LevelScreen(onBack = { nav.popBackStack() }) }
        composable("protractor") { ProtractorScreen(onBack = { nav.popBackStack() }) }
        composable("rangefinder") { RangefinderScreen(onBack = { nav.popBackStack() }) }
        composable("photo") { PhotoMeasureScreen(onBack = { nav.popBackStack() }) }
        composable("area") { AreaScreen(onBack = { nav.popBackStack() }) }
        composable("rebar") { RebarScreen(onBack = { nav.popBackStack() }) }

        // ============== АУДИТ ==============
        composable("audit/stockpile") {
            StockpileScreen(onBack = { nav.popBackStack() })
        }
        composable("audit/rbu") {
            RbuAuditScreen(onBack = { nav.popBackStack() })
        }
        composable(
            route = "audit/checklist/{kind}",
            arguments = listOf(navArgument("kind") { type = NavType.StringType }),
        ) { entry ->
            val kindName = entry.arguments?.getString("kind") ?: "RBU"
            val kind = runCatching { ChecklistTemplates.TemplateKind.valueOf(kindName) }
                .getOrDefault(ChecklistTemplates.TemplateKind.RBU)
            ChecklistScreen(
                templateKind = kind,
                onBack = { nav.popBackStack() },
                onComplete = { recordId ->
                    nav.navigate("audit/checklist_report/$recordId") {
                        // убираем сам чек-лист из стека, чтобы Назад из отчёта
                        // вернул в подменю «Аудит», а не на полный чек-лист
                        popUpTo("audit/checklist/{kind}") { inclusive = true }
                    }
                }
            )
        }
        composable(
            route = "audit/checklist_report/{recordId}",
            arguments = listOf(navArgument("recordId") { type = NavType.LongType }),
        ) { entry ->
            val recordId = entry.arguments?.getLong("recordId") ?: 0L
            ChecklistReportScreen(
                recordId = recordId,
                onBack = { nav.popBackStack() }
            )
        }

        // ============== ЖУРНАЛ ==============
        composable("history") { HistoryScreen(onBack = { nav.popBackStack() }) }
    }
}
