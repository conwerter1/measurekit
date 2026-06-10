package ru.measurekit.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.sp

/**
 * Профессиональный «индустриальный» стиль интерфейса (по образцу Bosch /
 * Hilti / Festool). Намеренно отключаем dynamicColor — иначе цвета на каждом
 * устройстве подстроятся под обои и узнаваемость пропадёт.
 *
 * Палитра:
 *   - primary    — тёмно-синий «производственный» #003B5C
 *   - secondary  — холодный графит для второстепенных надписей
 *   - tertiary   — Hilti blue #009BBC, акцент для активных элементов
 *   - error      — индустриальный красный для критики (как «стоп»)
 *   - background — почти-белый/почти-чёрный без оттенков, как в HMI
 *
 * Скругления и тени мы не задаём в схеме (Material делает свои дефолты),
 * но компоненты на уровне UI должны использовать прямоугольники / 4dp
 * скругления и border вместо elevation.
 */

// === Light ===
private val LightScheme = lightColorScheme(
    primary           = Color(0xFF003B5C),  // тёмно-синий Bosch-style
    onPrimary         = Color(0xFFFFFFFF),
    primaryContainer  = Color(0xFFCFE4F5),
    onPrimaryContainer= Color(0xFF002033),

    secondary         = Color(0xFF455A64),  // графит
    onSecondary       = Color(0xFFFFFFFF),
    secondaryContainer= Color(0xFFD7E3EA),
    onSecondaryContainer= Color(0xFF1B2A33),

    tertiary          = Color(0xFF009BBC),  // Hilti blue, акцент
    onTertiary        = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFB8E8F2),
    onTertiaryContainer= Color(0xFF002F3A),

    error             = Color(0xFFC62828),  // индустриальный красный
    onError           = Color(0xFFFFFFFF),
    errorContainer    = Color(0xFFFFCDD2),
    onErrorContainer  = Color(0xFF410002),

    background        = Color(0xFFF5F7F8),  // холодный почти-белый
    onBackground      = Color(0xFF1A1C1E),
    surface           = Color(0xFFFFFFFF),
    onSurface         = Color(0xFF1A1C1E),
    surfaceVariant    = Color(0xFFE1E5E8),  // плотная серая «панель»
    onSurfaceVariant  = Color(0xFF44474A),

    outline           = Color(0xFF74787C),  // тонкие границы
    outlineVariant    = Color(0xFFC4C7CA),  // разделители

    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow    = Color(0xFFF0F3F4),
    surfaceContainer       = Color(0xFFEAEEF0),
    surfaceContainerHigh   = Color(0xFFE3E7EA),
    surfaceContainerHighest= Color(0xFFDDE2E5),
)

// === Dark ===
private val DarkScheme = darkColorScheme(
    primary           = Color(0xFF74C2EA),  // светло-синий, читается на тёмном
    onPrimary         = Color(0xFF002033),
    primaryContainer  = Color(0xFF003B5C),
    onPrimaryContainer= Color(0xFFCFE4F5),

    secondary         = Color(0xFFB8C7CE),
    onSecondary       = Color(0xFF22323B),
    secondaryContainer= Color(0xFF334852),
    onSecondaryContainer= Color(0xFFD7E3EA),

    tertiary          = Color(0xFF5DD0E8),  // Hilti blue светлее
    onTertiary        = Color(0xFF002F3A),
    tertiaryContainer = Color(0xFF005064),
    onTertiaryContainer= Color(0xFFB8E8F2),

    error             = Color(0xFFEF5350),
    onError           = Color(0xFF410002),
    errorContainer    = Color(0xFF8E0000),
    onErrorContainer  = Color(0xFFFFDAD6),

    background        = Color(0xFF101214),  // почти-чёрный
    onBackground      = Color(0xFFE2E2E5),
    surface           = Color(0xFF161A1D),  // приподнятая «панель»
    onSurface         = Color(0xFFE2E2E5),
    surfaceVariant    = Color(0xFF2A2E32),
    onSurfaceVariant  = Color(0xFFC2C7CB),

    outline           = Color(0xFF8C9094),
    outlineVariant    = Color(0xFF44484C),

    surfaceContainerLowest = Color(0xFF0B0D0F),
    surfaceContainerLow    = Color(0xFF161A1D),
    surfaceContainer       = Color(0xFF1B1F22),
    surfaceContainerHigh   = Color(0xFF252A2E),
    surfaceContainerHighest= Color(0xFF2F343A),
)

/**
 * Типографика «индустриальная»: чуть более плотная, чуть меньше декоративных
 * вариаций. Опираемся на дефолтный sans-serif (Roboto на Android), но даём
 * заголовкам жирность и трекинг как в HMI-панелях.
 */
private val IndustrialTypography = Typography(
    // Заголовки разделов / экранов
    headlineMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Bold,
        fontSize = 22.sp,
        lineHeight = 28.sp,
        letterSpacing = 0.sp,
    ),
    headlineSmall = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 20.sp,
        lineHeight = 26.sp,
        letterSpacing = 0.sp,
    ),
    titleLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 17.sp,
        lineHeight = 22.sp,
        letterSpacing = 0.sp,
    ),
    titleMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 15.sp,
        lineHeight = 20.sp,
        letterSpacing = 0.1.sp,
    ),
    titleSmall = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 13.sp,
        lineHeight = 18.sp,
        letterSpacing = 0.1.sp,
    ),
    // Метки (подписи к данным, мини-заголовки в стиле HMI «UPPERCASE TRACKED»)
    labelLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Medium,
        fontSize = 13.sp,
        lineHeight = 18.sp,
        letterSpacing = 0.5.sp,
    ),
    labelMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Medium,
        fontSize = 12.sp,
        lineHeight = 16.sp,
        letterSpacing = 0.5.sp,
    ),
    labelSmall = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Medium,
        fontSize = 11.sp,
        lineHeight = 14.sp,
        letterSpacing = 0.5.sp,
    ),
)

@Composable
fun MeasureKitTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    val colorScheme = if (darkTheme) DarkScheme else LightScheme
    MaterialTheme(
        colorScheme = colorScheme,
        typography = IndustrialTypography,
        content = content,
    )
}
