package com.example.speedup.ui.profile

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import com.example.speedup.data.model.Education
import com.example.speedup.data.model.UserProfile
import com.example.speedup.data.model.WorkExperience
import com.example.speedup.data.repository.ProfileRepository
import java.util.UUID

class ProfileViewModel(application: Application) : AndroidViewModel(application) {
    private val repository = ProfileRepository(application)

    private val _profile = MutableLiveData<UserProfile>()
    val profile: LiveData<UserProfile> get() = _profile

    private val _experiences = MutableLiveData<List<WorkExperience>>()
    val experiences: LiveData<List<WorkExperience>> get() = _experiences

    private val _education = MutableLiveData<List<Education>>()
    val education: LiveData<List<Education>> get() = _education

    init {
        loadData()
    }

    fun loadData() {
        _profile.value = repository.getProfile()
        _experiences.value = repository.getExperiences()
        _education.value = repository.getEducation()
    }

    fun saveProfile(profile: UserProfile) {
        repository.saveProfile(profile)
        loadData()
    }

    fun updatePersonalInfo(
        firstName: String,
        lastName: String,
        email: String,
        phone: String,
        countryCode: String,
        location: String,
        website: String,
        linkedIn: String,
        title: String
    ) {
        val current = _profile.value ?: UserProfile()
        saveProfile(
            current.copy(
                firstName = firstName,
                lastName = lastName,
                email = email,
                phone = phone,
                countryCode = countryCode,
                location = location,
                website = website,
                linkedIn = linkedIn,
                title = title
            )
        )
    }

    fun saveExperiences(experiences: List<WorkExperience>) {
        repository.saveExperiences(experiences)
        loadData()
    }

    fun saveEducation(education: List<Education>) {
        repository.saveEducation(education)
        loadData()
    }

    fun addExperience(title: String, company: String, duration: String, description: String = "") {
        val current = _experiences.value?.toMutableList() ?: mutableListOf()
        current.add(
            WorkExperience(
                UUID.randomUUID().toString(),
                title,
                company,
                duration,
                description,
                skillsUsed = _profile.value?.skills ?: emptyList()
            )
        )
        repository.saveExperiences(current)
        loadData()
    }

    fun addEducation(degree: String, school: String, year: String) {
        val current = _education.value?.toMutableList() ?: mutableListOf()
        current.add(Education(UUID.randomUUID().toString(), degree, school, year))
        repository.saveEducation(current)
        loadData()
    }

    fun addSkill(skill: String) {
        val currentProfile = _profile.value ?: UserProfile()
        if (!currentProfile.skills.contains(skill)) {
            val updatedSkills = currentProfile.skills.toMutableList().apply { add(skill) }
            saveProfile(currentProfile.copy(skills = updatedSkills))
        }
    }

    fun updateResume(fileName: String, uri: Uri) {
        val current = _profile.value ?: UserProfile()
        saveProfile(current.copy(resumeFileName = fileName, resumeUri = uri.toString()))
    }

    fun markProfileSetupCompleted() {
        repository.setProfileSetupCompleted(true)
    }
}
