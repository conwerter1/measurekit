package ru.measurekit.ui.screen.home

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import ru.measurekit.ui.registry.AppModule
import ru.measurekit.ui.registry.Category
import ru.measurekit.ui.registry.ModuleRegistry

/**
 * Универсальный экран подменю одной категории.
 * Рендерит ВСЕ модули заданной категории — порядок и состав берёт из
 * [ModuleRegistry]. Чтобы добавить модуль в это меню, не нужно ничего
 * править здесь — достаточно дописать AppModule в реестр.
 *
 * Стиль — индустриальный: плотные карточки 64dp высотой, левая полоска-
 * акцент категории, бейдж справа («ГОСТ 7473», «PDF», «AI»), монохромная
 * иконка модуля слева.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CategoryScreen(
    category: Category,
    onBack: () -> Unit,
    onModuleClick: (AppModule) -> Unit,
) {
    val modules = ModuleRegistry.byCategory(category)
    val accent = when (category) {
        Category.MEASUREMENT -> MaterialTheme.colorScheme.primary
        Category.AUDIT       -> MaterialTheme.colorScheme.tertiary
        Category.JOURNAL     -> MaterialTheme.colorScheme.secondary
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = category.title.uppercase(),
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                        )
                        Text(
                            text = "${modules.size} ${pluralModules(modules.size)}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Назад",
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                ),
            )
        },
        containerColor = MaterialTheme.colorScheme.background,
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(modules, key = { it.id }) { module ->
                ModuleCard(
                    module = module,
                    accent = accent,
                    onClick = { onModuleClick(module) },
                )
            }
        }
    }
}

@Composable
private fun ModuleCard(
    module: AppModule,
    accent: Color,
    onClick: () -> Unit,
) {
    val enabled = module.enabled
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled, onClick = onClick)
            .alpha(if (enabled) 1f else 0.5f),
        shape = RoundedCornerShape(4.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Row(
            modifier = Modifier.height(IntrinsicSize.Min),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Левая полоска-акцент во всю высоту
            Box(
                modifier = Modifier
                    .width(4.dp)
                    .fillMaxHeight()
                    .background(accent),
            )
            // Иконка модуля
            Box(
                modifier = Modifier
                    .padding(horizontal = 14.dp, vertical = 14.dp)
                    .size(36.dp),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = module.icon,
                    contentDescription = null,
                    modifier = Modifier.size(28.dp),
                    tint = MaterialTheme.colorScheme.onBackground,
                )
            }
            // Заголовок + подзаголовок
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(1.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = module.title,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onBackground,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    if (module.badge != null) {
                        Spacer(Modifier.width(8.dp))
                        Badge(text = module.badge, accent = accent)
                    }
                    if (!enabled) {
                        Spacer(Modifier.width(8.dp))
                        Badge(text = "СКОРО", accent = MaterialTheme.colorScheme.outline)
                    }
                }
                Text(
                    text = module.subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    overflow = TextOverflow.Ellipsis,
                    maxLines = 2,
                )
            }
            // Chevron
            Icon(
                imageVector = Icons.AutoMirrored.Outlined.KeyboardArrowRight,
                contentDescription = null,
                modifier = Modifier
                    .padding(end = 12.dp)
                    .size(24.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * Маленький моноширинный бейдж в стиле индустриальной маркировки —
 * «ГОСТ 7473», «PDF», «AI» и т.п.
 */
@Composable
private fun Badge(text: String, accent: Color) {
    Surface(
        color = accent.copy(alpha = 0.12f),
        shape = RoundedCornerShape(2.dp),
        border = BorderStroke(0.5.dp, accent.copy(alpha = 0.4f)),
    ) {
        Text(
            text = text.uppercase(),
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold,
            color = accent,
        )
    }
}

private fun pluralModules(n: Int): String {
    val n10 = n % 10
    val n100 = n % 100
    return when {
        n100 in 11..14 -> "модулей"
        n10 == 1 -> "модуль"
        n10 in 2..4 -> "модуля"
        else -> "модулей"
    }
}
