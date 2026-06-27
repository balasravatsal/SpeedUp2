package com.example.speedup.engine

import android.content.Context
import android.util.Log
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.util.Locale

/**
 * Loads field aliases and ATS-specific viewId overrides from assets.
 */
object FieldAliasRepository {
    private const val TAG = "FieldAliasRepository"
    private val gson = Gson()

    private var aliasMap: Map<CanonicalField, List<String>> = emptyMap()
    private var atsOverrides: Map<String, Map<String, CanonicalField>> = emptyMap()
    private var initialized = false

    fun initialize(context: Context) {
        if (initialized) return
        synchronized(this) {
            if (initialized) return
            aliasMap = loadAliases(context)
            atsOverrides = loadAtsOverrides(context)
            initialized = true
            Log.d(TAG, "Loaded ${aliasMap.size} canonical alias groups, ${atsOverrides.size} ATS packs")
        }
    }

    fun aliasesFor(field: CanonicalField): List<String> =
        aliasMap[field].orEmpty()

    fun allAliasEntries(): List<Pair<CanonicalField, String>> {
        val source = aliasMap.ifEmpty { builtinFallbackAliases() }
        val out = mutableListOf<Pair<CanonicalField, String>>()
        for ((field, aliases) in source) {
            for (alias in aliases) {
                out.add(field to alias)
            }
        }
        return out
    }

    /** Used when assets are unavailable (unit tests) or JSON load fails. */
    private fun builtinFallbackAliases(): Map<CanonicalField, List<String>> = mapOf(
        CanonicalField.FIRST_NAME to listOf("first name", "given name", "firstname"),
        CanonicalField.LAST_NAME to listOf("last name", "surname", "family name", "familyname"),
        CanonicalField.EMAIL to listOf("email", "email address"),
        CanonicalField.PHONE to listOf("phone", "phone number", "mobile number"),
        CanonicalField.COUNTRY_CODE to listOf("country code", "dial code"),
        CanonicalField.YEARS_OF_EXPERIENCE to listOf("years of experience", "total experience")
    )

    fun atsOverride(packageName: String, viewId: String): CanonicalField? {
        val idKey = viewId.substringAfterLast("/").lowercase(Locale.US)
        val packs = listOfNotNull(
            atsOverrides[packageName],
            resolveAtsPack(packageName)
        )
        for (overrides in packs) {
            overrides[idKey]?.let { return it }
            for ((key, field) in overrides) {
                if (idKey.contains(key)) return field
            }
        }
        return null
    }

    private fun resolveAtsPack(packageName: String): Map<String, CanonicalField>? {
        val key = when {
            packageName.contains("workday", ignoreCase = true) -> "workday"
            packageName.contains("linkedin", ignoreCase = true) -> "linkedin"
            packageName.contains("indeed", ignoreCase = true) -> "indeed"
            packageName.contains("greenhouse", ignoreCase = true) -> "greenhouse"
            packageName.contains("lever", ignoreCase = true) -> "lever"
            WindowScanner.isBrowserPackage(packageName) -> "greenhouse"
            else -> return null
        }
        return atsOverrides[key]
    }

    private fun loadAliases(context: Context): Map<CanonicalField, List<String>> {
        return try {
            val json = context.assets.open("field_aliases.json").bufferedReader().use { it.readText() }
            val raw: Map<String, List<String>> = gson.fromJson(
                json,
                object : TypeToken<Map<String, List<String>>>() {}.type
            )
            raw.mapNotNull { (key, aliases) ->
                val field = runCatching { CanonicalField.valueOf(key) }.getOrNull() ?: return@mapNotNull null
                field to aliases.map { it.lowercase(Locale.US).trim() }
            }.toMap()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load field_aliases.json", e)
            emptyMap()
        }
    }

    private fun loadAtsOverrides(context: Context): Map<String, Map<String, CanonicalField>> {
        val result = mutableMapOf<String, Map<String, CanonicalField>>()
        val files = listOf("greenhouse", "lever", "workday", "linkedin", "indeed")
        for (name in files) {
            try {
                val path = "ats_overrides/$name.json"
                val json = context.assets.open(path).bufferedReader().use { it.readText() }
                val raw: Map<String, String> = gson.fromJson(
                    json,
                    object : TypeToken<Map<String, String>>() {}.type
                )
                val mapped = raw.mapNotNull { (viewKey, canonicalName) ->
                    val field = runCatching { CanonicalField.valueOf(canonicalName) }.getOrNull()
                        ?: return@mapNotNull null
                    viewKey.lowercase(Locale.US) to field
                }.toMap()
                result[name] = mapped
            } catch (e: Exception) {
                Log.w(TAG, "ATS override $name not loaded: ${e.message}")
            }
        }
        return result
    }
}
