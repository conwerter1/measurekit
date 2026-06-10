package ru.measurekit.ui.screen.audit

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import ru.measurekit.data.MeasurementType
import ru.measurekit.data.MeasurementsRepository
import ru.measurekit.domain.audit.ChecklistTemplates
import ru.measurekit.ui.common.InstructionCard
import ru.measurekit.ui.common.Instructions
import ru.measurekit.util.GpsHelper
import java.io.File
import java.io.FileOutputStream

/**
 * Универсальный экран чек-листа.
 *
 * Принимает параметром тип шаблона (РБУ/ДСУ/Карьер) и показывает все его пункты.
 *
 * Workflow:
 *   1. Сверху - реквизиты проверки (объект, организация, аудитор)
 *   2. Список разделов с пунктами в LazyColumn
 *   3. По каждому пункту: вопрос + 4 кнопки (OK/Замечание/Нарушение/N/A) +
 *      поле комментария + кнопка "Снять фото"
 *   4. Внизу - кнопка "Завершить и создать PDF"
 *   5. После завершения - переход на экран превью с возможностью поделиться
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChecklistScreen(
    templateKind: ChecklistTemplates.TemplateKind,
    onBack: () -> Unit,
    onComplete: (savedRecordId: Long) -> Unit,
) {
    val ctx = LocalContext.current
    val repo = remember { MeasurementsRepository.get(ctx) }
    val scope = rememberCoroutineScope()
    val snackbarHost = remember { SnackbarHostState() }

    // Реквизиты проверки
    var objectName by remember { mutableStateOf("") }
    var organizationName by remember { mutableStateOf("") }
    var auditorName by remember { mutableStateOf("") }
    var commonNote by remember { mutableStateOf("") }

    // GPS - получим один раз при старте
    var gpsCoords by remember { mutableStateOf<GpsHelper.Coords?>(null) }
    LaunchedEffect(Unit) {
        gpsCoords = GpsHelper.getLastKnown(ctx)
    }

    // Состояние ответов: itemId -> ItemAnswer (mutable!)
    val template = remember(templateKind) { ChecklistTemplates.byKind(templateKind) }
    val sections = remember(template) { template.sections }
    val allItems = remember(sections) { sections.flatMap { it.items } }

    val answersState = remember(templateKind) {
        mutableStateMapOf<String, ChecklistTemplates.ItemAnswer>().apply {
            for (it in allItems) {
                put(it.id, ChecklistTemplates.ItemAnswer(itemId = it.id))
            }
        }
    }

    // Активный пункт для съёмки фото (ставим перед запуском камеры)
    var photoTargetItemId by remember { mutableStateOf<String?>(null) }
    var pendingCameraUri by remember { mutableStateOf<Uri?>(null) }

    val cameraLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.TakePicture()
    ) { success ->
        val uri = pendingCameraUri
        val targetId = photoTargetItemId
        if (success && uri != null && targetId != null) {
            scope.launch {
                val saved = withContext(Dispatchers.IO) {
                    saveBitmapFromCacheToFiles(ctx, uri, "checklist_${targetId}")
                }
                val current = answersState[targetId] ?: ChecklistTemplates.ItemAnswer(itemId = targetId)
                answersState[targetId] = current.copy(
                    photoPath = saved,
                    timestamp = System.currentTimeMillis(),
                    gpsLat = gpsCoords?.latitude,
                    gpsLon = gpsCoords?.longitude,
                )
            }
        }
        photoTargetItemId = null
    }

    val galleryLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        val targetId = photoTargetItemId
        if (uri != null && targetId != null) {
            scope.launch {
                val saved = withContext(Dispatchers.IO) {
                    saveBitmapFromUriToFiles(ctx, uri, "checklist_${targetId}")
                }
                val current = answersState[targetId] ?: ChecklistTemplates.ItemAnswer(itemId = targetId)
                answersState[targetId] = current.copy(
                    photoPath = saved,
                    timestamp = System.currentTimeMillis(),
                    gpsLat = gpsCoords?.latitude,
                    gpsLon = gpsCoords?.longitude,
                )
            }
        }
        photoTargetItemId = null
    }

    // Сводная статистика для быстрого обзора прогресса
    val stats = remember(answersState.values.toList()) {
        val total = allItems.size
        val ok = answersState.count { it.value.answer == ChecklistTemplates.Answer.OK }
        val notes = answersState.count { it.value.answer == ChecklistTemplates.Answer.NOTE }
        val fails = answersState.count { it.value.answer == ChecklistTemplates.Answer.FAIL }
        val na = answersState.count { it.value.answer == ChecklistTemplates.Answer.NA }
        val pending = total - ok - notes - fails - na
        Stats(total, ok, notes, fails, na, pending)
    }

    // Выбираем инструкцию и moduleKey по типу шаблона:
    // у каждого чек-листа свой ключ — "свёрнутость" запоминается отдельно
    // (чтобы инструкция РБУ не мешала пользователю видеть инструкцию ДСУ).
    val instructionSections = when (templateKind) {
        ChecklistTemplates.TemplateKind.RBU    -> Instructions.CHECKLIST_RBU
        ChecklistTemplates.TemplateKind.DSU    -> Instructions.CHECKLIST_DSU
        ChecklistTemplates.TemplateKind.QUARRY -> Instructions.CHECKLIST_QUARRY
    }
    val instructionModuleKey = "checklist_${templateKind.code.lowercase()}"

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Чек-лист: ${templateKind.label}") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Назад")
                    }
                }
            )
        },
        snackbarHost = { SnackbarHost(snackbarHost) }
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding).padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // -------- Краткая инструкция (раскрывающаяся) --------
            item {
                InstructionCard(
                    moduleKey = instructionModuleKey,
                    title = "Как пройти проверку",
                    sections = instructionSections,
                )
            }

            // -------- Реквизиты проверки --------
            item {
                Card {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Text("Реквизиты проверки",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.SemiBold)
                        Spacer(Modifier.height(8.dp))
                        OutlinedTextField(
                            value = objectName,
                            onValueChange = { objectName = it },
                            label = { Text("Объект (например: РБУ Akkuyu уч-к 3)") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                        Spacer(Modifier.height(4.dp))
                        OutlinedTextField(
                            value = organizationName,
                            onValueChange = { organizationName = it },
                            label = { Text("Организация") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                        Spacer(Modifier.height(4.dp))
                        OutlinedTextField(
                            value = auditorName,
                            onValueChange = { auditorName = it },
                            label = { Text("Аудитор (Ф.И.О.)") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                        Spacer(Modifier.height(8.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Filled.LocationOn, contentDescription = null,
                                modifier = Modifier.size(14.dp),
                                tint = if (gpsCoords != null) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.onSurfaceVariant)
                            Spacer(Modifier.width(4.dp))
                            Text(GpsHelper.format(gpsCoords),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }

            // -------- Сводная статистика --------
            item {
                Card(colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
                )) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Text("Прогресс: ${stats.completed} из ${stats.total}",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold)
                        Spacer(Modifier.height(6.dp))
                        LinearProgressIndicator(
                            progress = { stats.completed.toFloat() / stats.total.coerceAtLeast(1) },
                            modifier = Modifier.fillMaxWidth().height(6.dp)
                        )
                        Spacer(Modifier.height(8.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            StatBadge("✓", stats.ok, Color(0xFF2E7D32))
                            StatBadge("⚠", stats.notes, Color(0xFFEF6C00))
                            StatBadge("✗", stats.fails, Color(0xFFC62828))
                            StatBadge("N/A", stats.na, Color(0xFF757575))
                            StatBadge("?", stats.pending, Color(0xFFBDBDBD))
                        }
                    }
                }
            }

            // -------- Разделы и пункты --------
            for (section in sections) {
                item(key = "section_${section.name}") {
                    Spacer(Modifier.height(8.dp))
                    Text(section.name,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = 4.dp, vertical = 4.dp))
                }
                for (item in section.items) {
                    item(key = "item_${item.id}") {
                        ChecklistItemCard(
                            item = item,
                            answer = answersState[item.id] ?: ChecklistTemplates.ItemAnswer(itemId = item.id),
                            onAnswerChange = { newAnswer ->
                                answersState[item.id] = (answersState[item.id]
                                    ?: ChecklistTemplates.ItemAnswer(itemId = item.id))
                                    .copy(
                                        answer = newAnswer,
                                        timestamp = System.currentTimeMillis(),
                                        gpsLat = gpsCoords?.latitude,
                                        gpsLon = gpsCoords?.longitude,
                                    )
                            },
                            onCommentChange = { newComment ->
                                answersState[item.id] = (answersState[item.id]
                                    ?: ChecklistTemplates.ItemAnswer(itemId = item.id))
                                    .copy(comment = newComment)
                            },
                            onTakePhoto = {
                                photoTargetItemId = item.id
                                val uri = createTempImageUri(ctx, "checklist_${item.id}")
                                pendingCameraUri = uri
                                cameraLauncher.launch(uri)
                            },
                            onPickPhoto = {
                                photoTargetItemId = item.id
                                galleryLauncher.launch("image/*")
                            },
                        )
                    }
                }
            }

            // -------- Общие комментарии и кнопка завершения --------
            item {
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = commonNote,
                    onValueChange = { commonNote = it },
                    label = { Text("Общие примечания аудитора") },
                    minLines = 3,
                    modifier = Modifier.fillMaxWidth()
                )
            }

            item {
                Button(
                    onClick = {
                        scope.launch {
                            val recordId = saveChecklistRecord(
                                ctx = ctx,
                                repo = repo,
                                templateKind = templateKind,
                                allItems = allItems,
                                answersState = answersState,
                                objectName = objectName,
                                organizationName = organizationName,
                                auditorName = auditorName,
                                commonNote = commonNote,
                                gpsCoords = gpsCoords,
                                stats = stats,
                            )
                            snackbarHost.showSnackbar("Чек-лист сохранён")
                            onComplete(recordId)
                        }
                    },
                    modifier = Modifier.fillMaxWidth().height(52.dp)
                ) {
                    Icon(Icons.Filled.Description, contentDescription = null,
                        modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Завершить и создать PDF-акт",
                        style = MaterialTheme.typography.titleMedium)
                }
            }
            item { Spacer(Modifier.height(40.dp)) }
        }
    }
}

/* ----------- Карточка одного пункта ----------- */

