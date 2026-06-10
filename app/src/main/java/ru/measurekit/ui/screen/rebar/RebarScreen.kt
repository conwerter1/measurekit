package ru.measurekit.ui.screen.rebar

import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AutoFixHigh
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.ZoomIn
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
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
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import ru.measurekit.data.MeasurementType
import ru.measurekit.data.MeasurementsRepository
import ru.measurekit.domain.rebar.MaterialSpec
import ru.measurekit.ml.RebarDetector
import java.io.File
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Модуль "Связка / штабель".
 *
 * Поддерживаемые типы материала:
 *  - Арматура (специализированная модель Huawei rebar, mAP50 = 98.4%)
 *  - Труба стальная ВГП (мульти-модель + classFilter=pipe_end)
 *  - Профильная труба (мульти-модель + classFilter=pipe_end)
 *  - Кругляк / лес (мульти-модель + classFilter=log_end, расчёт объёма м³)
 *  - Своё (мульти-модель без фильтра + ручная погонная масса)
 *
 * Архитектура координат:
 *   Все детекции хранятся в КООРДИНАТАХ ИСХОДНОГО ИЗОБРАЖЕНИЯ (пиксели).
 *   При отрисовке преобразуем через текущий transform (scale + offset).
 *   Это позволяет произвольно зумить и панорамировать без пересчёта данных.
 */

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RebarScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val repo = remember { MeasurementsRepository.get(ctx) }
    val scope = rememberCoroutineScope()
    val snackbarHost = remember { SnackbarHostState() }

    var imageBitmap by remember { mutableStateOf<ImageBitmap?>(null) }
    var loadError by remember { mutableStateOf<String?>(null) }
    var isDetecting by remember { mutableStateOf(false) }

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
                title = { Text("Связка / штабель") },
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
                    isDetecting = isDetecting,
                    onDetect = { material, onComplete ->
                        isDetecting = true
                        scope.launch {
                            val detections = withContext(Dispatchers.Default) {
                                runDetection(ctx, current, material)
                            }
                            isDetecting = false
                            onComplete(detections)
                            snackbarHost.showSnackbar("Найдено: ${detections.size} концов")
                        }
                    },
                    onSave = { result, count, material, note, markup, original ->
                        scope.launch {
                            val title = "${material.label}: $count шт, ${"%.1f".format(result.nominal)} ${result.unit}"
                            val noteFull = buildString {
                                append(title)
                                if (note.isNotBlank()) append(" — $note")
                            }
                            repo.save(
                                type = MeasurementType.REBAR,
                                valueRaw = count.toDouble(),
                                unit = "шт",
                                note = noteFull,
                                markupBitmap = markup,
                                originalBitmap = original
                            )
                            snackbarHost.showSnackbar("Сохранено: $title")
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
    val file = File.createTempFile("rebar_", ".jpg", cacheDir)
    return FileProvider.getUriForFile(ctx, "${ctx.packageName}.fileprovider", file)
}

/**
 * Запуск детекции с правильной моделью и фильтром по классу.
 */
private suspend fun runDetection(
    ctx: Context,
    image: ImageBitmap,
    material: MaterialSpec.MaterialType,
): List<RawDetection> = withContext(Dispatchers.Default) {
    val detector = RebarDetectorHolder.getForMaterial(ctx, material)
    val androidBitmap = image.asAndroidBitmap()
    // Фильтр класса: -1 у CUSTOM = принимаем все классы; иначе фильтруем
    val classFilter = if (material.targetClass < 0) null else material.targetClass
    val rawDetections = detector.detect(androidBitmap, classFilter = classFilter)
    rawDetections.map { d ->
        val r = (d.w + d.h) / 4f
        RawDetection(d.cx, d.cy, r, d.confidence)
    }
}

/* ---------- Стартовый экран ---------- */

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
            text = "Подсчёт концов в связке",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = "Сфотографируйте торец связки или штабеля. " +
                    "Алгоритм автоматически найдёт концы стержней, труб или брёвен.",
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

/* ---------- ТИПЫ КООРДИНАТ ---------- */

data class RawDetection(
    val cx: Float, val cy: Float, val r: Float, val confidence: Float = 1f,
)

/** Точка-кружок в координатах ИСХОДНОГО ФОТО (пиксели). */
data class ImagePoint(
    val cxImg: Float, val cyImg: Float, val rImg: Float, val confidence: Float = 1f,
)

data class ViewTransform(
    val scale: Float, val offsetX: Float, val offsetY: Float,
) {
    fun imageToCanvas(p: Offset): Offset =
        Offset(p.x * scale + offsetX, p.y * scale + offsetY)
    fun canvasToImage(p: Offset): Offset =
        Offset((p.x - offsetX) / scale, (p.y - offsetY) / scale)
}

private fun computeFitTransform(imgW: Float, imgH: Float, canvasSize: Size): ViewTransform {
    if (canvasSize == Size.Zero || imgW <= 0 || imgH <= 0) return ViewTransform(1f, 0f, 0f)
    val scale = min(canvasSize.width / imgW, canvasSize.height / imgH)
    val drawW = imgW * scale
    val drawH = imgH * scale
    val offsetX = (canvasSize.width - drawW) / 2f
    val offsetY = (canvasSize.height - drawH) / 2f
    return ViewTransform(scale, offsetX, offsetY)
}

/* ---------- Главный экран измерения ---------- */

@Composable
private fun MeasureView(
    image: ImageBitmap,
    isDetecting: Boolean,
    onDetect: (material: MaterialSpec.MaterialType,
               onComplete: (List<RawDetection>) -> Unit) -> Unit,
    onSave: (result: MaterialSpec.CalcResult, count: Int,
             material: MaterialSpec.MaterialType, note: String,
             markup: android.graphics.Bitmap, original: android.graphics.Bitmap) -> Unit
) {
    val points = remember { mutableStateListOf<ImagePoint>() }

    // Состояние выбранных параметров
    var material by remember { mutableStateOf(MaterialSpec.MaterialType.REBAR) }
    var rebarClass by remember { mutableStateOf(MaterialSpec.RebarClass.A500C) }
    var rebarDiameter by remember { mutableStateOf(MaterialSpec.REBAR_DIAMETERS.first { it.mm == 16 }) }
    var pipeSize by remember { mutableStateOf(MaterialSpec.PIPE_STEEL_SIZES.first()) }
    var profileSize by remember { mutableStateOf(MaterialSpec.PROFILE_SIZES.first()) }
    var logSize by remember { mutableStateOf(MaterialSpec.LOG_DIAMETERS.first { it.avgDiameterCm == 20 }) }
    var customMassText by remember { mutableStateOf("1.0") }
    var lengthText by remember { mutableStateOf("11.7") }

    var showSaveDialog by remember { mutableStateOf(false) }
    var showFullscreen by remember { mutableStateOf(false) }

    // При смене материала меняем длину по умолчанию
    LaunchedEffect(material) {
        lengthText = MaterialSpec.standardLength(material).toString()
        // Очищаем точки если детектор будет другой - чтобы не перепутались координаты
        points.clear()
    }

    val lengthM = lengthText.replace(',', '.').toDoubleOrNull() ?: 0.0

    val calcResult: MaterialSpec.CalcResult? = remember(
        material, points.size, lengthM,
        rebarDiameter, pipeSize, profileSize, logSize, customMassText
    ) {
        MaterialSpec.calculate(
            material = material,
            count = points.size,
            lengthM = lengthM,
            rebarDiameterMm = rebarDiameter.mm,
            pipeSize = pipeSize,
            profileSize = profileSize,
            logSize = logSize,
            customMassKgPerM = customMassText.replace(',', '.').toDoubleOrNull(),
        )
    }

    Column(modifier = Modifier.fillMaxSize()) {
        Box(modifier = Modifier.weight(1f).fillMaxWidth().background(Color.Black)) {
            DetectionCanvas(
                image = image,
                points = points,
                onAddPoint = { p -> points.add(p) },
                onRemovePoint = { idx -> if (idx in points.indices) points.removeAt(idx) },
            )

            FloatingActionButton(
                onClick = { showFullscreen = true },
                modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp),
                containerColor = MaterialTheme.colorScheme.tertiaryContainer,
                contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
            ) {
                Icon(Icons.Filled.ZoomIn, contentDescription = "Увеличить на весь экран")
            }

            Surface(
                modifier = Modifier.align(Alignment.TopStart).padding(12.dp),
                color = Color.Black.copy(alpha = 0.7f),
                shape = MaterialTheme.shapes.medium
            ) {
                Text(
                    text = "Концов: ${points.size}",
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                    style = MaterialTheme.typography.titleMedium,
                    color = Color.White,
                    fontWeight = FontWeight.Bold
                )
            }

            if (isDetecting) {
                Box(
                    modifier = Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.5f)),
                    contentAlignment = Alignment.Center
                ) {
                    Surface(
                        color = MaterialTheme.colorScheme.surface,
                        shape = MaterialTheme.shapes.medium
                    ) {
                        Column(
                            modifier = Modifier.padding(20.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            CircularProgressIndicator()
                            Spacer(Modifier.height(12.dp))
                            Text("Распознавание...")
                        }
                    }
                }
            }
        }

        BottomPanel(
            material = material,
            onMaterialChange = { material = it },
            rebarClass = rebarClass,
            onRebarClassChange = { rebarClass = it },
            rebarDiameter = rebarDiameter,
            onRebarDiameterChange = { rebarDiameter = it },
            pipeSize = pipeSize,
            onPipeSizeChange = { pipeSize = it },
            profileSize = profileSize,
            onProfileSizeChange = { profileSize = it },
            logSize = logSize,
            onLogSizeChange = { logSize = it },
            customMassText = customMassText,
            onCustomMassChange = { customMassText = it },
            lengthText = lengthText,
            onLengthChange = { lengthText = it },
            calcResult = calcResult,
            isDetecting = isDetecting,
            onDetect = {
                onDetect(material) { result ->
                    points.clear()
                    for (d in result) {
                        points.add(ImagePoint(d.cx, d.cy, d.r, d.confidence))
                    }
                }
            },
            onClear = { points.clear() },
            onSave = { showSaveDialog = true }
        )
    }

    if (showFullscreen) {
        FullscreenZoomView(
            image = image,
            points = points,
            onAddPoint = { p -> points.add(p) },
            onRemovePoint = { idx -> if (idx in points.indices) points.removeAt(idx) },
            onClose = { showFullscreen = false }
        )
    }

    if (showSaveDialog && calcResult != null) {
        SaveDialog(
            count = points.size,
            material = material,
            calcResult = calcResult,
            onDismiss = { showSaveDialog = false },
            onConfirm = { note ->
                val markup = ru.measurekit.util.MarkupRenderer.renderRebarMarkup(
                    sourceImage = image,
                    points = points.toList(),
                    valueText = "${points.size} шт",
                    bundleInfo = "${material.label} — ${"%.1f".format(calcResult.nominal)} ${calcResult.unit}"
                )
                val original = ru.measurekit.util.MarkupRenderer.imageBitmapToBitmap(image)
                onSave(calcResult, points.size, material, note, markup, original)
                showSaveDialog = false
            }
        )
    }
}

