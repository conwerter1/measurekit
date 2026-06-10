package ru.measurekit.ui.screen.area

import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.CenterFocusStrong
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowLeft
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Undo
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import ru.measurekit.data.MeasurementType
import ru.measurekit.data.MeasurementsRepository
import java.io.File
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Площадь по фото с эталоном. Поддерживает два режима:
 *
 * РЕЖИМ ПРОСТОЙ (быстрый):
 *   - Эталон = одна сторона (отрезок A1-A2 известной длины)
 *   - Полигон V1...Vn по контуру объекта
 *   - Площадь по shoelace, масштаб через pixelsPerMm = refPx / refMm
 *   - Работает корректно только когда фото снято строго перпендикулярно поверхности
 *     (иначе искажения перспективы дают грубые ошибки в площади)
 *
 * РЕЖИМ ТОЧНЫЙ (гомография, 4 точки):
 *   - Эталон = четыре угла прямоугольника известных размеров (кредитка 86×54, A4 297×210...)
 *   - 4 точки на фото C1, C2, C3, C4 → 4 известные координаты в мм
 *   - Решается матрица гомографии 3×3 (DLT, 8 уравнений)
 *   - Все вершины полигона V1..Vn трансформируются в реальные координаты в мм
 *   - Площадь считается в реальной плоскости - без перспективных искажений
 *   - Работает даже на фото снятых под углом
 *
 * Улучшения точности:
 *   - Лупа остаётся видимой 3 секунды после отпускания пальца (можно проверить позицию)
 *   - Стрелочная подстройка последней потроганной точки на 1px за тап
 *   - Текущий pixelsPerMm в нижней панели для контроля масштаба
 */

private const val MAX_VERTICES = 30
private const val POINT_HIT_RADIUS = 150f
private const val MAGNIFIER_LINGER_MS = 3000L

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AreaScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val repo = remember { MeasurementsRepository.get(ctx) }
    val scope = rememberCoroutineScope()
    val snackbarHost = remember { SnackbarHostState() }

    var imageBitmap by remember { mutableStateOf<ImageBitmap?>(null) }
    var loadError by remember { mutableStateOf<String?>(null) }
    var measureMode by remember { mutableStateOf<MeasureMode?>(null) }

    val galleryLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let {
            scope.launch {
                val bm = loadBitmap(ctx, it)
                if (bm != null) {
                    imageBitmap = bm.asImageBitmap()
                    loadError = null
                } else {
                    loadError = "Не удалось загрузить изображение"
                }
            }
        }
    }

    var pendingCameraUri by remember { mutableStateOf<Uri?>(null) }
    val cameraLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.TakePicture()
    ) { success ->
        val uri = pendingCameraUri
        if (success && uri != null) {
            scope.launch {
                val bm = loadBitmap(ctx, uri)
                if (bm != null) {
                    imageBitmap = bm.asImageBitmap()
                    loadError = null
                } else {
                    loadError = "Не удалось загрузить снимок"
                }
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Площадь") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Назад")
                    }
                },
                actions = {
                    if (imageBitmap != null) {
                        IconButton(onClick = {
                            imageBitmap = null
                            measureMode = null
                        }) {
                            Icon(Icons.Filled.Refresh, contentDescription = "Новое фото")
                        }
                    }
                }
            )
        },
        snackbarHost = { SnackbarHost(snackbarHost) }
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
            val current = imageBitmap
            val mode = measureMode
            when {
                current == null -> SourcePicker(
                    onPickGallery = { galleryLauncher.launch("image/*") },
                    onPickCamera = {
                        val uri = createTempImageUri(ctx)
                        pendingCameraUri = uri
                        cameraLauncher.launch(uri)
                    },
                    error = loadError
                )
                mode == null -> ModePicker(
                    onPick = { measureMode = it },
                    onBack = { imageBitmap = null }
                )
                mode == MeasureMode.SIMPLE -> SimpleMeasureView(
                    image = current,
                    onSave = { areaMm2, refLabel, refMm, vertexCount, note, markup, original ->
                        scope.launch {
                            val noteFull = buildString {
                                append("режим=простой, вершин=$vertexCount, ")
                                append("эталон=$refLabel ${"%.1f".format(refMm)} мм")
                                if (note.isNotBlank()) append(" — $note")
                            }
                            repo.save(
                                type = MeasurementType.AREA,
                                valueRaw = areaMm2,
                                unit = "мм²",
                                note = noteFull,
                                markupBitmap = markup,
                                originalBitmap = original
                            )
                            snackbarHost.showSnackbar("Сохранено: ${formatAreaShort(areaMm2)}")
                        }
                    }
                )
                mode == MeasureMode.HOMOGRAPHY -> HomographyMeasureView(
                    image = current,
                    onSave = { areaMm2, refLabel, refW, refH, vertexCount, note, markup, original ->
                        scope.launch {
                            val noteFull = buildString {
                                append("режим=точный (4т), вершин=$vertexCount, ")
                                append("эталон=$refLabel ${"%.0f".format(refW)}×${"%.0f".format(refH)} мм")
                                if (note.isNotBlank()) append(" — $note")
                            }
                            repo.save(
                                type = MeasurementType.AREA,
                                valueRaw = areaMm2,
                                unit = "мм²",
                                note = noteFull,
                                markupBitmap = markup,
                                originalBitmap = original
                            )
                            snackbarHost.showSnackbar("Сохранено: ${formatAreaShort(areaMm2)}")
                        }
                    }
                )
            }
        }
    }
}

private enum class MeasureMode { SIMPLE, HOMOGRAPHY }

/* ---------- Загрузка изображений ---------- */

private suspend fun loadBitmap(ctx: Context, uri: Uri): android.graphics.Bitmap? {
    return withContext(Dispatchers.IO) {
        try {
            val metaOpts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            ctx.contentResolver.openInputStream(uri)?.use { stream ->
                BitmapFactory.decodeStream(stream, null, metaOpts)
            }
            val maxDim = max(metaOpts.outWidth, metaOpts.outHeight)
            var sampleSize = 1
            while (maxDim / sampleSize > 2400) sampleSize *= 2
            val opts = BitmapFactory.Options().apply { inSampleSize = sampleSize }
            ctx.contentResolver.openInputStream(uri)?.use { stream ->
                BitmapFactory.decodeStream(stream, null, opts)
            }
        } catch (e: Exception) {
            null
        }
    }
}

private fun createTempImageUri(ctx: Context): Uri {
    val cacheDir = File(ctx.cacheDir, "camera").apply { mkdirs() }
    val file = File.createTempFile("photo_", ".jpg", cacheDir)
    return FileProvider.getUriForFile(ctx, "${ctx.packageName}.fileprovider", file)
}

/* ---------- Стартовый экран выбора источника ---------- */