@Composable
private fun ChecklistItemCard(
    item: ChecklistTemplates.Item,
    answer: ChecklistTemplates.ItemAnswer,
    onAnswerChange: (ChecklistTemplates.Answer) -> Unit,
    onCommentChange: (String) -> Unit,
    onTakePhoto: () -> Unit,
    onPickPhoto: () -> Unit,
) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = if (answer.answer != ChecklistTemplates.Answer.PENDING)
                MaterialTheme.colorScheme.surfaceContainer
            else
                MaterialTheme.colorScheme.surface
        )
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            // Метка критичности
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .background(
                            color = when (item.severity) {
                                ChecklistTemplates.Severity.CRITICAL -> Color(0xFFC62828)
                                ChecklistTemplates.Severity.MAJOR -> Color(0xFFEF6C00)
                                ChecklistTemplates.Severity.MINOR -> Color(0xFF757575)
                            },
                            shape = RoundedCornerShape(50)
                        )
                )
                Spacer(Modifier.width(6.dp))
                Text("[${item.severity.label}]",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (item.gostRef != null) {
                    Spacer(Modifier.width(8.dp))
                    Text("• ${item.gostRef}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (item.photoRequired) {
                    Spacer(Modifier.width(6.dp))
                    Text("📷 фото обязательно",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error)
                }
            }
            Spacer(Modifier.height(4.dp))
            Text(item.question,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium)
            if (item.hint.isNotBlank()) {
                Spacer(Modifier.height(2.dp))
                Text(item.hint,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Spacer(Modifier.height(8.dp))

            // Кнопки ответа
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                AnswerButton(
                    label = "✓ Норма",
                    color = Color(0xFF2E7D32),
                    selected = answer.answer == ChecklistTemplates.Answer.OK,
                    onClick = { onAnswerChange(ChecklistTemplates.Answer.OK) },
                    modifier = Modifier.weight(1f)
                )
                AnswerButton(
                    label = "⚠",
                    color = Color(0xFFEF6C00),
                    selected = answer.answer == ChecklistTemplates.Answer.NOTE,
                    onClick = { onAnswerChange(ChecklistTemplates.Answer.NOTE) },
                    modifier = Modifier.weight(0.6f)
                )
                AnswerButton(
                    label = "✗",
                    color = Color(0xFFC62828),
                    selected = answer.answer == ChecklistTemplates.Answer.FAIL,
                    onClick = { onAnswerChange(ChecklistTemplates.Answer.FAIL) },
                    modifier = Modifier.weight(0.6f)
                )
                AnswerButton(
                    label = "N/A",
                    color = Color(0xFF757575),
                    selected = answer.answer == ChecklistTemplates.Answer.NA,
                    onClick = { onAnswerChange(ChecklistTemplates.Answer.NA) },
                    modifier = Modifier.weight(0.7f)
                )
            }

            // Комментарий (показываем если есть ответ кроме PENDING)
            if (answer.answer != ChecklistTemplates.Answer.PENDING &&
                answer.answer != ChecklistTemplates.Answer.NA) {
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = answer.comment,
                    onValueChange = onCommentChange,
                    label = { Text("Комментарий" +
                        if (answer.answer != ChecklistTemplates.Answer.OK) " *" else "") },
                    minLines = 1,
                    maxLines = 4,
                    modifier = Modifier.fillMaxWidth()
                )
            }

            // Фото (превью + кнопки)
            if (answer.photoPath != null) {
                val bm = remember(answer.photoPath) {
                    try { BitmapFactory.decodeFile(answer.photoPath) } catch (_: Exception) { null }
                }
                bm?.let {
                    Spacer(Modifier.height(8.dp))
                    androidx.compose.foundation.Image(
                        bitmap = it.asImageBitmap(),
                        contentDescription = null,
                        modifier = Modifier.fillMaxWidth().height(140.dp)
                    )
                }
            }
            Spacer(Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                OutlinedButton(onClick = onTakePhoto, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Filled.CameraAlt, contentDescription = null,
                        modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text(if (answer.photoPath != null) "Заменить" else "Снять",
                        style = MaterialTheme.typography.labelMedium)
                }
                OutlinedButton(onClick = onPickPhoto, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Filled.PhotoLibrary, contentDescription = null,
                        modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Галерея", style = MaterialTheme.typography.labelMedium)
                }
            }
        }
    }
}

