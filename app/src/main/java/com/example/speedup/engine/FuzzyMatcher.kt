package com.example.speedup.engine

object FuzzyMatcher {

    fun jaroWinkler(s1: String, s2: String): Double {
        if (s1 == s2) return 1.0
        if (s1.isEmpty() || s2.isEmpty()) return 0.0

        val matchDistance = (maxOf(s1.length, s2.length) / 2) - 1
        val s1Matches = BooleanArray(s1.length)
        val s2Matches = BooleanArray(s2.length)

        var matches = 0
        var transpositions = 0

        for (i in s1.indices) {
            val start = maxOf(0, i - matchDistance)
            val end = minOf(i + matchDistance + 1, s2.length)
            for (j in start until end) {
                if (s2Matches[j] || s1[i] != s2[j]) continue
                s1Matches[i] = true
                s2Matches[j] = true
                matches++
                break
            }
        }

        if (matches == 0) return 0.0

        var k = 0
        for (i in s1.indices) {
            if (!s1Matches[i]) continue
            while (!s2Matches[k]) k++
            if (s1[i] != s2[k]) transpositions++
            k++
        }

        val jaro = (
            matches.toDouble() / s1.length +
                matches.toDouble() / s2.length +
                (matches - transpositions / 2.0) / matches
            ) / 3.0

        var prefix = 0
        for (i in 0 until minOf(4, minOf(s1.length, s2.length))) {
            if (s1[i] == s2[i]) prefix++ else break
        }

        return jaro + prefix * 0.1 * (1.0 - jaro)
    }

    fun normalize(text: String): String =
        text.lowercase()
            .replace(Regex("[^a-z0-9\\s]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
}