@Composable
private fun SourcePicker(
    onPickGallery: () -> Unit,
    onPickCamera: () -> Unit,
    error: String?
) {
    Column(
        modifier = Modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = "Выберите источник фото",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = "На фото должны быть эталон (кредитка/монета/линейка/A4) и измеряемая фигура. " +
                    "Эталон в той же плоскости, что и фигура.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(32.dp))
        Button(
            onClick = onPickCamera,
            modifier = Modifier.fillMaxWidth().height(56.dp)
        ) {
            Icon(Icons.Filled.CameraAlt, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text("Снять фото", style = MaterialTheme.typography.titleMedium)
        }
        Spacer(Modifier.height(12.dp))
        OutlinedButton(
            onClick = onPickGallery,
            modifier = Modifier.fillMaxWidth().height(56.dp)
        ) {
            Icon(Icons.Filled.PhotoLibrary, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text("Из галереи", style = MaterialTheme.typography.titleMedium)
        }
        if (error != null) {
            Spacer(Modifier.height(16.dp))
            Text(
                text = error,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error
            )
        }
    }
}

/* ---------- Выбор режима измерения ---------- */

@Composable
private fun ModePicker(onPick: (MeasureMode) -> Unit, onBack: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = "Режим измерения",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold
        )
        Spacer(Modifier.height(24.dp))

        ModeCard(
            title = "Простой",
            description = "Эталон = одна сторона (2 точки). Быстро. " +
                    "Точность ±5–15% и зависит от перспективы. " +
                    "Снимать строго перпендикулярно.",
            icon = Icons.Filled.Tune,
            onClick = { onPick(MeasureMode.SIMPLE) }
        )
        Spacer(Modifier.height(12.dp))
        ModeCard(
            title = "Точный (4 точки)",
            description = "Эталон = прямоугольник (4 точки): кредитка, A4, линейка. " +
                    "Корректирует перспективу через гомографию. Точность ±2–5% даже " +
                    "на фото под углом.",
            icon = Icons.Filled.CenterFocusStrong,
            onClick = { onPick(MeasureMode.HOMOGRAPHY) }
        )

        Spacer(Modifier.height(24.dp))
        TextButton(onClick = onBack) {
            Text("← Выбрать другое фото")
        }
    }
}

