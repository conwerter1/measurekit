package ru.measurekit.ui.screen.audit

import android.content.Context
import android.graphics.Bitmap
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
import androidx.compose.material.icons.filled.AutoFixHigh
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
import ru.measurekit.domain.audit.DoserSpec
import ru.measurekit.domain.audit.MixerSpec
import ru.measurekit.domain.audit.MoistureSpec
import ru.measurekit.ui.common.InstructionCard
import ru.measurekit.ui.common.Instructions
import ru.measurekit.util.GpsHelper
import ru.measurekit.util.TextOcrHelper
import java.io.File
import kotlin.math.max

/**
 * Модуль Б — Контроль РБУ (3 закладки: Дозатор / Замес / Влажность).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RbuAuditScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val snackbar = remember { SnackbarHostState() }

    var tab by remember { mutableStateOf(0) }
    var gps by remember { mutableStateOf<GpsHelper.Coords?>(null) }
    LaunchedEffect(Unit) {
        if (GpsHelper.hasLocationPermission(ctx)) {
            gps = GpsHelper.getLastKnown(ctx)
                ?: withContext(Dispatchers.Default) { GpsHelper.requestSingleUpdate(ctx, 5000) }
        }
    }

    Scaffold(
        topBar = {
            Column {
                TopAppBar(
                    title = { Text("Контроль РБУ") },
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Назад")
                        }
                    },
                )
                TabRow(selectedTabIndex = tab) {
                    Tab(selected = tab == 0, onClick = { tab = 0 },
                        text = { Text("Дозатор") })
                    Tab(selected = tab == 1, onClick = { tab = 1 },
                        text = { Text("Замес") })
                    Tab(selected = tab == 2, onClick = { tab = 2 },
                        text = { Text("Влажность") })
                }
            }
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
            when (tab) {
                0 -> DoserCheckTab(ctx, snackbar, gps)
                1 -> MixerBatchTab(ctx, snackbar, gps)
                2 -> MoistureTab(ctx, snackbar, gps)
            }
        }
    }
}

/* ============================================================================
   Tab 1 — Проверка дозатора
   ============================================================================ */
