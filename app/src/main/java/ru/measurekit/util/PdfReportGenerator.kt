package ru.measurekit.util

import android.content.Context
import android.graphics.*
import android.graphics.pdf.PdfDocument
import android.graphics.pdf.PdfDocument.PageInfo
import androidx.core.graphics.scale
import ru.measurekit.domain.audit.ChecklistTemplates
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Генератор PDF-отчётов по чек-листам аудита.
 *
 * Использует встроенный Android PdfDocument API — без сторонних зависимостей.
 * Поддерживает:
 *   - А4 портрет (595 × 842 pt)
 *   - кириллицу (через системные шрифты)
 *   - таблицу разделов и пунктов с цветными статус-маркерами
 *   - встраивание фото с автоматическим масштабированием
 *   - автоматическое разбиение на страницы по необходимости
 *
 * Структура отчёта:
 *   Стр. 1: титул + сводка (количество OK / Замечаний / Несоответствий) + GPS + дата
 *   Стр. 2..N: содержимое чек-листа с фото
 *   Стр. N+1: место для подписей
 */
object PdfReportGenerator {

    private const val PAGE_W = 595
    private const val PAGE_H = 842
    private const val MARGIN = 40
    private val CONTENT_W = PAGE_W - 2 * MARGIN

    private const val COLOR_BLACK = 0xFF212121.toInt()
    private const val COLOR_GREY  = 0xFF616161.toInt()
    private const val COLOR_GREEN = 0xFF1B5E20.toInt()
    private const val COLOR_AMBER = 0xFFF57F17.toInt()
    private const val COLOR_RED   = 0xFFC62828.toInt()
    private const val COLOR_BLUE  = 0xFF0D47A1.toInt()

    /** Метаданные акта. */
    data class ReportMeta(
        val templateKind: ChecklistTemplates.TemplateKind,
        val objectName: String,
        val auditorName: String,
        val organizationName: String,
        val auditDate: Long,
        val gpsLat: Double? = null,
        val gpsLon: Double? = null,
        val notes: String = "",
    )

    /** Заполненный пункт чек-листа. */
    data class FilledItem(
        val item: ChecklistTemplates.Item,
        val sectionName: String,
        val answer: ChecklistTemplates.Answer,
        val comment: String,
        val photoPath: String? = null,
        val timestamp: Long = 0L,
    )

    /**
     * Генерация полного PDF-акта.
     */
    fun generate(
        ctx: Context,
        meta: ReportMeta,
        items: List<FilledItem>,
        outputFile: File,
    ): File {
        val pdf = PdfDocument()
        try {
            renderCoverPage(pdf, meta, items)
            renderContentPages(pdf, meta, items)
            renderSignaturePage(pdf, meta)

            outputFile.parentFile?.mkdirs()
            FileOutputStream(outputFile).use { out -> pdf.writeTo(out) }
        } finally {
            pdf.close()
        }
        return outputFile
    }