@Composable
private fun AnswerButton(
    label: String,
    color: Color,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (selected) {
        Button(
            onClick = onClick,
            colors = ButtonDefaults.buttonColors(
                containerColor = color,
                contentColor = Color.White
            ),
            contentPadding = PaddingValues(horizontal = 6.dp, vertical = 4.dp),
            modifier = modifier.height(36.dp)
        ) {
            Text(label, style = MaterialTheme.typography.labelMedium)
        }
    } else {
        OutlinedButton(
            onClick = onClick,
            colors = ButtonDefaults.outlinedButtonColors(contentColor = color),
            contentPadding = PaddingValues(horizontal = 6.dp, vertical = 4.dp),
            modifier = modifier.height(36.dp)
        ) {
            Text(label, style = MaterialTheme.typography.labelMedium)
        }
    }
}

@Composable
private fun StatBadge(label: String, count: Int, color: Color) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .size(10.dp)
                .background(color, RoundedCornerShape(50))
        )
        Spacer(Modifier.width(4.dp))
        Text("$label $count",
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold)
    }
}

private data class Stats(
    val total: Int, val ok: Int, val notes: Int,
    val fails: Int, val na: Int, val pending: Int,
) {
    val completed: Int get() = ok + notes + fails + na
}

/* ----------- Сохранение чек-листа в БД ----------- */

