package ru.measurekit.ui.screen.home

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import ru.measurekit.data.MeasurementsRepository
import ru.measurekit.ui.registry.Category
import ru.measurekit.ui.registry.ModuleRegistry

/**
 * Главный экран приложения. Показывает три раздела:
 *   ◤ Измерения (N инструментов)
 *   ◤ Аудит (N модулей)
 *   ◤ Журнал (N записей)
 *
 * Стиль — индустриальный (Bosch / Hilti): прямоугольные карточки с тонким
 * outline вместо тени, плотная типографика, минимум декора. Каждая карточка
 * слева имеет цветную полоску-акцент, как в КИПовских пультах HMI.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    onCategoryClick: (Category) -> Unit,
) {
    val ctx = LocalContext.current
    val repo = remember { MeasurementsRepository.get(ctx) }
    val journalItems by repo.observeAll().collectAsState(initial = emptyList())

    Scaffold(
        topBar = { HomeTopBar() },
        containerColor = MaterialTheme.colorScheme.background,
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            CategoryCard(
                category = Category.MEASUREMENT,
                count = ModuleRegistry.count(Category.MEASUREMENT),
                accent = MaterialTheme.colorScheme.primary,
                onClick = { onCategoryClick(Category.MEASUREMENT) },
            )
            CategoryCard(
                category = Category.AUDIT,
                count = ModuleRegistry.count(Category.AUDIT),
                accent = MaterialTheme.colorScheme.tertiary,
                onClick = { onCategoryClick(Category.AUDIT) },
            )
            CategoryCard(
                category = Category.JOURNAL,
                count = journalItems.size,
                countLabel = "записей",
                accent = MaterialTheme.colorScheme.secondary,
                onClick = { onCategoryClick(Category.JOURNAL) },
            )

            Spacer(Modifier.height(8.dp))

            // «Подвал» — статус-строка как в индустриальных HMI
            HomeFooter(modulesTotal = ModuleRegistry.all.size)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HomeTopBar() {
    TopAppBar(
        title = {
            Column {
                Text(
                    text = "MEASUREKIT",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Black,
                    color = MaterialTheme.colorScheme.onBackground,
                )
                Text(
                    text = "Инструменты измерения и аудита",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = MaterialTheme.colorScheme.background,
        ),
    )
}

/**
 * Большая карточка раздела на главном экране.
 *
 * Архитектура:
 *  - левая полоска-акцент 6dp (категория)
 *  - иконка категории (40dp)
 *  - заголовок + subtitle
 *  - правая колонка: счётчик + chevron
 */
@Composable
private fun CategoryCard(
    category: Category,
    count: Int,
    accent: androidx.compose.ui.graphics.Color,
    onClick: () -> Unit,
    countLabel: String = "модулей",
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(4.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Row(
            modifier = Modifier.height(IntrinsicSize.Min),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Цветная полоска-акцент слева, во всю высоту карточки
            Box(
                modifier = Modifier
                    .width(6.dp)
                    .fillMaxHeight()
                    .background(accent),
            )

            // Иконка категории
            Box(
                modifier = Modifier
                    .padding(horizontal = 16.dp, vertical = 18.dp)
                    .size(40.dp),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = category.icon,
                    contentDescription = null,
                    modifier = Modifier.size(36.dp),
                    tint = MaterialTheme.colorScheme.onBackground,
                )
            }

            // Заголовок + подзаголовок
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(vertical = 14.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(
                    text = category.title.uppercase(),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onBackground,
                )
                Text(
                    text = category.subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    overflow = TextOverflow.Ellipsis,
                    maxLines = 2,
                )
            }

            // Счётчик + chevron
            Column(
                modifier = Modifier.padding(end = 12.dp),
                horizontalAlignment = Alignment.End,
            ) {
                Text(
                    text = count.toString(),
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Black,
                    color = accent,
                )
                Text(
                    text = countLabel,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Icon(
                imageVector = Icons.AutoMirrored.Outlined.KeyboardArrowRight,
                contentDescription = null,
                modifier = Modifier
                    .padding(end = 12.dp)
                    .size(28.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun HomeFooter(modulesTotal: Int) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        shape = RoundedCornerShape(2.dp),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            FooterStat(label = "МОДУЛЕЙ", value = modulesTotal.toString())
            FooterDivider()
            FooterStat(label = "ВЕРСИЯ", value = "0.4.1")
            FooterDivider()
            FooterStat(label = "ОФЛАЙН", value = "✓")
        }
    }
}

@Composable
private fun FooterStat(label: String, value: String) {
    Column {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontWeight = FontWeight.Medium,
        )
        Text(
            text = value,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onBackground,
        )
    }
}

@Composable
private fun FooterDivider() {
    Box(
        modifier = Modifier
            .width(1.dp)
            .height(24.dp)
            .background(MaterialTheme.colorScheme.outlineVariant)
    )
}
