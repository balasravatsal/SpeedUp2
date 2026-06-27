package com.example.speedup.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "field_mappings")
data class FieldMappingEntity(
    @PrimaryKey val id: String,
    val hostPattern: String,
    val viewId: String?,
    val labelNormalized: String,
    val canonicalField: String,
    val widgetType: String,
    val correctedAt: Long = System.currentTimeMillis()
)
