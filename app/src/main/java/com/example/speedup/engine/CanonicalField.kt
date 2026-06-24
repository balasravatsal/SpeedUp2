package com.example.speedup.engine

enum class CanonicalField(val key: String, val displayName: String) {
    FIRST_NAME("first_name", "First Name"),
    LAST_NAME("last_name", "Last Name"),
    FULL_NAME("full_name", "Full Name"),
    EMAIL("email", "Email"),
    PHONE("phone", "Phone"),
    COUNTRY_CODE("country_code", "Country Code"),
    CITY("city", "City"),
    STATE("state", "State"),
    COUNTRY("country", "Country"),
    LOCATION("location", "Location"),
    LINKEDIN("linkedin", "LinkedIn URL"),
    PORTFOLIO("portfolio", "Portfolio URL"),
    CURRENT_TITLE("current_title", "Current Title"),
    YEARS_OF_EXPERIENCE("years_of_experience", "Years of Experience"),
    COMPANY("company", "Company"),
    EDUCATION("education", "Education"),
    DEGREE("degree", "Degree"),
    SCHOOL("school", "School"),
    COVER_LETTER("cover_letter", "Cover Letter"),
    ABOUT("about", "About / Bio"),
    UNKNOWN("unknown", "Unknown");

    companion object {
        fun fromKey(key: String): CanonicalField =
            entries.firstOrNull { it.key == key } ?: UNKNOWN
    }
}

data class FieldMatch(
    val field: CanonicalField,
    val confidence: Float,
    val matchedLayer: Int,
    val matchedSignal: String = ""
)
