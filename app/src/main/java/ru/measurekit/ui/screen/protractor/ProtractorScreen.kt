package ru.measurekit.ui.screen.protractor

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Anchor
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import ru.measurekit.data.MeasurementType
import ru.measurekit.data.MeasurementsRepository
import ru.measurekit.domain.sensor.rememberOrientation
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * Транспортир — измерение угла наклона устройства.
 *
 * Источник угла: те же pitch/roll что и в уровне. По умолчанию — pitch
 * (наклон вверх-низ при ribbon-приложении к стене), переключатель меняет на roll.
 *
 * Шкала:
 *   - Полукруг ±90°: для измерения уклонов от горизонта (стандарт для строительства)
 *   - Полный круг 0..360°: для измерения угла поворота относительно вертикали
 *
 * Зафиксировать ноль:
 *   Сохраняем текущий угол как референс. Дальше показываем не абсолютный угол,
 *   а разницу с момента фиксации. Полезно для "насколько повернул предмет".
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProtractorScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val repo = remember { MeasurementsRepository.get(ctx) }
    val scope = rememberCoroutineScope()
    val snackbarHost = remember { SnackbarHostState() }

    val orientation by rememberOrientation()

    var axis by rememberSaveable { mutableStateOf(ProtractorAxis.PITCH.name) }
    var scaleType by rememberSaveable { mutableStateOf(ProtractorScale.HALF.name) }
    var anchorAngle by rememberSaveable { mutableStateOf<Float?>(null) }
    var showSaveDialog by remember { mutableStateOf(false) }

    val activeAxis = ProtractorAxis.valueOf(axis)
    val activeScale = ProtractorScale.valueOf(scaleType)

    // Сырое значение угла из выбранной оси
    val rawAngle = when (activeAxis) {
        ProtractorAxis.PITCH -> orientation.pitch
        ProtractorAxis.ROLL -> orientation.roll
    }

    // Эффективный угол: с поправкой на якорь, если он установлен
    val effectiveAngle = if (anchorAngle != null) {
        normalizeAngle(rawAngle - anchorAngle!!, activeScale)
    } else {
        normalizeAngle(rawAngle, activeScale)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Транспортир") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Назад")
                    }
                },
                actions = {
                    if (anchorAngle != null) {
                        IconButton(onClick = { anchorAngle = null }) {
                            Icon(
                                Icons.Filled.Restore,
                                contentDescription = "Сбросить точку отсчёта",
                                tint = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                    IconButton(onClick = { anchorAngle = rawAngle }) {
                        Icon(
                            Icons.Filled.Anchor,
                            contentDescription = "Зафиксировать ноль",
                            tint = if (anchorAngle != null)
                                MaterialTheme.colorScheme.primary
                            else
                                LocalContentColor.current
                        )
                    }
                    IconButton(onClick = { showSaveDialog = true }) {
                        Icon(Icons.Filled.Bookmark, contentDescription = "Сохранить")
                    }
                }
            )
        },
        snackbarHost = { SnackbarHost(snackbarHost) }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .background(MaterialTheme.colorScheme.surface),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Селектор шкалы (полукруг / полный круг)
            ScaleSelector(
                current = activeScale,
                onChange = { scaleType = it.name },
                modifier = Modifier.padding(top = 8.dp)
            )

            // Селектор оси
            AxisSelector(
                current = activeAxis,
                onChange = { axis = it.name },
                modifier = Modifier.padding(top = 8.dp)
            )

            // Сама шкала транспортира
            Box(
                modifier = Modifier.weight(1f).fillMaxWidth(),
                contentAlignment = Alignment.Center
            ) {
                if (!orientation.available) {
                    Text(
                        text = "Ожидание данных датчика…",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    ProtractorDial(
                        angle = effectiveAngle,
                        scale = activeScale,
                        hasAnchor = anchorAngle != null
                    )
                }
            }

            // Цифровая индикация
            ReadoutCard(
                angle = effectiveAngle,
                scale = activeScale,
                hasAnchor = anchorAngle != null,
                modifier = Modifier.padding(16.dp).fillMaxWidth()
            )
        }
    }

    if (showSaveDialog) {
        SaveDialog(
            angle = effectiveAngle,
            hasAnchor = anchorAngle != null,
            onDismiss = { showSaveDialog = false },
            onConfirm = { note ->
                val noteFull = buildString {
                    if (anchorAngle != null) append("относит. (анкер ${"%.1f".format(anchorAngle!!)}°): ")
                    else append("абсолют.: ")
                    append("${"%+.1f".format(effectiveAngle)}°")
                    if (note.isNotBlank()) append(" — $note")
                }
                scope.launch {
                    repo.save(
                        type = MeasurementType.PROTRACTOR,
                        valueRaw = effectiveAngle.toDouble(),
                        unit = "°",
                        note = noteFull
                    )
                    snackbarHost.showSnackbar("Сохранено: ${"%+.1f".format(effectiveAngle)}°")
                }
                showSaveDialog = false
            }
        )
    }
}

