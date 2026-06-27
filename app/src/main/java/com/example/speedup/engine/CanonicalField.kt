package com.example.speedup.engine

enum class CanonicalField(val key: String, val displayName: String) {
    FIRST_NAME("first_name", "First Name"),
    MIDDLE_NAME("middle_name", "Middle Name"),
    LAST_NAME("last_name", "Last Name"),
    PREFERRED_NAME("preferred_name", "Preferred Name"),
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
    GITHUB("github", "GitHub URL"),
    CURRENT_TITLE("current_title", "Current Title"),
    YEARS_OF_EXPERIENCE("years_of_experience", "Years of Experience"),
    COMPANY("company", "Company"),
    EDUCATION("education", "Education"),
    DEGREE("degree", "Degree"),
    SCHOOL("school", "School"),
    COVER_LETTER("cover_letter", "Cover Letter"),
    ABOUT("about", "About / Bio"),
    WORK_AUTHORIZATION("work_authorization", "Work Authorization"),
    VETERAN_STATUS("veteran_status", "Veteran Status"),
    GENDER("gender", "Gender"),
    WILLING_TO_RELOCATE("willing_to_relocate", "Willing to Relocate"),
    SALARY_EXPECTATION("salary_expectation", "Salary Expectation"),
    NOTICE_PERIOD("notice_period", "Notice Period"),
    REFERRAL_SOURCE("referral_source", "How did you hear about us"),
    UNKNOWN("unknown", "Unknown");

    companion object {
        fun fromKey(key: String): CanonicalField =
            entries.firstOrNull { it.key == key } ?: UNKNOWN

        /** One fill per page for these; duplicates skipped by fingerprint not canonical alone. */
        val singleFillPerPage: Set<CanonicalField> = setOf(
            FIRST_NAME, MIDDLE_NAME, LAST_NAME, PREFERRED_NAME, FULL_NAME,
            EMAIL, PHONE, COUNTRY_CODE, LINKEDIN, PORTFOLIO, GITHUB
        )
    }
}

data class FieldMatch(
    val field: CanonicalField,
    val confidence: Float,
    val matchedLayer: Int,
    val matchedSignal: String = ""
)
