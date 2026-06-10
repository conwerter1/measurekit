package ru.measurekit.ui.screen.level

import android.content.Context
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.VolumeOff
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import ru.measurekit.data.MeasurementType
import ru.measurekit.data.MeasurementsRepository
import ru.measurekit.domain.sensor.rememberOrientation
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * Электронный уровень.
 *
 * Семантика углов (после remap в OrientationProvider):
 *   pitch = наклон по короткой оси экрана. 0 когда экран горизонтален.
 *   roll  = наклон по длинной оси экрана. 0 когда экран не "завален".
 *
 * Режимы:
 *   - Круглый: для горизонтальных поверхностей. Ровно когда |pitch|<0.3 и |roll|<0.3.
 *   - Линейный поперёк: меряет одну ось — наклон поперёк экрана (roll).
 *   - Линейный вдоль: меряет другую ось — наклон вдоль экрана (pitch).
 *
 * Звук + вибрация при достижении уровня.
 */
private const val LEVEL_THRESHOLD_DEG = 0.3f

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LevelScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val repo = remember { MeasurementsRepository.get(ctx) }
    val scope = rememberCoroutineScope()
    val snackbarHost = remember { SnackbarHostState() }

    val orientation by rememberOrientation()

    var mode by rememberSaveable { mutableStateOf(LevelMode.BULLSEYE.name) }
    var feedbackEnabled by rememberSaveable { mutableStateOf(true) }
    var showSaveDialog by remember { mutableStateOf(false) }

    val activeMode = LevelMode.valueOf(mode)

    // Угол, по которому судим "ровно/не ровно" в каждом режиме
    val displayedAngle = when (activeMode) {
        LevelMode.BULLSEYE -> max(abs(orientation.pitch), abs(orientation.roll))
        LevelMode.BAR_ROLL -> abs(orientation.roll)
        LevelMode.BAR_PITCH -> abs(orientation.pitch)
    }
    val isLevel = displayedAngle < LEVEL_THRESHOLD_DEG

    LevelFeedback(
        isLevel = isLevel,
        enabled = feedbackEnabled && orientation.available
    )

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Уровень") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Назад")
                    }
                },
                actions = {
                    IconButton(onClick = { feedbackEnabled = !feedbackEnabled }) {
                        Icon(
                            if (feedbackEnabled) Icons.Filled.VolumeUp else Icons.Filled.VolumeOff,
                            contentDescription = if (feedbackEnabled) "Звук вкл" else "Звук выкл"
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
            ModeSelector(
                current = activeMode,
                onChange = { mode = it.name },
                modifier = Modifier.padding(8.dp)
            )

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
                    when (activeMode) {
                        LevelMode.BULLSEYE -> BullseyeLevel(
                            pitch = orientation.pitch,
                            roll = orientation.roll,
                            isLevel = isLevel
                        )
                        LevelMode.BAR_ROLL -> BarLevel(
                            angle = orientation.roll,
                            horizontal = true,
                            isLevel = isLevel
                        )
                        LevelMode.BAR_PITCH -> BarLevel(
                            angle = orientation.pitch,
                            horizontal = false,
                            isLevel = isLevel
                        )
                    }
                }
            }

            ReadoutCard(
                pitch = orientation.pitch,
                roll = orientation.roll,
                mode = activeMode,
                isLevel = isLevel,
                modifier = Modifier.padding(16.dp).fillMaxWidth()
            )
        }
    }

    if (showSaveDialog) {
        SaveLevelDialog(
            pitch = orientation.pitch,
            roll = orientation.roll,
            onDismiss = { showSaveDialog = false },
            onConfirm = { note ->
                val rawValue = max(abs(orientation.pitch), abs(orientation.roll)).toDouble()
                val noteFull = buildString {
                    append("pitch=${"%.1f".format(orientation.pitch)}°, ")
                    append("roll=${"%.1f".format(orientation.roll)}°")
                    if (note.isNotBlank()) append(" — $note")
                }
                scope.launch {
                    repo.save(
                        type = MeasurementType.LEVEL,
                        valueRaw = rawValue,
                        unit = "°",
                        note = noteFull
                    )
                    snackbarHost.showSnackbar("Сохранено: ${"%.1f".format(rawValue)}°")
                }
                showSaveDialog = false
            }
        )
    }
}