/* ---------- Канвас для основного экрана ---------- */

@Composable
private fun DetectionCanvas(
    image: ImageBitmap,
    points: List<ImagePoint>,
    onAddPoint: (ImagePoint) -> Unit,
    onRemovePoint: (Int) -> Unit,
) {
    val markerColor = MaterialTheme.colorScheme.primary
    val markerStroke = Color.White
    val textMeasurer = rememberTextMeasurer()
    val labelStyle = TextStyle(fontSize = 9.sp, fontWeight = FontWeight.Bold, color = Color.White)
    val curPoints by rememberUpdatedState(points)
    var canvasSize by remember { mutableStateOf(Size.Zero) }

    Canvas(
        modifier = Modifier.fillMaxSize().pointerInput(Unit) {
            detectTapGestures(onTap = { offset ->
                val tf = computeFitTransform(image.width.toFloat(), image.height.toFloat(), canvasSize)
                val imgPt = tf.canvasToImage(offset)
                val hitIdx = findHitIndex(imgPt, curPoints, tf.scale)
                if (hitIdx >= 0) {
                    onRemovePoint(hitIdx)
                } else {
                    if (imgPt.x < 0 || imgPt.y < 0 ||
                        imgPt.x > image.width || imgPt.y > image.height) return@detectTapGestures
                    val medianR = if (curPoints.isEmpty()) 25f
                    else curPoints.map { it.rImg }.sorted()[curPoints.size / 2]
                    onAddPoint(ImagePoint(imgPt.x, imgPt.y, medianR, 1f))
                }
            })
        }
    ) {
        canvasSize = size
        val tf = computeFitTransform(image.width.toFloat(), image.height.toFloat(), size)
        val drawW = image.width * tf.scale
        val drawH = image.height * tf.scale
        drawImage(
            image = image,
            dstOffset = IntOffset(tf.offsetX.roundToInt(), tf.offsetY.roundToInt()),
            dstSize = IntSize(drawW.roundToInt(), drawH.roundToInt())
        )
        points.forEachIndexed { idx, p ->
            val canvasPt = tf.imageToCanvas(Offset(p.cxImg, p.cyImg))
            val canvasR = max(p.rImg * tf.scale, 14f)
            drawCircle(color = markerColor.copy(alpha = 0.25f), radius = canvasR, center = canvasPt)
            drawCircle(color = markerStroke, radius = canvasR, center = canvasPt, style = Stroke(width = 2f))
            drawCircle(color = markerColor, radius = canvasR - 1.5f, center = canvasPt, style = Stroke(width = 2f))
            if (canvasR > 18f) {
                val layout = textMeasurer.measure("${idx + 1}", labelStyle)
                drawText(
                    textLayoutResult = layout,
                    topLeft = Offset(canvasPt.x - layout.size.width / 2f, canvasPt.y - layout.size.height / 2f),
                    color = Color.White
                )
            }
        }
    }
}