@Composable
private fun DoserCheckTab(
    ctx: Context,
    snackbar: SnackbarHostState,
    gps: GpsHelper.Coords?,
) {
    val repo = remember { MeasurementsRepository.get(ctx) }
    val scope = rememberCoroutineScope()

    var kind by remember { mutableStateOf(DoserSpec.DoserKind.CEMENT) }
    var nominal by remember { mutableStateOf("") }
    var actual by remember { mutableStateOf("") }
    var doserId by remember { mutableStateOf("") }

    var photo by remember { mutableStateOf<Bitmap?>(null) }
    var ocrNumbers by remember { mutableStateOf<List<Double>>(emptyList()) }
    var ocrInProgress by remember { mutableStateOf(false) }

    var pendingCameraUri by remember { mutableStateOf<Uri?>(null) }
    val cameraLauncher = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        val uri = pendingCameraUri
        if (ok && uri != null) {
            scope.launch { photo = withContext(Dispatchers.IO) { loadBitmap(ctx, uri) } }
        }
    }

    val nKg = nominal.replace(',', '.').toDoubleOrNull()
    val aKg = actual.replace(',', '.').toDoubleOrNull()
    val result = remember(kind, nominal, actual) {
        if (nKg != null && aKg != null && nKg > 0) DoserSpec.check(kind, nKg, aKg) else null
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            InstructionCard(
                moduleKey = "doser_check",
                title = "Как проверить дозатор",
                sections = Instructions.DOSER_CHECK,
            )
        }
        item {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Text("Вид дозатора", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(4.dp))
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        items(DoserSpec.DoserKind.all().size) { idx ->
                            val k = DoserSpec.DoserKind.all()[idx]
                            FilterChip(
                                selected = k == kind,
                                onClick = { kind = k },
                                label = { Text(k.label, style = MaterialTheme.typography.labelSmall) }
                            )
                        }
                    }
                    Spacer(Modifier.height(4.dp))
                    Text("Допуск ±${(kind.toleranceFraction * 100).toInt()}% · ${kind.gostRef}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }

        item {
            OutlinedTextField(
                value = doserId, onValueChange = { doserId = it },
                label = { Text("№ дозатора (опц.)") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
        }

        item {
            OutlinedTextField(
                value = nominal, onValueChange = { nominal = it },
                label = { Text("Номинал (по карте подбора)") },
                suffix = { Text("кг") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                modifier = Modifier.fillMaxWidth()
            )
        }

        item {
            OutlinedTextField(
                value = actual, onValueChange = { actual = it },
                label = { Text("Фактический вес (по поверителю)") },
                suffix = { Text("кг") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                modifier = Modifier.fillMaxWidth()
            )
        }

        item {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Text("Фото дисплея весов (с OCR)",
                        style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(6.dp))
                    if (photo != null) {
                        androidx.compose.foundation.Image(
                            bitmap = photo!!.asImageBitmap(),
                            contentDescription = null,
                            modifier = Modifier.fillMaxWidth().height(140.dp)
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            onClick = {
                                val uri = createTempImageUri(ctx, "doser")
                                pendingCameraUri = uri
                                cameraLauncher.launch(uri)
                            },
                            modifier = Modifier.weight(1f),
                        ) {
                            Icon(Icons.Filled.CameraAlt, contentDescription = null,
                                modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("Сфотографировать")
                        }
                        if (photo != null) {
                            OutlinedButton(
                                onClick = {
                                    val bm = photo ?: return@OutlinedButton
                                    ocrInProgress = true
                                    scope.launch {
                                        try {
                                            val r = TextOcrHelper.recognize(bm)
                                            ocrNumbers = r.numbers
                                        } catch (e: Exception) {
                                            snackbar.showSnackbar("OCR ошибка: ${e.message}")
                                        } finally {
                                            ocrInProgress = false
                                        }
                                    }
                                },
                                enabled = !ocrInProgress,
                                modifier = Modifier.weight(1f),
                            ) {
                                Icon(Icons.Filled.AutoFixHigh, contentDescription = null,
                                    modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(4.dp))
                                Text(if (ocrInProgress) "..." else "Распознать")
                            }
                        }
                    }
                    if (ocrNumbers.isNotEmpty()) {
                        Spacer(Modifier.height(8.dp))
                        Text("Найденные значения (нажмите для подстановки):",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            items(ocrNumbers.size) { idx ->
                                val v = ocrNumbers[idx]
                                AssistChip(
                                    onClick = { actual = v.toString() },
                                    label = { Text("%.2f".format(v)) }
                                )
                            }
                        }
                    }
                }
            }
        }

        item {
            if (result != null) {
                val color = when (result.verdict) {
                    DoserSpec.Verdict.OK      -> MaterialTheme.colorScheme.primaryContainer
                    DoserSpec.Verdict.WARNING -> Color(0xFFFFE082)
                    DoserSpec.Verdict.FAIL    -> Color(0xFFFFCDD2)
                }
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = color)
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(result.verdict.label,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold)
                        Spacer(Modifier.height(8.dp))
                        Text("Отклонение: ${"%+.2f".format(result.absoluteErrorKg)} кг " +
                                "(${"%+.2f".format(result.relativeErrorPercent)}%)",
                            style = MaterialTheme.typography.bodyLarge)
                        Text("Допуск: ±${result.tolerancePercent.toInt()}%  ·  Допустимое отклонение: " +
                                "±${"%.2f".format(result.nominalKg * result.toleranceFraction)} кг",
                            style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }

        item {
            Button(
                onClick = {
                    val r = result ?: return@Button
                    scope.launch {
                        val extra = JSONObject().apply {
                            put("kind", kind.name)
                            put("kind_label", kind.label)
                            put("doser_id", doserId)
                            put("nominal_kg", r.nominalKg)
                            put("actual_kg", r.actualKg)
                            put("error_kg", r.absoluteErrorKg)
                            put("error_percent", r.relativeErrorPercent)
                            put("tolerance_percent", r.tolerancePercent)
                            put("verdict", r.verdict.name)
                            put("gost", kind.gostRef)
                        }
                        repo.save(
                            type = MeasurementType.DOSE_CHECK,
                            valueRaw = r.relativeErrorPercent,
                            unit = "%",
                            note = "${kind.label}${if (doserId.isNotBlank()) " #$doserId" else ""}: " +
                                    "номинал ${r.nominalKg}кг, факт ${r.actualKg}кг, " +
                                    "отклонение ${"%+.2f".format(r.relativeErrorPercent)}% [${r.verdict.label}]",
                            originalBitmap = photo,
                            gpsLat = gps?.latitude,
                            gpsLon = gps?.longitude,
                            extraJson = extra.toString(),
                        )
                        snackbar.showSnackbar("Проверка дозатора сохранена")
                    }
                },
                enabled = result != null,
                modifier = Modifier.fillMaxWidth().height(50.dp)
            ) {
                Icon(Icons.Filled.Save, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("Сохранить", style = MaterialTheme.typography.titleMedium)
            }
        }
    }
}

/* ============================================================================
   Tab 2 — Паспорт замеса
   ============================================================================ */
@Composable
private fun MixerBatchTab(
    ctx: Context,
    snackbar: SnackbarHostState,
    gps: GpsHelper.Coords?,
) {
    val repo = remember { MeasurementsRepository.get(ctx) }
    val scope = rememberCoroutineScope()

    var concreteClass by remember { mutableStateOf(MixerSpec.ConcreteClass.B30) }
    var cementGrade by remember { mutableStateOf(MixerSpec.CementGrade.CEM_42_5) }
    var normSet by remember { mutableStateOf(MixerSpec.NormSet.SP_28_13330) }

    var volume by remember { mutableStateOf("4.0") }
    var cement by remember { mutableStateOf("") }
    var sand by remember { mutableStateOf("") }
    var crushed by remember { mutableStateOf("") }
    var water by remember { mutableStateOf("") }
    var admix by remember { mutableStateOf("0") }

    fun p(s: String) = s.replace(',', '.').toDoubleOrNull() ?: 0.0
    val report = remember(concreteClass, cementGrade, normSet,
        volume, cement, sand, crushed, water, admix) {
        if (p(volume) > 0 && p(cement) > 0) {
            MixerSpec.analyze(MixerSpec.BatchInput(
                concreteClass = concreteClass,
                volumeM3 = p(volume),
                cementKg = p(cement),
                cementGrade = cementGrade,
                sandKg = p(sand),
                crushedKg = p(crushed),
                waterKg = p(water),
                admixtureKg = p(admix),
                normSet = normSet,
            ))
        } else null
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            InstructionCard(
                moduleKey = "mixer_batch",
                title = "Как проверить замес",
                sections = Instructions.MIXER_BATCH,
            )
        }
        item {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Text("Норматив", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(4.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        for (n in MixerSpec.NormSet.entries) {
                            FilterChip(
                                selected = n == normSet,
                                onClick = { normSet = n },
                                label = { Text(n.label, style = MaterialTheme.typography.labelSmall) }
                            )
                        }
                    }
                }
            }
        }

        item {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Text("Класс бетона", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(4.dp))
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        items(MixerSpec.ConcreteClass.all().size) { idx ->
                            val c = MixerSpec.ConcreteClass.all()[idx]
                            FilterChip(
                                selected = c == concreteClass,
                                onClick = { concreteClass = c },
                                label = { Text(c.label, style = MaterialTheme.typography.labelSmall) }
                            )
                        }
                    }
                    Spacer(Modifier.height(4.dp))
                    val minC = if (normSet == MixerSpec.NormSet.SP_28_13330)
                        concreteClass.minCementSp28 else concreteClass.minCementGost
                    val maxWc = if (normSet == MixerSpec.NormSet.SP_28_13330)
                        concreteClass.maxWcSp28 else concreteClass.maxWcGost
                    Text("Норма: цемент ≥ $minC кг/м³, В/Ц ≤ $maxWc",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }

        item {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Text("Марка цемента", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(4.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        for (g in MixerSpec.CementGrade.entries) {
                            FilterChip(
                                selected = g == cementGrade,
                                onClick = { cementGrade = g },
                                label = { Text(g.label, style = MaterialTheme.typography.labelSmall) }
                            )
                        }
                    }
                }
            }
        }

        item {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Text("Состав замеса", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(8.dp))
                    NumField(volume, { volume = it }, "Объём замеса", "м³")
                    Spacer(Modifier.height(8.dp))
                    NumField(cement, { cement = it }, "Цемент", "кг")
                    Spacer(Modifier.height(8.dp))
                    NumField(sand, { sand = it }, "Песок", "кг")
                    Spacer(Modifier.height(8.dp))
                    NumField(crushed, { crushed = it }, "Щебень суммарно", "кг")
                    Spacer(Modifier.height(8.dp))
                    NumField(water, { water = it }, "Вода", "кг")
                    Spacer(Modifier.height(8.dp))
                    NumField(admix, { admix = it }, "Добавки (СП и др.)", "кг")
                }
            }
        }

        item {
            if (report != null) {
                val color = if (report.passed)
                    MaterialTheme.colorScheme.primaryContainer
                else Color(0xFFFFCDD2)
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = color)
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(if (report.passed) "Соответствует нормативу"
                             else "Выявлены несоответствия",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold)
                        Spacer(Modifier.height(8.dp))
                        Text("Расход цемента: ${"%.0f".format(report.cementPerM3Kg)} кг/м³",
                            style = MaterialTheme.typography.bodyMedium)
                        Text("В/Ц: ${"%.3f".format(report.wcRatio)}",
                            style = MaterialTheme.typography.bodyMedium)
                        Text("Соотношение Щ/П: ${"%.2f".format(report.sandToCrushedRatio)}",
                            style = MaterialTheme.typography.bodyMedium)
                        Spacer(Modifier.height(8.dp))
                        for (chk in report.checks) {
                            val mark = if (chk.ok) "✓" else "✗"
                            val markColor = if (chk.ok) Color(0xFF1B5E20) else Color(0xFFC62828)
                            Row {
                                Text(mark, color = markColor, fontWeight = FontWeight.Bold,
                                    modifier = Modifier.padding(end = 6.dp))
                                Column {
                                    Text(chk.title, style = MaterialTheme.typography.bodySmall,
                                        fontWeight = FontWeight.SemiBold)
                                    Text("ожидается: ${chk.expected}",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    Text("факт: ${chk.actual}",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                            Spacer(Modifier.height(4.dp))
                        }
                    }
                }
            }
        }

        item {
            Button(
                onClick = {
                    val r = report ?: return@Button
                    scope.launch {
                        val extra = JSONObject().apply {
                            put("class", concreteClass.name)
                            put("cement_grade", cementGrade.name)
                            put("norm_set", normSet.name)
                            put("volume_m3", p(volume))
                            put("cement_kg", p(cement))
                            put("sand_kg", p(sand))
                            put("crushed_kg", p(crushed))
                            put("water_kg", p(water))
                            put("admix_kg", p(admix))
                            put("cement_per_m3", r.cementPerM3Kg)
                            put("wc_ratio", r.wcRatio)
                            put("passed", r.passed)
                        }
                        repo.save(
                            type = MeasurementType.MIXER_BATCH,
                            valueRaw = r.cementPerM3Kg,
                            unit = "кг/м³",
                            note = "Замес ${concreteClass.label}: цемент ${"%.0f".format(r.cementPerM3Kg)} кг/м³, " +
                                    "В/Ц ${"%.3f".format(r.wcRatio)} " +
                                    "[${if (r.passed) "OK" else "FAIL"}]",
                            gpsLat = gps?.latitude,
                            gpsLon = gps?.longitude,
                            extraJson = extra.toString(),
                        )
                        snackbar.showSnackbar("Замес сохранён")
                    }
                },
                enabled = report != null,
                modifier = Modifier.fillMaxWidth().height(50.dp)
            ) {
                Icon(Icons.Filled.Save, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("Сохранить", style = MaterialTheme.typography.titleMedium)
            }
        }
    }
}

/* ============================================================================
   Tab 3 — Влажность заполнителя
   ============================================================================ */
@Composable
private fun MoistureTab(
    ctx: Context,
    snackbar: SnackbarHostState,
    gps: GpsHelper.Coords?,
) {
    val repo = remember { MeasurementsRepository.get(ctx) }
    val scope = rememberCoroutineScope()

    var kind by remember { mutableStateOf(MoistureSpec.AggregateKind.SAND) }
    var wet by remember { mutableStateOf("") }
    var dry by remember { mutableStateOf("") }

    val wG = wet.replace(',', '.').toDoubleOrNull()
    val dG = dry.replace(',', '.').toDoubleOrNull()
    val result = remember(kind, wet, dry) {
        if (wG != null && dG != null) MoistureSpec.calculate(kind, wG, dG) else null
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            InstructionCard(
                moduleKey = "moisture",
                title = "Как замерить влажность",
                sections = Instructions.MOISTURE,
            )
        }
        item {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Text("Тип заполнителя", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(4.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        for (k in MoistureSpec.AggregateKind.entries) {
                            FilterChip(
                                selected = k == kind,
                                onClick = { kind = k },
                                label = { Text(k.label, style = MaterialTheme.typography.labelSmall) }
                            )
                        }
                    }
                    Spacer(Modifier.height(4.dp))
                    Text("Норма: ${kind.typicalRangeMin}–${kind.typicalRangeMax}%",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }

        item {
            NumField(wet, { wet = it }, "Масса влажная (m_влажн)", "г")
        }
        item {
            NumField(dry, { dry = it }, "Масса сухая (m_сухой)", "г")
        }

        item {
            if (result != null) {
                val color = if (result.warning != null) Color(0xFFFFE082)
                            else MaterialTheme.colorScheme.primaryContainer
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = color),
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text("W = ${"%.2f".format(result.moisturePercent)}%",
                            style = MaterialTheme.typography.headlineSmall,
                            fontWeight = FontWeight.Bold)
                        Text("Свободная вода: ${"%.1f".format(result.freeWaterG)} г " +
                                "(${"%.2f".format(result.freeWaterG / result.dryMassG * 1000)} л/т)",
                            style = MaterialTheme.typography.bodyMedium)
                        if (result.warning != null) {
                            Spacer(Modifier.height(8.dp))
                            Text("⚠ ${result.warning}",
                                style = MaterialTheme.typography.bodySmall,
                                fontWeight = FontWeight.SemiBold)
                        }
                    }
                }
            }
        }

        item {
            Button(
                onClick = {
                    val r = result ?: return@Button
                    scope.launch {
                        val extra = JSONObject().apply {
                            put("kind", kind.name)
                            put("wet_g", r.wetMassG)
                            put("dry_g", r.dryMassG)
                            put("free_water_g", r.freeWaterG)
                            put("moisture_percent", r.moisturePercent)
                        }
                        repo.save(
                            type = MeasurementType.MOISTURE,
                            valueRaw = r.moisturePercent,
                            unit = "%",
                            note = "${kind.label}: W = ${"%.2f".format(r.moisturePercent)}%" +
                                    if (r.warning != null) " (${r.warning})" else "",
                            gpsLat = gps?.latitude,
                            gpsLon = gps?.longitude,
                            extraJson = extra.toString(),
                        )
                        snackbar.showSnackbar("Замер сохранён")
                    }
                },
                enabled = result != null,
                modifier = Modifier.fillMaxWidth().height(50.dp),
            ) {
                Icon(Icons.Filled.Save, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("Сохранить", style = MaterialTheme.typography.titleMedium)
            }
        }
    }
}

/* ============================================================================
   Утилиты
   ============================================================================ */

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

private fun createTempImageUri(ctx: Context, prefix: String): Uri {
    val cacheDir = File(ctx.cacheDir, "camera").apply { mkdirs() }
    val file = File.createTempFile("${prefix}_", ".jpg", cacheDir)
    return FileProvider.getUriForFile(ctx, "${ctx.packageName}.fileprovider", file)
}

private fun loadBitmap(ctx: Context, uri: Uri): Bitmap? {
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