    /* ============== Страница 1: ТИТУЛ ============== */
    private fun renderCoverPage(
        pdf: PdfDocument,
        meta: ReportMeta,
        items: List<FilledItem>,
    ) {
        val page = pdf.startPage(PageInfo.Builder(PAGE_W, PAGE_H, 1).create())
        val c = page.canvas
        var y = MARGIN.toFloat() + 50f

        // Заголовок
        c.drawText("АКТ ПРОВЕРКИ", PAGE_W / 2f, y,
            centerPaint(textPaint(20f, COLOR_BLACK, bold = true)))
        y += 26f
        c.drawText(meta.templateKind.label, PAGE_W / 2f, y,
            centerPaint(textPaint(12f, COLOR_BLUE, bold = true)))
        y += 50f

        // Реквизиты
        val df = SimpleDateFormat("dd.MM.yyyy", Locale.getDefault())
        val tf = SimpleDateFormat("HH:mm", Locale.getDefault())
        val labelP = textPaint(11f, COLOR_GREY)
        val valueP = textPaint(12f, COLOR_BLACK)

        fun row(label: String, value: String) {
            c.drawText(label, MARGIN.toFloat(), y, labelP)
            // value может быть длинным — делаем перенос если выходит за края
            val maxValueW = (CONTENT_W - 130).toFloat()
            val lines = wrapText(value, valueP, maxValueW)
            for ((idx, ln) in lines.withIndex()) {
                c.drawText(ln, MARGIN + 130f, y + idx * 16f, valueP)
            }
            y += (16f * lines.size).coerceAtLeast(22f)
        }

        if (meta.objectName.isNotBlank())     row("Объект:",      meta.objectName)
        if (meta.organizationName.isNotBlank()) row("Организация:", meta.organizationName)
        if (meta.auditorName.isNotBlank())    row("Аудитор:",     meta.auditorName)
        row("Дата проверки:", "${df.format(Date(meta.auditDate))} ${tf.format(Date(meta.auditDate))}")
        if (meta.gpsLat != null && meta.gpsLon != null) {
            val ns = if (meta.gpsLat >= 0) "N" else "S"
            val ew = if (meta.gpsLon >= 0) "E" else "W"
            row("GPS:", "%.6f° %s, %.6f° %s".format(
                Math.abs(meta.gpsLat), ns, Math.abs(meta.gpsLon), ew))
        }
        y += 16f

        // Сводка
        c.drawText("Сводка результатов", MARGIN.toFloat(), y,
            textPaint(13f, COLOR_BLACK, bold = true))
        y += 22f

        val ok      = items.count { it.answer == ChecklistTemplates.Answer.OK }
        val note    = items.count { it.answer == ChecklistTemplates.Answer.NOTE }
        val fail    = items.count { it.answer == ChecklistTemplates.Answer.FAIL }
        val na      = items.count { it.answer == ChecklistTemplates.Answer.NA }
        val pending = items.count { it.answer == ChecklistTemplates.Answer.PENDING }

        fun stat(color: Int, label: String, count: Int) {
            val p = Paint().apply { this.color = color; isAntiAlias = true }
            c.drawCircle(MARGIN + 8f, y - 5f, 7f, p)
            c.drawText("$label: $count", MARGIN + 22f, y, valueP)
            y += 20f
        }
        stat(COLOR_GREEN, "Соответствует", ok)
        stat(COLOR_AMBER, "Замечания", note)
        stat(COLOR_RED, "Несоответствия", fail)
        stat(COLOR_GREY, "Не применимо", na)
        if (pending > 0) stat(0xFFBDBDBD.toInt(), "Не проверено", pending)
        y += 16f

        // Заключение
        val verdict = when {
            fail > 0 -> "ВЫЯВЛЕНЫ НЕСООТВЕТСТВИЯ" to COLOR_RED
            note > 0 -> "ВЫЯВЛЕНЫ ЗАМЕЧАНИЯ" to COLOR_AMBER
            ok > 0   -> "ОБЪЕКТ СООТВЕТСТВУЕТ ТРЕБОВАНИЯМ" to COLOR_GREEN
            else     -> "ПРОВЕРКА НЕ ЗАВЕРШЕНА" to COLOR_GREY
        }
        c.drawText(verdict.first, PAGE_W / 2f, y,
            centerPaint(textPaint(14f, verdict.second, bold = true)))
        y += 30f

        // Общие заметки
        if (meta.notes.isNotBlank()) {
            c.drawText("Общие замечания:", MARGIN.toFloat(), y,
                textPaint(11f, COLOR_BLACK, bold = true))
            y += 16f
            val notesP = textPaint(10f, COLOR_BLACK)
            for (line in wrapText(meta.notes, notesP, CONTENT_W.toFloat())) {
                c.drawText(line, MARGIN.toFloat(), y, notesP)
                y += 14f
            }
        }

        // Footer
        c.drawText("Создано в MeasureKit · ${df.format(Date(meta.auditDate))}",
            PAGE_W / 2f, (PAGE_H - MARGIN).toFloat(),
            centerPaint(textPaint(9f, COLOR_GREY)))

        pdf.finishPage(page)
    }

    /* ============== Стр. 2..N: КОНТЕНТ ============== */
    private fun renderContentPages(
        pdf: PdfDocument,
        meta: ReportMeta,
        items: List<FilledItem>,
    ) {
        var pageNum = 2
        var page = pdf.startPage(PageInfo.Builder(PAGE_W, PAGE_H, pageNum).create())
        var c = page.canvas
        var y = MARGIN.toFloat()

        // Заголовок страницы
        c.drawText(meta.templateKind.label, MARGIN.toFloat(), y,
            textPaint(11f, COLOR_GREY))
        y += 25f

        // Группируем по разделам
        val grouped = items.groupBy { it.sectionName }
        for ((section, sectionItems) in grouped) {
            // Если осталось слишком мало места — новая страница
            if (y > PAGE_H - 100) {
                pdf.finishPage(page)
                pageNum++
                page = pdf.startPage(PageInfo.Builder(PAGE_W, PAGE_H, pageNum).create())
                c = page.canvas
                y = MARGIN.toFloat()
            }
            // Заголовок раздела
            c.drawText(section, MARGIN.toFloat(), y,
                textPaint(13f, COLOR_BLUE, bold = true))
            y += 18f
            val linePaint = Paint().apply { color = COLOR_BLUE; strokeWidth = 1.5f }
            c.drawLine(MARGIN.toFloat(), y - 8f, (PAGE_W - MARGIN).toFloat(), y - 8f, linePaint)
            y += 4f

            for (it in sectionItems) {
                // Резерв для пункта (с фото — больше)
                val needHeight = if (!it.photoPath.isNullOrBlank()) 200f else 80f
                if (y + needHeight > PAGE_H - MARGIN) {
                    pdf.finishPage(page)
                    pageNum++
                    page = pdf.startPage(PageInfo.Builder(PAGE_W, PAGE_H, pageNum).create())
                    c = page.canvas
                    y = MARGIN.toFloat()
                }
                y = renderItem(c, it, y)
                y += 10f
            }
            y += 6f
        }

        pdf.finishPage(page)
    }

