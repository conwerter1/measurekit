package ru.measurekit.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [MeasurementEntity::class],
    version = 3,
    exportSchema = false
)
abstract class MeasureKitDb : RoomDatabase() {
    abstract fun measurementDao(): MeasurementDao

    companion object {
        @Volatile
        private var instance: MeasureKitDb? = null

        // Миграция 1->2: добавляем колонки image_path и original_path
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE measurements ADD COLUMN image_path TEXT")
                db.execSQL("ALTER TABLE measurements ADD COLUMN original_path TEXT")
            }
        }

        // Миграция 2->3: добавляем GPS-координаты и JSON-поле для аудиторских модулей.
        // Все колонки nullable, существующие записи остаются совместимыми.
        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE measurements ADD COLUMN gps_lat REAL")
                db.execSQL("ALTER TABLE measurements ADD COLUMN gps_lon REAL")
                db.execSQL("ALTER TABLE measurements ADD COLUMN extra_json TEXT")
            }
        }

        fun get(context: Context): MeasureKitDb {
            return instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    MeasureKitDb::class.java,
                    "measurekit.db"
                )
                    .addMigrations(MIGRATION_1_2, MIGRATION_2_3)
                    .build()
                    .also { instance = it }
            }
        }
    }
}