enum class LevelMode { BULLSEYE, BAR_ROLL, BAR_PITCH }

@Composable
private fun ModeSelector(
    current: LevelMode,
    onChange: (LevelMode) -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier,
        shape = MaterialTheme.shapes.small,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        tonalElevation = 2.dp
    ) {
        Row(modifier = Modifier.padding(2.dp)) {
            val items = listOf(
                LevelMode.BULLSEYE to "Круг",
                LevelMode.BAR_ROLL to "Поперёк",
                LevelMode.BAR_PITCH to "Вдоль"
            )
            items.forEach { (m, label) ->
                val selected = m == current
                TextButton(
                    onClick = { onChange(m) },
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

/**
 * Круглый пузырьковый уровень.
 * Пузырёк смещается от центра пропорционально углам pitch/roll.
 * Полное отклонение пузырька на радиус = 30° наклона.
 */
@Composable
private fun BullseyeLevel(pitch: Float, roll: Float, isLevel: Boolean) {
    val outline = MaterialTheme.colorScheme.outline
    val primary = MaterialTheme.colorScheme.primary
    val tertiary = MaterialTheme.colorScheme.tertiary
    val onSurface = MaterialTheme.colorScheme.onSurface
    val targetColor = if (isLevel) primary else tertiary

    Canvas(modifier = Modifier.fillMaxSize().padding(32.dp)) {
        val cx = size.width / 2f
        val cy = size.height / 2f
        val outerRadius = min(size.width, size.height) / 2f - 16f
        val targetRadius = outerRadius * 0.12f
        val bubbleRadius = outerRadius * 0.16f

        drawCircle(
            color = outline,
            radius = outerRadius,
            center = Offset(cx, cy),
            style = Stroke(width = 4f)
        )
        drawLine(
            color = outline.copy(alpha = 0.4f),
            start = Offset(cx - outerRadius, cy),
            end = Offset(cx + outerRadius, cy),
            strokeWidth = 1.5f
        )
        drawLine(
            color = outline.copy(alpha = 0.4f),
            start = Offset(cx, cy - outerRadius),
            end = Offset(cx, cy + outerRadius),
            strokeWidth = 1.5f
        )
        drawCircle(
            color = outline.copy(alpha = 0.3f),
            radius = outerRadius * 0.66f,
            center = Offset(cx, cy),
            style = Stroke(width = 1.5f)
        )
        drawCircle(
            color = outline.copy(alpha = 0.3f),
            radius = outerRadius * 0.33f,
            center = Offset(cx, cy),
            style = Stroke(width = 1.5f)
        )
        drawCircle(
            color = targetColor.copy(alpha = 0.3f),
            radius = targetRadius,
            center = Offset(cx, cy)
        )
        drawCircle(
            color = targetColor,
            radius = targetRadius,
            center = Offset(cx, cy),
            style = Stroke(width = 2f)
        )

        // Положение пузырька:
        // roll  → смещение по X (наклон вправо = пузырёк "стекает" вправо)
        // pitch → смещение по Y (наклон вперёд = пузырёк к верху экрана)
        val maxAngleRad = Math.toRadians(30.0).toFloat()
        val rollRad = Math.toRadians(roll.toDouble()).toFloat()
        val pitchRad = Math.toRadians(pitch.toDouble()).toFloat()
        val displaceX = (sin(rollRad.coerceIn(-maxAngleRad, maxAngleRad)) /
                         sin(maxAngleRad)) * (outerRadius - bubbleRadius)
        val displaceY = -(sin(pitchRad.coerceIn(-maxAngleRad, maxAngleRad)) /
                          sin(maxAngleRad)) * (outerRadius - bubbleRadius)

        val total = hypot(displaceX, displaceY)
        val maxDisplace = outerRadius - bubbleRadius
        val (clampedX, clampedY) = if (total > maxDisplace) {
            Pair(displaceX / total * maxDisplace, displaceY / total * maxDisplace)
        } else {
            Pair(displaceX, displaceY)
        }

        val bubbleColor = if (isLevel) primary else onSurface
        drawCircle(
            color = bubbleColor.copy(alpha = 0.2f),
            radius = bubbleRadius * 1.3f,
            center = Offset(cx + clampedX, cy + clampedY)
        )
        drawCircle(
            color = bubbleColor,
            radius = bubbleRadius,
            center = Offset(cx + clampedX, cy + clampedY)
        )
    }
}

/**
 * Линейный пузырьковый уровень.
 * @param horizontal — true: трубка идёт горизонтально, пузырёк бегает влево/вправо.
 *                    false: трубка вертикально, пузырёк вверх/вниз.
 */
@Composable
private fun BarLevel(angle: Float, horizontal: Boolean, isLevel: Boolean) {
    val outline = MaterialTheme.colorScheme.outline
    val primary = MaterialTheme.colorScheme.primary
    val tertiary = MaterialTheme.colorScheme.tertiary
    val onSurface = MaterialTheme.colorScheme.onSurface
    val targetColor = if (isLevel) primary else tertiary

    Canvas(modifier = Modifier.fillMaxSize().padding(32.dp)) {
        val cx = size.width / 2f
        val cy = size.height / 2f

        val barLength: Float
        val barThickness: Float
        if (horizontal) {
            barLength = size.width - 64f
            barThickness = min(size.height * 0.4f, 200f)
        } else {
            barThickness = min(size.width * 0.4f, 200f)
            barLength = size.height - 64f
        }

        val bubbleRadius = barThickness * 0.35f
        val targetWidth = barThickness * 1.1f

        if (horizontal) {
            drawRect(
                color = outline,
                topLeft = Offset(cx - barLength / 2f, cy - barThickness / 2f),
                size = Size(barLength, barThickness),
                style = Stroke(width = 4f)
            )
            drawRect(
                color = targetColor.copy(alpha = 0.25f),
                topLeft = Offset(cx - barThickness * 0.8f, cy - targetWidth / 2f),
                size = Size(barThickness * 1.6f, targetWidth)
            )
            drawLine(
                color = targetColor,
                start = Offset(cx - barThickness * 0.8f, cy - targetWidth / 2f),
                end = Offset(cx - barThickness * 0.8f, cy + targetWidth / 2f),
                strokeWidth = 2f
            )
            drawLine(
                color = targetColor,
                start = Offset(cx + barThickness * 0.8f, cy - targetWidth / 2f),
                end = Offset(cx + barThickness * 0.8f, cy + targetWidth / 2f),
                strokeWidth = 2f
            )
        } else {
            drawRect(
                color = outline,
                topLeft = Offset(cx - barThickness / 2f, cy - barLength / 2f),
                size = Size(barThickness, barLength),
                style = Stroke(width = 4f)
            )
            drawRect(
                color = targetColor.copy(alpha = 0.25f),
                topLeft = Offset(cx - targetWidth / 2f, cy - barThickness * 0.8f),
                size = Size(targetWidth, barThickness * 1.6f)
            )
            drawLine(
                color = targetColor,
                start = Offset(cx - targetWidth / 2f, cy - barThickness * 0.8f),
                end = Offset(cx + targetWidth / 2f, cy - barThickness * 0.8f),
                strokeWidth = 2f
            )
            drawLine(
                color = targetColor,
                start = Offset(cx - targetWidth / 2f, cy + barThickness * 0.8f),
                end = Offset(cx + targetWidth / 2f, cy + barThickness * 0.8f),
                strokeWidth = 2f
            )
        }

        val maxAngleRad = Math.toRadians(30.0).toFloat()
        val angleRad = Math.toRadians(angle.toDouble()).toFloat()
        val maxDisplace = barLength / 2f - bubbleRadius - 8f
        val displace = (sin(angleRad.coerceIn(-maxAngleRad, maxAngleRad)) /
                        sin(maxAngleRad)) * maxDisplace

        val bubbleColor = if (isLevel) primary else onSurface
        val (bx, by) = if (horizontal) {
            Pair(cx + displace, cy)
        } else {
            // Для вертикальной трубки: положительный pitch = верх задирается
            // → пузырёк "уходит" вверх (к нулю по экрану)
            Pair(cx, cy - displace)
        }
        drawCircle(
            color = bubbleColor.copy(alpha = 0.2f),
            radius = bubbleRadius * 1.3f,
            center = Offset(bx, by)
        )
        drawCircle(
            color = bubbleColor,
            radius = bubbleRadius,
            center = Offset(bx, by)
        )
    }
}

@Composable
private fun ReadoutCard(
    pitch: Float,
    roll: Float,
    mode: LevelMode,
    isLevel: Boolean,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(
            containerColor = if (isLevel) MaterialTheme.colorScheme.primaryContainer
                             else MaterialTheme.colorScheme.surfaceContainerHigh
        )
    ) {
        Column(
            modifier = Modifier.padding(16.dp).fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            if (isLevel) {
                Text(
                    text = "✓ ровно",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )
                Spacer(Modifier.height(4.dp))
            }
            when (mode) {
                LevelMode.BULLSEYE -> {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceEvenly
                    ) {
                        AxisReadout(label = "Pitch", value = pitch)
                        AxisReadout(label = "Roll", value = roll)
                    }
                }
                LevelMode.BAR_ROLL -> {
                    AxisReadout(label = "Roll (поперёк)", value = roll)
                }
                LevelMode.BAR_PITCH -> {
                    AxisReadout(label = "Pitch (вдоль)", value = pitch)
                }
            }
        }
    }
}

@Composable
private fun AxisReadout(label: String, value: Float) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = "%+.1f°".format(value),
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold
        )
    }
}

