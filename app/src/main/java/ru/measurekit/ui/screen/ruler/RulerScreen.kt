package ru.measurekit.ui.screen.ruler

import android.app.Activity
import android.os.Build
import android.view.WindowManager
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Straighten
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import kotlinx.coroutines.launch
import ru.measurekit.data.MeasurementType
import ru.measurekit.data.MeasurementsRepository
import ru.measurekit.domain.LengthUnit
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Виртуальная линейка на экране устройства.
 *
 * Шкала в мм: деления каждый мм, длинные каждые 10 мм с подписью cm.
 * Шкала в см: то же что в мм, просто компактнее (подписи 1, 2, 3 cm).
 * Шкала в дюймах: деления каждые 1/16", подписи каждый целый дюйм.
 *
 * Маркер ZERO двигается, маркер LEN двигается. Расстояние = |LEN - ZERO|.
 */
private const val TOP_OFFSET_MM = 10f

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RulerScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val activity = ctx as? Activity
    val repo = remember { MeasurementsRepository.get(ctx) }
    val scope = rememberCoroutineScope()
    val snackbarHost = remember { SnackbarHostState() }

    DisposableEffect(activity) {
        val window = activity?.window
        var insetsController: WindowInsetsControllerCompat? = null
        if (window != null) {
            WindowCompat.setDecorFitsSystemWindows(window, false)
            insetsController = WindowInsetsControllerCompat(window, window.decorView).apply {
                hide(WindowInsetsCompat.Type.systemBars())
                systemBarsBehavior = WindowInsetsControllerCompat
                    .BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                window.attributes = window.attributes.apply {
                    layoutInDisplayCutoutMode =
                        WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
                }
            }
        }
        onDispose {
            if (window != null) {
                WindowCompat.setDecorFitsSystemWindows(window, true)
                insetsController?.show(WindowInsetsCompat.Type.systemBars())
            }
        }
    }

    val realXdpi = remember {
        try {
            android.content.res.Resources.getSystem().displayMetrics.xdpi
        } catch (e: Exception) {
            446f
        }
    }

    var calibrationFactor by rememberSaveable { mutableFloatStateOf(1f) }
    var showCalibration by remember { mutableStateOf(false) }
    var showLengthDialog by remember { mutableStateOf(false) }
    var showSaveDialog by remember { mutableStateOf(false) }
    var unit by rememberSaveable { mutableStateOf(LengthUnit.MM.name) }

    // Положения маркеров в десятых мм относительно начала шкалы
    var zeroTenthMm by rememberSaveable { mutableIntStateOf(0) }
    var lenTenthMm by rememberSaveable { mutableIntStateOf(856) }

    var activeMarker by remember { mutableStateOf(MarkerKind.LEN) }

    val effectiveDpi = realXdpi * calibrationFactor
    val pxPerMm = effectiveDpi / 25.4f

    val measuredMm = abs(lenTenthMm - zeroTenthMm) / 10.0
    val activeUnit = LengthUnit.valueOf(unit)

    Scaffold(snackbarHost = { SnackbarHost(snackbarHost) }) { _ ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.surface)
        ) {
            RulerCanvas(
                pxPerMm = pxPerMm,
                zeroTenthMm = zeroTenthMm,
                lenTenthMm = lenTenthMm,
                activeMarker = activeMarker,
                measuredMm = measuredMm,
                unit = activeUnit,
                onMarkerDrag = { kind, newTenthMm ->
                    when (kind) {
                        MarkerKind.ZERO -> zeroTenthMm = newTenthMm.coerceAtLeast(0)
                        MarkerKind.LEN -> lenTenthMm = newTenthMm.coerceAtLeast(0)
                    }
                    activeMarker = kind
                },
                onMarkerTap = { kind -> activeMarker = kind }
            )

            FilledTonalIconButton(
                onClick = onBack,
                modifier = Modifier.align(Alignment.TopStart).padding(8.dp)
            ) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Назад")
            }

            FilledTonalIconButton(
                onClick = { showCalibration = true },
                modifier = Modifier.align(Alignment.TopEnd).padding(8.dp)
            ) {
                Icon(Icons.Filled.Settings, contentDescription = "Калибровка")
            }

            UnitToggle(
                current = activeUnit,
                onChange = { unit = it.name },
                modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 16.dp)
            )

            Column(
                modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                horizontalAlignment = Alignment.End
            ) {
                SmallFloatingActionButton(
                    onClick = { showSaveDialog = true },
                    containerColor = MaterialTheme.colorScheme.secondaryContainer
                ) {
                    Icon(Icons.Filled.Bookmark, contentDescription = "Сохранить в историю")
                }
                SmallFloatingActionButton(
                    onClick = { showLengthDialog = true },
                    containerColor = MaterialTheme.colorScheme.primary
                ) {
                    Icon(Icons.Filled.Edit, contentDescription = "Задать длину")
                }
            }
        }
    }

    if (showCalibration) {
        CalibrationDialog(
            currentFactor = calibrationFactor,
            currentDpi = realXdpi,
            onDismiss = { showCalibration = false },
            onConfirm = { newFactor ->
                calibrationFactor = newFactor
                showCalibration = false
            }
        )
    }

    if (showLengthDialog) {
        LengthInputDialog(
            currentTenthMm = lenTenthMm - zeroTenthMm,
            unit = activeUnit,
            onDismiss = { showLengthDialog = false },
            onConfirm = { newDistanceTenthMm ->
                lenTenthMm = zeroTenthMm + newDistanceTenthMm
                activeMarker = MarkerKind.LEN
                showLengthDialog = false
            }
        )
    }

    if (showSaveDialog) {
        SaveMeasurementDialog(
            measuredMm = measuredMm,
            unit = activeUnit,
            onDismiss = { showSaveDialog = false },
            onConfirm = { note ->
                scope.launch {
                    repo.save(
                        type = MeasurementType.RULER,
                        valueRaw = measuredMm,
                        unit = activeUnit.label,
                        note = note
                    )
                    snackbarHost.showSnackbar("Сохранено: ${activeUnit.format(measuredMm)}")
                }
                showSaveDialog = false
            }
        )
    }
}

