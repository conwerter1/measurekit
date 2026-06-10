package ru.measurekit.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Запись об одном измерении в истории.
 *
 * Поля:
 *   - type: тип измерения (RULER, PHOTO, AREA, RANGEFINDER, PROTRACTOR, LEVEL, REBAR,
 *           STOCKPILE, DOSE_CHECK, MIXER_BATCH, MOISTURE, CHECKLIST_RBU/DSU/QUARRY).
 *   - valueRaw: исходное численное значение в "канонических" единицах для типа.
 *               Для длин - мм; для углов - градусы; для площадей - мм^2; для расстояний - м;
 *               для арматуры - количество стержней; для штабелей - объём м³;
 *               для дозаторов - отклонение в процентах.
 *   - unit: единица, как пользователь видел при сохранении ("мм", "см", "м³", "т", "%")
 *   - note: пользовательская заметка (включая детали типа замера)
 *   - timestamp: unix-millis момента сохранения
 *   - imagePath: путь к скриншоту разметки или null
 *   - originalPath: путь к оригинальному фото или null
 *   - gpsLat / gpsLon: координаты места замера (для аудиторских модулей)
 *   - extraJson: JSON с дополнительными структурированными данными типа замера.
 *                Для STOCKPILE: материал, форма, размеры. Для DOSE_CHECK: вид дозатора,
 *                номинал, факт. Для MIXER_BATCH: класс, цемент, песок, щебень, вода.
 *                Для CHECKLIST_*: серия ответов на пункты + GPS на каждое фото.
 */
@Entity(tableName = "measurements")
data class MeasurementEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "type") val type: String,
    @ColumnInfo(name = "value_raw") val valueRaw: Double,
    @ColumnInfo(name = "unit") val unit: String,
    @ColumnInfo(name = "note") val note: String,
    @ColumnInfo(name = "timestamp") val timestamp: Long,
    @ColumnInfo(name = "image_path") val imagePath: String? = null,
    @ColumnInfo(name = "original_path") val originalPath: String? = null,
    @ColumnInfo(name = "gps_lat") val gpsLat: Double? = null,
    @ColumnInfo(name = "gps_lon") val gpsLon: Double? = null,
    @ColumnInfo(name = "extra_json") val extraJson: String? = null,
)

/** Типы измерений как строковые константы для расширяемости. */
object MeasurementType {
    const val RULER = "RULER"
    const val PHOTO = "PHOTO"
    const val AREA = "AREA"
    const val RANGEFINDER = "RANGEFINDER"
    const val PROTRACTOR = "PROTRACTOR"
    const val LEVEL = "LEVEL"
    const val REBAR = "REBAR"

    // Аудиторские модули
    const val STOCKPILE = "STOCKPILE"               // обмер штабеля
    const val DOSE_CHECK = "DOSE_CHECK"             // проверка дозатора
    const val MIXER_BATCH = "MIXER_BATCH"           // паспорт замеса
    const val MOISTURE = "MOISTURE"                 // влажность заполнителя
    const val SIEVE_TEST = "SIEVE_TEST"             // ситовый анализ
    const val CHECKLIST_RBU = "CHECKLIST_RBU"
    const val CHECKLIST_DSU = "CHECKLIST_DSU"
    const val CHECKLIST_QUARRY = "CHECKLIST_QUARRY"
}