@Composable
private fun LevelFeedback(isLevel: Boolean, enabled: Boolean) {
    val ctx = LocalContext.current
    var wasLevel by remember { mutableStateOf(false) }

    LaunchedEffect(isLevel, enabled) {
        if (!enabled) {
            wasLevel = isLevel
            return@LaunchedEffect
        }
        if (isLevel && !wasLevel) {
            playLevelTone()
            vibrate(ctx)
        }
        wasLevel = isLevel
    }
}

private fun playLevelTone() {
    try {
        val tone = ToneGenerator(AudioManager.STREAM_NOTIFICATION, 80)
        tone.startTone(ToneGenerator.TONE_PROP_BEEP, 150)
    } catch (e: Exception) { }
}

private fun vibrate(ctx: Context) {
    try {
        val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val vm = ctx.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
            vm.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            ctx.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            vibrator.vibrate(VibrationEffect.createOneShot(80, VibrationEffect.DEFAULT_AMPLITUDE))
        } else {
            @Suppress("DEPRECATION")
            vibrator.vibrate(80)
        }
    } catch (e: Exception) { }
}

@Composable
private fun SaveLevelDialog(
    pitch: Float,
    roll: Float,
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
                    text = "Pitch: ${"%+.1f".format(pitch)}°",
                    style = MaterialTheme.typography.titleMedium
                )
                Text(
                    text = "Roll: ${"%+.1f".format(roll)}°",
                    style = MaterialTheme.typography.titleMedium
                )
                Spacer(Modifier.height(16.dp))
                OutlinedTextField(
                    value = note,
                    onValueChange = { note = it },
                    label = { Text("Заметка (необязательно)") },
                    placeholder = { Text("Например: полка кухни") },
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