enum class MarkerKind { ZERO, LEN }

@Composable
private fun UnitToggle(
    current: LengthUnit,
    onChange: (LengthUnit) -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier,
        shape = MaterialTheme.shapes.small,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        tonalElevation = 2.dp
    ) {
        Row(modifier = Modifier.padding(2.dp)) {
            LengthUnit.entries.forEach { u ->
                val selected = u == current
                TextButton(
                    onClick = { onChange(u) },
                    colors = ButtonDefaults.textButtonColors(
                        contentColor = if (selected) MaterialTheme.colorScheme.primary
                                       else MaterialTheme.colorScheme.onSurfaceVariant
                    ),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)
                ) {
                    Text(
                        text = u.label,
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal
                    )
                }
            }
        }
    }
}

/**
 * Подпись на маркере под выбранную единицу. Достаточно компактная,
 * чтобы влезала в ручку ширины 240px.
 */
private fun formatMarkerLabel(mm: Double, unit: LengthUnit): String {
    return when (unit) {
        LengthUnit.MM -> "%.1f мм".format(mm)
        LengthUnit.CM -> "%.2f см".format(mm / 10.0)
        LengthUnit.INCH -> "%.2f\"".format(mm / 25.4)  // ' " ' - короткий символ дюйма
    }
}

