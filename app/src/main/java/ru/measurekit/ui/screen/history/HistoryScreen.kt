package ru.measurekit.ui.screen.history

import android.content.Intent
import android.graphics.BitmapFactory
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.IosShare
import androidx.compose.material.icons.filled.Photo
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import ru.measurekit.data.MeasurementEntity
import ru.measurekit.data.MeasurementType
import ru.measurekit.data.MeasurementsRepository
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * История измерений.
 *
 * Возможности:
 *   - Список всех измерений в обратном хронологическом порядке
 *   - Превью скриншота разметки (если есть) на карточке
 *   - Тап по карточке - полноэкранный просмотр изображения с кнопкой "Поделиться"
 *   - Удаление одной записи (вместе с её изображениями на диске)
 *   - "Удалить всё" с подтверждением
 *   - Экспорт: если есть записи с изображениями - ZIP (CSV + папка images/),
 *             иначе обычный CSV.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HistoryScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val repo = remember { MeasurementsRepository.get(ctx) }
    val items by repo.observeAll().collectAsState(initial = emptyList())
    val scope = rememberCoroutineScope()
    val snackbarHost = remember { SnackbarHostState() }
    var showDeleteAll by remember { mutableStateOf(false) }
    var viewingItem by remember { mutableStateOf<MeasurementEntity?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("История (${items.size})") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Назад")
                    }
                },
                actions = {
                    IconButton(
                        enabled = items.isNotEmpty(),
                        onClick = {
                            scope.launch {
                                val all = repo.getAllSnapshot()
                                val hasImages = all.any { it.imagePath != null || it.originalPath != null }
                                if (hasImages) {
                                    exportZip(ctx, all)
                                } else {
                                    exportCsvOnly(ctx, all)
                                }
                            }
                        }
                    ) {
                        Icon(Icons.Filled.IosShare, contentDescription = "Экспорт")
                    }
                    IconButton(
                        enabled = items.isNotEmpty(),
                        onClick = { showDeleteAll = true }
                    ) {
                        Icon(Icons.Filled.DeleteSweep, contentDescription = "Удалить всё")
                    }
                }
            )
        },
        snackbarHost = { SnackbarHost(snackbarHost) }
    ) { padding ->
        if (items.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("Пусто",
                        style = MaterialTheme.typography.titleLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("Сохранённые измерения появятся здесь",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(items, key = { it.id }) { item ->
                    MeasurementCard(
                        item = item,
                        onView = { viewingItem = item },
                        onDelete = {
                            scope.launch {
                                repo.deleteById(item.id)
                                snackbarHost.showSnackbar("Удалено")
                            }
                        }
                    )
                }
            }
        }
    }

    if (showDeleteAll) {
        AlertDialog(
            onDismissRequest = { showDeleteAll = false },
            title = { Text("Удалить всю историю?") },
            text = { Text("Будут удалены все ${items.size} записей, включая сохранённые фото. Это действие нельзя отменить.") },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch {
                        repo.deleteAll()
                        snackbarHost.showSnackbar("История очищена")
                    }
                    showDeleteAll = false
                }) { Text("Удалить всё") }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteAll = false }) { Text("Отмена") }
            }
        )
    }

    viewingItem?.let { item ->
        FullscreenViewer(
            item = item,
            onClose = { viewingItem = null }
        )
    }
}

/* ---------- Карточка ---------- */

