package ru.measurekit.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import kotlinx.coroutines.flow.Flow
import java.io.File
import java.io.FileOutputStream
import kotlin.math.max

/**
 * Singleton-репозиторий для работы с измерениями.
 *
 * Также управляет файлами изображений (скриншоты разметки + оригиналы фото).
 * Файлы хранятся в filesDir/measurements/ — приватная папка приложения.
 *
 * Параметры сжатия (выбраны "максимум" сжатия по запросу пользователя):
 *   - Скриншот разметки: max длинная сторона 1280px, JPEG quality 50
 *   - Оригинал фото:     max длинная сторона 1280px, JPEG quality 50
 * Дает примерно 80–200 КБ на файл — вполне приемлемо для сотен измерений.
 */
class MeasurementsRepository private constructor(
    private val ctx: Context,
    private val dao: MeasurementDao
) {

    private val imagesDir: File by lazy {
        File(ctx.filesDir, "measurements").apply { mkdirs() }
    }

    fun observeAll(): Flow<List<MeasurementEntity>> = dao.observeAll()

    fun observeByType(type: String): Flow<List<MeasurementEntity>> = dao.observeByType(type)

    suspend fun getById(id: Long): MeasurementEntity? = dao.getAllSnapshot().firstOrNull { it.id == id }

    /**
     * Сохранить новую запись измерения.
     * Если переданы bitmaps - они сохраняются на диск со сжатием, а пути записываются в БД.
     *
     * @param markupBitmap скриншот разметки (точки, полигон, эталон поверх фото). Сжимается агрессивно.
     * @param originalBitmap оригинал фото (для повторных измерений). null для дальномера.
     * @param gpsLat / gpsLon: GPS-координаты места замера (для аудиторских модулей).
     * @param extraJson: JSON с дополнительными структурированными данными по типу замера.
     */
    suspend fun save(
        type: String,
        valueRaw: Double,
        unit: String,
        note: String,
        markupBitmap: Bitmap? = null,
        originalBitmap: Bitmap? = null,
        gpsLat: Double? = null,
        gpsLon: Double? = null,
        extraJson: String? = null,
    ): Long {
        val ts = System.currentTimeMillis()

        val markupPath = markupBitmap?.let {
            saveCompressedJpeg(it, "${ts}_markup.jpg", maxDim = 1280, quality = 50)
        }
        val originalPath = originalBitmap?.let {
            saveCompressedJpeg(it, "${ts}_orig.jpg", maxDim = 1280, quality = 50)
        }

        val entity = MeasurementEntity(
            type = type,
            valueRaw = valueRaw,
            unit = unit,
            note = note,
            timestamp = ts,
            imagePath = markupPath,
            originalPath = originalPath,
            gpsLat = gpsLat,
            gpsLon = gpsLon,
            extraJson = extraJson,
        )
        return dao.insert(entity)
    }

    /**
     * Сохранение готового файла-картинки (для модулей где фото уже хранится отдельно).
     */
    fun saveExternalPhoto(sourcePath: String, prefix: String = "ext"): String {
        val ts = System.currentTimeMillis()
        val src = File(sourcePath)
        val dst = File(imagesDir, "${ts}_${prefix}.jpg")
        src.copyTo(dst, overwrite = true)
        return dst.absolutePath
    }

    /**
     * Сохранение bitmap как сжатого JPEG. Возвращает абсолютный путь файла.
     */
    private fun saveCompressedJpeg(
        bitmap: Bitmap,
        fileName: String,
        maxDim: Int,
        quality: Int
    ): String {
        val w = bitmap.width
        val h = bitmap.height
        val longSide = max(w, h)
        val scaled = if (longSide > maxDim) {
            val scale = maxDim.toFloat() / longSide
            val newW = (w * scale).toInt().coerceAtLeast(1)
            val newH = (h * scale).toInt().coerceAtLeast(1)
            Bitmap.createScaledBitmap(bitmap, newW, newH, true)
        } else {
            bitmap
        }
        val file = File(imagesDir, fileName)
        FileOutputStream(file).use { out ->
            scaled.compress(Bitmap.CompressFormat.JPEG, quality, out)
        }
        if (scaled !== bitmap) scaled.recycle()
        return file.absolutePath
    }

    suspend fun deleteById(id: Long) {
        val all = dao.getAllSnapshot()
        val item = all.firstOrNull { it.id == id }
        item?.imagePath?.let { runCatching { File(it).delete() } }
        item?.originalPath?.let { runCatching { File(it).delete() } }
        dao.deleteById(id)
    }

    suspend fun deleteAll() {
        val all = dao.getAllSnapshot()
        for (item in all) {
            item.imagePath?.let { runCatching { File(it).delete() } }
            item.originalPath?.let { runCatching { File(it).delete() } }
        }
        dao.deleteAll()
    }

    suspend fun getAllSnapshot(): List<MeasurementEntity> = dao.getAllSnapshot()

    fun loadThumbnail(path: String?, targetSize: Int = 200): Bitmap? {
        if (path == null) return null
        val file = File(path)
        if (!file.exists()) return null
        return try {
            val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(path, opts)
            var sample = 1
            while (max(opts.outWidth, opts.outHeight) / sample > targetSize * 2) sample *= 2
            val finalOpts = BitmapFactory.Options().apply { inSampleSize = sample }
            BitmapFactory.decodeFile(path, finalOpts)
        } catch (e: Exception) {
            null
        }
    }

    fun loadFull(path: String?): Bitmap? {
        if (path == null) return null
        val file = File(path)
        if (!file.exists()) return null
        return try {
            BitmapFactory.decodeFile(path)
        } catch (e: Exception) {
            null
        }
    }

    companion object {
        @Volatile
        private var instance: MeasurementsRepository? = null

        fun get(context: Context): MeasurementsRepository {
            return instance ?: synchronized(this) {
                instance ?: MeasurementsRepository(
                    context.applicationContext,
                    MeasureKitDb.get(context).measurementDao()
                ).also { instance = it }
            }
        }
    }
}
