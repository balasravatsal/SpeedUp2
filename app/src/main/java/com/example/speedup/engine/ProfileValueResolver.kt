package com.example.speedup.engine

import com.example.speedup.data.model.Education
import com.example.speedup.data.model.UserProfile
import com.example.speedup.data.model.WorkExperience
import com.example.speedup.data.repository.ProfileRepository

class ProfileValueResolver(private val repository: ProfileRepository) {

    fun resolve(field: CanonicalField): String? {
        val profile = repository.getProfile()
        val experiences = repository.getExperiences()
        val education = repository.getEducation()

        return when (field) {
            CanonicalField.FIRST_NAME -> profile.firstName.takeIf { it.isNotBlank() }
            CanonicalField.LAST_NAME -> profile.lastName.takeIf { it.isNotBlank() }
            CanonicalField.FULL_NAME -> profile.fullName.takeIf { it.isNotBlank() }
            CanonicalField.EMAIL -> profile.email.takeIf { it.isNotBlank() }
            CanonicalField.PHONE -> formatPhone(profile)
            CanonicalField.COUNTRY_CODE -> profile.countryCode.takeIf { it.isNotBlank() }
            CanonicalField.CITY, CanonicalField.LOCATION -> profile.location.takeIf { it.isNotBlank() }
            CanonicalField.STATE -> profile.state.takeIf { it.isNotBlank() }
            CanonicalField.COUNTRY -> profile.country.takeIf { it.isNotBlank() }
            CanonicalField.LINKEDIN -> profile.linkedIn.takeIf { it.isNotBlank() }
            CanonicalField.PORTFOLIO -> profile.website.takeIf { it.isNotBlank() }
            CanonicalField.CURRENT_TITLE -> profile.title.takeIf { it.isNotBlank() }
                ?: experiences.firstOrNull()?.title
            CanonicalField.YEARS_OF_EXPERIENCE -> {
                val years = repository.getTotalYearsOfExperience()
                if (years > 0) "%.1f".format(years) else null
            }
            CanonicalField.COMPANY -> experiences.firstOrNull()?.company
            CanonicalField.EDUCATION -> formatEducation(education.firstOrNull())
            CanonicalField.DEGREE -> education.firstOrNull()?.degree
            CanonicalField.SCHOOL -> education.firstOrNull()?.school
            CanonicalField.COVER_LETTER -> buildCoverLetter(profile, experiences)
            CanonicalField.ABOUT -> profile.bio.takeIf { it.isNotBlank() }
                ?: buildShortBio(profile, experiences)
            CanonicalField.UNKNOWN -> null
        }
    }

    fun buildFillMap(matches: List<Pair<CanonicalField, String>>): Map<String, String> {
        val result = linkedMapOf<String, String>()
        for ((field, _) in matches) {
            if (field == CanonicalField.UNKNOWN) continue
            val value = resolve(field) ?: continue
            result[field.key] = value
        }
        return result
    }

    private fun formatPhone(profile: UserProfile): String? {
        if (profile.phone.isBlank()) return null
        return if (profile.countryCode.isNotBlank() && !profile.phone.startsWith("+")) {
            "${profile.countryCode} ${profile.phone}"
        } else {
            profile.phone
        }
    }

    private fun formatEducation(edu: Education?): String? {
        if (edu == null) return null
        return "${edu.degree} — ${edu.school} (${edu.year})"
    }

    private fun buildCoverLetter(profile: UserProfile, experiences: List<WorkExperience>): String {
        val title = profile.title.ifBlank { experiences.firstOrNull()?.title ?: "this role" }
        val skills = profile.skills.take(5).joinToString(", ")
        val expYears = repository.getTotalYearsOfExperience()
        return buildString {
            append("I am excited to apply for the $title position. ")
            if (expYears > 0) {
                append("With ${"%.0f".format(expYears)}+ years of experience")
                experiences.firstOrNull()?.company?.let { append(" at $it") }
                append(", ")
            }
            if (skills.isNotBlank()) {
                append("I bring strong skills in $skills. ")
            }
            append("I would welcome the opportunity to contribute to your team.")
        }
    }

    private fun buildShortBio(profile: UserProfile, experiences: List<WorkExperience>): String {
        val title = profile.title.ifBlank { experiences.firstOrNull()?.title ?: "Professional" }
        val skills = profile.skills.take(4).joinToString(", ")
        return if (skills.isNotBlank()) {
            "$title with expertise in $skills."
        } else {
            "$title with relevant industry experience."
        }
    }
}