@Composable
private fun MeasurementCard(
    item: MeasurementEntity,
    onView: () -> Unit,
    onDelete: () -> Unit
) {
    val ctx = LocalContext.current
    val repo = remember { MeasurementsRepository.get(ctx) }

    // Загружаем thumbnail асинхронно
    val thumbnail by produceState<android.graphics.Bitmap?>(initialValue = null, item.id, item.imagePath) {
        value = withContext(Dispatchers.IO) {
            repo.loadThumbnail(item.imagePath, targetSize = 200)
        }
    }

    val hasImage = item.imagePath != null

    Card(
        modifier = Modifier.fillMaxWidth().let {
            if (hasImage) it.clickable(onClick = onView) else it
        },
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Превью или иконка-плейсхолдер
            Box(
                modifier = Modifier
                    .size(72.dp)
                    .background(
                        MaterialTheme.colorScheme.surfaceContainerHigh,
                        MaterialTheme.shapes.small
                    ),
                contentAlignment = Alignment.Center
            ) {
                val tn = thumbnail
                if (tn != null) {
                    androidx.compose.foundation.Image(
                        bitmap = tn.asImageBitmap(),
                        contentDescription = "Превью",
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop
                    )
                } else if (hasImage) {
                    // Загружается
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        strokeWidth = 2.dp
                    )
                } else {
                    Icon(
                        Icons.Filled.Photo,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                        modifier = Modifier.size(28.dp)
                    )
                }
            }
            Spacer(Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = formatTypeLabel(item.type),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = " • ${formatTimestamp(item.timestamp)}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Text(
                    text = formatValue(item),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
                if (item.note.isNotBlank()) {
                    Text(
                        text = item.note,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 2.dp),
                        maxLines = 2
                    )
                }
            }
            IconButton(onClick = onDelete) {
                Icon(
                    Icons.Filled.Delete,
                    contentDescription = "Удалить",
                    tint = MaterialTheme.colorScheme.error
                )
            }
        }
    }
}

/* ---------- Полноэкранный просмотр ---------- */

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FullscreenViewer(
    item: MeasurementEntity,
    onClose: () -> Unit
) {
    val ctx = LocalContext.current
    val repo = remember { MeasurementsRepository.get(ctx) }

    var showOriginal by remember { mutableStateOf(false) }
    val hasOriginal = item.originalPath != null

    val activePath = if (showOriginal && hasOriginal) item.originalPath else item.imagePath

    val bitmap by produceState<android.graphics.Bitmap?>(initialValue = null, activePath) {
        value = withContext(Dispatchers.IO) { repo.loadFull(activePath) }
    }

    Dialog(
        onDismissRequest = onClose,
        properties = androidx.compose.ui.window.DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false
        )
    ) {
        Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
            // Изображение по центру
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                val bm = bitmap
                if (bm != null) {
                    androidx.compose.foundation.Image(
                        bitmap = bm.asImageBitmap(),
                        contentDescription = null,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Fit
                    )
                } else {
                    CircularProgressIndicator(color = Color.White)
                }
            }

            // Верхняя панель с кнопками
            Row(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .fillMaxWidth()
                    .background(Color.Black.copy(alpha = 0.5f))
                    .padding(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onClose) {
                    Icon(Icons.Filled.Close, contentDescription = "Закрыть", tint = Color.White)
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        formatTypeLabel(item.type) + " • " + formatTimestamp(item.timestamp),
                        style = MaterialTheme.typography.labelMedium,
                        color = Color.White.copy(alpha = 0.7f)
                    )
                    Text(
                        formatValue(item),
                        style = MaterialTheme.typography.titleMedium,
                        color = Color.White,
                        fontWeight = FontWeight.Bold
                    )
                }
                IconButton(
                    onClick = {
                        activePath?.let { sharePath(ctx, it) }
                    },
                    enabled = activePath != null
                ) {
                    Icon(Icons.Filled.IosShare, contentDescription = "Поделиться", tint = Color.White)
                }
            }

            // Нижняя панель: переключатель "Разметка / Оригинал" если есть оригинал
            if (hasOriginal) {
                Surface(
                    modifier = Modifier.align(Alignment.BottomCenter).padding(16.dp),
                    color = Color.Black.copy(alpha = 0.6f),
                    shape = MaterialTheme.shapes.medium
                ) {
                    Row(modifier = Modifier.padding(4.dp)) {
                        TextButton(
                            onClick = { showOriginal = false },
                            colors = ButtonDefaults.textButtonColors(
                                contentColor = if (!showOriginal) Color.White
                                else Color.White.copy(alpha = 0.5f)
                            )
                        ) { Text("Разметка") }
                        TextButton(
                            onClick = { showOriginal = true },
                            colors = ButtonDefaults.textButtonColors(
                                contentColor = if (showOriginal) Color.White
                                else Color.White.copy(alpha = 0.5f)
                            )
                        ) { Text("Оригинал") }
                    }
                }
            }
        }
    }
}