private fun findHitIndex(imgPt: Offset, points: List<ImagePoint>, scale: Float): Int {
    if (points.isEmpty()) return -1
    var bestIdx = -1
    var bestDist = Float.MAX_VALUE
    points.forEachIndexed { idx, p ->
        val distImg = hypot(imgPt.x - p.cxImg, imgPt.y - p.cyImg)
        val distScreen = distImg * scale
        val canvasR = max(p.rImg * scale, 14f)
        val hitRadiusScreen = max(canvasR, 25f)
        if (distScreen < hitRadiusScreen && distScreen < bestDist) {
            bestDist = distScreen
            bestIdx = idx
        }
    }
    return bestIdx
}

/* ---------- Полноэкранный режим с зумом (без изменений) ---------- */

@Composable
private fun FullscreenZoomView(
    image: ImageBitmap,
    points: List<ImagePoint>,
    onAddPoint: (ImagePoint) -> Unit,
    onRemovePoint: (Int) -> Unit,
    onClose: () -> Unit,
) {
    Dialog(
        onDismissRequest = onClose,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)
    ) {
        Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
            ZoomableCanvas(image, points, onAddPoint, onRemovePoint)
            Row(
                modifier = Modifier.align(Alignment.TopCenter).fillMaxWidth()
                    .background(Color.Black.copy(alpha = 0.6f)).padding(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onClose) {
                    Icon(Icons.Filled.Close, contentDescription = "Закрыть", tint = Color.White)
                }
                Text("Концов: ${points.size}",
                    style = MaterialTheme.typography.titleMedium, color = Color.White,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f).padding(start = 8.dp))
                Text("тап = добавить/удалить, 2 пальца = зум",
                    style = MaterialTheme.typography.labelSmall,
                    color = Color.White.copy(alpha = 0.7f),
                    modifier = Modifier.padding(end = 8.dp))
            }
        }
    }
}

