package com.example.speedup.data.model

data class UserProfile(
    val firstName: String = "",
    val lastName: String = "",
    val email: String = "",
    val phone: String = "",
    val countryCode: String = "+1",
    val location: String = "",
    val state: String = "",
    val country: String = "",
    val website: String = "",
    val linkedIn: String = "",
    val title: String = "",
    val bio: String = "",
    val skills: List<String> = emptyList(),
    val resumeFileName: String = "",
    val resumeUri: String = "",
    val completion: Int = 0
) {
    val fullName: String
        get() = if (firstName.isNotEmpty() || lastName.isNotEmpty()) "$firstName $lastName".trim() else ""
    val avatar: String
        get() = if (firstName.isNotEmpty()) firstName.take(1).uppercase() else "U"
}

data class WorkExperience(
    val id: String,
    val title: String,
    val company: String,
    val duration: String,
    val description: String = "",
    val skillsUsed: List<String> = emptyList()
)

data class Education(
    val id: String,
    val degree: String,
    val school: String,
    val year: String
)

data class JobPosting(
    val title: String,
    val company: String,
    val location: String,
    val fitScore: Int,
    /** JD requirements you satisfy */
    val skillsMatched: List<String>,
    /** JD requirements you lack */
    val skillsMissing: List<String>,
    /** JD requirements with weak overlap */
    val skillsPartial: List<String> = emptyList(),
    val skillsIgnored: List<String> = emptyList(),
    val requiredExperience: Double = 0.0,
    val jdDetected: Boolean = false,
    val fitLabel: String = "",
    val fitSubtitle: String = ""
)
