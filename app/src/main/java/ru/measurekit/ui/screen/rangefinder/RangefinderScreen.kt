package ru.measurekit.ui.screen.rangefinder

import android.Manifest
import android.content.Context
import android.content.SharedPreferences
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Height
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Straighten
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.isGranted
import com.google.accompanist.permissions.rememberPermissionState
import kotlinx.coroutines.launch
import ru.measurekit.data.MeasurementType
import ru.measurekit.data.MeasurementsRepository
import ru.measurekit.domain.sensor.rememberOrientation
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.tan

/**
 * Дальномер: измерение расстояния до объекта и его высоты по углу наклона.
 *
 * Принцип (адаптирован из открытых проектов, например mostafa1075/Camera_Measure):
 *   - Пользователь держит телефон вертикально (экран на себя), у глаз/у лба.
 *   - В этом положении pitch ≈ 0°. Когда телефон наклоняется вниз, pitch < 0.
 *   - Шаг 1: наводит перекрестие на ОСНОВАНИЕ объекта, тапает "Захватить низ".
 *            Запоминается angleBottom = -pitch (угол вниз от горизонта, положительный).
 *   - Шаг 2: поднимает телефон, наводит перекрестие на ВЕРХ объекта,
 *            тапает "Захватить верх". Запоминается angleTop.
 *
 * Формулы:
 *   D = h_eye / tan(angleBottom)      - расстояние до объекта
 *   H = D * tan(angleTop) + h_eye     - высота объекта
 *     где angleTop > 0 если объект выше глаз, < 0 если ниже
 *
 * Ограничения:
 *   - Объект должен быть на земле (или на той же горизонтальной плоскости что и пользователь)
 *   - Дальность реалистичная: 2..25 метров
 *   - Точность ±5..10% при стабильном держании
 */

private const val PREFS_NAME = "rangefinder_prefs"
private const val KEY_EYE_HEIGHT_CM = "eye_height_cm"
private const val DEFAULT_EYE_HEIGHT_CM = 170  // соответствует росту 180 см