@Composable
private fun ModeCard(
    title: String,
    description: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    onClick: () -> Unit
) {
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                icon, contentDescription = null,
                modifier = Modifier.size(32.dp),
                tint = MaterialTheme.colorScheme.primary
            )
            Spacer(Modifier.width(16.dp))
            Column {
                Text(title, style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold)
                Text(description, style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

/* ---------- Эталоны для простого режима (1D) ---------- */

private data class LinearReference(val label: String, val mm: Double)

private val LINEAR_REFERENCES = listOf(
    LinearReference("Кредитка длинная", 85.6),
    LinearReference("Кредитка короткая", 53.98),
    LinearReference("Монета 10₽", 22.0),
    LinearReference("Монета 5₽", 25.0),
    LinearReference("Монета 2₽", 23.0),
    LinearReference("Монета 1₽", 20.5),
    LinearReference("Спич. коробок", 50.0),
    LinearReference("A4 длинная", 297.0),
    LinearReference("A4 короткая", 210.0),
    LinearReference("Линейка 10 см", 100.0),
    LinearReference("Линейка 30 см", 300.0),
    LinearReference("Свой размер", 0.0)
)

/* ---------- Эталоны для точного режима (2D, прямоугольник) ---------- */

private data class RectReference(val label: String, val widthMm: Double, val heightMm: Double)

private val RECT_REFERENCES = listOf(
    RectReference("Кредитка", 85.6, 53.98),
    RectReference("A4 лист", 297.0, 210.0),
    RectReference("A5 лист", 210.0, 148.0),
    RectReference("Спич. коробок", 50.0, 35.0),
    RectReference("Игральная карта", 88.0, 63.0),
    RectReference("Свой размер", 0.0, 0.0)
)

/* ---------- Общие типы ---------- */

private enum class HitKind { REF_A, REF_B, REF_C1, REF_C2, REF_C3, REF_C4, VERTEX }
private data class Hit(val kind: HitKind, val vertexIndex: Int = -1)

private enum class AreaUnit(val label: String, val mm2PerUnit: Double) {
    MM2("мм²", 1.0),
    CM2("см²", 100.0),
    M2("м²", 1_000_000.0);

    fun fromMm2(mm2: Double): Double = mm2 / mm2PerUnit
    fun format(mm2: Double): String {
        val v = fromMm2(mm2)
        return when (this) {
            MM2 -> "%.0f $label".format(v)
            CM2 -> "%.2f $label".format(v)
            M2 -> "%.4f $label".format(v)
        }
    }
}

/* ===========================================================================
 *                      ПРОСТОЙ РЕЖИМ (отрезок-эталон)
 * =========================================================================== */

@Composable
private fun SimpleMeasureView(
    image: ImageBitmap,
    onSave: (areaMm2: Double, refLabel: String, refMm: Double, vertexCount: Int, note: String, markup: android.graphics.Bitmap, original: android.graphics.Bitmap) -> Unit
) {
    var canvasSize by remember { mutableStateOf(Size.Zero) }

    var refA by remember { mutableStateOf(Offset(0.2f, 0.15f)) }
    var refB by remember { mutableStateOf(Offset(0.5f, 0.15f)) }
    val vertices = remember { mutableStateListOf<Offset>() }

    var selectedReference by remember { mutableStateOf(LINEAR_REFERENCES[0]) }
    var customMmText by remember { mutableStateOf("") }
    var areaUnit by rememberSaveable { mutableStateOf(AreaUnit.CM2.name) }
    var showSaveDialog by remember { mutableStateOf(false) }

    // Состояние лупы и подстройки
    var draggingPos by remember { mutableStateOf<Offset?>(null) }
    var lingerPos by remember { mutableStateOf<Offset?>(null) }
    var lastTouchedHit by remember { mutableStateOf<Hit?>(null) }

    // Лупа исчезает через MAGNIFIER_LINGER_MS после отпускания
    LaunchedEffect(lingerPos) {
        if (lingerPos != null && draggingPos == null) {
            delay(MAGNIFIER_LINGER_MS)
            if (draggingPos == null) lingerPos = null
        }
    }

    val activeUnit = AreaUnit.valueOf(areaUnit)
    val effectiveRefMm = if (selectedReference.label == "Свой размер") {
        customMmText.replace(',', '.').toDoubleOrNull() ?: 0.0
    } else {
        selectedReference.mm
    }

    val pixelsPerMm: Double? = remember(refA, refB, effectiveRefMm, canvasSize) {
        if (canvasSize == Size.Zero || effectiveRefMm <= 0.0) return@remember null
        val refPx = distance(relToCanvas(refA, canvasSize), relToCanvas(refB, canvasSize))
        if (refPx < 5.0) return@remember null
        refPx / effectiveRefMm
    }

    val areaMm2: Double? = remember(vertices.toList(), pixelsPerMm, canvasSize) {
        if (vertices.size < 3 || pixelsPerMm == null || canvasSize == Size.Zero) return@remember null
        val areaPx2 = polygonAreaPx(vertices.map { relToCanvas(it, canvasSize) })
        if (areaPx2 < 1.0) return@remember null
        areaPx2 / (pixelsPerMm * pixelsPerMm)
    }

    fun nudgePoint(dx: Float, dy: Float) {
        val hit = lastTouchedHit ?: return
        if (canvasSize == Size.Zero) return
        val dxRel = dx / canvasSize.width
        val dyRel = dy / canvasSize.height
        when (hit.kind) {
            HitKind.REF_A -> refA = Offset(
                (refA.x + dxRel).coerceIn(0f, 1f),
                (refA.y + dyRel).coerceIn(0f, 1f)
            )
            HitKind.REF_B -> refB = Offset(
                (refB.x + dxRel).coerceIn(0f, 1f),
                (refB.y + dyRel).coerceIn(0f, 1f)
            )
            HitKind.VERTEX -> {
                val idx = hit.vertexIndex
                if (idx in vertices.indices) {
                    val cur = vertices[idx]
                    vertices[idx] = Offset(
                        (cur.x + dxRel).coerceIn(0f, 1f),
                        (cur.y + dyRel).coerceIn(0f, 1f)
                    )
                }
            }
            else -> Unit
        }
        // Обновляем lingerPos чтобы лупа отображала новую позицию
        val newPosCanvas = when (hit.kind) {
            HitKind.REF_A -> relToCanvas(refA, canvasSize)
            HitKind.REF_B -> relToCanvas(refB, canvasSize)
            HitKind.VERTEX -> {
                val idx = hit.vertexIndex
                if (idx in vertices.indices) relToCanvas(vertices[idx], canvasSize) else null
            }
            else -> null
        }
        if (newPosCanvas != null) lingerPos = newPosCanvas
    }

    Column(modifier = Modifier.fillMaxSize()) {
        Box(modifier = Modifier.weight(1f).fillMaxWidth().background(Color.Black)) {
            SimpleAreaCanvas(
                image = image,
                refA = refA, refB = refB,
                vertices = vertices,
                onAddVertex = { canvasPos ->
                    if (vertices.size < MAX_VERTICES) {
                        vertices.add(canvasToRel(canvasPos, canvasSize))
                        lastTouchedHit = Hit(HitKind.VERTEX, vertices.size - 1)
                    }
                },
                onPointDrag = { hit, newPos ->
                    val rel = canvasToRel(newPos, canvasSize)
                    when (hit.kind) {
                        HitKind.REF_A -> refA = rel
                        HitKind.REF_B -> refB = rel
                        HitKind.VERTEX -> {
                            if (hit.vertexIndex in vertices.indices) vertices[hit.vertexIndex] = rel
                        }
                        else -> Unit
                    }
                    draggingPos = newPos
                    lingerPos = newPos
                    lastTouchedHit = hit
                },
                onDragEnd = { draggingPos = null },
                onLongPressVertex = { idx ->
                    if (idx in vertices.indices) vertices.removeAt(idx)
                },
                onCanvasSizeChange = { canvasSize = it }
            )

            // Лупа: показываем при перетаскивании ИЛИ в течение 3 сек после отпускания
            val mag = lingerPos
            if (mag != null && canvasSize != Size.Zero) {
                val onRight = mag.x < canvasSize.width / 2f
                val alignment = if (onRight) Alignment.TopEnd else Alignment.TopStart
                Column(
                    modifier = Modifier.align(alignment).padding(8.dp),
                    horizontalAlignment = if (onRight) Alignment.End else Alignment.Start
                ) {
                    Box(modifier = Modifier.size(140.dp)) {
                        Magnifier(image = image, canvasSize = canvasSize, focus = mag)
                    }
                    // Стрелочная подстройка: показываем когда не активный drag
                    if (draggingPos == null && lastTouchedHit != null) {
                        Spacer(Modifier.height(6.dp))
                        NudgePad(onNudge = { dx, dy -> nudgePoint(dx, dy) })
                    }
                }
            }

            if (vertices.isEmpty()) {
                Surface(
                    modifier = Modifier.align(Alignment.BottomCenter).padding(16.dp),
                    color = Color.Black.copy(alpha = 0.7f),
                    shape = MaterialTheme.shapes.medium
                ) {
                    Text(
                        text = "Тапайте по границам фигуры — добавятся вершины (минимум 3)",
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                        style = MaterialTheme.typography.bodySmall,
                        color = Color.White
                    )
                }
            }
        }

        SimpleBottomPanel(
            areaMm2 = areaMm2,
            unit = activeUnit,
            vertexCount = vertices.size,
            pixelsPerMm = pixelsPerMm,
            references = LINEAR_REFERENCES,
            selectedReference = selectedReference,
            onReferenceChange = { selectedReference = it },
            customMmText = customMmText,
            onCustomMmChange = { customMmText = it },
            onUnitChange = { areaUnit = it.name },
            onSave = { showSaveDialog = true },
            onUndo = {
                if (vertices.isNotEmpty()) {
                    vertices.removeAt(vertices.size - 1)
                    if (lastTouchedHit?.kind == HitKind.VERTEX &&
                        lastTouchedHit!!.vertexIndex >= vertices.size) {
                        lastTouchedHit = null
                    }
                }
            },
            onClearVertices = {
                vertices.clear()
                if (lastTouchedHit?.kind == HitKind.VERTEX) lastTouchedHit = null
            }
        )
    }

    if (showSaveDialog && areaMm2 != null) {
        SaveDialogSimple(
            areaMm2 = areaMm2, unit = activeUnit, vertexCount = vertices.size,
            referenceLabel = selectedReference.label, referenceMm = effectiveRefMm,
            onDismiss = { showSaveDialog = false },
            onConfirm = { note ->
                val markup = ru.measurekit.util.MarkupRenderer.renderAreaSimpleMarkup(
                    sourceImage = image,
                    refA = refA, refB = refB,
                    vertices = vertices.toList(),
                    valueText = activeUnit.format(areaMm2),
                    referenceText = "эталон: ${selectedReference.label} (${"%.1f".format(effectiveRefMm)} мм), вершин: ${vertices.size}"
                )
                val original = ru.measurekit.util.MarkupRenderer.imageBitmapToBitmap(image)
                onSave(areaMm2, selectedReference.label, effectiveRefMm, vertices.size, note, markup, original)
                showSaveDialog = false
            }
        )
    }
}

/* ---------- Канвас простого режима ---------- */

@Composable
private fun SimpleAreaCanvas(
    image: ImageBitmap,
    refA: Offset, refB: Offset,
    vertices: List<Offset>,
    onAddVertex: (Offset) -> Unit,
    onPointDrag: (Hit, Offset) -> Unit,
    onDragEnd: () -> Unit,
    onLongPressVertex: (Int) -> Unit,
    onCanvasSizeChange: (Size) -> Unit
) {
    val refColor = MaterialTheme.colorScheme.tertiary
    val polyColor = MaterialTheme.colorScheme.primary
    val polyFill = polyColor.copy(alpha = 0.18f)
    val textMeasurer = rememberTextMeasurer()
    val labelStyle = TextStyle(fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Color.White)

    val currentRefA by rememberUpdatedState(refA)
    val currentRefB by rememberUpdatedState(refB)
    val currentVertices by rememberUpdatedState(vertices)

    Canvas(
        modifier = Modifier.fillMaxSize()
            .pointerInput(Unit) {
                var draggingHit: Hit? = null
                detectDragGestures(
                    onDragStart = { offset ->
                        draggingHit = findSimpleHit(offset, currentRefA, currentRefB, currentVertices, this.size.toSize())
                    },
                    onDrag = { change, _ ->
                        draggingHit?.let { onPointDrag(it, change.position) }
                    },
                    onDragEnd = { draggingHit = null; onDragEnd() },
                    onDragCancel = { draggingHit = null; onDragEnd() }
                )
            }
            .pointerInput(Unit) {
                detectTapGestures(
                    onLongPress = { offset ->
                        val hit = findSimpleHit(offset, currentRefA, currentRefB, currentVertices, this.size.toSize())
                        if (hit?.kind == HitKind.VERTEX) onLongPressVertex(hit.vertexIndex)
                    },
                    onTap = { offset ->
                        val hit = findSimpleHit(offset, currentRefA, currentRefB, currentVertices, this.size.toSize())
                        if (hit == null) onAddVertex(offset)
                    }
                )
            }
    ) {
        onCanvasSizeChange(size)
        drawFitImage(image)

        val refAp = relToCanvas(refA, size)
        val refBp = relToCanvas(refB, size)
        drawLine(
            color = refColor, start = refAp, end = refBp, strokeWidth = 4f,
            pathEffect = PathEffect.dashPathEffect(floatArrayOf(15f, 10f), 0f)
        )
        drawPointMarker(refAp, refColor, "A1", textMeasurer, labelStyle)
        drawPointMarker(refBp, refColor, "A2", textMeasurer, labelStyle)

        if (vertices.isNotEmpty()) {
            val canvasVertices = vertices.map { relToCanvas(it, size) }
            if (canvasVertices.size >= 3) {
                val path = Path().apply {
                    moveTo(canvasVertices[0].x, canvasVertices[0].y)
                    for (i in 1 until canvasVertices.size) lineTo(canvasVertices[i].x, canvasVertices[i].y)
                    close()
                }
                drawPath(path, polyFill)
                drawPath(path, polyColor, style = Stroke(width = 4f))
            } else if (canvasVertices.size == 2) {
                drawLine(polyColor, canvasVertices[0], canvasVertices[1], strokeWidth = 4f)
            }
            canvasVertices.forEachIndexed { idx, pt ->
                drawPointMarker(pt, polyColor, "V${idx + 1}", textMeasurer, labelStyle)
            }
        }
    }
}

private fun findSimpleHit(
    offset: Offset, refA: Offset, refB: Offset,
    vertices: List<Offset>, canvasSize: Size
): Hit? {
    val candidates = mutableListOf<Pair<Hit, Offset>>()
    candidates.add(Hit(HitKind.REF_A) to relToCanvas(refA, canvasSize))
    candidates.add(Hit(HitKind.REF_B) to relToCanvas(refB, canvasSize))
    vertices.forEachIndexed { idx, v ->
        candidates.add(Hit(HitKind.VERTEX, idx) to relToCanvas(v, canvasSize))
    }
    val closest = candidates.minByOrNull { distance(it.second, offset) } ?: return null
    return if (distance(closest.second, offset) < POINT_HIT_RADIUS) closest.first else null
}

@Composable
private fun SimpleBottomPanel(
    areaMm2: Double?, unit: AreaUnit, vertexCount: Int, pixelsPerMm: Double?,
    references: List<LinearReference>,
    selectedReference: LinearReference,
    onReferenceChange: (LinearReference) -> Unit,
    customMmText: String,
    onCustomMmChange: (String) -> Unit,
    onUnitChange: (AreaUnit) -> Unit,
    onSave: () -> Unit, onUndo: () -> Unit, onClearVertices: () -> Unit
) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh, tonalElevation = 4.dp) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    val scale = if (pixelsPerMm != null) "  •  %.2f px/мм".format(pixelsPerMm) else ""
                    Text(
                        text = "Площадь  (вершин: $vertexCount$scale)",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    if (areaMm2 != null) {
                        Text(unit.format(areaMm2),
                            style = MaterialTheme.typography.headlineMedium,
                            fontWeight = FontWeight.Bold)
                    } else {
                        Text("—", style = MaterialTheme.typography.headlineMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                Row {
                    AreaUnit.entries.forEach { u ->
                        val sel = u == unit
                        TextButton(
                            onClick = { onUnitChange(u) },
                            colors = ButtonDefaults.textButtonColors(
                                contentColor = if (sel) MaterialTheme.colorScheme.primary
                                               else MaterialTheme.colorScheme.onSurfaceVariant
                            ),
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)
                        ) {
                            Text(u.label, style = MaterialTheme.typography.labelMedium,
                                fontWeight = if (sel) FontWeight.Bold else FontWeight.Normal)
                        }
                    }
                }
            }

            Spacer(Modifier.height(8.dp))
            Text("Эталон:", style = MaterialTheme.typography.labelMedium)
            Spacer(Modifier.height(4.dp))
            LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                items(references.size) { idx ->
                    val r = references[idx]
                    val sel = r.label == selectedReference.label
                    FilterChip(
                        selected = sel, onClick = { onReferenceChange(r) },
                        label = {
                            Text(
                                if (r.label == "Свой размер") r.label
                                else "${r.label} ${"%.0f".format(r.mm)}мм",
                                style = MaterialTheme.typography.labelSmall
                            )
                        }
                    )
                }
            }
            if (selectedReference.label == "Свой размер") {
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = customMmText, onValueChange = onCustomMmChange,
                    label = { Text("Размер эталона, мм") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.fillMaxWidth()
                )
            }
            Spacer(Modifier.height(12.dp))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onUndo, enabled = vertexCount > 0) {
                    Icon(Icons.Filled.Undo, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Назад")
                }
                OutlinedButton(onClick = onClearVertices, enabled = vertexCount > 0) {
                    Icon(Icons.Filled.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                }
                Button(onClick = onSave, enabled = areaMm2 != null,
                    modifier = Modifier.weight(1f)) {
                    Icon(Icons.Filled.Bookmark, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Сохранить")
                }
            }
        }
    }
}