@Composable
private fun RulerCanvas(
    pxPerMm: Float,
    zeroTenthMm: Int,
    lenTenthMm: Int,
    activeMarker: MarkerKind,
    measuredMm: Double,
    unit: LengthUnit,
    onMarkerDrag: (MarkerKind, Int) -> Unit,
    onMarkerTap: (MarkerKind) -> Unit
) {
    val onSurface = MaterialTheme.colorScheme.onSurface
    val primary = MaterialTheme.colorScheme.primary
    val secondary = MaterialTheme.colorScheme.tertiary
    val highlight = primary.copy(alpha = 0.18f)
    val textMeasurer = rememberTextMeasurer()
    val labelStyle = TextStyle(
        fontSize = 12.sp,
        fontWeight = FontWeight.SemiBold,
        color = onSurface
    )
    val zeroDigitStyle = TextStyle(
        fontSize = 14.sp,
        fontWeight = FontWeight.SemiBold,
        color = primary
    )
    val markerLabelStyle = TextStyle(
        fontSize = 16.sp,
        fontWeight = FontWeight.Bold,
        color = Color.White
    )

    val handleWidth = 240f
    val handleHeight = 56f

    // Актуальные значения позиций маркеров для чтения внутри pointerInput.
    // pointerInput с ключом (pxPerMm) НЕ пересоздаётся при изменении позиций,
    // поэтому обычное чтение zeroTenthMm/lenTenthMm в lambda дало бы стейловые значения.
    val currentZero by rememberUpdatedState(zeroTenthMm)
    val currentLen by rememberUpdatedState(lenTenthMm)

    Canvas(
        modifier = Modifier
            .fillMaxSize()
            .pointerInput(pxPerMm) {
                // Локальная переменная: какой маркер сейчас перетаскивается.
                // Определяется ОДИН раз в onDragStart по близости к месту касания
                // и используется на ВЕСЬ жест. Pointer input не пересоздаётся
                // при изменении позиций - иначе жест обрывается и палец прыгает.
                var draggingKind: MarkerKind = MarkerKind.LEN
                detectDragGestures(
                    onDragStart = { offset ->
                        val topPx = TOP_OFFSET_MM * pxPerMm
                        // Читаем актуальные значения из state на момент НАЧАЛА жеста
                        val zeroY = topPx + currentZero / 10f * pxPerMm
                        val lenY = topPx + currentLen / 10f * pxPerMm
                        val distZero = abs(offset.y - zeroY)
                        val distLen = abs(offset.y - lenY)
                        draggingKind = if (distZero < distLen) MarkerKind.ZERO else MarkerKind.LEN
                        onMarkerTap(draggingKind)
                    }
                ) { change, _ ->
                    val topPx = TOP_OFFSET_MM * pxPerMm
                    val mmFromZero = ((change.position.y - topPx) / pxPerMm).coerceAtLeast(0f)
                    val newTenthMm = (mmFromZero * 10f).roundToInt()
                    onMarkerDrag(draggingKind, newTenthMm)
                }
            }
    ) {
        val height = size.height
        val baseX = 0f
        val topOffsetPx = TOP_OFFSET_MM * pxPerMm
        val usableHeight = height - topOffsetPx

        val zeroY = topOffsetPx + (zeroTenthMm / 10f * pxPerMm).coerceIn(0f, usableHeight)
        val lenY = topOffsetPx + (lenTenthMm / 10f * pxPerMm).coerceIn(0f, usableHeight)
        val highlightTop = minOf(zeroY, lenY)
        val highlightHeight = abs(lenY - zeroY)

        drawRect(
            color = highlight,
            topLeft = Offset(0f, highlightTop),
            size = Size(size.width, highlightHeight)
        )

        drawLine(
            color = onSurface,
            start = Offset(baseX, topOffsetPx),
            end = Offset(baseX, height),
            strokeWidth = 3f
        )

        // Шкала зависит от выбранной единицы
        when (unit) {
            LengthUnit.MM, LengthUnit.CM -> drawMmScale(
                topOffsetPx = topOffsetPx,
                pxPerMm = pxPerMm,
                usableHeight = usableHeight,
                height = height,
                baseX = baseX,
                onSurface = onSurface,
                textMeasurer = textMeasurer,
                labelStyle = labelStyle
            )
            LengthUnit.INCH -> drawInchScale(
                topOffsetPx = topOffsetPx,
                pxPerMm = pxPerMm,
                usableHeight = usableHeight,
                height = height,
                baseX = baseX,
                onSurface = onSurface,
                textMeasurer = textMeasurer,
                labelStyle = labelStyle
            )
        }

        // Подпись "0" возле первого деления (общая для всех шкал)
        drawText(
            textMeasurer = textMeasurer,
            text = "0",
            style = zeroDigitStyle,
            topLeft = Offset(baseX + 55f + 8f, topOffsetPx - 10f)
        )

        // Маркер ZERO
        drawMarker(
            y = zeroY,
            label = "ZERO",
            color = secondary,
            isActive = activeMarker == MarkerKind.ZERO,
            size = size,
            handleWidth = handleWidth,
            handleHeight = handleHeight,
            handleOffsetY = -handleHeight - 4f,
            textMeasurer = textMeasurer,
            labelStyle = markerLabelStyle
        )

        // Маркер LEN
        drawMarker(
            y = lenY,
            label = formatMarkerLabel(measuredMm, unit),
            color = primary,
            isActive = activeMarker == MarkerKind.LEN,
            size = size,
            handleWidth = handleWidth,
            handleHeight = handleHeight,
            handleOffsetY = 4f,
            textMeasurer = textMeasurer,
            labelStyle = markerLabelStyle
        )
    }
}

/**
 * Метрическая шкала: деление каждый мм, средние каждые 5 мм, длинные каждые 10 мм.
 * Подписи целых сантиметров.
 */