@OptIn(ExperimentalMaterial3Api::class, ExperimentalPermissionsApi::class)
@Composable
fun RangefinderScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val repo = remember { MeasurementsRepository.get(ctx) }
    val scope = rememberCoroutineScope()
    val snackbarHost = remember { SnackbarHostState() }
    val prefs = remember {
        ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    val orientation by rememberOrientation()
    val cameraPermissionState = rememberPermissionState(Manifest.permission.CAMERA)

    var eyeHeightCm by rememberSaveable {
        mutableIntStateOf(prefs.getInt(KEY_EYE_HEIGHT_CM, DEFAULT_EYE_HEIGHT_CM))
    }
    var showSettingsDialog by remember { mutableStateOf(false) }
    var showSaveDialog by remember { mutableStateOf(false) }

    // Захваченные углы. null = не захвачен.
    // Используем remember (не rememberSaveable) - чтобы при каждом входе на экран
    // начинать с чистого листа, не таскать старые значения от прошлой сессии.
    var angleBottom by remember { mutableStateOf<Float?>(null) }
    var angleTop by remember { mutableStateOf<Float?>(null) }

    // Запросим разрешение при первом входе
    LaunchedEffect(Unit) {
        if (!cameraPermissionState.status.isGranted) {
            cameraPermissionState.launchPermissionRequest()
        }
    }

    // Семантика наклона (проверена по реальным замерам на телефоне):
    //   Телефон лежит экраном вверх (горизонтально): pitch = 0
    //   Телефон вертикально, экран на пользователя:  pitch = -90 (опорная позиция)
    //   Телефон наклонён ВНИЗ (целимся на основание, верх телефона ОТ пользователя):
    //                                                pitch -> 0 (например -71)
    //   Телефон поднят ВВЕРХ (целимся в потолок, верх телефона К пользователю):
    //                                                pitch -> -180 (например -115)
    //
    // Угол наклона вниз от опоры = pitch + 90
    //   Вертикально (опора):  -90 + 90 = 0
    //   Наклон вниз 18° (pitch=-71.8):  -71.8 + 90 = +18.2
    //   Наклон вверх 2° (pitch=-92):    -92 + 90 = -2
    val currentTiltDown = orientation.pitch + 90f

    val distance = computeDistance(angleBottom, eyeHeightCm)
    val height = computeHeight(angleBottom, angleTop, eyeHeightCm)

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHost) }
    ) { _ ->
        Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
            // Фон: камера или чёрный экран
            if (cameraPermissionState.status.isGranted) {
                CameraPreview(modifier = Modifier.fillMaxSize())
            }

            // Перекрестие в центре экрана
            Crosshair(
                modifier = Modifier.fillMaxSize(),
                color = if (currentIsValidAngle(currentTiltDown))
                    MaterialTheme.colorScheme.primary
                else
                    Color.Yellow
            )

            // Верхняя панель
            Row(
                modifier = Modifier.align(Alignment.TopCenter)
                    .fillMaxWidth().padding(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                FilledTonalIconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Назад")
                }
                Spacer(Modifier.weight(1f))
                FilledTonalIconButton(onClick = { showSettingsDialog = true }) {
                    Icon(Icons.Filled.Settings, contentDescription = "Настройки")
                }
            }

            // Текущий угол - крупно сверху
            Surface(
                modifier = Modifier.align(Alignment.TopCenter).padding(top = 64.dp),
                shape = MaterialTheme.shapes.medium,
                color = Color.Black.copy(alpha = 0.6f)
            ) {
                Column(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = "Угол наклона",
                        style = MaterialTheme.typography.labelSmall,
                        color = Color.White.copy(alpha = 0.7f)
                    )
                    Text(
                        text = "%+.1f°".format(currentTiltDown),
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.Bold,
                        color = Color.White
                    )
                }
            }

            // Результаты
            ResultPanel(
                modifier = Modifier.align(Alignment.BottomCenter).padding(16.dp),
                angleBottom = angleBottom,
                angleTop = angleTop,
                distance = distance,
                height = height,
                eyeHeightCm = eyeHeightCm,
                onCaptureBottom = {
                    angleBottom = currentTiltDown
                },
                onCaptureTop = {
                    angleTop = currentTiltDown
                },
                onReset = {
                    angleBottom = null
                    angleTop = null
                },
                onSave = { showSaveDialog = true }
            )
        }
    }

    if (showSettingsDialog) {
        EyeHeightDialog(
            currentCm = eyeHeightCm,
            onDismiss = { showSettingsDialog = false },
            onConfirm = { newCm ->
                eyeHeightCm = newCm
                prefs.edit().putInt(KEY_EYE_HEIGHT_CM, newCm).apply()
                showSettingsDialog = false
            }
        )
    }

    if (showSaveDialog && distance != null) {
        SaveDialog(
            distance = distance,
            height = height,
            onDismiss = { showSaveDialog = false },
            onConfirm = { note ->
                val noteFull = buildString {
                    if (height != null) append("высота=${"%.2f".format(height)} м, ")
                    append("h_eye=${eyeHeightCm} см, ")
                    append("α_низ=${"%.1f".format(angleBottom!!)}°")
                    if (angleTop != null) append(", α_верх=${"%.1f".format(angleTop!!)}°")
                    if (note.isNotBlank()) append(" — $note")
                }
                scope.launch {
                    val markup = ru.measurekit.util.MarkupRenderer.renderRangefinderCard(
                        valueText = "${"%.2f".format(distance)} м",
                        angleBottom = angleBottom,
                        angleTop = angleTop,
                        height = height,
                        eyeHeightCm = eyeHeightCm
                    )
                    repo.save(
                        type = MeasurementType.RANGEFINDER,
                        valueRaw = distance.toDouble(),
                        unit = "м",
                        note = noteFull,
                        markupBitmap = markup
                    )
                    snackbarHost.showSnackbar("Сохранено: ${"%.2f".format(distance)} м")
                }
                showSaveDialog = false
            }
        )
    }
}

private fun currentIsValidAngle(tiltDown: Float): Boolean {
    // Валидный диапазон: 1°..70° наклон вниз (выше — слишком близко, ниже — точность ужасная)
    return tiltDown in 1f..70f
}

private fun computeDistance(angleBottom: Float?, eyeHeightCm: Int): Float? {
    if (angleBottom == null || angleBottom <= 0f) return null
    val angleRad = angleBottom * PI.toFloat() / 180f
    val tanA = tan(angleRad)
    if (tanA < 0.001f) return null
    val distanceCm = eyeHeightCm / tanA
    val distanceM = distanceCm / 100f
    if (distanceM > 1000f) return null  // нереалистично
    return distanceM
}

