package com.example.speedup.engine

import kotlin.math.abs

/**
 * Matches dropdown/radio option text against a [FillValue].
 */
object OptionMatcher {

    fun bestOption(options: List<String>, fillValue: FillValue): String? {
        if (options.isEmpty()) return null
        return when (fillValue) {
            is FillValue.RangeYears -> ExperienceOptionMatcher.bestOption(options, fillValue.years)
            is FillValue.Option -> bestTextOption(options, fillValue.matchTokens)
            is FillValue.Text -> bestTextOption(options, listOf(fillValue.value))
            is FillValue.BooleanValue -> bestTextOption(options, listOf(if (fillValue.checked) "Yes" else "No"))
            is FillValue.DateValue -> bestTextOption(options, listOf(fillValue.isoDate))
            is FillValue.FileUri -> null
        }
    }

    fun bestTextOption(options: List<String>, tokens: List<String>): String? {
        val normalizedOptions = options.map { it.trim() }.filter { it.isNotBlank() }
        if (normalizedOptions.isEmpty()) return null

        for (token in tokens) {
            val norm = FuzzyMatcher.normalize(token)
            normalizedOptions.firstOrNull { FuzzyMatcher.normalize(it) == norm }?.let { return it }
        }

        var best: Pair<String, Double>? = null
        for (token in tokens) {
            val norm = FuzzyMatcher.normalize(token)
            for (option in normalizedOptions) {
                val score = FuzzyMatcher.jaroWinkler(norm, FuzzyMatcher.normalize(option))
                if (score > 0.85 && (best == null || score > best.second)) {
                    best = option to score
                }
            }
        }
        return best?.first
    }
}

object ExperienceOptionMatcher {

  fun bestOption(options: List<String>, years: Double): String? {
        var best: Pair<String, Double>? = null
        for (option in options) {
            val score = scoreOption(option, years)
            if (score > 0 && (best == null || score > best.second)) {
                best = option to score
            }
        }
        return best?.first ?: OptionMatcher.bestTextOption(options, listOf("%.1f".format(years), "%.0f".format(years)))
    }

    fun scoreOption(option: String, years: Double): Double {
        val normalized = option.lowercase().trim()
        if (normalized.isBlank() || normalized.contains("select")) return 0.0

        parseRangeHyphen(normalized)?.let { (low, high) ->
            if (years >= low && years < high) return 1.0
            return 0.3 - abs(years - low) * 0.1
        }

        parseRangeTo(normalized)?.let { (low, high) ->
            if (years >= low && years < high) return 1.0
            return 0.3 - abs(years - low) * 0.1
        }

        Regex("""(\d+)\+""").find(normalized)?.groupValues?.getOrNull(1)?.toDoubleOrNull()?.let { min ->
            if (years >= min) return 0.95
        }

        if (normalized.contains("less than")) {
            Regex("""(\d+)""").find(normalized)?.groupValues?.getOrNull(1)?.toDoubleOrNull()?.let { max ->
                if (years < max) return 0.95
            }
        }

        Regex("""^(\d+)\s*years?""").find(normalized)?.groupValues?.getOrNull(1)?.toDoubleOrNull()?.let { exact ->
            if (abs(years - exact) < 0.6) return 0.9
        }

        return 0.0
    }

    private fun parseRangeHyphen(text: String): Pair<Double, Double>? {
        val m = Regex("""(\d+(?:\.\d+)?)\s*[-–]\s*(\d+(?:\.\d+)?)""").find(text) ?: return null
        val low = m.groupValues[1].toDoubleOrNull() ?: return null
        val high = m.groupValues[2].toDoubleOrNull() ?: return null
        return low to high
    }

    private fun parseRangeTo(text: String): Pair<Double, Double>? {
        val m = Regex("""(\d+(?:\.\d+)?)\s*to\s*(\d+(?:\.\d+)?)""").find(text) ?: return null
        val low = m.groupValues[1].toDoubleOrNull() ?: return null
        val high = m.groupValues[2].toDoubleOrNull() ?: return null
        return low to high
    }
}