/* ===========================================================================
 *               ТОЧНЫЙ РЕЖИМ (4 точки эталона + гомография)
 * =========================================================================== */

@Composable
private fun HomographyMeasureView(
    image: ImageBitmap,
    onSave: (areaMm2: Double, refLabel: String, refW: Double, refH: Double, vertexCount: Int, note: String, markup: android.graphics.Bitmap, original: android.graphics.Bitmap) -> Unit
) {
    var canvasSize by remember { mutableStateOf(Size.Zero) }

    // 4 точки эталона: C1 - левый верхний, C2 - правый верхний,
    // C3 - правый нижний, C4 - левый нижний (по часовой стрелке)
    var refC1 by remember { mutableStateOf(Offset(0.25f, 0.10f)) }
    var refC2 by remember { mutableStateOf(Offset(0.55f, 0.10f)) }
    var refC3 by remember { mutableStateOf(Offset(0.55f, 0.30f)) }
    var refC4 by remember { mutableStateOf(Offset(0.25f, 0.30f)) }
    val vertices = remember { mutableStateListOf<Offset>() }

    var selectedReference by remember { mutableStateOf(RECT_REFERENCES[0]) }
    var customWidthText by remember { mutableStateOf("") }
    var customHeightText by remember { mutableStateOf("") }
    var areaUnit by rememberSaveable { mutableStateOf(AreaUnit.CM2.name) }
    var showSaveDialog by remember { mutableStateOf(false) }

    var draggingPos by remember { mutableStateOf<Offset?>(null) }
    var lingerPos by remember { mutableStateOf<Offset?>(null) }
    var lastTouchedHit by remember { mutableStateOf<Hit?>(null) }

    LaunchedEffect(lingerPos) {
        if (lingerPos != null && draggingPos == null) {
            delay(MAGNIFIER_LINGER_MS)
            if (draggingPos == null) lingerPos = null
        }
    }

    val activeUnit = AreaUnit.valueOf(areaUnit)
    val refW = if (selectedReference.label == "Свой размер")
        customWidthText.replace(',', '.').toDoubleOrNull() ?: 0.0
    else selectedReference.widthMm
    val refH = if (selectedReference.label == "Свой размер")
        customHeightText.replace(',', '.').toDoubleOrNull() ?: 0.0
    else selectedReference.heightMm

    // Гомография: преобразование "пиксели на канвасе" -> "миллиметры в реальной плоскости"
    val homography: Homography? = remember(refC1, refC2, refC3, refC4, refW, refH, canvasSize) {
        if (canvasSize == Size.Zero || refW <= 0.0 || refH <= 0.0) return@remember null
        // Источник: 4 точки эталона на канвасе
        val srcPts = listOf(
            relToCanvas(refC1, canvasSize),
            relToCanvas(refC2, canvasSize),
            relToCanvas(refC3, canvasSize),
            relToCanvas(refC4, canvasSize)
        )
        // Назначение: соответствующие реальные координаты в мм
        val dstPts = listOf(
            Offset(0f, 0f),                  // C1
            Offset(refW.toFloat(), 0f),       // C2
            Offset(refW.toFloat(), refH.toFloat()),  // C3
            Offset(0f, refH.toFloat())        // C4
        )
        computeHomography(srcPts, dstPts)
    }

    val areaMm2: Double? = remember(vertices.toList(), homography, canvasSize) {
        if (vertices.size < 3 || homography == null || canvasSize == Size.Zero) return@remember null
        // Каждую вершину переводим в реальные мм
        val realVertices = vertices.map {
            val canvasPt = relToCanvas(it, canvasSize)
            homography.apply(canvasPt)
        }
        val areaMm2 = polygonAreaPx(realVertices)  // та же shoelace, но в мм
        if (areaMm2 < 1.0) return@remember null
        areaMm2
    }

    fun nudgePoint(dx: Float, dy: Float) {
        val hit = lastTouchedHit ?: return
        if (canvasSize == Size.Zero) return
        val dxRel = dx / canvasSize.width
        val dyRel = dy / canvasSize.height
        val newPosCanvas: Offset?
        when (hit.kind) {
            HitKind.REF_C1 -> { refC1 = Offset((refC1.x + dxRel).coerceIn(0f, 1f), (refC1.y + dyRel).coerceIn(0f, 1f)); newPosCanvas = relToCanvas(refC1, canvasSize) }
            HitKind.REF_C2 -> { refC2 = Offset((refC2.x + dxRel).coerceIn(0f, 1f), (refC2.y + dyRel).coerceIn(0f, 1f)); newPosCanvas = relToCanvas(refC2, canvasSize) }
            HitKind.REF_C3 -> { refC3 = Offset((refC3.x + dxRel).coerceIn(0f, 1f), (refC3.y + dyRel).coerceIn(0f, 1f)); newPosCanvas = relToCanvas(refC3, canvasSize) }
            HitKind.REF_C4 -> { refC4 = Offset((refC4.x + dxRel).coerceIn(0f, 1f), (refC4.y + dyRel).coerceIn(0f, 1f)); newPosCanvas = relToCanvas(refC4, canvasSize) }
            HitKind.VERTEX -> {
                val idx = hit.vertexIndex
                if (idx in vertices.indices) {
                    val cur = vertices[idx]
                    vertices[idx] = Offset((cur.x + dxRel).coerceIn(0f, 1f), (cur.y + dyRel).coerceIn(0f, 1f))
                    newPosCanvas = relToCanvas(vertices[idx], canvasSize)
                } else newPosCanvas = null
            }
            else -> newPosCanvas = null
        }
        if (newPosCanvas != null) lingerPos = newPosCanvas
    }

    Column(modifier = Modifier.fillMaxSize()) {
        Box(modifier = Modifier.weight(1f).fillMaxWidth().background(Color.Black)) {
            HomographyAreaCanvas(
                image = image,
                refC1 = refC1, refC2 = refC2, refC3 = refC3, refC4 = refC4,
                vertices = vertices,
                onAddVertex = { canvasPos ->
                    if (vertices.size < MAX_VERTICES) {
                        vertices.add(canvasToRel(canvasPos, canvasSize))
                        lastTouchedHit = Hit(HitKind.VERTEX, vertices.size - 1)
                    }
                },
                onPointDrag = { hit, newPos ->
                    val rel = canvasToRel(newPos, canvasSize)
                    when (hit.kind) {
                        HitKind.REF_C1 -> refC1 = rel
                        HitKind.REF_C2 -> refC2 = rel
                        HitKind.REF_C3 -> refC3 = rel
                        HitKind.REF_C4 -> refC4 = rel
                        HitKind.VERTEX -> { if (hit.vertexIndex in vertices.indices) vertices[hit.vertexIndex] = rel }
                        else -> Unit
                    }
                    draggingPos = newPos
                    lingerPos = newPos
                    lastTouchedHit = hit
                },
                onDragEnd = { draggingPos = null },
                onLongPressVertex = { idx ->
                    if (idx in vertices.indices) vertices.removeAt(idx)
                },
                onCanvasSizeChange = { canvasSize = it }
            )

            val mag = lingerPos
            if (mag != null && canvasSize != Size.Zero) {
                val onRight = mag.x < canvasSize.width / 2f
                val alignment = if (onRight) Alignment.TopEnd else Alignment.TopStart
                Column(
                    modifier = Modifier.align(alignment).padding(8.dp),
                    horizontalAlignment = if (onRight) Alignment.End else Alignment.Start
                ) {
                    Box(modifier = Modifier.size(140.dp)) {
                        Magnifier(image = image, canvasSize = canvasSize, focus = mag)
                    }
                    if (draggingPos == null && lastTouchedHit != null) {
                        Spacer(Modifier.height(6.dp))
                        NudgePad(onNudge = { dx, dy -> nudgePoint(dx, dy) })
                    }
                }
            }

            if (vertices.isEmpty()) {
                Surface(
                    modifier = Modifier.align(Alignment.BottomCenter).padding(16.dp),
                    color = Color.Black.copy(alpha = 0.7f),
                    shape = MaterialTheme.shapes.medium
                ) {
                    Text(
                        text = "1) Двигайте C1-C4 по углам эталона.  2) Тапайте по контуру фигуры.",
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                        style = MaterialTheme.typography.bodySmall,
                        color = Color.White
                    )
                }
            }
        }

        HomographyBottomPanel(
            areaMm2 = areaMm2,
            unit = activeUnit,
            vertexCount = vertices.size,
            references = RECT_REFERENCES,
            selectedReference = selectedReference,
            onReferenceChange = { selectedReference = it },
            customWidthText = customWidthText,
            onCustomWidthChange = { customWidthText = it },
            customHeightText = customHeightText,
            onCustomHeightChange = { customHeightText = it },
            onUnitChange = { areaUnit = it.name },
            onSave = { showSaveDialog = true },
            onUndo = {
                if (vertices.isNotEmpty()) {
                    vertices.removeAt(vertices.size - 1)
                    if (lastTouchedHit?.kind == HitKind.VERTEX &&
                        lastTouchedHit!!.vertexIndex >= vertices.size) {
                        lastTouchedHit = null
                    }
                }
            },
            onClearVertices = {
                vertices.clear()
                if (lastTouchedHit?.kind == HitKind.VERTEX) lastTouchedHit = null
            }
        )
    }

    if (showSaveDialog && areaMm2 != null) {
        SaveDialogHomography(
            areaMm2 = areaMm2, unit = activeUnit, vertexCount = vertices.size,
            referenceLabel = selectedReference.label, refW = refW, refH = refH,
            onDismiss = { showSaveDialog = false },
            onConfirm = { note ->
                val markup = ru.measurekit.util.MarkupRenderer.renderAreaHomographyMarkup(
                    sourceImage = image,
                    refC1 = refC1, refC2 = refC2, refC3 = refC3, refC4 = refC4,
                    vertices = vertices.toList(),
                    valueText = activeUnit.format(areaMm2),
                    referenceText = "эталон: ${selectedReference.label} (${"%.0f".format(refW)}×${"%.0f".format(refH)} мм), вершин: ${vertices.size}, точный режим"
                )
                val original = ru.measurekit.util.MarkupRenderer.imageBitmapToBitmap(image)
                onSave(areaMm2, selectedReference.label, refW, refH, vertices.size, note, markup, original)
                showSaveDialog = false
            }
        )
    }
}