private fun computeHeight(angleBottom: Float?, angleTop: Float?, eyeHeightCm: Int): Float? {
    if (angleBottom == null || angleTop == null) return null
    val distance = computeDistance(angleBottom, eyeHeightCm) ?: return null
    val angleTopRad = angleTop * PI.toFloat() / 180f
    val eyeHeightM = eyeHeightCm / 100f
    // angleTop отрицательный когда смотрим ВВЕРХ (выше горизонта), положительный когда ВНИЗ.
    // Высота объекта = высота_глаз + расстояние * tan(угол_до_верха)
    // Но angleTop у нас "наклон вниз", поэтому если angleTop < 0 (смотрим вверх) —
    // tan отрицательный, и в формуле "+ d*tan(angleTop)" фактически добавляем
    // положительное значение к высоте глаз.
    // Используем: H = h_eye + d * tan(-angleTop) = h_eye - d * tan(angleTop)
    val heightM = eyeHeightM - distance * tan(angleTopRad)
    if (heightM < 0f || heightM > 200f) return null  // фильтр некорректных значений
    return heightM
}

@Composable
private fun CameraPreview(modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    AndroidView(
        modifier = modifier,
        factory = { context ->
            val previewView = PreviewView(context).apply {
                scaleType = PreviewView.ScaleType.FILL_CENTER
            }
            val cameraProviderFuture = ProcessCameraProvider.getInstance(context)
            cameraProviderFuture.addListener({
                try {
                    val cameraProvider = cameraProviderFuture.get()
                    val preview = Preview.Builder().build().also {
                        it.setSurfaceProvider(previewView.surfaceProvider)
                    }
                    cameraProvider.unbindAll()
                    cameraProvider.bindToLifecycle(
                        lifecycleOwner,
                        CameraSelector.DEFAULT_BACK_CAMERA,
                        preview
                    )
                } catch (e: Exception) {
                    // Камера не доступна, ничего не делаем
                }
            }, ContextCompat.getMainExecutor(context))
            previewView
        }
    )
}

@Composable
private fun Crosshair(modifier: Modifier = Modifier, color: Color) {
    Canvas(modifier = modifier) {
        val cx = size.width / 2f
        val cy = size.height / 2f
        val crossSize = 60f
        val gap = 12f
        val strokeW = 3f

        // Горизонтальные линии перекрестия (с разрывом по центру)
        drawLine(
            color = color,
            start = Offset(cx - crossSize, cy),
            end = Offset(cx - gap, cy),
            strokeWidth = strokeW
        )
        drawLine(
            color = color,
            start = Offset(cx + gap, cy),
            end = Offset(cx + crossSize, cy),
            strokeWidth = strokeW
        )
        // Вертикальные
        drawLine(
            color = color,
            start = Offset(cx, cy - crossSize),
            end = Offset(cx, cy - gap),
            strokeWidth = strokeW
        )
        drawLine(
            color = color,
            start = Offset(cx, cy + gap),
            end = Offset(cx, cy + crossSize),
            strokeWidth = strokeW
        )
        // Центральная точка
        drawCircle(
            color = color,
            radius = 4f,
            center = Offset(cx, cy)
        )
        // Ободок-рамка
        drawCircle(
            color = color.copy(alpha = 0.4f),
            radius = crossSize + 8f,
            center = Offset(cx, cy),
            style = androidx.compose.ui.graphics.drawscope.Stroke(width = 1.5f)
        )
    }
}