@Composable
private fun ZoomableCanvas(
    image: ImageBitmap, points: List<ImagePoint>,
    onAddPoint: (ImagePoint) -> Unit, onRemovePoint: (Int) -> Unit,
) {
    var canvasSize by remember { mutableStateOf(Size.Zero) }
    var userScale by remember { mutableStateOf(1f) }
    var userOffsetX by remember { mutableStateOf(0f) }
    var userOffsetY by remember { mutableStateOf(0f) }

    fun currentTransform(canvasSz: Size): ViewTransform {
        val fit = computeFitTransform(image.width.toFloat(), image.height.toFloat(), canvasSz)
        val totalScale = fit.scale * userScale
        return ViewTransform(
            scale = totalScale,
            offsetX = fit.offsetX * userScale + userOffsetX + (canvasSz.width / 2f) * (1f - userScale),
            offsetY = fit.offsetY * userScale + userOffsetY + (canvasSz.height / 2f) * (1f - userScale),
        )
    }

    fun clampUser(canvasSz: Size) {
        userScale = userScale.coerceIn(0.8f, 8f)
        val maxShift = max(canvasSz.width, canvasSz.height) * userScale
        userOffsetX = userOffsetX.coerceIn(-maxShift, maxShift)
        userOffsetY = userOffsetY.coerceIn(-maxShift, maxShift)
    }

    val markerColor = MaterialTheme.colorScheme.primary
    val markerStroke = Color.White
    val textMeasurer = rememberTextMeasurer()
    val labelStyle = TextStyle(fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Color.White)
    val curPoints by rememberUpdatedState(points)

    Canvas(
        modifier = Modifier.fillMaxSize()
            .pointerInput(Unit) {
                detectTransformGestures { centroid, pan, zoom, _ ->
                    val newScale = (userScale * zoom).coerceIn(0.8f, 8f)
                    val actualZoom = newScale / userScale
                    val cx = canvasSize.width / 2f
                    val cy = canvasSize.height / 2f
                    val dx = centroid.x - cx
                    val dy = centroid.y - cy
                    userOffsetX = userOffsetX * actualZoom - dx * (actualZoom - 1f) + pan.x
                    userOffsetY = userOffsetY * actualZoom - dy * (actualZoom - 1f) + pan.y
                    userScale = newScale
                    clampUser(canvasSize)
                }
            }
            .pointerInput(Unit) {
                detectTapGestures(
                    onTap = { offset ->
                        val tf = currentTransform(canvasSize)
                        val imgPt = tf.canvasToImage(offset)
                        val hitIdx = findHitIndex(imgPt, curPoints, tf.scale)
                        if (hitIdx >= 0) {
                            onRemovePoint(hitIdx)
                        } else {
                            if (imgPt.x < 0 || imgPt.y < 0 ||
                                imgPt.x > image.width || imgPt.y > image.height) return@detectTapGestures
                            val medianR = if (curPoints.isEmpty()) 25f
                            else curPoints.map { it.rImg }.sorted()[curPoints.size / 2]
                            onAddPoint(ImagePoint(imgPt.x, imgPt.y, medianR, 1f))
                        }
                    },
                    onDoubleTap = { offset ->
                        if (userScale > 1.05f) {
                            userScale = 1f
                            userOffsetX = 0f
                            userOffsetY = 0f
                        } else {
                            val cx = canvasSize.width / 2f
                            val cy = canvasSize.height / 2f
                            val dx = offset.x - cx
                            val dy = offset.y - cy
                            val factor = 2f
                            userOffsetX = userOffsetX * factor - dx * (factor - 1f)
                            userOffsetY = userOffsetY * factor - dy * (factor - 1f)
                            userScale = 2f
                            clampUser(canvasSize)
                        }
                    }
                )
            }
    ) {
        canvasSize = size
        val tf = currentTransform(size)
        val drawW = image.width * tf.scale
        val drawH = image.height * tf.scale
        drawImage(image = image,
            dstOffset = IntOffset(tf.offsetX.roundToInt(), tf.offsetY.roundToInt()),
            dstSize = IntSize(drawW.roundToInt(), drawH.roundToInt()))

        points.forEachIndexed { idx, p ->
            val canvasPt = tf.imageToCanvas(Offset(p.cxImg, p.cyImg))
            val canvasR = max(p.rImg * tf.scale, 14f)
            drawCircle(color = markerColor.copy(alpha = 0.25f), radius = canvasR, center = canvasPt)
            drawCircle(color = markerStroke, radius = canvasR, center = canvasPt, style = Stroke(width = 2.5f))
            drawCircle(color = markerColor, radius = canvasR - 1.5f, center = canvasPt, style = Stroke(width = 2.5f))
            if (canvasR > 18f) {
                val layout = textMeasurer.measure("${idx + 1}", labelStyle)
                drawText(
                    textLayoutResult = layout,
                    topLeft = Offset(canvasPt.x - layout.size.width / 2f, canvasPt.y - layout.size.height / 2f),
                    color = Color.White
                )
            }
        }
    }
}

