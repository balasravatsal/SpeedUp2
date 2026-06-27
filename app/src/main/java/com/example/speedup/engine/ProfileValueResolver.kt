package com.example.speedup.engine

import com.example.speedup.data.model.Education
import com.example.speedup.data.model.UserProfile
import com.example.speedup.data.model.WorkExperience
import com.example.speedup.data.repository.ProfileRepository

class ProfileValueResolver(private val repository: ProfileRepository) {

    fun resolve(field: CanonicalField, context: FormContext = FormContext(), widgetType: FieldWidgetType = FieldWidgetType.TEXT): FillValue? {
        val profile = repository.getProfile()
        val experiences = repository.getExperiences()
        val education = repository.getEducation()

        return when (field) {
            CanonicalField.FIRST_NAME -> profile.firstName.takeIf { it.isNotBlank() }?.let { FillValue.Text(it) }
            CanonicalField.MIDDLE_NAME -> profile.middleName.takeIf { it.isNotBlank() }?.let { FillValue.Text(it) }
            CanonicalField.LAST_NAME -> profile.lastName.takeIf { it.isNotBlank() }?.let { FillValue.Text(it) }
            CanonicalField.PREFERRED_NAME -> {
                val name = profile.preferredName.ifBlank { profile.firstName }
                name.takeIf { it.isNotBlank() }?.let { FillValue.Text(it) }
            }
            CanonicalField.FULL_NAME -> profile.fullName.takeIf { it.isNotBlank() }?.let { FillValue.Text(it) }
            CanonicalField.EMAIL -> profile.email.takeIf { it.isNotBlank() }?.let { FillValue.Text(it) }
            CanonicalField.PHONE -> resolvePhone(profile, context, widgetType)
            CanonicalField.COUNTRY_CODE -> resolveCountryCode(profile, widgetType)
            CanonicalField.CITY, CanonicalField.LOCATION -> profile.location.takeIf { it.isNotBlank() }?.let { FillValue.Text(it) }
            CanonicalField.STATE -> profile.state.takeIf { it.isNotBlank() }?.let { FillValue.Text(it) }
            CanonicalField.COUNTRY -> resolveCountry(profile, widgetType)
            CanonicalField.LINKEDIN -> profile.linkedIn.takeIf { it.isNotBlank() }?.let { FillValue.Text(it) }
            CanonicalField.PORTFOLIO -> profile.website.takeIf { it.isNotBlank() }?.let { FillValue.Text(it) }
            CanonicalField.GITHUB -> (profile.github.ifBlank { profile.website }).takeIf { it.isNotBlank() }?.let { FillValue.Text(it) }
            CanonicalField.CURRENT_TITLE -> (profile.title.ifBlank { experiences.firstOrNull()?.title.orEmpty() })
                .takeIf { it.isNotBlank() }?.let { FillValue.Text(it) }
            CanonicalField.YEARS_OF_EXPERIENCE -> resolveExperience(widgetType)
            CanonicalField.COMPANY -> experiences.firstOrNull()?.company?.takeIf { it.isNotBlank() }?.let { FillValue.Text(it) }
            CanonicalField.EDUCATION -> formatEducation(education.firstOrNull())?.let { FillValue.Text(it) }
            CanonicalField.DEGREE -> education.firstOrNull()?.degree?.takeIf { it.isNotBlank() }?.let { FillValue.Text(it) }
            CanonicalField.SCHOOL -> education.firstOrNull()?.school?.takeIf { it.isNotBlank() }?.let { FillValue.Text(it) }
            CanonicalField.COVER_LETTER -> buildCoverLetter(profile, experiences)?.let { FillValue.Text(it) }
            CanonicalField.ABOUT -> (profile.bio.ifBlank { buildShortBio(profile, experiences) })
                .takeIf { it.isNotBlank() }?.let { FillValue.Text(it) }
            CanonicalField.WORK_AUTHORIZATION -> profile.workAuthorization.takeIf { it.isNotBlank() }
                ?.let { FillValue.Option(listOf(it, "Yes", "Authorized")) }
            CanonicalField.VETERAN_STATUS -> profile.veteranStatus.takeIf { it.isNotBlank() }?.let { FillValue.Option(listOf(it)) }
            CanonicalField.GENDER -> profile.gender.takeIf { it.isNotBlank() }?.let { FillValue.Option(listOf(it)) }
            CanonicalField.WILLING_TO_RELOCATE -> profile.willingToRelocate.takeIf { it.isNotBlank() }
                ?.let { FillValue.Option(listOf(it, "Yes", "No")) }
            CanonicalField.SALARY_EXPECTATION -> profile.salaryExpectation.takeIf { it.isNotBlank() }?.let { FillValue.Text(it) }
            CanonicalField.NOTICE_PERIOD -> profile.noticePeriod.takeIf { it.isNotBlank() }?.let { FillValue.Text(it) }
            CanonicalField.REFERRAL_SOURCE -> profile.referralSource.takeIf { it.isNotBlank() }
                ?.let { FillValue.Option(listOf(it, "Job Board", "LinkedIn", "Indeed")) }
            CanonicalField.UNKNOWN -> null
        }?.takeUnless { it is FillValue.Text && it.value.isBlank() }
    }

