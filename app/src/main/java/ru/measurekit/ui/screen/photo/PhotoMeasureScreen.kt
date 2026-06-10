package ru.measurekit.ui.screen.photo

import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
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
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import ru.measurekit.data.MeasurementType
import ru.measurekit.data.MeasurementsRepository
import ru.measurekit.domain.LengthUnit
import java.io.File
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Фото с эталоном.
 *
 * Workflow:
 *   1. Делаешь фото (или выбираешь из галереи). На фото - эталон + объект в одной плоскости.
 *   2. Двигаешь две точки на эталон (A1, A2 - концы известного отрезка).
 *   3. Двигаешь две точки на объект (B1, B2 - концы измеряемого отрезка).
 *   4. Размер объекта = (расстояние B1-B2) / (расстояние A1-A2) * эталон_в_мм
 *
 * Лупа в углу при перетаскивании - чтобы видеть, куда ставится точка под пальцем.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PhotoMeasureScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val repo = remember { MeasurementsRepository.get(ctx) }
    val scope = rememberCoroutineScope()
    val snackbarHost = remember { SnackbarHostState() }

    var imageBitmap by remember { mutableStateOf<ImageBitmap?>(null) }
    var loadError by remember { mutableStateOf<String?>(null) }

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
                title = { Text("Фото с эталоном") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Назад")
                    }
                },
                actions = {
                    if (imageBitmap != null) {
                        IconButton(onClick = { imageBitmap = null }) {
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
            if (current == null) {
                SourcePicker(
                    onPickGallery = { galleryLauncher.launch("image/*") },
                    onPickCamera = {
                        val uri = createTempImageUri(ctx)
                        pendingCameraUri = uri
                        cameraLauncher.launch(uri)
                    },
                    error = loadError
                )
            } else {
                MeasureView(
                    image = current,
                    onSave = { measuredMm, refLabel, refMm, note, markup, original ->
                        scope.launch {
                            val noteFull = buildString {
                                append("эталон=$refLabel ${"%.1f".format(refMm)} мм")
                                if (note.isNotBlank()) append(" — $note")
                            }
                            repo.save(
                                type = MeasurementType.PHOTO,
                                valueRaw = measuredMm,
                                unit = "мм",
                                note = noteFull,
                                markupBitmap = markup,
                                originalBitmap = original
                            )
                            snackbarHost.showSnackbar("Сохранено: ${"%.1f".format(measuredMm)} мм")
                        }
                    }
                )
            }
        }
    }
}

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
            text = "Эталон (кредитка, монета и т.п.) и измеряемый объект " +
                    "должны быть в одной плоскости. Снимайте перпендикулярно поверхности.",
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

/* ---------- Эталоны ---------- */

private data class Reference(val label: String, val mm: Double)

private val DEFAULT_REFERENCES = listOf(
    Reference("Кредитка длинная", 85.6),
    Reference("Кредитка короткая", 53.98),
    Reference("Монета 10₽", 22.0),
    Reference("Монета 5₽", 25.0),
    Reference("Монета 2₽", 23.0),
    Reference("Монета 1₽", 20.5),
    Reference("Спич. коробок", 50.0),
    Reference("A4 длинная", 297.0),
    Reference("A4 короткая", 210.0),
    Reference("Линейка 10 см", 100.0),
    Reference("Линейка 30 см", 300.0),
    Reference("Свой размер", 0.0)
)

/* ---------- Главный экран измерения ---------- */

private enum class PointKind { REF_A, REF_B, OBJ_A, OBJ_B }