@Composable
private fun HomographyAreaCanvas(
    image: ImageBitmap,
    refC1: Offset, refC2: Offset, refC3: Offset, refC4: Offset,
    vertices: List<Offset>,
    onAddVertex: (Offset) -> Unit,
    onPointDrag: (Hit, Offset) -> Unit,
    onDragEnd: () -> Unit,
    onLongPressVertex: (Int) -> Unit,
    onCanvasSizeChange: (Size) -> Unit
) {
    val refColor = MaterialTheme.colorScheme.tertiary
    val polyColor = MaterialTheme.colorScheme.primary
    val polyFill = polyColor.copy(alpha = 0.18f)
    val textMeasurer = rememberTextMeasurer()
    val labelStyle = TextStyle(fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Color.White)

    val cur1 by rememberUpdatedState(refC1)
    val cur2 by rememberUpdatedState(refC2)
    val cur3 by rememberUpdatedState(refC3)
    val cur4 by rememberUpdatedState(refC4)
    val curV by rememberUpdatedState(vertices)

    Canvas(
        modifier = Modifier.fillMaxSize()
            .pointerInput(Unit) {
                var draggingHit: Hit? = null
                detectDragGestures(
                    onDragStart = { offset ->
                        draggingHit = findHomographyHit(offset, cur1, cur2, cur3, cur4, curV, this.size.toSize())
                    },
                    onDrag = { change, _ ->
                        draggingHit?.let { onPointDrag(it, change.position) }
                    },
                    onDragEnd = { draggingHit = null; onDragEnd() },
                    onDragCancel = { draggingHit = null; onDragEnd() }
                )
            }
            .pointerInput(Unit) {
                detectTapGestures(
                    onLongPress = { offset ->
                        val hit = findHomographyHit(offset, cur1, cur2, cur3, cur4, curV, this.size.toSize())
                        if (hit?.kind == HitKind.VERTEX) onLongPressVertex(hit.vertexIndex)
                    },
                    onTap = { offset ->
                        val hit = findHomographyHit(offset, cur1, cur2, cur3, cur4, curV, this.size.toSize())
                        if (hit == null) onAddVertex(offset)
                    }
                )
            }
    ) {
        onCanvasSizeChange(size)
        drawFitImage(image)

        // Эталон-четырёхугольник: C1-C2-C3-C4 закрашенным контуром
        val c1p = relToCanvas(refC1, size)
        val c2p = relToCanvas(refC2, size)
        val c3p = relToCanvas(refC3, size)
        val c4p = relToCanvas(refC4, size)
        val refPath = Path().apply {
            moveTo(c1p.x, c1p.y); lineTo(c2p.x, c2p.y)
            lineTo(c3p.x, c3p.y); lineTo(c4p.x, c4p.y); close()
        }
        drawPath(refPath, refColor.copy(alpha = 0.10f))
        drawPath(refPath, refColor, style = Stroke(
            width = 3f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(15f, 10f), 0f)
        ))
        drawPointMarker(c1p, refColor, "C1", textMeasurer, labelStyle)
        drawPointMarker(c2p, refColor, "C2", textMeasurer, labelStyle)
        drawPointMarker(c3p, refColor, "C3", textMeasurer, labelStyle)
        drawPointMarker(c4p, refColor, "C4", textMeasurer, labelStyle)

        // Полигон-объект
        if (vertices.isNotEmpty()) {
            val canvasVertices = vertices.map { relToCanvas(it, size) }
            if (canvasVertices.size >= 3) {
                val path = Path().apply {
                    moveTo(canvasVertices[0].x, canvasVertices[0].y)
                    for (i in 1 until canvasVertices.size) lineTo(canvasVertices[i].x, canvasVertices[i].y)
                    close()
                }
                drawPath(path, polyFill)
                drawPath(path, polyColor, style = Stroke(width = 4f))
            } else if (canvasVertices.size == 2) {
                drawLine(polyColor, canvasVertices[0], canvasVertices[1], strokeWidth = 4f)
            }
            canvasVertices.forEachIndexed { idx, pt ->
                drawPointMarker(pt, polyColor, "V${idx + 1}", textMeasurer, labelStyle)
            }
        }
    }
}

