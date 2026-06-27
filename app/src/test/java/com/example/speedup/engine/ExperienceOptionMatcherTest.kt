package com.example.speedup.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class ExperienceOptionMatcherTest {

    @Test
    fun onePointFiveYears_matchesZeroToTwoRange() {
        val options = listOf("Select", "0-2 years", "2-5 years", "5+ years")
        val match = ExperienceOptionMatcher.bestOption(options, 1.5)
        assertEquals("0-2 years", match)
    }

    @Test
    fun onePointFiveYears_matchesLessThanTwo() {
        val options = listOf("Less than 2 years", "2 to 5 years", "More than 5 years")
        val match = ExperienceOptionMatcher.bestOption(options, 1.5)
        assertEquals("Less than 2 years", match)
    }

    @Test
    fun threeYears_matchesTwoToFiveRange() {
        val options = listOf("0-2", "2-5 years", "5+")
        val match = ExperienceOptionMatcher.bestOption(options, 3.0)
        assertEquals("2-5 years", match)
    }

    @Test
    fun rangeYearsFillValue_routesThroughOptionMatcher() {
        val options = listOf("0 to 2 years", "3 to 5 years")
        val match = OptionMatcher.bestOption(options, FillValue.RangeYears(1.5))
        assertNotNull(match)
        assertEquals("0 to 2 years", match)
    }
}