@Composable
private fun MeasureView(
    image: ImageBitmap,
    onSave: (measuredMm: Double, refLabel: String, refMm: Double, note: String, markup: android.graphics.Bitmap, original: android.graphics.Bitmap) -> Unit
) {
    var canvasSize by remember { mutableStateOf(Size.Zero) }

    // Точки в относительных координатах [0..1]
    var refA by remember { mutableStateOf(Offset(0.25f, 0.25f)) }
    var refB by remember { mutableStateOf(Offset(0.55f, 0.25f)) }
    var objA by remember { mutableStateOf(Offset(0.25f, 0.65f)) }
    var objB by remember { mutableStateOf(Offset(0.55f, 0.65f)) }

    var selectedReference by remember { mutableStateOf(DEFAULT_REFERENCES[0]) }
    var customMmText by remember { mutableStateOf("") }
    var unit by rememberSaveable { mutableStateOf(LengthUnit.MM.name) }
    var showSaveDialog by remember { mutableStateOf(false) }
    var draggingPos by remember { mutableStateOf<Offset?>(null) }

    val activeUnit = LengthUnit.valueOf(unit)
    val effectiveRefMm = if (selectedReference.label == "Свой размер") {
        customMmText.replace(',', '.').toDoubleOrNull() ?: 0.0
    } else {
        selectedReference.mm
    }

    val measuredMm: Double? = remember(refA, refB, objA, objB, effectiveRefMm, canvasSize) {
        if (canvasSize == Size.Zero || effectiveRefMm <= 0.0) return@remember null
        val refPx = distance(relToCanvas(refA, canvasSize), relToCanvas(refB, canvasSize))
        val objPx = distance(relToCanvas(objA, canvasSize), relToCanvas(objB, canvasSize))
        if (refPx < 5.0) return@remember null
        objPx / refPx * effectiveRefMm
    }

    Column(modifier = Modifier.fillMaxSize()) {
        Box(
            modifier = Modifier.weight(1f).fillMaxWidth().background(Color.Black)
        ) {
            PhotoCanvas(
                image = image,
                refA = refA, refB = refB,
                objA = objA, objB = objB,
                onPointDrag = { which, newPos ->
                    val rel = canvasToRel(newPos, canvasSize)
                    when (which) {
                        PointKind.REF_A -> refA = rel
                        PointKind.REF_B -> refB = rel
                        PointKind.OBJ_A -> objA = rel
                        PointKind.OBJ_B -> objB = rel
                    }
                    draggingPos = newPos
                },
                onDragEnd = { draggingPos = null },
                onCanvasSizeChange = { canvasSize = it }
            )

            // Лупа поверх в верхнем-левом или верхнем-правом углу,
            // в зависимости от того, в какой половине экрана палец
            val mag = draggingPos
            if (mag != null && canvasSize != Size.Zero) {
                val onRight = mag.x < canvasSize.width / 2f  // если палец слева - лупа справа
                val alignment = if (onRight) Alignment.TopEnd else Alignment.TopStart
                Box(
                    modifier = Modifier
                        .align(alignment)
                        .padding(8.dp)
                        .size(140.dp)
                ) {
                    Magnifier(
                        image = image,
                        canvasSize = canvasSize,
                        focus = mag
                    )
                }
            }
        }

        BottomPanel(
            measuredMm = measuredMm,
            unit = activeUnit,
            references = DEFAULT_REFERENCES,
            selectedReference = selectedReference,
            onReferenceChange = { selectedReference = it },
            customMmText = customMmText,
            onCustomMmChange = { customMmText = it },
            onUnitChange = { unit = it.name },
            onSave = { showSaveDialog = true },
            onResetPoints = {
                refA = Offset(0.25f, 0.25f)
                refB = Offset(0.55f, 0.25f)
                objA = Offset(0.25f, 0.65f)
                objB = Offset(0.55f, 0.65f)
            }
        )
    }

    if (showSaveDialog && measuredMm != null) {
        SaveDialog(
            measuredMm = measuredMm,
            unit = activeUnit,
            referenceLabel = selectedReference.label,
            referenceMm = effectiveRefMm,
            onDismiss = { showSaveDialog = false },
            onConfirm = { note ->
                val markup = ru.measurekit.util.MarkupRenderer.renderPhotoMarkup(
                    sourceImage = image,
                    refA = refA, refB = refB, objA = objA, objB = objB,
                    valueText = activeUnit.format(measuredMm),
                    referenceText = "эталон: ${selectedReference.label} (${"%.1f".format(effectiveRefMm)} мм)"
                )
                val original = ru.measurekit.util.MarkupRenderer.imageBitmapToBitmap(image)
                onSave(measuredMm, selectedReference.label, effectiveRefMm, note, markup, original)
                showSaveDialog = false
            }
        )
    }
}

/* ---------- Канвас с фото и точками ---------- */

