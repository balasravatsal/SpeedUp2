package com.example.speedup.engine

import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.InputStreamReader

/**
 * Corpus-driven mapping tests using anonymized form field labels in test resources.
 */
class FormCorpusMappingTest {

    private val gson = Gson()

    @Test
    fun greenhouseNameFields_mapCorrectly() {
        runCorpus("forms/greenhouse_name_fields.json")
    }

    @Test
    fun workdaySplitPhone_mapCorrectly() {
        runCorpus("forms/workday_split_phone.json")
    }

    private fun runCorpus(resourcePath: String) {
        val corpus = loadCorpus(resourcePath)
        for (field in corpus.fields) {
            val match = FieldMapper.matchField(
                viewId = field.label.lowercase().replace(" ", "_"),
                hint = "",
                contentDesc = "",
                labelText = field.label,
                inputType = 0,
                packageName = corpus.platform
            )
            assertEquals(
                "Label '${field.label}' in $resourcePath",
                field.expected,
                match.field.name
            )
        }
    }

    private fun loadCorpus(path: String): FormCorpus {
        val stream = checkNotNull(javaClass.classLoader?.getResourceAsStream(path)) {
            "Missing test resource: $path"
        }
        return InputStreamReader(stream).use { gson.fromJson(it, FormCorpus::class.java) }
    }

    private data class FormCorpus(
        val platform: String,
        val description: String,
        val fields: List<CorpusField>
    )

    private data class CorpusField(
        val label: String,
        val expected: String,
        @SerializedName("widget") val widget: String? = null
    )
}