    private fun renderItem(c: Canvas, it: FilledItem, startY: Float): Float {
        var y = startY

        // Цветной маркер слева
        val markerColor = it.answer.color.toInt()
        val markerP = Paint().apply { color = markerColor; isAntiAlias = true }
        c.drawRect(MARGIN.toFloat(), y - 12f, MARGIN + 4f, y + 4f, markerP)

        // Метка ответа справа
        val ansLabel = it.answer.label.uppercase()
        c.drawText(ansLabel, (PAGE_W - MARGIN - 90).toFloat(), y,
            textPaint(10f, markerColor, bold = true))

        // Вопрос
        val qP = textPaint(11f, COLOR_BLACK, bold = true)
        for (line in wrapText(it.item.question, qP, CONTENT_W - 100f)) {
            c.drawText(line, MARGIN + 12f, y, qP)
            y += 14f
        }

        // Метаданные (severity + ГОСТ)
        val metaLine = listOfNotNull(
            it.item.severity.label,
            it.item.gostRef,
        ).joinToString(" · ")
        if (metaLine.isNotEmpty()) {
            c.drawText(metaLine, MARGIN + 12f, y, textPaint(9f, COLOR_GREY))
            y += 13f
        }

        // Комментарий
        if (it.comment.isNotBlank()) {
            val cmtP = textPaint(10f, COLOR_BLACK)
            for (line in wrapText("Комментарий: ${it.comment}", cmtP, CONTENT_W - 12f)) {
                c.drawText(line, MARGIN + 12f, y, cmtP)
                y += 13f
            }
        }

        // Фото
        if (!it.photoPath.isNullOrBlank()) {
            try {
                val bm = BitmapFactory.decodeFile(it.photoPath)
                if (bm != null) {
                    val photoH = 110f
                    val photoW = (photoH * bm.width / bm.height.toFloat())
                        .coerceAtMost(CONTENT_W - 12f)
                    val scaled = bm.scale(photoW.toInt(), photoH.toInt())
                    c.drawBitmap(scaled, MARGIN + 12f, y, null)
                    y += photoH + 5f
                    if (scaled !== bm) scaled.recycle()
                    bm.recycle()
                }
            } catch (e: Exception) { /* пропускаем сломанное фото */ }
        }

        return y
    }

    /* ============== Стр. N+1: ПОДПИСИ ============== */
    private fun renderSignaturePage(pdf: PdfDocument, meta: ReportMeta) {
        val pageNum = pdf.pages.size + 1
        val page = pdf.startPage(PageInfo.Builder(PAGE_W, PAGE_H, pageNum).create())
        val c = page.canvas
        var y = MARGIN.toFloat() + 60f

        c.drawText("Подписи сторон", PAGE_W / 2f, y,
            centerPaint(textPaint(16f, COLOR_BLACK, bold = true)))
        y += 60f

        c.drawText("Аудитор: ${meta.auditorName.ifBlank { "_______________" }}",
            MARGIN.toFloat(), y, textPaint(11f, COLOR_BLACK))
        y += 80f
        c.drawLine(MARGIN.toFloat(), y, MARGIN + 200f, y,
            Paint().apply { strokeWidth = 1f })
        c.drawText("(подпись)", MARGIN.toFloat(), y + 12f, textPaint(8f, COLOR_GREY))
        y += 60f

        c.drawText("Представитель объекта: _______________________",
            MARGIN.toFloat(), y, textPaint(11f, COLOR_BLACK))
        y += 80f
        c.drawLine(MARGIN.toFloat(), y, MARGIN + 200f, y,
            Paint().apply { strokeWidth = 1f })
        c.drawText("(подпись)", MARGIN.toFloat(), y + 12f, textPaint(8f, COLOR_GREY))

        pdf.finishPage(page)
    }

    /* ============== Утилиты ============== */
    private fun textPaint(size: Float, color: Int, bold: Boolean = false): Paint = Paint().apply {
        this.color = color
        this.textSize = size
        this.isAntiAlias = true
        this.typeface = if (bold) Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
                        else Typeface.SANS_SERIF
    }

    private fun centerPaint(base: Paint): Paint = Paint(base).apply {
        textAlign = Paint.Align.CENTER
    }

    private fun wrapText(text: String, paint: Paint, maxWidth: Float): List<String> {
        if (text.isEmpty()) return emptyList()
        val lines = mutableListOf<String>()
        for (raw in text.split("\n")) {
            val words = raw.split(Regex("\\s+"))
            var current = StringBuilder()
            for (word in words) {
                val candidate = if (current.isEmpty()) word else "$current $word"
                if (paint.measureText(candidate) <= maxWidth) {
                    if (current.isEmpty()) current.append(word) else current.append(' ').append(word)
                } else {
                    if (current.isNotEmpty()) lines.add(current.toString())
                    current = StringBuilder(word)
                }
            }
            if (current.isNotEmpty()) lines.add(current.toString())
            if (raw.isEmpty()) lines.add("")
        }
        return lines
    }
}