@Composable
private fun Dialog(
    onDismissRequest: () -> Unit,
    properties: androidx.compose.ui.window.DialogProperties,
    content: @Composable () -> Unit
) {
    androidx.compose.ui.window.Dialog(
        onDismissRequest = onDismissRequest,
        properties = properties,
        content = content
    )
}

/* ---------- Поделиться файлом ---------- */

private fun sharePath(ctx: android.content.Context, path: String) {
    try {
        val file = File(path)
        if (!file.exists()) {
            android.widget.Toast.makeText(ctx, "Файл не найден", android.widget.Toast.LENGTH_SHORT).show()
            return
        }
        val uri = FileProvider.getUriForFile(
            ctx,
            ctx.packageName + ".fileprovider",
            file
        )
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "image/jpeg"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        ctx.startActivity(Intent.createChooser(intent, "Поделиться"))
    } catch (e: Exception) {
        // Например, IllegalArgumentException от FileProvider если путь не подпадает под file_paths.xml
        android.widget.Toast.makeText(
            ctx,
            "Не удалось поделиться: ${e.message}",
            android.widget.Toast.LENGTH_LONG
        ).show()
    }
}

/* ---------- Экспорт ---------- */

private suspend fun exportCsvOnly(
    ctx: android.content.Context,
    all: List<MeasurementEntity>
) = withContext(Dispatchers.IO) {
    val csv = buildCsv(all)
    val file = File(ctx.cacheDir, "measurekit_history.csv")
    file.writeText(csv, Charsets.UTF_8)
    val uri = FileProvider.getUriForFile(ctx, ctx.packageName + ".fileprovider", file)
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = "text/csv"
        putExtra(Intent.EXTRA_STREAM, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    withContext(Dispatchers.Main) {
        ctx.startActivity(Intent.createChooser(intent, "Экспорт истории"))
    }
}

private suspend fun exportZip(
    ctx: android.content.Context,
    all: List<MeasurementEntity>
) = withContext(Dispatchers.IO) {
    val zipFile = File(ctx.cacheDir, "measurekit_history.zip")
    ZipOutputStream(FileOutputStream(zipFile)).use { zip ->
        // 1) CSV
        val csv = buildCsv(all)
        zip.putNextEntry(ZipEntry("history.csv"))
        zip.write(csv.toByteArray(Charsets.UTF_8))
        zip.closeEntry()

        // 2) Изображения
        for (item in all) {
            item.imagePath?.let { path ->
                val f = File(path)
                if (f.exists()) {
                    val name = "images/${item.id}_markup.jpg"
                    zip.putNextEntry(ZipEntry(name))
                    f.inputStream().use { it.copyTo(zip) }
                    zip.closeEntry()
                }
            }
            item.originalPath?.let { path ->
                val f = File(path)
                if (f.exists()) {
                    val name = "images/${item.id}_original.jpg"
                    zip.putNextEntry(ZipEntry(name))
                    f.inputStream().use { it.copyTo(zip) }
                    zip.closeEntry()
                }
            }
        }
    }
    val uri = FileProvider.getUriForFile(ctx, ctx.packageName + ".fileprovider", zipFile)
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = "application/zip"
        putExtra(Intent.EXTRA_STREAM, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    withContext(Dispatchers.Main) {
        ctx.startActivity(Intent.createChooser(intent, "Экспорт истории (ZIP)"))
    }
}

/* ---------- Форматирование ---------- */

private fun formatTypeLabel(type: String): String = when (type) {
    MeasurementType.RULER -> "ЛИНЕЙКА"
    MeasurementType.PHOTO -> "ФОТО"
    MeasurementType.AREA -> "ПЛОЩАДЬ"
    MeasurementType.RANGEFINDER -> "ДАЛЬНОМЕР"
    MeasurementType.PROTRACTOR -> "ТРАНСПОРТИР"
    MeasurementType.LEVEL -> "УРОВЕНЬ"
    MeasurementType.REBAR -> "АРМАТУРА"
    MeasurementType.STOCKPILE -> "ШТАБЕЛЬ"
    MeasurementType.DOSE_CHECK -> "ДОЗАТОР"
    MeasurementType.MIXER_BATCH -> "ЗАМЕС"
    MeasurementType.MOISTURE -> "ВЛАЖНОСТЬ"
    MeasurementType.SIEVE_TEST -> "СИТО"
    MeasurementType.CHECKLIST_RBU -> "ЧЕК-ЛИСТ РБУ"
    MeasurementType.CHECKLIST_DSU -> "ЧЕК-ЛИСТ ДСУ"
    MeasurementType.CHECKLIST_QUARRY -> "ЧЕК-ЛИСТ КАРЬЕР"
    else -> type
}

private fun formatValue(item: MeasurementEntity): String {
    return when (item.type) {
        MeasurementType.RULER, MeasurementType.PHOTO -> {
            val mm = item.valueRaw
            when (item.unit) {
                "см" -> "%.2f см".format(mm / 10.0)
                "дюйм" -> "%.3f дюйм".format(mm / 25.4)
                else -> "%.1f мм".format(mm)
            }
        }
        MeasurementType.AREA -> {
            val mm2 = item.valueRaw
            when {
                mm2 < 1000 -> "%.0f мм²".format(mm2)
                mm2 < 100_000 -> "%.2f см²".format(mm2 / 100)
                else -> "%.4f м²".format(mm2 / 1_000_000)
            }
        }
        MeasurementType.RANGEFINDER -> "%.2f м".format(item.valueRaw)
        MeasurementType.PROTRACTOR, MeasurementType.LEVEL -> "%.1f°".format(item.valueRaw)
        MeasurementType.REBAR -> "${item.valueRaw.toInt()} шт"
        MeasurementType.STOCKPILE -> "%.1f м³".format(item.valueRaw)
        MeasurementType.DOSE_CHECK -> "%+.2f%%".format(item.valueRaw)
        MeasurementType.MIXER_BATCH -> "%.0f кг/м³".format(item.valueRaw)
        MeasurementType.MOISTURE -> "%.2f%%".format(item.valueRaw)
        MeasurementType.SIEVE_TEST -> "%.1f%%".format(item.valueRaw)
        MeasurementType.CHECKLIST_RBU,
        MeasurementType.CHECKLIST_DSU,
        MeasurementType.CHECKLIST_QUARRY -> "${item.valueRaw.toInt()} наруш."
        else -> "${item.valueRaw} ${item.unit}"
    }
}

private val tsFormat = SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.getDefault())

private fun formatTimestamp(t: Long): String = tsFormat.format(Date(t))

private fun buildCsv(items: List<MeasurementEntity>): String {
    val sb = StringBuilder()
    sb.append("id;type;value_raw;unit;note;timestamp;markup_image;original_image\n")
    for (item in items) {
        val noteEscaped = item.note.replace("\"", "\"\"")
        val markupRef = if (item.imagePath != null) "images/${item.id}_markup.jpg" else ""
        val origRef = if (item.originalPath != null) "images/${item.id}_original.jpg" else ""
        sb.append(item.id).append(';')
            .append(item.type).append(';')
            .append(item.valueRaw).append(';')
            .append(item.unit).append(';')
            .append('"').append(noteEscaped).append('"').append(';')
            .append(formatTimestamp(item.timestamp)).append(';')
            .append(markupRef).append(';')
            .append(origRef).append('\n')
    }
    return sb.toString()
}
