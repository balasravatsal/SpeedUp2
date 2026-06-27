package com.example.speedup.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface FieldMappingDao {
    @Query(
        """
        SELECT * FROM field_mappings
        WHERE hostPattern = :hostPattern
          AND labelNormalized = :labelNormalized
        LIMIT 1
        """
    )
    suspend fun findByLabel(hostPattern: String, labelNormalized: String): FieldMappingEntity?

    @Query(
        """
        SELECT * FROM field_mappings
        WHERE hostPattern = :hostPattern
          AND viewId = :viewId
        LIMIT 1
        """
    )
    suspend fun findByViewId(hostPattern: String, viewId: String): FieldMappingEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: FieldMappingEntity)
}
