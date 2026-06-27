package com.example.speedup.data.repository

import android.content.Context
import com.example.speedup.data.db.FieldMappingEntity
import com.example.speedup.data.db.SpeedUpDatabase
import com.example.speedup.engine.CanonicalField
import com.example.speedup.engine.FieldWidgetType
import com.example.speedup.engine.FuzzyMatcher
import kotlinx.coroutines.runBlocking

class FieldMappingRepository(context: Context) {
    private val dao = SpeedUpDatabase.get(context).fieldMappingDao()

    fun lookup(hostPattern: String, viewId: String?, label: String): Pair<CanonicalField, FieldWidgetType>? {
        val normalizedLabel = FuzzyMatcher.normalize(label)
        return runBlocking {
            val byViewId = viewId?.takeIf { it.isNotBlank() }?.let { dao.findByViewId(hostPattern, it) }
            val entity = byViewId ?: dao.findByLabel(hostPattern, normalizedLabel)
            entity?.toPair()
        }
    }

    fun saveCorrection(
        hostPattern: String,
        viewId: String?,
        label: String,
        canonical: CanonicalField,
        widgetType: FieldWidgetType
    ) {
        val id = "$hostPattern|${viewId.orEmpty()}|${FuzzyMatcher.normalize(label)}"
        runBlocking {
            dao.upsert(
                FieldMappingEntity(
                    id = id,
                    hostPattern = hostPattern,
                    viewId = viewId,
                    labelNormalized = FuzzyMatcher.normalize(label),
                    canonicalField = canonical.name,
                    widgetType = widgetType.name
                )
            )
        }
    }

    private fun FieldMappingEntity.toPair(): Pair<CanonicalField, FieldWidgetType>? {
        val canonical = runCatching { CanonicalField.valueOf(canonicalField) }.getOrNull() ?: return null
        val widget = runCatching { FieldWidgetType.valueOf(widgetType) }.getOrElse { FieldWidgetType.TEXT }
        return canonical to widget
    }
}