enum class ProtractorAxis { PITCH, ROLL }
enum class ProtractorScale { HALF, FULL }   // ±90° или 0..360°

/**
 * Приведение угла в нужный диапазон.
 *  HALF: [-180, +180] - полный диапазон со знаком, удобно для уклонов
 *  FULL: [0, 360) - полный круг без знака, удобно для поворотов
 */
private fun normalizeAngle(angle: Float, scale: ProtractorScale): Float {
    return when (scale) {
        ProtractorScale.HALF -> {
            // Приводим к [-180, 180]
            var a = angle
            while (a > 180f) a -= 360f
            while (a < -180f) a += 360f
            a
        }
        ProtractorScale.FULL -> {
            var a = angle % 360f
            if (a < 0f) a += 360f
            a
        }
    }
}

@Composable
private fun ScaleSelector(
    current: ProtractorScale,
    onChange: (ProtractorScale) -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier,
        shape = MaterialTheme.shapes.small,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        tonalElevation = 2.dp
    ) {
        Row(modifier = Modifier.padding(2.dp)) {
            listOf(
                ProtractorScale.HALF to "±180°",
                ProtractorScale.FULL to "0..360°"
            ).forEach { (s, label) ->
                val selected = s == current
                TextButton(
                    onClick = { onChange(s) },
                    colors = ButtonDefaults.textButtonColors(
                        contentColor = if (selected) MaterialTheme.colorScheme.primary
                                       else MaterialTheme.colorScheme.onSurfaceVariant
                    ),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)
                ) {
                    Text(
                        text = label,
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal
                    )
                }
            }
        }
    }
}

@Composable
private fun AxisSelector(
    current: ProtractorAxis,
    onChange: (ProtractorAxis) -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier,
        shape = MaterialTheme.shapes.small,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Row(modifier = Modifier.padding(2.dp)) {
            listOf(
                ProtractorAxis.PITCH to "Pitch (вдоль)",
                ProtractorAxis.ROLL to "Roll (поперёк)"
            ).forEach { (a, label) ->
                val selected = a == current
                TextButton(
                    onClick = { onChange(a) },
                    colors = ButtonDefaults.textButtonColors(
                        contentColor = if (selected) MaterialTheme.colorScheme.primary
                                       else MaterialTheme.colorScheme.onSurfaceVariant
                    ),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)
                ) {
                    Text(
                        text = label,
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal
                    )
                }
            }
        }
    }
}

/**
 * Рисуем транспортир — шкалу с делениями и стрелку.
 * Для HALF: полукруг от 270° до 90° по стандарту (вверх=0°, вправо=+90°, влево=-90°).
 * Для FULL: полный круг 0..360°, 0 сверху, по часовой.
 */
