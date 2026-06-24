package com.example.speedup.data.repository

import android.content.Context
import android.content.SharedPreferences
import com.example.speedup.data.model.Education
import com.example.speedup.data.model.UserProfile
import com.example.speedup.data.model.WorkExperience
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

class ProfileRepository(context: Context) {
    private val prefs: SharedPreferences =
        context.getSharedPreferences("speedup_prefs", Context.MODE_PRIVATE)
    private val gson = Gson()

    fun getProfile(): UserProfile {
        val json = prefs.getString("user_profile", null) ?: return UserProfile()
        return gson.fromJson(json, UserProfile::class.java)
    }

    fun saveProfile(profile: UserProfile) {
        val json = gson.toJson(calculateCompletion(profile))
        prefs.edit().putString("user_profile", json).apply()
    }

    fun getExperiences(): List<WorkExperience> {
        val json = prefs.getString("work_experiences", null) ?: return emptyList()
        val type = object : TypeToken<List<WorkExperience>>() {}.type
        return gson.fromJson(json, type)
    }

    fun saveExperiences(experiences: List<WorkExperience>) {
        val json = gson.toJson(experiences)
        prefs.edit().putString("work_experiences", json).apply()
        saveProfile(getProfile())
    }

    fun getEducation(): List<Education> {
        val json = prefs.getString("education", null) ?: return emptyList()
        val type = object : TypeToken<List<Education>>() {}.type
        return gson.fromJson(json, type)
    }

    fun saveEducation(education: List<Education>) {
        val json = gson.toJson(education)
        prefs.edit().putString("education", json).apply()
        saveProfile(getProfile())
    }

    fun isIntroCompleted(): Boolean {
        return prefs.getBoolean("intro_completed", false)
    }

    fun setIntroCompleted(completed: Boolean) {
        prefs.edit().putBoolean("intro_completed", completed).apply()
    }

    fun isProfileSetupCompleted(): Boolean {
        return prefs.getBoolean("profile_setup_completed", false)
    }

    fun setProfileSetupCompleted(completed: Boolean) {
        prefs.edit().putBoolean("profile_setup_completed", completed).apply()
    }

    fun isOnboardingCompleted(): Boolean {
        return prefs.getBoolean("onboarding_completed", false)
    }

    fun setOnboardingCompleted(completed: Boolean) {
        prefs.edit().putBoolean("onboarding_completed", completed).apply()
    }

    fun hasMinimumProfile(): Boolean {
        val profile = getProfile()
        return profile.firstName.isNotBlank() &&
            profile.email.isNotBlank() &&
            profile.phone.isNotBlank()
    }

    fun getTotalYearsOfExperience(): Double {
        return getExperiences().sumOf { parseDurationToYears(it.duration) }
    }

    fun getYearsOfExperienceForSkill(skill: String): Double {
        val target = skill.lowercase().trim()
        return getExperiences()
            .filter { exp ->
                exp.skillsUsed.any { it.lowercase().trim() == target } ||
                    exp.title.lowercase().contains(target) ||
                    exp.description.lowercase().contains(target)
            }
            .sumOf { parseDurationToYears(it.duration) }
    }

    private fun parseDurationToYears(duration: String): Double {
        val clean = duration.lowercase().trim()
        val numberRegex = """(\d+(?:\.\d+)?)""".toRegex()
        val match = numberRegex.find(clean) ?: return 0.0
        val value = match.value.toDouble()
        return when {
            clean.contains("year") || clean.contains("yr") -> value
            clean.contains("month") || clean.contains("mo") -> value / 12.0
            else -> value
        }
    }

    private fun calculateCompletion(profile: UserProfile): UserProfile {
        var filled = 0
        val total = 11

        if (profile.firstName.isNotEmpty() || profile.lastName.isNotEmpty()) filled++
        if (profile.email.isNotEmpty()) filled++
        if (profile.phone.isNotEmpty()) filled++
        if (profile.countryCode.isNotEmpty()) filled++
        if (profile.location.isNotEmpty()) filled++
        if (profile.website.isNotEmpty() || profile.linkedIn.isNotEmpty()) filled++
        if (profile.title.isNotEmpty()) filled++
        if (profile.skills.isNotEmpty()) filled++
        if (getExperiences().isNotEmpty()) filled++
        if (getEducation().isNotEmpty()) filled++
        if (profile.resumeFileName.isNotEmpty()) filled++

        val percentage = (filled * 100) / total
        return profile.copy(completion = percentage)
    }
}