@Composable
private fun PhotoCanvas(
    image: ImageBitmap,
    refA: Offset, refB: Offset,
    objA: Offset, objB: Offset,
    onPointDrag: (PointKind, Offset) -> Unit,
    onDragEnd: () -> Unit,
    onCanvasSizeChange: (Size) -> Unit
) {
    val refColor = MaterialTheme.colorScheme.tertiary
    val objColor = MaterialTheme.colorScheme.primary
    val textMeasurer = rememberTextMeasurer()
    val labelStyle = TextStyle(
        fontSize = 12.sp,
        fontWeight = FontWeight.Bold,
        color = Color.White
    )

    val currentRefA by rememberUpdatedState(refA)
    val currentRefB by rememberUpdatedState(refB)
    val currentObjA by rememberUpdatedState(objA)
    val currentObjB by rememberUpdatedState(objB)

    Canvas(
        modifier = Modifier
            .fillMaxSize()
            .pointerInput(Unit) {
                var draggingKind: PointKind? = null
                detectDragGestures(
                    onDragStart = { offset ->
                        val pts = listOf(
                            PointKind.REF_A to relToCanvas(currentRefA, this.size.toSize()),
                            PointKind.REF_B to relToCanvas(currentRefB, this.size.toSize()),
                            PointKind.OBJ_A to relToCanvas(currentObjA, this.size.toSize()),
                            PointKind.OBJ_B to relToCanvas(currentObjB, this.size.toSize())
                        )
                        val closest = pts.minByOrNull { distance(it.second, offset) }
                        if (closest != null && distance(closest.second, offset) < 150.0) {
                            draggingKind = closest.first
                            onPointDrag(closest.first, offset)
                        }
                    },
                    onDrag = { change, _ ->
                        draggingKind?.let { kind ->
                            onPointDrag(kind, change.position)
                        }
                    },
                    onDragEnd = {
                        draggingKind = null
                        onDragEnd()
                    },
                    onDragCancel = {
                        draggingKind = null
                        onDragEnd()
                    }
                )
            }
    ) {
        onCanvasSizeChange(size)

        // Подгоняем картинку чтобы помещалась целиком (fit)
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

        val refAp = relToCanvas(refA, size)
        val refBp = relToCanvas(refB, size)
        val objAp = relToCanvas(objA, size)
        val objBp = relToCanvas(objB, size)

        // Линия эталона - пунктир
        drawLine(
            color = refColor,
            start = refAp,
            end = refBp,
            strokeWidth = 4f,
            pathEffect = PathEffect.dashPathEffect(floatArrayOf(15f, 10f), 0f)
        )
        // Линия объекта - сплошная
        drawLine(
            color = objColor,
            start = objAp,
            end = objBp,
            strokeWidth = 4f
        )

        drawPointMarker(refAp, refColor, "A1", textMeasurer, labelStyle)
        drawPointMarker(refBp, refColor, "A2", textMeasurer, labelStyle)
        drawPointMarker(objAp, objColor, "B1", textMeasurer, labelStyle)
        drawPointMarker(objBp, objColor, "B2", textMeasurer, labelStyle)
    }
}

private fun androidx.compose.ui.unit.IntSize.toSize() = Size(width.toFloat(), height.toFloat())

private fun DrawScope.drawPointMarker(
    pos: Offset,
    color: Color,
    label: String,
    textMeasurer: TextMeasurer,
    labelStyle: TextStyle
) {
    drawCircle(color = Color.White, radius = 22f, center = pos)
    drawCircle(color = color, radius = 19f, center = pos)
    drawCircle(color = Color.White, radius = 5f, center = pos)
    val layout = textMeasurer.measure(label, labelStyle)
    val labelPos = Offset(pos.x + 28f, pos.y - 28f)
    drawCircle(
        color = Color.Black.copy(alpha = 0.7f),
        radius = max(layout.size.width, layout.size.height).toFloat() / 1.5f,
        center = Offset(labelPos.x + layout.size.width / 2f, labelPos.y + layout.size.height / 2f)
    )
    drawText(
        textLayoutResult = layout,
        topLeft = labelPos,
        color = Color.White
    )
}

/* ---------- Лупа ---------- */