@Composable
private fun ProtractorDial(
    angle: Float,
    scale: ProtractorScale,
    hasAnchor: Boolean
) {
    val onSurface = MaterialTheme.colorScheme.onSurface
    val outline = MaterialTheme.colorScheme.outline
    val primary = MaterialTheme.colorScheme.primary
    val tertiary = MaterialTheme.colorScheme.tertiary
    val surface = MaterialTheme.colorScheme.surface
    val arrowColor = if (hasAnchor) tertiary else primary

    val textMeasurer = rememberTextMeasurer()
    val tickLabelStyle = TextStyle(
        fontSize = 11.sp,
        fontWeight = FontWeight.SemiBold,
        color = onSurface
    )
    val centerLabelStyle = TextStyle(
        fontSize = 56.sp,
        fontWeight = FontWeight.Bold,
        color = onSurface
    )

    Canvas(modifier = Modifier.fillMaxSize().padding(24.dp)) {
        val cx = size.width / 2f
        val cy = size.height / 2f
        val outerRadius = min(size.width, size.height) / 2f - 16f
        val innerRadius = outerRadius * 0.65f

        // Контур шкалы - всегда полный круг
        drawCircle(
            color = outline,
            radius = outerRadius,
            center = Offset(cx, cy),
            style = Stroke(width = 3f)
        )
        // Горизонтальная линия для удобства видения "горизонта"
        if (scale == ProtractorScale.HALF) {
            drawLine(
                color = outline.copy(alpha = 0.4f),
                start = Offset(cx - outerRadius, cy),
                end = Offset(cx + outerRadius, cy),
                strokeWidth = 1.5f
            )
        }

        // Деления и подписи
        val tickStep = 5  // деление каждые 5°
        val labelStep = 30 // подпись каждые 30°
        val (start, end) = when (scale) {
            ProtractorScale.HALF -> -180 to 180
            ProtractorScale.FULL -> 0 to 359
        }

        for (deg in start..end step tickStep) {
            // -180 и +180 это одна и та же точка на круге - пропускаем -180,
            // чтобы не было двух подписей в одном месте
            if (scale == ProtractorScale.HALF && deg == -180) continue
            val angleRad = degToCanvasRad(deg.toFloat(), scale)
            val isMajor = deg % labelStep == 0
            val tickLen = if (isMajor) outerRadius * 0.12f else outerRadius * 0.06f
            val strokeW = if (isMajor) 2.5f else 1.5f

            val x1 = cx + cos(angleRad) * (outerRadius - tickLen)
            val y1 = cy + sin(angleRad) * (outerRadius - tickLen)
            val x2 = cx + cos(angleRad) * outerRadius
            val y2 = cy + sin(angleRad) * outerRadius

            drawLine(
                color = onSurface,
                start = Offset(x1, y1),
                end = Offset(x2, y2),
                strokeWidth = strokeW
            )

            if (isMajor) {
                val labelText = deg.toString()
                val labelLayout = textMeasurer.measure(labelText, tickLabelStyle)
                val labelRadius = outerRadius - tickLen - 18f
                val lx = cx + cos(angleRad) * labelRadius - labelLayout.size.width / 2f
                val ly = cy + sin(angleRad) * labelRadius - labelLayout.size.height / 2f
                drawText(
                    textLayoutResult = labelLayout,
                    color = onSurface,
                    topLeft = Offset(lx, ly)
                )
            }
        }

        // Опорная линия "0" подсветим
        val zeroAngle = degToCanvasRad(0f, scale)
        val zeroX = cx + cos(zeroAngle) * outerRadius
        val zeroY = cy + sin(zeroAngle) * outerRadius
        drawLine(
            color = if (hasAnchor) tertiary else outline,
            start = Offset(cx, cy),
            end = Offset(zeroX, zeroY),
            strokeWidth = 1.5f
        )

        // Стрелка для текущего угла
        val arrowAngle = degToCanvasRad(angle, scale)
        val arrowEndX = cx + cos(arrowAngle) * (outerRadius - 4f)
        val arrowEndY = cy + sin(arrowAngle) * (outerRadius - 4f)

        // Тень стрелки
        drawLine(
            color = arrowColor.copy(alpha = 0.25f),
            start = Offset(cx, cy),
            end = Offset(arrowEndX, arrowEndY),
            strokeWidth = 16f
        )
        // Сама стрелка
        drawLine(
            color = arrowColor,
            start = Offset(cx, cy),
            end = Offset(arrowEndX, arrowEndY),
            strokeWidth = 6f
        )
        // Наконечник стрелки — кружок
        drawCircle(
            color = arrowColor,
            radius = 14f,
            center = Offset(arrowEndX, arrowEndY)
        )

        // Центральная точка
        drawCircle(
            color = onSurface,
            radius = 10f,
            center = Offset(cx, cy)
        )
        drawCircle(
            color = surface,
            radius = 5f,
            center = Offset(cx, cy)
        )

        // Цифра в центре (внутри полукруга или круга)
        val angleStr = if (scale == ProtractorScale.HALF) {
            "%+.1f°".format(angle)
        } else {
            "%.1f°".format(angle)
        }
        val angleLayout = textMeasurer.measure(angleStr, centerLabelStyle)
        // В режиме полукруга цифра ниже центра, в полном круге — точно в центре
        val labelOffsetY = when (scale) {
            ProtractorScale.HALF -> innerRadius * 0.5f
            ProtractorScale.FULL -> 0f
        }
        drawText(
            textLayoutResult = angleLayout,
            topLeft = Offset(
                cx - angleLayout.size.width / 2f,
                cy + labelOffsetY - angleLayout.size.height / 2f
            ),
            color = onSurface
        )
    }
}

