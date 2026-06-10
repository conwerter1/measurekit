package ru.measurekit.util

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import kotlin.math.max
import kotlin.math.min

/**
 * Утилиты для рендера скриншотов разметки в bitmap.
 *
 * Генерируем картинку программно (а не делаем screen capture), потому что:
 *   - Скриншот экрана включает UI (TopAppBar, нижняя панель, навигация) - лишнее
 *   - Программный рендер дает чистую картинку: фото + разметка + значение
 *   - Можно использовать любые цвета и размеры независимо от состояния экрана
 *
 * Все функции возвращают bitmap, который потом сжимается репозиторием.
 */
object MarkupRenderer {

    private const val OUTPUT_LONG_SIDE = 1280  // целевой размер по длинной стороне
    private const val FOOTER_HEIGHT_RATIO = 0.07f  // высота нижней полосы со значением

    /* ---- Фото с эталоном (PHOTO): фото + 4 точки A1,A2,B1,B2 + линии + значение ---- */

    fun renderPhotoMarkup(
        sourceImage: ImageBitmap,
        // 4 точки в относительных координатах [0..1] относительно исходного изображения
        refA: Offset, refB: Offset, objA: Offset, objB: Offset,
        valueText: String,
        referenceText: String
    ): Bitmap {
        val (out, drawW, drawH, drawX, drawY, footerH) = prepareCanvas(sourceImage)
        val canvas = Canvas(out)

        // Точки эталона - бирюзовый
        val refColor = 0xFF26C6DA.toInt()
        val objColor = 0xFF4285F4.toInt()

        val refAp = relTo(refA, drawX, drawY, drawW, drawH)
        val refBp = relTo(refB, drawX, drawY, drawW, drawH)
        val objAp = relTo(objA, drawX, drawY, drawW, drawH)
        val objBp = relTo(objB, drawX, drawY, drawW, drawH)

        // Линия эталона (пунктир)
        val refPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = refColor; strokeWidth = 5f; style = Paint.Style.STROKE
            pathEffect = DashPathEffect(floatArrayOf(18f, 12f), 0f)
        }
        canvas.drawLine(refAp.x, refAp.y, refBp.x, refBp.y, refPaint)