private fun findHomographyHit(
    offset: Offset,
    c1: Offset, c2: Offset, c3: Offset, c4: Offset,
    vertices: List<Offset>, canvasSize: Size
): Hit? {
    val candidates = mutableListOf<Pair<Hit, Offset>>()
    candidates.add(Hit(HitKind.REF_C1) to relToCanvas(c1, canvasSize))
    candidates.add(Hit(HitKind.REF_C2) to relToCanvas(c2, canvasSize))
    candidates.add(Hit(HitKind.REF_C3) to relToCanvas(c3, canvasSize))
    candidates.add(Hit(HitKind.REF_C4) to relToCanvas(c4, canvasSize))
    vertices.forEachIndexed { idx, v ->
        candidates.add(Hit(HitKind.VERTEX, idx) to relToCanvas(v, canvasSize))
    }
    val closest = candidates.minByOrNull { distance(it.second, offset) } ?: return null
    return if (distance(closest.second, offset) < POINT_HIT_RADIUS) closest.first else null
}

@Composable
private fun HomographyBottomPanel(
    areaMm2: Double?, unit: AreaUnit, vertexCount: Int,
    references: List<RectReference>,
    selectedReference: RectReference,
    onReferenceChange: (RectReference) -> Unit,
    customWidthText: String, onCustomWidthChange: (String) -> Unit,
    customHeightText: String, onCustomHeightChange: (String) -> Unit,
    onUnitChange: (AreaUnit) -> Unit,
    onSave: () -> Unit, onUndo: () -> Unit, onClearVertices: () -> Unit
) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh, tonalElevation = 4.dp) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = "Площадь  (вершин: $vertexCount, точный режим)",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    if (areaMm2 != null) {
                        Text(unit.format(areaMm2),
                            style = MaterialTheme.typography.headlineMedium,
                            fontWeight = FontWeight.Bold)
                    } else {
                        Text("—", style = MaterialTheme.typography.headlineMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                Row {
                    AreaUnit.entries.forEach { u ->
                        val sel = u == unit
                        TextButton(
                            onClick = { onUnitChange(u) },
                            colors = ButtonDefaults.textButtonColors(
                                contentColor = if (sel) MaterialTheme.colorScheme.primary
                                               else MaterialTheme.colorScheme.onSurfaceVariant
                            ),
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)
                        ) {
                            Text(u.label, style = MaterialTheme.typography.labelMedium,
                                fontWeight = if (sel) FontWeight.Bold else FontWeight.Normal)
                        }
                    }
                }
            }

            Spacer(Modifier.height(8.dp))
            Text("Эталон-прямоугольник:", style = MaterialTheme.typography.labelMedium)
            Spacer(Modifier.height(4.dp))
            LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                items(references.size) { idx ->
                    val r = references[idx]
                    val sel = r.label == selectedReference.label
                    FilterChip(
                        selected = sel, onClick = { onReferenceChange(r) },
                        label = {
                            Text(
                                if (r.label == "Свой размер") r.label
                                else "${r.label} ${"%.0f".format(r.widthMm)}×${"%.0f".format(r.heightMm)}мм",
                                style = MaterialTheme.typography.labelSmall
                            )
                        }
                    )
                }
            }
            if (selectedReference.label == "Свой размер") {
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = customWidthText, onValueChange = onCustomWidthChange,
                        label = { Text("Ширина, мм") }, singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        modifier = Modifier.weight(1f)
                    )
                    OutlinedTextField(
                        value = customHeightText, onValueChange = onCustomHeightChange,
                        label = { Text("Высота, мм") }, singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        modifier = Modifier.weight(1f)
                    )
                }
            }
            Spacer(Modifier.height(12.dp))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onUndo, enabled = vertexCount > 0) {
                    Icon(Icons.Filled.Undo, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Назад")
                }
                OutlinedButton(onClick = onClearVertices, enabled = vertexCount > 0) {
                    Icon(Icons.Filled.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                }
                Button(onClick = onSave, enabled = areaMm2 != null,
                    modifier = Modifier.weight(1f)) {
                    Icon(Icons.Filled.Bookmark, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Сохранить")
                }
            }
        }
    }
}

