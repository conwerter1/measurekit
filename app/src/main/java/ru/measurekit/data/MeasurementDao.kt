package ru.measurekit.data

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface MeasurementDao {
    @Query("SELECT * FROM measurements ORDER BY timestamp DESC")
    fun observeAll(): Flow<List<MeasurementEntity>>

    @Query("SELECT * FROM measurements WHERE type = :type ORDER BY timestamp DESC")
    fun observeByType(type: String): Flow<List<MeasurementEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entity: MeasurementEntity): Long

    @Delete
    suspend fun delete(entity: MeasurementEntity)

    @Query("DELETE FROM measurements WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("DELETE FROM measurements")
    suspend fun deleteAll()

    @Query("SELECT * FROM measurements ORDER BY timestamp DESC")
    suspend fun getAllSnapshot(): List<MeasurementEntity>
}
