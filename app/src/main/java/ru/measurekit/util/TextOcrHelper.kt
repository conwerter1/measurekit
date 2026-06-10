package ru.measurekit.util

import android.graphics.Bitmap
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Распознавание текста на изображениях через Google ML Kit Text Recognition v2.
 *
 * Используется bundled-модель — работает полностью офлайн (TFLite),
 * +6 МБ к APK, ~50-200 мс инференс на современных Android.
 *
 * Применение:
 *  - OCR показаний весов / дисплея пульта дозатора (быстрая подстановка значений
 *    в форму проверки дозатора).
 *  - OCR текста с сертификатов и паспортов качества (партия, дата, маркировка).
 *  - OCR накладных и отгрузочных документов.
 *
 * Латинский распознаватель умеет читать также цифры и кириллицу частично.
 * Для качественного распознавания кириллицы можно подключить отдельный
 * расширенный распознаватель в дальнейшем.
 */
object TextOcrHelper {

    /** Распознанный фрагмент с координатами на исходном изображении. */
    data class TextBlock(
        val text: String,
        val left: Int, val top: Int, val right: Int, val bottom: Int,
        val confidence: Float? = null,
    )

    /** Полный результат распознавания. */
    data class OcrResult(
        val fullText: String,
        val blocks: List<TextBlock>,
        val numbers: List<Double>,    // выделенные числовые значения
    )

    @Volatile
    private var recognizer: TextRecognizer? = null

    private fun getRecognizer(): TextRecognizer {
        return recognizer ?: synchronized(this) {
            recognizer ?: TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
                .also { recognizer = it }
        }
    }

    /**
     * Запуск распознавания. Возвращает структурированный результат.
     * Бросает исключение при ошибке ML Kit (например, модель ещё не скачана).
     */
    suspend fun recognize(bitmap: Bitmap): OcrResult = suspendCancellableCoroutine { cont ->
        val rec = getRecognizer()
        val img = InputImage.fromBitmap(bitmap, 0)

        rec.process(img)
            .addOnSuccessListener { visionText ->
                val blocks = mutableListOf<TextBlock>()
                for (block in visionText.textBlocks) {
                    for (line in block.lines) {
                        val rect = line.boundingBox ?: continue
                        blocks.add(TextBlock(
                            text = line.text,
                            left = rect.left, top = rect.top,
                            right = rect.right, bottom = rect.bottom,
                            confidence = null,  // в ML Kit confidence на line недоступен напрямую
                        ))
                    }
                }
                val numbers = extractNumbers(visionText.text)
                cont.resume(OcrResult(
                    fullText = visionText.text,
                    blocks = blocks,
                    numbers = numbers,
                ))
            }
            .addOnFailureListener { e -> cont.resumeWithException(e) }
    }

    /**
     * Извлекает числа из текста. Полезно для OCR показаний весов и дисплеев.
     * Поддерживает форматы: "1234", "12.34", "12,34", "1 234,56", "12 345.67".
     * Возвращает уникальные числа в порядке появления в тексте.
     */
    fun extractNumbers(text: String): List<Double> {
        // Регэкс для целого + дробной части с разделителями (точка/запятая)
        // Учитываем что разделитель тысяч может быть пробелом или неразрывным пробелом.
        val pattern = Regex("""(?<![A-Za-zА-Яа-я.])(\d{1,3}(?:[\s\u00A0]\d{3})*|\d+)([.,]\d+)?(?![A-Za-zА-Яа-я])""")
        val seen = mutableSetOf<Double>()
        val result = mutableListOf<Double>()
        for (match in pattern.findAll(text)) {
            val raw = match.value.replace("\u00A0", "").replace(" ", "").replace(',', '.')
            val v = raw.toDoubleOrNull() ?: continue
            if (seen.add(v)) result.add(v)
        }
        return result
    }

    /** Освобождение ресурсов (вызывать при завершении работы экрана). */
    fun release() {
        recognizer?.close()
        recognizer = null
    }
}