@Composable
private fun ResultPanel(
    modifier: Modifier,
    angleBottom: Float?,
    angleTop: Float?,
    distance: Float?,
    height: Float?,
    eyeHeightCm: Int,
    onCaptureBottom: () -> Unit,
    onCaptureTop: () -> Unit,
    onReset: () -> Unit,
    onSave: () -> Unit
) {
    Surface(
        modifier = modifier,
        shape = MaterialTheme.shapes.large,
        color = Color.Black.copy(alpha = 0.75f)
    ) {
        Column(
            modifier = Modifier.padding(16.dp).fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Если есть результат - показываем большие цифры
            if (distance != null) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly
                ) {
                    BigReadout(
                        icon = Icons.Filled.Straighten,
                        label = "Расстояние",
                        value = "%.2f м".format(distance)
                    )
                    if (height != null) {
                        BigReadout(
                            icon = Icons.Filled.Height,
                            label = "Высота",
                            value = "%.2f м".format(height)
                        )
                    }
                }
                Spacer(Modifier.height(12.dp))
            }

            // Подсказки в зависимости от текущего шага
            val hint = when {
                angleBottom == null -> "1. Наведите крест на ОСНОВАНИЕ объекта (землю под ним)"
                angleTop == null -> "2. Поднимите телефон, наведите крест на ВЕРХ объекта"
                else -> "Готово. Можно сохранить или измерить заново"
            }
            Text(
                text = hint,
                style = MaterialTheme.typography.bodySmall,
                color = Color.White.copy(alpha = 0.8f),
                modifier = Modifier.fillMaxWidth(),
                fontWeight = FontWeight.Medium
            )
            Spacer(Modifier.height(12.dp))

            // Кнопки действий
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (angleBottom == null) {
                    Button(
                        onClick = onCaptureBottom,
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(Icons.Filled.CameraAlt, contentDescription = null,
                            modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Захватить низ")
                    }
                } else if (angleTop == null) {
                    Button(
                        onClick = onCaptureTop,
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(Icons.Filled.CameraAlt, contentDescription = null,
                            modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Захватить верх")
                    }
                    OutlinedButton(
                        onClick = onReset,
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White)
                    ) {
                        Icon(Icons.Filled.Refresh, contentDescription = "Сбросить")
                    }
                } else {
                    Button(
                        onClick = onSave,
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(Icons.Filled.Bookmark, contentDescription = null,
                            modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Сохранить")
                    }
                    OutlinedButton(
                        onClick = onReset,
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White)
                    ) {
                        Icon(Icons.Filled.Refresh, contentDescription = "Заново")
                    }
                }
            }

            Spacer(Modifier.height(8.dp))
            Text(
                text = "Высота камеры (глаз): ${eyeHeightCm} см",
                style = MaterialTheme.typography.labelSmall,
                color = Color.White.copy(alpha = 0.5f)
            )
        }
    }
}

@Composable
private fun BigReadout(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    value: String
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                icon, contentDescription = null,
                tint = Color.White, modifier = Modifier.size(16.dp)
            )
            Spacer(Modifier.width(4.dp))
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = Color.White.copy(alpha = 0.7f)
            )
        }
        Text(
            text = value,
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
            color = Color.White
        )
    }
}

@Composable
private fun EyeHeightDialog(
    currentCm: Int,
    onDismiss: () -> Unit,
    onConfirm: (Int) -> Unit
) {
    var text by remember { mutableStateOf(currentCm.toString()) }
    var parsed by remember { mutableIntStateOf(currentCm) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Высота камеры") },
        text = {
            Column {
                Text(
                    text = "Расстояние от пола до глаз/камеры в см. " +
                            "Обычно это рост минус 10 см.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = text,
                    onValueChange = { newText ->
                        text = newText
                        newText.toIntOrNull()?.let { v ->
                            if (v in 50..220) parsed = v
                        }
                    },
                    label = { Text("Высота, см") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    text = "Пресеты:",
                    style = MaterialTheme.typography.labelMedium
                )
                Spacer(Modifier.height(4.dp))
                val presets = listOf(
                    "Глаз стоя 170 (рост 180)" to 170,
                    "Глаз стоя 160 (рост 170)" to 160,
                    "Глаз стоя 150 (рост 160)" to 150,
                    "Сидя 120" to 120,
                    "Штатив 130" to 130,
                    "У пола 10" to 10
                )
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    presets.chunked(2).forEach { row ->
                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            row.forEach { (label, cm) ->
                                FilterChip(
                                    selected = parsed == cm,
                                    onClick = {
                                        parsed = cm
                                        text = cm.toString()
                                    },
                                    label = {
                                        Text(label, style = MaterialTheme.typography.labelSmall)
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
            TextButton(onClick = { onConfirm(parsed) }) { Text("Применить") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Отмена") }
        }
    )
}

@Composable
private fun SaveDialog(
    distance: Float,
    height: Float?,
    onDismiss: () -> Unit,
    onConfirm: (note: String) -> Unit
) {
    var note by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Сохранить измерение") },
        text = {
            Column {
                Text(
                    text = "Расстояние: %.2f м".format(distance),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                if (height != null) {
                    Text(
                        text = "Высота объекта: %.2f м".format(height),
                        style = MaterialTheme.typography.titleMedium
                    )
                }
                Spacer(Modifier.height(16.dp))
                OutlinedTextField(
                    value = note,
                    onValueChange = { note = it },
                    label = { Text("Заметка (необязательно)") },
                    placeholder = { Text("Например: дерево во дворе") },
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