/* ---------- Нижняя панель (с типом материала) ---------- */

@Composable
private fun BottomPanel(
    material: MaterialSpec.MaterialType,
    onMaterialChange: (MaterialSpec.MaterialType) -> Unit,
    rebarClass: MaterialSpec.RebarClass,
    onRebarClassChange: (MaterialSpec.RebarClass) -> Unit,
    rebarDiameter: MaterialSpec.Diameter,
    onRebarDiameterChange: (MaterialSpec.Diameter) -> Unit,
    pipeSize: MaterialSpec.PipeSize,
    onPipeSizeChange: (MaterialSpec.PipeSize) -> Unit,
    profileSize: MaterialSpec.ProfileSize,
    onProfileSizeChange: (MaterialSpec.ProfileSize) -> Unit,
    logSize: MaterialSpec.LogSize,
    onLogSizeChange: (MaterialSpec.LogSize) -> Unit,
    customMassText: String,
    onCustomMassChange: (String) -> Unit,
    lengthText: String,
    onLengthChange: (String) -> Unit,
    calcResult: MaterialSpec.CalcResult?,
    isDetecting: Boolean,
    onDetect: () -> Unit,
    onClear: () -> Unit,
    onSave: () -> Unit
) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh, tonalElevation = 4.dp) {
        Column(modifier = Modifier.padding(12.dp)) {

            // Результат расчёта
            if (calcResult != null) {
                Column {
                    Text(
                        text = if (calcResult is MaterialSpec.VolumeResult) "Объём" else "Масса",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = "${"%.${if (calcResult is MaterialSpec.VolumeResult) 4 else 1}f".format(calcResult.nominal)} ${calcResult.unit}",
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = calcResult.description,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = "Диапазон ±${(calcResult.tolerance * 100).toInt()}%: " +
                                "${"%.${if (calcResult is MaterialSpec.VolumeResult) 4 else 1}f".format(calcResult.min)}–" +
                                "${"%.${if (calcResult is MaterialSpec.VolumeResult) 4 else 1}f".format(calcResult.max)} ${calcResult.unit}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Spacer(Modifier.height(8.dp))
            }

            // ВЫБОР МАТЕРИАЛА (главный, всегда виден)
            Text("Материал:", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(4.dp))
            LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                items(MaterialSpec.MaterialType.all().size) { idx ->
                    val m = MaterialSpec.MaterialType.all()[idx]
                    FilterChip(
                        selected = m == material,
                        onClick = { onMaterialChange(m) },
                        label = { Text(m.label, style = MaterialTheme.typography.labelSmall) }
                    )
                }
            }
            Spacer(Modifier.height(8.dp))

            // Параметры в зависимости от выбранного типа материала
            when (material) {
                MaterialSpec.MaterialType.REBAR -> {
                    Text("Класс:", style = MaterialTheme.typography.labelMedium)
                    Spacer(Modifier.height(4.dp))
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        items(MaterialSpec.RebarClass.all().size) { idx ->
                            val cls = MaterialSpec.RebarClass.all()[idx]
                            FilterChip(
                                selected = cls == rebarClass,
                                onClick = { onRebarClassChange(cls) },
                                label = { Text(cls.label, style = MaterialTheme.typography.labelSmall) }
                            )
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    Text("Диаметр:", style = MaterialTheme.typography.labelMedium)
                    Spacer(Modifier.height(4.dp))
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        items(MaterialSpec.REBAR_DIAMETERS.size) { idx ->
                            val d = MaterialSpec.REBAR_DIAMETERS[idx]
                            FilterChip(
                                selected = d.mm == rebarDiameter.mm,
                                onClick = { onRebarDiameterChange(d) },
                                label = { Text(d.label, style = MaterialTheme.typography.labelSmall) }
                            )
                        }
                    }
                }
                MaterialSpec.MaterialType.PIPE_STEEL -> {
                    Text("Размер ВГП (ГОСТ 3262):", style = MaterialTheme.typography.labelMedium)
                    Spacer(Modifier.height(4.dp))
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        items(MaterialSpec.PIPE_STEEL_SIZES.size) { idx ->
                            val s = MaterialSpec.PIPE_STEEL_SIZES[idx]
                            FilterChip(
                                selected = s == pipeSize,
                                onClick = { onPipeSizeChange(s) },
                                label = { Text(s.label, style = MaterialTheme.typography.labelSmall) }
                            )
                        }
                    }
                }
                MaterialSpec.MaterialType.PIPE_PROFILE -> {
                    Text("Размер профильной (ГОСТ 30245):", style = MaterialTheme.typography.labelMedium)
                    Spacer(Modifier.height(4.dp))
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        items(MaterialSpec.PROFILE_SIZES.size) { idx ->
                            val s = MaterialSpec.PROFILE_SIZES[idx]
                            FilterChip(
                                selected = s == profileSize,
                                onClick = { onProfileSizeChange(s) },
                                label = { Text(s.label, style = MaterialTheme.typography.labelSmall) }
                            )
                        }
                    }
                }
                MaterialSpec.MaterialType.ROUND_LOG -> {
                    Text("Диаметр (в верхнем отрубе):", style = MaterialTheme.typography.labelMedium)
                    Spacer(Modifier.height(4.dp))
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        items(MaterialSpec.LOG_DIAMETERS.size) { idx ->
                            val s = MaterialSpec.LOG_DIAMETERS[idx]
                            FilterChip(
                                selected = s == logSize,
                                onClick = { onLogSizeChange(s) },
                                label = { Text(s.label, style = MaterialTheme.typography.labelSmall) }
                            )
                        }
                    }
                }
                MaterialSpec.MaterialType.CUSTOM -> {
                    OutlinedTextField(
                        value = customMassText,
                        onValueChange = onCustomMassChange,
                        label = { Text("Погонная масса, кг/м") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
            Spacer(Modifier.height(8.dp))

            OutlinedTextField(
                value = lengthText,
                onValueChange = onLengthChange,
                label = { Text("Длина, м") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(12.dp))

            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onDetect, enabled = !isDetecting, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Filled.AutoFixHigh, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Распознать")
                }
                OutlinedButton(onClick = onClear, enabled = calcResult?.count ?: 0 > 0) {
                    Icon(Icons.Filled.Delete, contentDescription = null, modifier = Modifier.size(18.dp))
                }
                Button(onClick = onSave, enabled = calcResult != null) {
                    Icon(Icons.Filled.Bookmark, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Сохр")
                }
            }
        }
    }
}

/* ---------- Диалог сохранения ---------- */

@Composable
private fun SaveDialog(
    count: Int,
    material: MaterialSpec.MaterialType,
    calcResult: MaterialSpec.CalcResult,
    onDismiss: () -> Unit,
    onConfirm: (note: String) -> Unit
) {
    var note by remember { mutableStateOf("") }
    val precision = if (calcResult is MaterialSpec.VolumeResult) 4 else 1

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Сохранить замер") },
        text = {
            Column {
                Text("$count концов",
                    style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                Text(material.label, style = MaterialTheme.typography.bodyMedium)
                Text("${"%.${precision}f".format(calcResult.nominal)} ${calcResult.unit}",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.primary)
                Text("± ${(calcResult.tolerance * 100).toInt()}%",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(16.dp))
                OutlinedTextField(
                    value = note, onValueChange = { note = it },
                    label = { Text("Заметка (партия, поставщик, ярлык...)") },
                    minLines = 2, modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = { TextButton(onClick = { onConfirm(note.trim()) }) { Text("Сохранить") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена") } }
    )
}