private suspend fun saveChecklistRecord(
    ctx: Context,
    repo: MeasurementsRepository,
    templateKind: ChecklistTemplates.TemplateKind,
    allItems: List<ChecklistTemplates.Item>,
    answersState: Map<String, ChecklistTemplates.ItemAnswer>,
    objectName: String,
    organizationName: String,
    auditorName: String,
    commonNote: String,
    gpsCoords: GpsHelper.Coords?,
    stats: Stats,
): Long {
    val type = when (templateKind) {
        ChecklistTemplates.TemplateKind.RBU -> MeasurementType.CHECKLIST_RBU
        ChecklistTemplates.TemplateKind.DSU -> MeasurementType.CHECKLIST_DSU
        ChecklistTemplates.TemplateKind.QUARRY -> MeasurementType.CHECKLIST_QUARRY
    }

    // Сериализуем все ответы в JSON для последующей генерации PDF
    val answersJson = JSONArray()
    for (it in allItems) {
        val a = answersState[it.id] ?: continue
        if (a.answer == ChecklistTemplates.Answer.PENDING) continue
        answersJson.put(JSONObject().apply {
            put("item_id", it.id)
            put("answer", a.answer.name)
            put("comment", a.comment)
            put("photo_path", a.photoPath)
            put("timestamp", a.timestamp)
            a.gpsLat?.let { v -> put("gps_lat", v) }
            a.gpsLon?.let { v -> put("gps_lon", v) }
        })
    }

    val extra = JSONObject().apply {
        put("template", templateKind.name)
        put("object_name", objectName)
        put("organization", organizationName)
        put("auditor", auditorName)
        put("common_note", commonNote)
        put("stats_total", stats.total)
        put("stats_ok", stats.ok)
        put("stats_notes", stats.notes)
        put("stats_fails", stats.fails)
        put("stats_na", stats.na)
        put("stats_pending", stats.pending)
        put("answers", answersJson)
    }.toString()

    val noteFull = buildString {
        append("Чек-лист ${templateKind.label}")
        if (objectName.isNotBlank()) append(" • $objectName")
        append(" • OK:${stats.ok} ⚠:${stats.notes} ✗:${stats.fails}")
        if (commonNote.isNotBlank()) append(" — $commonNote")
    }

    return repo.save(
        type = type,
        valueRaw = stats.fails.toDouble(),
        unit = "наруш.",
        note = noteFull,
        gpsLat = gpsCoords?.latitude,
        gpsLon = gpsCoords?.longitude,
        extraJson = extra,
    )
}