private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawMmScale(
    topOffsetPx: Float,
    pxPerMm: Float,
    usableHeight: Float,
    height: Float,
    baseX: Float,
    onSurface: Color,
    textMeasurer: androidx.compose.ui.text.TextMeasurer,
    labelStyle: TextStyle
) {
    val tickShort = 20f
    val tickMid = 35f
    val tickLong = 55f

    val totalMm = (usableHeight / pxPerMm).toInt() + 1
    for (mm in 0..totalMm) {
        val y = topOffsetPx + mm * pxPerMm
        if (y > height) break

        val (tickLen, strokeW) = when {
            mm % 10 == 0 -> tickLong to 3f
            mm % 5 == 0 -> tickMid to 2f
            else -> tickShort to 1.5f
        }

        drawLine(
            color = onSurface,
            start = Offset(baseX, y),
            end = Offset(baseX + tickLen, y),
            strokeWidth = strokeW
        )

        if (mm % 10 == 0 && mm > 0) {
            val cm = mm / 10
            drawText(
                textMeasurer = textMeasurer,
                text = cm.toString(),
                style = labelStyle,
                topLeft = Offset(baseX + tickLong + 8f, y - 10f)
            )
        }
    }
}

/**
 * Дюймовая шкала: деление каждые 1/16", средние каждые 1/4", длинные каждые 1/2",
 * самые длинные каждый целый дюйм с подписью.
 *
 * 1 дюйм = 25.4 мм, 1/16" = 1.5875 мм
 */