        // Линия объекта (сплошная)
        val objPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = objColor; strokeWidth = 5f; style = Paint.Style.STROKE
        }
        canvas.drawLine(objAp.x, objAp.y, objBp.x, objBp.y, objPaint)

        drawPointMarker(canvas, refAp, refColor, "A1")
        drawPointMarker(canvas, refBp, refColor, "A2")
        drawPointMarker(canvas, objAp, objColor, "B1")
        drawPointMarker(canvas, objBp, objColor, "B2")

        drawFooter(canvas, out.width, out.height, footerH, valueText, referenceText)
        return out
    }

    /* ---- Площадь, простой режим (AREA simple): фото + эталон A1,A2 + полигон V1..Vn + значение ---- */

    fun renderAreaSimpleMarkup(
        sourceImage: ImageBitmap,
        refA: Offset, refB: Offset,
        vertices: List<Offset>,
        valueText: String,
        referenceText: String
    ): Bitmap {
        val (out, drawW, drawH, drawX, drawY, footerH) = prepareCanvas(sourceImage)
        val canvas = Canvas(out)
        val refColor = 0xFF26C6DA.toInt()
        val polyColor = 0xFF4285F4.toInt()

        val refAp = relTo(refA, drawX, drawY, drawW, drawH)
        val refBp = relTo(refB, drawX, drawY, drawW, drawH)
        val refPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = refColor; strokeWidth = 5f; style = Paint.Style.STROKE
            pathEffect = DashPathEffect(floatArrayOf(18f, 12f), 0f)
        }
        canvas.drawLine(refAp.x, refAp.y, refBp.x, refBp.y, refPaint)
        drawPointMarker(canvas, refAp, refColor, "A1")
        drawPointMarker(canvas, refBp, refColor, "A2")

        if (vertices.isNotEmpty()) {
            val canvasV = vertices.map { relTo(it, drawX, drawY, drawW, drawH) }
            if (canvasV.size >= 3) {
                val path = Path().apply {
                    moveTo(canvasV[0].x, canvasV[0].y)
                    for (i in 1 until canvasV.size) lineTo(canvasV[i].x, canvasV[i].y)
                    close()
                }
                // Заливка
                val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = polyColor; alpha = 50; style = Paint.Style.FILL
                }
                canvas.drawPath(path, fillPaint)
                // Контур
                val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = polyColor; strokeWidth = 5f; style = Paint.Style.STROKE
                }
                canvas.drawPath(path, strokePaint)
            } else if (canvasV.size == 2) {
                val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = polyColor; strokeWidth = 5f
                }
                canvas.drawLine(canvasV[0].x, canvasV[0].y, canvasV[1].x, canvasV[1].y, linePaint)
            }
            canvasV.forEachIndexed { idx, pt ->
                drawPointMarker(canvas, pt, polyColor, "V${idx + 1}")
            }
        }

        drawFooter(canvas, out.width, out.height, footerH, valueText, referenceText)
        return out
    }

    /* ---- Площадь, точный режим (4 точки + полигон) ---- */

    fun renderAreaHomographyMarkup(
        sourceImage: ImageBitmap,
        refC1: Offset, refC2: Offset, refC3: Offset, refC4: Offset,
        vertices: List<Offset>,
        valueText: String,
        referenceText: String
    ): Bitmap {
        val (out, drawW, drawH, drawX, drawY, footerH) = prepareCanvas(sourceImage)
        val canvas = Canvas(out)
        val refColor = 0xFF26C6DA.toInt()
        val polyColor = 0xFF4285F4.toInt()

        val c1 = relTo(refC1, drawX, drawY, drawW, drawH)
        val c2 = relTo(refC2, drawX, drawY, drawW, drawH)
        val c3 = relTo(refC3, drawX, drawY, drawW, drawH)
        val c4 = relTo(refC4, drawX, drawY, drawW, drawH)
        val refPath = Path().apply {
            moveTo(c1.x, c1.y); lineTo(c2.x, c2.y)
            lineTo(c3.x, c3.y); lineTo(c4.x, c4.y); close()
        }
        // Лёгкая заливка эталона
        val refFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = refColor; alpha = 30; style = Paint.Style.FILL
        }
        canvas.drawPath(refPath, refFillPaint)
        // Пунктирный контур
        val refStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = refColor; strokeWidth = 4f; style = Paint.Style.STROKE
            pathEffect = DashPathEffect(floatArrayOf(18f, 12f), 0f)
        }
        canvas.drawPath(refPath, refStrokePaint)
        drawPointMarker(canvas, c1, refColor, "C1")
        drawPointMarker(canvas, c2, refColor, "C2")
        drawPointMarker(canvas, c3, refColor, "C3")
        drawPointMarker(canvas, c4, refColor, "C4")

        if (vertices.isNotEmpty()) {
            val canvasV = vertices.map { relTo(it, drawX, drawY, drawW, drawH) }
            if (canvasV.size >= 3) {
                val path = Path().apply {
                    moveTo(canvasV[0].x, canvasV[0].y)
                    for (i in 1 until canvasV.size) lineTo(canvasV[i].x, canvasV[i].y)
                    close()
                }
                val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = polyColor; alpha = 50; style = Paint.Style.FILL
                }
                canvas.drawPath(path, fillPaint)
                val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = polyColor; strokeWidth = 5f; style = Paint.Style.STROKE
                }
                canvas.drawPath(path, strokePaint)
            }
            canvasV.forEachIndexed { idx, pt ->
                drawPointMarker(canvas, pt, polyColor, "V${idx + 1}")
            }
        }

        drawFooter(canvas, out.width, out.height, footerH, valueText, referenceText)
        return out
    }

    /* ---- Дальномер: текстовая карточка с углом и расстоянием на чёрном фоне ---- */
    /* Нет реального снимка - просто компонуем визуальную карточку для истории */

    fun renderRangefinderCard(
        valueText: String,
        angleBottom: Float?,
        angleTop: Float?,
        height: Float?,
        eyeHeightCm: Int
    ): Bitmap {
        val w = 1024
        val h = 768
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        canvas.drawColor(Color.BLACK)

        // Лёгкое перекрестие в центре (имитация прицела)
        val crossColor = 0xFF4285F4.toInt()
        val cx = w / 2f
        val cy = h * 0.35f
        val crossPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = crossColor; strokeWidth = 3f
        }
        canvas.drawLine(cx - 60, cy, cx - 12, cy, crossPaint)
        canvas.drawLine(cx + 12, cy, cx + 60, cy, crossPaint)
        canvas.drawLine(cx, cy - 60, cx, cy - 12, crossPaint)
        canvas.drawLine(cx, cy + 12, cx, cy + 60, crossPaint)
        val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = crossColor; alpha = 100; strokeWidth = 2f; style = Paint.Style.STROKE
        }
        canvas.drawCircle(cx, cy, 70f, ringPaint)

        // Большой результат
        val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = 100f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            textAlign = Paint.Align.CENTER
        }
        canvas.drawText(valueText, cx, h * 0.62f, titlePaint)

        // Параметры
        val paramPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFFCCCCCC.toInt()
            textSize = 32f
            textAlign = Paint.Align.CENTER
        }
        var line = h * 0.74f
        canvas.drawText("Высота камеры: $eyeHeightCm см", cx, line, paramPaint)
        line += 42f
        if (angleBottom != null) {
            canvas.drawText("Угол низ: %.1f°".format(angleBottom), cx, line, paramPaint)
            line += 42f
        }
        if (angleTop != null) {
            canvas.drawText("Угол верх: %.1f°".format(angleTop), cx, line, paramPaint)
            line += 42f
        }
        if (height != null) {
            canvas.drawText("Высота объекта: %.2f м".format(height), cx, line, paramPaint)
        }

        return out
    }

    /* ---- Helpers ---- */

    private data class CanvasInfo(
        val out: Bitmap,
        val drawW: Float,
        val drawH: Float,
        val drawX: Float,
        val drawY: Float,
        val footerH: Float
    )

    /**
     * Создаёт bitmap нужного размера с уже отрисованным фоновым изображением (вписано "fit"),
     * чёрной рамкой по краям и зарезервированной нижней полосой под значение.
     */
    private fun prepareCanvas(sourceImage: ImageBitmap): CanvasInfo {
        val srcBmp = sourceImage.asAndroidBitmap()
        val srcW = srcBmp.width.toFloat()
        val srcH = srcBmp.height.toFloat()

        // Целевые размеры: длинная сторона = OUTPUT_LONG_SIDE
        val scale = OUTPUT_LONG_SIDE / max(srcW, srcH)
        val outW = (srcW * scale).toInt().coerceAtLeast(640)
        val photoH = (srcH * scale).toInt().coerceAtLeast(480)
        val footerH = (photoH * FOOTER_HEIGHT_RATIO).coerceAtLeast(56f)
        val outH = photoH + footerH.toInt()

        val out = Bitmap.createBitmap(outW, outH, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        canvas.drawColor(Color.BLACK)

        // Фото вписано без искажения (но с заполнением всей зоны фото)
        val fitScale = min(outW.toFloat() / srcW, photoH.toFloat() / srcH)
        val drawW = srcW * fitScale
        val drawH = srcH * fitScale
        val drawX = (outW - drawW) / 2f
        val drawY = (photoH - drawH) / 2f
        val rect = RectF(drawX, drawY, drawX + drawW, drawY + drawH)
        canvas.drawBitmap(srcBmp, null, rect, Paint(Paint.FILTER_BITMAP_FLAG))

        return CanvasInfo(out, drawW, drawH, drawX, drawY, footerH)
    }

    /**
     * rel - относительные координаты [0..1] в зоне фото на канвасе.
     * Преобразуем в абсолютные.
     *
     * ВАЖНО: для Простого режима Площади/Фото точки хранятся в координатах [0..1]
     * относительно ВСЕГО ВИДИМОГО КАНВАСА (а не самого изображения).
     * Поскольку видимый канвас на телефоне = вся доступная зона под фото в режиме fit,
     * формулу делаю универсальной - использую "fit"-зону в выходном bitmap.
     */
    private fun relTo(rel: Offset, drawX: Float, drawY: Float, drawW: Float, drawH: Float): Offset {
        return Offset(drawX + rel.x * drawW, drawY + rel.y * drawH)
    }

    private fun drawPointMarker(canvas: Canvas, pos: Offset, color: Int, label: String) {
        val whitePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = Color.WHITE; style = Paint.Style.FILL }
        val colorPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color; style = Paint.Style.FILL }
        // Внешний кружок
        canvas.drawCircle(pos.x, pos.y, 22f, whitePaint)
        canvas.drawCircle(pos.x, pos.y, 19f, colorPaint)
        // Точка в центре
        canvas.drawCircle(pos.x, pos.y, 5f, whitePaint)

        // Подпись на чёрном фоне
        val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = Color.WHITE
            textSize = 22f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }
        val textBounds = Rect()
        textPaint.getTextBounds(label, 0, label.length, textBounds)
        val labelX = pos.x + 28f
        val labelY = pos.y - 22f
        val padding = 6f
        val bgPaint = Paint().apply {
            this.color = Color.BLACK
            alpha = 180
        }
        canvas.drawRoundRect(
            labelX - padding,
            labelY - textBounds.height() - padding,
            labelX + textBounds.width() + padding,
            labelY + padding,
            6f, 6f, bgPaint
        )
        canvas.drawText(label, labelX, labelY, textPaint)
    }

    /** Нижняя информационная полоса со значением и названием эталона. */
    private fun drawFooter(
        canvas: Canvas, w: Int, h: Int, footerH: Float,
        valueText: String, referenceText: String
    ) {
        val footerTop = h - footerH
        val footerPaint = Paint().apply {
            color = 0xFF1A1A1A.toInt()
        }
        canvas.drawRect(0f, footerTop, w.toFloat(), h.toFloat(), footerPaint)

        val valuePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = footerH * 0.45f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }
        canvas.drawText(valueText, 24f, footerTop + footerH * 0.55f, valuePaint)

        val refPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFFAAAAAA.toInt()
            textSize = footerH * 0.25f
        }
        canvas.drawText(referenceText, 24f, footerTop + footerH * 0.85f, refPaint)
    }

    /* ---- Связка арматуры (REBAR): фото с пронумерованными кружками + кол-во и тип арматуры ---- */

    fun renderRebarMarkup(
        sourceImage: ImageBitmap,
        // points в КООРДИНАТАХ ИСХОДНОГО ФОТО (пиксели)
        points: List<ru.measurekit.ui.screen.rebar.ImagePoint>,
        valueText: String,
        bundleInfo: String
    ): Bitmap {
        val (out, drawW, drawH, drawX, drawY, footerH) = prepareCanvas(sourceImage)
        val canvas = Canvas(out)
        val markerColor = 0xFF4285F4.toInt()
        val whiteRing = Color.WHITE

        // Масштаб от исходного фото к выходному bitmap
        val scaleX = drawW / sourceImage.width.toFloat()
        val scaleY = drawH / sourceImage.height.toFloat()
        val scale = (scaleX + scaleY) / 2f
        val numberPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = 16f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            textAlign = Paint.Align.CENTER
        }

        points.forEachIndexed { idx, p ->
            val cx = drawX + p.cxImg * scaleX
            val cy = drawY + p.cyImg * scaleY
            val r = max(p.rImg * scale, 12f)
            // Полупрозрачная заливка
            val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = markerColor; alpha = 60; style = Paint.Style.FILL
            }
            canvas.drawCircle(cx, cy, r, fillPaint)
            // Контур белый
            val whitePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = whiteRing; strokeWidth = 2f; style = Paint.Style.STROKE
            }
            canvas.drawCircle(cx, cy, r, whitePaint)
            val colorPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = markerColor; strokeWidth = 2f; style = Paint.Style.STROKE
            }
            canvas.drawCircle(cx, cy, r - 1.5f, colorPaint)
            // Номер - только если кружок не очень мелкий
            if (r > 14f) {
                canvas.drawText("${idx + 1}", cx, cy + 5f, numberPaint)
            }
        }

        drawFooter(canvas, out.width, out.height, footerH, valueText, bundleInfo)
        return out
    }

    /**
     * Конвертирует ImageBitmap в android Bitmap для сохранения как "оригинал".
     * Просто извлекает underlying bitmap.
     */
    fun imageBitmapToBitmap(image: ImageBitmap): Bitmap {
        return image.asAndroidBitmap()
    }
}