/**
 * Перевод "градус наклона" в угол на канвасе (в радианах).
 * Canvas: 0 рад = справа (+X), угол растёт по часовой.
 *
 * Для нашей шкалы:
 *   HALF: 0° наклона = вверх (-π/2), +90° = вправо (0), -90° = влево (π или -π).
 *         Формула: canvas = -π/2 + (deg * π/180)
 *   FULL: 0° = вверх, 90° = вправо.
 *         Формула: canvas = -π/2 + (deg * π/180)
 *   Обе формулы совпадают.
 */
private fun degToCanvasRad(deg: Float, scale: ProtractorScale): Float {
    return -PI.toFloat() / 2f + deg * PI.toFloat() / 180f
}

@Composable
private fun ReadoutCard(
    angle: Float,
    scale: ProtractorScale,
    hasAnchor: Boolean,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(
            containerColor = if (hasAnchor) MaterialTheme.colorScheme.tertiaryContainer
                             else MaterialTheme.colorScheme.surfaceContainerHigh
        )
    ) {
        Column(
            modifier = Modifier.padding(12.dp).fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = if (hasAnchor) "Относительно якоря" else "Абсолютный угол",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(4.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically
            ) {
                AngleReadout(
                    label = "Угол",
                    value = if (scale == ProtractorScale.HALF) "%+.1f°".format(angle)
                            else "%.1f°".format(angle)
                )
                AngleReadout(
                    label = "В радианах",
                    value = "%.3f".format(angle * PI.toFloat() / 180f)
                )
                if (scale == ProtractorScale.HALF) {
                    AngleReadout(
                        label = "Уклон",
                        value = formatSlope(angle)
                    )
                }
            }
        }
    }
}

@Composable
private fun AngleReadout(label: String, value: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = value,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold
        )
    }
}

/**
 * Форматирование уклона в %, как в строительстве.
 * 45° = 100%, 0° = 0%.
 */
private fun formatSlope(degrees: Float): String {
    val percent = kotlin.math.tan(degrees * PI.toFloat() / 180f) * 100f
    return when {
        kotlin.math.abs(percent) >= 999f -> "—"
        kotlin.math.abs(percent) < 1f -> "%.2f%%".format(percent)
        else -> "%.1f%%".format(percent)
    }
}

@Composable
private fun SaveDialog(
    angle: Float,
    hasAnchor: Boolean,
    onDismiss: () -> Unit,
    onConfirm: (note: String) -> Unit
) {
    var note by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Сохранить угол") },
        text = {
            Column {
                Text(
                    text = "%+.1f°".format(angle),
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold
                )
                if (hasAnchor) {
                    Text(
                        text = "относительно зафиксированного нуля",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Spacer(Modifier.height(16.dp))
                OutlinedTextField(
                    value = note,
                    onValueChange = { note = it },
                    label = { Text("Заметка (необязательно)") },
                    placeholder = { Text("Например: уклон рампы") },
                    minLines = 2,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(note.trim()) }) { Text("Сохранить") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Отмена") }
        }
    )
}