    /** Legacy string resolve for previews and toasts. */
    fun resolveString(field: CanonicalField, context: FormContext = FormContext(), widgetType: FieldWidgetType = FieldWidgetType.TEXT): String? =
        resolve(field, context, widgetType)?.displayText()

    private fun resolvePhone(profile: UserProfile, context: FormContext, widgetType: FieldWidgetType): FillValue? {
        if (profile.phone.isBlank()) return null
        val local = stripCountryCode(profile.phone, profile.countryCode)
        return when {
            context.hasSplitPhone -> FillValue.Text(local)
            widgetType == FieldWidgetType.DROPDOWN -> FillValue.Option(listOf(local, profile.phone))
            else -> FillValue.Text(formatPhone(profile))
        }
    }

    private fun resolveCountryCode(profile: UserProfile, widgetType: FieldWidgetType): FillValue? {
        val code = profile.countryCode.takeIf { it.isNotBlank() } ?: return null
        val digits = code.removePrefix("+").trim()
        return if (widgetType == FieldWidgetType.DROPDOWN) {
            FillValue.Option(listOf(code, digits, "+$digits", "United States", "US"))
        } else {
            FillValue.Text(digits)
        }
    }

    private fun resolveCountry(profile: UserProfile, widgetType: FieldWidgetType): FillValue? {
        val country = profile.country.takeIf { it.isNotBlank() } ?: return null
        return if (widgetType == FieldWidgetType.DROPDOWN) {
            FillValue.Option(listOf(country))
        } else {
            FillValue.Text(country)
        }
    }

    private fun resolveExperience(widgetType: FieldWidgetType): FillValue? {
        val years = repository.getTotalYearsOfExperience()
        if (years <= 0) return null
        return if (widgetType == FieldWidgetType.DROPDOWN) {
            FillValue.RangeYears(years)
        } else {
            val rounded = if (years < 2) "%.0f".format(years) else "%.1f".format(years)
            FillValue.Text(rounded)
        }
    }

    fun stripCountryCode(phone: String, countryCode: String): String {
        var local = phone.trim()
        val cc = countryCode.trim().removePrefix("+")
        if (cc.isNotBlank()) {
            if (local.startsWith("+$cc")) local = local.removePrefix("+$cc")
            else if (local.startsWith(cc)) local = local.removePrefix(cc)
        }
        return local.trim().replace(Regex("[^0-9]"), "").ifBlank { phone.trim() }
    }

    private fun formatPhone(profile: UserProfile): String {
        if (profile.phone.isBlank()) return ""
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

    private fun buildCoverLetter(profile: UserProfile, experiences: List<WorkExperience>): String? {
        // Only generate when there is real profile substance — otherwise leave blank.
        val hasSubstance = profile.title.isNotBlank() ||
            experiences.isNotEmpty() ||
            profile.skills.isNotEmpty()
        if (!hasSubstance) return null

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
