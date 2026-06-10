package ru.measurekit.ui.screen.audit

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Share
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
import ru.measurekit.data.MeasurementsRepository
import ru.measurekit.domain.audit.ChecklistTemplates
import ru.measurekit.util.PdfReportGenerator
import java.io.File

/**
 * Превью PDF-отчёта по сохранённому чек-листу.
 *
 * Workflow:
 *   1. Открывается с переданным recordId (id записи в БД)
 *   2. Загружает запись, парсит extra_json с ответами
 *   3. Через PdfReportGenerator делает PDF в filesDir/reports/
 *   4. Через PdfRenderer показывает preview всех страниц
 *   5. Кнопка "Поделиться" - Intent.ACTION_SEND для PDF через FileProvider
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChecklistReportScreen(
    recordId: Long,
    onBack: () -> Unit,
) {
    val ctx = LocalContext.current
    val repo = remember { MeasurementsRepository.get(ctx) }
    val scope = rememberCoroutineScope()

    var pdfFile by remember { mutableStateOf<File?>(null) }
    var pageImages by remember { mutableStateOf<List<Bitmap>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }

    // Генерируем PDF при старте
    LaunchedEffect(recordId) {
        scope.launch {
            try {
                val record = repo.getById(recordId)
                if (record == null) {
                    error = "Запись не найдена"
                    loading = false
                    return@launch
                }
                val extra = record.extraJson?.let { JSONObject(it) }
                if (extra == null) {
                    error = "Нет данных чек-листа"
                    loading = false
                    return@launch
                }

                val templateKind = ChecklistTemplates.TemplateKind.valueOf(
                    extra.optString("template", "RBU")
                )
                val template = ChecklistTemplates.byKind(templateKind)
                val itemsById = template.sections.flatMap { s -> s.items.map { it.id to (s.name to it) } }.toMap()

                // Восстанавливаем заполненные пункты из JSON
                val answersJson = extra.optJSONArray("answers") ?: JSONArray()
                val filled = mutableListOf<PdfReportGenerator.FilledItem>()
                for (i in 0 until answersJson.length()) {
                    val a = answersJson.getJSONObject(i)
                    val itemId = a.getString("item_id")
                    val (sectionName, item) = itemsById[itemId] ?: continue
                    val answer = ChecklistTemplates.Answer.valueOf(
                        a.optString("answer", "PENDING")
                    )
                    val comment = a.optString("comment", "")
                    val photoPath = a.optString("photo_path", "").takeIf { it.isNotBlank() }
                    val ts = a.optLong("timestamp", 0L)
                    filled += PdfReportGenerator.FilledItem(
                        item = item,
                        sectionName = sectionName,
                        answer = answer,
                        comment = comment,
                        photoPath = photoPath,
                        timestamp = ts,
                    )
                }

                val meta = PdfReportGenerator.ReportMeta(
                    templateKind = templateKind,
                    objectName = extra.optString("object_name", ""),
                    auditorName = extra.optString("auditor", ""),
                    organizationName = extra.optString("organization", ""),
                    auditDate = record.timestamp,
                    gpsLat = record.gpsLat,
                    gpsLon = record.gpsLon,
                    notes = extra.optString("common_note", ""),
                )

                // Генерируем PDF
                val reportsDir = File(ctx.filesDir, "reports").apply { mkdirs() }
                val ts = record.timestamp
                val fileName = "akt_${templateKind.name.lowercase()}_${ts}.pdf"
                val outFile = File(reportsDir, fileName)

                withContext(Dispatchers.IO) {
                    PdfReportGenerator.generate(ctx, meta, filled, outFile)
                }

                pdfFile = outFile

                // Рендерим страницы для превью
                val pages = withContext(Dispatchers.IO) {
                    renderPdfPages(outFile)
                }
                pageImages = pages
                loading = false
            } catch (e: Exception) {
                error = "Ошибка: ${e.message}"
                loading = false
            }
        }
    }

    // Чистим bitmaps при выходе
    DisposableEffect(Unit) {
        onDispose {
            pageImages.forEach { runCatching { it.recycle() } }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Акт проверки (PDF)") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Назад")
                    }
                },
                actions = {
                    pdfFile?.let { f ->
                        IconButton(onClick = { sharePdf(ctx, f) }) {
                            Icon(Icons.Filled.Share, contentDescription = "Поделиться")
                        }
                    }
                }
            )
        }
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding)
            .background(Color(0xFFEEEEEE))) {
            when {
                loading -> {
                    Column(modifier = Modifier.align(Alignment.Center),
                        horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator()
                        Spacer(Modifier.height(8.dp))
                        Text("Генерация PDF...")
                    }
                }
                error != null -> {
                    Text(error ?: "", color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.align(Alignment.Center).padding(20.dp))
                }
                else -> {
                    Column(modifier = Modifier.fillMaxSize()) {
                        LazyColumn(
                            modifier = Modifier.weight(1f),
                            contentPadding = PaddingValues(12.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            items(pageImages.size) { idx ->
                                Card(modifier = Modifier.fillMaxWidth()) {
                                    Image(
                                        bitmap = pageImages[idx].asImageBitmap(),
                                        contentDescription = "Стр. ${idx + 1}",
                                        modifier = Modifier.fillMaxWidth()
                                    )
                                }
                            }
                            item {
                                Text("Всего страниц: ${pageImages.size}",
                                    style = MaterialTheme.typography.bodySmall,
                                    fontWeight = FontWeight.Medium,
                                    modifier = Modifier.padding(top = 8.dp),
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                        // Bottom action bar
                        Surface(
                            color = MaterialTheme.colorScheme.surfaceContainerHigh,
                            tonalElevation = 4.dp,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(modifier = Modifier.fillMaxWidth().padding(12.dp),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                pdfFile?.let { f ->
                                    Button(
                                        onClick = { sharePdf(ctx, f) },
                                        modifier = Modifier.weight(1f).height(48.dp)
                                    ) {
                                        Icon(Icons.Filled.Share, contentDescription = null,
                                            modifier = Modifier.size(18.dp))
                                        Spacer(Modifier.width(6.dp))
                                        Text("Поделиться актом")
                                    }
                                }
                                OutlinedButton(
                                    onClick = onBack,
                                    modifier = Modifier.weight(1f).height(48.dp)
                                ) {
                                    Text("Готово")
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun renderPdfPages(file: File): List<Bitmap> {
    val out = mutableListOf<Bitmap>()
    var fd: ParcelFileDescriptor? = null
    var renderer: PdfRenderer? = null
    try {
        fd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
        renderer = PdfRenderer(fd)
        for (i in 0 until renderer.pageCount) {
            val page = renderer.openPage(i)
            // 595×842 pt → render at ~150 dpi (scale 2x)
            val w = 595 * 2
            val h = 842 * 2
            val bm = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            page.render(bm, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
            out += bm
            page.close()
        }
    } catch (e: Exception) {
        // если что-то пошло не так - возвращаем что успели отрендерить
    } finally {
        renderer?.close()
        fd?.close()
    }
    return out
}

private fun sharePdf(ctx: android.content.Context, file: File) {
    try {
        val uri: Uri = FileProvider.getUriForFile(
            ctx, "${ctx.packageName}.fileprovider", file
        )
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "application/pdf"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, "Акт проверки: ${file.name}")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        ctx.startActivity(Intent.createChooser(intent, "Поделиться актом"))
    } catch (e: Exception) {
        // Тихо падаем, чтобы не показывать страшное сообщение в UI
    }
}