/* ===========================================================================
 *                       Общие компоненты
 * =========================================================================== */

@Composable
private fun NudgePad(onNudge: (dx: Float, dy: Float) -> Unit) {
    val step = 1f
    Surface(
        color = Color.Black.copy(alpha = 0.7f),
        shape = MaterialTheme.shapes.small
    ) {
        Column(
            modifier = Modifier.padding(4.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            NudgeButton(Icons.Filled.KeyboardArrowUp) { onNudge(0f, -step) }
            Row {
                NudgeButton(Icons.Filled.KeyboardArrowLeft) { onNudge(-step, 0f) }
                Spacer(Modifier.width(28.dp))
                NudgeButton(Icons.Filled.KeyboardArrowRight) { onNudge(step, 0f) }
            }
            NudgeButton(Icons.Filled.KeyboardArrowDown) { onNudge(0f, step) }
        }
    }
}

@Composable
private fun NudgeButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    onClick: () -> Unit
) {
    IconButton(onClick = onClick, modifier = Modifier.size(28.dp)) {
        Icon(icon, contentDescription = null, tint = Color.White, modifier = Modifier.size(20.dp))
    }
}

private fun DrawScope.drawFitImage(image: ImageBitmap) {
    val imgW = image.width.toFloat()
    val imgH = image.height.toFloat()
    val fitScale = min(size.width / imgW, size.height / imgH)
    val drawW = imgW * fitScale
    val drawH = imgH * fitScale
    val drawX = (size.width - drawW) / 2f
    val drawY = (size.height - drawH) / 2f
    drawImage(
        image = image,
        dstOffset = IntOffset(drawX.roundToInt(), drawY.roundToInt()),
        dstSize = IntSize(drawW.roundToInt(), drawH.roundToInt())
    )
}

private fun androidx.compose.ui.unit.IntSize.toSize() = Size(width.toFloat(), height.toFloat())

private fun DrawScope.drawPointMarker(
    pos: Offset, color: Color, label: String,
    textMeasurer: TextMeasurer, labelStyle: TextStyle
) {
    drawCircle(color = Color.White, radius = 20f, center = pos)
    drawCircle(color = color, radius = 17f, center = pos)
    drawCircle(color = Color.White, radius = 4f, center = pos)
    val layout = textMeasurer.measure(label, labelStyle)
    val labelPos = Offset(pos.x + 26f, pos.y - 26f)
    drawCircle(
        color = Color.Black.copy(alpha = 0.7f),
        radius = max(layout.size.width, layout.size.height).toFloat() / 1.4f,
        center = Offset(labelPos.x + layout.size.width / 2f, labelPos.y + layout.size.height / 2f)
    )
    drawText(textLayoutResult = layout, topLeft = labelPos, color = Color.White)
}

@Composable
private fun Magnifier(image: ImageBitmap, canvasSize: Size, focus: Offset) {
    val targetColor = MaterialTheme.colorScheme.primary
    Canvas(modifier = Modifier.fillMaxSize().background(Color.Black)) {
        val outSize = size.width
        val imgW = image.width.toFloat()
        val imgH = image.height.toFloat()
        val fitScale = min(canvasSize.width / imgW, canvasSize.height / imgH)
        val mainDrawW = imgW * fitScale
        val mainDrawH = imgH * fitScale
        val mainDrawX = (canvasSize.width - mainDrawW) / 2f
        val mainDrawY = (canvasSize.height - mainDrawH) / 2f

        val pxX = (focus.x - mainDrawX) / fitScale
        val pxY = (focus.y - mainDrawY) / fitScale

        val magScale = fitScale * 2.5f
        val magW = imgW * magScale
        val magH = imgH * magScale
        val magX = -pxX * magScale + outSize / 2f
        val magY = -pxY * magScale + outSize / 2f

        drawImage(
            image = image,
            dstOffset = IntOffset(magX.roundToInt(), magY.roundToInt()),
            dstSize = IntSize(magW.roundToInt(), magH.roundToInt())
        )

        val cx = outSize / 2f
        val cy = outSize / 2f
        drawLine(targetColor, Offset(cx - 22f, cy), Offset(cx + 22f, cy), strokeWidth = 2f)
        drawLine(targetColor, Offset(cx, cy - 22f), Offset(cx, cy + 22f), strokeWidth = 2f)
        drawCircle(targetColor, radius = 10f, center = Offset(cx, cy), style = Stroke(width = 2f))
        drawRect(targetColor, size = size, style = Stroke(width = 3f))
    }
}

/* ---------- Геометрия ---------- */

private fun relToCanvas(rel: Offset, canvasSize: Size): Offset {
    return Offset(rel.x * canvasSize.width, rel.y * canvasSize.height)
}