private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawInchScale(
    topOffsetPx: Float,
    pxPerMm: Float,
    usableHeight: Float,
    height: Float,
    baseX: Float,
    onSurface: Color,
    textMeasurer: androidx.compose.ui.text.TextMeasurer,
    labelStyle: TextStyle
) {
    val tickXS = 15f      // 1/16"
    val tickShort = 22f   // 1/8"
    val tickMid = 32f     // 1/4"
    val tickLong = 42f    // 1/2"
    val tickXL = 55f      // целый дюйм

    val mmPerSixteenth = 25.4f / 16f  // 1.5875 мм
    val totalSixteenths = (usableHeight / (mmPerSixteenth * pxPerMm)).toInt() + 1

    for (n in 0..totalSixteenths) {
        val y = topOffsetPx + n * mmPerSixteenth * pxPerMm
        if (y > height) break

        // n - количество шестнадцатых от нуля
        // целый дюйм = n кратно 16; 1/2 = кратно 8; 1/4 = кратно 4; 1/8 = кратно 2; 1/16 = иначе
        val (tickLen, strokeW) = when {
            n % 16 == 0 -> tickXL to 3f
            n % 8 == 0 -> tickLong to 2.5f
            n % 4 == 0 -> tickMid to 2f
            n % 2 == 0 -> tickShort to 1.5f
            else -> tickXS to 1f
        }

        drawLine(
            color = onSurface,
            start = Offset(baseX, y),
            end = Offset(baseX + tickLen, y),
            strokeWidth = strokeW
        )

        if (n % 16 == 0 && n > 0) {
            val inches = n / 16
            drawText(
                textMeasurer = textMeasurer,
                text = inches.toString(),
                style = labelStyle,
                topLeft = Offset(baseX + tickXL + 8f, y - 10f)
            )
        }
    }
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawMarker(
    y: Float,
    label: String,
    color: Color,
    isActive: Boolean,
    size: androidx.compose.ui.geometry.Size,
    handleWidth: Float,
    handleHeight: Float,
    handleOffsetY: Float,
    textMeasurer: androidx.compose.ui.text.TextMeasurer,
    labelStyle: TextStyle
) {
    drawLine(
        color = color,
        start = Offset(0f, y),
        end = Offset(size.width, y),
        strokeWidth = if (isActive) 5f else 3f
    )

    val handleX = size.width / 2f - handleWidth / 2f
    val handleY = y + handleOffsetY
    drawRect(
        color = color,
        topLeft = Offset(handleX, handleY),
        size = Size(handleWidth, handleHeight)
    )

    val labelLayout = textMeasurer.measure(label, labelStyle)
    drawText(
        textLayoutResult = labelLayout,
        color = Color.White,
        topLeft = Offset(
            handleX + (handleWidth - labelLayout.size.width) / 2f,
            handleY + (handleHeight - labelLayout.size.height) / 2f
        )
    )
}

@Composable
private fun LengthInputDialog(
    currentTenthMm: Int,
    unit: LengthUnit,
    onDismiss: () -> Unit,
    onConfirm: (Int) -> Unit
) {
    var textValue by remember {
        mutableStateOf(formatForUnit(currentTenthMm, unit))
    }
    var parsedTenthMm by remember { mutableIntStateOf(currentTenthMm) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Длина измерения") },
        text = {
            Column {
                OutlinedTextField(
                    value = textValue,
                    onValueChange = { newText ->
                        textValue = newText
                        val v = newText.replace(',', '.').toDoubleOrNull()
                        if (v != null && v >= 0.0) {
                            val mm = unit.toMm(v)
                            parsedTenthMm = (mm * 10.0).roundToInt()
                        }
                    },
                    label = { Text("Длина, ${unit.label}") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    text = "= ${LengthUnit.MM.format(parsedTenthMm / 10.0)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(16.dp))
                Text(
                    text = "Эталоны:",
                    style = MaterialTheme.typography.titleSmall
                )
                Spacer(Modifier.height(8.dp))

                val presets = listOf(
                    "Кредитка 85.6 мм" to 856,
                    "Спич. кор. 50 мм" to 500,
                    "Монета 10₽ 22.0 мм" to 220,
                    "Монета 5₽ 25.0 мм" to 250,
                    "Монета 2₽ 23.0 мм" to 230,
                    "Монета 1₽ 20.5 мм" to 205,
                    "5 см" to 500,
                    "10 см" to 1000,
                )
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    presets.chunked(2).forEach { row ->
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            row.forEach { (label, valueT) ->
                                FilterChip(
                                    selected = parsedTenthMm == valueT,
                                    onClick = {
                                        parsedTenthMm = valueT
                                        textValue = formatForUnit(valueT, unit)
                                    },
                                    label = {
                                        Text(
                                            text = label,
                                            style = MaterialTheme.typography.labelSmall,
                                            textAlign = TextAlign.Start
                                        )
                                    },
                                    modifier = Modifier.weight(1f)
                                )
                            }
                            if (row.size == 1) Spacer(Modifier.weight(1f))
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(parsedTenthMm) }) { Text("Применить") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Отмена") }
        }
    )
}

@Composable
private fun SaveMeasurementDialog(
    measuredMm: Double,
    unit: LengthUnit,
    onDismiss: () -> Unit,
    onConfirm: (note: String) -> Unit
) {
    var note by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Сохранить измерение") },
        text = {
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Filled.Straighten,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = unit.format(measuredMm),
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold
                    )
                }
                Spacer(Modifier.height(16.dp))
                OutlinedTextField(
                    value = note,
                    onValueChange = { note = it },
                    label = { Text("Заметка (необязательно)") },
                    placeholder = { Text("Например: ширина книги") },
                    singleLine = false,
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

private fun formatForUnit(tenthMm: Int, unit: LengthUnit): String {
    val v = unit.fromMm(tenthMm / 10.0)
    return when (unit) {
        LengthUnit.MM -> "%.1f".format(v)
        LengthUnit.CM -> "%.2f".format(v)
        LengthUnit.INCH -> "%.3f".format(v)
    }
}

@Composable
private fun CalibrationDialog(
    currentFactor: Float,
    currentDpi: Float,
    onDismiss: () -> Unit,
    onConfirm: (Float) -> Unit
) {
    var factor by remember { mutableFloatStateOf(currentFactor) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Калибровка линейки") },
        text = {
            Column {
                Text(
                    text = "Приложи кредитную карту длинной стороной к линейке. " +
                            "Реальная длина — 85.6 мм (стандарт ISO/IEC 7810).",
                    style = MaterialTheme.typography.bodyMedium
                )
                Spacer(Modifier.height(16.dp))
                Text(
                    text = "Подгони множитель так, чтобы отметка 85.6 мм совпала с краем карты:",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    text = "Множитель: ${"%.3f".format(factor)}",
                    style = MaterialTheme.typography.titleMedium
                )
                Slider(
                    value = factor,
                    onValueChange = { factor = it },
                    valueRange = 0.85f..1.15f,
                    steps = 119
                )
                Text(
                    text = "Эффективный DPI: ${"%.1f".format(currentDpi * factor)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(8.dp))
                TextButton(onClick = { factor = 1f }) {
                    Text("Сбросить к системному DPI")
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(factor) }) { Text("Применить") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Отмена") }
        }
    )
}