/* ----------- File helpers ----------- */

private fun saveBitmapFromCacheToFiles(ctx: Context, uri: Uri, prefix: String): String? {
    return try {
        val src = uri.path ?: return null
        val bm = BitmapFactory.decodeFile(src) ?: return null
        val dst = File(ctx.filesDir, "checklist").apply { mkdirs() }
        val outFile = File(dst, "${prefix}_${System.currentTimeMillis()}.jpg")
        FileOutputStream(outFile).use { out -> bm.compress(Bitmap.CompressFormat.JPEG, 70, out) }
        bm.recycle()
        outFile.absolutePath
    } catch (e: Exception) {
        null
    }
}

private fun saveBitmapFromUriToFiles(ctx: Context, uri: Uri, prefix: String): String? {
    return try {
        val opts = BitmapFactory.Options().apply { inSampleSize = 2 }
        val bm = ctx.contentResolver.openInputStream(uri)?.use { stream ->
            BitmapFactory.decodeStream(stream, null, opts)
        } ?: return null
        val dst = File(ctx.filesDir, "checklist").apply { mkdirs() }
        val outFile = File(dst, "${prefix}_${System.currentTimeMillis()}.jpg")
        FileOutputStream(outFile).use { out -> bm.compress(Bitmap.CompressFormat.JPEG, 70, out) }
        bm.recycle()
        outFile.absolutePath
    } catch (e: Exception) {
        null
    }
}

private fun createTempImageUri(ctx: Context, prefix: String): Uri {
    val cacheDir = File(ctx.cacheDir, "camera").apply { mkdirs() }
    val file = File.createTempFile("${prefix}_", ".jpg", cacheDir)
    return FileProvider.getUriForFile(ctx, "${ctx.packageName}.fileprovider", file)
}