private fun canvasToRel(pos: Offset, canvasSize: Size): Offset {
    if (canvasSize == Size.Zero) return Offset(0.5f, 0.5f)
    return Offset(
        (pos.x / canvasSize.width).coerceIn(0f, 1f),
        (pos.y / canvasSize.height).coerceIn(0f, 1f)
    )
}

private fun distance(a: Offset, b: Offset): Double = hypot((a.x - b.x).toDouble(), (a.y - b.y).toDouble())

private fun polygonAreaPx(points: List<Offset>): Double {
    if (points.size < 3) return 0.0
    var sum = 0.0
    for (i in points.indices) {
        val curr = points[i]
        val next = points[(i + 1) % points.size]
        sum += curr.x.toDouble() * next.y.toDouble() - next.x.toDouble() * curr.y.toDouble()
    }
    return abs(sum) / 2.0
}

/* ===========================================================================
 *                    Гомография (DLT, 4-точечная)
 * =========================================================================== */

/**
 * Проективное преобразование 3×3.
 * Для точки (x, y) даёт:
 *   x' = (h11*x + h12*y + h13) / (h31*x + h32*y + h33)
 *   y' = (h21*x + h22*y + h23) / (h31*x + h32*y + h33)
 *
 * Используется для коррекции перспективных искажений.
 */
private class Homography(private val m: DoubleArray) {
    fun apply(p: Offset): Offset {
        val x = p.x.toDouble()
        val y = p.y.toDouble()
        val w = m[6] * x + m[7] * y + m[8]
        val nx = (m[0] * x + m[1] * y + m[2]) / w
        val ny = (m[3] * x + m[4] * y + m[5]) / w
        return Offset(nx.toFloat(), ny.toFloat())
    }
}

/**
 * Вычисляет матрицу гомографии H, отображающую src[i] -> dst[i].
 *
 * Метод DLT (Direct Linear Transform): из 4 точек строим систему 8 уравнений
 * (по 2 на каждую точку), решаем относительно 8 неизвестных (h33 фиксируем = 1).
 *
 * Возвращает null если точки вырождены (3+ коллинеарны).
 */
private fun computeHomography(src: List<Offset>, dst: List<Offset>): Homography? {
    if (src.size != 4 || dst.size != 4) return null

    // Строим матрицу системы 8x8 и вектор b (8x1):
    // Для каждой точки (xs, ys) -> (xd, yd) два уравнения:
    //   xd = h11*xs + h12*ys + h13 - h31*xs*xd - h32*ys*xd
    //   yd = h21*xs + h22*ys + h23 - h31*xs*yd - h32*ys*yd
    // Неизвестные: [h11 h12 h13 h21 h22 h23 h31 h32]
    val a = Array(8) { DoubleArray(8) }
    val b = DoubleArray(8)
    for (i in 0..3) {
        val xs = src[i].x.toDouble()
        val ys = src[i].y.toDouble()
        val xd = dst[i].x.toDouble()
        val yd = dst[i].y.toDouble()
        // Уравнение для xd
        val r1 = i * 2
        a[r1][0] = xs; a[r1][1] = ys; a[r1][2] = 1.0
        a[r1][3] = 0.0; a[r1][4] = 0.0; a[r1][5] = 0.0
        a[r1][6] = -xs * xd; a[r1][7] = -ys * xd
        b[r1] = xd
        // Уравнение для yd
        val r2 = i * 2 + 1
        a[r2][0] = 0.0; a[r2][1] = 0.0; a[r2][2] = 0.0
        a[r2][3] = xs; a[r2][4] = ys; a[r2][5] = 1.0
        a[r2][6] = -xs * yd; a[r2][7] = -ys * yd
        b[r2] = yd
    }

    val h = solveLinearSystem(a, b) ?: return null
    return Homography(doubleArrayOf(h[0], h[1], h[2], h[3], h[4], h[5], h[6], h[7], 1.0))
}

/**
 * Решает систему линейных уравнений A*x = b методом Гаусса с частичным выбором ведущего элемента.
 * Возвращает null если матрица сингулярна.
 */
private fun solveLinearSystem(A: Array<DoubleArray>, b: DoubleArray): DoubleArray? {
    val n = b.size
    // Расширенная матрица [A | b]
    val m = Array(n) { i -> DoubleArray(n + 1) { j -> if (j < n) A[i][j] else b[i] } }

    // Прямой ход с выбором ведущего элемента
    for (k in 0 until n) {
        // Ищем максимальный по модулю в столбце k
        var maxRow = k
        var maxVal = abs(m[k][k])
        for (i in k + 1 until n) {
            if (abs(m[i][k]) > maxVal) {
                maxVal = abs(m[i][k])
                maxRow = i
            }
        }
        if (maxVal < 1e-12) return null  // вырожденная матрица
        if (maxRow != k) {
            val tmp = m[k]; m[k] = m[maxRow]; m[maxRow] = tmp
        }
        // Зануляем столбец k под главной диагональю
        for (i in k + 1 until n) {
            val factor = m[i][k] / m[k][k]
            for (j in k..n) {
                m[i][j] -= factor * m[k][j]
            }
        }
    }
    // Обратный ход
    val x = DoubleArray(n)
    for (i in n - 1 downTo 0) {
        var sum = m[i][n]
        for (j in i + 1 until n) sum -= m[i][j] * x[j]
        x[i] = sum / m[i][i]
    }
    return x
}

/* ===========================================================================
 *                    Диалоги сохранения
 * =========================================================================== */

@Composable
private fun SaveDialogSimple(
    areaMm2: Double, unit: AreaUnit, vertexCount: Int,
    referenceLabel: String, referenceMm: Double,
    onDismiss: () -> Unit, onConfirm: (note: String) -> Unit
) {
    var note by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Сохранить площадь") },
        text = {
            Column {
                Text(unit.format(areaMm2),
                    style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                Text("$vertexCount вершин, эталон: $referenceLabel (${"%.1f".format(referenceMm)} мм)",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(16.dp))
                OutlinedTextField(
                    value = note, onValueChange = { note = it },
                    label = { Text("Заметка (необязательно)") },
                    minLines = 2, modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = { TextButton(onClick = { onConfirm(note.trim()) }) { Text("Сохранить") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена") } }
    )
}

@Composable
private fun SaveDialogHomography(
    areaMm2: Double, unit: AreaUnit, vertexCount: Int,
    referenceLabel: String, refW: Double, refH: Double,
    onDismiss: () -> Unit, onConfirm: (note: String) -> Unit
) {
    var note by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Сохранить площадь") },
        text = {
            Column {
                Text(unit.format(areaMm2),
                    style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                Text(
                    "$vertexCount вершин, эталон: $referenceLabel (${"%.0f".format(refW)}×${"%.0f".format(refH)} мм), " +
                    "точный режим (4 точки + гомография)",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(16.dp))
                OutlinedTextField(
                    value = note, onValueChange = { note = it },
                    label = { Text("Заметка (необязательно)") },
                    minLines = 2, modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = { TextButton(onClick = { onConfirm(note.trim()) }) { Text("Сохранить") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена") } }
    )
}

private fun formatAreaShort(mm2: Double): String {
    return when {
        mm2 < 1000 -> "%.0f мм²".format(mm2)
        mm2 < 100_000 -> "%.1f см²".format(mm2 / 100)
        else -> "%.2f м²".format(mm2 / 1_000_000)
    }
}
