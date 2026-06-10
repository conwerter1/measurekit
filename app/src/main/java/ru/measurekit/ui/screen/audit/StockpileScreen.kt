package ru.measurekit.ui.screen.audit

import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.Save
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import ru.measurekit.data.MeasurementType
import ru.measurekit.data.MeasurementsRepository
import ru.measurekit.domain.audit.StockpileSpec
import ru.measurekit.ui.common.InstructionCard
import ru.measurekit.ui.common.Instructions
import ru.measurekit.util.GpsHelper
import java.io.File
import kotlin.math.max

/**
 * Модуль А — «Обмер штабеля» нерудных материалов.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StockpileScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val repo = remember { MeasurementsRepository.get(ctx) }
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }

    var photoBitmap by remember { mutableStateOf<android.graphics.Bitmap?>(null) }
    var gps by remember { mutableStateOf<GpsHelper.Coords?>(null) }

    var material by remember { mutableStateOf(StockpileSpec.Material.LIMESTONE_5_10) }
    var shape by remember { mutableStateOf(StockpileSpec.Shape.CONE) }

    var d1 by remember { mutableStateOf("") }
    var d2 by remember { mutableStateOf("") }
    var d3 by remember { mutableStateOf("") }
    var d4 by remember { mutableStateOf("") }

    var note by remember { mutableStateOf("") }

    LaunchedEffect(Unit) {
        if (GpsHelper.hasLocationPermission(ctx)) {
            gps = GpsHelper.getLastKnown(ctx)
                ?: withContext(Dispatchers.Default) { GpsHelper.requestSingleUpdate(ctx, 5000) }
        }
    }

    var pendingCameraUri by remember { mutableStateOf<Uri?>(null) }
    val cameraLauncher = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        val uri = pendingCameraUri
        if (ok && uri != null) {
            scope.launch {
                photoBitmap = withContext(Dispatchers.IO) { loadBitmap(ctx, uri) }
            }
        }
    }
    val galleryLauncher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        uri?.let {
            scope.launch {
                photoBitmap = withContext(Dispatchers.IO) { loadBitmap(ctx, it) }
            }
        }
    }

    val dims: DoubleArray? = remember(shape, d1, d2, d3, d4) {
        fun p(s: String) = s.replace(',', '.').toDoubleOrNull()
        when (shape) {
            StockpileSpec.Shape.CONE -> {
                val r = p(d1); val h = p(d2)
                if (r != null && h != null) doubleArrayOf(r, h) else null
            }
            StockpileSpec.Shape.FRUSTUM -> {
                val r1 = p(d1); val r2 = p(d2); val h = p(d3)
                if (r1 != null && r2 != null && h != null) doubleArrayOf(r1, r2, h) else null
            }
            StockpileSpec.Shape.TRAPEZOID_PRISM -> {
                val l = p(d1); val a = p(d2); val b = p(d3); val h = p(d4)
                if (l != null && a != null && b != null && h != null) doubleArrayOf(l, a, b, h) else null
            }
            StockpileSpec.Shape.BOX -> {
                val l = p(d1); val w = p(d2); val h = p(d3)
                if (l != null && w != null && h != null) doubleArrayOf(l, w, h) else null
            }
        }
    }

    val result: StockpileSpec.StockpileResult? = remember(material, shape, dims) {
        if (dims != null) StockpileSpec.calculate(material, shape, *dims) else null
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Обмер штабеля") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        androidx.compose.material3.Icon(
                            Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Назад")
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                InstructionCard(
                    moduleKey = "stockpile",
                    title = "Как обмерить штабель",
                    sections = Instructions.STOCKPILE,
                )
            }
            item {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Text("Фото штабеля", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                        Spacer(Modifier.height(6.dp))
                        if (photoBitmap != null) {
                            androidx.compose.foundation.Image(
                                bitmap = photoBitmap!!.asImageBitmap(),
                                contentDescription = "Фото",
                                modifier = Modifier.fillMaxWidth().height(180.dp)
                            )
                        } else {
                            Surface(
                                modifier = Modifier.fillMaxWidth().height(120.dp),
                                color = MaterialTheme.colorScheme.surfaceVariant,
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Text("Сделайте фото или выберите из галереи",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        }
                        Spacer(Modifier.height(8.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(
                                onClick = {
                                    val uri = createTempImageUri(ctx)
                                    pendingCameraUri = uri
                                    cameraLauncher.launch(uri)
                                },
                                modifier = Modifier.weight(1f),
                            ) {
                                androidx.compose.material3.Icon(
                                    Icons.Filled.CameraAlt, contentDescription = null,
                                    modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(4.dp))
                                Text("Камера")
                            }
                            OutlinedButton(
                                onClick = { galleryLauncher.launch("image/*") },
                                modifier = Modifier.weight(1f),
                            ) {
                                androidx.compose.material3.Icon(
                                    Icons.Filled.PhotoLibrary, contentDescription = null,
                                    modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(4.dp))
                                Text("Галерея")
                            }
                        }
                        if (gps != null) {
                            Text("GPS: ${gps!!.short()} (±${gps!!.accuracyM.toInt()}м)",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }

            item {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Text("Материал", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                        Spacer(Modifier.height(4.dp))
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            items(StockpileSpec.Material.all().size) { idx ->
                                val m = StockpileSpec.Material.all()[idx]
                                FilterChip(
                                    selected = m == material,
                                    onClick = { material = m },
                                    label = { Text(m.label, style = MaterialTheme.typography.labelSmall) }
                                )
                            }
                        }
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = "ρ = ${material.bulkDensity} т/м³ · α = ${material.angleOfReposeDeg}° · ${material.gostRef}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }

            item {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Text("Форма штабеля", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                        Spacer(Modifier.height(4.dp))
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            items(StockpileSpec.Shape.all().size) { idx ->
                                val s = StockpileSpec.Shape.all()[idx]
                                FilterChip(
                                    selected = s == shape,
                                    onClick = { shape = s },
                                    label = { Text(s.label, style = MaterialTheme.typography.labelSmall) }
                                )
                            }
                        }
                    }
                }
            }

            item {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Text("Размеры (м)", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                        Spacer(Modifier.height(8.dp))
                        when (shape) {
                            StockpileSpec.Shape.CONE -> {
                                NumField(d1, { d1 = it }, "Радиус основания, R", "м")
                                Spacer(Modifier.height(8.dp))
                                NumField(d2, { d2 = it }, "Высота, H", "м")
                                val r = d1.replace(',', '.').toDoubleOrNull()
                                if (r != null) {
                                    val expectedH = StockpileSpec.expectedConeHeight(material, r)
                                    Spacer(Modifier.height(4.dp))
                                    Text(
                                        "Подсказка: для R=${"%.1f".format(r)}м высота при угле откоса " +
                                                "${material.angleOfReposeDeg}° ≈ ${"%.2f".format(expectedH)} м",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                            StockpileSpec.Shape.FRUSTUM -> {
                                NumField(d1, { d1 = it }, "Радиус низа, R₁", "м")
                                Spacer(Modifier.height(8.dp))
                                NumField(d2, { d2 = it }, "Радиус верха, R₂", "м")
                                Spacer(Modifier.height(8.dp))
                                NumField(d3, { d3 = it }, "Высота, H", "м")
                            }
                            StockpileSpec.Shape.TRAPEZOID_PRISM -> {
                                NumField(d1, { d1 = it }, "Длина штабеля, L", "м")
                                Spacer(Modifier.height(8.dp))
                                NumField(d2, { d2 = it }, "Ширина низа, a", "м")
                                Spacer(Modifier.height(8.dp))
                                NumField(d3, { d3 = it }, "Ширина верха, b", "м")
                                Spacer(Modifier.height(8.dp))
                                NumField(d4, { d4 = it }, "Высота, H", "м")
                            }
                            StockpileSpec.Shape.BOX -> {
                                NumField(d1, { d1 = it }, "Длина", "м")
                                Spacer(Modifier.height(8.dp))
                                NumField(d2, { d2 = it }, "Ширина", "м")
                                Spacer(Modifier.height(8.dp))
                                NumField(d3, { d3 = it }, "Высота", "м")
                            }
                        }
                    }
                }
            }

            item {
                if (result != null) {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.primaryContainer)
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Text("Результат расчёта",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.SemiBold)
                            Spacer(Modifier.height(8.dp))
                            Row {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text("Объём", style = MaterialTheme.typography.labelMedium)
                                    Text("${"%.1f".format(result.volumeM3)} м³",
                                        style = MaterialTheme.typography.headlineSmall,
                                        fontWeight = FontWeight.Bold)
                                }
                                Column(modifier = Modifier.weight(1f)) {
                                    Text("Масса (по ρ)", style = MaterialTheme.typography.labelMedium)
                                    Text("${"%.1f".format(result.massNominalT)} т",
                                        style = MaterialTheme.typography.headlineSmall,
                                        fontWeight = FontWeight.Bold)
                                }
                            }
                            Spacer(Modifier.height(8.dp))
                            Text("С учётом уплотнения (k=${StockpileSpec.COMPACTION_MAX}): " +
                                    "${"%.1f".format(result.massWithCompactionT)} т",
                                style = MaterialTheme.typography.bodySmall)
                            Text("Диапазон ±10%: ${"%.1f".format(result.massMinT)} – " +
                                    "${"%.1f".format(result.massMaxT)} т",
                                style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }

            item {
                OutlinedTextField(
                    value = note, onValueChange = { note = it },
                    label = { Text("Заметка (партия, поставщик, № штабеля)") },
                    minLines = 2,
                    modifier = Modifier.fillMaxWidth()
                )
            }

            item {
                Button(
                    onClick = {
                        val r = result ?: return@Button
                        scope.launch {
                            val extra = JSONObject().apply {
                                put("material", material.name)
                                put("material_label", material.label)
                                put("shape", shape.name)
                                put("density", material.bulkDensity)
                                put("volume_m3", r.volumeM3)
                                put("mass_nominal_t", r.massNominalT)
                                put("mass_compacted_t", r.massWithCompactionT)
                                put("dims", dims!!.joinToString(","))
                                put("description", r.description)
                            }
                            repo.save(
                                type = MeasurementType.STOCKPILE,
                                valueRaw = r.volumeM3,
                                unit = "м³",
                                note = "${material.label}: ${"%.1f".format(r.volumeM3)} м³ (${"%.1f".format(r.massNominalT)} т)" +
                                        if (note.isNotBlank()) " — $note" else "",
                                originalBitmap = photoBitmap,
                                gpsLat = gps?.latitude,
                                gpsLon = gps?.longitude,
                                extraJson = extra.toString(),
                            )
                            snackbar.showSnackbar("Замер сохранён в Историю")
                        }
                    },
                    enabled = result != null,
                    modifier = Modifier.fillMaxWidth().height(50.dp)
                ) {
                    androidx.compose.material3.Icon(Icons.Filled.Save, contentDescription = null,
                        modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Сохранить", style = MaterialTheme.typography.titleMedium)
                }
            }
        }
    }
}

@Composable
private fun NumField(value: String, onValueChange: (String) -> Unit, label: String, unit: String) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        suffix = { Text(unit) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        modifier = Modifier.fillMaxWidth()
    )
}

private fun createTempImageUri(ctx: Context): Uri {
    val cacheDir = File(ctx.cacheDir, "camera").apply { mkdirs() }
    val file = File.createTempFile("stockpile_", ".jpg", cacheDir)
    return FileProvider.getUriForFile(ctx, "${ctx.packageName}.fileprovider", file)
}

private fun loadBitmap(ctx: Context, uri: Uri): android.graphics.Bitmap? {
    return try {
        val metaOpts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        ctx.contentResolver.openInputStream(uri)?.use { stream ->
            BitmapFactory.decodeStream(stream, null, metaOpts)
        }
        val maxDim = max(metaOpts.outWidth, metaOpts.outHeight)
        var sample = 1
        while (maxDim / sample > 1920) sample *= 2
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        ctx.contentResolver.openInputStream(uri)?.use { stream ->
            BitmapFactory.decodeStream(stream, null, opts)
        }
    } catch (e: Exception) {
        null
    }
}