@Composable
private fun Magnifier(
    image: ImageBitmap,
    canvasSize: Size,
    focus: Offset
) {
    val targetColor = MaterialTheme.colorScheme.primary

    Canvas(modifier = Modifier.fillMaxSize().background(Color.Black)) {
        val outSize = size.width

        // Картинка размещается так, чтобы пиксель под "focus" в основном канвасе
        // оказался в центре лупы.
        val imgW = image.width.toFloat()
        val imgH = image.height.toFloat()
        val fitScale = min(canvasSize.width / imgW, canvasSize.height / imgH)
        val mainDrawW = imgW * fitScale
        val mainDrawH = imgH * fitScale
        val mainDrawX = (canvasSize.width - mainDrawW) / 2f
        val mainDrawY = (canvasSize.height - mainDrawH) / 2f

        // Точка в координатах исходных пикселей картинки:
        val pxX = (focus.x - mainDrawX) / fitScale
        val pxY = (focus.y - mainDrawY) / fitScale

        // Зум в лупе - 2.5x от обычного fit-масштаба
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

        // Перекрестие в центре
        val cx = outSize / 2f
        val cy = outSize / 2f
        drawLine(
            color = targetColor,
            start = Offset(cx - 22f, cy),
            end = Offset(cx + 22f, cy),
            strokeWidth = 2f
        )
        drawLine(
            color = targetColor,
            start = Offset(cx, cy - 22f),
            end = Offset(cx, cy + 22f),
            strokeWidth = 2f
        )
        drawCircle(
            color = targetColor,
            radius = 10f,
            center = Offset(cx, cy),
            style = Stroke(width = 2f)
        )

        // Рамка лупы
        drawRect(
            color = targetColor,
            size = size,
            style = Stroke(width = 3f)
        )
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

private fun distance(a: Offset, b: Offset): Double {
    return hypot((a.x - b.x).toDouble(), (a.y - b.y).toDouble())
}

/* ---------- Нижняя панель управления ---------- */

@Composable
private fun BottomPanel(
    measuredMm: Double?,
    unit: LengthUnit,
    references: List<Reference>,
    selectedReference: Reference,
    onReferenceChange: (Reference) -> Unit,
    customMmText: String,
    onCustomMmChange: (String) -> Unit,
    onUnitChange: (LengthUnit) -> Unit,
    onSave: () -> Unit,
    onResetPoints: () -> Unit
) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        tonalElevation = 4.dp
    ) {
        Column(modifier = Modifier.padding(12.dp)) {

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = "Размер объекта",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    if (measuredMm != null) {
                        Text(
                            text = unit.format(measuredMm),
                            style = MaterialTheme.typography.headlineMedium,
                            fontWeight = FontWeight.Bold
                        )
                    } else {
                        Text(
                            text = "—",
                            style = MaterialTheme.typography.headlineMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                Row {
                    LengthUnit.entries.forEach { u ->
                        val sel = u == unit
                        TextButton(
                            onClick = { onUnitChange(u) },
                            colors = ButtonDefaults.textButtonColors(
                                contentColor = if (sel) MaterialTheme.colorScheme.primary
                                               else MaterialTheme.colorScheme.onSurfaceVariant
                            ),
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)
                        ) {
                            Text(
                                u.label,
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = if (sel) FontWeight.Bold else FontWeight.Normal
                            )
                        }
                    }
                }
            }

            Spacer(Modifier.height(8.dp))

            Text(
                text = "Эталон:",
                style = MaterialTheme.typography.labelMedium
            )
            Spacer(Modifier.height(4.dp))
            LazyRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                items(references.size) { idx ->
                    val r = references[idx]
                    val sel = r.label == selectedReference.label
                    FilterChip(
                        selected = sel,
                        onClick = { onReferenceChange(r) },
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
                    value = customMmText,
                    onValueChange = onCustomMmChange,
                    label = { Text("Размер эталона, мм") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.fillMaxWidth()
                )
            }

            Spacer(Modifier.height(12.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedButton(onClick = onResetPoints) {
                    Icon(Icons.Filled.Refresh, contentDescription = null,
                        modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Точки")
                }
                Button(
                    onClick = onSave,
                    enabled = measuredMm != null,
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(Icons.Filled.Bookmark, contentDescription = null,
                        modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Сохранить")
                }
            }
        }
    }
}

@Composable
private fun SaveDialog(
    measuredMm: Double,
    unit: LengthUnit,
    referenceLabel: String,
    referenceMm: Double,
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
                    text = unit.format(measuredMm),
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = "по эталону: $referenceLabel (${"%.1f".format(referenceMm)} мм)",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(16.dp))
                OutlinedTextField(
                    value = note,
                    onValueChange = { note = it },
                    label = { Text("Заметка (необязательно)") },
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
